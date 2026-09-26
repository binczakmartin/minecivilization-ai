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
    /**
     * Block lookups per tick.
     *
     * <p>Raised a long way from three thousand. Reading a block state out of a
     * loaded chunk costs almost nothing; the old budget made a wide search
     * take a quarter of a minute of standing still, and measurement showed a
     * third of all colony time going into searching alone.</p>
     */
    private static final int SLICE_PER_TICK = 16000;
    /**
     * How far above and below the citizen a search looks.
     *
     * <p>Almost everything a citizen is sent for sits in a thin band around
     * the surface it is standing on: trees, crops, soil, exposed stone. The
     * search was a full cube, so at its widest it examined nine hundred
     * thousand cells to find a tree twenty blocks away — and ninety per cent
     * of those cells were sky or deep rock. Ore is the exception, and a miner
     * reaches it by digging down rather than by searching for it from the
     * surface.</p>
     */
    private static final int VERTICAL_BAND = 12;

    /**
     * How long a citizen holds the resource it is walking to.
     *
     * <p>Long enough to get there and work it; short enough that a worker
     * which dies or is called away does not reserve a tree for the afternoon.</p>
     */
    private static final int TARGET_CLAIM_TICKS = 400;
    /**
     * Size of the patch one citizen reserves, in blocks.
     *
     * <p>Claiming a single block would reserve one log of a tree and leave the
     * trunk beside it free for somebody else, which is the same collision one
     * block over. A small patch reserves the whole tree, or the corner of the
     * seam, which is what a job actually is.</p>
     */
    private static final int CLAIM_PATCH = 3;

    @Override
    public SkillType type() {
        return SkillType.FIND_BLOCK;
    }

    /**
     * Reserve a resource block for this citizen.
     *
     * <p>A whole tree is one job, so claiming the trunk block a citizen walks
     * to is enough to keep two lumberjacks off the same oak.</p>
     */
    private static boolean claimTarget(SkillContext context, BlockPos pos) {
        String owner = String.valueOf(context.citizen.getIdentity().citizenId);
        BlockPos patch = patchOf(pos);
        boolean claimed = ai.minecivilization.construction.WorkClaimStore.claim(
                context.level, owner, patch, "resource",
                context.level.getGameTime(), TARGET_CLAIM_TICKS);
        if (claimed) context.put("resource.claim", patch);
        return claimed;
    }

    /** The patch a block belongs to — one claim covers a whole tree. */
    private static BlockPos patchOf(BlockPos pos) {
        return new BlockPos(Math.floorDiv(pos.getX(), CLAIM_PATCH),
                0, Math.floorDiv(pos.getZ(), CLAIM_PATCH));
    }

    /** Release the resource when the search's owner is done with it. */
    static void releaseTarget(SkillContext context) {
        BlockPos pos = context.get("resource.claim", (BlockPos) null);
        if (pos == null) return;
        ai.minecivilization.construction.WorkClaimStore.release(context.level,
                String.valueOf(context.citizen.getIdentity().citizenId), pos, "resource");
        context.data.remove("resource.claim");
    }

    @Override
    public boolean canStart(SkillContext context) {
        return resolveBlock(context) != null;
    }

    private Block resolveBlock(SkillContext context) {
        String blockId = context.params.block;
        if ((blockId == null || blockId.equals("minecraft:air")) && context.params.resource != null) {
            var sources = ai.minecivilization.forestry.ResourceFamily.sourceBlocks(context.params.resource);
            if (!sources.isEmpty()) blockId = sources.getFirst();
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

        // A builder needs the exact block its blueprint names: an acacia log
        // does not stand in for an oak post.
        if ("true".equals(context.params.extra.get("exact"))) return blocks;
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
            // Cells outside the band cost a step of cheap arithmetic, not a
            // block lookup, so they must not spend the tick's budget either.
            if (Math.abs(offset[1]) > VERTICAL_BAND) continue;
            processed++;
            BlockPos pos = origin.offset(offset[0], offset[1], offset[2]);
            if (pos.getY() < context.level.getMinBuildHeight()
                    || pos.getY() >= context.level.getMaxBuildHeight()) continue;
            if (!context.level.isLoaded(pos)) continue;
            BlockState state = context.level.getBlockState(pos);
            if (!accepted.contains(state.getBlock())) continue;
            if (ai.minecivilization.navigation.UnreachableMemory.isUnreachable(pos,
                    context.level.getGameTime())) continue;
            // A log with neither ground nor more trunk under it is a branch up
            // in the canopy — acacias are mostly branch. Aiming at one sent
            // citizens pillaring into the treetops on three blocks of dirt;
            // the trunk it grows from is a walk away and fells the lot.
            if (state.is(net.minecraft.tags.BlockTags.LOGS)) {
                BlockState under = context.level.getBlockState(pos.below());
                if (!under.is(net.minecraft.tags.BlockTags.LOGS)
                        && !under.is(net.minecraft.tags.BlockTags.DIRT)
                        && (under.is(net.minecraft.tags.BlockTags.LEAVES)
                            || !under.isFaceSturdy(context.level, pos.below(), Direction.UP))) continue;
            }
            if (!new ai.minecivilization.navigation.LevelBlockView(context.level).diggable(pos)) continue;
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
            if (!context.level.getEntities(context.citizen,
                    new net.minecraft.world.phys.AABB(pos)).isEmpty()) continue;
            // Somebody else's tree is not this citizen's tree. Without a claim
            // the nearest block is the nearest block for everybody, so the
            // whole shift converges on one trunk, jams each other out of arm's
            // reach and reports it unreachable. Keep scanning for one that is
            // genuinely free — there is always another tree.
            if (!claimTarget(context, pos)) continue;
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
        // Searching is work, and it is done standing perfectly still. Without
        // saying so, a citizen part-way through a wide search looked identical
        // to one that had simply stopped — and had its job taken away for
        // being motionless. The cursor running out still bounds it, so this
        // cannot hide a genuine stall.
        if (processed > 0) context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    @Override
    public void cancel(SkillContext context) {
        // Give the tree back. The claim would lapse on its own, but a patch
        // nobody is working is a patch the next citizen should be allowed to
        // take — especially in a camp with only a handful of trees.
        releaseTarget(context);
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
