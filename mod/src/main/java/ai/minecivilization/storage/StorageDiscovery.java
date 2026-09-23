package ai.minecivilization.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;

/**
 * Finds the settlement's containers so citizens can actually use them.
 *
 * <p>Registering a chest was, until now, something nothing ever did: the
 * warehouse registry existed, every skill read from it, and it was always
 * empty — so {@code known_storage} stayed at zero and no citizen ever delivered
 * anything. Storage has to be <em>discovered</em>, because the chests are put
 * down by players and citizens as the settlement grows, not declared up front.</p>
 *
 * <p>This half handles containers appearing under someone's hand — placed by a
 * player or by a citizen. Containers that were already standing when the colony
 * arrived, and registrations whose container has since been broken, are picked
 * up by {@link ai.minecivilization.colony.ColonyCensus}, which walks the town
 * anyway to count beds.</p>
 */
public final class StorageDiscovery {

    private StorageDiscovery() {
    }

    /**
     * Register a container the moment it is placed. Idempotent: the storage id
     * is derived from the position, so placing, breaking and replacing a chest
     * in the same spot reuses the same registration.
     */
    public static boolean onContainerPlaced(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        if (!isStorageBlock(level, pos)
                || !(level.getBlockEntity(pos) instanceof Container)) return false;

        StorageManager manager = StorageManager.get(level);
        String id = idFor(pos);
        for (StorageNode existing : manager.all()) {
            if (existing.storageId.equals(id)) return false;
        }
        manager.register(new StorageNode(id, pos.getX(), pos.getY(), pos.getZ()));
        return true;
    }

    /** Drop the registration when a container is broken. */
    public static void onContainerRemoved(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return;
        StorageManager.get(level).remove(idFor(pos));
    }

    /** Only settlement containers count; furnaces also implement Container. */
    public static boolean isStorageBlock(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        var state = level.getBlockState(pos);
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.BARREL)
                || state.is(Blocks.WHITE_SHULKER_BOX)
                || state.is(Blocks.ORANGE_SHULKER_BOX)
                || state.is(Blocks.MAGENTA_SHULKER_BOX)
                || state.is(Blocks.LIGHT_BLUE_SHULKER_BOX)
                || state.is(Blocks.YELLOW_SHULKER_BOX)
                || state.is(Blocks.LIME_SHULKER_BOX)
                || state.is(Blocks.PINK_SHULKER_BOX)
                || state.is(Blocks.GRAY_SHULKER_BOX)
                || state.is(Blocks.LIGHT_GRAY_SHULKER_BOX)
                || state.is(Blocks.CYAN_SHULKER_BOX)
                || state.is(Blocks.PURPLE_SHULKER_BOX)
                || state.is(Blocks.BLUE_SHULKER_BOX)
                || state.is(Blocks.BROWN_SHULKER_BOX)
                || state.is(Blocks.GREEN_SHULKER_BOX)
                || state.is(Blocks.RED_SHULKER_BOX)
                || state.is(Blocks.BLACK_SHULKER_BOX);
    }

    /** Position-derived, stable across restarts and re-placements. */
    static String idFor(BlockPos pos) {
        return "chest_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
    }
}
