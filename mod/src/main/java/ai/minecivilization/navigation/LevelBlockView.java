package ai.minecivilization.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * {@link BlockView} backed by a live server level — the adapter that lets the
 * pure {@link TerrainPlanner} reason about a real world.
 *
 * <p>Two safety rules live here rather than in the planner, because they are
 * about the settlement and not about geometry: lava is never walked into, and
 * anything the citizens built or filled (containers, workstations, beds) is
 * never tunnelled through. A citizen that mined its way out through the
 * warehouse wall would be technically efficient and practically a disaster.</p>
 */
public final class LevelBlockView implements BlockView {

    private final ServerLevel level;

    public LevelBlockView(ServerLevel level) {
        this.level = level;
    }

    @Override
    public boolean passable(BlockPos pos) {
        if (!inBounds(pos)) return false;
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        // Lava is "walkable" as far as collision goes. It is not.
        if (!fluid.isEmpty() && fluid.is(net.minecraft.tags.FluidTags.LAVA)) return false;
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    @Override
    public boolean sturdy(BlockPos pos) {
        if (!inBounds(pos)) return false;
        BlockState state = level.getBlockState(pos);
        // Matches the reachability rules used elsewhere: you cannot balance on
        // a leaf canopy, so a route must never assume it.
        if (state.getBlock() instanceof LeavesBlock) return false;
        if (state.is(Blocks.MAGMA_BLOCK)) return false;
        return state.isFaceSturdy(level, pos, Direction.UP);
    }

    @Override
    public boolean diggable(BlockPos pos) {
        if (!inBounds(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;       // never "mine" a fluid
        if (state.getDestroySpeed(level, pos) < 0f) return false;  // bedrock & friends
        if (state.is(BlockTags.BEDS) || state.is(BlockTags.DOORS)) return false;
        if (isSettlementProperty(state)) return false;
        // Anything with contents or state worth keeping (chests, furnaces, signs).
        return !(level.getBlockEntity(pos) instanceof BlockEntity);
    }

    @Override
    public boolean inBounds(BlockPos pos) {
        return pos.getY() >= level.getMinBuildHeight()
                && pos.getY() < level.getMaxBuildHeight()
                && level.isLoaded(pos);
    }

    /** Blocks the citizens placed on purpose — a shortcut is never worth these. */
    private static boolean isSettlementProperty(BlockState state) {
        return state.is(Blocks.CRAFTING_TABLE)
                || state.is(Blocks.FURNACE)
                || state.is(Blocks.BLAST_FURNACE)
                || state.is(Blocks.SMOKER)
                || state.is(Blocks.CHEST)
                || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.BARREL)
                || state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.FARMLAND)
                || state.is(BlockTags.CROPS);
    }
}
