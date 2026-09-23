package ai.minecivilization.navigation;

/**
 * Breaking a long journey into legs a route planner can actually answer.
 *
 * <p>Terrain planning searches a bounded box around the citizen. A goal outside
 * that box cannot be planned for at all — not because no route exists, but
 * because none is visible from here. Asked to walk home from a hundred and
 * twenty blocks out, citizens therefore failed instantly and repeatedly: one
 * session logged 445 identical "no route" failures, and the colony sat idle
 * through all of them.</p>
 *
 * <p>Aiming at a point part of the way there turns one impossible question into
 * a series of answerable ones.</p>
 *
 * <p>Pure integer geometry, unit tested.</p>
 */
public final class TravelLeg {

    private TravelLeg() {
    }

    /** Horizontal distance between two points. */
    public static double distance(int fromX, int fromZ, int toX, int toZ) {
        double dx = toX - (double) fromX;
        double dz = toZ - (double) fromZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Where to aim for the next leg: the goal itself when it is close enough,
     * otherwise a point {@code legLength} blocks along the straight line to it.
     *
     * @return {x, z} of the aim point
     */
    public static int[] aim(int fromX, int fromZ, int toX, int toZ, int legLength) {
        int leg = Math.max(1, legLength);
        double distance = distance(fromX, fromZ, toX, toZ);
        if (distance <= leg) {
            return new int[]{toX, toZ};
        }
        double scale = leg / distance;
        return new int[]{
                fromX + (int) Math.round((toX - (double) fromX) * scale),
                fromZ + (int) Math.round((toZ - (double) fromZ) * scale)};
    }

    /** True when the goal can be planned for directly, with no intermediate leg. */
    public static boolean withinOneLeg(int fromX, int fromZ, int toX, int toZ, int legLength) {
        return distance(fromX, fromZ, toX, toZ) <= Math.max(1, legLength);
    }
}
