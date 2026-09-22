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

    @Override
    public void start(SkillContext context) {
        Block block = resolveBlock(context);
        int radius = Integer.parseInt(context.params.extra.getOrDefault("radius", "24"));
        context.put("block", block);
        context.put("radius", radius);
        context.put("index", 0);
        context.put("origin", context.citizen.blockPosition());
        context.put("maxAge", "true".equals(context.params.extra.get("maxAge")));
        context.put("checked", 0);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SkillResult tick(SkillContext context) {
        Block block = context.get("block", (Block) null);
        if (block == null) {
            context.fail(SkillFailure.notFound("no block id to search for"));
            return SkillResult.FAILED;
        }
        int radius = context.get("radius", 24);
        boolean maxAge = context.get("maxAge", false);
        BlockPos origin = context.get("origin", context.citizen.blockPosition());
        int total = (2 * radius + 1);
        int totalChecks = total * total * total;
        int i = context.get("index", 0);
        int processed = 0;
        int cx = origin.getX(), cy = origin.getY(), cz = origin.getZ();

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

        while (i < totalChecks && processed < SLICE_PER_TICK) {
            int x = i % total;
            int y = (i / total) % total;
            int z = i / (total * total);
            i++;
            processed++;
            BlockPos pos = new BlockPos(cx - radius + x, cy - radius + y, cz - radius + z);
            if (Math.abs(pos.getX() - cx) > radius || Math.abs(pos.getY() - cy) > radius
                    || Math.abs(pos.getZ() - cz) > radius) continue;
            if (pos.getY() < context.level.getMinBuildHeight()
                    || pos.getY() >= context.level.getMaxBuildHeight()) continue;
            if (!context.level.isLoaded(pos)) continue;
            BlockState state = context.level.getBlockState(pos);
            if (state.getBlock() != block) continue;
            if (maxAge) {
                if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) {
                    continue;
                }
            }
            if (!Reachability.canInteractFrom(pos, bodyFree, sturdyFloor)) {
                continue; // unreachable: keep scanning for one the citizen can stand by
            }
            context.put("index", i);
            context.params.position = new int[]{pos.getX(), pos.getY(), pos.getZ()};
            context.citizen.onResourceFound(block, pos);
            return SkillResult.COMPLETED;
        }
        context.put("index", i);
        if (i >= totalChecks) {
            context.fail(SkillFailure.notFound(
                    "No reachable " + ForgeRegistries.BLOCKS.getKey(block)
                            + " within " + radius + " blocks."));
            return SkillResult.FAILED;
        }
        return SkillResult.RUNNING;
    }

    @Override
    public void cancel(SkillContext context) {
    }

    @Override
    public String progressLabel(SkillContext context) {
        int i = context.get("index", 0);
        return "searching (" + i + " checked)";
    }
}
