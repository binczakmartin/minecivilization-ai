package ai.minecivilization.roads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Everywhere the colony knows how to walk to.
 *
 * <p>Citizens used to solve every journey from first principles, every time.
 * Twenty citizens walking the same two hundred blocks to the same coal seam
 * ran twenty independent searches, made twenty slightly different mistakes, and
 * left nothing behind for the twenty-first. This is the shared memory that
 * fixes that: trips are recorded, journeys that repeat are merged, and a route
 * that enough people walk becomes a road the colony maintains.</p>
 *
 * <p>Saved with the world, because a road network that evaporated on restart
 * would never get past a footpath.</p>
 */
public final class PathMemory extends SavedData {
    private static final String KEY = "minecivilization_roads";

    /** Upper bound on remembered routes; the least useful are forgotten first. */
    private static final int MAX_ROUTES = 192;

    private final Map<String, Route> routes = new LinkedHashMap<>();

    public static PathMemory get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PathMemory::new, PathMemory::load, null), KEY);
    }

    // ------------------------------------------------------------------ recording

    /**
     * Remember a completed journey.
     *
     * <p>Merged into an existing route when it runs between the same two
     * places, so a hundred trips make one well-known road rather than a hundred
     * one-use ones.</p>
     *
     * @return the route the trip belongs to, or null when it was too short to
     *         be worth remembering
     */
    @Nullable
    public Route recordTrip(ServerLevel level, BlockPos from, BlockPos to,
                            List<BlockPos> trail, long ticks) {
        if (from == null || to == null) return null;
        int length = (int) Math.sqrt(from.distSqr(to));
        if (!RoutePolicy.worthRemembering(length)) return null;

        long now = level.getGameTime();
        Route route = matching(from, to);
        if (route == null) {
            if (routes.size() >= MAX_ROUTES) forgetLeastUseful(now);
            route = new Route(UUID.randomUUID().toString().substring(0, 8), from, to);
            route.createdAtGameTime = now;
            route.lengthBlocks = length;
            routes.put(route.id, route);
        }
        route.recordTrip(trail, ticks, now);
        setDirty();
        return route;
    }

    /** Something went wrong along this route — a death, a mob, a fall. */
    public void reportDanger(ServerLevel level, BlockPos where) {
        Route route = nearestPassing(where, 16);
        if (route == null) return;
        route.reportDanger(level.getGameTime());
        setDirty();
    }

    /** Record that a stretch of a road has been improved to its pending grade. */
    public void recordImprovement(Route route, int waypointsDone) {
        if (route == null) return;
        route.improvedUpTo = Math.max(route.improvedUpTo, waypointsDone);
        RoadGrade pending = route.pendingUpgrade();
        if (pending != null && route.improvedUpTo >= Math.max(1, route.waypoints.size())) {
            route.grade = pending;
            route.improvedUpTo = 0;
        }
        setDirty();
    }

    public void rename(Route route, String name) {
        if (route == null || name == null) return;
        route.name = name;
        setDirty();
    }

    // ------------------------------------------------------------------ queries

    /** The route that runs between these two places, or null. */
    @Nullable
    public Route matching(BlockPos from, BlockPos to) {
        int[] a = {from.getX(), from.getY(), from.getZ()};
        int[] b = {to.getX(), to.getY(), to.getZ()};
        for (Route route : routes.values()) {
            int[] c = {route.from.getX(), route.from.getY(), route.from.getZ()};
            int[] d = {route.to.getX(), route.to.getY(), route.to.getZ()};
            if (RoutePolicy.sameJourney(a, b, c, d)) return route;
        }
        return null;
    }

    /**
     * The best route to take from here toward there.
     *
     * <p>Not necessarily one that goes the whole way: a route that starts near
     * the traveller and ends anywhere meaningfully closer to the destination is
     * worth joining, which is how a network of short roads adds up to a long
     * journey.</p>
     */
    @Nullable
    public Route bestToward(BlockPos from, BlockPos to) {
        Route best = null;
        double bestScore = 0;
        double directDist = Math.sqrt(from.distSqr(to));
        for (Route route : routes.values()) {
            if (route.isDangerous() || route.waypoints.isEmpty()) continue;
            BlockPos[] ends = route.orientedFrom(from);
            double joinCost = Math.sqrt(ends[0].distSqr(from));
            // Joining a road must not be most of the journey.
            if (joinCost > Math.max(32, directDist * 0.5)) continue;
            double remaining = Math.sqrt(ends[1].distSqr(to));
            double saved = directDist - remaining - joinCost;
            if (saved <= 8) continue;
            double score = saved * route.travelValue();
            if (score > bestScore) {
                bestScore = score;
                best = route;
            }
        }
        return best;
    }

    /**
     * The next place to walk to when following remembered roads toward a goal.
     *
     * <p>This is the cheapest rung of the rescue ladder: before pathfinding,
     * before digging, ask whether the colony already knows a way.</p>
     */
    @Nullable
    public BlockPos waypointToward(BlockPos from, BlockPos to) {
        Route route = bestToward(from, to);
        if (route == null) return null;
        List<BlockPos> ordered = route.waypointsFrom(from);
        // Aim past whatever is already behind us, so following a road makes
        // progress instead of walking back to its head.
        double best = Double.MAX_VALUE;
        int nearest = 0;
        for (int i = 0; i < ordered.size(); i++) {
            double d = ordered.get(i).distSqr(from);
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        // A waypoint the citizen is already standing on is no step at all: the
        // walk "arrives" at once and the rescue asks again, thousands of times.
        // Take the first one further along that is both a real move and nearer
        // the destination; at the end of the road there is none, and the
        // caller walks the rest directly.
        double here = from.distSqr(to);
        for (int i = nearest + 1; i < ordered.size(); i++) {
            BlockPos candidate = ordered.get(i);
            if (candidate.distSqr(from) <= 16) continue;
            if (candidate.distSqr(to) >= here) continue;
            return candidate;
        }
        return null;
    }

    /** The route whose surface most deserves a citizen's attention right now. */
    @Nullable
    public Route nextToImprove(long now) {
        Route best = null;
        double bestScore = 0;
        for (Route route : routes.values()) {
            if (route.pendingUpgrade() == null || route.waypoints.isEmpty()) continue;
            double score = route.upkeepScore(now);
            if (score > bestScore) {
                bestScore = score;
                best = route;
            }
        }
        return best;
    }

    /** A route passing within {@code radius} of a position. */
    @Nullable
    public Route nearestPassing(BlockPos where, int radius) {
        long limit = (long) radius * radius;
        for (Route route : routes.values()) {
            for (BlockPos point : route.waypoints) {
                if (point.distSqr(where) <= limit) return route;
            }
            if (route.from.distSqr(where) <= limit || route.to.distSqr(where) <= limit) {
                return route;
            }
        }
        return null;
    }

    public List<Route> all() {
        return new ArrayList<>(routes.values());
    }

    /** Roads worth showing a player, busiest first. */
    public List<Route> busiest(int limit) {
        List<Route> sorted = all();
        sorted.sort(Comparator.comparingInt((Route r) -> r.uses).reversed());
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    public int count() {
        return routes.size();
    }

    /** Roads that have actually been built, as opposed to merely remembered. */
    public int builtCount() {
        int n = 0;
        for (Route route : routes.values()) {
            if (route.grade != RoadGrade.TRACK) n++;
        }
        return n;
    }

    private void forgetLeastUseful(long now) {
        Route worst = null;
        double worstScore = Double.MAX_VALUE;
        for (Route route : routes.values()) {
            double score = route.upkeepScore(now);
            if (score < worstScore) {
                worstScore = score;
                worst = route;
            }
        }
        if (worst != null) routes.remove(worst.id);
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Route route : routes.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", route.id);
            t.putString("name", route.name == null ? "" : route.name);
            t.putLong("from", route.from.asLong());
            t.putLong("to", route.to.asLong());
            t.putInt("uses", route.uses);
            t.putInt("length", route.lengthBlocks);
            t.putDouble("avg", route.averageTicks);
            t.putInt("danger", route.dangerReports);
            t.putLong("lastUsed", route.lastUsedAtGameTime);
            t.putLong("created", route.createdAtGameTime);
            t.putString("grade", route.grade.name());
            t.putInt("improved", route.improvedUpTo);
            long[] points = new long[route.waypoints.size()];
            for (int i = 0; i < points.length; i++) points[i] = route.waypoints.get(i).asLong();
            t.putLongArray("waypoints", points);
            list.add(t);
        }
        tag.put("routes", list);
        return tag;
    }

    public static PathMemory load(CompoundTag tag,
                                  net.minecraft.core.HolderLookup.Provider registries) {
        PathMemory memory = new PathMemory();
        ListTag list = tag.getList("routes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Route route = new Route(t.getString("id"),
                    BlockPos.of(t.getLong("from")), BlockPos.of(t.getLong("to")));
            route.name = t.getString("name");
            route.uses = t.getInt("uses");
            route.lengthBlocks = t.getInt("length");
            route.averageTicks = t.getDouble("avg");
            route.dangerReports = t.getInt("danger");
            route.lastUsedAtGameTime = t.getLong("lastUsed");
            route.createdAtGameTime = t.getLong("created");
            try {
                route.grade = RoadGrade.valueOf(t.getString("grade"));
            } catch (IllegalArgumentException ex) {
                route.grade = RoadGrade.TRACK;   // a grade removed between versions
            }
            route.improvedUpTo = t.getInt("improved");
            for (long packed : t.getLongArray("waypoints")) {
                route.waypoints.add(BlockPos.of(packed));
            }
            memory.routes.put(route.id, route);
        }
        return memory;
    }
}
