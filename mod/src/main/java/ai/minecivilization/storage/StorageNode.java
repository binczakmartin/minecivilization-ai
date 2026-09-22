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

    public StorageNode() {
    }

    public StorageNode(String storageId, int x, int y, int z) {
        this.storageId = storageId;
        this.containerX = x;
        this.containerY = y;
        this.containerZ = z;
        this.ownerId = null;
        this.accessPolicy = "PUBLIC";
    }

    public net.minecraft.core.BlockPos containerPos() {
        return new net.minecraft.core.BlockPos(containerX, containerY, containerZ);
    }
}
