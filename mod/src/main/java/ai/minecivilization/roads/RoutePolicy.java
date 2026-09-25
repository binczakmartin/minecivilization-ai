package ai.minecivilization.roads;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules a colony's road network follows, with no world attached.
 *
 * <p>Which two trips count as the same journey, how a wandering trail becomes
 * a small list of waypoints, when a well-trodden route has earned paving, and
 * which of thirty remembered routes is worth a citizen's afternoon — all of it
 * is arithmetic, and all of it decides what the settlement physically looks
 * like after a few hours. Keeping it pure means those thresholds are tested
 * rather than tuned by watching.</p>
 */
public final class RoutePolicy {

    /** Endpoints within this many blocks are treated as the same place. */
    public static final int ENDPOINT_TOLERANCE = 12;
    /** Waypoints closer together than this are redundant. */
    public static final int MIN_WAYPOINT_SPACING = 8;
    /** Waypoints kept per route — a road, not a breadcrumb trail. */
    public static final int MAX_WAYPOINTS = 24;
    /** Journeys shorter than this are not worth remembering as routes. */
    public static final int MIN_ROUTE_LENGTH = 24;
    /** Danger reports before a route is avoided rather than improved. */
    public static final int DANGEROUS_AT = 4;

    private RoutePolicy() {
    }

    // ------------------------------------------------------------------ identity

    /** Squared distance, in plain integers — no floating point in an identity test. */
    public static long distSqr(int ax, int ay, int az, int bx, int by, int bz) {
        long dx = ax - bx;
        long dy = ay - by;
        long dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** True when two journeys start and end in the same two places. */
    public static boolean sameJourney(int[] aFrom, int[] aTo, int[] bFrom, int[] bTo) {
        return sameJourney(aFrom, aTo, bFrom, bTo, ENDPOINT_TOLERANCE);
    }

    public static boolean sameJourney(int[] aFrom, int[] aTo, int[] bFrom, int[] bTo,
                                      int tolerance) {
        long limit = (long) tolerance * tolerance;
        boolean forward = distSqr(aFrom[0], aFrom[1], aFrom[2], bFrom[0], bFrom[1], bFrom[2]) <= limit
                && distSqr(aTo[0], aTo[1], aTo[2], bTo[0], bTo[1], bTo[2]) <= limit;
        // A road is walked in both directions; remembering each way separately
        // would halve every use count and stop any road ever being built.
        boolean reverse = distSqr(aFrom[0], aFrom[1], aFrom[2], bTo[0], bTo[1], bTo[2]) <= limit
                && distSqr(aTo[0], aTo[1], aTo[2], bFrom[0], bFrom[1], bFrom[2]) <= limit;
        return forward || reverse;
    }

    // ------------------------------------------------------------------ shape

    /**
     * Turn a tick-by-tick trail into a route's waypoints.
     *
     * <p>Two passes: drop points that are barely apart, then thin what is left
     * down to a fixed budget. The first and last points always survive, because
     * a route that forgot where it started is not a route.</p>
     */
    public static List<int[]> compact(List<int[]> trail) {
        return compact(trail, MIN_WAYPOINT_SPACING, MAX_WAYPOINTS);
    }

    public static List<int[]> compact(List<int[]> trail, int minSpacing, int maxPoints) {
        List<int[]> out = new ArrayList<>();
        if (trail == null || trail.isEmpty()) return out;

        long spacingSqr = (long) minSpacing * minSpacing;
        int[] last = null;
        for (int[] point : trail) {
            if (point == null || point.length != 3) continue;
            if (last == null || distSqr(last[0], last[1], last[2],
                    point[0], point[1], point[2]) >= spacingSqr) {
                out.add(new int[]{point[0], point[1], point[2]});
                last = point;
            }
        }
        int[] finish = trail.get(trail.size() - 1);
        if (out.isEmpty()) {
            out.add(new int[]{finish[0], finish[1], finish[2]});
        } else {
            int[] tail = out.get(out.size() - 1);
            if (tail[0] != finish[0] || tail[1] != finish[1] || tail[2] != finish[2]) {
                out.add(new int[]{finish[0], finish[1], finish[2]});
            }
        }
        if (out.size() <= maxPoints) return out;

        // Thin evenly rather than truncating: a road's far half matters as much
        // as its near half.
        List<int[]> thinned = new ArrayList<>(maxPoints);
        for (int i = 0; i < maxPoints; i++) {
            int index = (int) ((long) i * (out.size() - 1) / (maxPoints - 1));
            int[] point = out.get(index);
            if (thinned.isEmpty()) {
                thinned.add(point);
                continue;
            }
            int[] previous = thinned.get(thinned.size() - 1);
            if (previous != point) thinned.add(point);
        }
        return thinned;
    }

    /** Walked length of a polyline, in blocks. */
    public static int lengthOf(List<int[]> points) {
        if (points == null || points.size() < 2) return 0;
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            int[] a = points.get(i - 1);
            int[] b = points.get(i);
            total += Math.sqrt(distSqr(a[0], a[1], a[2], b[0], b[1], b[2]));
        }
        return (int) Math.round(total);
    }

    /** Worth keeping as a route at all? A three-block stroll is not a road. */
    public static boolean worthRemembering(int lengthBlocks) {
        return lengthBlocks >= MIN_ROUTE_LENGTH;
    }

    // ------------------------------------------------------------------ upkeep

    /**
     * The grade this route should be improved to next, or null when it is
     * already as good as its traffic justifies.
     *
     * <p>A dangerous route is never improved: paving the path through the
     * ravine that keeps killing people is the wrong answer, and the colony
     * should be routing around it instead.</p>
     */
    public static RoadGrade upgradeFor(RoadGrade current, int uses, int dangerReports) {
        if (dangerReports >= DANGEROUS_AT) return null;
        RoadGrade earned = RoadGrade.earnedBy(uses);
        if (earned.ordinal() <= current.ordinal()) return null;
        RoadGrade next = current.above();
        return next == null || next.ordinal() > earned.ordinal() ? null : next;
    }

    /**
     * How much a route deserves the colony's attention.
     *
     * <p>Busy beats long, recent beats stale, and danger counts heavily
     * against — a road nobody has walked in an hour does not need lamps.</p>
     */
    public static double upkeepScore(int uses, long ticksSinceUse, int lengthBlocks,
                                     int dangerReports) {
        if (uses <= 0) return 0;
        double recency = 1.0 / (1.0 + Math.max(0, ticksSinceUse) / 6000.0);
        double reach = 1.0 + Math.min(lengthBlocks, 400) / 200.0;
        double risk = 1.0 + dangerReports;
        return uses * recency * reach / risk;
    }

    /**
     * How much a route is worth *using* for a journey.
     *
     * <p>Higher is better. A well-built road is faster than open country even
     * when it is not the shortest line, which is why a colony with roads looks
     * organised rather than merely busy.</p>
     */
    public static double travelValue(RoadGrade grade, int uses, int dangerReports) {
        if (dangerReports >= DANGEROUS_AT) return 0;
        return (1.0 + grade.ordinal() * 0.5) * (1.0 + Math.min(uses, 200) / 100.0)
                / (1.0 + dangerReports * 0.5);
    }
}
