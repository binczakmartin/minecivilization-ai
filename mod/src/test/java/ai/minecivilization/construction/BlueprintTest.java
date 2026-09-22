package ai.minecivilization.construction;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blueprint bookkeeping is pure logic on purpose: BOM math and build order are
 * testable without booting Minecraft, and builders must never place blocks the
 * bill of materials did not account for.
 */
class BlueprintTest {

    private static Blueprint sample() {
        return new Blueprint("test", "Test hut", 3, 2, 3, List.of(
                new Blueprint.BlockEntry(0, 0, 0, "minecraft:stone_bricks"),
                new Blueprint.BlockEntry(1, 0, 0, "minecraft:stone_bricks"),
                new Blueprint.BlockEntry(0, 1, 0, "minecraft:oak_stairs[facing=east,half=bottom]"),
                new Blueprint.BlockEntry(1, 1, 0, "minecraft:oak_planks"),
                new Blueprint.BlockEntry(2, 1, 0, "minecraft:oak_planks")));
    }

    @Test
    void billOfMaterialsAgreesWithBlockCount() {
        Blueprint bp = sample();
        Map<String, Integer> bom = bp.billOfMaterials();
        assertEquals(2, bom.get("minecraft:stone_bricks"));
        assertEquals(2, bom.get("minecraft:oak_planks"));
        assertEquals(1, bom.get("minecraft:oak_stairs"));
        assertEquals(bom.values().stream().mapToInt(Integer::intValue).sum(), bp.blockCount());
    }

    @Test
    void itemIdStripsBlockStateProperties() {
        Blueprint.BlockEntry stairs =
                new Blueprint.BlockEntry(0, 0, 0, "minecraft:oak_stairs[facing=east,half=top]");
        assertEquals("minecraft:oak_stairs", stairs.itemId());
        assertEquals(1, sample().countOf("minecraft:oak_stairs"));
    }

    @Test
    void buildOrderIsPreservedAndImmutable() {
        Blueprint bp = sample();
        assertEquals("minecraft:stone_bricks", bp.entries().get(0).blockState);
        assertThrows(UnsupportedOperationException.class,
                () -> bp.entries().add(new Blueprint.BlockEntry(9, 9, 9, "minecraft:dirt")));
    }

    @Test
    void emptyBlueprintHasZeroBlocks() {
        Blueprint bp = new Blueprint("empty", "Empty", 1, 1, 1, List.of());
        assertEquals(0, bp.blockCount());
        assertTrue(bp.billOfMaterials().isEmpty());
    }

    @Test
    void metadataRoundTrips() {
        Blueprint bp = sample();
        assertEquals("test", bp.id);
        assertEquals("Test hut", bp.name);
        assertEquals(3, bp.sizeX);
        assertEquals(2, bp.sizeY);
        assertEquals(3, bp.sizeZ);
    }
}
