package ai.minecivilization.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choosing what to eat.
 *
 * <p>Two failure modes, opposite and both bad: a citizen that eats zombie
 * flesh with bread in its pack is poisoning itself for nothing, and one that
 * starves beside a stack of flesh it refused has died of fastidiousness. The
 * rule is that the nasty stuff is ranked last but never ruled out.</p>
 */
class FoodChoiceTest {

    private static final String BREAD = "minecraft:bread";
    private static final String STEAK = "minecraft:cooked_beef";
    private static final String RAW_BEEF = "minecraft:beef";
    private static final String FLESH = "minecraft:rotten_flesh";

    @Test
    void theMostNourishingWholesomeFoodWins() {
        // Bread in the lowest slot, steak above it: taking whatever came first
        // was the old behaviour and it is the wrong answer.
        int best = FoodPolicy.bestIndex(new String[]{BREAD, STEAK}, new int[]{5, 8}, false);
        assertEquals(1, best);
    }

    @Test
    void rottenFleshIsRefusedWhileThereIsRealFood() {
        assertEquals(1, FoodPolicy.bestIndex(new String[]{FLESH, BREAD},
                new int[]{4, 5}, false));
        assertEquals(1, FoodPolicy.bestIndex(new String[]{FLESH, BREAD},
                new int[]{4, 5}, true), "even desperate, bread beats flesh");
    }

    @Test
    void rottenFleshIsEatenRatherThanStarve() {
        assertEquals(-1, FoodPolicy.bestIndex(new String[]{FLESH}, new int[]{4}, false),
                "not while there is any choice");
        assertEquals(0, FoodPolicy.bestIndex(new String[]{FLESH}, new int[]{4}, true),
                "but yes, rather than die");
    }

    @Test
    void aFillingNastyMouthfulStillLosesToASmallWholesomeOne() {
        // Nutrition alone would pick the flesh; the penalty is what stops it.
        assertEquals(1, FoodPolicy.bestIndex(new String[]{FLESH, BREAD},
                new int[]{20, 1}, true));
    }

    @Test
    void rawChickenCountsAsALastResortAndCookedDoesNot() {
        assertTrue(FoodPolicy.isLastResort("minecraft:chicken"));
        assertFalse(FoodPolicy.isLastResort("minecraft:cooked_chicken"));
        assertTrue(FoodPolicy.isLastResort("minecraft:spider_eye"));
        assertTrue(FoodPolicy.isLastResort("minecraft:pufferfish"));
        assertFalse(FoodPolicy.isLastResort(BREAD));
        assertFalse(FoodPolicy.isLastResort(null));
    }

    @Test
    void cookedFoodBeatsTheRawVersionOfItself() {
        assertEquals(0, FoodPolicy.bestIndex(new String[]{STEAK, RAW_BEEF},
                new int[]{8, 3}, false));
    }

    @Test
    void anEmptyPackOffersNothing() {
        assertEquals(-1, FoodPolicy.bestIndex(new String[]{null, null},
                new int[]{0, 0}, true));
        assertEquals(-1, FoodPolicy.bestIndex(null, null, true));
    }

    @Test
    void toolsAndBlocksAreNotFood() {
        // Nutrition zero is how a non-food slot presents itself.
        assertEquals(-1, FoodPolicy.bestIndex(
                new String[]{"minecraft:wooden_pickaxe", "minecraft:cobblestone"},
                new int[]{0, 0}, true));
    }
}
