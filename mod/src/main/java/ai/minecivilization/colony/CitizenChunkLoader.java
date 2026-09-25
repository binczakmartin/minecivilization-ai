package ai.minecivilization.colony;

import java.util.HashSet;
import java.util.Set;

import ai.minecivilization.MineCivilization;
import ai.minecivilization.config.ModConfig;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;

/**
 * Citizens carry their own loaded world around with them, the way a player does.
 *
 * <p>The colony held its own centre open, which kept the town alive but did
 * nothing for the people working outside it. A lumberjack a hundred blocks out
 * was standing in terrain that nobody was ticking, and that has consequences
 * far beyond the citizen freezing: <em>random ticks never fire there</em>. No
 * random ticks means leaves never decay, so every felled tree left its canopy
 * hanging in the air permanently; crops never grow; saplings never become
 * trees. The settlement looked haunted.</p>
 *
 * <p>Each citizen now holds a small ticking area around itself. Ticking
 * matters — a merely <em>loaded</em> chunk still does not random-tick, which
 * is exactly the trap the town-centre loader fell into.</p>
 *
 * <p>Chunks are real cost, so the radius is small, the set is recomputed as a
 * diff rather than torn down and rebuilt, and a citizen that dies or unloads
 * takes its claim with it.</p>
 */
public final class CitizenChunkLoader {

    /** Ticks between recomputations. Citizens walk, they do not teleport. */
    private static final int CHECK_INTERVAL = 40;

    /** Chunks either side of a citizen. One ring is enough to work in. */
    private static final int RADIUS = 1;

    /** Ceiling on the whole colony's personal chunks, whatever the population. */
    private static final int MAX_CHUNKS = 256;

    private static final Set<ChunkPos> held = new HashSet<>();
    private static int tickCounter;

    private CitizenChunkLoader() {
    }

    public static int heldChunkCount() {
        return held.size();
    }

    public static void reset() {
        held.clear();
        tickCounter = 0;
    }

    /** Called every server tick; recomputes occasionally. */
    public static void tick(ServerLevel level) {
        if (level == null) return;
        if (++tickCounter % CHECK_INTERVAL != 0) return;

        if (!ModConfig.KEEP_COLONY_LOADED.get()) {
            if (!held.isEmpty()) releaseAll(level);
            return;
        }

        Set<ChunkPos> wanted = new HashSet<>();
        for (CitizenEntity citizen : CitizenIndex.all()) {
            if (citizen.isRemoved() || citizen.level() != level) continue;
            ChunkPos at = new ChunkPos(citizen.blockPosition());
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    if (wanted.size() >= MAX_CHUNKS) break;
                    wanted.add(new ChunkPos(at.x + dx, at.z + dz));
                }
            }
        }

        // Diff, so a colony standing still does no work and a colony on the
        // move only pays for the chunks that actually changed.
        for (ChunkPos pos : new HashSet<>(held)) {
            if (wanted.contains(pos)) continue;
            force(level, pos, false);
            held.remove(pos);
        }
        for (ChunkPos pos : wanted) {
            if (held.contains(pos)) continue;
            if (force(level, pos, true)) held.add(pos);
        }
    }

    private static void releaseAll(ServerLevel level) {
        for (ChunkPos pos : held) {
            force(level, pos, false);
        }
        held.clear();
    }

    /**
     * Hold or release one chunk, with ticking enabled.
     *
     * <p>{@code ticking = true} is the whole point: a loaded chunk that does
     * not tick is why the canopies never fell.</p>
     */
    private static boolean force(ServerLevel level, ChunkPos pos, boolean add) {
        return ForgeChunkManager.forceChunk(level, MineCivilization.MOD_ID,
                CHUNK_OWNER, pos.x, pos.z, add, true);
    }

    /**
     * One owner for every citizen chunk.
     *
     * <p>A per-citizen owner would be tidier but leaks: a citizen that dies far
     * from home never gets the chance to release its own ticket, and the world
     * keeps the chunk open for good. One owner plus an explicit diff can always
     * be reconciled.</p>
     */
    private static final java.util.UUID CHUNK_OWNER =
            java.util.UUID.nameUUIDFromBytes("minecivilization:citizens".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
}
