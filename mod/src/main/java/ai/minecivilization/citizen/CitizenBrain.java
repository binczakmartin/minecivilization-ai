package ai.minecivilization.citizen;

import java.util.ArrayDeque;
import java.util.Iterator;

import com.mojang.logging.LogUtils;
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
 * citizens finish deterministic work where possible, then idle safely.</p>
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
        if (decisionPending && now - decisionRequestedAt > 1200) {
            decisionPending = false;
        }

        // emergency eating between tasks
        if (currentTask == null && self.getHunger() < 6f
                && self.getInventory().firstFoodSlot() >= 0) {
            self.eatNow();
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
            }
            case COMPLETED -> {
                LOGGER.debug("[Citizen {}] task completed: {}", self.getName().getString(),
                        currentTask.type);
                self.onTaskSucceeded(currentTask.type.name());
                AiBridge.postTaskResult(self, currentTask, "COMPLETED", null, null);
                addEvent("completed " + currentTask.type);
                currentTask = null;
                consecutiveTaskFailures = 0;
                taskAttempts = 0;
                self.getSkills().addXp(skillXpKey(), 0.02f);
            }
            case FAILED -> {
                SkillFailure failure = outcome.failure;
                boolean recoverable = failure == null || failure.recoverable;
                LOGGER.info("[Citizen {}] task {} failed: {}", self.getName().getString(),
                        currentTask.type, failure);
                if (failure != null) {
                    self.onTaskFailed(failure);
                }
                AiBridge.postTaskResult(self, currentTask, "FAILED", failure, null);
                addEvent("failed " + currentTask.type + " [" + (failure == null ? "?" : failure.code) + "]");
                consecutiveTaskFailures++;
                taskAttempts++;

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

    private void advancePlan(ServerLevel level, CitizenEntity self, long now) {
        if (!tasks.isEmpty()) {
            currentTask = tasks.pollFirst();
            self.getExecutor().reset(currentTask);
            lastTaskStartedAt = now;
            taskAttempts = 0;
            status = Status.WORKING;
            LOGGER.debug("[Citizen {}] task started: {}", self.getName().getString(), currentTask.type);
            AiBridge.postTaskResult(self, currentTask, "IN_PROGRESS", null, null);
            addEvent("started " + currentTask.type);
            self.setDisplayState("WORKING", goalLabel(), currentTask.type.toString());
            return;
        }
        if (goal != null) {
            LOGGER.info("[Citizen {}] Goal completed: {}", self.getName().getString(), goalLabel());
            addEvent("goal completed: " + goalLabel());
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
    }

    // ---------------------------------------------------------------- cognition

    public void requestDecision(ServerLevel level, CitizenEntity self, String reason,
                                String priority, long now) {
        if (!ModConfig.AI_ENABLED.get()) return;
        if (decisionPending) return;
        if (!self.isRegisteredWithService()) {
            // decisions require a registered citizen (404 otherwise); retry registration instead
            AiBridge.registerCitizen(self);
            return;
        }
        boolean forced = "forced".equals(reason);
        long cooldown = ModConfig.DECISION_COOLDOWN_TICKS.get();
        if (!forced && lastDecisionAt != Long.MIN_VALUE && now - lastDecisionAt < cooldown) {
            return;
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
        AiBridge.requestDecision(self, observation, priority, reason, plan -> {
            // executed on the Minecraft server thread by AiBridge.drain()
            applyPlan(self, plan, reason);
        });
        LOGGER.debug("[Cognition] {} queued priority={} reason={}",
                self.getName().getString(), priority, reason);
    }

    private void applyPlan(CitizenEntity self, CitizenPlan plan, String reason) {
        decisionPending = false;
        if (plan == null || plan.goal == null) return;
        if (currentTask != null) return; // a task already running wins
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
        clearPlan("stopped by command");
        decisionPending = false;
        status = Status.IDLE;
        self.setDisplayState("IDLE", "", "");
        LOGGER.info("[Citizen {}] stopped (command or interruption)", self.getName().getString());
    }

    private void clearPlan(String why) {
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
            case HARVEST, PLANT -> "farming";
            case BUILD, PLACE -> "building";
            case DELIVER, WITHDRAW -> "logistics";
            default -> "research";
        };
    }
}
