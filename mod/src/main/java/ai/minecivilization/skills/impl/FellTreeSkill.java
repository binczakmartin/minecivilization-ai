package ai.minecivilization.skills.impl;

import java.util.List;

import ai.minecivilization.citizen.CitizenTaskParams;
import ai.minecivilization.colony.Zone;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.colony.ZoneType;
import ai.minecivilization.forestry.TreeShape;
import ai.minecivilization.forestry.TreeSpecies;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillRegistry;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * FELL_TREE — take the whole tree, then put one back.
 *
 * <p>Mining a single log leaves a stump under a floating canopy, and the
 * lumberjack walks off to do it again somewhere else. A forest treated that way
 * fills with debris and never regrows. Felling means the entire connected
 * trunk, worked from the foot upward, and then a sapling planted on the stump
 * so the colony's wood supply is renewable rather than mined.</p>
 *
 * <p>Tall trees are not reachable from the ground, so the skill leans on
 * {@link TraverseSkill}: when the next log is out of arm's reach the citizen
 * pillars up to it with its own blocks, exactly as it would to cross a ravine.
 * Branches that cannot be reached at all are left rather than failing the job —
 * a mostly-felled tree is still a felled tree.</p>
 *
 * <p>Each species felled is reported to the colony's managed forest, which is
 * how an expedition that finds spruce in the hills ends up with spruce growing
 * at home.</p>
 */
public final class FellTreeSkill implements CitizenSkill {

    /** Arm's reach for mining, squared — matches MINE_BLOCK. */
    private static final double REACH_SQR = 20.0;
    /** Branches abandoned before the job is called done anyway. */
    private static final int MAX_SKIPPED = 6;

    private List<BlockPos> trunk;
    private BlockPos base;
    private String logId;
    private int index;
    private int skipped;
    private int scaffoldDug;
    private int teardownWalks;
    private boolean replantAttempted;

    private CitizenSkill sub;
    private SkillContext subContext;
    private boolean subIsTraverse;

    @Override
    public SkillType type() {
        return SkillType.FELL_TREE;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.position != null;
    }

    @Override
    public void start(SkillContext context) {
        trunk = null;
        base = null;
        logId = null;
        index = 0;
        skipped = 0;
        scaffoldDug = 0;
        teardownWalks = 0;
        replantAttempted = false;
        context.citizen.forgetAllScaffold();   // only tidy up this job's own blocks
        sub = null;
        subContext = null;
    }

    @Override
    public SkillResult tick(SkillContext context) {
        if (trunk == null) {
            SkillResult settled = survey(context);
            if (settled != null) return settled;
        }

        // Skip anything already gone — another citizen may be felling with us.
        while (index < trunk.size() && context.level.getBlockState(trunk.get(index)).isAir()) {
            index++;
        }
        if (index >= trunk.size()) {
            // The tree is down. Take the climbing blocks back before replanting:
            // a felled forest dotted with abandoned dirt towers is worse to look
            // at than the stumps whole-tree felling was meant to remove.
            SkillResult teardown = clearScaffold(context);
            if (teardown != null) return teardown;
            return finish(context);
        }

        BlockPos target = trunk.get(index);
        boolean inReach = context.citizen.distanceToSqr(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) <= REACH_SQR;

        // Climbing costs blocks. A lumberjack that set out empty-handed simply
        // abandoned every branch above head height, leaving the stump-and-canopy
        // mess whole-tree felling exists to prevent — so it digs up the dirt it
        // needs, the way a player would.
        if (!inReach && sub == null && !hasScaffold(context)) {
            SkillResult digging = digScaffold(context);
            if (digging != null) return digging;
        }

        if (sub == null) {
            beginSub(context, target, inReach);
        }

        SkillResult result = sub.tick(subContext);
        if (result == SkillResult.RUNNING) return SkillResult.RUNNING;

        SkillFailure failure = subContext.failure;
        boolean wasTraverse = subIsTraverse;
        endSub(context);

        if (result == SkillResult.COMPLETED) {
            if (!wasTraverse) index++;       // the log is down; the next one is up
            context.startGameTime = context.level.getGameTime();
            return SkillResult.RUNNING;
        }

        // A branch we cannot reach or break is left standing rather than
        // failing the whole job.
        if (failure != null && "BLOCK_ALREADY_MINED".equals(failure.code)) {
            index++;
            return SkillResult.RUNNING;
        }
        index++;
        if (++skipped > MAX_SKIPPED) {
            return finish(context);
        }
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ survey

    /** @return a terminal result, or null once the tree is mapped. */
    private SkillResult survey(SkillContext context) {
        int[] p = context.params.position;
        if (p == null) {
            context.fail(new SkillFailure("INVALID_TASK", "FELL_TREE without position", false));
            return SkillResult.FAILED;
        }
        BlockPos start = new BlockPos(p[0], p[1], p[2]);
        BlockState state = context.level.getBlockState(start);
        if (!state.is(BlockTags.LOGS)) {
            context.fail(new SkillFailure("BLOCK_ALREADY_MINED",
                    "no tree standing at the target", true));
            return SkillResult.FAILED;
        }
        logId = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                .getKey(state.getBlock()).toString();

        trunk = TreeShape.collect(start,
                pos -> context.level.getBlockState(pos).is(BlockTags.LOGS),
                pos -> context.level.getBlockState(pos).is(BlockTags.LEAVES));
        if (trunk.isEmpty()) {
            context.fail(new SkillFailure("BLOCK_ALREADY_MINED",
                    "the tree is already down", true));
            return SkillResult.FAILED;
        }
        base = trunk.get(0);
        index = 0;
        context.startGameTime = context.level.getGameTime();
        return null;
    }

    // ------------------------------------------------------------------ sub-skills

    private void beginSub(SkillContext context, BlockPos target, boolean inReach) {
        CitizenTaskParams params = new CitizenTaskParams();
        params.position = new int[]{target.getX(), target.getY(), target.getZ()};
        subIsTraverse = !inReach;
        if (subIsTraverse) {
            // Climb to the branch the same way a citizen crosses a ravine.
            params.extra.put("traverse.arrival", "2");
        }
        subContext = new SkillContext(context.citizen, context.level, context.navigator, params);
        subContext.timeoutTicks = context.timeoutTicks;
        subContext.startGameTime = context.level.getGameTime();
        sub = SkillRegistry.create(subIsTraverse ? SkillType.TRAVERSE : SkillType.MINE_BLOCK);
        sub.start(subContext);
    }

    /**
     * Mine back the blocks placed to climb with, top down.
     *
     * <p>The material returns to the citizen's inventory, so a pillar costs
     * nothing over a whole job — it is borrowed, not spent. Anything out of
     * reach or already gone is simply forgotten.</p>
     *
     * @return a result while the work continues, or null once the site is clear
     */
    private SkillResult clearScaffold(SkillContext context) {
        for (BlockPos pos : context.citizen.scaffoldPlaced()) {
            BlockState state = context.level.getBlockState(pos);
            if (state.isAir() || trunk.contains(pos)) {
                context.citizen.forgetScaffold(pos);
                continue;
            }
            double distSqr = context.citizen.distanceToSqr(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distSqr > REACH_SQR) {
                // Standing on the very block we are about to take is fine —
                // the citizen drops onto the next one down.
                if (teardownWalks++ > MAX_TEARDOWN_WALKS) {
                    context.citizen.forgetScaffold(pos);
                    continue;
                }
                context.navigator.moveTo(pos, 1.0);
                context.navigator.tick();
                if (context.navigator.hasFailed()) {
                    context.navigator.stop();
                    context.citizen.forgetScaffold(pos);
                }
                return SkillResult.RUNNING;
            }
            context.level.destroyBlock(pos, true, context.citizen);
            context.citizen.forgetScaffold(pos);
            collectNearbyDrops(context);
            context.startGameTime = context.level.getGameTime();
            return SkillResult.RUNNING;
        }
        return null;
    }

    /** Walks spent tidying up before the rest is written off. */
    private static final int MAX_TEARDOWN_WALKS = 12;

    /** Blocks a citizen needs in hand before it can pillar up to a branch. */
    private static final int SCAFFOLD_WANTED = 6;

    private static boolean hasScaffold(SkillContext context) {
        return ai.minecivilization.navigation.ScaffoldMaterial
                .available(context.citizen.getInventory()) >= 2;
    }

    /**
     * Dig up a few blocks of ground to pillar with.
     *
     * <p>Looks for plain earth underfoot and nearby — never the tree, never
     * anything the settlement built. Returns null once enough is in hand, so
     * the caller carries straight on with the climb.</p>
     */
    private SkillResult digScaffold(SkillContext context) {
        if (scaffoldDug >= SCAFFOLD_WANTED) {
            // Dug all we said we would and still have nothing usable: the
            // ground here is not diggable. Fell what can be reached instead.
            return null;
        }
        BlockPos ground = findDiggableGround(context);
        if (ground == null) {
            scaffoldDug = SCAFFOLD_WANTED;   // stop trying
            return null;
        }

        context.level.destroyBlock(ground, true, context.citizen);
        scaffoldDug++;
        collectNearbyDrops(context);
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    /** Plain earth within arm's reach that is not part of the tree or the town. */
    private BlockPos findDiggableGround(SkillContext context) {
        BlockPos feet = context.citizen.blockPosition();
        for (int dy = -1; dy >= -2; dy--) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (dx == 0 && dz == 0 && dy == -1) continue;  // not the floor we stand on
                    if (trunk != null && trunk.contains(pos)) continue;
                    BlockState state = context.level.getBlockState(pos);
                    if (!state.is(BlockTags.DIRT) && !state.is(BlockTags.SAND)) continue;
                    if (state.is(net.minecraft.world.level.block.Blocks.FARMLAND)) continue;
                    return pos;
                }
            }
        }
        return null;
    }

    private void collectNearbyDrops(SkillContext context) {
        var inventory = context.citizen.getInventory();
        var box = context.citizen.getBoundingBox().inflate(4.0);
        for (var item : context.level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, box)) {
            if (!item.isAlive() || item.getItem().isEmpty()) continue;
            int leftover = inventory.insert(item.getItem().copy());
            if (leftover <= 0) {
                item.discard();
            } else {
                var stack = item.getItem();
                stack.setCount(leftover);
                item.setItem(stack);
            }
        }
    }

    private void endSub(SkillContext context) {
        if (sub != null && subContext != null) {
            sub.cancel(subContext);
        }
        sub = null;
        subContext = null;
        subIsTraverse = false;
    }

    // ------------------------------------------------------------------ replanting

    /**
     * Put a sapling back on the stump and tell the colony which species this
     * was. Both are best-effort: a lumberjack with no saplings has still done
     * its job, and the drops from the canopy usually supply the next one.
     */
    private SkillResult finish(SkillContext context) {
        if (!replantAttempted) {
            replantAttempted = true;
            replant(context);
            rememberSpecies(context);
        }
        return SkillResult.COMPLETED;
    }

    private void replant(SkillContext context) {
        if (base == null || logId == null) return;
        String sapling = TreeSpecies.saplingFor(logId);
        if (sapling == null) return;

        CitizenInventory inventory = context.citizen.getInventory();
        if (!inventory.containsAtLeast(sapling, 1)) return;
        if (!context.level.getBlockState(base).canBeReplaced()) return;

        BlockState soil = context.level.getBlockState(base.below());
        if (!soil.is(BlockTags.DIRT)) return;

        BlockState saplingState = ai.minecivilization.construction.ConstructionManager
                .parseState(context.level, sapling);
        if (saplingState == null || !saplingState.canSurvive(context.level, base)) return;

        inventory.extract(sapling, 1);
        context.level.setBlock(base, saplingState, 3);
        context.citizen.getSkills().addXp("farming", 0.05f);
        context.citizen.onBlockPlaced(sapling);
    }

    /**
     * Add this species to the colony's managed forest. Expeditions are how a
     * plantation becomes mixed: whatever a citizen fells abroad, it can grow
     * at home.
     */
    private void rememberSpecies(SkillContext context) {
        String sapling = TreeSpecies.saplingFor(logId);
        if (sapling == null) return;
        ZoneManager zones = ZoneManager.get(context.level);
        boolean changed = false;
        for (Zone zone : zones.byType(ZoneType.FOREST)) {
            changed |= zone.recordSpecies(sapling);
        }
        if (changed) {
            zones.touch();
            // Worth telling the player about: the colony's palette just widened.
            ai.minecivilization.colony.ColonyNotifier.speciesDiscovered(context.level, sapling);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void cancel(SkillContext context) {
        endSub(context);
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        if (trunk == null) return "sizing up the tree";
        return "felling " + Math.min(index + 1, trunk.size()) + "/" + trunk.size();
    }
}
