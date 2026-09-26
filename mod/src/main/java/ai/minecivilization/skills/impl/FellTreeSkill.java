package ai.minecivilization.skills.impl;

import java.util.List;

import ai.minecivilization.citizen.CitizenTaskParams;
import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.colony.Zone;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.colony.ZoneType;
import ai.minecivilization.forestry.TreeShape;
import ai.minecivilization.forestry.TreeSpecies;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.navigation.PlacementSafety;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillRegistry;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

    /** Felling traces, behind {@code /mciv debug on}. */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Arm's reach for mining, squared — matches MINE_BLOCK. */
    private static final double REACH_SQR = 20.0;
    /**
     * Retry passes over anything that could not be taken first time.
     *
     * <p>Three, then the job ends regardless: a lumberjack must not spend all
     * afternoon on one unreachable branch, but it must also not walk away from
     * the first one it cannot climb to.</p>
     */
    private static final int MAX_SWEEPS = 3;
    /** Saplings put back for each tree taken. */
    private static final int REPLANT_TARGET = 2;
    /**
     * Leaves cleared by hand before the rest is left to decay.
     *
     * <p>Enough to yield the saplings the replanting needs, and no more. Every
     * log of the tree is taken — that is what leaves no trace, and it is what
     * starts vanilla decay on the remaining foliage. Clearing the rest by hand
     * is a courtesy that cost one citizen four minutes on a single acacia.</p>
     */
    private static final int CANOPY_BUDGET = 24;
    /** Leaves handed to vanilla decay when the trunk comes down. */
    private static final int MAX_DECAY_SCHEDULED = 512;
    /** Blocks either side the trunk may be while still worth climbing to. */
    private static final int CLIMB_REACH = 3;
    /** Pillar blocks placed for one tree before the climb is given up on. */
    private static final int MAX_CLIMB = 24;

    private int leavesTaken;
    /** When this tree was started, for its time budget. */
    private long treeStartedAt;
    /** Longest one tree may take (90 s) before the job settles for what is down. */
    private static final int TREE_BUDGET_TICKS = 1800;
    private int climbed;
    /** True while the running sub-skill is a pillar rather than a mine. */
    private boolean climbing;

    private List<BlockPos> trunk;
    private BlockPos base;
    private String logId;
    private int index;
    private int skipped;
    /** How many of the work list are logs, for the progress label. */
    private int logCount;
    /** Cells that could not be taken on this pass; retried before giving up. */
    private final List<BlockPos> deferred = new java.util.ArrayList<>();
    /** Sweeps made looking for anything still standing. */
    private int sweeps;
    /** The tree's bounding box, so a final sweep knows where to look. */
    private BlockPos extentMin;
    private BlockPos extentMax;
    private int scaffoldDug;
    private int teardownWalks;
    private boolean replantAttempted;
    private boolean cleanupAbandoned;

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
        logCount = 0;
        leavesTaken = 0;
        climbed = 0;
        climbing = false;
        deferred.clear();
        sweeps = 0;
        extentMin = null;
        extentMax = null;
        scaffoldDug = 0;
        teardownWalks = 0;
        replantAttempted = false;
        cleanupAbandoned = false;
        treeStartedAt = context.level.getGameTime();
        // Keep the ledger of earlier temporary bridges/pillars.  It is not
        // route-scoped yet, and forgetting it here would make cleanup silently
        // lose ownership of blocks that are still standing.
        context.citizen.setBracedPlacement(true);
        sub = null;
        subContext = null;
    }

    @Override
    public SkillResult tick(SkillContext context) {
        if (sub == null) context.citizen.clearWorkAnimation();
        if (trunk == null) {
            SkillResult settled = survey(context);
            if (settled != null) return settled;
        }

        // One tree, one budget. A savanna acacia cluster is ninety-odd blocks of
        // diagonal branch, and pillaring up to every one of them kept a
        // lumberjack on a single tree for many minutes. A player cuts what it
        // can reach and moves on: take what is down, let the rest decay, plant.
        if (sub == null && context.level.getGameTime() - treeStartedAt > TREE_BUDGET_TICKS
                && index < trunk.size()) {
            index = trunk.size();
            deferred.clear();
            sweeps = MAX_SWEEPS;
        }

        // Skip anything already gone — another citizen may be felling with us.
        while (index < trunk.size() && context.level.getBlockState(trunk.get(index)).isAir()) {
            index++;
        }
        if (index >= trunk.size()) {
            // Anything skipped gets another go with fresh scaffolding before
            // the job is called done. A branch left hanging is exactly the
            // "trace on the map" whole-tree felling exists to prevent.
            if (!deferred.isEmpty() && sweeps < MAX_SWEEPS) {
                // Only logs are worth another pass. A leaf left standing is
                // removed by decay the moment the last log goes, so chasing it
                // up a tree that is no longer there buys nothing.
                List<BlockPos> retry = new java.util.ArrayList<>();
                for (BlockPos pos : deferred) {
                    if (isLog(context, pos)) retry.add(pos);
                }
                deferred.clear();
                if (!retry.isEmpty()) {
                    sweeps++;
                    trunk = retry;
                    index = 0;
                    skipped = 0;
                    context.startGameTime = context.level.getGameTime();
                    return SkillResult.RUNNING;
                }
            }
            // Then look at the ground truth: whatever the plan said, is there
            // still a log of this tree standing? Floating branches usually come
            // from a neighbour felling into the same canopy, so the only
            // reliable check is the world itself.
            if (sweeps < MAX_SWEEPS) {
                List<BlockPos> leftovers = standingRemains(context);
                if (!leftovers.isEmpty()) {
                    sweeps++;
                    trunk = leftovers;
                    index = 0;
                    skipped = 0;
                    context.startGameTime = context.level.getGameTime();
                    return SkillResult.RUNNING;
                }
            }
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

        // Leaves are taken within reach only, and only up to a budget. Every
        // log is felled regardless — that is what removes the tree and what
        // sets vanilla decay going on whatever foliage is left. Clearing the
        // rest by hand is a courtesy, and an expensive one.
        if (!isLog(context, target)) {
            if (!inReach || leavesTaken >= CANOPY_BUDGET) {
                index++;
                return SkillResult.RUNNING;
            }
        }

        // Climbing costs blocks. A lumberjack that set out empty-handed simply
        // abandoned every branch above head height, leaving the stump-and-canopy
        // mess whole-tree felling exists to prevent — so it digs up the dirt it
        // needs, the way a player would.
        // Keep enough blocks to climb with. A spruce is twenty logs tall and a
        // budget of six left the citizen stranded halfway up its own pillar
        // with the rest of the trunk still standing.
        if (!inReach && sub == null && !hasScaffold(context)) {
            SkillResult digging = digScaffold(context);
            if (digging != null) return digging;
        }

        // A branch overhead is climbed to, not navigated to. Handing a
        // mid-air target to the route planner produced a plan whose steps sit
        // in the air the pillar has not been built yet — so the walk between
        // steps failed, the traverse failed, and the citizen burned its whole
        // timeout on one branch. Every tree in play stalled at the fifth log,
        // which is exactly where the trunk passes out of arm's reach.
        //
        // Pillaring straight up under the trunk is what a player does, needs
        // no route at all, and cannot fail for any reason but running out of
        // blocks.
        if (!inReach && sub == null) {
            boolean overhead = isOverhead(context, target);
            SkillResult climbedNow = overhead ? climbOneBlock(context) : null;
            if (ai.minecivilization.config.ModConfig.debug()) {
                LOGGER.info("[ClimbTrace] {} at={} target={} overhead={} climbed={} "
                                + "scaffold={} material={} canRaise={}",
                        context.citizen.getIdentity().name, context.citizen.blockPosition(),
                        target, overhead, climbedNow != null, climbed,
                        climbMaterial(context),
                        ai.minecivilization.navigation.PlacementSafety.canRaiseOne(
                                context.level, context.citizen,
                                context.citizen.blockPosition()));
            }
            if (climbedNow != null) return climbedNow;
        }

        if (sub == null) {
            beginSub(context, target, inReach);
        }

        SkillResult result = sub.tick(subContext);
        if (result == SkillResult.RUNNING) return SkillResult.RUNNING;

        SkillFailure failure = subContext.failure;
        boolean wasTraverse = subIsTraverse;
        boolean wasClimbing = climbing;
        endSub(context);

        if (result == SkillResult.COMPLETED) {
            if (wasClimbing) {
                // One block taller; the same log is now closer to reach.
                context.startGameTime = context.level.getGameTime();
                return SkillResult.RUNNING;
            }
            if (!wasTraverse) {
                if (index < trunk.size() && index >= logCount) leavesTaken++;
                index++;                     // the log is down; the next one is up
            }
            context.startGameTime = context.level.getGameTime();
            return SkillResult.RUNNING;
        }

        // A branch we cannot reach or break is left standing rather than
        // failing the whole job.
        if (failure != null && "BLOCK_ALREADY_MINED".equals(failure.code)) {
            index++;
            return SkillResult.RUNNING;
        }
        if (ai.minecivilization.config.ModConfig.debug()) {
            LOGGER.info(
                    "[FellTrace] {} idx={}/{} logs={} target={} block={} inReach={} traverse={} scaffold={} fail={}",
                    context.citizen.getIdentity().name, index, trunk.size(), logCount,
                    trunk.get(index),
                    context.level.getBlockState(trunk.get(index)), inReach, wasTraverse,
                    ai.minecivilization.navigation.ScaffoldMaterial.availableAny(
                            context.citizen.getInventory()), failure);
        }
        // Could not take it this time. Put it aside for the retry pass instead
        // of walking away: abandoning after six was what left branches hanging
        // in mid-air over a felled forest.
        deferred.add(trunk.get(index));
        index++;
        skipped++;
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    /**
     * Anything of this tree still standing, straight from the world.
     *
     * <p>The work list is a plan made at the start; the world is what a player
     * actually sees. Re-reading it catches logs that grew into the job from a
     * neighbouring canopy, cells another citizen replaced, and anything the
     * plan simply got wrong.</p>
     */
    /**
     * True when the target is above the citizen and close enough overhead that
     * simply standing taller would bring it into reach.
     */
    private static boolean isOverhead(SkillContext context, BlockPos target) {
        BlockPos at = context.citizen.blockPosition();
        int rise = target.getY() - at.getY();
        if (rise < 2) return false;
        int dx = Math.abs(target.getX() - at.getX());
        int dz = Math.abs(target.getZ() - at.getZ());
        return Math.max(dx, dz) <= CLIMB_REACH;
    }

    /**
     * Put one block under our own feet and stand on it.
     *
     * @return a result while climbing, or null when this citizen cannot climb
     *         here and the caller should fall back to ordinary navigation
     */
    private SkillResult climbOneBlock(SkillContext context) {
        if (climbed >= MAX_CLIMB) return null;

        String material = climbMaterial(context);
        if (material == null) return null;

        BlockPos feet = context.citizen.blockPosition();
        if (!ai.minecivilization.navigation.PlacementSafety.canRaiseOne(
                context.level, context.citizen, feet)) {
            // Something is in the way overhead — climbing a tree, that is
            // almost always the tree's own canopy, which this job is felling
            // anyway. Cut it and rise next tick, exactly as a player would.
            BlockPos blocking = blockedOverhead(context, feet);
            if (blocking == null) return null;
            return mineOverhead(context, blocking);
        }

        CitizenTaskParams params = new CitizenTaskParams();
        params.position = new int[]{feet.getX(), feet.getY(), feet.getZ()};
        params.block = material;
        params.extra.put("pillar", "true");
        params.extra.put("item", material);

        subContext = new SkillContext(context.citizen, context.level,
                context.navigator, params);
        subContext.timeoutTicks = context.timeoutTicks;
        subContext.startGameTime = context.level.getGameTime();
        sub = SkillRegistry.create(SkillType.PLACE_BLOCK);
        subIsTraverse = false;
        // A pillar is not progress on the log itself, so the index must not
        // advance when it completes.
        climbing = true;
        sub.start(subContext);
        climbed++;
        context.citizen.setBracedPlacement(true);
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    /**
     * The block stopping this citizen from standing one higher, if it can be
     * cut away.
     */
    private BlockPos blockedOverhead(SkillContext context, BlockPos feet) {
        var view = new ai.minecivilization.navigation.LevelBlockView(context.level);
        for (BlockPos cell : new BlockPos[]{feet.above(2), feet.above()}) {
            if (context.level.getBlockState(cell).isAir()) continue;
            if (!view.diggable(cell)) continue;
            return cell;
        }
        return null;
    }

    /** Cut one block of head room so the climb can carry on. */
    private SkillResult mineOverhead(SkillContext context, BlockPos blocking) {
        CitizenTaskParams params = new CitizenTaskParams();
        params.position = new int[]{blocking.getX(), blocking.getY(), blocking.getZ()};
        params.extra.put("workAnimation", "chop");

        subContext = new SkillContext(context.citizen, context.level,
                context.navigator, params);
        subContext.timeoutTicks = context.timeoutTicks;
        subContext.startGameTime = context.level.getGameTime();
        sub = SkillRegistry.create(SkillType.MINE_BLOCK);
        subIsTraverse = false;
        // Head room is not the log we came for, so the work list must not
        // advance when this finishes.
        climbing = true;
        sub.start(subContext);
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    /** True when this cell is part of the trunk rather than the canopy. */
    private boolean isLog(SkillContext context, BlockPos pos) {
        return context.level.getBlockState(pos).is(BlockTags.LOGS);
    }

    private List<BlockPos> standingRemains(SkillContext context) {
        List<BlockPos> remains = new java.util.ArrayList<>();
        if (extentMin == null || extentMax == null) return remains;

        for (BlockPos pos : BlockPos.betweenClosed(extentMin, extentMax)) {
            if (!context.level.isLoaded(pos)) continue;
            BlockState state = context.level.getBlockState(pos);
            // Leaves left behind are self-healing: with every log of the tree
            // gone, vanilla decays them on its own. A log is not, so a log is
            // what a sweep must never leave.
            if (!state.is(BlockTags.LOGS)) continue;
            if (ai.minecivilization.construction.ConstructionManager.get(context.level)
                    .protectsCell(pos)) continue;
            remains.add(pos.immutable());
            if (remains.size() >= 64) break;
        }
        return remains;
    }

    /** Remember where the tree stood, so a sweep knows where to look. */
    private void recordExtent(List<BlockPos> logs, List<BlockPos> leaves) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (List<BlockPos> group : List.of(logs, leaves)) {
            for (BlockPos pos : group) {
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
        }
        if (minX > maxX) return;
        extentMin = new BlockPos(minX, minY, minZ);
        extentMax = new BlockPos(maxX, maxY, maxZ);
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

        List<BlockPos> logs = TreeShape.collect(start,
                pos -> context.level.getBlockState(pos).is(BlockTags.LOGS),
                pos -> context.level.getBlockState(pos).is(BlockTags.LEAVES));
        if (logs.isEmpty()) {
            context.fail(new SkillFailure("BLOCK_ALREADY_MINED",
                    "the tree is already down", true));
            return SkillResult.FAILED;
        }
        base = logs.get(0);

        // The canopy comes down with the trunk. Felling logs alone leaves the
        // foliage hanging in the air, and it is also where the saplings for
        // replanting come from.
        List<BlockPos> leaves = TreeShape.canopy(logs,
                pos -> context.level.getBlockState(pos).is(BlockTags.LEAVES));
        trunk = TreeShape.fellingOrder(logs, leaves);
        logCount = logs.size();
        recordExtent(logs, leaves);
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
        } else {
            params.extra.put("workAnimation", "chop");
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
        if (cleanupAbandoned) return null;
        for (BlockPos pos : context.citizen.scaffoldPlaced()) {
            BlockState state = context.level.getBlockState(pos);
            BlockState ownedState = context.citizen.scaffoldState(pos);
            if (ownedState != null && !state.equals(ownedState)) {
                // A player or another worker replaced the temporary support;
                // release ownership without destroying their block.
                context.citizen.forgetScaffold(pos);
                continue;
            }
            if (state.isAir() || trunk.contains(pos)) {
                context.citizen.forgetScaffold(pos);
                continue;
            }

            // Never remove the block currently under the worker. First move to
            // a real neighbouring cell, then the next tick can take the support
            // away without dropping the citizen down the whole tower.
            if (pos.equals(context.citizen.blockPosition().below())) {
                BlockPos stand = findTeardownStand(context);
                if (stand == null) {
                    cleanupAbandoned = true;
                    return null;
                }
                if (!context.citizen.blockPosition().equals(stand)) {
                    if (teardownWalks++ > MAX_TEARDOWN_WALKS) {
                        cleanupAbandoned = true;
                        return null;
                    }
                    context.navigator.requestSafeStep();
                    context.navigator.moveToStand(stand, 1.0);
                    context.navigator.tick();
                    if (context.navigator.hasFailed()) {
                        cleanupAbandoned = true;
                        return null;
                    }
                    return SkillResult.RUNNING;
                }
            }

            double distSqr = context.citizen.distanceToSqr(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distSqr > REACH_SQR) {
                if (teardownWalks++ > MAX_TEARDOWN_WALKS) {
                    cleanupAbandoned = true;
                    return null;
                }
                context.navigator.requestSafeStep();
                context.navigator.moveTo(pos, 1.0);
                context.navigator.tick();
                if (context.navigator.hasFailed()) {
                    cleanupAbandoned = true;
                    return null;
                }
                return SkillResult.RUNNING;
            }
            if (PlacementSafety.hasOtherLivingEntity(context.level, context.citizen, pos)) {
                cleanupAbandoned = true;
                return null;
            }
            if (!context.level.destroyBlock(pos, true, context.citizen)) {
                cleanupAbandoned = true;
                return null;
            }
            context.citizen.animateAction(WorkAnimation.MINE, pos);
            context.citizen.forgetScaffold(pos);
            collectNearbyDrops(context);
            context.startGameTime = context.level.getGameTime();
            return SkillResult.RUNNING;
        }
        return null;
    }

    private BlockPos findTeardownStand(SkillContext context) {
        BlockPos feet = context.citizen.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos sameLevel = feet.relative(direction);
            if (!sameLevel.equals(feet)
                    && PlacementSafety.canStand(context.level, context.citizen, sameLevel)) {
                return sameLevel;
            }
            BlockPos lower = feet.below().relative(direction);
            if (PlacementSafety.canStand(context.level, context.citizen, lower)) {
                return lower;
            }
        }
        return null;
    }

    /** Walks spent tidying up before the rest is written off. */
    private static final int MAX_TEARDOWN_WALKS = 12;

    /** Blocks a citizen needs in hand before it can pillar up to a branch. */
    private static final int SCAFFOLD_WANTED = 16;

    /**
     * Enough rubble to climb with.
     *
     * <p>Rubble specifically, not "anything stackable". A lumberjack is by
     * definition holding logs, so counting those as scaffold meant it never
     * bothered to dig and instead stood on the timber it had just spent the
     * afternoon collecting. Digging a block of dirt costs a second; a log
     * costs four planks.</p>
     */
    private static boolean hasScaffold(SkillContext context) {
        return ai.minecivilization.navigation.ScaffoldMaterial
                .availableCheap(context.citizen.getInventory()) >= 2;
    }

    /**
     * What to stand on. Rubble while there is any, and only then the harvest.
     *
     * <p>Falling back to logs still matters: being stranded halfway up a tree
     * because the ground was stone is worse than spending one log.</p>
     */
    private static String climbMaterial(SkillContext context) {
        var inventory = context.citizen.getInventory();
        var counts = ai.minecivilization.crafting.VanillaRecipeSource
                .inventorySnapshot(inventory);
        if (ai.minecivilization.navigation.ScaffoldMaterial.availableCheap(counts) > 0) {
            for (String cheap : new String[]{"minecraft:dirt", "minecraft:coarse_dirt",
                    "minecraft:gravel", "minecraft:sand", "minecraft:cobblestone",
                    "minecraft:cobbled_deepslate", "minecraft:andesite",
                    "minecraft:diorite", "minecraft:granite", "minecraft:tuff"}) {
                if (inventory.count(cheap) > 0) return cheap;
            }
        }
        return ai.minecivilization.navigation.ScaffoldMaterial.chooseAny(inventory, 1);
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

        if (!context.level.destroyBlock(ground, true, context.citizen)) {
            return SkillResult.RUNNING;
        }
        context.citizen.animateAction(WorkAnimation.MINE, ground);
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
                    if (!state.is(BlockTags.DIRT)
                            || !new ai.minecivilization.navigation.LevelBlockView(context.level)
                            .diggable(pos)) continue;
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
            if (!item.isAlive() || item.getItem().isEmpty()
                    || !ai.minecivilization.navigation.ScaffoldMaterial.isExpendable(
                    CitizenInventory.idOf(item.getItem()))) continue;
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
        climbing = false;
    }

    // ------------------------------------------------------------------ replanting

    /**
     * Put a sapling back on the stump and tell the colony which species this
     * was. Both are best-effort: a lumberjack with no saplings has still done
     * its job, and the drops from the canopy usually supply the next one.
     */
    private SkillResult finish(SkillContext context) {
        context.navigator.stop();
        context.citizen.setBracedPlacement(false);
        if (!replantAttempted) {
            replantAttempted = true;
            dropCanopy(context);
            decayOrphanedLeaves(context);
            collectDrops(context);
            replant(context);
            rememberSpecies(context);
        }
        return SkillResult.COMPLETED;
    }

    /**
     * Bring down whatever canopy the citizen did not cut by hand.
     *
     * <p>With the trunk gone the leaves are already doomed — vanilla decays
     * any leaf too far from a log — but decay rides on random ticks, which are
     * sparse and only happen in chunks something is actually ticking. Left to
     * itself a felled forest kept its canopies hanging in the air for a very
     * long time, and in chunks nobody was ticking, forever.</p>
     *
     * <p>Asking for the tick explicitly makes the collapse prompt instead of
     * eventual, and it is still vanilla decay doing the work: the leaves drop
     * their saplings and apples exactly as they should.</p>
     */
    private void dropCanopy(SkillContext context) {
        if (trunk == null) return;
        var level = context.level;
        int scheduled = 0;

        for (BlockPos pos : trunk) {
            if (scheduled >= MAX_DECAY_SCHEDULED) break;
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!state.is(BlockTags.LEAVES)) continue;
            // Leaves a player placed are meant to stay; only the tree's own
            // growth decays.
            if (state.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                    && state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) {
                continue;
            }
            // Stagger them so a big canopy does not vanish in a single frame.
            level.scheduleTick(pos, state.getBlock(), 1 + (scheduled % 20));
            scheduled++;
        }
    }

    /**
     * Bring the orphaned canopy down now, the way vanilla decay eventually
     * would: every leaf with no log within six blocks through the foliage
     * falls, dropping its saplings, sticks and apples.
     *
     * <p>Asking vanilla to re-check the leaves only updates their distance;
     * the actual decay then waits for random ticks, which are rare — felled
     * forests kept floating canopies for a long time. Leaves still held by a
     * log (a branch the lumberjack could not reach, a neighbouring tree) are
     * left exactly as vanilla would leave them.</p>
     */
    private void decayOrphanedLeaves(SkillContext context) {
        if (trunk == null || trunk.isEmpty()) return;
        var level = context.level;
        java.util.Set<BlockPos> leaves = new java.util.HashSet<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : trunk) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }
        // Every natural leaf in and around the tree's extent, and every log
        // that could still be holding some of them up.
        java.util.ArrayDeque<BlockPos> frontier = new java.util.ArrayDeque<>();
        java.util.Map<BlockPos, Integer> held = new java.util.HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(minX - 6, minY - 2, minZ - 6,
                maxX + 6, maxY + 2, maxZ + 6)) {
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS)) {
                BlockPos log = pos.immutable();
                held.put(log, 0);
                frontier.add(log);
            } else if (state.is(BlockTags.LEAVES)
                    && !(state.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                         && state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT))) {
                leaves.add(pos.immutable());
            }
        }
        // Distance through foliage from the nearest remaining log, as vanilla counts it.
        while (!frontier.isEmpty()) {
            BlockPos at = frontier.poll();
            int d = held.get(at);
            if (d >= 6) continue;
            for (Direction dir : Direction.values()) {
                BlockPos next = at.relative(dir);
                if (!leaves.contains(next) || held.containsKey(next)) continue;
                held.put(next, d + 1);
                frontier.add(next);
            }
        }
        int fallen = 0;
        for (BlockPos leaf : leaves) {
            if (held.containsKey(leaf)) continue;
            if (fallen >= MAX_DECAY_SCHEDULED) break;
            level.destroyBlock(leaf, true);
            fallen++;
        }
    }

    /** Pick up what the tree dropped: saplings, sticks, apples, stray logs. */
    private void collectDrops(SkillContext context) {
        var box = context.citizen.getBoundingBox().inflate(10.0, 12.0, 10.0);
        var inventory = context.citizen.getInventory();
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

    /**
     * Put the tree back — one on the stump, and a second beside it when the
     * citizen has a sapling to spare.
     *
     * <p>Two rather than one because not every sapling survives to be a tree,
     * and a forest that replaces each felled trunk with exactly one is a
     * forest that slowly shrinks. Saplings come from the canopy the same job
     * just took down, so replanting costs the colony nothing.</p>
     */
    private void replant(SkillContext context) {
        if (base == null || logId == null) return;
        String sapling = TreeSpecies.saplingFor(logId);
        if (sapling == null) return;

        int planted = 0;
        for (BlockPos spot : replantSpots(context)) {
            if (planted >= REPLANT_TARGET) break;
            if (plantOne(context, spot, sapling)) planted++;
        }
    }

    /** The stump first, then the cells around it — spaced so both can grow. */
    private List<BlockPos> replantSpots(SkillContext context) {
        List<BlockPos> spots = new java.util.ArrayList<>();
        spots.add(base);
        // Two apart, so neither sapling is shaded out by the other's trunk.
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            spots.add(base.relative(dir, 2));
        }
        return spots;
    }

    /** @return true when a sapling is now standing here */
    private boolean plantOne(SkillContext context, BlockPos spot, String sapling) {
        if (spot == null) return false;
        if (ai.minecivilization.construction.ConstructionManager.get(context.level)
                .protectsCell(spot)) return false;

        CitizenInventory inventory = context.citizen.getInventory();
        if (!inventory.containsAtLeast(sapling, 1)) return false;

        // Follow the ground: a spot beside the stump may sit a block up or down.
        BlockPos ground = groundAt(context, spot);
        if (ground == null) return false;
        if (!context.level.getBlockState(ground).canBeReplaced()) return false;
        if (!context.level.getBlockState(ground.below()).is(BlockTags.DIRT)) return false;

        BlockState saplingState = ai.minecivilization.construction.ConstructionManager
                .parseState(context.level, sapling);
        if (saplingState == null || !saplingState.canSurvive(context.level, ground)
                || !PlacementSafety.canOccupy(context.level, context.citizen, ground, false)) {
            return false;
        }

        inventory.extract(sapling, 1);
        if (!context.level.setBlock(ground, saplingState, 3)
                || !context.level.getBlockState(ground).equals(saplingState)) {
            inventory.insert(new net.minecraft.world.item.ItemStack(
                    CitizenInventory.itemById(sapling)));
            return false;
        }
        context.citizen.animateAction(WorkAnimation.PLANT, ground);
        context.citizen.getSkills().addXp("farming", 0.05f);
        context.citizen.onBlockPlaced(sapling);
        return true;
    }

    /** The open cell on the soil at this column, within a block of the stump. */
    private BlockPos groundAt(SkillContext context, BlockPos near) {
        for (int dy = 1; dy >= -1; dy--) {
            BlockPos candidate = near.above(dy);
            if (!context.level.isLoaded(candidate)) continue;
            if (context.level.getBlockState(candidate).canBeReplaced()
                    && context.level.getBlockState(candidate.below()).is(BlockTags.DIRT)) {
                return candidate;
            }
        }
        return null;
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
        if (!context.citizen.isOnOwnedScaffold()) {
            context.citizen.setBracedPlacement(false);
        }
    }

    @Override
    public String progressLabel(SkillContext context) {
        if (trunk == null) return "sizing up the tree";
        String what = index < logCount ? "felling" : "clearing the canopy";
        return what + " " + Math.min(index + 1, trunk.size()) + "/" + trunk.size()
                + (sweeps > 0 ? " (sweep " + sweeps + ")" : "");
    }
}
