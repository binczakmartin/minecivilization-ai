package ai.minecivilization.citizen;

/**
 * How a citizen that cannot get home tries harder.
 *
 * <p>The old behaviour was a single idea repeated: issue a walk, and when the
 * walk failed, wait longer and issue the same walk again. A citizen at the
 * bottom of a ravine would do that forever, because the answer was never going
 * to change — no amount of patience turns an impossible route into a possible
 * one.</p>
 *
 * <p>This is the escalation instead. Each failure moves one rung up a ladder
 * of increasingly expensive, increasingly physical answers, ending in the one
 * that always works: stop trying to find a way out and cut one. A citizen
 * genuinely buried under a mountain will spend several minutes digging a
 * staircase to daylight, and that is the correct outcome.</p>
 *
 * <p>Pure: distance, depth and a failure count in, a rung out. The rescue
 * policy of the whole colony is therefore unit tested rather than observed.</p>
 */
public final class RescueLadder {

    /** One rung. Ordered cheapest-first; {@link #ordinal()} is the rung number. */
    public enum Step {
        /** Follow a route the colony already walks — the cheapest correct answer. */
        KNOWN_ROUTE,
        /** Ordinary pathfinding straight to the destination. */
        DIRECT_PATH,
        /** Path to a waypoint part of the way there, then re-plan from it. */
        WAYPOINT,
        /** Walk to any reachable open ground nearby and look again from there. */
        LOCAL_EXPLORE,
        /** Bridge, pillar or tunnel through whatever is in the way. */
        BUILD_PASSAGE,
        /** Cut a staircase upward until the sky is visible. */
        DIG_TO_SURFACE,
        /** Head at the destination in a straight line, making ground as needed. */
        BEELINE
    }

    /** Below this many blocks under the local surface, digging up comes first. */
    public static final int DEEP_UNDERGROUND = 20;

    private static final int BASE_BACKOFF_TICKS = 40;
    private static final int MAX_BACKOFF_TICKS = 600;

    private int failures;
    private long readyAt = Long.MIN_VALUE;
    private Step lastStep = Step.KNOWN_ROUTE;

    /**
     * The rung to try now.
     *
     * @param failures            how many attempts have already failed
     * @param depthBelowSurface   blocks between the citizen and open sky
     *                            (0 or less when already on the surface)
     * @param hasKnownRoute       the colony remembers a route from here
     */
    public static Step stepFor(int failures, int depthBelowSurface, boolean hasKnownRoute) {
        // Buried deep, ordinary navigation is a formality: a path out of a
        // sealed cave does not exist, so stop spending attempts proving it.
        if (depthBelowSurface >= DEEP_UNDERGROUND) {
            if (failures <= 0 && hasKnownRoute) return Step.KNOWN_ROUTE;
            if (failures <= 1) return Step.DIRECT_PATH;
            return failures <= 4 ? Step.DIG_TO_SURFACE : Step.BEELINE;
        }
        int rung = failures;
        if (!hasKnownRoute) rung++;         // no remembered route: skip that rung
        return switch (Math.max(0, rung)) {
            case 0 -> Step.KNOWN_ROUTE;
            case 1 -> Step.DIRECT_PATH;
            case 2 -> Step.WAYPOINT;
            case 3 -> Step.LOCAL_EXPLORE;
            case 4 -> Step.BUILD_PASSAGE;
            case 5 -> Step.DIG_TO_SURFACE;
            default -> Step.BEELINE;
        };
    }

    /**
     * Pause between attempts. Short at first — most failures are transient —
     * and capped low enough that a stranded citizen never looks abandoned.
     */
    public static int backoffTicks(int failures) {
        if (failures <= 1) return BASE_BACKOFF_TICKS;
        long delay = (long) BASE_BACKOFF_TICKS << Math.min(failures - 1, 16);
        return (int) Math.min(delay, MAX_BACKOFF_TICKS);
    }

    // ------------------------------------------------------------------ state

    public Step next(long now, int depthBelowSurface, boolean hasKnownRoute) {
        lastStep = stepFor(failures, depthBelowSurface, hasKnownRoute);
        return lastStep;
    }

    public Step lastStep() {
        return lastStep;
    }

    public boolean ready(long now) {
        return readyAt == Long.MIN_VALUE || now >= readyAt;
    }

    public long readyAt() {
        return readyAt;
    }

    /** This rung did not work; the next attempt climbs one higher. */
    public void failed(long now) {
        failures = Math.min(failures + 1, 32);
        readyAt = now + backoffTicks(failures);
    }

    /**
     * Climb a rung without waiting for the backoff.
     *
     * <p>Used when a player orders a rescue: the request itself is evidence
     * that the cheap answers are not working, and making them wait first would
     * be a strange way to respond to "go and get them".</p>
     */
    public void escalateNow() {
        failures = Math.min(failures + 1, 32);
        readyAt = Long.MIN_VALUE;
    }

    /** Back home (or making real progress) — start again from the bottom. */
    public void succeeded() {
        failures = 0;
        readyAt = Long.MIN_VALUE;
        lastStep = Step.KNOWN_ROUTE;
    }

    public int failures() {
        return failures;
    }

    /** True once ordinary walking has been ruled out and digging has begun. */
    public boolean isDesperate() {
        return lastStep == Step.DIG_TO_SURFACE || lastStep == Step.BEELINE;
    }
}
