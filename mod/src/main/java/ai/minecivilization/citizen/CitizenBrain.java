package ai.minecivilization.citizen;

import java.util.ArrayDeque;
import java.util.Iterator;

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
    private long nextLocalWorkAt;
    private long requestGeneration;

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

        // expire a cognition request that never came back (service offline)
        // A request that never came back should cost a moment, not a minute:
        // at 1200 ticks a citizen whose decision was dropped stood still for a
        // full minute before it was allowed to ask again.
        if (decisionPending && now - decisionRequestedAt > 100) {
            decisionPending = false;
            requestGeneration++;
        }

        // emergency eating between tasks
        if (currentTask == null && self.getHunger() < 6f
                && self.getInventory().firstFoodSlot() >= 0) {
            self.eatNow();
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
                LOGGER.info("[Citizen {}] completed {}{}", self.getName().getString(),
                        currentTask.type,
                        currentTask.resource == null ? "" : " " + currentTask.resource);
                self.onTaskSucceeded(currentTask.type.name());
                AiBridge.postTaskResult(self, currentTask, "COMPLETED", null, null);
                addEvent("completed " + currentTask.type);
                self.getSkills().addXp(skillXpKey(), 0.02f);
                currentTask = null;
                consecutiveTaskFailures = 0;
                taskAttempts = 0;
            }
            case FAILED -> {
                SkillFailure failure = outcome.failure;
                boolean recoverable = failure == null || failure.recoverable;
                boolean returningHome = walkingHome && currentTask != null
                        && currentTask.type == CitizenPlan.TaskType.MOVE;
                LOGGER.info("[Citizen {}] task {} failed: {}", self.getName().getString(),
                        currentTask.type, failure);
                if (failure != null) {
                    self.onTaskFailed(failure);
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
                    consecutiveTaskFailures = 0;
                    taskAttempts = 0;
                    clearPlan("walk home failed; waiting before another attempt");
                    homeward.failed(now);
                    status = Status.BLOCKED;
                    long retryIn = Math.max(0, homeward.retryAt() - now);
                    self.setDisplayState("BLOCKED", "RETURN_HOME",
                            "route blocked; retry in " + (retryIn / 20) + "s");
                    addEvent("walk home blocked; retry in " + (retryIn / 20) + "s");
                    LOGGER.info("[Citizen {}] walk home failed; retry in {}s",
                            self.getName().getString(), retryIn / 20);
                    return;
                }

                localWork.failed(currentTask, now);
                consecutiveTaskFailures++;
                taskAttempts++;

                // Resource/search failures have already exhausted deterministic recovery.
                // Repeating them immediately cannot grow a crop or invent a missing tool.
                if (failure != null && ("MISSING_RESOURCE".equals(failure.code)
                        || "TARGET_NOT_FOUND".equals(failure.code))) {
                    clearPlan("prerequisite unavailable; choose different work");
                    status = Status.IDLE;
                    return;
                }
                if (!recoverable) {
                    clearPlan("fatal task failure");
                    status = Status.BLOCKED;
                    self.setDisplayState("BLOCKED", goalLabel(), failure == null ? "" : failure.code);
                    return;
                }
                if (consecutiveTaskFailures >= 3) {
                    // deterministic layer already tried simple recovery; ask for a new decision
                    LOGGER.info("[Citizen {}] goal disengaged after {} failures",
                            self.getName().getString(), consecutiveTaskFailures);
                    clearPlan("repeated task failures");
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

    /**
     * Issue a walk home when the citizen has strayed. The task is an ordinary
     * MOVE, so it escalates to TRAVERSE and will bridge, tunnel or pillar its
     * way back across whatever it crossed on the way out.
     *
     * @return true when a walk home was started this tick
     */
    private boolean returnHomeIfStrayed(ServerLevel level, CitizenEntity self) {
        long now = level.getGameTime();
        net.minecraft.core.BlockPos center = ZoneManager.get(level).townCenter(level);
        double distance = Math.sqrt(center.distSqr(self.blockPosition()));
        HomewardPolicy.Action action = homeward.evaluate(distance, now);

        if (action == HomewardPolicy.Action.AT_HOME) {
            if (walkingHome) addEvent("back at the colony");
            walkingHome = false;
            return false;
        }
        if (action == HomewardPolicy.Action.WAIT) {
            // A blocked return is a real state, not an invitation to accept a
            // new job on the very next tick.  Let the backoff expire first.
            status = Status.BLOCKED;
            long retryIn = Math.max(0, homeward.retryAt() - now);
            self.setDisplayState("BLOCKED", "RETURN_HOME",
                    "route blocked; retry in " + (retryIn / 20) + "s");
            return true;
        }
        if (action != HomewardPolicy.Action.START) {
            walkingHome = false;
            return false;
        }
        if (walkingHome && currentTask != null) return false;

        net.minecraft.core.BlockPos home = HomeDestination.forCitizen(level, self);
        if (home == null) home = center;
        walkingHome = true;
        consecutiveTaskFailures = 0;
        taskAttempts = 0;
        clearPlan("heading back to the colony");
        currentTask = new CitizenPlan.Task(CitizenPlan.TaskType.MOVE, null, -1, null, null, null,
                new int[]{home.getX(), home.getY(), home.getZ()});
        self.getExecutor().reset(currentTask);
        status = Status.WORKING;
        self.setDisplayState("WORKING", "RETURN_HOME",
                "walking back (" + (int) distance + " blocks out)");
        addEvent("strayed " + (int) distance + " blocks; heading home");
        LOGGER.info("[Citizen {}] {} blocks from the colony — walking home to {},{},{}",
                self.getName().getString(), (int) distance,
                home.getX(), home.getY(), home.getZ());
        return true;
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
            lastTaskStartedAt = now;
            taskAttempts = 0;
            status = Status.WORKING;
            LOGGER.info("[Citizen {}] starts {}{}", self.getName().getString(), currentTask.type,
                    currentTask.resource == null ? "" : " " + currentTask.resource);
            AiBridge.postTaskResult(self, currentTask, "IN_PROGRESS", null, null);
            addEvent("started " + currentTask.type);
            self.setDisplayState("WORKING", goalLabel(), currentTask.type.toString());
            return;
        }
        if (goal != null) {
            LOGGER.info("[Citizen {}] Goal completed: {}", self.getName().getString(), goalLabel());
            addEvent("goal completed: " + goalLabel());
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
        if (waitingForCognition) return;
        if (now < nextLocalWorkAt) return;

        nextLocalWorkAt = now + 40;
        CitizenPlan local = localWork.next(level, self);
        if (local != null) adoptPlan(self, local);
    }

    // ---------------------------------------------------------------- cognition

    public void requestDecision(ServerLevel level, CitizenEntity self, String reason,
                                String priority, long now) {
        if (!ModConfig.AI_ENABLED.get()) return;
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
            if (plan != null && plan.goal != null) {
                queuedPlan = plan;
            }
        });
    }

    /** Take up a plan that is already in hand. */
    private void adoptPlan(CitizenEntity self, CitizenPlan plan) {
        this.tasks.addAll(plan.tasks);
        this.goal = plan.goal;
        this.consecutiveTaskFailures = 0;
        this.status = Status.WORKING;
        String label = goalLabel();
        LOGGER.info("[Citizen {}] Goal assigned: {} (ready in hand)",
                self.getName().getString(), label);
        addEvent("goal assigned: " + label);
        self.setDisplayState("WORKING", label, "");
    }

    private void applyPlan(CitizenEntity self, CitizenPlan plan, String reason) {
        decisionPending = false;
        if (plan == null || plan.goal == null) return;
        if (currentTask != null) { queuedPlan = plan; return; }
        clearPlan("replaced");
        this.goal = plan.goal;
        this.tasks.addAll(plan.tasks);
        this.consecutiveTaskFailures = 0;
        this.status = Status.WORKING;
        String label = goalLabel();
        LOGGER.info("[Citizen {}] Goal assigned: {}", self.getName().getString(), label);
        addEvent("goal assigned: " + label);
        self.setDisplayState("WORKING", label, "");
        if (ModConfig.debug()) {
            LOGGER.info("[Citizen {}] reasoning: {}", self.getName().getString(),
                    plan.reasoningSummary);
        }
    }

    public void forceDecision(ServerLevel level, CitizenEntity self) {
        requestDecision(level, self, "forced", "HIGH", level.getGameTime());
    }

    public void stopAll(CitizenEntity self) {
        requestGeneration++;
        clearPlan("stopped by command");
        walkingHome = false;
        homeward.reset();
        decisionPending = false;
        status = Status.IDLE;
        self.setDisplayState("IDLE", "", "");
        LOGGER.info("[Citizen {}] stopped (command or interruption)", self.getName().getString());
    }

    private void clearPlan(String why) {
        queuedPlan = null;
        if (goal != null && ModConfig.debug()) {
            LOGGER.debug("clearing plan: {}", why);
        }
        self.getExecutor().cancel();
        goal = null;
        tasks.clear();
        currentTask = null;
        lastOutcome = null;
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
