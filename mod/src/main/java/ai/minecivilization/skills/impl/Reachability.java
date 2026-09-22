package ai.minecivilization.skills.impl;

import net.minecraft.core.BlockPos;

import java.util.function.Predicate;

/**
 * Can a citizen physically interact with this block? Pure geometry over an
 * injected block query — no world access, fully unit tested.
 *
 * <p>"Reachable" means: there exists a place to stand (feet + head free of
 * collision, sturdy floor below — never leaves, you cannot balance on a
 * canopy) within arm's reach of the target. A block buried in stone or
 * hanging in the middle of a leaf crown is unreachable, so smarter searches
 * skip it instead of walking over and failing with TARGET_UNREACHABLE.</p>
 */
public final class Reachability {

    private Reachability() {
    }

    /** Feet and head have no collision and the floor below is sturdy. */
    public static boolean isStandable(BlockPos feet,
                                      Predicate<BlockPos> bodyFree,
                                      Predicate<BlockPos> sturdyFloor) {
        return bodyFree.test(feet)
                && bodyFree.test(feet.above())
                && sturdyFloor.test(feet.below());
    }

    /**
     * True when some stand position touches the target: the target's own
     * column or any of the 8 horizontal neighbours, one block below, at, or
     * one block above the target's level (27 candidates, fixed order).
     *
     * <p>Covers both interaction poses: standing beside a solid block to mine
     * it, and standing on/over a workable block (farmland, a floor block).</p>
     */
    public static boolean canInteractFrom(BlockPos target,
                                          Predicate<BlockPos> bodyFree,
                                          Predicate<BlockPos> sturdyFloor) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos feet = new BlockPos(
                            target.getX() + dx, target.getY() + dy, target.getZ() + dz);
                    if (isStandable(feet, bodyFree, sturdyFloor)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
