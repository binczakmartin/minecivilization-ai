package ai.minecivilization.citizen;

/**
 * Why a citizen has stopped being productive.
 *
 * <p>"Nothing is happening" is not one condition, and the difference decides
 * the cure. A citizen standing in the same block for a minute is stuck on
 * terrain; one failing the same task over and over is stuck on a
 * prerequisite; one that keeps being handed no work at all is stuck on the
 * colony rather than on the world. Treating all three as "idle" is how a
 * settlement quietly stops.</p>
 *
 * <p>Deliberately pure — plain numbers in, a verdict out — so the thresholds
 * that decide when a citizen is rescued are unit tested rather than watched
 * for an hour in a running world.</p>
 */
public final class StuckDetector {

    /** Ticks in the same block, with no job, before standing still counts as stalled. */
    public static final int STILL_TICKS = 160;
    /**
     * Ticks in the same block <em>while holding a job</em> before it counts.
     *
     * <p>Much longer, because plenty of real work is done standing perfectly
     * still: mining a block of deepslate, waiting at a furnace, crafting. Using
     * the idle threshold here flagged every miner in the colony as stuck.</p>
     */
    public static final int WORKING_STILL_TICKS = 600;
    /**
     * Ticks with no completed task before productivity counts as stalled.
     *
     * <p>Two minutes, not thirty seconds. A task is a coarse unit: felling a
     * tree, walking two hundred blocks, or resolving a crafting tree down
     * through planks and logs can all legitimately take more than a minute
     * without anything reaching {@code COMPLETED}. At thirty seconds the
     * monitor reported an entire working colony as unproductive, which is
     * worse than not reporting at all. Fast loops are caught by
     * {@link Reason#REPEATED_FAILURE} long before this fires.</p>
     */
    public static final int UNPRODUCTIVE_TICKS = 2400;
    /** Identical failures in a row before the task itself is the problem. */
    public static final int REPEAT_FAILURES = 3;

    /** What is wrong, most specific first. */
    public enum Reason {
        /** Working normally. */
        NONE,
        /** Has work, but has not physically moved and is not making progress. */
        NOT_MOVING,
        /** The same failure code keeps coming back. */
        REPEATED_FAILURE,
        /** Has had no task at all for a long time. */
        NO_WORK,
        /** Nothing has been completed for a long time, whatever it is doing. */
        UNPRODUCTIVE
    }

    private int lastX = Integer.MIN_VALUE;
    private int lastY;
    private int lastZ;
    private long stillSince = Long.MIN_VALUE;
    private long lastProgressAt = Long.MIN_VALUE;
    private long lastWorkAt = Long.MIN_VALUE;

    private String lastFailureCode;
    private int repeatedFailures;

    /** Set while the citizen genuinely has nothing assigned. */
    private boolean hasWork;

    /**
     * Record where the citizen is now.
     *
     * <p>Movement of a single block resets the stall clock: a citizen inching
     * along a tunnel is slow, not stuck.</p>
     */
    public void sample(long now, int x, int y, int z) {
        if (lastProgressAt == Long.MIN_VALUE) lastProgressAt = now;
        if (lastWorkAt == Long.MIN_VALUE) lastWorkAt = now;
        if (x != lastX || y != lastY || z != lastZ) {
            lastX = x;
            lastY = y;
            lastZ = z;
            stillSince = now;
            return;
        }
        if (stillSince == Long.MIN_VALUE) stillSince = now;
    }

    /** The citizen currently holds a task (or does not). */
    public void setHasWork(long now, boolean working) {
        this.hasWork = working;
        if (working) lastWorkAt = now;
        else if (lastWorkAt == Long.MIN_VALUE) lastWorkAt = now;
    }

    /** Something actually finished — the strongest possible evidence of health. */
    public void noteProgress(long now) {
        lastProgressAt = now;
        lastWorkAt = now;
        stillSince = now;
        repeatedFailures = 0;
        lastFailureCode = null;
    }

    /**
     * A task failed.
     *
     * <p>Only the <em>same</em> code repeating counts: a citizen that fails to
     * find wood, then fails to reach a chest, is having a bad day rather than
     * being trapped in a loop.</p>
     */
    public void noteFailure(long now, String code) {
        String normalized = code == null ? "?" : code;
        if (normalized.equals(lastFailureCode)) {
            repeatedFailures++;
        } else {
            lastFailureCode = normalized;
            repeatedFailures = 1;
        }
    }

    /** Forget everything — a new goal deserves a clean slate. */
    public void reset(long now) {
        stillSince = now;
        lastProgressAt = now;
        lastWorkAt = now;
        repeatedFailures = 0;
        lastFailureCode = null;
        lastX = Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ verdict

    public Reason reason(long now) {
        if (repeatedFailures >= REPEAT_FAILURES) return Reason.REPEATED_FAILURE;
        if (hasWork && stillSince != Long.MIN_VALUE
                && now - stillSince >= WORKING_STILL_TICKS) {
            return Reason.NOT_MOVING;
        }
        if (!hasWork && lastWorkAt != Long.MIN_VALUE && now - lastWorkAt >= STILL_TICKS) {
            return Reason.NO_WORK;
        }
        if (lastProgressAt != Long.MIN_VALUE && now - lastProgressAt >= UNPRODUCTIVE_TICKS) {
            return Reason.UNPRODUCTIVE;
        }
        return Reason.NONE;
    }

    public boolean isStuck(long now) {
        return reason(now) != Reason.NONE;
    }

    /** Ticks spent in the same block, or 0 while moving. */
    public long stillTicks(long now) {
        return stillSince == Long.MIN_VALUE ? 0 : Math.max(0, now - stillSince);
    }

    /** Ticks since anything was completed. */
    public long idleTicks(long now) {
        return lastProgressAt == Long.MIN_VALUE ? 0 : Math.max(0, now - lastProgressAt);
    }

    public int repeatedFailures() {
        return repeatedFailures;
    }

    public String lastFailureCode() {
        return lastFailureCode;
    }
}
