package ai.minecivilization.mining;

import java.util.ArrayList;
import java.util.List;

/**
 * The plan of a colony mine: one entrance, one staircase, a landing at the
 * depth each ore is actually worth digging for.
 *
 * <p>Miners each wandering off to scratch their own hole find ore by luck and
 * never find the deep seams at all, because nobody digs far enough alone. A
 * shared shaft is the difference: it is dug once, everyone uses it, and it
 * reaches the depths where the ore actually is.</p>
 *
 * <p>The depths are the real ones. Minecraft's ore distribution peaks at
 * specific heights, and digging at the wrong level is most of why casual mining
 * feels unproductive: diamonds essentially do not exist above y=16, and iron
 * near the surface is a fraction of what it is at y=16.</p>
 *
 * <p>Pure integer geometry, unit tested.</p>
 */
public final class MineLayout {

    /** Blocks of horizontal travel per block of descent — a walkable stair. */
    public static final int RUN_PER_DROP = 1;
    /** Torches every this many steps down the stair. */
    public static final int TORCH_INTERVAL = 6;
    /** How far a landing reaches from the shaft before branch tunnels start. */
    public static final int LANDING_LENGTH = 5;

    /** One depth worth stopping at, and what is found there. */
    public record Level(int y, String ore, String label) {
    }

    /**
     * The depths a colony mine stops at, deepest last.
     *
     * <p>Chosen from where each ore actually peaks, not from where it is first
     * seen: mining iron at y=40 works, mining it at y=16 works far better.</p>
     */
    public static List<Level> levels() {
        return List.of(
                new Level(96, "minecraft:coal_ore", "Coal"),
                new Level(48, "minecraft:copper_ore", "Copper"),
                new Level(16, "minecraft:iron_ore", "Iron"),
                new Level(0, "minecraft:lapis_ore", "Lapis"),
                new Level(-16, "minecraft:gold_ore", "Gold"),
                new Level(-54, "minecraft:redstone_ore", "Redstone"),
                new Level(-59, "minecraft:diamond_ore", "Diamond"));
    }

    private MineLayout() {
    }

    /**
     * The levels worth digging to from a given surface height, deepest last.
     *
     * <p>A mine whose entrance is at y=70 has no business cutting a "coal
     * level" at y=96 — that is above its own front door.</p>
     */
    public static List<Level> levelsBelow(int surfaceY, int worldBottom) {
        List<Level> out = new ArrayList<>();
        for (Level level : levels()) {
            if (level.y() >= surfaceY) continue;      // above the entrance
            if (level.y() <= worldBottom + 1) continue;  // in the bedrock
            out.add(level);
        }
        return out;
    }

    /**
     * The staircase from the entrance down to a depth.
     *
     * <p>Each step drops one block and moves one along, which is a stair a
     * citizen can actually walk rather than a ladder shaft it would fall down.
     * The direction alternates every landing so the mine folds back on itself
     * instead of running a kilometre from the settlement.</p>
     *
     * @return the feet position of each step, in digging order
     */
    public static List<int[]> stairSteps(int entranceX, int entranceY, int entranceZ,
                                         int targetY, int legIndex) {
        List<int[]> steps = new ArrayList<>();
        if (targetY >= entranceY) return steps;

        // Alternate the run direction per leg: +x, +z, -x, -z.
        int[][] directions = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        int[] direction = directions[Math.floorMod(legIndex, directions.length)];

        int x = entranceX;
        int y = entranceY;
        int z = entranceZ;
        while (y > targetY) {
            y--;
            x += direction[0] * RUN_PER_DROP;
            z += direction[1] * RUN_PER_DROP;
            steps.add(new int[]{x, y, z});
        }
        return steps;
    }

    /** True when this step should carry a torch. */
    public static boolean isTorchStep(int stepIndex) {
        return stepIndex > 0 && stepIndex % TORCH_INTERVAL == 0;
    }

    /**
     * The landing at a level: a short corridor off the stair, where the sign
     * and the chest for that ore go.
     *
     * @return the feet positions of the landing floor, nearest the stair first
     */
    public static List<int[]> landing(int x, int y, int z, int legIndex) {
        List<int[]> cells = new ArrayList<>();
        // Run the landing across the stair's direction, so it does not collide
        // with the next leg going down.
        int[][] directions = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
        int[] direction = directions[Math.floorMod(legIndex, directions.length)];
        for (int step = 1; step <= LANDING_LENGTH; step++) {
            cells.add(new int[]{x + direction[0] * step, y, z + direction[1] * step});
        }
        return cells;
    }

    /** Where the sign and chest go on a landing: at its far end, out of the way. */
    public static int[] depotOf(List<int[]> landing) {
        return landing.isEmpty() ? null : landing.get(landing.size() - 1);
    }
}
