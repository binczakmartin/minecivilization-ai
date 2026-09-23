package ai.minecivilization.forestry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Telling a tree from a log cabin — pure geometry over hand-drawn worlds. */
class TreeShapeTest {

    private static final int GROUND = 64;

    /** A plain oak: five trunk logs with a leaf canopy on top. */
    private static Set<BlockPos> plainOak(int x, int z) {
        Set<BlockPos> logs = new HashSet<>();
        for (int dy = 0; dy < 5; dy++) {
            logs.add(new BlockPos(x, GROUND + dy, z));
        }
        return logs;
    }

    private static Set<BlockPos> canopyAround(int x, int z, int y) {
        Set<BlockPos> leaves = new HashSet<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                leaves.add(new BlockPos(x + dx, y, z + dz));
                leaves.add(new BlockPos(x + dx, y + 1, z + dz));
            }
        }
        return leaves;
    }

    private static Predicate<BlockPos> in(Set<BlockPos> set) {
        return set::contains;
    }

    // ------------------------------------------------------------------ felling

    @Test
    void theWholeTrunkIsFelledNotJustTheBlockThatWasHit() {
        Set<BlockPos> logs = plainOak(0, 0);
        Set<BlockPos> leaves = canopyAround(0, 0, GROUND + 4);

        List<BlockPos> felled = TreeShape.collect(
                new BlockPos(0, GROUND + 2, 0), in(logs), in(leaves));

        assertEquals(5, felled.size(), "leaving four logs standing is what makes stumps");
        assertEquals(new HashSet<>(felled), logs);
    }

    @Test
    void fellingStartsAtTheFootOfTheTrunk() {
        Set<BlockPos> logs = plainOak(0, 0);
        Set<BlockPos> leaves = canopyAround(0, 0, GROUND + 4);

        List<BlockPos> felled = TreeShape.collect(
                new BlockPos(0, GROUND + 3, 0), in(logs), in(leaves));

        assertEquals(GROUND, felled.get(0).getY(), "a citizen works from the ground up");
        for (int i = 1; i < felled.size(); i++) {
            assertTrue(felled.get(i).getY() >= felled.get(i - 1).getY(),
                    "felling order must never jump back down");
        }
    }

    @Test
    void theBaseIsFoundFromAnywhereInTheTrunk() {
        Set<BlockPos> logs = plainOak(3, -7);
        assertEquals(new BlockPos(3, GROUND, -7),
                TreeShape.baseOf(new BlockPos(3, GROUND + 4, -7), in(logs)));
        assertEquals(new BlockPos(3, GROUND, -7),
                TreeShape.baseOf(new BlockPos(3, GROUND, -7), in(logs)));
    }

    @Test
    void diagonalBranchesAreTakenToo() {
        // Trees branch diagonally, so face-only flooding would leave limbs behind.
        Set<BlockPos> logs = plainOak(0, 0);
        logs.add(new BlockPos(1, GROUND + 5, 1));   // diagonal limb
        logs.add(new BlockPos(2, GROUND + 6, 2));
        Set<BlockPos> leaves = canopyAround(0, 0, GROUND + 6);

        List<BlockPos> felled = TreeShape.collect(new BlockPos(0, GROUND, 0),
                in(logs), in(leaves));

        assertEquals(7, felled.size());
        assertTrue(felled.contains(new BlockPos(2, GROUND + 6, 2)), "the far limb was left behind");
    }

    @Test
    void twoTreesSideBySideAreFelledSeparately() {
        Set<BlockPos> logs = new HashSet<>(plainOak(0, 0));
        logs.addAll(plainOak(5, 0));  // far enough apart not to touch
        Set<BlockPos> leaves = new HashSet<>(canopyAround(0, 0, GROUND + 4));
        leaves.addAll(canopyAround(5, 0, GROUND + 4));

        List<BlockPos> felled = TreeShape.collect(new BlockPos(0, GROUND, 0),
                in(logs), in(leaves));

        assertEquals(5, felled.size(), "one job is one tree");
        assertFalse(felled.contains(new BlockPos(5, GROUND, 0)));
    }

    // ------------------------------------------------------------------ not a tree

    @Test
    void aLogCabinIsNeverMistakenForATree() {
        // A 10x10 wall of logs, no canopy: exactly what a player builds.
        Set<BlockPos> cabin = new HashSet<>();
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 4; y++) {
                cabin.add(new BlockPos(x, GROUND + y, 0));
                cabin.add(new BlockPos(x, GROUND + y, 9));
            }
        }

        List<BlockPos> felled = TreeShape.collect(new BlockPos(4, GROUND + 1, 0),
                in(cabin), p -> false);

        assertEquals(1, felled.size(),
                "with no canopy this is a building — take one block, not the wall");
    }

    @Test
    void aRegionThatRunsPastTreeSizeIsRefused() {
        // A log floor stretching far past any canopy's reach.
        Predicate<BlockPos> endlessFloor = p -> p.getY() == GROUND;
        List<BlockPos> felled = TreeShape.collect(new BlockPos(0, GROUND, 0),
                endlessFloor, p -> true);

        assertEquals(1, felled.size(), "a floor is not a tree however leafy its surroundings");
    }

    @Test
    void aLeafyTreeIsStillFelledEvenWhenTheTrunkIsBare() {
        // Only the crown carries leaves — the trunk below is bare, as usual.
        Set<BlockPos> logs = plainOak(0, 0);
        Set<BlockPos> leaves = canopyAround(0, 0, GROUND + 4);

        assertEquals(5, TreeShape.collect(new BlockPos(0, GROUND, 0),
                in(logs), in(leaves)).size());
    }

    @Test
    void anEmptyOrNonLogTargetYieldsNothing() {
        assertTrue(TreeShape.collect(new BlockPos(0, GROUND, 0), p -> false, p -> true).isEmpty());
        assertTrue(TreeShape.collect(null, p -> true, p -> true).isEmpty());
    }

    @Test
    void collectionIsDeterministic() {
        Set<BlockPos> logs = plainOak(0, 0);
        logs.add(new BlockPos(1, GROUND + 4, 0));
        logs.add(new BlockPos(-1, GROUND + 4, 0));
        Set<BlockPos> leaves = canopyAround(0, 0, GROUND + 5);

        List<BlockPos> a = TreeShape.collect(new BlockPos(0, GROUND, 0), in(logs), in(leaves));
        List<BlockPos> b = TreeShape.collect(new BlockPos(0, GROUND, 0), in(logs), in(leaves));
        assertEquals(a, b, "a retry must fell in the same order");
    }
}
