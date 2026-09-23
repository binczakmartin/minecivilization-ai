package ai.minecivilization.livestock;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HerdPolicyTest {
    @Test void breedingCountsBabiesAndPendingBirths() {
        assertTrue(HerdPolicy.canBreed(9, 0, 2, 10));
        assertFalse(HerdPolicy.canBreed(9, 1, 2, 10));
        assertFalse(HerdPolicy.canBreed(10, 0, 8, 10));
        assertFalse(HerdPolicy.canBreed(3, 0, 1, 10));
    }
    @Test void slaughterProtectsBabiesAndTheLastBreedingPair() {
        assertTrue(HerdPolicy.canCull(11, 5, false, 10));
        assertFalse(HerdPolicy.canCull(11, 2, false, 10));
        assertFalse(HerdPolicy.canCull(11, 5, true, 10));
        assertFalse(HerdPolicy.canCull(10, 5, false, 10));
    }
    @Test void foodProductionCanRenewAFullHerdWithoutExceedingTheCap() {
        assertTrue(HerdPolicy.canHarvestForFood(10, 5, false, 10, true));
        assertFalse(HerdPolicy.canHarvestForFood(9, 5, false, 10, true));
        assertFalse(HerdPolicy.canHarvestForFood(10, 5, false, 10, false));
        assertFalse(HerdPolicy.canHarvestForFood(10, 2, false, 10, true));
    }
    @Test void wolvesStopAtFiftyAndZeroDisablesAcquisition() {
        assertTrue(HerdPolicy.canRecruit(49, 50));
        assertFalse(HerdPolicy.canRecruit(50, 50));
        assertFalse(HerdPolicy.canRecruit(51, 50));
        assertFalse(HerdPolicy.canRecruit(0, 0));
    }
}
