package ai.minecivilization.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ai.minecivilization.citizen.CitizenBrain;
import ai.minecivilization.citizen.CitizenPlan;
import ai.minecivilization.colony.CitizenMarkers;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.work.GlobalTaskPool;
import ai.minecivilization.work.ProductivityMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Everything worth knowing about one citizen, in one place.
 *
 * <p>Debugging a colony used to mean reading a log line that said
 * {@code task MOVE failed: TARGET_UNREACHABLE} and guessing the rest: which
 * citizen, going where, from where, why it wanted to, what it had tried, and
 * what it would do next. All of that existed somewhere in the brain, the
 * executor, the navigator and the inventory, and none of it was ever assembled.
 * This assembles it.</p>
 *
 * <p>Built on demand rather than maintained, so a colony of a hundred pays
 * nothing for reports nobody asked for. The same object backs the inspect
 * command, the heartbeat log and the supervision readout, so those three can
 * never drift apart.</p>
 */
public record CitizenReport(
        String name,
        String profession,
        String id,
        ProductivityMonitor.State state,
        String goal,
        String task,
        String subTask,
        String priority,
        BlockPos position,
        BlockPos destination,
        BlockPos colony,
        int distanceHome,
        String bearingHome,
        int depthBelowSurface,
        float health,
        float hunger,
        float energy,
        String reason,
        String problem,
        String fallback,
        String lastSuccess,
        String nextAction,
        int completedCount,
        int consecutiveFailures,
        long stuckTicks,
        String inventory,
        String claim,
        boolean decisionPending,
        List<String> recentEvents) {

    /** Assemble a report from the live citizen. */
    public static CitizenReport of(ServerLevel level, CitizenEntity citizen) {
        CitizenBrain brain = citizen.getCitizenBrain();
        long now = level.getGameTime();

        BlockPos at = citizen.blockPosition();
        BlockPos centre = ZoneManager.get(level).townCenter(level);
        int distance = (int) Math.sqrt(centre.distSqr(at));

        CitizenPlan.Goal goal = brain.currentGoal();
        CitizenPlan.Task task = brain.currentTaskOrNull();
        CitizenPlan.Task next = brain.nextTaskOrNull();

        var stuckReason = brain.stuckReason(now);
        String problem = stuckReason == ai.minecivilization.citizen.StuckDetector.Reason.NONE
                ? "" : humanise(stuckReason.name());

        // The fallback is only interesting while a rescue is actually running;
        // reporting one for a citizen happily chopping wood is noise.
        String fallback = brain.isWalkingHome() || brain.isLost()
                ? brain.rescueStep().name() + " (attempt " + (brain.rescueAttempts() + 1) + ")"
                : "";

        List<String> events = new ArrayList<>();
        var iterator = brain.recentEvents();
        while (iterator.hasNext()) events.add(iterator.next());

        return new CitizenReport(
                citizen.getIdentity().name,
                citizen.getIdentity().profession,
                String.valueOf(citizen.getIdentity().citizenId),
                ProductivityMonitor.stateOf(citizen, now),
                goal == null ? "none" : describeGoal(goal),
                task == null ? "none" : describeTask(task),
                brain.currentSkillLabel(),
                brain.workPriority() == null ? "" : brain.workPriority().label(),
                at,
                brain.destination(),
                centre,
                distance,
                CitizenMarkers.bearing(centre.getX() - at.getX(), centre.getZ() - at.getZ()),
                depth(level, at),
                citizen.getHealth(),
                citizen.getHunger(),
                citizen.getEnergy(),
                brain.decisionReason(),
                problem,
                fallback,
                brain.lastCompleted(),
                next == null ? "" : describeTask(next),
                brain.completedCount(),
                brain.consecutiveTaskFailures(),
                brain.stuckTicks(now),
                citizen.getInventory().summary().toString(),
                GlobalTaskPool.heldBy(citizen) == null ? "" : GlobalTaskPool.heldBy(citizen),
                brain.isDecisionPending(),
                events);
    }

    // ------------------------------------------------------------------ rendering

    /**
     * The report as the block of lines a player reads.
     *
     * <p>Fixed field order and fixed labels: a readout you can scan down the
     * left-hand side of is worth more than a prettier one you have to read.</p>
     */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add(String.format("Citizen %s [%s]  %s", name, profession, state.label()));
        out.add(String.format("  Goal:        %s", goal));
        out.add(String.format("  Task:        %s", task));
        if (!subTask.isBlank()) out.add(String.format("  Doing:       %s", subTask));
        if (!priority.isBlank()) out.add(String.format("  Priority:    %s", priority));
        out.add(String.format("  Position:    X=%d Y=%d Z=%d%s",
                position.getX(), position.getY(), position.getZ(),
                depthBelowSurface > 0 ? "  (" + depthBelowSurface + " below surface)" : ""));
        if (destination != null) {
            out.add(String.format("  Destination: X=%d Y=%d Z=%d",
                    destination.getX(), destination.getY(), destination.getZ()));
        }
        out.add(String.format("  Colony:      X=%d Y=%d Z=%d  (%dm %s)",
                colony.getX(), colony.getY(), colony.getZ(), distanceHome, bearingHome));
        out.add(String.format("  Condition:   health %.0f  hunger %.0f  energy %.0f",
                health, hunger, energy));
        if (!reason.isBlank()) out.add(String.format("  Because:     %s", reason));
        if (!problem.isBlank()) {
            out.add(String.format("  Problem:     %s (%ds)", problem, stuckTicks / 20));
        }
        if (!fallback.isBlank()) out.add(String.format("  Fallback:    %s", fallback));
        if (!lastSuccess.isBlank()) out.add(String.format("  Last done:   %s", lastSuccess));
        if (!nextAction.isBlank()) out.add(String.format("  Next:        %s", nextAction));
        out.add(String.format("  Completed:   %d task(s)   failures %d   cognition %s",
                completedCount, consecutiveFailures, decisionPending ? "pending" : "idle"));
        if (!claim.isBlank()) out.add(String.format("  Holding:     %s", claim));
        out.add(String.format("  Carrying:    %s", inventory));
        return out;
    }

    /** One dense line, for the colony heartbeat and list views. */
    public String summaryLine() {
        StringBuilder line = new StringBuilder(String.format(
                "%-12s %-11s %-9s %-22s at %d,%d,%d",
                name, profession, state.label(), task,
                position.getX(), position.getY(), position.getZ()));
        if (distanceHome > 0) line.append(String.format("  %dm %s", distanceHome, bearingHome));
        if (!problem.isBlank()) line.append("  ! ").append(problem);
        return line.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static String describeGoal(CitizenPlan.Goal goal) {
        StringBuilder sb = new StringBuilder(goal.type.name());
        if (goal.resource != null) sb.append(' ').append(shortName(goal.resource));
        if (goal.targetQuantity > 0) sb.append(" x").append(goal.targetQuantity);
        if (!goal.description.isBlank()) sb.append(" — ").append(goal.description);
        return sb.toString();
    }

    private static String describeTask(CitizenPlan.Task task) {
        StringBuilder sb = new StringBuilder(task.type.name());
        if (task.resource != null) sb.append(' ').append(shortName(task.resource));
        if (task.quantity > 0) sb.append(" x").append(task.quantity);
        if (task.position != null) {
            sb.append(" @").append(task.position[0]).append(',')
              .append(task.position[1]).append(',').append(task.position[2]);
        }
        return sb.toString();
    }

    private static String shortName(String id) {
        return id == null ? "" : id.substring(id.indexOf(':') + 1).replace('_', ' ');
    }

    private static String humanise(String constant) {
        return constant.charAt(0) + constant.substring(1).toLowerCase(Locale.ROOT)
                .replace('_', ' ');
    }

    private static int depth(ServerLevel level, BlockPos at) {
        if (level.canSeeSky(at.above())) return 0;
        int surface = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                at.getX(), at.getZ());
        return Math.max(0, surface - at.getY());
    }
}
