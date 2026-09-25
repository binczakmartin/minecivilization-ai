package ai.minecivilization.citizen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Getting a stranded citizen home.
 *
 * <p>Written after watching a citizen at the bottom of a ravine issue the same
 * failing walk-home for twenty minutes. The old policy's entire repertoire was
 * "wait longer and try the identical thing", which cannot succeed, so these
 * tests are mostly about the ladder actually climbing.</p>
 */
class RescueLadderTest {

    @Test
    void theFirstTryUsesARouteTheColonyAlreadyKnows() {
        assertEquals(RescueLadder.Step.KNOWN_ROUTE,
                RescueLadder.stepFor(0, 0, true));
    }

    @Test
    void withNoKnownRouteItStartsWithOrdinaryPathfinding() {
        // Offering a remembered route that does not exist would waste a rung.
        assertEquals(RescueLadder.Step.DIRECT_PATH,
                RescueLadder.stepFor(0, 0, false));
    }

    @Test
    void eachFailureBuysAMorePhysicalAnswer() {
        RescueLadder.Step previous = null;
        for (int failures = 0; failures <= 6; failures++) {
            RescueLadder.Step step = RescueLadder.stepFor(failures, 0, true);
            if (previous != null) {
                assertTrue(step.ordinal() >= previous.ordinal(),
                        "rung " + failures + " went backwards: " + previous + " -> " + step);
            }
            previous = step;
        }
        // Enough failures and the citizen stops asking permission from terrain.
        assertEquals(RescueLadder.Step.BEELINE, RescueLadder.stepFor(20, 0, true));
    }

    @Test
    void buriedDeepItSkipsStraightToDigging() {
        // The exact case that used to strand citizens forever: sealed in a cave
        // a long way down, where no amount of pathfinding can ever succeed.
        RescueLadder.Step step = RescueLadder.stepFor(2, 40, false);
        assertEquals(RescueLadder.Step.DIG_TO_SURFACE, step);

        // And it does not waste more than a token attempt on walking first.
        assertNotEquals(RescueLadder.Step.WAYPOINT, RescueLadder.stepFor(3, 40, false));
        assertNotEquals(RescueLadder.Step.LOCAL_EXPLORE, RescueLadder.stepFor(4, 40, false));
    }

    @Test
    void shallowUndergroundStillTriesToWalkOutFirst() {
        // Three blocks under a tree canopy is not "buried"; digging out of it
        // would be absurd, and was the risk of a naive depth check.
        assertEquals(RescueLadder.Step.KNOWN_ROUTE, RescueLadder.stepFor(0, 5, true));
    }

    @Test
    void backoffGrowsButStaysShortEnoughToLookAlive() {
        assertEquals(40, RescueLadder.backoffTicks(0));
        assertTrue(RescueLadder.backoffTicks(3) > RescueLadder.backoffTicks(1));
        // Half a minute at most: a stranded citizen must never look abandoned.
        assertTrue(RescueLadder.backoffTicks(30) <= 600);
    }

    @Test
    void successResetsTheWholeLadder() {
        RescueLadder ladder = new RescueLadder();
        ladder.failed(100);
        ladder.failed(200);
        assertEquals(2, ladder.failures());

        ladder.succeeded();
        assertEquals(0, ladder.failures());
        assertTrue(ladder.ready(0));
        assertEquals(RescueLadder.Step.KNOWN_ROUTE, ladder.next(0, 0, true));
    }

    @Test
    void aFailureMakesTheCitizenWaitBeforeTryingAgain() {
        RescueLadder ladder = new RescueLadder();
        assertTrue(ladder.ready(0));
        ladder.failed(1000);
        assertFalse(ladder.ready(1000));
        assertTrue(ladder.ready(1000 + RescueLadder.backoffTicks(1)));
    }

    @Test
    void anOrderedRescueClimbsWithoutWaiting() {
        RescueLadder ladder = new RescueLadder();
        ladder.escalateNow();
        assertEquals(1, ladder.failures());
        // A player who asks for a rescue should not then watch a cooldown.
        assertTrue(ladder.ready(0));
    }

    @Test
    void diggingAndBeeliningCountAsDesperate() {
        RescueLadder ladder = new RescueLadder();
        ladder.next(0, 40, false);
        assertFalse(ladder.isDesperate());   // first attempt is still a walk

        for (int i = 0; i < 3; i++) ladder.failed(i * 1000L);
        ladder.next(9000, 40, false);
        assertTrue(ladder.isDesperate());
    }
}
