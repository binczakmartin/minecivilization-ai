package ai.minecivilization.skills.impl;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reachability geometry with fake block queries: no world needed, the rules
 * that keep citizens out of leaf crowns and walls are pure predicates.
 */
class ReachabilityTest {

    private static final BlockPos T = new BlockPos(0, 64, 0);

    private static Predicate<BlockPos> setOf(BlockPos... positions) {
        Set<BlockPos> set = new HashSet<>(Set.of(positions));
        return set::contains;
    }

    // ---------------------------------------------------------------- isStandable

    @Test
    void standableNeedsFeetHeadAndFloor() {
        Predicate<BlockPos> air = setOf(T, T.above());
        Predicate<BlockPos> floor = setOf(T.below());
        assertTrue(Reachability.isStandable(T, air, floor));
        assertFalse(Reachability.isStandable(T, setOf(T), floor));   // head blocked
        assertFalse(Reachability.isStandable(T, air, setOf()));      // no floor
    }

    // ---------------------------------------------------------------- canInteractFrom

    @Test
    void sideExposedBlockIsReachable() {
        // trunk-base pattern: air beside the block, solid ground below that air
        BlockPos side = new BlockPos(-1, 64, 0);
        assertTrue(Reachability.canInteractFrom(T,
                setOf(side, side.above()),
                setOf(side.below())));
    }

    @Test
    void blockOnTopIsReachable() {
        // farmland / floor pattern: stand on the target itself
        assertTrue(Reachability.canInteractFrom(T,
                setOf(T.above(), T.above(2)),
                setOf(T)));
    }

    @Test
    void buriedBlockIsUnreachable() {
        // every body position is solid: no stand exists
        assertFalse(Reachability.canInteractFrom(T, setOf(), setOf(T.below())));
    }

    @Test
    void leafFloorDoesNotCountAsStand() {
        // canopy pattern: air beside the log, but the floor under it is leaves
        // (leaves are simply absent from the sturdyFloor predicate)
        BlockPos side = new BlockPos(1, 64, 0);
        assertFalse(Reachability.canInteractFrom(T,
                setOf(side, side.above(), side.above(2)),
                setOf(T.below()))); // only the low floor is sturdy — not under `side`
    }

    @Test
    void canopyLogSurroundedByLeavesIsUnreachable() {
        // air gap beside the log with leaves as floor, leaves above its top
        BlockPos gap = new BlockPos(-1, 64, 0);
        assertFalse(Reachability.canInteractFrom(T,
                setOf(gap, gap.above(), T.above()),  // air gap + air above the log
                setOf(T.below())));                  // no sturdy floor under the gap
    }
}
