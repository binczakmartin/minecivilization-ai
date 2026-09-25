package ai.minecivilization.navigation;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a staircase cut out of solid ground.
 *
 * <p>A citizen sealed underground has exactly one guaranteed way out, and it is
 * not pathfinding: it is removing rock until there is sky overhead. This is the
 * geometry of that — one block forward and one block up per step, which is the
 * steepest stair a walking mob can climb without jumping.</p>
 *
 * <p>Pure integer geometry, so the shape of every emergency exit in the colony
 * is unit tested rather than inspected in a save file.</p>
 */
public final class StaircasePlan {

    /** One tread: where the citizen's feet go. Head room is the cell above. */
    public record Step(int x, int y, int z) {
    }

    /** Longest staircase planned in one go — a full world column and then some. */
    public static final int MAX_STEPS = 400;

    private StaircasePlan() {
    }

    /**
     * Treads climbing from {@code fromY} to {@code toY}, advancing one block per
     * step in the given cardinal direction.
     *
     * <p>The starting cell is not included: the citizen is already standing in
     * it. An empty list means there is nothing to climb.</p>
     *
     * @param dirX  -1, 0 or 1; exactly one of dirX/dirZ must be non-zero
     * @param dirZ  -1, 0 or 1
     */
    public static List<Step> upward(int fromX, int fromY, int fromZ, int toY,
                                    int dirX, int dirZ) {
        List<Step> steps = new ArrayList<>();
        if (toY <= fromY) return steps;
        int stepX = Integer.signum(dirX);
        int stepZ = Integer.signum(dirZ);
        // A staircase that does not advance is a ladder shaft the citizen
        // cannot climb. Fall back to a direction rather than cutting one.
        if (stepX == 0 && stepZ == 0) stepX = 1;
        if (stepX != 0 && stepZ != 0) stepZ = 0;   // cardinal only: no diagonal treads

        int climb = Math.min(toY - fromY, MAX_STEPS);
        for (int i = 1; i <= climb; i++) {
            steps.add(new Step(fromX + stepX * i, fromY + i, fromZ + stepZ * i));
        }
        return steps;
    }

    /**
     * The cardinal direction that points most directly from here to there.
     *
     * <p>Climbing out toward home means the citizen surfaces closer to the
     * colony than it started, which turns an escape into progress rather than
     * merely survival.</p>
     *
     * @return {dx, dz}, one of which is zero
     */
    public static int[] cardinalToward(int fromX, int fromZ, int toX, int toZ) {
        int dx = toX - fromX;
        int dz = toZ - fromZ;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return new int[]{dx >= 0 ? 1 : -1, 0};
        }
        return new int[]{0, dz >= 0 ? 1 : -1};
    }

    /**
     * How many blocks of material a staircase of this height removes.
     *
     * <p>Two cells per tread — feet and head — which is what a citizen needs to
     * walk up. Reported to the player so "digging out" has a visible size.</p>
     */
    public static int blocksToRemove(int climb) {
        return Math.max(0, Math.min(climb, MAX_STEPS)) * 2;
    }
}
