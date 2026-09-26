package ai.minecivilization.work;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.citizen.CitizenPlan;

/**
 * One job the colony is offering.
 *
 * <p>An offer is a plan with a price tag: what the work is, how badly the
 * colony wants it done, and a key that stops six citizens taking the same job.
 * That last part is what separates a colony from a crowd — without it every
 * idle citizen independently picks the nearest wolf, the nearest torch spot or
 * the nearest half-built wall, and they spend the afternoon colliding.</p>
 */
public record WorkOffer(WorkPriority priority, String claimKey, String reason,
                        List<CitizenPlan.Task> tasks, int maxWorkers) {

    /** How long a claim on this kind of job is held before it lapses, in ticks. */
    public static final int DEFAULT_TTL = 1200;

    public WorkOffer {
        tasks = List.copyOf(tasks);
    }

    /** An offer only one citizen at a time should take. */
    public static WorkOffer exclusive(WorkPriority priority, String claimKey, String reason,
                                      List<CitizenPlan.Task> tasks) {
        return new WorkOffer(priority, claimKey, reason, tasks, 1);
    }

    /** An offer several citizens may work on at once — a big build, say. */
    public static WorkOffer shared(WorkPriority priority, String claimKey, String reason,
                                   List<CitizenPlan.Task> tasks, int workers) {
        return new WorkOffer(priority, claimKey, reason, tasks, Math.max(1, workers));
    }

    /** A single-task offer, which most of them are. */
    public static WorkOffer single(WorkPriority priority, String claimKey, String reason,
                                   CitizenPlan.Task task) {
        List<CitizenPlan.Task> one = new ArrayList<>(1);
        one.add(task);
        return new WorkOffer(priority, claimKey, reason, one, 1);
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    /**
     * The offer as a plan the brain can adopt.
     *
     * <p>The goal is synthesised from the work itself, so deterministic jobs
     * appear in inspection and logs exactly like cognition-issued ones.</p>
     */
    public CitizenPlan toPlan() {
        CitizenPlan.Task first = tasks.get(0);
        CitizenPlan.GoalType goalType = switch (first.type) {
            case BUILD, PLACE, ROADWORK, SIGN -> CitizenPlan.GoalType.BUILD_PROJECT;
            case HARVEST -> CitizenPlan.GoalType.HARVEST_FOOD;
            case CRAFT, SMELT -> CitizenPlan.GoalType.CRAFT_ITEM;
            case DELIVER, WITHDRAW, HANDOVER -> CitizenPlan.GoalType.DELIVER_RESOURCE;
            case EXPLORE, MOVE, ESCAPE -> CitizenPlan.GoalType.EXPLORE;
            case IDLE, REST, SHELTER -> CitizenPlan.GoalType.REST;
            default -> CitizenPlan.GoalType.INCREASE_RESOURCE;
        };
        return new CitizenPlan(reason,
                new CitizenPlan.Goal(goalType, first.resource, first.quantity,
                        first.projectId, priority.label() + ": " + reason),
                tasks);
    }
}
