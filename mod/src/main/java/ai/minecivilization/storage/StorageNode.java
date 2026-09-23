package ai.minecivilization.storage;

/**
 * A registered storage location. Citizens cannot magically access civilization
 * inventory — resources physically live in Minecraft containers; this registry
 * only knows where those containers are.
 */
public final class StorageNode {
    public String storageId;
    public int containerX;
    public int containerY;
    public int containerZ;
    public String ownerId;       // citizen/company id or null
    public String accessPolicy;  // PUBLIC | OWNER | PROJECT
    public long lastDepositedAt;
    /**
     * What this chest is for. Null until a citizen needs somewhere to put a
     * category and claims the nearest free container — the warehouse sorts
     * itself as the settlement grows rather than being laid out up front.
     */
    public ItemCategory category;

    public StorageNode() {
    }

    public StorageNode(String storageId, int x, int y, int z) {
        this.storageId = storageId;
        this.containerX = x;
        this.containerY = y;
        this.containerZ = z;
        this.ownerId = null;
        this.accessPolicy = "PUBLIC";
        this.category = null;
    }

    /** Anyone in the settlement may take from and add to this container. */
    public boolean isPublic() {
        return accessPolicy == null || "PUBLIC".equals(accessPolicy);
    }

    /** True when this container is the right home for {@code itemId}. */
    public boolean accepts(String itemId) {
        return category != null && category == ItemCategory.of(itemId);
    }

    public net.minecraft.core.BlockPos containerPos() {
        return new net.minecraft.core.BlockPos(containerX, containerY, containerZ);
    }
}
