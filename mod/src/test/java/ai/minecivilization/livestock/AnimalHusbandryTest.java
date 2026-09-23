package ai.minecivilization.livestock;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a colony keeps, and what it takes to lead one home. */
class AnimalHusbandryTest {

    @Test
    void theFarmAnimalsAreRecognised() {
        assertTrue(AnimalHusbandry.isLivestock("minecraft:sheep"));
        assertTrue(AnimalHusbandry.isLivestock("minecraft:cow"));
        assertTrue(AnimalHusbandry.isLivestock("minecraft:chicken"));
        assertTrue(AnimalHusbandry.isLivestock("minecraft:pig"));
    }

    @Test
    void wildAndHostileThingsAreNotLivestock() {
        assertFalse(AnimalHusbandry.isLivestock("minecraft:creeper"));
        assertFalse(AnimalHusbandry.isLivestock("minecraft:wolf"));
        assertFalse(AnimalHusbandry.isLivestock("minecraft:villager"));
        assertFalse(AnimalHusbandry.isLivestock(null));
    }

    @Test
    void eachSpeciesHasFeedThatWorks() {
        assertTrue(AnimalHusbandry.isFeed("minecraft:sheep", "minecraft:wheat"));
        assertTrue(AnimalHusbandry.isFeed("minecraft:chicken", "minecraft:wheat_seeds"));
        assertTrue(AnimalHusbandry.isFeed("minecraft:pig", "minecraft:carrot"));

        assertFalse(AnimalHusbandry.isFeed("minecraft:sheep", "minecraft:wheat_seeds"),
                "sheep will not follow seeds");
        assertFalse(AnimalHusbandry.isFeed("minecraft:cow", "minecraft:cobblestone"));
    }

    @Test
    void everyKeptSpeciesHasBothFeedAndAPurpose() {
        for (String species : AnimalHusbandry.species()) {
            assertNotNull(AnimalHusbandry.preferredFeed(species),
                    species + " cannot be led anywhere");
            assertFalse(AnimalHusbandry.feedFor(species).isEmpty());
        }
        // The species the colony actually needs first must pay for beds.
        assertEquals("minecraft:white_wool", AnimalHusbandry.yieldOf("minecraft:sheep"));
    }

    // ------------------------------------------------------------------ choosing

    @Test
    void sheepComeFirstBecauseBedsGateThePopulation() {
        Map<String, Integer> carried = Map.of("minecraft:wheat", 8, "minecraft:wheat_seeds", 8);

        assertEquals("minecraft:sheep", AnimalHusbandry.nextWanted(Map.of(), carried),
                "wool is beds, and beds are what cap the colony");
    }

    @Test
    void aSpeciesWithABreedingPairIsLeftAlone() {
        Map<String, Integer> carried = Map.of("minecraft:wheat", 8);
        Map<String, Integer> penned = Map.of("minecraft:sheep", 2);

        String next = AnimalHusbandry.nextWanted(penned, carried);
        assertEquals("minecraft:cow", next, "two sheep breed themselves; fetch variety instead");
    }

    @Test
    void nothingIsFetchedWithoutFeedToLureItWith() {
        assertNull(AnimalHusbandry.nextWanted(Map.of(), Map.of()),
                "an empty-handed citizen cannot lead an animal anywhere");
        assertNull(AnimalHusbandry.nextWanted(Map.of(), Map.of("minecraft:cobblestone", 64)));
    }

    @Test
    void onlySpeciesTheCitizenCanActuallyLureAreConsidered() {
        // Carrying only seeds: chickens are reachable, sheep and cows are not.
        Map<String, Integer> seedsOnly = Map.of("minecraft:wheat_seeds", 16);

        assertEquals("minecraft:chicken", AnimalHusbandry.nextWanted(Map.of(), seedsOnly));
        assertTrue(AnimalHusbandry.canLure("minecraft:chicken", seedsOnly));
        assertFalse(AnimalHusbandry.canLure("minecraft:sheep", seedsOnly));
    }

    @Test
    void afullPastureAsksForNothingMore() {
        Map<String, Integer> carried = Map.of(
                "minecraft:wheat", 64, "minecraft:wheat_seeds", 64, "minecraft:carrot", 64);
        Map<String, Integer> full = new java.util.HashMap<>();
        for (String species : AnimalHusbandry.species()) {
            full.put(species, 4);
        }
        assertNull(AnimalHusbandry.nextWanted(full, carried));
    }

    @Test
    void theChoiceIsDeterministic() {
        Map<String, Integer> carried = Map.of("minecraft:wheat", 8, "minecraft:carrot", 8);
        assertEquals(AnimalHusbandry.nextWanted(Map.of(), carried),
                AnimalHusbandry.nextWanted(Map.of(), carried));
    }

    @Test
    void theFeedShoppingListIsThingsAFarmActuallyProduces() {
        for (String item : AnimalHusbandry.shoppingList()) {
            boolean usedBySomeone = AnimalHusbandry.species().stream()
                    .anyMatch(species -> AnimalHusbandry.isFeed(species, item));
            assertTrue(usedBySomeone, item + " is on the list but tempts nothing");
        }
    }
}
