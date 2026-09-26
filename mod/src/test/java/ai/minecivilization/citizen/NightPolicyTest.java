package ai.minecivilization.citizen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Night work: close to home, carefully. Written after a colony lost seven of
 * twelve citizens in its first three minutes of darkness.
 */
class NightPolicyTest {

    @Test
    void theWorkingDayIsLeftAlone() {
        assertEquals(NightPolicy.Phase.DAY, NightPolicy.phase(0, false));
        assertEquals(NightPolicy.Phase.DAY, NightPolicy.phase(6_000, false));
        assertEquals(NightPolicy.Phase.DAY, NightPolicy.phase(10_999, false));
    }

    @Test
    void workStopsBeforeTheSunGoesDown() {
        assertEquals(NightPolicy.Phase.DUSK, NightPolicy.phase(11_000, false));
        assertEquals(NightPolicy.Phase.DUSK, NightPolicy.phase(12_000, false));
        assertEquals(NightPolicy.Phase.NIGHT, NightPolicy.phase(13_000, false),
                "hostiles are spawning by 13000");
    }

    @Test
    void morningComesAfterTheMobsStopSpawning() {
        assertEquals(NightPolicy.Phase.NIGHT, NightPolicy.phase(23_000, false));
        assertEquals(NightPolicy.Phase.DAY, NightPolicy.phase(23_600, false));
    }

    @Test
    void laterDaysBehaveLikeTheFirst() {
        long day5 = 5 * NightPolicy.DAY_LENGTH;
        assertEquals(NightPolicy.Phase.NIGHT, NightPolicy.phase(day5 + 18_000, false));
        assertEquals(NightPolicy.Phase.DAY, NightPolicy.phase(day5 + 1_000, false));
        assertEquals(5, NightPolicy.nightIndex(day5 + 18_000));
    }

    @Test
    void aThunderstormIsNightInDaylight() {
        assertEquals(NightPolicy.Phase.NIGHT, NightPolicy.phase(6_000, true));
        assertTrue(NightPolicy.shelterTime(6_000, true));
    }

    @Test
    void onlyCitizensFarOutWalkBackAtDusk() {
        assertTrue(NightPolicy.walkHome(NightPolicy.Phase.DUSK, 90));
        assertFalse(NightPolicy.walkHome(NightPolicy.Phase.DUSK, 30),
                "close enough to carry on working");
        assertFalse(NightPolicy.walkHome(NightPolicy.Phase.DUSK, 400), "an expedition, not a walk");
        assertFalse(NightPolicy.walkHome(NightPolicy.Phase.DAY, 90));
    }
}
