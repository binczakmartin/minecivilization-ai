package ai.minecivilization.citizen;

/**
 * How the night changes a citizen's day.
 *
 * <p>Citizens used to work straight through dusk a hundred blocks out, and a
 * colony lost seven of twelve people in its first three minutes of darkness.
 * Walling everyone in until morning fixed the deaths but stopped the colony for
 * half of every day. The rule now: keep working, but close to home — come back
 * from far out at dusk, go nowhere far at night, and let the hurt and the
 * unarmed rest by the others until they heal.</p>
 *
 * <p>Pure: time of day in, phase out, so it is unit tested without a world.</p>
 */
public final class NightPolicy {

    /** Ticks in a Minecraft day. */
    public static final long DAY_LENGTH = 24_000L;
    /**
     * The walk back from far out starts. Sunset is at 12 000 and the first
     * hostile spawns around 13 000; a thousand ticks of margin is roughly the
     * fifty seconds it takes to walk sixty blocks.
     */
    public static final long DUSK_START = 11_000L;
    /** Hostiles are out: the hurt and the unarmed stop working. */
    public static final long NIGHT_START = 12_500L;
    /**
     * Mobs stop spawning and the sun starts burning them around 23 000–23 500.
     */
    public static final long DAWN = 23_500L;
    /** Beyond this, walking home at dusk would not finish before dark. */
    public static final double MAX_WALK_HOME = 160.0;
    /** Inside this, a citizen is already home. */
    public static final double HOME_RADIUS = 10.0;
    /** Working this far from home is fine by day, too far to be out at night. */
    public static final double NIGHT_RANGE = 48.0;
    /** Below this share of health, a citizen stops working at night. */
    public static final float RETREAT_HEALTH = 0.5f;

    public enum Phase {
        /** Work as normal. */
        DAY,
        /** Come back from far out; otherwise carry on. */
        DUSK,
        /** Work close to home; the hurt and the unarmed rest. */
        NIGHT
    }

    private NightPolicy() {
    }

    /**
     * @param dayTime    the level's day time (any value; taken modulo a day)
     * @param thundering a thunderstorm spawns hostiles in daylight too
     */
    public static Phase phase(long dayTime, boolean thundering) {
        long t = Math.floorMod(dayTime, DAY_LENGTH);
        if (t >= NIGHT_START && t < DAWN) return Phase.NIGHT;
        if (thundering) return Phase.NIGHT;
        if (t >= DUSK_START && t < NIGHT_START) return Phase.DUSK;
        return Phase.DAY;
    }

    /** True from dusk to dawn (or in a thunderstorm). */
    public static boolean shelterTime(long dayTime, boolean thundering) {
        return phase(dayTime, thundering) != Phase.DAY;
    }

    /**
     * Whether the dusk walk back towards the colony is worth making.
     *
     * <p>Only at dusk, only for a citizen working far out — anyone within
     * {@link #NIGHT_RANGE} simply carries on — and only from close enough that
     * the walk is a walk rather than an expedition.</p>
     */
    public static boolean walkHome(Phase phase, double distanceHome) {
        return phase == Phase.DUSK
                && distanceHome > NIGHT_RANGE
                && distanceHome <= MAX_WALK_HOME;
    }

    /** Which night this is, so one night's decisions are not repeated. */
    public static long nightIndex(long dayTime) {
        return Math.floorDiv(dayTime, DAY_LENGTH);
    }
}
