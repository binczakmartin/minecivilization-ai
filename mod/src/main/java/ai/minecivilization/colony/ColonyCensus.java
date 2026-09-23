package ai.minecivilization.colony;

import ai.minecivilization.storage.StorageDiscovery;
import ai.minecivilization.storage.StorageManager;
import ai.minecivilization.storage.StorageNode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * The colony taking stock of its own land.
 *
 * <p>One slow pass over the town answers the two questions nothing else can:
 * which containers exist (so citizens can use them) and how many beds there
 * are (so the colony knows whether it can grow). Both were previously
 * unanswerable — the storage registry was never written to, and nobody counted
 * beds at all.</p>
 *
 * <p>Deliberately one sweep rather than two: walking the same forty thousand
 * cells twice a minute to ask two questions about them would be wasteful. The
 * pass is sliced across ticks and bounded to the town, so it can never become
 * a whole-world scan or stall a tick.</p>
 */
public final class ColonyCensus {

    /** Horizontal reach of the survey, in blocks from the town centre. */
    static final int RADIUS = 48;
    /** Vertical reach either side of the centre — cellars and upper floors. */
    static final int HEIGHT = 16;
    /** Cells examined per server tick. */
    private static final int CELLS_PER_TICK = 2_048;
    /** Ticks of rest between completed surveys. */
    private static final int INTERVAL = 600;

    private static BlockPos origin;
    private static long cursor;
    private static int cooldown;

    private static int bedsInProgress;
    private static int beds;
    private static boolean surveyed;

    private ColonyCensus() {
    }

    /** Beds counted by the last completed survey. */
    public static int beds() {
        return beds;
    }

    /** False until the first survey finishes — "0 beds" is not yet knowledge. */
    public static boolean hasSurveyed() {
        return surveyed;
    }

    public static void reset() {
        origin = null;
        cursor = 0;
        cooldown = 0;
        bedsInProgress = 0;
        beds = 0;
        surveyed = false;
    }

    /** Advance the survey by one slice. Safe to call every tick. */
    public static void tick(ServerLevel level) {
        if (level == null) return;
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        BlockPos center = ZoneManager.get(level).townCenter(level);
        if (origin == null || !origin.equals(center)) {
            origin = center;
            cursor = 0;
            bedsInProgress = 0;
        }

        int width = RADIUS * 2 + 1;
        int height = HEIGHT * 2 + 1;
        long total = (long) width * width * height;

        int examined = 0;
        while (cursor < total && examined < CELLS_PER_TICK) {
            int x = (int) (cursor % width);
            int z = (int) ((cursor / width) % width);
            int y = (int) (cursor / ((long) width * width));
            cursor++;
            examined++;

            BlockPos pos = origin.offset(x - RADIUS, y - HEIGHT, z - RADIUS);
            if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
                continue;
            }
            // Never force-load a chunk just to survey it.
            if (!level.isLoaded(pos)) continue;

            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof BedBlock) {
                // A bed is two blocks; count the head so each bed counts once.
                if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
                    bedsInProgress++;
                }
            } else if (StorageDiscovery.isStorageBlock(level, pos)
                    && level.getBlockEntity(pos) instanceof Container) {
                StorageDiscovery.onContainerPlaced(level, pos);
            }
            // The same pass writes down every workbench, furnace, stonecutter
            // and mechanism it walks over, so the colony stops rediscovering
            // its own infrastructure on every single job.
            LandmarkRegistry.get(level).notice(level, pos);
        }

        if (cursor >= total) {
            beds = bedsInProgress;
            bedsInProgress = 0;
            surveyed = true;
            pruneBrokenContainers(level);
            LandmarkRegistry.get(level).prune(level);
            cursor = 0;
            cooldown = INTERVAL;
        }
    }

    /**
     * Forget containers that are no longer there. Unloaded chunks are left
     * alone: "I cannot see it right now" is not "it is gone".
     */
    private static void pruneBrokenContainers(ServerLevel level) {
        StorageManager manager = StorageManager.get(level);
        for (StorageNode node : manager.all()) {
            BlockPos pos = node.containerPos();
            if (!level.isLoaded(pos)) continue;
            if (!StorageDiscovery.isStorageBlock(level, pos)
                    || !(level.getBlockEntity(pos) instanceof Container)) {
                manager.remove(node.storageId);
            }
        }
    }
}
