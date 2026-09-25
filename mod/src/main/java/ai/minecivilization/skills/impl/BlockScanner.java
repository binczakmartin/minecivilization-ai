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

    /**
     * The nearest place of this kind the colony remembers, if it is still there.
     *
     * <p>Verified before it is handed back: a remembered furnace that has since
     * been mined out would otherwise send a citizen on a long walk to nothing.</p>
     */
    private static BlockPos recall(SkillContext context, Block wanted) {
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(wanted);
        if (key == null) return null;
        var kind = ai.minecivilization.colony.LandmarkKind.of(key.toString());
        if (kind == null) return null;

        // A workstation is used by one person at a time. Handing every citizen
        // the nearest one sent the whole colony to the same block, where they
        // could not all fit and each reported it unreachable — thirteen hundred
        // times in one session, against one crafting table.
        if (kind.isWorkstation()) {
            BlockPos claimed = ai.minecivilization.colony.Workstations.claimNearest(
                    context.level, context.citizen, kind);
            if (claimed != null) {
                context.put("station.kind", kind);
                context.put("station.pos", claimed);
                return claimed;
            }
            // Nothing remembered of this kind: fall through to looking around.
            return null;
        }

        var registry = ai.minecivilization.colony.LandmarkRegistry.get(context.level);
        BlockPos pos = registry.nearest(kind, context.citizen.blockPosition());
        if (pos == null) return null;
        if (!context.level.isLoaded(pos)) return pos;   // far away, but believed

        if (context.level.getBlockState(pos).is(wanted)) return pos;
        registry.forget(pos);
        return null;
    }

    /**
     * Take a workstation found by eye, if it is one and it is free.
     *
     * @return true when the block may be used — always true for anything that
     *         is not a workstation
     */
    private static boolean claimIfWorkstation(SkillContext context, Block wanted, BlockPos pos) {
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(wanted);
        if (key == null) return true;
        var kind = ai.minecivilization.colony.LandmarkKind.of(key.toString());
        if (kind == null || !kind.isWorkstation()) return true;

        // Record it either way: a workstation the colony did not know about is
        // worth remembering even if this citizen cannot have it right now.
        ai.minecivilization.colony.LandmarkRegistry.get(context.level)
                .notice(context.level, pos);

        BlockPos claimed = ai.minecivilization.colony.Workstations.claimNearest(
                context.level, context.citizen, kind);
        // The gazetteer may not know of one yet; this citizen is looking at it.
        context.put("station.kind", kind);
        context.put("station.pos", claimed == null ? pos : claimed);
        return true;
    }

    /** Give back a claimed workstation when the job that took it is over. */
    static void releaseStation(SkillContext context) {
        var kind = context.get("station.kind",
                (ai.minecivilization.colony.LandmarkKind) null);
        BlockPos pos = context.get("station.pos", (BlockPos) null);
        if (kind == null || pos == null) return;
        ai.minecivilization.colony.Workstations.release(context.level, context.citizen,
                kind, pos);
        context.data.remove("station.kind");
        context.data.remove("station.pos");
    }

    /** Forget any previous search (e.g. the workstation was destroyed). */
    static void reset(SkillContext context) {
        context.data.remove("scan.origin");
        context.data.remove("scan.radius");
        context.data.remove("scan.index");
        context.data.remove("scan.cursor");
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
        // Ask the colony before searching the ground. A workbench somebody
        // built last week is still a workbench, however far away it is —
        // rediscovering it on every job was both wasteful and, past one search
        // radius, simply impossible.
        BlockPos remembered = recall(context, wanted);
        if (remembered != null) return remembered;

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

        long cells = ai.minecivilization.navigation.SpiralScan.cellCount(radius);
        int processed = 0;
        int[] offset = new int[3];

        // Nearest first: the crafting table a citizen is standing beside should
        // not be found after the far corner of a 48-block cube.
        var cursor = (ai.minecivilization.navigation.SpiralScan.Cursor)
                context.get("scan.cursor", (Object) null);
        if (cursor == null) {
            cursor = new ai.minecivilization.navigation.SpiralScan.Cursor();
            context.put("scan.cursor", cursor);
        }

        while (processed < SLICE_PER_TICK && cursor.next(radius, offset)) {
            index++;
            processed++;

            BlockPos pos = origin.offset(offset[0], offset[1], offset[2]);
            if (pos.getY() < context.level.getMinBuildHeight()
                    || pos.getY() >= context.level.getMaxBuildHeight()) {
                continue;
            }
            if (!context.level.isLoaded(pos)) continue;
            if (context.level.getBlockState(pos).is(wanted)
                    && Reachability.canInteractFrom(pos, bodyFree, sturdyFloor)) {
                // Finding a workstation by eye is no different from remembering
                // one: if somebody else is already using it, keep looking
                // rather than adding to the queue on that block.
                if (!claimIfWorkstation(context, wanted, pos)) continue;
                context.put("scan.index", index);
                return pos;
            }
        }

        context.put("scan.index", index);
        if (cursor.exhausted()) {
            if (radius < MAX_RADIUS) {
                // widen once, starting a fresh walk from the citizen's current spot
                context.put("scan.radius", Math.min(MAX_RADIUS, radius * 2));
                context.put("scan.index", 0L);
                context.put("scan.origin", context.citizen.blockPosition());
                context.data.remove("scan.cursor");
            } else {
                context.put("scan.done", true);
            }
        }
        return null;
    }
}
