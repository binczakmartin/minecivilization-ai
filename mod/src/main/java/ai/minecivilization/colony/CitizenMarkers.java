package ai.minecivilization.colony;

/**
 * Making citizens findable.
 *
 * <p>A settlement of twenty spread over a few hundred blocks is genuinely hard
 * to keep track of — citizens work in trenches, inside half-built houses and
 * down mineshafts. Name tags help at close range; an outline seen through
 * terrain is what actually lets a player find someone.</p>
 *
 * <p>Off by default, because a permanent glow everywhere is a strange way to
 * play. Toggled with {@code /mciv highlight}.</p>
 */
public final class CitizenMarkers {

    private static boolean highlight;

    private CitizenMarkers() {
    }

    /** True while citizens are outlined through walls. */
    public static boolean highlighted() {
        return highlight;
    }

    public static void setHighlighted(boolean enabled) {
        highlight = enabled;
    }

    /** Reset with the server, so a toggle never leaks between worlds. */
    public static void reset() {
        highlight = false;
    }

    /**
     * A compass bearing, for telling a player where to walk.
     *
     * @param dx east-positive offset from the viewer
     * @param dz south-positive offset from the viewer
     */
    public static String bearing(double dx, double dz) {
        if (dx == 0 && dz == 0) return "here";
        double angle = Math.toDegrees(Math.atan2(-dx, dz));   // 0 = south, as Minecraft yaw
        if (angle < 0) angle += 360.0;
        String[] points = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        return points[(int) Math.round(angle / 45.0) % 8];
    }
}
