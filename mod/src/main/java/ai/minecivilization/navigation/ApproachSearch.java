package ai.minecivilization.navigation;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic stand positions to try when a target block cannot be walked
 * into (it is solid): beside it first, then on top, then wider rings one
 * block up/down. Pure ordering — the navigator injects the world test
 * (standable + pathable) while iterating.
 */
public final class ApproachSearch {

    private ApproachSearch() {
    }

    /** Candidate feet positions around {@code target}, best first (never the target itself). */
    public static List<BlockPos> candidatesAround(BlockPos target) {
        List<BlockPos> out = new ArrayList<>(145);
        addRing(out, target, 1, 0);   // beside — the natural mining pose
        addRing(out, target, 0, 1);   // standing on top of it
        addRing(out, target, 1, -1);  // beside, one block lower
        addRing(out, target, 1, 1);   // beside, one block higher
        addRing(out, target, 2, 0);
        addRing(out, target, 2, -1);
        addRing(out, target, 2, 1);
        addRing(out, target, 3, 0);
        addRing(out, target, 3, -1);
        addRing(out, target, 3, 1);
        return out;
    }

    /** Cells at Chebyshev radius {@code r}, sorted by distance then dx, dz. */
    private static void addRing(List<BlockPos> out, BlockPos t, int r, int dy) {
        if (r == 0) {
            if (dy != 0) {
                out.add(new BlockPos(t.getX(), t.getY() + dy, t.getZ()));
            }
            return;
        }
        List<int[]> cells = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                cells.add(new int[]{dx * dx + dz * dz, dx, dz});
            }
        }
        cells.sort(Comparator.comparingInt((int[] c) -> c[0])
                .thenComparingInt(c -> c[1])
                .thenComparingInt(c -> c[2]));
        for (int[] c : cells) {
            out.add(new BlockPos(t.getX() + c[1], t.getY() + dy, t.getZ() + c[2]));
        }
    }
}
