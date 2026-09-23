package ai.minecivilization.skills.impl;

import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Predicate;

/**
 * Incremental block search within a sight radius (default 24 blocks).
 * Tick-sliced so it never hogs the server thread. On success the found
 * position is written to context (params.position) for the next skill.
 *
 * <p>Only blocks within the citizen's sight radius are ever "known" — this is
 * how server omniscience is kept out of civilization knowledge.</p>
 */
public final class FindBlockSkill implements CitizenSkill {
    private static final int SLICE_PER_TICK = 3000;

    @Override
    public SkillType type() {
        return SkillType.FIND_BLOCK;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return resolveBlock(context) != null;
    }

    private Block resolveBlock(SkillContext context) {
        String blockId = context.params.block;
        if (blockId == null && context.params.resource != null) {
            var item = ai.minecivilization.inventory.CitizenInventory.itemById(context.params.resource);
            Block byItem = Block.byItem(item);
            blockId = byItem == null ? null
                    : ForgeRegistries.BLOCKS.getKey(byItem).toString();
        }
        if (blockId == null) return null;
        return ForgeRegistries.BLOCKS.getValue(net.minecraft.resources.ResourceLocation.parse(blockId));
    }

    /**
     * Every block that would satisfy this search, not just the one named.
     *
     * <p>A citizen sent for oak in a spruce forest used to scan the whole
     * radius, fail, widen it, fail again — standing still for a quarter of an
     * hour. The request was never really for oak; it was for wood.</p>
     */
    private java.util.Set<Block> acceptableBlocks(SkillContext context) {
        java.util.Set<Block> blocks = new java.util.LinkedHashSet<>();
        Block named = resolveBlock(context);
        if (named != null) blocks.add(named);

        String wanted = context.params.resource != null
                ? context.params.resource : context.params.block;
        for (String id : ai.minecivilization.forestry.ResourceFamily.sourceBlocks(wanted)) {
            Block substitute = ForgeRegistries.BLOCKS.getValue(
                    net.minecraft.resources.ResourceLocation.parse(id));
            if (substitute != null && substitute != Blocks.AIR) blocks.add(substitute);
        }
        return blocks;
    }

    @Override
    public void start(SkillContext context) {
        Block block = resolveBlock(context);
        int radius = Integer.parseInt(context.params.extra.getOrDefault("radius", "24"));
        context.put("accepted", acceptableBlocks(context));
        context.put("block", block);
        context.put("radius", radius);
        context.put("cursor", new ai.minecivilization.navigation.SpiralScan.Cursor());
        context.put("origin", context.citizen.blockPosition());
        context.put("maxAge", "true".equals(context.params.extra.get("maxAge")));
        context.put("checked", 0);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SkillResult tick(SkillContext context) {
        Block block = context.get("block", (Block) null);
        java.util.Set<Block> accepted = context.get("accepted", (java.util.Set<Block>) null);
        if (block == null && (accepted == null || accepted.isEmpty())) {
            context.fail(SkillFailure.notFound("no block id to search for"));
            return SkillResult.FAILED;
        }
        if (accepted == null || accepted.isEmpty()) {
            accepted = java.util.Set.of(block);
        }
        int radius = context.get("radius", 24);
        boolean maxAge = context.get("maxAge", false);
        BlockPos origin = context.get("origin", context.citizen.blockPosition());
        var cursor = context.get("cursor",
                (ai.minecivilization.navigation.SpiralScan.Cursor) null);
        if (cursor == null) {
            cursor = new ai.minecivilization.navigation.SpiralScan.Cursor();
            context.put("cursor", cursor);
        }
        int processed = 0;
        int[] offset = new int[3];

        // Reachability filters (pure rules, injected world queries): only pick a
        // block the citizen could actually stand next to — no canopy logs, no
        // buried stone. That was the "stuck in the trees" failure loop.
        Predicate<BlockPos> bodyFree = p -> context.level.getBlockState(p)
                .getCollisionShape(context.level, p).isEmpty();
        Predicate<BlockPos> sturdyFloor = p -> {
            BlockState s = context.level.getBlockState(p);
            return !(s.getBlock() instanceof LeavesBlock)
                    && s.isFaceSturdy(context.level, p, Direction.UP);
        };

        // Nearest cells first: a raster scan starts in the far bottom corner, so
        // a tree five blocks away used to wait behind half a million empty cells.
        while (processed < SLICE_PER_TICK && cursor.next(radius, offset)) {
            processed++;
            BlockPos pos = origin.offset(offset[0], offset[1], offset[2]);
            if (pos.getY() < context.level.getMinBuildHeight()
                    || pos.getY() >= context.level.getMaxBuildHeight()) continue;
            if (!context.level.isLoaded(pos)) continue;
            BlockState state = context.level.getBlockState(pos);
            if (!accepted.contains(state.getBlock())) continue;
            if (state.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                    && !context.level.getBlockState(pos.above()).isAir()) continue;
            if (maxAge) {
                if (!ai.minecivilization.farming.Crops.ripe(state, context.level, pos)) {
                    continue;
                }
            }
            if (!Reachability.canInteractFrom(pos, bodyFree, sturdyFloor)) {
                continue; // unreachable: keep scanning for one the citizen can stand by
            }
            context.params.position = new int[]{pos.getX(), pos.getY(), pos.getZ()};
            // Tell the rest of the task what was actually found: a spruce trunk
            // must be felled as spruce, not as the oak that was asked for.
            var foundKey = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (foundKey != null) context.params.block = foundKey.toString();
            context.citizen.onResourceFound(state.getBlock(), pos);
            return SkillResult.COMPLETED;
        }
        if (cursor.exhausted()) {
            context.fail(SkillFailure.notFound(
                    "No reachable " + ForgeRegistries.BLOCKS.getKey(block)
                            + " (or any substitute) within " + radius + " blocks."));
            return SkillResult.FAILED;
        }
        return SkillResult.RUNNING;
    }

    @Override
    public void cancel(SkillContext context) {
    }

    @Override
    public String progressLabel(SkillContext context) {
        var cursor = context.get("cursor",
                (ai.minecivilization.navigation.SpiralScan.Cursor) null);
        int radius = context.get("radius", 24);
        long checked = cursor == null ? 0 : cursor.visited(radius);
        long total = ai.minecivilization.navigation.SpiralScan.cellCount(radius);
        return "searching (" + checked + " of " + total + ")";
    }
}
