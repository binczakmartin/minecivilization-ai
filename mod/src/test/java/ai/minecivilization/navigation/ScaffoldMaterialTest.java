package ai.minecivilization.navigation;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a citizen is — and is not — willing to spend on a footbridge. */
class ScaffoldMaterialTest {

    @Test
    void emptyHandedMeansNoScaffolding() {
        assertNull(ScaffoldMaterial.choose(Map.of(), 4));
        assertNull(ScaffoldMaterial.choose((Map<String, Integer>) null, 4));
        assertEquals(0, ScaffoldMaterial.available(Map.of()));
    }

    @Test
    void cheapestAbundantMaterialWins() {
        Map<String, Integer> inventory = Map.of(
                "minecraft:oak_planks", 64,
                "minecraft:cobblestone", 32,
                "minecraft:dirt", 12);

        assertEquals("minecraft:dirt", ScaffoldMaterial.choose(inventory, 4),
                "dirt is the cheapest thing to throw into a trench");
    }

    @Test
    void aMaterialThatCoversTheWholeJobBeatsAMorePreferredOneThatRunsOut() {
        Map<String, Integer> inventory = Map.of(
                "minecraft:dirt", 3,
                "minecraft:cobblestone", 40);

        assertEquals("minecraft:cobblestone", ScaffoldMaterial.choose(inventory, 10),
                "a bridge that stops halfway strands the builder");
        assertEquals("minecraft:dirt", ScaffoldMaterial.choose(inventory, 3),
                "for a short gap the cheaper material is still preferred");
    }

    @Test
    void whenNothingCoversTheJobTheLargestStackIsOffered() {
        Map<String, Integer> inventory = Map.of(
                "minecraft:dirt", 2,
                "minecraft:granite", 7);

        assertEquals("minecraft:granite", ScaffoldMaterial.choose(inventory, 20),
                "partial progress is better than refusing to start");
    }

    @Test
    void logsAreAStableLastResortScaffold() {
        assertEquals("minecraft:spruce_log", ScaffoldMaterial.choose(
                Map.of("minecraft:spruce_log", 4), 4));
        assertTrue(ScaffoldMaterial.isExpendable("minecraft:spruce_log"));
    }

    @Test
    void valuablesAndContainersAreNeverSpent() {
        Map<String, Integer> inventory = Map.of(
                "minecraft:diamond_block", 64,
                "minecraft:chest", 16,
                "minecraft:iron_block", 30);

        assertNull(ScaffoldMaterial.choose(inventory, 1),
                "a diamond footbridge is worse than not crossing");
        assertEquals(0, ScaffoldMaterial.available(inventory));
    }

    @Test
    void gravityBlocksAreNeverUsedAsDecking() {
        assertNull(ScaffoldMaterial.choose(Map.of("minecraft:sand", 64, "minecraft:gravel", 64), 4),
                "sand and gravel fall out from under the builder");
        assertFalse(ScaffoldMaterial.isExpendable("minecraft:sand"));
        assertFalse(ScaffoldMaterial.isExpendable("minecraft:gravel"));
        assertTrue(ScaffoldMaterial.isExpendable("minecraft:cobblestone"));
    }

    @Test
    void availableCountsOnlyUsableBlocks() {
        Map<String, Integer> inventory = Map.of(
                "minecraft:dirt", 10,
                "minecraft:cobblestone", 5,
                "minecraft:sand", 64,          // excluded: gravity
                "minecraft:diamond", 3,        // excluded: not a block
                "minecraft:chest", 2);         // excluded: furniture

        assertEquals(15, ScaffoldMaterial.available(inventory));
    }

    @Test
    void theDenyListAndThePreferenceListNeverOverlap() {
        for (String preferred : ScaffoldMaterial.PREFERRED) {
            assertFalse(ScaffoldMaterial.NEVER.contains(preferred),
                    preferred + " cannot be both preferred and forbidden");
        }
    }
}
