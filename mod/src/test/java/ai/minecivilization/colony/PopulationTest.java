package ai.minecivilization.colony;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a colony can afford another mouth. */
class PopulationTest {

    private static final long LONG_AGO = Long.MAX_VALUE;

    private static boolean ready(int population, int food, int beds) {
        return Population.canReproduce(population, food, beds, LONG_AGO);
    }

    /** A colony comfortably able to grow, used as the baseline to break. */
    private static int comfortableFood(int population) {
        return Population.foodNeededFor(population) + 100;
    }

    @Test
    void aWellRunColonyGrows() {
        assertTrue(ready(4, comfortableFood(4), Population.bedsNeededFor(4)));
    }

    @Test
    void aLoneCitizenCannotRaiseAChild() {
        assertFalse(ready(1, 10_000, 50));
        assertNotNull(Population.blocker(1, 10_000, 50, LONG_AGO));
    }

    @Test
    void growthStopsAtTheVillageCeiling() {
        int max = Population.MAX_POPULATION;
        assertTrue(ready(max - 1, comfortableFood(max), Population.bedsNeededFor(max)));
        assertFalse(ready(max, comfortableFood(max), Population.bedsNeededFor(max)),
                "a village, not a server-melting crowd");
    }

    @Test
    void nobodyIsBornWithoutABedToSleepIn() {
        int population = 6;
        int food = comfortableFood(population);

        assertFalse(ready(population, food, population),
                "one bed each leaves nowhere for the newcomer");
        assertTrue(ready(population, food, population + 1));
    }

    @Test
    void theFoodBarRisesWithThePopulation() {
        assertTrue(Population.foodNeededFor(20) > Population.foodNeededFor(4),
                "feeding twenty takes more slack than feeding four");

        // Enough for a small camp is not enough for a town.
        int smallColonyFood = Population.foodNeededFor(4);
        assertTrue(ready(4, smallColonyFood, Population.bedsNeededFor(4)));
        assertFalse(ready(20, smallColonyFood, Population.bedsNeededFor(20)));
    }

    @Test
    void birthsAreSpacedOut() {
        int population = 5;
        int food = comfortableFood(population);
        int beds = Population.bedsNeededFor(population);

        assertFalse(Population.canReproduce(population, food, beds, 0),
                "a colony must not double overnight");
        assertFalse(Population.canReproduce(population, food, beds,
                Population.BIRTH_COOLDOWN_TICKS - 1));
        assertTrue(Population.canReproduce(population, food, beds,
                Population.BIRTH_COOLDOWN_TICKS));
    }

    // ------------------------------------------------------------------ diagnosis

    @Test
    void aReadyColonyReportsNoBlocker() {
        assertNull(Population.blocker(4, comfortableFood(4), Population.bedsNeededFor(4), LONG_AGO));
    }

    @Test
    void eachObstacleExplainsItself() {
        // "Why is nobody being born?" must have an answer, not a shrug.
        assertTrue(Population.blocker(1, 9999, 99, LONG_AGO).contains("citizens"));
        assertTrue(Population.blocker(6, 9999, 2, LONG_AGO).contains("beds"));
        assertTrue(Population.blocker(6, 0, 99, LONG_AGO).contains("food"));
        assertTrue(Population.blocker(6, 9999, 99, 0).contains("soon"));
    }

    @Test
    void theBlockerReportedIsTheMostFundamentalOne() {
        // Everything is wrong at once: report the thing to fix first.
        String blocker = Population.blocker(1, 0, 0, 0);
        assertTrue(blocker.contains("citizens"),
                "no point mentioning food to a colony of one: " + blocker);
    }

    @Test
    void bedsNeededNeverGoesNegative() {
        assertEquals(1, Population.bedsNeededFor(0));
        assertEquals(1, Population.bedsNeededFor(-5));
        assertEquals(0, Population.foodNeededFor(0));
    }
}
