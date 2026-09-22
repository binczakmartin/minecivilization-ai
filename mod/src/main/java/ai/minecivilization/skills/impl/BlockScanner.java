package ai.minecivilization.skills.impl;

import ai.minecivilization.skills.SkillContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Tick-sliced search for a single block type around the citizen.
 *
 * <p>Shared by workstation skills (furnace, crafting table). It scans an
 * expanding cube (24 → 48 blocks) a few thousand cells per tick, so a search
 * never hogs the server thread and only ever finds blocks the citizen could
 * plausibly see — no server omniscience.</p>
 *
 * <p>State lives in {@link SkillContext#data} under the {@code scan.*} keys:
 * <ul>
 *   <li>{@code scan.origin} — where the search started</li>
 *   <li>{@code scan.radius} — current half-size of the cube</li>
 *   <li>{@code scan.index} — cell cursor inside the cube</li>
 *   <li>{@code scan.done} — the last radius was exhausted: nothing exists</li>
 * </ul>
 * Call {@link #reset(SkillContext)} when the target may have moved/appeared.</p>
 */
final class BlockScanner {
    private static final int SLICE_PER_TICK = 3000;
    private static final int START_RADIUS = 24;
    private static final int MAX_RADIUS = 48;

    private BlockScanner() {
    }

    /** Forget any previous search (e.g. the workstation was destroyed). */
    static void reset(SkillContext context) {
        context.data.remove("scan.origin");
        context.data.remove("scan.radius");
        context.data.remove("scan.index");
        context.data.remove("scan.done");
    }

    static boolean done(SkillContext context) {
        return context.get("scan.done", false);
    }

    /**
     * Advance the search by at most {@link #SLICE_PER_TICK} cells.
     *
     * @return the position of the wanted block, or {@code null} — either the
     *         search is still running or {@link #done(SkillContext)} is true.
     */
    static BlockPos find(SkillContext context, Block wanted) {
        if (done(context)) return null;

        BlockPos origin = context.get("scan.origin", context.citizen.blockPosition());
        int radius = context.get("scan.radius", START_RADIUS);
        long index = ((Number) context.get("scan.index", 0L)).longValue();

        // Same reachability rules as FindBlockSkill: never pick a workstation
        // sealed inside a wall — the walk there would only end in failure.
        Predicate<BlockPos> bodyFree = p -> context.level.getBlockState(p)
                .getCollisionShape(context.level, p).isEmpty();
        Predicate<BlockPos> sturdyFloor = p -> {
            BlockState s = context.level.getBlockState(p);
            return !(s.getBlock() instanceof LeavesBlock)
                    && s.isFaceSturdy(context.level, p, Direction.UP);
        };

        int total = 2 * radius + 1;
        long cells = (long) total * total * total;
        int processed = 0;

        while (index < cells && processed < SLICE_PER_TICK) {
            int x = (int) (index % total);
            int y = (int) ((index / total) % total);
            int z = (int) (index / (total * (long) total));
            index++;
            processed++;

            BlockPos pos = origin.offset(x - radius, y - radius, z - radius);
            if (pos.getY() < context.level.getMinBuildHeight()
                    || pos.getY() >= context.level.getMaxBuildHeight()) {
                continue;
            }
            if (!context.level.isLoaded(pos)) continue;
            if (context.level.getBlockState(pos).is(wanted)
                    && Reachability.canInteractFrom(pos, bodyFree, sturdyFloor)) {
                context.put("scan.index", index);
                return pos;
            }
        }

        context.put("scan.index", index);
        if (index >= cells) {
            if (radius < MAX_RADIUS) {
                // widen once, starting a fresh ring from the citizen's current spot
                context.put("scan.radius", Math.min(MAX_RADIUS, radius * 2));
                context.put("scan.index", 0L);
                context.put("scan.origin", context.citizen.blockPosition());
            } else {
                context.put("scan.done", true);
            }
        }
        return null;
    }
}
