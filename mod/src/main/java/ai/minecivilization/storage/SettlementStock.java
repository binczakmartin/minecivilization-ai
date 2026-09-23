package ai.minecivilization.storage;

import java.util.LinkedHashMap;
import java.util.Map;

import ai.minecivilization.inventory.CitizenInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * What the settlement collectively owns, read straight out of its chests.
 *
 * <p>This is the difference between a citizen that re-mines iron the colony
 * already has and one that walks to the warehouse. It is deliberately <em>not</em>
 * a cached ledger: Minecraft's containers are the single source of truth, and a
 * player who empties a chest by hand must be able to change what citizens
 * believe without the mod noticing a "desync".</p>
 *
 * <p>Knowledge is bounded by distance and by access policy, so this never turns
 * into server omniscience: a citizen only counts public containers its own
 * settlement has registered and could physically walk to.</p>
 */
public final class SettlementStock {

    /** How far a citizen will consider a container to be "its" warehouse. */
    public static final int DEFAULT_RADIUS = 128;

    private SettlementStock() {
    }

    /**
     * Everything held in reachable public containers, item id to total count.
     *
     * @param from   where the citizen is standing
     * @param radius how far it is willing to walk for a withdrawal
     */
    public static Map<String, Integer> totals(ServerLevel level, BlockPos from, int radius) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        long limit = (long) radius * radius;

        for (StorageNode node : StorageManager.get(level).all()) {
            if (!node.isPublic()) continue;
            if (node.containerPos().distSqr(from) > limit) continue;
            Container container = containerAt(level, node);
            if (container == null) continue;

            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) continue;
                totals.merge(CitizenInventory.idOf(stack), stack.getCount(), Integer::sum);
            }
        }
        return totals;
    }

    public static Map<String, Integer> totals(ServerLevel level, BlockPos from) {
        return totals(level, from, DEFAULT_RADIUS);
    }

    /** How many of one item sit in a single container right now. */
    public static int countIn(ServerLevel level, StorageNode node, String itemId) {
        Container container = containerAt(level, node);
        if (container == null) return 0;
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && CitizenInventory.idOf(stack).equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Total of one item across the reachable warehouse. */
    public static int count(ServerLevel level, BlockPos from, String itemId) {
        return totals(level, from).getOrDefault(itemId, 0);
    }

    /**
     * The container to go to for {@code itemId}, or null when the settlement
     * has none. Prefers a container that can cover the whole request.
     */
    public static StorageNode locate(ServerLevel level, BlockPos from,
                                     String itemId, int wanted) {
        StorageNode covering = StorageManager.nearestWith(level, from, itemId, wanted);
        return covering != null
                ? covering
                : StorageManager.nearestWith(level, from, itemId, 1);
    }

    /**
     * The block entity behind a registered node, or null when the container was
     * broken since it was registered.
     */
    public static Container containerAt(ServerLevel level, StorageNode node) {
        BlockPos pos = node.containerPos();
        if (!level.isLoaded(pos)) return null; // never force-load a chunk just to count
        if (!StorageDiscovery.isStorageBlock(level, pos)) return null;
        return level.getBlockEntity(pos) instanceof Container container ? container : null;
    }
}
