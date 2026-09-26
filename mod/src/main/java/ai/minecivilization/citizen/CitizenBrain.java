package ai.minecivilization.citizen;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

import com.mojang.logging.LogUtils;
import ai.minecivilization.colony.HomeDestination;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.config.ModConfig;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.network.AiBridge;
import ai.minecivilization.skills.SkillFailure;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * The deterministic citizen brain: GOAL → TASK → SKILL.
 *
 * <p>Cognition requests happen only when meaningful (no goal, goal completed,
 * repeated task failure, periodic reflection) and always asynchronously —
 * Minecraft never waits for the AI service. If the service is offline,
 * citizens finish deterministic work where possible, then select bounded local colony work.</p>
 */
public final class CitizenBrain {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Status { IDLE, WORKING, BLOCKED }

    private CitizenPlan.Goal goal;
    private final ArrayDeque<CitizenPlan.Task> tasks = new ArrayDeque<>();
    private CitizenPlan.Task currentTask;
    private final CitizenEntity self;

    private Status status = Status.IDLE;
    private int consecutiveTaskFailures;
    private long lastDecisionAt = Long.MIN_VALUE;
    private boolean decisionPending;
    private long decisionRequestedAt;
    private int taskAttempts;

    private final ArrayDeque<String> recentEvents = new ArrayDeque<>();
    private final ArrayDeque<String> activeSkillLog = new ArrayDeque<>();

    /** Why this citizen is (or is not) productive — see {@link StuckDetector}. */
    private final StuckDetector stuck = new StuckDetector();
    /** How hard it is currently trying to get home — see {@link RescueLadder}. */
    private final RescueLadder rescue = new RescueLadder();
    /** True while the deterministic rescue owns the citizen. */
    private boolean lost;
    /** The rung of the rescue ladder in use, for inspection and the event feed. */
    private RescueLadder.Step rescueStep = RescueLadder.Step.KNOWN_ROUTE;
    /** The rung last announced, so one walk home is one line in the feed. */
    private RescueLadder.Step announcedStep;
    /** Where the current task is trying to get to, for the status readout. */
    private net.minecraft.core.BlockPos destination;
    /** Why the current plan was chosen — cognition reasoning or a work source. */
    private String decisionReason = "";
    /** Priority band of the current work, when it came from the colony board. */
    private ai.minecivilization.work.WorkPriority workPriority;
    /** The last thing that actually finished, for "is this citizen working?". */
    private String lastCompleted = "";
    private long lastCompletedAt = Long.MIN_VALUE;
    private int completedCount;

    private TaskExecutor.Outcome lastOutcome;
    private long lastTaskStartedAt;
    /**
     * The next plan, fetched while the current one is still running.
     *
     * <p>Asking only once the work runs out means standing still for the whole
     * round trip, every single time. Asking a job early costs nothing and the
     * answer is waiting when it is needed.</p>
     */
    private CitizenPlan queuedPlan;
    private final LocalWorkPlanner localWork = new LocalWorkPlanner();
    /** Ticks between deadlock checks, so breaking one cannot thrash. */
    private static final int DEADLOCK_COOLDOWN = 60;
    /** Deadlocks in a row before the citizen gives up on the area. */
    private static final int DEADLOCKS_BEFORE_RETREAT = 4;
    private long nextLocalWorkAt;
    private long requestGeneration;
    private long decisionRetryAt;
    /** True while a deterministic fallback lease owns the worker. */
    private boolean localPlanActive;

    public CitizenBrain(CitizenEntity self) {
        this.self = self;
    }

    public CitizenPlan.Goal currentGoal() {
        return goal;
    }

    public CitizenPlan.Task currentTaskOrNull() {
        return currentTask;
    }

    public Status status() {
        return status;
    }

    public int consecutiveTaskFailures() {
        return consecutiveTaskFailures;
    }

    // ---------------------------------------------------------------- telemetry

    /** True while the citizen is stranded and the rescue ladder owns it. */
    public boolean isLost() {
        return lost;
    }

    /** True while the citizen is deliberately heading back to the colony. */
    public boolean isWalkingHome() {
        return walkingHome;
    }

    /** Which rescue measure is being attempted. */
    public RescueLadder.Step rescueStep() {
        return rescueStep;
    }

    public int rescueAttempts() {
        return rescue.failures();
    }

    public StuckDetector.Reason stuckReason(long now) {
        return stuck.reason(now);
    }

    /** Ticks this citizen has been in the same place without progress. */
    public long stuckTicks(long now) {
        return Math.max(stuck.stillTicks(now), stuck.idleTicks(now));
    }

    /** Where the current task is heading, or null when it is not going anywhere. */
    public net.minecraft.core.BlockPos destination() {
        return destination;
    }

    /** Why the citizen is doing what it is doing. */
    public String decisionReason() {
        return decisionReason;
    }

    /** Priority band of the current job, or null for cognition-issued work. */
    public ai.minecivilization.work.WorkPriority workPriority() {
        return workPriority;
    }

    public String lastCompleted() {
        return lastCompleted;
    }

    public long lastCompletedAt() {
        return lastCompletedAt;
    }

    /** Tasks this citizen has finished since it spawned — its life's work. */
    public int completedCount() {
        return completedCount;
    }

    /** The next task in the queue, so inspection can say what comes after this. */
    public CitizenPlan.Task nextTaskOrNull() {
        return tasks.peekFirst();
    }

    public int queuedTaskCount() {
        return tasks.size();
    }

    public boolean isDecisionPending() {
        return decisionPending;
    }

    public Iterator<String> recentEvents() {
        return recentEvents.iterator();
    }

    public String currentSkillLabel() {
        if (currentTask == null) return "";
        if (lastOutcome == null || lastOutcome.status == TaskExecutor.Status.RUNNING) {
            return lastOutcome == null ? "" : lastOutcome.activeSkill + " " + lastOutcome.progressLabel;
        }
        return "";
    }

    public void addEvent(String event) {
        recentEvents.addLast(event);
        while (recentEvents.size() > 12) recentEvents.removeFirst();
    }

    // ---------------------------------------------------------------- main tick

    public void tick(ServerLevel level, CitizenEntity self) {
        if (self.isRemoved()) return;
        long now = level.getGameTime();
        TaskExecutor executor = self.getExecutor();

        // Productivity accounting happens first and unconditionally: a citizen
        // that returns early from this method for any reason has still spent
        // the tick, and the whole point of the monitor is that nothing is
        // allowed to be quietly unaccounted for.
        var here = self.blockPosition();
        stuck.sample(now, here.getX(), here.getY(), here.getZ());
        stuck.setHasWork(now, currentTask != null);

        // Standing still is what working looks like. Mining a block, crafting
        // at a table and searching the ground are all done without moving an
        // inch, so position alone reports every productive citizen as stuck —
        // and the deadlock breaker below then took their work away. In one
        // session that was sixty-four interrupted harvests and twenty-five
        // interrupted crafts, which is precisely why nothing was ever mined,
        // gathered or built.
        //
        // The skill's own progress clock is the honest signal: it advances
        // whenever a block is actually broken, placed or finished.
        long skillProgress = executor.skillProgressAt();
        if (skillProgress != Long.MIN_VALUE && skillProgress > lastSkillProgressAt) {
            lastSkillProgressAt = skillProgress;
            stuck.noteProgress(now);
        }

        // Nothing used to act on any of that. The detector reported, the
        // monitor counted, the window said "Stuck 10" — and the ten citizens
        // carried on standing there, because knowing you are stuck is not the
        // same as doing something about it. This is the guarantee: a citizen
        // that has stopped making progress drops what it is doing and takes
        // different work, every time, without exception.
        // Caught in water the navigator cannot get out of (a cave waterfall
        // held half a colony): swim for dry ground, or cut a step and climb.
        boolean wasEscaping = waterEscape.active();
        boolean diggingOut = currentTask != null && currentTask.type == CitizenPlan.TaskType.ESCAPE;
        if (!diggingOut && waterEscape.tick(level, self, now)) {
            if (!wasEscaping) {
                if (destination != null) {
                    ai.minecivilization.navigation.UnreachableMemory.note(destination, now);
                }
                addEvent("swimming out of the water");
                LOGGER.info("[Citizen {}] stuck in water — making for dry ground",
                        self.getName().getString());
                // Out of the water and straight back in, again and again: the
                // only walkable way out of this cave is through the current.
                // Stop walking and cut a staircase to daylight instead.
                if (now - waterEscapeWindowStart > 2400) {
                    waterEscapeWindowStart = now;
                    waterEscapes = 0;
                }
                if (++waterEscapes >= 2 && depthBelowSurface(level, self.blockPosition()) >= 4) {
                    waterEscapes = 0;
                    waterEscape.reset();
                    digOut(level, self, now, "keeps ending up in the water underground");
                    return;
                }
            }
            return;
        }

        // Stranded on top of a pillar: come down before anything else.
        if (climbDownPillar(level, self, now)) return;

        // Nightfall outranks everything the brain might otherwise do: a job
        // finished in the dark is usually a citizen lost in the dark.
        if (nightWatch(level, self, now)) return;

        if (breakDeadlock(level, self, now)) return;

        // expire a cognition request only after the HTTP timeout has had time
        // to deliver its explicit terminal callback.  The bridge now reports
        // 429/503/network failures too, so this is only a crash-proof backstop.
        long decisionTimeout = Math.max(100,
                (ModConfig.AI_TIMEOUT_MS.get() + 999) / 50 + 10);
        if (decisionPending && now - decisionRequestedAt > decisionTimeout) {
            decisionPending = false;
            requestGeneration++;
        }

        // emergency eating between tasks
        if (currentTask == null && self.getHunger() < 6f
                && self.getInventory().firstFoodSlot() >= 0 && self.eatNow()) {
            return;
        }
        // A local maintenance lease is deliberately interruptible: food is a
        // survival checkpoint, not another long task hidden behind a queue.
        if (currentTask != null && localPlanActive && self.getHunger() < 6f
                && self.getInventory().firstFoodSlot() >= 0 && self.eatNow()) {
            return;
        }

        // A citizen that has wandered far from the colony walks home before
        // asking what to do next — an expedition that ends in a decision made
        // three hundred blocks out is how citizens get lost for good.
        if (currentTask == null && returnHomeIfStrayed(level, self)) {
            return;
        }

        if (currentTask == null) {
            advancePlan(level, self, now);
            return;
        }

        TaskExecutor.Outcome outcome = executor.step(level, self);
        lastOutcome = outcome;
        switch (outcome.status) {
            case RUNNING -> {
                status = Status.WORKING;
                self.setDisplayState("WORKING", goal == null ? "" : goalLabel(),
                        currentTask.type + ": " + outcome.progressLabel);
                // Last task of the plan: line the next one up now, so finishing
                // this one leads straight into more work.
                if (tasks.isEmpty() && queuedPlan == null && !decisionPending) {
                    prefetchDecision(level, self, now);
                }
            }
            case COMPLETED -> {
                // Logged at INFO, not DEBUG: with only failures visible, a
                // colony that is working looks identical to one that is stuck.
                // Resting is logged quietly: a hurt citizen resting through the
                // night finishes a short rest every second or two.
                boolean restful = currentTask.type == CitizenPlan.TaskType.REST
                        || currentTask.type == CitizenPlan.TaskType.IDLE;
                if (restful) {
                    LOGGER.debug("[Citizen {}] completed {}", self.getName().getString(), currentTask.type);
                } else {
                    LOGGER.info("[Citizen {}] completed {}{}", self.getName().getString(),
                            currentTask.type,
                            currentTask.resource == null ? "" : " " + currentTask.resource);
                }
                self.onTaskSucceeded(currentTask.type.name());
                AiBridge.postTaskResult(self, currentTask, "COMPLETED", null, null);
                addEvent("completed " + currentTask.type);
                self.getSkills().addXp(skillXpKey(), 0.02f);
                stuck.noteProgress(now);
                if (localPlanActive) {
                    ai.minecivilization.work.GlobalTaskPool.noteSuccess(self);
                }
                // Finishing the instant it started, again and again, is a loop
                // wearing the costume of progress: a job whose target is already
                // met, re-offered forever. Five in a row and it is set aside.
                if (now - lastTaskStartedAt <= 2) {
                    if (++instantCompletions >= 5) {
                        impossible.put(taskKey(currentTask), now + IMPOSSIBLE_TICKS);
                        instantCompletions = 0;
                        LOGGER.info("[Citizen {}] {} keeps finishing before it starts — setting it aside",
                                self.getName().getString(), taskKey(currentTask));
                    }
                } else {
                    instantCompletions = 0;
                }
                lastCompleted = currentTask.type
                        + (currentTask.resource == null ? "" : " " + currentTask.resource);
                lastCompletedAt = now;
                completedCount++;
                // An escape that worked is the end of being lost, whatever the
                // distance home still says: the citizen can walk again.
                if (currentTask.type == CitizenPlan.TaskType.ESCAPE) {
                    rescue.succeeded();
                    lost = false;
                }
                // A walk home that "arrives" the moment it starts went nowhere:
                // treat it as a failed rung so the ladder climbs instead of
                // reissuing the same non-move every tick.
                if (walkingHome && currentTask.type == CitizenPlan.TaskType.MOVE
                        && now - lastTaskStartedAt <= 2) {
                    rescue.failed(now);
                }
                currentTask = null;
                destination = null;
                consecutiveTaskFailures = 0;
                taskAttempts = 0;
            }
            case FAILED -> {
                SkillFailure failure = outcome.failure;
                boolean recoverable = failure == null || failure.recoverable;
                // Every rung of the rescue ladder counts as "going home",
                // including the one that digs, so a failed excavation escalates
                // instead of being reported as an ordinary task failure.
                boolean returningHome = walkingHome && currentTask != null
                        && (currentTask.type == CitizenPlan.TaskType.MOVE
                            || currentTask.type == CitizenPlan.TaskType.ESCAPE);
                if (failure != null) {
                    self.onTaskFailed(failure);
                    stuck.noteFailure(now, failure.code);
                } else {
                    stuck.noteFailure(now, "UNKNOWN");
                }
                // The same failure repeating is one fact, not fifty. A colony
                // looping on an impossible job wrote two lines a second per
                // citizen and buried everything else in the log; after the
                // first couple the repetition itself is the news, and the
                // productivity monitor already reports that.
                if (stuck.repeatedFailures() <= 2) {
                    LOGGER.info("[Citizen {}] task {} failed: {}", self.getName().getString(),
                            currentTask.type, failure);
                } else if (stuck.repeatedFailures() == 3) {
                    LOGGER.info("[Citizen {}] task {} keeps failing ({}) — "
                                    + "further repeats will not be logged",
                            self.getName().getString(), currentTask.type,
                            failure == null ? "?" : failure.code);
                } else {
                    LOGGER.debug("[Citizen {}] task {} failed again: {}",
                            self.getName().getString(), currentTask.type, failure);
                }
                AiBridge.postTaskResult(self, currentTask, "FAILED", failure, null);
                addEvent("failed " + currentTask.type + " [" + (failure == null ? "?" : failure.code) + "]");

                // A walk-home route is already the recovery phase. If it cannot
                // be completed, do not reissue the identical MOVE on the next
                // tick: that produced hundreds of one-second attempts and left
                // the citizen visibly running in place. The policy backs the
                // next attempt off exponentially, and a citizen that made any
                // progress back inside the colony leash resumes normal work.
                if (returningHome) {
                    // A walk home that failed is not a dead end, it is a rung.
                    // The ladder escalates: known route, plain path, waypoint,
                    // local exploration, building a passage, cutting a
                    // staircase to daylight, and finally a straight line. Each
                    // failure buys the next, more physical, answer.
                    consecutiveTaskFailures = 0;
                    taskAttempts = 0;
                    clearPlan("walk home failed; escalating the rescue", true);
                    // The ladder now owns the pacing. HomewardPolicy is kept
                    // for its distance thresholds — when a citizen counts as
                    // home, strayed or merely far — and no longer gates
                    // retries, whose backoff it would only lengthen.
                    rescue.failed(now);
                    lost = true;
                    status = Status.BLOCKED;
                    long retryIn = Math.max(0, rescue.readyAt() - now);
                    self.setDisplayState("BLOCKED", "RETURN_HOME",
                            "attempt " + rescue.failures() + " failed; next: "
                                    + nextRescueLabel(level, self));
                    addEvent("rescue rung " + rescue.failures() + " failed");
                    LOGGER.info("[Citizen {}] walk home failed ({} attempts); "
                                    + "escalating to {} in {}s",
                            self.getName().getString(), rescue.failures(),
                            nextRescueLabel(level, self), retryIn / 20);
                    return;
                }

                localWork.failed(currentTask, now);
                // A courier that could not deliver lets someone else try.
                ai.minecivilization.work.MaterialRequests.release(self.getUUID());
                // Underground and unable to get anywhere: the cave is the
                // problem. Two such failures in two minutes and the citizen
                // digs out instead of walking the same dead ends for ten.
                if (failure != null && "TARGET_UNREACHABLE".equals(failure.code)
                        && currentTask.type != CitizenPlan.TaskType.ESCAPE
                        && currentTask.type != CitizenPlan.TaskType.MINE_SHAFT
                        && depthBelowSurface(level, self.blockPosition()) >= 4) {
                    if (now - caveFailuresSince > 2400) {
                        caveFailuresSince = now;
                        caveFailures = 0;
                    }
                    if (++caveFailures >= 2) {
                        caveFailures = 0;
                        digOut(level, self, now, "cannot find a way out of the cave");
                        return;
                    }
                }
                // An empty hunting ground stays empty for a while. Cognition
                // re-issued the same hunt 1149 times in one afternoon to
                // citizens standing in a valley with no animals; each attempt
                // spent a minute foraging bare grass before timing out.
                if ((currentTask.type == CitizenPlan.TaskType.HUNT
                        || currentTask.type == CitizenPlan.TaskType.HARVEST) && failure != null
                        && ("TARGET_NOT_FOUND".equals(failure.code) || "TIMEOUT".equals(failure.code))) {
                    impossible.put(taskKey(currentTask), now + 6000);
                    clearPlan("nothing to hunt, harvest or forage here", true);
                    status = Status.IDLE;
                    return;
                }
                // A remembered resource that turned out to be unreachable is
                // not a resource: forget it, or the planner hands it straight
                // back (48 identical failures on one spot in a session).
                if (currentTask.position != null && failure != null
                        && "TARGET_UNREACHABLE".equals(failure.code)
                        && currentTask.type != CitizenPlan.TaskType.MOVE) {
                    var spot = new net.minecraft.core.BlockPos(currentTask.position[0],
                            currentTask.position[1], currentTask.position[2]);
                    self.knownResources().values().removeIf(pos -> pos.distSqr(spot) <= 4);
                }
                if (currentTask.type == CitizenPlan.TaskType.COLLECT && currentTask.position != null
                        && failure != null && "TARGET_UNREACHABLE".equals(failure.code)) {
                    ai.minecivilization.work.ColonyWork.noteUnreachableDrop(
                            new net.minecraft.core.BlockPos(currentTask.position[0],
                                    currentTask.position[1], currentTask.position[2]), now);
                    // Nothing to retry: the next offer will be a different item.
                    clearPlan("drop out of reach", true);
                    status = Status.IDLE;
                    return;
                }
                // Tell the board its job did not work out, so it offers this
                // citizen something else rather than the same impossible thing
                // on the very next tick.
                if (localPlanActive) {
                    ai.minecivilization.work.GlobalTaskPool.noteFailure(self, now);
                }
                consecutiveTaskFailures++;
                taskAttempts++;

                // Resource/search failures have already exhausted deterministic recovery.
                // Repeating them immediately cannot grow a crop or invent a missing tool.
                if (failure != null && ("MISSING_RESOURCE".equals(failure.code)
                        || "TARGET_NOT_FOUND".equals(failure.code))) {
                    // Remember it, so the same plan is not adopted again the
                    // moment it comes back from cognition.
                    impossible.put(taskKey(currentTask), now + IMPOSSIBLE_TICKS);
                    clearPlan("prerequisite unavailable; choose different work", true);
                    status = Status.IDLE;
                    return;
                }
                if (!recoverable) {
                    clearPlan("fatal task failure", true);
                    status = Status.BLOCKED;
                    self.setDisplayState("BLOCKED", goalLabel(), failure == null ? "" : failure.code);
                    return;
                }
                if (consecutiveTaskFailures >= 3) {
                    // deterministic layer already tried simple recovery; ask for a new decision
                    LOGGER.info("[Citizen {}] goal disengaged after {} failures",
                            self.getName().getString(), consecutiveTaskFailures);
                    // Three strikes: keep the same job off this citizen for a
                    // while, or the queued copy of it is adopted on the next tick.
                    impossible.put(taskKey(currentTask), now + IMPOSSIBLE_TICKS);
                    clearPlan("repeated task failures", true);
                    status = Status.IDLE;
                    requestDecision(level, self, "task_failed", "HIGH", now);
                    return;
                }
                // retry the same task deterministically once or twice
                self.getExecutor().reset(currentTask);
                if (consecutiveTaskFailures >= 2) {
                    requestDecision(level, self, "task_failed", "HIGH", now);
                }
            }
        }
    }

    private boolean walkingHome;
    private final HomewardPolicy homeward = new HomewardPolicy();

    /** True while the current plan is the evening routine (walk home, wall in). */
    private boolean nightPlan;
    /** The night whose dusk walk home has already been tried — it is tried once. */
    private long homewardNight = Long.MIN_VALUE;
    /** The night last announced in the log: one line per citizen per night. */
    private long announcedNight = Long.MIN_VALUE;

    /** True while the citizen is following its evening routine. */
    public boolean isNightRoutine() {
        return nightPlan;
    }

    /**
     * Stop work at dusk, get home if it is close, and wall in for the night.
     *
     * <p>A citizen used to work straight through sunset and be a hundred blocks
     * out felling trees when the zombies rose; seven of twelve died in one
     * colony's first three minutes of darkness. This is the player's first-night
     * routine, and it outranks every job: at dusk the citizen drops what it is
     * doing, walks home if home is near enough to reach before dark, and then
     * puts four walls and a roof around itself until morning. At dawn it takes
     * the walls back down and goes back to work.</p>
     *
     * @return true when the routine took over this tick
     */
    private boolean nightWatch(ServerLevel level, CitizenEntity self, long now) {
        long dayTime = level.getDayTime();
        NightPolicy.Phase phase = NightPolicy.phase(dayTime, level.isThundering());
        boolean sheltering = currentTask != null
                && currentTask.type == CitizenPlan.TaskType.SHELTER;

        // Walls left from the nights citizens used to spend shut in: take them
        // back first. They are building material, and a field of abandoned
        // cells is litter.
        if (currentTask == null && !sheltering && !self.shelterBlocks().isEmpty()) {
            beginNightRoutine(level, self, List.of(new CitizenPlan.Task(
                    CitizenPlan.TaskType.SHELTER, null, 1, "dismantle", null, null, null)),
                    "taking an old night shelter down");
            return true;
        }
        if (phase == NightPolicy.Phase.DAY) {
            nightPlan = false;
            return false;
        }
        // The night is for working carefully, not for hiding: citizens keep at
        // their jobs after dark. What changes is where and who. The walk home
        // (below) keeps them near the colony, the work board stops sending
        // anyone over the horizon, and combat handles what comes.
        if (nightPlan && currentTask != null) return false;

        long night = NightPolicy.nightIndex(dayTime);
        net.minecraft.core.BlockPos home = HomeDestination.forCitizen(level, self);
        if (home == null) home = ZoneManager.get(level).townCenter(level);
        double distance = Math.sqrt(home.distSqr(self.blockPosition()));
        boolean vulnerable = !self.isArmed()
                || self.getHealth() < self.getMaxHealth() * NightPolicy.RETREAT_HEALTH;

        // Far out at dusk: come back towards the colony, once, and carry on
        // working there.
        if (NightPolicy.walkHome(phase, distance) && homewardNight != night) {
            homewardNight = night;
            String why = "dusk: heading back to the colony (" + (int) distance + " blocks)";
            announce(self, night, why);
            beginNightRoutine(level, self, List.of(move(home, false)), why);
            return true;
        }
        // Unarmed or hurt in the dark: no work is worth it. Go home and rest
        // near the others until healed or morning.
        if (phase == NightPolicy.Phase.NIGHT && vulnerable && currentTask == null) {
            String why;
            CitizenPlan.Task task;
            if (distance > NightPolicy.HOME_RADIUS) {
                why = "night: too hurt or unarmed to work in the dark — going home";
                task = move(home, false);
            } else {
                why = "night: resting at home until healed or morning";
                task = new CitizenPlan.Task(CitizenPlan.TaskType.REST, null, 1, null, null, null, null);
            }
            announce(self, night, why);
            beginNightRoutine(level, self, List.of(task), why);
            return true;
        }
        return false;
    }

    private void announce(CitizenEntity self, long night, String why) {
        if (announcedNight == night) return;
        announcedNight = night;
        LOGGER.info("[Citizen {}] {}", self.getName().getString(), why);
    }

    private void beginNightRoutine(ServerLevel level, CitizenEntity self,
                                   List<CitizenPlan.Task> routine, String why) {
        // Keep whatever cognition had lined up: it is still the plan for tomorrow.
        clearPlan(why, true);
        walkingHome = false;
        lost = false;
        announcedStep = null;
        rescue.succeeded();
        consecutiveTaskFailures = 0;
        taskAttempts = 0;
        nightPlan = true;
        goal = new CitizenPlan.Goal(CitizenPlan.GoalType.REST, null, -1, null, why);
        tasks.addAll(routine);
        currentTask = tasks.pollFirst();
        destination = currentTask.position == null ? null
                : new net.minecraft.core.BlockPos(currentTask.position[0],
                        currentTask.position[1], currentTask.position[2]);
        self.getExecutor().reset(currentTask);
        lastSkillProgressAt = Long.MIN_VALUE;
        lastTaskStartedAt = level.getGameTime();
        stuck.reset(level.getGameTime());
        decisionReason = why;
        workPriority = ai.minecivilization.work.WorkPriority.SURVIVAL;
        status = Status.WORKING;
        self.setDisplayState("WORKING", "SURVIVE_NIGHT", currentTask.type.toString());
        addEvent(why);
    }

    private long nextPillarStepAt;
    private final WaterEscape waterEscape = new WaterEscape();
    /** Unreachable failures while underground, and when the count started. */
    private int caveFailures;
    private long caveFailuresSince;

    /** Stop trying to walk out of a cave and cut a staircase to daylight instead. */
    private void digOut(ServerLevel level, CitizenEntity self, long now, String why) {
        net.minecraft.core.BlockPos home = HomeDestination.forCitizen(level, self);
        if (home == null) home = ZoneManager.get(level).townCenter(level);
        clearPlan(why + " — digging out", true);
        currentTask = new CitizenPlan.Task(CitizenPlan.TaskType.ESCAPE, null, 1,
                null, null, null, new int[]{home.getX(), home.getY(), home.getZ()});
        self.getExecutor().reset(currentTask);
        lastTaskStartedAt = now;
        status = Status.WORKING;
        decisionReason = why;
        addEvent("digging a staircase to the surface");
        LOGGER.info("[Citizen {}] {} — digging a staircase out", self.getName().getString(), why);
    }
    private int waterEscapes;
    private long waterEscapeWindowStart;
    /** Tasks in a row that completed on the tick they started. */
    private int instantCompletions;

    /**
     * Dig down a pillar the citizen is stranded on.
     *
     * <p>Climbing towards a log in a treetop leaves a one-block column of dirt
     * with the citizen on top, and the record of which blocks it placed is not
     * kept across a reload. From up there every route home starts with a drop
     * too long to take, so the planner found nothing and the rescue ladder
     * failed rung after rung while the citizen stood in the canopy. The answer
     * a player would give: dig the block you are standing on, drop one, repeat.</p>
     *
     * @return true while it is climbing down
     */
    private boolean climbDownPillar(ServerLevel level, CitizenEntity self, long now) {
        if (now < nextPillarStepAt) return currentTask == null && nextPillarStepAt - now < 10;
        boolean idleOrLost = currentTask == null || lost || walkingHome
                || stuck.repeatedFailures() >= 2;
        if (!idleOrLost || !self.onGround()) return false;
        var feet = self.blockPosition();
        var under = feet.below();
        var state = level.getBlockState(under);
        String id = ai.minecivilization.inventory.CitizenInventory.idOf(new net.minecraft.world.item.ItemStack(state.getBlock().asItem()));
        boolean cheap = ai.minecivilization.navigation.ScaffoldMaterial.isExpendable(id)
                || state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
        if (!cheap || level.getBlockEntity(under) != null
                || ai.minecivilization.construction.ConstructionManager.get(level).protectsCell(under)) {
            return false;
        }
        // A column top: nothing beside the block at its own level, so every
        // step off it is a drop. (Only for a citizen that is idle, lost or
        // failing — a worker on its own bridge is left alone.)
        for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var beside = under.relative(side);
            if (!level.getBlockState(beside).getCollisionShape(level, beside).isEmpty()) return false;
        }
        // A floating block (placed to stand on, the column under it since
        // mined) is still a way down — as long as the drop is a short one, or
        // ends in water.
        int drop = 0;
        var probe = under.below();
        while (drop < 16 && level.getBlockState(probe).getCollisionShape(level, probe).isEmpty()
                && level.getFluidState(probe).isEmpty()) {
            drop++;
            probe = probe.below();
        }
        // Never down into water: a pillar standing in a pool is how citizens got
        // back into the current they had just been pulled out of.
        if (!level.getFluidState(probe).isEmpty()) return false;
        // At most three hearts of fall damage, and only with health to spare.
        if (drop > 6 || self.getHealth() < 20f) return false;
        if (currentTask != null) clearPlan("climbing down a pillar", true);
        var item = new net.minecraft.world.item.ItemStack(state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                ? net.minecraft.world.item.Items.DIRT : state.getBlock().asItem());
        level.removeBlock(under, false);
        if (self.getInventory().insert(item) > 0) {
            net.minecraft.world.level.block.Block.popResource(level, under, item);
        }
        self.forgetScaffold(under);
        self.setBracedPlacement(false);
        self.setTraversalSneak(false);
        self.animateAction(ai.minecivilization.entity.WorkAnimation.MINE, under);
        nextPillarStepAt = now + 8;
        stuck.reset(now);
        addEvent("climbing down a pillar");
        return true;
    }

    /** Where the current stretch of travel started, and when. */
    private net.minecraft.core.BlockPos travelAnchor;
    private long travelAnchorAt;
    /** Travel that has not left a two-block radius in this long is stuck. */
    private static final int GOING_NOWHERE_TICKS = 900;

    /**
     * Movement that never moves.
     *
     * <p>The other stuck checks trust the running skill's progress clock, and
     * a traversal that replans refreshes that clock every time — so a citizen
     * could "make a way" on the same spot for an hour and look healthy. This
     * one only looks at where the body is: walking or traversing for
     * forty-five seconds without getting two blocks from where it started is
     * stuck, whatever the skill believes.</p>
     */
    private boolean goingNowhere(CitizenEntity self, long now) {
        var skill = self.getExecutor().activeSkillType();
        boolean travelling = currentTask != null && !walkingHome && !lost
                && (skill == ai.minecivilization.skills.SkillType.MOVE_TO
                    || skill == ai.minecivilization.skills.SkillType.TRAVERSE);
        var here = self.blockPosition();
        if (!travelling || travelAnchor == null || travelAnchor.distSqr(here) > 4) {
            travelAnchor = travelling ? here : null;
            travelAnchorAt = now;
            return false;
        }
        return now - travelAnchorAt >= GOING_NOWHERE_TICKS;
    }

    /** Deadlocks broken in a row, so repeated ones escalate. */
    private int deadlocks;
    private long nextDeadlockCheckAt;
    /** Last seen value of the running skill's progress clock. */
    private long lastSkillProgressAt = Long.MIN_VALUE;

    /** When the running skill last did something real, or long ago if never. */
    private static long executorProgressAt(CitizenEntity self) {
        long at = self.getExecutor().skillProgressAt();
        return at == Long.MIN_VALUE ? Long.MIN_VALUE / 2 : at;
    }

    /**
     * Drop a job that has stopped going anywhere and take another.
     *
     * <p>Three things count as stopped: the same failure three times running,
     * half a minute stationary while holding a task, and two minutes without
     * completing anything. All three mean the same thing in practice — this
     * citizen is not going to finish this, and there is always something else
     * it could be doing.</p>
     *
     * <p>The source that handed out the job is benched for this citizen, so
     * the board offers something different rather than the same impossible
     * thing one tick later. After several in a row the citizen gives up on the
     * area entirely and heads home, which is the only move guaranteed to
     * change its situation.</p>
     *
     * @return true when the citizen was freed this tick
     */
    private boolean breakDeadlock(ServerLevel level, CitizenEntity self, long now) {
        if (now < nextDeadlockCheckAt) return false;
        if (goingNowhere(self, now)) {
            // The destination is the problem: mark it so the next search picks
            // a different tree, block or table instead of the same one.
            if (destination != null) {
                ai.minecivilization.navigation.UnreachableMemory.note(destination, now);
            }
            var target = self.getNavigator().currentTarget();
            if (target != null) ai.minecivilization.navigation.UnreachableMemory.note(target, now);
            travelAnchor = null;
            deadlocks++;
            nextDeadlockCheckAt = now + DEADLOCK_COOLDOWN;
            String abandoned = currentTask == null ? "nothing" : currentTask.type.name();
            ai.minecivilization.work.GlobalTaskPool.noteFailure(self, now);
            if (currentTask != null) localWork.failed(currentTask, now);
            clearPlan("travelling without getting anywhere", false);
            self.setBracedPlacement(false);
            self.setTraversalSneak(false);
            self.setControlledDrop(false);
            stuck.reset(now);
            status = Status.IDLE;
            nextLocalWorkAt = 0;
            addEvent("dropped " + abandoned + " (going nowhere)");
            LOGGER.info("[Citizen {}] dropped {} — {}s of travel without leaving the spot",
                    self.getName().getString(), abandoned, GOING_NOWHERE_TICKS / 20);
            return false;
        }
        StuckDetector.Reason reason = stuck.reason(now);
        if (reason == StuckDetector.Reason.NONE) {
            deadlocks = 0;
            return false;
        }
        // A citizen with no work is not deadlocked; it is between jobs, and
        // the ordinary path below is what finds it one.
        if (reason == StuckDetector.Reason.NO_WORK && currentTask == null) return false;

        // Standing still is not evidence of anything. Mining, crafting,
        // searching and smelting are all done without moving, and treating
        // stillness as a stall took work away from sixty citizens an hour —
        // which is exactly why nothing was ever mined or gathered. The two
        // honest signals are the same failure coming back again and again, and
        // nothing at all being finished for two solid minutes. Both of those
        // mean it regardless of whether the citizen is moving.
        if (reason == StuckDetector.Reason.NOT_MOVING
                && now - executorProgressAt(self) < StuckDetector.UNPRODUCTIVE_TICKS) {
            return false;
        }
        // Being rescued is allowed to look stuck: that ladder has its own
        // escalation and interrupting it would restart the rescue.
        if (walkingHome || lost) return false;
        // Nor is sitting out the night in a shelter a deadlock.
        if (currentTask != null && currentTask.type == CitizenPlan.TaskType.SHELTER) return false;

        deadlocks++;
        nextDeadlockCheckAt = now + DEADLOCK_COOLDOWN;

        String abandoned = currentTask == null ? "nothing" : currentTask.type.name();
        ai.minecivilization.work.GlobalTaskPool.noteFailure(self, now);
        ai.minecivilization.work.GlobalTaskPool.release(self);
        if (currentTask != null) {
            localWork.failed(currentTask, now);
            // Whatever could not be finished here is not offered straight back.
            impossible.put(taskKey(currentTask), now
                    + (currentTask.type == CitizenPlan.TaskType.HUNT ? 6000 : IMPOSSIBLE_TICKS));
        }
        clearPlan("deadlock: " + reason, false);
        // Whatever pinned it — a braced pose on an old pillar, a sneak left on
        // by a cancelled traversal — must not outlive the job it was for.
        self.setBracedPlacement(false);
        self.setTraversalSneak(false);
        self.setControlledDrop(false);
        stuck.reset(now);
        consecutiveTaskFailures = 0;
        taskAttempts = 0;
        status = Status.IDLE;
        addEvent("dropped " + abandoned + " (" + reason + ")");
        LOGGER.info("[Citizen {}] dropped {} after {} — taking different work",
                self.getName().getString(), abandoned, reason);

        // Repeatedly stuck in the same spot: the area is the problem, not the
        // job. Walking home is the one move that always changes the situation.
        if (deadlocks >= DEADLOCKS_BEFORE_RETREAT) {
            deadlocks = 0;
            ai.minecivilization.telemetry.ColonyEventLog.of(level).rescue(level,
                    self.getIdentity().name,
                    "could not get anything done here and is heading back");
            forceRescue(level, self);
            return true;
        }
        // Ask for fresh work immediately rather than waiting for the next
        // scheduled look at the board.
        nextLocalWorkAt = 0;
        return false;
    }

    /**
     * Get the citizen home, by whatever means the situation demands.
     *
     * <p>The old version had exactly one idea — issue a walk, wait longer when
     * it failed, issue the same walk again — which is why a citizen at the
     * bottom of a ravine stayed there. This walks the {@link RescueLadder}
     * instead: each failure buys a more physical answer, ending in cutting a
     * staircase to daylight, which cannot fail for any reason the world can
     * produce.</p>
     *
     * @return true when the rescue owns this tick
     */
    private boolean returnHomeIfStrayed(ServerLevel level, CitizenEntity self) {
        long now = level.getGameTime();
        net.minecraft.core.BlockPos centre = ZoneManager.get(level).townCenter(level);
        net.minecraft.core.BlockPos at = self.blockPosition();
        double distance = Math.sqrt(centre.distSqr(at));
        HomewardPolicy.Action action = homeward.evaluate(distance, now);

        // Buried counts as stranded even from inside the colony: a citizen that
        // fell into a cave under its own town hall is exactly as stuck as one
        // lost in the wilderness, and used to be handled by nothing at all.
        //
        // Only sustained trouble counts, though. A miner working the colony's
        // shaft stands still for a long time on purpose and is regularly
        // between tasks a hundred blocks down — reading that as "trapped"
        // would have the rescue drag every miner back to the surface, which is
        // both wrong and the opposite of productive.
        int depth = depthBelowSurface(level, at);
        StuckDetector.Reason reason = stuck.reason(now);
        boolean trapped = depth >= RescueLadder.DEEP_UNDERGROUND
                && (reason == StuckDetector.Reason.REPEATED_FAILURE
                    || reason == StuckDetector.Reason.UNPRODUCTIVE);

        if (action == HomewardPolicy.Action.AT_HOME && !trapped) {
            if (walkingHome) {
                addEvent("back at the colony");
                ai.minecivilization.telemetry.ColonyEventLog.of(level)
                        .rescue(level, self.getIdentity().name, "made it back to the colony");
            }
            walkingHome = false;
            lost = false;
            announcedStep = null;
            rescue.succeeded();
            return false;
        }
        if (action == HomewardPolicy.Action.WITHIN_LEASH && !trapped) {
            walkingHome = false;
            lost = false;
            announcedStep = null;
            rescue.succeeded();
            return false;
        }
        if (!rescue.ready(now)) {
            // Between attempts. A real state, not an invitation to pick up a
            // new job that walks the citizen further away.
            status = Status.BLOCKED;
            long retryIn = Math.max(0, rescue.readyAt() - now);
            self.setDisplayState("BLOCKED", "RETURN_HOME",
                    "next rescue attempt in " + (retryIn / 20) + "s");
            return true;
        }
        if (walkingHome && currentTask != null) return false;

        net.minecraft.core.BlockPos home = HomeDestination.forCitizen(level, self);
        if (home == null) home = centre;

        boolean hasRoute = ai.minecivilization.roads.PathMemory.get(level)
                .waypointToward(at, home) != null;
        RescueLadder.Step step = rescue.next(now, depth, hasRoute);
        rescueStep = step;

        CitizenPlan.Task task = rescueTask(level, self, step, at, home);
        if (task == null) {
            rescue.failed(now);
            return true;
        }

        // Worth telling the player about when it is the start of a journey home
        // or an escalation, but not for every waypoint of a walk in progress.
        boolean announceRescue = !walkingHome || step != announcedStep;
        announcedStep = step;

        walkingHome = true;
        lost = rescue.failures() > 0;
        consecutiveTaskFailures = 0;
        taskAttempts = 0;
        clearPlan("heading back to the colony");
        currentTask = task;
        destination = task.position == null ? home
                : new net.minecraft.core.BlockPos(task.position[0], task.position[1],
                        task.position[2]);
        decisionReason = "rescue: " + label(step);
        workPriority = ai.minecivilization.work.WorkPriority.RETURN_HOME;
        self.getExecutor().reset(currentTask);
        status = Status.WORKING;
        self.setDisplayState("WORKING", "RETURN_HOME",
                label(step) + " (" + (int) distance + " blocks out, y=" + at.getY() + ")");
        addEvent("rescue: " + label(step));
        // Only announce real trouble. A citizen following a road home takes one
        // hop per waypoint, and reporting each of them would bury the feed
        // under what is, from the outside, an ordinary walk.
        if (announceRescue) {
            ai.minecivilization.telemetry.ColonyEventLog.of(level).rescue(level,
                    self.getIdentity().name,
                    "is " + (int) distance + " blocks from the colony at y=" + at.getY()
                            + " — " + label(step));
        }
        LOGGER.info("[Citizen {}] rescue rung {} ({}) — {} blocks out at y={}, heading to {},{},{}",
                self.getName().getString(), rescue.failures() + 1, step,
                (int) distance, at.getY(),
                destination.getX(), destination.getY(), destination.getZ());
        return true;
    }

    /**
     * Turn a rung of the ladder into a task.
     *
     * <p>Every rung produces an ordinary task, which is what keeps the rescue
     * generic: no special execution path, no skill that only rescues, and the
     * same failure handling as any other work.</p>
     */
    private CitizenPlan.Task rescueTask(ServerLevel level, CitizenEntity self,
                                        RescueLadder.Step step,
                                        net.minecraft.core.BlockPos at,
                                        net.minecraft.core.BlockPos home) {
        return switch (step) {
            case KNOWN_ROUTE -> {
                var waypoint = ai.minecivilization.roads.PathMemory.get(level)
                        .waypointToward(at, home);
                yield waypoint == null ? move(home, false) : move(waypoint, false);
            }
            case DIRECT_PATH -> move(home, false);
            // Half the distance is a target ordinary pathfinding can usually
            // see, where the full journey is beyond its search box.
            case WAYPOINT -> {
                int[] aim = ai.minecivilization.navigation.TravelLeg.aim(
                        at.getX(), at.getZ(), home.getX(), home.getZ(), 48);
                int y = level.isLoaded(new net.minecraft.core.BlockPos(aim[0], at.getY(), aim[1]))
                        ? level.getHeight(
                                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                aim[0], aim[1])
                        : at.getY();   // unloaded heightmaps read as the bottom of the world
                yield move(new net.minecraft.core.BlockPos(aim[0], y, aim[1]), false);
            }
            case LOCAL_EXPLORE -> {
                var open = openGroundToward(level, self, at, home);
                yield open == null ? move(home, true) : move(open, false);
            }
            case BUILD_PASSAGE, BEELINE -> move(home, true);
            case DIG_TO_SURFACE -> new CitizenPlan.Task(CitizenPlan.TaskType.ESCAPE,
                    null, 1, null, null, null,
                    new int[]{home.getX(), home.getY(), home.getZ()});
        };
    }

    private static CitizenPlan.Task move(net.minecraft.core.BlockPos to, boolean forceTraverse) {
        return new CitizenPlan.Task(CitizenPlan.TaskType.MOVE, null, -1,
                forceTraverse ? "traverse" : null, null, null,
                new int[]{to.getX(), to.getY(), to.getZ()});
    }

    /**
     * Somewhere standable, nearer home than here, that the citizen can see.
     *
     * <p>The point of this rung is to get off whatever ledge, pit or cave floor
     * is confusing the pathfinder, from where the ordinary route may work.</p>
     */
    private static net.minecraft.core.BlockPos openGroundToward(
            ServerLevel level, CitizenEntity self,
            net.minecraft.core.BlockPos at, net.minecraft.core.BlockPos home) {
        double best = at.distSqr(home);
        net.minecraft.core.BlockPos found = null;
        for (int radius = 4; radius <= 16; radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 2) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -4; dy <= 4; dy++) {
                        var candidate = at.offset(dx, dy, dz);
                        if (!level.isLoaded(candidate)) continue;
                        double d = candidate.distSqr(home);
                        if (d >= best) continue;
                        if (!ai.minecivilization.navigation.PlacementSafety
                                .canStand(level, self, candidate)) continue;
                        best = d;
                        found = candidate;
                    }
                }
            }
        }
        return found;
    }

    /** Blocks between the citizen and open sky, or 0 on the surface. */
    private static int depthBelowSurface(ServerLevel level, net.minecraft.core.BlockPos at) {
        if (level.canSeeSky(at.above())) return 0;
        int surface = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                at.getX(), at.getZ());
        return Math.max(0, surface - at.getY());
    }

    private static String label(RescueLadder.Step step) {
        return switch (step) {
            case KNOWN_ROUTE -> "following a known route home";
            case DIRECT_PATH -> "walking home";
            case WAYPOINT -> "heading for a waypoint on the way home";
            case LOCAL_EXPLORE -> "looking for open ground";
            case BUILD_PASSAGE -> "building a passage home";
            case DIG_TO_SURFACE -> "digging a staircase to the surface";
            case BEELINE -> "cutting a straight line home";
        };
    }

    /** The rung that would be tried next, for logs and the status line. */
    private String nextRescueLabel(ServerLevel level, CitizenEntity self) {
        var at = self.blockPosition();
        boolean hasRoute = ai.minecivilization.roads.PathMemory.get(level)
                .waypointToward(at, ZoneManager.get(level).townCenter(level)) != null;
        return label(RescueLadder.stepFor(rescue.failures(),
                depthBelowSurface(level, at), hasRoute));
    }

    private void advancePlan(ServerLevel level, CitizenEntity self, long now) {
        // An interrupted livestock job must not leave its worker trapped behind
        // a closed gate when the next job is outside the pasture.
        for (var pen : ai.minecivilization.livestock.Pens.all(level)) {
            if (ai.minecivilization.livestock.Pens.intact(level, pen)
                    && ai.minecivilization.livestock.Pens.interior(pen).contains(self.position())) {
                tasks.addFirst(new CitizenPlan.Task(CitizenPlan.TaskType.TEND_LIVESTOCK,
                    null, 1, "EXIT", null, null, null));
                break;
            }
        }
        if (!tasks.isEmpty()) {
            currentTask = tasks.pollFirst();
            self.getExecutor().reset(currentTask);
            lastSkillProgressAt = Long.MIN_VALUE;
            destination = currentTask.position == null ? null
                    : new net.minecraft.core.BlockPos(currentTask.position[0],
                            currentTask.position[1], currentTask.position[2]);
            lastTaskStartedAt = now;
            taskAttempts = 0;
            status = Status.WORKING;
            // Only worth a line when it is not simply the goal restated: a
            // one-task plan already logged what it is about to do.
            if (tasks.size() > 0 || stuck.repeatedFailures() == 0) {
                LOGGER.debug("[Citizen {}] starts {}{}", self.getName().getString(),
                        currentTask.type,
                        currentTask.resource == null ? "" : " " + currentTask.resource);
            }
            AiBridge.postTaskResult(self, currentTask, "IN_PROGRESS", null, null);
            addEvent("started " + currentTask.type);
            self.setDisplayState("WORKING", goalLabel(), currentTask.type.toString());
            return;
        }
        if (goal == null && queuedPlan != null) {
            CitizenPlan next = queuedPlan;
            queuedPlan = null;
            if (boardFirst(self)) return;
            adoptPlan(self, next);
            return;
        }
        if (goal != null) {
            LOGGER.info("[Citizen {}] Goal completed: {}", self.getName().getString(), goalLabel());
            addEvent("goal completed: " + goalLabel());
            // Finishing a board job means letting go of its claim. Waiting for
            // the claim's timeout instead would keep a completed job reserved
            // for a full minute, during which nobody else may take it — the
            // exact starvation the board exists to prevent.
            if (localPlanActive) {
                ai.minecivilization.work.GlobalTaskPool.release(self);
                localPlanActive = false;
            }
            // The answer we asked for early is already here: no pause at all.
            if (queuedPlan != null) {
                CitizenPlan next = queuedPlan;
                queuedPlan = null;
                goal = null;
                adoptPlan(self, next);
                return;
            }
            AiBridge.postEvent(self, "TASK_COMPLETED", com.google.gson.JsonParser
                    .parseString("{\"goal\":\"" + goalLabel() + "\"}").getAsJsonObject());
            goal = null;
            status = Status.IDLE;
            self.setDisplayState("IDLE", "", "");
            requestDecision(level, self, "goal_completed", "NORMAL", now);
            return;
        }
        status = status == Status.BLOCKED ? Status.IDLE : status;
        self.setDisplayState(status.name(), "", "");
        String reason = now - lastDecisionAt > 6000 ? "periodic" : "no_goal";
        requestDecision(level, self, reason, "NORMAL", now);

        // Local work is a fallback, not a race with cognition. At world load
        // every citizen used to choose husbandry/lighting in the same tick and
        // stampede one wolf or one torch job. Let a healthy service answer
        // first; use local work immediately only when cognition is disabled,
        // already in circuit-breaker mode, or has not produced a plan.
        boolean waitingForCognition = ModConfig.AI_ENABLED.get()
                && (decisionPending
                || (!self.isRegisteredWithService() && !AiBridge.isDegraded()));
        // Never let a slow cognition round-trip turn into a visible idle
        // second. Give cognition a short head start, then let deterministic
        // colony work take over until the next decision boundary.
        if (waitingForCognition && now - decisionRequestedAt < 10) return;
        if (now < nextLocalWorkAt) return;

        nextLocalWorkAt = now + 5;

        // The colony's own work board comes first. It ranks jobs by urgency and
        // hands out claims, which is what stops six citizens converging on the
        // same wolf, torch spot or half-built wall — the single most visible
        // symptom of a colony with no dispatcher.
        var offer = ai.minecivilization.work.GlobalTaskPool.claim(level, self);
        if (offer != null && isImpossible(offer.toPlan(), now)) {
            // The board offered the very job this citizen just set aside. Bench
            // that source for it and look again next time round.
            ai.minecivilization.work.GlobalTaskPool.noteFailure(self, now);
            ai.minecivilization.work.GlobalTaskPool.release(self);
            offer = null;
        }
        if (offer != null) {
            workPriority = offer.priority();
            decisionReason = offer.reason();
            // Keep the cognition request alive: its response is queued by
            // applyPlan while this bounded job runs, then adopted at the next
            // safe task boundary.
            adoptPlan(self, offer.toPlan(), true);
            return;
        }

        CitizenPlan local = localWork.next(level, self);
        if (local != null && !isImpossible(local, now)) {
            workPriority = ai.minecivilization.work.WorkPriority.RESOURCES;
            decisionReason = local.reasoningSummary;
            adoptPlan(self, local, true);
        }
    }

    // ---------------------------------------------------------------- cognition

    public void requestDecision(ServerLevel level, CitizenEntity self, String reason,
                                String priority, long now) {
        if (!ModConfig.AI_ENABLED.get()) return;
        if (now < decisionRetryAt) return;
        if (decisionPending) return;
        if (lastDecisionAt != Long.MIN_VALUE && now - lastDecisionAt < 100 && !"forced".equals(reason)) return;
        if (!self.isRegisteredWithService()) {
            lastDecisionAt = now;
            // decisions require a registered citizen (404 otherwise); retry registration instead
            AiBridge.registerCitizen(self);
            return;
        }
        // A citizen with nothing to do is not a citizen to throttle. The
        // cooldown exists to stop periodic reflection hammering the service,
        // not to make someone stand still for twenty seconds between jobs —
        // which is exactly what it was doing: more than half of all citizen
        // time was spent with no goal at all, waiting for permission to ask
        // for one.
        if (!isIdleReason(reason)) {
            long cooldown = ModConfig.DECISION_COOLDOWN_TICKS.get();
            if (lastDecisionAt != Long.MIN_VALUE && now - lastDecisionAt < cooldown) {
                return;
            }
        }
        if (AiBridge.shouldSkip()) {
            // circuit open: cheap local cooldown instead of hammering a dead service
            lastDecisionAt = now;
            return;
        }
        String observation = ObservationBuilder.build(self, reason, this);
        self.setLastObservation(observation);
        decisionPending = true;
        decisionRequestedAt = now;
        lastDecisionAt = now;
        long generation = ++requestGeneration;
        AiBridge.requestDecision(self, observation, priority, reason, plan -> {
            if (generation != requestGeneration) return;
            // executed on the Minecraft server thread by AiBridge.drain()
            applyPlan(self, plan, reason);
        });
        LOGGER.debug("[Cognition] {} queued priority={} reason={}",
                self.getName().getString(), priority, reason);
    }

    /**
     * Reasons that mean "this citizen has nothing to do right now".
     *
     * <p>These skip the cooldown: standing idle is never the cheaper option,
     * and a decision the citizen is waiting on is worth asking for at once.</p>
     */
    private static boolean isIdleReason(String reason) {
        return "forced".equals(reason)
                || "goal_completed".equals(reason)
                || "no_goal".equals(reason)
                || "task_failed".equals(reason);
    }

    /**
     * Ask for the next job while the current one is still being done.
     *
     * <p>Deliberately low priority and never forced: this is opportunistic, and
     * a colony under pressure should answer the citizens who are actually stuck
     * before the ones merely looking ahead.</p>
     */
    private void prefetchDecision(ServerLevel level, CitizenEntity self, long now) {
        if (!ModConfig.AI_ENABLED.get()) return;
        if (!self.isRegisteredWithService() || AiBridge.shouldSkip()) return;
        if (now < decisionRetryAt) return;

        if (lastDecisionAt != Long.MIN_VALUE && now - lastDecisionAt < 100) return;
        decisionPending = true;
        decisionRequestedAt = now;
        lastDecisionAt = now;
        long generation = ++requestGeneration;
        // Its own reason, not "periodic": the policy continues the current goal
        // on a periodic check, which would have the citizen queue up the very
        // job it is already finishing.
        String observation = ObservationBuilder.build(self, "prefetch", this);
        AiBridge.requestDecision(self, observation, "LOW", "prefetch", plan -> {
            if (generation != requestGeneration) return;
            decisionPending = false;
            if (plan == null || plan.goal == null) {
                decisionRetryAt = self.level().getGameTime() + 20;
            } else {
                decisionRetryAt = 0;
            }
            if (plan != null && plan.goal != null
                    && !isImpossible(plan, self.level().getGameTime())) {
                queuedPlan = plan;
            }
        });
    }

    /**
     * The colony's own urgent work before a suggestion from the AI service.
     *
     * <p>The service knows one citizen's pack; the work board knows the colony
     * — who asked for planks, which crop is ripe, which home needs a bed, where
     * the herd was seen. Letting every service answer through meant the board's
     * priorities were skipped most of the time: couriers never ran, herds were
     * never fetched, homes waited while citizens made torches. Anything the
     * board ranks at construction or above now goes first; the service keeps
     * everything below that.</p>
     *
     * @return true when a board job was adopted instead
     */
    private boolean boardFirst(CitizenEntity self) {
        if (!(self.level() instanceof ServerLevel level)) return false;
        var offer = ai.minecivilization.work.GlobalTaskPool.claim(level, self,
                ai.minecivilization.work.WorkPriority.CONSTRUCTION);
        if (offer == null) return false;
        if (isImpossible(offer.toPlan(), level.getGameTime())) {
            ai.minecivilization.work.GlobalTaskPool.release(self);
            return false;
        }
        workPriority = offer.priority();
        decisionReason = offer.reason();
        adoptPlan(self, offer.toPlan(), true);
        return true;
    }

    /** Take up a plan that is already in hand. */
    private void adoptPlan(CitizenEntity self, CitizenPlan plan) {
        if (isImpossible(plan, self.level().getGameTime())) {
            // Leave the citizen planless: advancePlan goes to the work board.
            decisionRetryAt = self.level().getGameTime() + 100;
            return;
        }
        adoptPlan(self, plan, false);
    }

    /**
     * Jobs that just failed because what they needed does not exist here —
     * "task type|resource" to the game time they may be tried again.
     *
     * <p>Cognition does not see the failure in time: a hungry citizen was
     * handed "go hunting" eighty times in two minutes in a valley with no
     * animals, each answer already queued before the last hunt had failed.
     * Nothing else got done. A failed search now keeps that job off the
     * citizen for a minute, and the work board fills the gap.</p>
     */
    private final java.util.Map<String, Long> impossible = new java.util.HashMap<>();
    private static final int IMPOSSIBLE_TICKS = 1200;

    private static String taskKey(CitizenPlan.Task task) {
        return task.type + "|" + (task.resource == null ? "" : task.resource);
    }

    private boolean isImpossible(CitizenPlan plan, long now) {
        if (plan == null || plan.tasks == null || plan.tasks.isEmpty()) return false;
        impossible.values().removeIf(until -> until <= now);
        return impossible.containsKey(taskKey(plan.tasks.get(0)));
    }

    private void adoptPlan(CitizenEntity self, CitizenPlan plan, boolean local) {
        this.localPlanActive = local;
        this.tasks.addAll(plan.tasks);
        this.goal = plan.goal;
        this.consecutiveTaskFailures = 0;
        this.status = Status.WORKING;
        String label = goalLabel();
        // Quiet while the citizen is visibly looping: the assignment is a
        // consequence of the failure already reported, not new information.
        if (stuck.repeatedFailures() <= 2) {
            LOGGER.info("[Citizen {}] Goal assigned: {} (ready in hand)",
                    self.getName().getString(), label);
        } else {
            LOGGER.debug("[Citizen {}] Goal assigned: {} (ready in hand)",
                    self.getName().getString(), label);
        }
        addEvent("goal assigned: " + label);
        self.setDisplayState("WORKING", label, "");
    }

    private void applyPlan(CitizenEntity self, CitizenPlan plan, String reason) {
        decisionPending = false;
        if (plan == null || plan.goal == null) {
            decisionRetryAt = self.level().getGameTime() + 20;
            return;
        }
        decisionRetryAt = 0;
        if (currentTask == null && goal == null && tasks.isEmpty() && boardFirst(self)) return;
        if (isImpossible(plan, self.level().getGameTime())) {
            decisionRetryAt = self.level().getGameTime() + 100;
            return;
        }
        // A local plan may have been adopted but not started yet.  Treat its
        // queued tasks exactly like an active task so an AI response cannot
        // erase work between two server ticks.
        if (currentTask != null || goal != null || !tasks.isEmpty()) {
            queuedPlan = plan;
            return;
        }
        clearPlan("replaced");
        localPlanActive = false;
        this.goal = plan.goal;
        this.tasks.addAll(plan.tasks);
        this.consecutiveTaskFailures = 0;
        this.status = Status.WORKING;
        String label = goalLabel();
        decisionReason = plan.reasoningSummary == null ? "" : plan.reasoningSummary;
        workPriority = null;   // cognition work is not ranked by the local board
        LOGGER.info("[Citizen {}] Goal assigned: {}", self.getName().getString(), label);
        addEvent("goal assigned: " + label);
        self.setDisplayState("WORKING", label, "");
        if (ModConfig.debug()) {
            LOGGER.info("[Citizen {}] reasoning: {}", self.getName().getString(),
                    plan.reasoningSummary);
        }
    }

    /**
     * Drop everything and get home, starting at the rung the situation
     * deserves rather than the bottom of the ladder.
     *
     * <p>A citizen a player can see is stuck does not need to work back
     * through "try walking" three times first.</p>
     */
    public boolean forceRescue(ServerLevel level, CitizenEntity self) {
        long now = level.getGameTime();
        clearPlan("rescue ordered");
        walkingHome = true;
        lost = true;
        homeward.reset();
        rescue.succeeded();
        // One rung pre-climbed, with no backoff: a rescue somebody asked for
        // has already established that plain walking is not working, and
        // making them wait first would be a strange way to answer "go and get
        // them".
        rescue.escalateNow();
        nextLocalWorkAt = 0;
        stuck.reset(now);
        addEvent("rescue ordered");
        ai.minecivilization.telemetry.ColonyEventLog.of(level)
                .rescue(level, self.getIdentity().name, "was ordered home");
        boolean engaged = returnHomeIfStrayed(level, self);
        if (!engaged) {
            // Already home. Nothing to rescue, so hand the citizen straight
            // back to ordinary work rather than leaving it flagged as lost.
            lost = false;
            walkingHome = false;
        }
        return engaged;
    }

    public void forceDecision(ServerLevel level, CitizenEntity self) {
        requestDecision(level, self, "forced", "HIGH", level.getGameTime());
    }

    public void stopAll(CitizenEntity self) {
        requestGeneration++;
        clearPlan("stopped by command");
        walkingHome = false;
        lost = false;
        homeward.reset();
        rescue.succeeded();
        stuck.reset(self.level().getGameTime());
        ai.minecivilization.work.GlobalTaskPool.release(self);
        decisionPending = false;
        decisionRetryAt = 0;
        status = Status.IDLE;
        self.setDisplayState("IDLE", "", "");
        self.clearWorkAnimation();
        LOGGER.info("[Citizen {}] stopped (command or interruption)", self.getName().getString());
    }

    private void clearPlan(String why) {
        clearPlan(why, false);
    }

    private void clearPlan(String why, boolean keepQueuedPlan) {
        CitizenPlan retained = keepQueuedPlan ? queuedPlan : null;
        queuedPlan = null;
        if (goal != null && ModConfig.debug()) {
            LOGGER.debug("clearing plan: {}", why);
        }
        self.getExecutor().cancel();
        // A claim outlives its plan only by accident, and an orphaned claim is
        // a job nobody will ever do.
        if (localPlanActive) ai.minecivilization.work.GlobalTaskPool.release(self);
        localPlanActive = false;
        goal = null;
        destination = null;
        tasks.clear();
        currentTask = null;
        lastOutcome = null;
        if (retained != null) queuedPlan = retained;
    }

    private String goalLabel() {
        if (goal == null) return "";
        StringBuilder sb = new StringBuilder(goal.type.name());
        if (goal.resource != null) sb.append(' ').append(goal.resource);
        if (goal.targetQuantity > 0) sb.append(" x").append(goal.targetQuantity);
        return sb.toString();
    }

    private String skillXpKey() {
        if (currentTask == null) return "logistics";
        return switch (currentTask.type) {
            case GATHER -> "mining";
            case HARVEST, PLANT, HERD, BREED, TEND_LIVESTOCK, TAME_WOLF -> "farming";
            case BUILD, PLACE -> "building";
            case DELIVER, WITHDRAW -> "logistics";
            default -> "research";
        };
    }
}
