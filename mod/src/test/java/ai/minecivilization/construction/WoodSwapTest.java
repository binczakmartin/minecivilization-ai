package ai.minecivilization.construction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An oak house built of acacia is still the house. */
class WoodSwapTest {

    @Test
    void speciesAreReadFromIds() {
        assertEquals("oak", WoodSwap.speciesOf("minecraft:oak_planks"));
        assertEquals("dark_oak", WoodSwap.speciesOf("minecraft:dark_oak_stairs"));
        assertEquals("acacia", WoodSwap.speciesOf("minecraft:stripped_acacia_log"));
        assertEquals(null, WoodSwap.speciesOf("minecraft:cobblestone"));
    }

    @Test
    void aWoodenBlockHasAVersionInEverySpecies() {
        var variants = WoodSwap.variants("minecraft:oak_planks");
        assertEquals("minecraft:oak_planks", variants.get(0), "the named species comes first");
        assertTrue(variants.contains("minecraft:acacia_planks"));
        assertEquals(java.util.List.of("minecraft:cobblestone"), WoodSwap.variants("minecraft:cobblestone"));
    }

    @Test
    void theSameThingInAnotherWoodMatches() {
        assertTrue(WoodSwap.sameKind("minecraft:oak_stairs", "minecraft:acacia_stairs"));
        assertTrue(WoodSwap.sameKind("minecraft:oak_log", "minecraft:oak_log"));
        assertFalse(WoodSwap.sameKind("minecraft:oak_stairs", "minecraft:acacia_planks"));
        assertFalse(WoodSwap.sameKind("minecraft:cobblestone", "minecraft:stone"));
        assertEquals("minecraft:stripped_acacia_log",
                WoodSwap.withSpecies("minecraft:stripped_oak_log", "acacia"));
    }
}
