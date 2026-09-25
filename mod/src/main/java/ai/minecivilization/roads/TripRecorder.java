package ai.minecivilization.roads;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * One citizen's breadcrumbs.
 *
 * <p>Ants do not plan trails; they leave them. This does the same thing: it
 * watches where a citizen actually goes and, when the citizen stops somewhere
 * for long enough to count as having arrived, hands the journey to the colony's
 * {@link PathMemory}. Nothing has to ask for a route to be recorded, which is
 * the point — a road network that only appeared when someone remembered to log
 * a trip would stay empty.</p>
 *
 * <p>Attached to the citizen and never persisted: an interrupted journey is
 * not worth saving, and the routes it produces are.</p>
 */
public final class TripRecorder {

    /** Ticks between breadcrumbs. Fine enough for a road, cheap enough for a hundred citizens. */
    private static final int SAMPLE_INTERVAL = 10;
    /** Ticks spent inside {@link #ARRIVAL_RADIUS} before the journey counts as over. */
    private static final int ARRIVAL_TICKS = 60;
    /** How still a citizen has to be to have "arrived", in blocks. */
    private static final int ARRIVAL_RADIUS = 6;
    /** Breadcrumbs kept before the oldest are dropped — about 800 blocks of walking. */
    private static final int MAX_SAMPLES = 256;

    private final List<BlockPos> trail = new ArrayList<>();
    private BlockPos origin;
    private BlockPos settledAt;
    private long startedAt;
    private long settledSince;
    private long lastSampleAt = Long.MIN_VALUE;

    /**
     * Called every tick; does work occasionally.
     *
     * @return the route the colony learned from a completed journey, or null
     */
    public Route tick(ServerLevel level, BlockPos at) {
        if (level == null || at == null) return null;
        long now = level.getGameTime();
        if (lastSampleAt != Long.MIN_VALUE && now - lastSampleAt < SAMPLE_INTERVAL) return null;
        lastSampleAt = now;

        if (origin == null) {
            begin(at, now);
            return null;
        }

        trail.add(at.immutable());
        while (trail.size() > MAX_SAMPLES) trail.remove(0);

        // Still moving: reset the arrival clock and carry on.
        if (settledAt == null
                || settledAt.distSqr(at) > (long) ARRIVAL_RADIUS * ARRIVAL_RADIUS) {
            settledAt = at.immutable();
            settledSince = now;
            return null;
        }
        if (now - settledSince < ARRIVAL_TICKS) return null;

        Route learned = commit(level, at, now);
        begin(at, now);
        return learned;
    }

    /** Throw the current journey away — the citizen was teleported or died. */
    public void reset() {
        trail.clear();
        origin = null;
        settledAt = null;
    }

    /** Blocks walked so far on the journey in progress. */
    public int distanceSoFar() {
        if (origin == null || trail.isEmpty()) return 0;
        return (int) Math.sqrt(origin.distSqr(trail.get(trail.size() - 1)));
    }

    private void begin(BlockPos at, long now) {
        trail.clear();
        origin = at.immutable();
        trail.add(origin);
        settledAt = origin;
        settledSince = now;
        startedAt = now;
    }

    private Route commit(ServerLevel level, BlockPos at, long now) {
        if (origin == null || trail.size() < 2) return null;
        // A journey that ended where it started is pacing, not travelling.
        if (!RoutePolicy.worthRemembering((int) Math.sqrt(origin.distSqr(at)))) return null;
        return PathMemory.get(level).recordTrip(level, origin, at,
                new ArrayList<>(trail), now - startedAt);
    }
}
