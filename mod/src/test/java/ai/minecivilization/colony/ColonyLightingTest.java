package ai.minecivilization.colony;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColonyLightingTest {
    @Test void livingAreasAndMinesReceiveLightEvenWithoutCrops() {
        assertEquals(7, ColonyLighting.requiredLight(false));
        assertEquals(9, ColonyLighting.requiredLight(true));
    }
}
