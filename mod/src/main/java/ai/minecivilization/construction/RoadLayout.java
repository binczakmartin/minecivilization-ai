package ai.minecivilization.construction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * Pure geometry for small, buildable colony paths.
 *
 * <p>This is deliberately not a terrain editor.  It lays out a bounded
 * corridor; the world adapter still has to verify air, support, ownership and
 * the citizen's AABB before a worker is allowed to place a block.  Keeping the
 * geometry pure makes it possible to test that roads stay inside their segment
 * and never fan out through plots or buildings.</p>
 */
public final class RoadLayout {

    private RoadLayout() {
    }

    /**
     * Build an orthogonal, two-column-friendly corridor from {@code from} to
     * {@code to}.  The path is capped at {@code maxLength} horizontal steps;
     * callers that need a longer road should create several segments.
     *
     * @param width 1 for a single-file path, 2 for a small two-wide lane
     */
    public static List<BlockPos> segment(BlockPos from, BlockPos to, int width, int maxLength) {
        if (from == null || to == null) return List.of();
        int cap = Math.max(1, maxLength);
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int horizontal = Math.abs(dx) + Math.abs(dz);
        if (horizontal == 0) return List.of(from.immutable());

        int steps = Math.min(horizontal, cap);
        int x = from.getX();
        int z = from.getZ();
        int xRemaining = Math.abs(dx);
        int zRemaining = Math.abs(dz);
        int xStep = Integer.signum(dx);
        int zStep = Integer.signum(dz);
        List<BlockPos> center = new ArrayList<>(steps + 1);
        center.add(new BlockPos(x, from.getY(), z));

        for (int i = 1; i <= steps; i++) {
            if (xRemaining > 0) {
                x += xStep;
                xRemaining--;
            } else {
                z += zStep;
                zRemaining--;
            }
            int y = from.getY() + Math.round((to.getY() - from.getY()) * (i / (float) steps));
            center.add(new BlockPos(x, y, z));
        }

        int widthSafe = Math.max(1, Math.min(3, width));
        int perpendicularX = Integer.signum(to.getX() - from.getX()) == 0
                ? 1 : 0;
        int perpendicularZ = Integer.signum(to.getX() - from.getX()) == 0
                ? 0 : 1;
        if (perpendicularX == 0 && perpendicularZ == 0) {
            perpendicularX = 1;
        }
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (BlockPos point : center) {
            cells.add(point.immutable());
            if (widthSafe >= 2) {
                cells.add(point.offset(perpendicularX, 0, perpendicularZ).immutable());
            }
            if (widthSafe >= 3) {
                cells.add(point.offset(-perpendicularX, 0, -perpendicularZ).immutable());
            }
        }
        return List.copyOf(cells);
    }

    /** Convenience for a two-wide path with a conservative segment cap. */
    public static List<BlockPos> segment(BlockPos from, BlockPos to) {
        return segment(from, to, 2, 24);
    }
}
