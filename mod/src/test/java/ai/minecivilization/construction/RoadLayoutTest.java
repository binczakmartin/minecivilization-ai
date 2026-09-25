package ai.minecivilization.construction;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadLayoutTest {

    @Test
    void createsAConnectedOrthogonalSegment() {
        List<BlockPos> cells = RoadLayout.segment(
                new BlockPos(0, 64, 0), new BlockPos(5, 64, 0), 1, 10);

        assertEquals(6, cells.size());
        for (int i = 1; i < cells.size(); i++) {
            int dx = Math.abs(cells.get(i).getX() - cells.get(i - 1).getX());
            int dz = Math.abs(cells.get(i).getZ() - cells.get(i - 1).getZ());
            assertTrue(dx + dz == 1, "road cells must be adjacent");
        }
    }

    @Test
    void widthTwoProducesASecondLaneWithoutDuplicates() {
        List<BlockPos> cells = RoadLayout.segment(
                new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), 2, 10);
        Set<BlockPos> unique = new HashSet<>(cells);

        assertEquals(cells.size(), unique.size());
        assertTrue(cells.contains(new BlockPos(0, 64, 1)));
        assertTrue(cells.contains(new BlockPos(4, 64, 1)));
    }

    @Test
    void capsLongRoadsIntoBoundedSegments() {
        List<BlockPos> cells = RoadLayout.segment(
                new BlockPos(0, 64, 0), new BlockPos(100, 64, 0), 1, 8);
        assertEquals(9, cells.size());
        assertEquals(8, cells.getLast().getX());
    }
}
