package ai.minecivilization.colony;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a colony's land needs grow with its population. */
class ZonePlannerTest {

    private static Map<ZoneType, Integer> census(Object... pairs) {
        Map<ZoneType, Integer> out = new EnumMap<>(ZoneType.class);
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((ZoneType) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    /** Grow a colony one allotment at a time, exactly as the tick does. */
    private static Map<ZoneType, Integer> growUntilComplete(int population) {
        Map<ZoneType, Integer> have = new EnumMap<>(ZoneType.class);
        for (int step = 0; step < 200; step++) {
            ZoneType next = ZonePlanner.nextNeeded(population, have);
            if (next == null) return have;
            have.merge(next, 1, Integer::sum);
        }
        throw new AssertionError("the town never stopped growing at population " + population);
    }

    // ------------------------------------------------------------------ what a town wants

    @Test
    void anEmptyColonyNeedsNothing() {
        assertNull(ZonePlanner.nextNeeded(0, census()));
        assertTrue(ZonePlanner.plan(0).isEmpty());
    }

    @Test
    void theFirstThingAColonyAllotsIsItsCentre() {
        assertEquals(ZoneType.CIVIC, ZonePlanner.nextNeeded(1, census()));
    }

    @Test
    void survivalIsZonedBeforeIndustry() {
        // A brand-new colony must reach wood and food before workshops.
        // (A colony of eight wants two warehouses, so give it both here —
        // the point of the test is what comes *after* the basics.)
        Map<ZoneType, Integer> have = census(ZoneType.CIVIC, 1, ZoneType.STORAGE, 2);
        ZoneType next = ZonePlanner.nextNeeded(8, have);
        assertEquals(ZoneType.FOREST, next, "wood is the first industry");

        have.put(ZoneType.FOREST, 2);
        assertEquals(ZoneType.FARM, ZonePlanner.nextNeeded(8, have));
    }

    @Test
    void aTwoPersonCampFencesNoPastureAndBuildsNoFactory() {
        Map<ZoneType, Integer> plan = ZonePlanner.plan(2);

        assertNull(plan.get(ZoneType.PASTURE), "nobody spare to tend livestock yet");
        assertNull(plan.get(ZoneType.INDUSTRIAL), "a camp of two has no workshops");
        assertNull(plan.get(ZoneType.MINE), "and nobody to spare down a shaft");
        assertEquals(1, plan.get(ZoneType.CIVIC));
    }

    @Test
    void livestockAndIndustryArriveWithEnoughHands() {
        assertEquals(0, ZonePlanner.desired(ZoneType.PASTURE, 3));
        assertTrue(ZonePlanner.desired(ZoneType.PASTURE, 4) >= 1);
        assertEquals(0, ZonePlanner.desired(ZoneType.INDUSTRIAL, 5));
        assertTrue(ZonePlanner.desired(ZoneType.INDUSTRIAL, 6) >= 1);
    }

    @Test
    void housingKeepsUpWithThePopulation() {
        // Reproduction depends on beds, so housing must never lag behind.
        assertEquals(1, ZonePlanner.desired(ZoneType.RESIDENTIAL, 1));
        assertEquals(1, ZonePlanner.desired(ZoneType.RESIDENTIAL, 2));
        assertEquals(2, ZonePlanner.desired(ZoneType.RESIDENTIAL, 4));
        assertEquals(6, ZonePlanner.desired(ZoneType.RESIDENTIAL, 12));
    }

    @Test
    void everyNeedGrowsOrHoldsAsAColonyGrowsNeverShrinks() {
        for (ZoneType type : ZoneType.values()) {
            int previous = 0;
            for (int population = 1; population <= 40; population++) {
                int want = ZonePlanner.desired(type, population);
                assertTrue(want >= previous,
                        type + " wanted " + want + " at population " + population
                                + " after wanting " + previous + " — a town does not un-zone itself");
                previous = want;
            }
        }
    }

    // ------------------------------------------------------------------ growth settles

    @Test
    void growthAlwaysReachesACompleteTown() {
        for (int population : new int[]{1, 2, 4, 8, 16, 40}) {
            Map<ZoneType, Integer> settled = growUntilComplete(population);
            assertNull(ZonePlanner.nextNeeded(population, settled),
                    "population " + population + " should be satisfied");
            assertEquals(ZonePlanner.plan(population), settled,
                    "growing one plot at a time must land exactly on the plan");
        }
    }

    @Test
    void aCompleteTownAsksForNothingMore() {
        Map<ZoneType, Integer> settled = growUntilComplete(10);
        assertNull(ZonePlanner.nextNeeded(10, settled));
        // ...until someone is born.
        assertNotNull(ZonePlanner.nextNeeded(30, settled));
    }

    @Test
    void aGrownTownStillFitsInsideTheLayoutLimit() {
        // The plan is only credible if the land actually exists for it.
        Map<ZoneType, Integer> plan = ZonePlanner.plan(40);
        Set<ZoneLayout.Plot> taken = new HashSet<>();

        for (Map.Entry<ZoneType, Integer> entry : plan.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                ZoneLayout.Plot plot = ZoneLayout.allocate(entry.getKey(), taken);
                assertNotNull(plot, "no room left for " + entry.getKey()
                        + " in a colony of 40");
                taken.add(plot);
            }
        }
    }
}
