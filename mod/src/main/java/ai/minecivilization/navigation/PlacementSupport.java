package ai.minecivilization.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Placement checks shared by autonomous workers.
 *
 * <p>{@link BlockState#canSurvive} is not enough for a worker using
 * {@code setBlock}: a number of utility blocks report that they can survive in
 * an otherwise empty cell, while the normal player placement rules would have
 * attached them to a face.  Citizens were therefore able to put tables,
 * furnaces and chests in mid-air.  A direct worker placement must either have a
 * sturdy floor or a real side face to attach to.</p>
 */
public final class PlacementSupport {

    private PlacementSupport() {
    }

    /** True when this exact state can be placed without a click context. */
    public static boolean canPlace(ServerLevel level, BlockState state, BlockPos pos) {
        if (level == null || state == null || pos == null) return false;
        if (!state.canSurvive(level, pos)) return false;

        BlockPos below = pos.below();
        if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return true;
        }

        // Wall torches, signs and other directional decorations may be attached
        // to a side instead.  setBlock has no clicked face, so only accept the
        // side opposite the state's own horizontal facing.
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (facing.getAxis().isHorizontal()) {
                BlockPos support = pos.relative(facing.getOpposite());
                return level.getBlockState(support).isFaceSturdy(level, support, facing);
            }
        }
        return false;
    }
}
