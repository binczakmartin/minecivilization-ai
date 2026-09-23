package ai.minecivilization.colony;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.navigation.LevelBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Resolves a physical rendezvous point around the logical town centre.
 *
 * <p>The centre coordinate is a zoning anchor, not a place to stand.  Citizens
 * used to receive that exact cell as their home target, so a tree, a crop, a
 * roof or a piece of player construction could make the target unusable and
 * every returning citizen would converge on the same column.  This resolver
 * keeps the anchor stable but chooses a standable, unoccupied cell from a
 * deterministic ring around it.</p>
 */
public final class HomeDestination {

    private static final int MAX_RING = 8;
    private static final int[] Y_OFFSETS = {
            0, -1, 1, -2, 2, -3, 3, -4, 4, -5, 5, -6, 6, -7, 7, -8, 8
    };
    private static final List<Offset> OFFSETS = buildOffsets();

    private HomeDestination() {
    }

    /** A standable cell reserved for this citizen, or the logical centre as a last resort. */
    public static BlockPos forCitizen(ServerLevel level, CitizenEntity citizen) {
        if (level == null || citizen == null) return null;
        BlockPos centre = ZoneManager.get(level).townCenter(level);
        LevelBlockView view = new LevelBlockView(level);
        // Start every UUID in the two nearest rings (24 cells), then fan out
        // only when those cells are occupied.  Hashing over all 289 cells
        // would make a normal return walk most of the way across town.
        int preferred = Math.floorMod(citizen.getUUID().hashCode(), 24);

        for (int i = 0; i < OFFSETS.size(); i++) {
            Offset offset = OFFSETS.get((preferred + i) % OFFSETS.size());
            for (int dy : Y_OFFSETS) {
                BlockPos feet = new BlockPos(centre.getX() + offset.x(),
                        centre.getY() + dy, centre.getZ() + offset.z());
                if (isSafe(level, view, citizen, feet)) return feet;
            }
        }

        // No free ring cell was available.  Returning the anchor preserves the
        // old behaviour as a last resort, while the resolver above keeps the
        // normal case physical and distributed.
        for (int dy : Y_OFFSETS) {
            BlockPos feet = centre.above(dy);
            if (isSafe(level, view, citizen, feet)) return feet;
        }
        return centre;
    }

    private static boolean isSafe(ServerLevel level, LevelBlockView view,
                                   CitizenEntity citizen, BlockPos feet) {
        if (!level.isLoaded(feet)) return false;
        if (!view.passable(feet) || !view.passable(feet.above())
                || !view.sturdy(feet.below())) return false;
        // Do not make a citizen stand on a crop or a cultivated field while it
        // is merely trying to get home.
        BlockState floor = level.getBlockState(feet.below());
        if (level.getBlockState(feet).is(BlockTags.CROPS)
                || floor.is(Blocks.FARMLAND)
                || floor.is(Blocks.CRAFTING_TABLE)
                || floor.is(Blocks.FURNACE)
                || floor.is(Blocks.CHEST)
                || floor.is(Blocks.BARREL)) return false;

        AABB body = new AABB(feet.getX(), feet.getY(), feet.getZ(),
                feet.getX() + 1.0, feet.getY() + 1.8, feet.getZ() + 1.0);
        for (CitizenEntity other : CitizenIndex.all()) {
            if (other == citizen || other.isRemoved()) continue;
            if (other.getBoundingBox().intersects(body)) return false;
        }
        return true;
    }

    private record Offset(int x, int z) {
    }

    private static List<Offset> buildOffsets() {
        List<Offset> out = new ArrayList<>();
        for (int ring = 1; ring <= MAX_RING; ring++) {
            for (int x = -ring; x <= ring; x++) {
                for (int z = -ring; z <= ring; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) == ring) {
                        out.add(new Offset(x, z));
                    }
                }
            }
        }
        // The exact centre is deliberately last.  It remains a fallback, but
        // is not the first thing every UUID tries.
        out.add(new Offset(0, 0));
        return out;
    }
}
