package ai.minecivilization.navigation;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search order that decides whether a citizen finds the tree next to it or
 * scans half a million empty cells first.
 */
class SpiralScanTest {

    private static Set<String> walkAll(int radius) {
        Set<String> seen = new HashSet<>();
        SpiralScan.Cursor cursor = new SpiralScan.Cursor();
        int[] out = new int[3];
        while (cursor.next(radius, out)) {
            assertTrue(seen.add(out[0] + "," + out[1] + "," + out[2]),
                    "cell visited twice: " + out[0] + "," + out[1] + "," + out[2]);
        }
        return seen;
    }

    @Test
    void everyCellInTheCubeIsVisitedExactlyOnce() {
        for (int radius = 0; radius <= 5; radius++) {
            Set<String> seen = walkAll(radius);
            assertEquals(SpiralScan.cellCount(radius), seen.size(),
                    "radius " + radius + " did not cover its cube");
        }
    }

    @Test
    void noCellFallsOutsideTheRadius() {
        int radius = 4;
        SpiralScan.Cursor cursor = new SpiralScan.Cursor();
        int[] out = new int[3];
        while (cursor.next(radius, out)) {
            assertTrue(Math.abs(out[0]) <= radius
                    && Math.abs(out[1]) <= radius
                    && Math.abs(out[2]) <= radius,
                    "escaped the cube: " + out[0] + "," + out[1] + "," + out[2]);
        }
    }

    @Test
    void theSearchStartsWhereTheCitizenIsStanding() {
        SpiralScan.Cursor cursor = new SpiralScan.Cursor();
        int[] out = new int[3];

        assertTrue(cursor.next(6, out));
        assertEquals(0, out[0]);
        assertEquals(0, out[1]);
        assertEquals(0, out[2]);
    }

    @Test
    void nearCellsAlwaysComeBeforeFarOnes() {
        // This is the whole point: a tree five blocks away must not wait behind
        // half a million cells in a distant corner.
        int radius = 5;
        SpiralScan.Cursor cursor = new SpiralScan.Cursor();
        int[] out = new int[3];
        int previous = -1;
        while (cursor.next(radius, out)) {
            int distance = Math.max(Math.abs(out[0]), Math.max(Math.abs(out[1]), Math.abs(out[2])));
            assertTrue(distance >= previous,
                    "went back inward: " + distance + " after " + previous);
            previous = distance;
        }
        assertEquals(radius, previous, "the walk must reach the outermost shell");
    }

    @Test
    void anythingWithinSixBlocksIsFoundInTheFirstFewThousandCells() {
        // A 48-block raster scan reaches its own neighbourhood only after
        // roughly 450,000 cells. This is the regression that guards it.
        int radius = 48;
        SpiralScan.Cursor cursor = new SpiralScan.Cursor();
        int[] out = new int[3];
        long steps = 0;
        boolean found = false;
        while (cursor.next(radius, out)) {
            steps++;
            if (out[0] == 5 && out[1] == 0 && out[2] == 2) {
                found = true;
                break;
            }
        }
        assertTrue(found, "the cell was never visited");
        assertTrue(steps < 3_000,
                "a neighbour took " + steps + " cells to reach — the old order was ~450,000");
    }

    @Test
    void progressIsReportedHonestly() {
        int radius = 3;
        SpiralScan.Cursor cursor = new SpiralScan.Cursor();
        int[] out = new int[3];
        long counted = 0;
        while (cursor.next(radius, out)) {
            counted++;
            assertEquals(counted, cursor.visited(radius),
                    "the progress counter drifted from the real count");
        }
        assertEquals(SpiralScan.cellCount(radius), counted);
    }

    @Test
    void theWalkEndsAndStaysEnded() {
        SpiralScan.Cursor cursor = new SpiralScan.Cursor();
        int[] out = new int[3];
        while (cursor.next(1, out)) {
            // drain
        }
        assertTrue(cursor.exhausted());
        assertFalse(cursor.next(1, out), "an exhausted cursor must stay exhausted");
    }

    @Test
    void aZeroRadiusSearchLooksOnlyAtItsOwnCell() {
        assertEquals(1, SpiralScan.cellCount(0));
        assertEquals(1, walkAll(0).size());
    }
}
