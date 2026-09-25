package ai.minecivilization.navigation;

import net.minecraft.core.BlockPos;

/**
 * The minimal world query a terrain plan needs, injected so planning stays
 * pure and unit-testable (same trick as {@link ai.minecivilization.skills.impl.Reachability}).
 *
 * <p>Implementations answer for a single citizen at a single moment: what it
 * could walk through, stand on, and break. The planner never touches the level
 * directly, so a test can describe a ravine or a wall as a handful of lambdas.</p>
 */
public interface BlockView {

    /** The citizen's body may occupy this cell (no collision, no lava). */
    boolean passable(BlockPos pos);

    /** The citizen may stand on top of this cell (sturdy up-face, never leaves). */
    boolean sturdy(BlockPos pos);

    /** This cell can be mined away — false for bedrock, liquids and protected blocks. */
    boolean diggable(BlockPos pos);

    /** Inside the buildable world and inside a loaded chunk. */
    boolean inBounds(BlockPos pos);

    /**
     * True when this cell is water a citizen can swim through.
     *
     * <p>Water is the one medium that is neither ground nor obstacle: there is
     * nothing to stand on, yet a citizen can move through it in any direction
     * including straight up. Treating it as "passable but with no floor" made
     * every route across a lake a bridge-building project, and every route
     * that ended in water unreachable — which is why a citizen that fell in a
     * river stayed in it.</p>
     */
    default boolean swimmable(BlockPos pos) {
        return false;
    }
}
