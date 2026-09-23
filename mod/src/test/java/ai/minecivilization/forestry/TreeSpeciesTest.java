package ai.minecivilization.forestry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What grows back from what — the colony's forestry knowledge. */
class TreeSpeciesTest {

    @Test
    void everyOverworldSpeciesReplantsItself() {
        assertEquals("minecraft:oak_sapling", TreeSpecies.saplingFor("minecraft:oak_log"));
        assertEquals("minecraft:spruce_sapling", TreeSpecies.saplingFor("minecraft:spruce_log"));
        assertEquals("minecraft:birch_sapling", TreeSpecies.saplingFor("minecraft:birch_log"));
        assertEquals("minecraft:jungle_sapling", TreeSpecies.saplingFor("minecraft:jungle_log"));
        assertEquals("minecraft:acacia_sapling", TreeSpecies.saplingFor("minecraft:acacia_log"));
        assertEquals("minecraft:dark_oak_sapling", TreeSpecies.saplingFor("minecraft:dark_oak_log"));
        assertEquals("minecraft:cherry_sapling", TreeSpecies.saplingFor("minecraft:cherry_log"));
    }

    @Test
    void theAwkwardSpeciesAreHandled() {
        // Mangroves drop propagules, and nether "trees" grow from fungus.
        assertEquals("minecraft:mangrove_propagule",
                TreeSpecies.saplingFor("minecraft:mangrove_log"));
        assertEquals("minecraft:crimson_fungus", TreeSpecies.saplingFor("minecraft:crimson_stem"));
        assertEquals("minecraft:warped_fungus", TreeSpecies.saplingFor("minecraft:warped_stem"));
    }

    @Test
    void strippedAndFullWoodRegrowTheSameTree() {
        assertEquals("minecraft:oak_sapling", TreeSpecies.saplingFor("minecraft:stripped_oak_log"));
        assertEquals("minecraft:birch_sapling", TreeSpecies.saplingFor("minecraft:birch_wood"));
        assertEquals("minecraft:spruce_sapling",
                TreeSpecies.saplingFor("minecraft:stripped_spruce_wood"));
    }

    @Test
    void thingsThatAreNotTrunksAreRefused() {
        assertNull(TreeSpecies.saplingFor("minecraft:cobblestone"));
        assertNull(TreeSpecies.saplingFor("minecraft:oak_planks"));
        assertNull(TreeSpecies.saplingFor("minecraft:bamboo_block"));
        assertNull(TreeSpecies.saplingFor(null));
        assertNull(TreeSpecies.saplingFor(""));
        assertFalse(TreeSpecies.isTrunk("minecraft:oak_planks"));
        assertTrue(TreeSpecies.isTrunk("minecraft:oak_log"));
    }

    @Test
    void theMappingRoundTripsForEveryCommonSpecies() {
        for (String sapling : TreeSpecies.COMMON_SAPLINGS) {
            String log = TreeSpecies.logFor(sapling);
            assertNotNull(log, sapling + " must name the log it grows into");
            assertEquals(sapling, TreeSpecies.saplingFor(log),
                    "round trip broken for " + sapling);
        }
    }
}
