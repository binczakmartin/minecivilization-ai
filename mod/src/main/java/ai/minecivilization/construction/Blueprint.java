package ai.minecivilization.construction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A blueprint: blocks relative to an origin, with build-order metadata and a
 * computed bill of materials. Block states are stored as strings so they survive
 * serialization (stairs, rails, redstone components keep their properties) and
 * so BOM logic stays testable without touching the block registry.
 *
 * <p>Blueprints are NEVER pasted — builders must physically provide every block.</p>
 */
public final class Blueprint {
    public static final class BlockEntry {
        public final int x;
        public final int y;
        public final int z;
        public final String blockState; // e.g. "minecraft:oak_planks" or "minecraft:oak_stairs[facing=east]"

        public BlockEntry(int x, int y, int z, String blockState) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockState = blockState;
        }

        public String itemId() {
            int bracket = blockState.indexOf('[');
            String id = bracket >= 0 ? blockState.substring(0, bracket) : blockState;
            return id;
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z + "=" + blockState;
        }
    }

    public final String id;
    public final String name;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    /** Inclusive relative bounds; generated architecture may use an overhang at -1. */
    public final int minX;
    public final int minY;
    public final int minZ;
    public final int maxX;
    public final int maxY;
    public final int maxZ;
    private final List<BlockEntry> entries; // in build order

    public Blueprint(String id, String name, int sizeX, int sizeY, int sizeZ,
                     List<BlockEntry> entriesInBuildOrder) {
        this.id = id;
        this.name = name;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.entries = new ArrayList<>(entriesInBuildOrder);
        if (this.entries.isEmpty()) {
            this.minX = 0;
            this.minY = 0;
            this.minZ = 0;
            this.maxX = Math.max(0, sizeX - 1);
            this.maxY = Math.max(0, sizeY - 1);
            this.maxZ = Math.max(0, sizeZ - 1);
        } else {
            int loX = Integer.MAX_VALUE, loY = Integer.MAX_VALUE, loZ = Integer.MAX_VALUE;
            int hiX = Integer.MIN_VALUE, hiY = Integer.MIN_VALUE, hiZ = Integer.MIN_VALUE;
            for (BlockEntry entry : this.entries) {
                loX = Math.min(loX, entry.x);
                loY = Math.min(loY, entry.y);
                loZ = Math.min(loZ, entry.z);
                hiX = Math.max(hiX, entry.x);
                hiY = Math.max(hiY, entry.y);
                hiZ = Math.max(hiZ, entry.z);
            }
            this.minX = loX;
            this.minY = loY;
            this.minZ = loZ;
            this.maxX = hiX;
            this.maxY = hiY;
            this.maxZ = hiZ;
        }
    }

    public List<BlockEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public int blockCount() {
        return entries.size();
    }

    /** Actual inclusive footprint, including any generated overhang. */
    public int footprintWidth() {
        return maxX - minX + 1;
    }

    public int footprintHeight() {
        return maxY - minY + 1;
    }

    public int footprintDepth() {
        return maxZ - minZ + 1;
    }

    /**
     * True when this relative cell is inside the blueprint's bounding box.
     *
     * <p>A box, not the building: most of the volume of any structure is the
     * air inside it and the ground under it. Use {@link #hasEntryAt} for
     * "does the design put a block here?" — confusing the two is how the
     * colony once declared several thousand cells of open countryside
     * untouchable, trees and all, and then wondered why nobody could chop
     * anything.</p>
     */
    public boolean containsRelative(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** True when the design places a block at exactly this relative cell. */
    public boolean hasEntryAt(int x, int y, int z) {
        if (!containsRelative(x, y, z)) return false;
        return occupied().contains(key(x, y, z));
    }

    private java.util.Set<Long> occupied() {
        java.util.Set<Long> cells = occupiedCells;
        if (cells == null) {
            cells = new java.util.HashSet<>(entries.size() * 2);
            for (BlockEntry entry : entries) {
                cells.add(key(entry.x, entry.y, entry.z));
            }
            occupiedCells = cells;
        }
        return cells;
    }

    /** Packed relative coordinate; the ranges are far smaller than a world's. */
    private static long key(int x, int y, int z) {
        return ((long) (x + 1024) << 42) | ((long) (y + 1024) << 21) | (z + 1024);
    }

    /** Lazily built index of the cells the design actually fills. */
    private transient java.util.Set<Long> occupiedCells;

    /** Bill of materials: {"minecraft:stone_bricks": 12, ...} keyed by item id. */
    public Map<String, Integer> billOfMaterials() {
        Map<String, Integer> bom = new LinkedHashMap<>();
        for (BlockEntry entry : entries) {
            bom.merge(entry.itemId(), 1, Integer::sum);
        }
        return bom;
    }

    /** Total blocks of a given item id. */
    public int countOf(String itemId) {
        int total = 0;
        for (BlockEntry entry : entries) {
            if (entry.itemId().equals(itemId)) total++;
        }
        return total;
    }
}
