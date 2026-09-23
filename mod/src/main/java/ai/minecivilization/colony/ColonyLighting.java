package ai.minecivilization.colony;

import ai.minecivilization.farming.FarmLighting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

/** Artificial light is maintained during daylight too. Searches only loaded work areas. */
public final class ColonyLighting {
    public static int requiredLight(boolean crop) { return crop ? 9 : 7; }
    public static boolean needsLight(ServerLevel level, BlockPos p) {
        return level.isLoaded(p) && level.getBrightness(LightLayer.BLOCK, p)
            < requiredLight(FarmLighting.crop(level.getBlockState(p)));
    }
    private static boolean walkway(ServerLevel level, BlockPos p) {
        return level.isLoaded(p) && level.getBlockState(p).isAir()
            && level.getBlockState(p.above()).isAir()
            && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP);
    }
    public static BlockPos find(ServerLevel level, BlockPos origin) {
        BlockPos crop = FarmLighting.findCrop(level, origin);
        if (crop != null) return crop;
        var zones = ZoneManager.get(level);
        BlockPos best = null;
        double distance = Double.MAX_VALUE;
        boolean undergroundWorker = level.hasChunkAt(origin) && origin.getY() + 4
            < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
        // Local interiors and underground passages, including old mines without markers.
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-8, -4, -8), origin.offset(8, 4, 8))) {
            var zone = zones.at(p);
            if ((!undergroundWorker && (zone == null || zone.type == ZoneType.FOREST)) || !walkway(level, p) || !needsLight(level, p)) continue;
            double d = p.distSqr(origin);
            if (d < distance && FarmLighting.torchSpot(level, p) != null) { best = p.immutable(); distance = d; }
        }
        if (best != null) return best;
        for (BlockPos p : ai.minecivilization.mining.MineWorks.get(level).passages()) {
            double d = p.distSqr(origin);
            if (d < distance && walkway(level, p) && needsLight(level, p) && FarmLighting.torchSpot(level, p) != null) {
                best = p; distance = d;
            }
        }
        // Distribute surface lighting across housing, storage, workshops and pasture.
        for (Zone zone : zones.all()) {
            if (zone.type == ZoneType.FOREST) continue;
            for (int x = zone.minX + 1; x < zone.maxX; x += 4) for (int z = zone.minZ + 1; z < zone.maxZ; z += 4) {
                if (!level.hasChunkAt(new BlockPos(x, origin.getY(), z))) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos p = new BlockPos(x, y, z);
                double d = p.distSqr(origin);
                if (d < distance && walkway(level, p) && needsLight(level, p) && FarmLighting.torchSpot(level, p) != null) {
                    best = p; distance = d;
                }
            }
        }
        return best;
    }
    private ColonyLighting() {}
}
