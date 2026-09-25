package ai.minecivilization.citizen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling a working citizen from a stopped one.
 *
 * <p>The distinction matters because the colony's productivity was invisible:
 * a settlement spending half its time standing still looked, from outside,
 * much like a busy one. These are the thresholds that decide when somebody
 * gets rescued, so they are worth pinning down.</p>
 */
class StuckDetectorTest {

    @Test
    void aCitizenThatKeepsMovingIsNeverStuck() {
        StuckDetector detector = new StuckDetector();
        detector.setHasWork(0, true);
        for (long tick = 0; tick < 2000; tick += 10) {
            detector.sample(tick, (int) tick / 10, 64, 0);
            detector.noteProgress(tick);
        }
        assertEquals(StuckDetector.Reason.NONE, detector.reason(2000));
        assertFalse(detector.isStuck(2000));
    }

    @Test
    void standingInOneBlockWithAJobIsNotMoving() {
        StuckDetector detector = new StuckDetector();
        detector.sample(0, 10, 64, 10);
        detector.setHasWork(0, true);

        long stuckAt = StuckDetector.WORKING_STILL_TICKS;
        for (long tick = 0; tick <= stuckAt; tick += 20) {
            detector.sample(tick, 10, 64, 10);
        }
        assertEquals(StuckDetector.Reason.NOT_MOVING, detector.reason(stuckAt));
        assertTrue(detector.stillTicks(stuckAt) >= StuckDetector.WORKING_STILL_TICKS);
    }

    @Test
    void aMinerStandingStillToMineIsNotStuck() {
        // Mining a block, waiting at a furnace and crafting are all done
        // perfectly still. Using the idle threshold here flagged every miner
        // in the colony, which is how a working settlement reported itself as
        // 94% busy while twelve of sixteen citizens carried a warning.
        StuckDetector detector = new StuckDetector();
        detector.setHasWork(0, true);
        for (long tick = 0; tick <= StuckDetector.STILL_TICKS + 40; tick += 20) {
            detector.sample(tick, 10, 64, 10);
        }
        assertEquals(StuckDetector.Reason.NONE,
                detector.reason(StuckDetector.STILL_TICKS + 40));
    }

    @Test
    void movingOneBlockResetsTheStallClock() {
        StuckDetector detector = new StuckDetector();
        detector.setHasWork(0, true);
        long moved = StuckDetector.WORKING_STILL_TICKS - 10;
        long later = moved + 100;
        detector.sample(0, 10, 64, 10);
        detector.sample(moved - 1, 10, 64, 10);
        // Inching along a tunnel is slow, not stuck.
        detector.sample(moved, 11, 64, 10);
        // Something finished along the way, so this is purely about the stall
        // clock and not about the separate "nothing completed" rule.
        detector.noteProgress(moved);
        detector.sample(later, 11, 64, 10);
        assertEquals(StuckDetector.Reason.NONE, detector.reason(later));
    }

    @Test
    void theSameFailureRepeatingIsALoop() {
        StuckDetector detector = new StuckDetector();
        detector.sample(0, 0, 64, 0);
        for (int i = 0; i < StuckDetector.REPEAT_FAILURES; i++) {
            detector.noteFailure(i * 20L, "TARGET_UNREACHABLE");
        }
        assertEquals(StuckDetector.Reason.REPEATED_FAILURE, detector.reason(100));
        assertEquals("TARGET_UNREACHABLE", detector.lastFailureCode());
    }

    @Test
    void differentFailuresAreABadDayNotALoop() {
        StuckDetector detector = new StuckDetector();
        detector.sample(0, 0, 64, 0);
        detector.noteFailure(0, "TARGET_UNREACHABLE");
        detector.noteFailure(20, "MISSING_RESOURCE");
        detector.noteFailure(40, "TARGET_NOT_FOUND");
        assertEquals(StuckDetector.Reason.NONE, detector.reason(60));
    }

    @Test
    void aCitizenNobodyGivesWorkToIsItsOwnKindOfStuck() {
        StuckDetector detector = new StuckDetector();
        detector.sample(0, 0, 64, 0);
        detector.setHasWork(0, false);
        for (long tick = 0; tick <= StuckDetector.STILL_TICKS; tick += 20) {
            // Wandering idly: moving, but never handed a job.
            detector.sample(tick, (int) (tick / 20), 64, 0);
            detector.setHasWork(tick, false);
        }
        assertEquals(StuckDetector.Reason.NO_WORK,
                detector.reason(StuckDetector.STILL_TICKS));
    }

    @Test
    void finishingSomethingClearsEverything() {
        StuckDetector detector = new StuckDetector();
        detector.sample(0, 5, 64, 5);
        detector.setHasWork(0, true);
        for (long tick = 0; tick <= StuckDetector.WORKING_STILL_TICKS; tick += 20) {
            detector.sample(tick, 5, 64, 5);
        }
        assertTrue(detector.isStuck(StuckDetector.WORKING_STILL_TICKS));

        detector.noteProgress(StuckDetector.WORKING_STILL_TICKS);
        assertEquals(StuckDetector.Reason.NONE,
                detector.reason(StuckDetector.WORKING_STILL_TICKS));
        assertEquals(0, detector.repeatedFailures());
    }

    @Test
    void completingNothingForAVeryLongTimeIsUnproductive() {
        StuckDetector detector = new StuckDetector();
        detector.sample(0, 0, 64, 0);
        detector.setHasWork(0, true);
        // Always moving, always has a task, never actually finishes anything —
        // the shape of a citizen looping between two half-done jobs.
        for (long tick = 0; tick <= StuckDetector.UNPRODUCTIVE_TICKS; tick += 10) {
            detector.sample(tick, (int) (tick / 10) % 7, 64, 0);
            detector.setHasWork(tick, true);
        }
        assertEquals(StuckDetector.Reason.UNPRODUCTIVE,
                detector.reason(StuckDetector.UNPRODUCTIVE_TICKS));
    }
}
