package ai.minecivilization.roads;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * One journey the colony makes often enough to have an opinion about.
 *
 * <p>A route is not a path: pathfinding answers "can I get from here to there
 * right now", and forgets. This remembers — where the journey goes, how many
 * citizens have made it, how long it takes them, whether anyone died on it,
 * and how much of it has been built. That memory is what lets the twentieth
 * traveller take a paved road instead of re-deriving a way through the
 * woods.</p>
 */
public final class Route {

    public final String id;
    /** Both ends, as first recorded. Journeys are bidirectional. */
    public BlockPos from;
    public BlockPos to;
    /** Ordered points along the way, sparse enough to be a road plan. */
    public final List<BlockPos> waypoints = new ArrayList<>();

    /** Name shown on signs and in the road list. */
    public String name = "";

    public int uses;
    public int lengthBlocks;
    /** Rolling mean of how long the journey takes, in ticks. */
    public double averageTicks;
    public int dangerReports;
    public long lastUsedAtGameTime;
    public long createdAtGameTime;
    public RoadGrade grade = RoadGrade.TRACK;
    /** How many waypoints of the current grade's work are already done. */
    public int improvedUpTo;

    public Route(String id, BlockPos from, BlockPos to) {
        this.id = id;
        this.from = from.immutable();
        this.to = to.immutable();
    }

    // ------------------------------------------------------------------ updating

    /**
     * Fold another trip along this journey into what is remembered.
     *
     * <p>The waypoints of a faster trip replace the old ones: a route that is
     * walked a hundred times should converge on the best way anyone has found,
     * not on the first way anyone stumbled into.</p>
     */
    public void recordTrip(List<BlockPos> trail, long ticks, long gameTime) {
        uses++;
        lastUsedAtGameTime = gameTime;
        averageTicks = averageTicks <= 0 ? ticks : averageTicks * 0.8 + ticks * 0.2;

        if (trail == null || trail.size() < 2) return;
        int length = RoutePolicy.lengthOf(toIntTriples(trail));
        boolean better = waypoints.isEmpty() || ticks < averageTicks || length < lengthBlocks;
        if (!better) return;

        waypoints.clear();
        for (int[] point : RoutePolicy.compact(toIntTriples(trail))) {
            waypoints.add(new BlockPos(point[0], point[1], point[2]));
        }
        lengthBlocks = length;
        // A better line means the built surface no longer matches the route;
        // improvement restarts rather than claiming work it did not do.
        improvedUpTo = 0;
    }

    public void reportDanger(long gameTime) {
        dangerReports++;
        lastUsedAtGameTime = gameTime;
    }

    /** True once the route is bad enough that traffic should go elsewhere. */
    public boolean isDangerous() {
        return dangerReports >= RoutePolicy.DANGEROUS_AT;
    }

    /** The work this road has earned next, or null when it is up to date. */
    public RoadGrade pendingUpgrade() {
        return RoutePolicy.upgradeFor(grade, uses, dangerReports);
    }

    public double upkeepScore(long now) {
        return RoutePolicy.upkeepScore(uses, now - lastUsedAtGameTime,
                lengthBlocks, dangerReports);
    }

    public double travelValue() {
        return RoutePolicy.travelValue(grade, uses, dangerReports);
    }

    /**
     * The end of this route nearest a position, and the other end.
     *
     * @return {near, far}
     */
    public BlockPos[] orientedFrom(BlockPos position) {
        return from.distSqr(position) <= to.distSqr(position)
                ? new BlockPos[]{from, to}
                : new BlockPos[]{to, from};
    }

    /** Waypoints in travel order for a journey starting at {@code start}. */
    public List<BlockPos> waypointsFrom(BlockPos start) {
        List<BlockPos> ordered = new ArrayList<>(waypoints);
        if (!ordered.isEmpty() && from.distSqr(start) > to.distSqr(start)) {
            java.util.Collections.reverse(ordered);
        }
        return ordered;
    }

    /** A readable name, generated when nothing better was supplied. */
    public String displayName() {
        if (name != null && !name.isBlank()) return name;
        return from.getX() + "," + from.getZ() + " → " + to.getX() + "," + to.getZ();
    }

    private static List<int[]> toIntTriples(List<BlockPos> points) {
        List<int[]> out = new ArrayList<>(points.size());
        for (BlockPos p : points) out.add(new int[]{p.getX(), p.getY(), p.getZ()});
        return out;
    }
}
