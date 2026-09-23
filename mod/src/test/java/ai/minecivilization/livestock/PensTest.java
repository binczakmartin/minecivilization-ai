package ai.minecivilization.livestock;

import ai.minecivilization.construction.AnimalPen;
import ai.minecivilization.construction.ConstructionProject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PensTest {
    @Test void animalsMustBeInsideTheFenceRatherThanMerelyInThePastureDistrict() {
        var pen = new ConstructionProject("test", "Test", AnimalPen.ID, 10, 64, 20);
        assertEquals(new BlockPos(14, 64, 28), Pens.gate(pen));
        assertTrue(Pens.interior(pen).contains(Pens.center(pen).getCenter()));
        assertFalse(Pens.interior(pen).contains(Pens.gate(pen).getCenter()));
        assertFalse(Pens.interior(pen).contains(Pens.gate(pen).south(2).getCenter()));
        assertFalse(Pens.interior(pen).contains(10.5, 64, 24.5));
        assertFalse(Pens.interior(pen).contains(14.5, 59, 24.5));
    }
}
