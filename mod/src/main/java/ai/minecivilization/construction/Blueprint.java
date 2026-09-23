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
    private final List<BlockEntry> entries; // in build order

    public Blueprint(String id, String name, int sizeX, int sizeY, int sizeZ,
                     List<BlockEntry> entriesInBuildOrder) {
        this.id = id;
        this.name = name;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.entries = new ArrayList<>(entriesInBuildOrder);
    }

    public List<BlockEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public int blockCount() {
        return entries.size();
    }

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
