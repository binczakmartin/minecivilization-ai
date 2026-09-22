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
            if (node != null) return node;
        }
        return null;
    }

    /** Nearest registered container to a position, or null. */
    public StorageNode nearest(BlockPos from) {
        StorageNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (StorageNode node : nodes.values()) {
            double d = node.containerPos().distSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = node;
            }
        }
        return best;
    }

    public static StorageNode nearest(ServerLevel level, BlockPos from) {
        return get(level).nearest(from);
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
            manager.nodes.put(node.storageId, node);
        }
        return manager;
    }
}
