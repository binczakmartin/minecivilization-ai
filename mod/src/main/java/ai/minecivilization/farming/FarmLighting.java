package ai.minecivilization.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.*;

/** Uses block light, so sunny fields are prepared before nightfall. */
public final class FarmLighting {
    public static final int MIN_CROP_LIGHT = 9;
    public static boolean needsLight(int blockLight) { return blockLight < MIN_CROP_LIGHT; }
    public static boolean crop(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof CropBlock || state.getBlock() instanceof StemBlock
            || state.getBlock() instanceof SweetBerryBushBlock;
    }
    public static BlockPos findCrop(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double distance = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-12, -5, -12), origin.offset(12, 5, 12))) {
            if (!level.isLoaded(p) || !crop(level.getBlockState(p))
                    || !needsLight(level.getBrightness(LightLayer.BLOCK, p))) continue;
            double d = p.distSqr(origin);
            if (d < distance && torchSpot(level, p) != null) { best = p.immutable(); distance = d; }
        }
        return best;
    }
    public static BlockPos torchSpot(ServerLevel level, BlockPos crop) {
        for (int radius = 1; radius <= 3; radius++) {
            for (BlockPos p : BlockPos.betweenClosed(crop.offset(-radius, -1, -radius), crop.offset(radius, 1, radius))) {
                if (p.distManhattan(crop) > 4 || !level.isLoaded(p)) continue;
                // Air only: never replace crops, water, flowers or somebody's structure.
                if (level.getBlockState(p).isAir() && Blocks.TORCH.defaultBlockState().canSurvive(level, p)
                        && clearLightPath(level, p, crop)) return p.immutable();
            }
        }
        return null;
    }
    private static boolean clearLightPath(ServerLevel level, BlockPos start, BlockPos end) {
        BlockPos p = start;
        while (!p.equals(end)) {
            if (p.getY() != end.getY()) p = p.offset(0, Integer.signum(end.getY() - p.getY()), 0);
            else if (p.getX() != end.getX()) p = p.offset(Integer.signum(end.getX() - p.getX()), 0, 0);
            else p = p.offset(0, 0, Integer.signum(end.getZ() - p.getZ()));
            if (level.getBlockState(p).getLightBlock(level, p) > 0) return false;
        }
        return true;
    }
    private FarmLighting() {}
}
