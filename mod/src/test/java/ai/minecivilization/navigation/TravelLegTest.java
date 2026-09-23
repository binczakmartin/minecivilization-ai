package ai.minecivilization.navigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walking home from a long way out.
 *
 * <p>Written after a session in which citizens logged 445 identical "no route
 * to the town centre" failures and sat idle through all of them: the route
 * existed, it was simply further than the planner's search box could see.</p>
 */
class TravelLegTest {

    private static final int LEG = 40;

    @Test
    void aNearbyGoalIsAimedAtDirectly() {
        assertTrue(TravelLeg.withinOneLeg(0, 0, 20, 10, LEG));
        assertArrayEquals(new int[]{20, 10}, TravelLeg.aim(0, 0, 20, 10, LEG));
    }

    @Test
    void aDistantGoalIsBrokenIntoALegTheRoutePlannerCanSee() {
        // 120 blocks out: the exact case that failed 445 times.
        assertFalse(TravelLeg.withinOneLeg(0, 0, 120, 0, LEG));

        int[] aim = TravelLeg.aim(0, 0, 120, 0, LEG);
        double reach = TravelLeg.distance(0, 0, aim[0], aim[1]);
        assertTrue(reach <= LEG + 1, "the aim point must be inside the planner's reach: " + reach);
        assertTrue(reach > LEG - 2, "and should use the leg it is given, not creep forward");
    }

    @Test
    void theAimPointIsOnTheWayToTheGoal() {
        int[] aim = TravelLeg.aim(-30, 90, 0, 0, LEG);

        // Strictly closer to the goal than the starting point was.
        double before = TravelLeg.distance(-30, 90, 0, 0);
        double after = TravelLeg.distance(aim[0], aim[1], 0, 0);
        assertTrue(after < before, "the leg went the wrong way: " + after + " vs " + before);
    }

    @Test
    void walkingLegByLegActuallyArrives() {
        // The property that matters: repeated legs converge on the goal rather
        // than stalling or orbiting it.
        int x = -140;
        int z = 95;
        for (int step = 0; step < 100; step++) {
            if (TravelLeg.withinOneLeg(x, z, 0, 0, LEG)) {
                return;   // the goal is now directly plannable
            }
            int[] aim = TravelLeg.aim(x, z, 0, 0, LEG);
            assertTrue(TravelLeg.distance(aim[0], aim[1], 0, 0)
                            < TravelLeg.distance(x, z, 0, 0),
                    "a leg that does not close the gap will loop forever");
            x = aim[0];
            z = aim[1];
        }
        throw new AssertionError("legs never reached the goal from -140,95");
    }

    @Test
    void standingOnTheGoalAsksForNoLegAtAll() {
        assertArrayEquals(new int[]{5, 5}, TravelLeg.aim(5, 5, 5, 5, LEG));
        assertTrue(TravelLeg.withinOneLeg(5, 5, 5, 5, LEG));
    }

    @Test
    void anAbsurdLegLengthIsStillSafe() {
        assertArrayEquals(new int[]{0, 0}, TravelLeg.aim(0, 0, 0, 0, 0));
        int[] aim = TravelLeg.aim(0, 0, 100, 0, -5);
        assertTrue(TravelLeg.distance(0, 0, aim[0], aim[1]) <= 2,
                "a nonsense leg length must not produce a nonsense jump");
    }
}
