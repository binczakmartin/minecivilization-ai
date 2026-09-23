package ai.minecivilization.mining;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plan of a shared mine: one stair, a landing where each ore actually is. */
class MineLayoutTest {

    private static String key(int[] p) {
        return p[0] + "," + p[1] + "," + p[2];
    }

    // ------------------------------------------------------------------ depths

    @Test
    void theDepthsAreWhereTheOreActuallyPeaks() {
        // Digging at the wrong level is most of why casual mining feels
        // unproductive — diamonds essentially do not exist above y=16.
        var byOre = new java.util.HashMap<String, Integer>();
        for (MineLayout.Level level : MineLayout.levels()) {
            byOre.put(level.ore(), level.y());
        }
        assertEquals(16, byOre.get("minecraft:iron_ore"));
        assertEquals(-59, byOre.get("minecraft:diamond_ore"));
        assertEquals(-16, byOre.get("minecraft:gold_ore"));
        assertTrue(byOre.get("minecraft:coal_ore") > byOre.get("minecraft:iron_ore"),
                "coal sits well above iron");
    }

    @Test
    void levelsAreOrderedFromTheSurfaceDown() {
        int previous = Integer.MAX_VALUE;
        for (MineLayout.Level level : MineLayout.levels()) {
            assertTrue(level.y() < previous, "levels must descend: " + level);
            previous = level.y();
        }
    }

    @Test
    void aMineNeverPlansALevelAboveItsOwnFrontDoor() {
        List<MineLayout.Level> levels = MineLayout.levelsBelow(70, -64);

        for (MineLayout.Level level : levels) {
            assertTrue(level.y() < 70, "planned " + level + " above the entrance");
        }
        assertFalse(levels.isEmpty(), "there is plenty below y=70");
    }

    @Test
    void aMineNeverPlansALevelInsideTheBedrock() {
        for (MineLayout.Level level : MineLayout.levelsBelow(320, -64)) {
            assertTrue(level.y() > -63, "planned " + level + " in the bedrock");
        }
    }

    @Test
    void aHighEntranceStillReachesEveryOre() {
        assertEquals(MineLayout.levels().size(), MineLayout.levelsBelow(200, -64).size());
    }

    // ------------------------------------------------------------------ the stair

    @Test
    void theStairIsWalkableRatherThanAShaftToFallDown() {
        List<int[]> steps = MineLayout.stairSteps(0, 64, 0, 48, 0);

        assertEquals(16, steps.size(), "one step per block of descent");
        int[] previous = {0, 64, 0};
        for (int[] step : steps) {
            assertEquals(previous[1] - 1, step[1], "a step must drop exactly one block");
            int horizontal = Math.abs(step[0] - previous[0]) + Math.abs(step[2] - previous[2]);
            assertEquals(MineLayout.RUN_PER_DROP, horizontal,
                    "a drop with no run is a hole, not a stair");
            previous = step;
        }
    }

    @Test
    void theStairEndsExactlyAtTheLevelItWasAskedFor() {
        List<int[]> steps = MineLayout.stairSteps(10, 64, -5, 16, 1);
        assertEquals(16, steps.get(steps.size() - 1)[1]);
    }

    @Test
    void legsAlternateSoTheMineFoldsBackOnItself() {
        // Otherwise a mine to bedrock runs a hundred and twenty blocks from
        // the settlement in a straight line.
        List<int[]> first = MineLayout.stairSteps(0, 64, 0, 60, 0);
        List<int[]> second = MineLayout.stairSteps(0, 64, 0, 60, 1);

        int[] endFirst = first.get(first.size() - 1);
        int[] endSecond = second.get(second.size() - 1);
        assertFalse(endFirst[0] == endSecond[0] && endFirst[2] == endSecond[2],
                "consecutive legs ran the same way");
    }

    @Test
    void askingToDigUpwardsYieldsNothing() {
        assertTrue(MineLayout.stairSteps(0, 16, 0, 64, 0).isEmpty());
        assertTrue(MineLayout.stairSteps(0, 16, 0, 16, 0).isEmpty());
    }

    @Test
    void theStairIsLitOftenEnoughToStopSpawns() {
        int lit = 0;
        for (int step = 0; step < 40; step++) {
            if (MineLayout.isTorchStep(step)) lit++;
        }
        assertTrue(lit >= 5, "a dark stair is a mob corridor into the colony");
        assertFalse(MineLayout.isTorchStep(0), "no torch in the doorway itself");
    }

    // ------------------------------------------------------------------ landings

    @Test
    void eachLevelGetsALandingWithSomewhereToPutThings() {
        List<int[]> landing = MineLayout.landing(0, 16, 0, 0);

        assertEquals(MineLayout.LANDING_LENGTH, landing.size());
        for (int[] cell : landing) {
            assertEquals(16, cell[1], "a landing is flat");
        }
        assertNotNull(MineLayout.depotOf(landing));
    }

    @Test
    void theDepotSitsAtTheFarEndOutOfTheWay() {
        List<int[]> landing = MineLayout.landing(0, 16, 0, 0);
        int[] depot = MineLayout.depotOf(landing);

        assertEquals(key(landing.get(landing.size() - 1)), key(depot));
        int distance = Math.abs(depot[0]) + Math.abs(depot[2]);
        assertEquals(MineLayout.LANDING_LENGTH, distance,
                "a chest at the foot of the stair blocks the stair");
    }

    @Test
    void aLandingNeverRunsIntoTheNextLegDown() {
        for (int leg = 0; leg < 4; leg++) {
            Set<String> stair = new HashSet<>();
            for (int[] step : MineLayout.stairSteps(0, 64, 0, 58, leg)) {
                stair.add(key(step));
            }
            for (int[] cell : MineLayout.landing(0, 64, 0, leg)) {
                assertFalse(stair.contains(key(cell)),
                        "leg " + leg + ": the landing was dug into the stair");
            }
        }
    }

    @Test
    void theWholePlanIsDeterministic() {
        assertEquals(MineLayout.stairSteps(3, 70, 4, 16, 2).size(),
                MineLayout.stairSteps(3, 70, 4, 16, 2).size());
        assertEquals(key(MineLayout.depotOf(MineLayout.landing(3, 16, 4, 2))),
                key(MineLayout.depotOf(MineLayout.landing(3, 16, 4, 2))));
    }
}
