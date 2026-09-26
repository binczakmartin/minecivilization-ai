package ai.minecivilization.forestry;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Any tree will do."
 *
 * <p>Written after a session in which citizens in a spruce forest searched for
 * oak, found none, widened the radius, found none again, and stood still for a
 * quarter of an hour.</p>
 */
class ResourceFamilyTest {

    @Test
    void aRequestForOakIsSatisfiedByAnyLog() {
        assertTrue(ResourceFamily.satisfies("minecraft:oak_log", "minecraft:spruce_log"));
        assertTrue(ResourceFamily.satisfies("minecraft:oak_log", "minecraft:birch_log"));
        assertTrue(ResourceFamily.satisfies("minecraft:oak_log", "minecraft:oak_log"));
    }

    @Test
    void theSpeciesAskedForIsStillSearchedFirst() {
        // Substitutes are a fallback, not a lottery: an oak forest still gets cut as oak.
        List<String> sources = ResourceFamily.sourceBlocks("minecraft:spruce_log");
        assertEquals("minecraft:spruce_log", sources.get(0));
        assertTrue(sources.contains("minecraft:oak_log"), "but oak is still acceptable");
    }

    @Test
    void everyWoodSpeciesIsReachableFromEveryOther() {
        for (String species : List.of("oak", "spruce", "birch", "jungle", "acacia",
                "dark_oak", "mangrove", "cherry")) {
            String log = "minecraft:" + species + "_log";
            assertTrue(ResourceFamily.isWood(log), log + " is not recognised as wood");
            assertTrue(ResourceFamily.satisfies("minecraft:oak_log", log));
            assertTrue(ResourceFamily.sourceBlocks("minecraft:oak_log").contains(log));
        }
    }

    @Test
    void stoneToolMaterialsAreInterchangeable() {
        // Stone tool recipes accept all three, so a deepslate seam is as good
        // as a cobblestone one.
        assertTrue(ResourceFamily.satisfies("minecraft:cobblestone",
                "minecraft:cobbled_deepslate"));
        assertTrue(ResourceFamily.satisfies("minecraft:cobblestone", "minecraft:blackstone"));
        assertTrue(ResourceFamily.sourceBlocks("minecraft:cobblestone")
                .contains("minecraft:deepslate"));
    }

    @Test
    void thingsThatOnlyLookAlikeAreNotSubstituted() {
        // Granite is not cobblestone: it drops granite and no recipe accepts it
        // where cobblestone is asked for.
        assertFalse(ResourceFamily.satisfies("minecraft:cobblestone", "minecraft:granite"));
        assertFalse(ResourceFamily.satisfies("minecraft:iron_ingot", "minecraft:gold_ingot"));
        assertFalse(ResourceFamily.satisfies("minecraft:oak_log", "minecraft:oak_planks"));
    }

    @Test
    void ordinaryItemsHaveNoSubstitutesAtAll() {
        assertFalse(ResourceFamily.hasSubstitutes("minecraft:diamond"));
        // An ordinary block is its own source (diamond, an item, is found
        // through its ore — see itemsThatAreNotBlocksAreFoundThroughWhatDropsThem).
        assertEquals(List.of("minecraft:clay"),
                ResourceFamily.sourceBlocks("minecraft:clay"));
        assertEquals(java.util.Set.of("minecraft:diamond"),
                ResourceFamily.equivalentItems("minecraft:diamond"));
    }

    @Test
    void nullsAreHandledWithoutFuss() {
        assertFalse(ResourceFamily.isWood(null));
        assertTrue(ResourceFamily.sourceBlocks(null).isEmpty());
        assertTrue(ResourceFamily.equivalentItems(null).isEmpty());
        assertFalse(ResourceFamily.satisfies("minecraft:oak_log", null));
    }

    @Test
    void aRequestAlwaysSatisfiesItself() {
        for (String item : List.of("minecraft:oak_log", "minecraft:cobblestone",
                "minecraft:diamond", "minecraft:wheat")) {
            assertTrue(ResourceFamily.satisfies(item, item), item + " must satisfy itself");
        }
    }

    @Test
    void itemsThatAreNotBlocksAreFoundThroughWhatDropsThem() {
        // "No reachable minecraft:air" — the farmer was looking for a seed block.
        assertEquals("minecraft:short_grass",
                ResourceFamily.sourceBlocks("minecraft:wheat_seeds").get(0));
        assertTrue(ResourceFamily.sourceBlocks("minecraft:raw_iron")
                .contains("minecraft:deepslate_iron_ore"));
        assertEquals(List.of("minecraft:gravel"), ResourceFamily.sourceBlocks("minecraft:flint"));
    }

    @Test
    void saplingsComeFromAnyLeavesAndAnySaplingWillDo() {
        var sources = ResourceFamily.sourceBlocks("minecraft:oak_sapling");
        assertEquals("minecraft:oak_leaves", sources.get(0));
        assertTrue(sources.contains("minecraft:acacia_leaves"), "a savanna has acacia, not oak");
        assertTrue(ResourceFamily.satisfies("minecraft:oak_sapling", "minecraft:acacia_sapling"));
    }
}
