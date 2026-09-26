package ai.minecivilization.farming;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Where the colony's crops are.
 *
 * <p>The harvest job only knew crops a citizen had personally come across,
 * and planting a seed never counted as coming across it. Wheat was sown,
 * grew ripe and stood there: nobody knew it existed. Every sown cell is now
 * recorded here, and a cheap scan around whoever is looking for work adds any
 * field it walks past, including ones made before this existed.</p>
 */
public final class FieldRegistry {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> FIELDS = new HashMap<>();

    private FieldRegistry() {
    }

    public static synchronized void add(ServerLevel level, BlockPos crop) {
        FIELDS.computeIfAbsent(level.dimension(), k -> new LinkedHashSet<>()).add(crop.immutable());
    }

    /**
     * The nearest ripe crop within {@code range} of {@code from}, forgetting
     * cells that no longer hold a crop at all.
     */
    public static synchronized BlockPos nearestRipe(ServerLevel level, BlockPos from, int range) {
        Set<BlockPos> cells = FIELDS.get(level.dimension());
        if (cells == null) return null;
        BlockPos best = null;
        double bestDist = (double) range * range;
        var it = cells.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (key == null || Crops.produceFor(key.toString()) == null) {
                // Harvested and not replanted, trampled, built over: gone.
                if (!state.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                        && !level.getBlockState(pos.below()).is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
                    it.remove();
                }
                continue;
            }
            if (!Crops.ripe(state, level, pos)) continue;
            double d = pos.distSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /** Record every crop in a small box around {@code centre}. */
    public static void scan(ServerLevel level, BlockPos centre, int radius, int height) {
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-radius, -height, -radius),
                centre.offset(radius, height, radius))) {
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.CropBlock)) continue;
            add(level, pos);
        }
    }

    /** Background survey of the whole town for crops, a slice per tick. */
    private static final int SURVEY_RADIUS = 64;
    private static final int SURVEY_HEIGHT = 12;
    private static final int SURVEY_PER_TICK = 4000;
    private static long surveyIndex;

    /**
     * Keep the registry true to the world without anyone having to walk past.
     *
     * <p>Kept only in memory, the registry started empty after every restart
     * and learnt of a field only when somebody looking for work stood within
     * eight blocks of it — so ripe wheat a little way off stood unharvested
     * for whole sessions. The town is now surveyed continuously, a few
     * thousand blocks per tick, a full pass every few seconds.</p>
     */
    public static void tickSurvey(ServerLevel level) {
        BlockPos centre = ai.minecivilization.colony.ZoneManager.get(level).townCenter(level);
        int side = SURVEY_RADIUS * 2 + 1;
        int height = SURVEY_HEIGHT * 2 + 1;
        long total = (long) side * side * height;
        for (int i = 0; i < SURVEY_PER_TICK; i++) {
            long index = surveyIndex++ % total;
            int x = (int) (index % side) - SURVEY_RADIUS;
            int z = (int) ((index / side) % side) - SURVEY_RADIUS;
            int y = (int) (index / ((long) side * side)) - SURVEY_HEIGHT;
            BlockPos pos = centre.offset(x, y, z);
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.CropBlock) {
                add(level, pos);
            }
        }
    }

    public static synchronized int size(ServerLevel level) {
        Set<BlockPos> cells = FIELDS.get(level.dimension());
        return cells == null ? 0 : cells.size();
    }

    public static synchronized void clear() {
        FIELDS.clear();
    }
}
