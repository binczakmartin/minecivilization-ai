package ai.minecivilization.colony;

import java.util.HashSet;
import java.util.Set;

import ai.minecivilization.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Keeps the settlement loaded so it keeps living.
 *
 * <p>Minecraft only ticks chunks near a player. On a dedicated server that
 * means the moment you log out — or simply walk far enough away — the colony
 * stops: no gathering, no building, no growth. You come back an hour later to
 * find exactly what you left. For a settlement simulation that is the one
 * failure mode that makes the whole thing pointless.</p>
 *
 * <p>Forcing chunks is a real cost, because forced chunks tick forever whether
 * or not anyone is watching, so the area is bounded by config and centred on
 * the town rather than following citizens around the map.</p>
 */
public final class ColonyChunkLoader {

    /** Ticks between checks — the town centre moves rarely, if ever. */
    private static final int CHECK_INTERVAL = 200;

    private static final Set<ChunkPos> forced = new HashSet<>();
    private static ChunkPos anchor;
    private static int tickCounter;

    private ColonyChunkLoader() {
    }

    /** Drop all knowledge (not the world's forced-chunk list) on shutdown. */
    public static void reset() {
        forced.clear();
        anchor = null;
        tickCounter = 0;
    }

    /** How many chunks the colony is currently holding open. */
    public static int loadedChunkCount() {
        return forced.size();
    }

    /** Called every server tick; does real work occasionally. */
    public static void tick(ServerLevel level) {
        if (level == null) return;
        if (++tickCounter % CHECK_INTERVAL != 0) return;

        if (!ModConfig.KEEP_COLONY_LOADED.get()) {
            if (!forced.isEmpty()) release(level);
            return;
        }

        BlockPos center = ZoneManager.get(level).townCenter(level);
        ChunkPos wanted = new ChunkPos(center);
        int radius = ModConfig.KEEP_LOADED_RADIUS.get();

        if (wanted.equals(anchor) && forced.size() == expectedCount(radius)) {
            return;   // nothing has moved and nothing has changed
        }

        release(level);
        anchor = wanted;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(wanted.x + dx, wanted.z + dz);
                level.setChunkForced(pos.x, pos.z, true);
                forced.add(pos);
            }
        }
    }

    private static int expectedCount(int radius) {
        int side = 2 * radius + 1;
        return side * side;
    }

    /** Hand the chunks back so a moved or disabled colony stops paying for them. */
    private static void release(ServerLevel level) {
        for (ChunkPos pos : forced) {
            level.setChunkForced(pos.x, pos.z, false);
        }
        forced.clear();
        anchor = null;
    }
}
