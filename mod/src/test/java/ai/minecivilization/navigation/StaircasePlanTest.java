package ai.minecivilization.navigation;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of the escape a buried citizen digs.
 *
 * <p>The geometry has one job: every tread must be climbable from the one
 * below it. A staircase that gains two blocks in a step, or that goes straight
 * up, is a shaft the citizen cannot use — which would turn the last resort of
 * the rescue ladder into another way to be stuck.</p>
 */
class StaircasePlanTest {

    @Test
    void everyTreadRisesExactlyOneBlockAndAdvancesExactlyOne() {
        List<StaircasePlan.Step> steps =
                StaircasePlan.upward(0, 10, 0, 20, 1, 0);

        assertEquals(10, steps.size());
        int previousY = 10;
        int previousX = 0;
        for (StaircasePlan.Step step : steps) {
            assertEquals(previousY + 1, step.y(), "a mob cannot climb more than one block");
            assertEquals(previousX + 1, step.x(), "a staircase that does not advance is a shaft");
            assertEquals(0, step.z());
            previousY = step.y();
            previousX = step.x();
        }
    }

    @Test
    void theStartingCellIsNotATread() {
        // The citizen is already standing in it; including it would have the
        // escape begin by mining the floor out from under itself.
        List<StaircasePlan.Step> steps = StaircasePlan.upward(5, 0, 5, 3, 0, 1);
        assertEquals(1, steps.get(0).y());
        assertEquals(3, steps.size());
    }

    @Test
    void nothingToClimbProducesNoSteps() {
        assertTrue(StaircasePlan.upward(0, 64, 0, 64, 1, 0).isEmpty());
        assertTrue(StaircasePlan.upward(0, 64, 0, 10, 1, 0).isEmpty());
    }

    @Test
    void aDiagonalRequestIsFlattenedToACardinal() {
        // Diagonal treads leave a corner a walking mob clips on.
        List<StaircasePlan.Step> steps = StaircasePlan.upward(0, 0, 0, 3, 1, 1);
        for (StaircasePlan.Step step : steps) {
            assertEquals(0, step.z(), "treads must advance along one axis only");
        }
    }

    @Test
    void aDirectionlessRequestStillProducesAClimbableStair() {
        List<StaircasePlan.Step> steps = StaircasePlan.upward(0, 0, 0, 3, 0, 0);
        assertEquals(3, steps.size());
        assertEquals(1, steps.get(0).x());
    }

    @Test
    void aVeryDeepClimbIsCapped() {
        List<StaircasePlan.Step> steps = StaircasePlan.upward(0, -2000, 0, 5000, 1, 0);
        assertEquals(StaircasePlan.MAX_STEPS, steps.size());
    }

    @Test
    void theStairLeansTowardWhereverItIsAimed() {
        assertArrayEquals(new int[]{1, 0}, StaircasePlan.cardinalToward(0, 0, 500, 10));
        assertArrayEquals(new int[]{-1, 0}, StaircasePlan.cardinalToward(0, 0, -500, 10));
        assertArrayEquals(new int[]{0, 1}, StaircasePlan.cardinalToward(0, 0, 10, 500));
        assertArrayEquals(new int[]{0, -1}, StaircasePlan.cardinalToward(0, 0, 10, -500));
    }

    @Test
    void theCostOfDiggingOutIsTwoBlocksPerTread() {
        // Feet and head: what a citizen needs in order to walk up it.
        assertEquals(80, StaircasePlan.blocksToRemove(40));
        assertEquals(0, StaircasePlan.blocksToRemove(0));
        assertEquals(0, StaircasePlan.blocksToRemove(-5));
    }
}
