package ai.minecivilization.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-saved registry of storage containers (chests etc.).
 * Minecraft stays authoritative: this only records positions.
 */
public final class StorageManager extends SavedData {
    private static final String KEY = "minecivilization_storage";

    private final Map<String, StorageNode> nodes = new HashMap<>();

    public static StorageManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(StorageManager::new, StorageManager::load, null),
                KEY);
    }

    public static StorageNode resolve(ServerLevel level, String storageId) {
        StorageManager manager = get(level);
        if (storageId != null && !storageId.isEmpty() && !"nearest".equals(storageId)) {
            StorageNode node = manager.nodes.get(storageId);
            if (node != null && isLive(level, node)) return node;
            // Keep an unloaded registration: absence from the loaded world is
            // not evidence that a player removed the chest.  Once the chunk is
            // loaded, active()/nearest() will validate and remove it if stale.
            if (node != null && level.isLoaded(node.containerPos())) manager.remove(node.storageId);
        }
        return null;
    }

    /** Nearest live registered container to a position, or null. */
    public static StorageNode nearest(ServerLevel level, BlockPos from) {
        StorageManager manager = get(level);
        StorageNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (StorageNode node : manager.nodes.values()) {
            if (!manager.isLive(level, node)) continue;
            double d = node.containerPos().distSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = node;
            }
        }
        return best;
    }

    /**
     * Where {@code itemId} belongs.
     *
     * <p>A chest already dedicated to the item's category wins. Failing that,
     * the nearest container with no purpose yet is <em>claimed</em> for the
     * category, which is how a heap of chests turns into a sorted warehouse
     * without anyone planning one. Only when every chest is spoken for does an
     * item overflow into the nearest container regardless of category.</p>
     */
    public StorageNode bestForLive(ServerLevel level, String itemId, BlockPos from) {
        ItemCategory wanted = ItemCategory.of(itemId);

        StorageNode dedicated = nearestMatching(level, from, node -> node.category == wanted);
        if (dedicated != null) return dedicated;

        StorageNode free = nearestMatching(level, from, node -> node.category == null);
        if (free != null) {
            free.category = wanted;
            setDirty();
            return free;
        }
        return nearest(level, from);
    }

    public static StorageNode bestFor(ServerLevel level, String itemId, BlockPos from) {
        return get(level).bestForLive(level, itemId, from);
    }

    /** The nearest public container that actually holds {@code itemId} right now. */
    public StorageNode findWith(ServerLevel level, BlockPos from, String itemId, int atLeast) {
        return nearestMatching(level, from, node -> node.isPublic()
                && SettlementStock.countIn(level, node, itemId) >= Math.max(1, atLeast));
    }

    public static StorageNode nearestWith(ServerLevel level, BlockPos from,
                                          String itemId, int atLeast) {
        return get(level).findWith(level, from, itemId, atLeast);
    }

    private StorageNode nearestMatching(ServerLevel level, BlockPos from,
                                        java.util.function.Predicate<StorageNode> accept) {
        StorageNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (StorageNode node : nodes.values()) {
            if (!isLive(level, node) || !accept.test(node)) continue;
            double d = node.containerPos().distSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = node;
            }
        }
        return best;
    }

    /** Only loaded, still-existing containers are live work destinations. */
    private static boolean isLive(ServerLevel level, StorageNode node) {
        BlockPos pos = node.containerPos();
        if (!level.isLoaded(pos)) return false;
        return StorageDiscovery.isStorageBlock(level, pos)
                && level.getBlockEntity(pos) instanceof Container;
    }

    /** A live view used by callers that must not count stale persisted nodes. */
    public List<StorageNode> active(ServerLevel level) {
        List<StorageNode> out = new ArrayList<>();
        List<StorageNode> stale = new ArrayList<>();
        for (StorageNode node : nodes.values()) {
            if (isLive(level, node)) out.add(node);
            else if (level.isLoaded(node.containerPos())) stale.add(node);
        }
        for (StorageNode node : stale) remove(node.storageId);
        return out;
    }

    /** Containers dedicated to a category, nearest first — the aisle for a shelf. */
    public List<StorageNode> forCategory(ItemCategory category) {
        List<StorageNode> out = new ArrayList<>();
        for (StorageNode node : nodes.values()) {
            if (node.category == category) out.add(node);
        }
        return out;
    }

    public void register(StorageNode node) {
        nodes.put(node.storageId, node);
        setDirty();
    }

    public boolean remove(String storageId) {
        boolean removed = nodes.remove(storageId) != null;
        if (removed) setDirty();
        return removed;
    }

    public List<StorageNode> all() {
        return new ArrayList<>(nodes.values());
    }

    public static void markDeposited(ServerLevel level, StorageNode node, int amount) {
        node.lastDepositedAt = level.getGameTime();
        get(level).setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (StorageNode node : nodes.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", node.storageId);
            t.putInt("x", node.containerX);
            t.putInt("y", node.containerY);
            t.putInt("z", node.containerZ);
            if (node.ownerId != null) t.putString("owner", node.ownerId);
            t.putString("policy", node.accessPolicy);
            t.putLong("lastDeposit", node.lastDepositedAt);
            if (node.category != null) t.putString("category", node.category.name());
            list.add(t);
        }
        tag.put("nodes", list);
        return tag;
    }

    public static StorageManager load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        StorageManager manager = new StorageManager();
        ListTag list = tag.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            StorageNode node = new StorageNode(t.getString("id"),
                    t.getInt("x"), t.getInt("y"), t.getInt("z"));
            node.ownerId = t.contains("owner") ? t.getString("owner") : null;
            node.accessPolicy = t.contains("policy") ? t.getString("policy") : "PUBLIC";
            node.lastDepositedAt = t.getLong("lastDeposit");
            if (t.contains("category")) {
                try {
                    node.category = ItemCategory.valueOf(t.getString("category"));
                } catch (IllegalArgumentException ex) {
                    node.category = null; // category renamed between versions: re-claim later
                }
            }
            manager.nodes.put(node.storageId, node);
        }
        return manager;
    }
}
