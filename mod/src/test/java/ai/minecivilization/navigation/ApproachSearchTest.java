package ai.minecivilization.navigation;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ordering guarantees of the approach candidate list (pure, no world). */
class ApproachSearchTest {

    private static final BlockPos T = new BlockPos(10, 64, -3);

    @Test
    void neverSuggestsStandingInsideTheTarget() {
        for (BlockPos p : ApproachSearch.candidatesAround(T)) {
            assertFalse(p.equals(T), "the target itself is not a stand position");
        }
    }

    @Test
    void besideTheBlockComesFirst() {
        List<BlockPos> c = ApproachSearch.candidatesAround(T);
        // phase 1: radius 1, same level — straights before diagonals
        assertEquals(new BlockPos(9, 64, -3), c.get(0));   // dx=-1 (d²=1, dx min)
        assertEquals(new BlockPos(10, 64, -4), c.get(1));  // dz=-1
        assertEquals(new BlockPos(10, 64, -2), c.get(2));  // dz=+1
        assertEquals(new BlockPos(11, 64, -3), c.get(3));  // dx=+1
        // diagonals of the same ring (d²=2) follow
        assertEquals(new BlockPos(9, 64, -4), c.get(4));
        // standing on top of the target is the next phase (index 8)
        assertEquals(new BlockPos(10, 65, -3), c.get(8));
    }

    @Test
    void candidateCountAndBounds() {
        List<BlockPos> c = ApproachSearch.candidatesAround(T);
        // 8 + 1 + 8 + 8 + 16 + 16 + 16 + 24 + 24 + 24 = 145 candidates
        assertEquals(145, c.size());
        for (BlockPos p : c) {
            int chebyshev = Math.max(Math.abs(p.getX() - T.getX()), Math.abs(p.getZ() - T.getZ()));
            assertTrue(chebyshev <= 3 && Math.abs(p.getY() - T.getY()) <= 1,
                    "candidate out of bounds: " + p);
        }
    }
}
