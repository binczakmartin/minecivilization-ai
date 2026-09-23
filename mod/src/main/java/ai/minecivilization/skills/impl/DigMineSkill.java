package ai.minecivilization.skills.impl;

import java.util.List;

import ai.minecivilization.colony.LandmarkRegistry;
import ai.minecivilization.colony.Signpost;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.mining.MineLayout;
import ai.minecivilization.navigation.PlacementSupport;
import ai.minecivilization.mining.MineWorks;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * DIG_MINE — cut the colony's shared mine, one shift at a time.
 *
 * <p>Miners working alone each scratch a hole, find ore by luck, and never
 * reach the deep seams because nobody digs far enough on their own. This is the
 * same mine for everybody: progress lives in {@link MineWorks}, so whoever
 * turns up next carries on from where the last one stopped.</p>
 *
 * <p>What gets cut is a walkable stair, lit every few steps, with a landing at
 * the depth each ore actually peaks — and at every landing a sign saying which
 * ore it is and a chest to put it in. A miner arriving at the iron level knows
 * it is the iron level, and knows where to leave what it finds.</p>
 *
 * <p>One call does a shift, not the whole mine: a few blocks of stair, then the
 * citizen comes up for air and the task ends cleanly. The mine is finished over
 * many shifts by many people, which is the point.</p>
 */
public final class DigMineSkill implements CitizenSkill {

    /** Blocks of stair cut in one shift before the job reports done. */
    private static final int SHIFT_LENGTH = 24;
    /** Arm's reach, squared. */
    private static final double REACH_SQR = 20.0;

    private MineWorks works;
    private MineLayout.Level target;
    private List<int[]> steps;
    private int cursor;
    private int cutThisShift;
    private boolean landingPhase;
    private List<int[]> landing;

    @Override
    public SkillType type() {
        return SkillType.DIG_MINE;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return MineWorks.get(context.level).found(context.level) != null;
    }

    @Override
    public void start(SkillContext context) {
        works = MineWorks.get(context.level);
        target = null;
        steps = null;
        landing = null;
        cursor = 0;
        cutThisShift = 0;
        landingPhase = false;
    }

    @Override
    public SkillResult tick(SkillContext context) {
        BlockPos entrance = works.found(context.level);
        if (entrance == null) {
            context.fail(SkillFailure.notFound("the colony has no mining district yet"));
            return SkillResult.FAILED;
        }

        // The mouth of the mine is marked once, so it can be found again.
        if (!works.isEntranceMarked()) {
            SkillResult marking = markEntrance(context, entrance);
            if (marking != null) return marking;
        }

        if (target == null) {
            target = works.nextLevel(context.level);
            if (target == null) {
                return SkillResult.COMPLETED;   // the mine reaches every seam already
            }
            steps = MineLayout.stairSteps(entrance.getX(), works.currentY(), entrance.getZ(),
                    target.y(), works.legIndex());
            cursor = 0;
            landingPhase = false;
        }

        if (landingPhase) {
            return digLanding(context);
        }

        if (cursor >= steps.size()) {
            // Reached the depth: open the landing out and set up its depot.
            landingPhase = true;
            int[] last = steps.isEmpty()
                    ? new int[]{entrance.getX(), works.currentY(), entrance.getZ()}
                    : steps.get(steps.size() - 1);
            landing = MineLayout.landing(last[0], last[1], last[2], works.legIndex());
            return SkillResult.RUNNING;
        }
        if (cutThisShift >= SHIFT_LENGTH) {
            return SkillResult.COMPLETED;   // end of shift; someone else carries on
        }

        return cutStep(context, steps.get(cursor));
    }

    // ------------------------------------------------------------------ digging

    /** Hollow out one step of stair: head room, body room, and a torch now and then. */
    private SkillResult cutStep(SkillContext context, int[] step) {
        BlockPos feet = new BlockPos(step[0], step[1], step[2]);
        if (tooFar(context, feet)) {
            return walkTo(context, feet, "mine.walk");
        }

        boolean cutSomething = false;
        for (BlockPos cell : new BlockPos[]{feet, feet.above()}) {
            if (context.level.getBlockState(cell).isAir()) continue;
            if (context.level.getBlockState(cell).getDestroySpeed(context.level, cell) < 0) {
                continue;   // bedrock: go around, not through
            }
            context.level.destroyBlock(cell, true, context.citizen);
            cutSomething = true;
            break;          // one block per tick keeps mining physical
        }
        if (cutSomething) {
            collectDrops(context);
            context.citizen.getSkills().addXp("mining", 0.02f);
            context.startGameTime = context.level.getGameTime();
            return SkillResult.RUNNING;
        }

        works.recordPassage(feet);
        if (MineLayout.isTorchStep(works.depth())) {
            place(context, feet, "minecraft:torch");
        }
        cursor++;
        cutThisShift++;
        works.deepenBy(1);
        return SkillResult.RUNNING;
    }

    /**
     * Open the landing and furnish it: a sign saying which ore this level is
     * for, and a chest to leave it in.
     */
    private SkillResult digLanding(SkillContext context) {
        for (int[] cell : landing) {
            BlockPos feet = new BlockPos(cell[0], cell[1], cell[2]);
            if (tooFar(context, feet)) {
                return walkTo(context, feet, "mine.landing");
            }
            for (BlockPos probe : new BlockPos[]{feet, feet.above()}) {
                if (context.level.getBlockState(probe).isAir()) continue;
                if (context.level.getBlockState(probe)
                        .getDestroySpeed(context.level, probe) < 0) continue;
                context.level.destroyBlock(probe, true, context.citizen);
                collectDrops(context);
                context.startGameTime = context.level.getGameTime();
                return SkillResult.RUNNING;
            }
        }

        int[] depot = MineLayout.depotOf(landing);
        if (depot != null) {
            BlockPos at = new BlockPos(depot[0], depot[1], depot[2]);
            // A double chest: two side by side, which is what "enough room for
            // a shift's ore" means in practice.
            place(context, at, "minecraft:chest");
            place(context, at.east(), "minecraft:chest");
            place(context, at.above(), "minecraft:oak_sign");
            Signpost.write(context.level, at.above(),
                    Signpost.titleForOre(target.ore()), "y=" + target.y());
            place(context, at.west(), "minecraft:torch");
        }

        works.finishLevel(target);
        target = null;
        landingPhase = false;
        return SkillResult.COMPLETED;
    }

    /** Light and label the mouth of the mine, so it reads as a way in. */
    private SkillResult markEntrance(SkillContext context, BlockPos entrance) {
        if (tooFar(context, entrance)) {
            return walkTo(context, entrance, "mine.entrance");
        }
        place(context, entrance.above(), "minecraft:oak_sign");
        Signpost.write(context.level, entrance.above(), "Colony mine", "all seams below");
        place(context, entrance.north(), "minecraft:torch");
        place(context, entrance.south(), "minecraft:torch");
        works.markEntrance();
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ helpers

    private boolean tooFar(SkillContext context, BlockPos pos) {
        return context.citizen.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > REACH_SQR;
    }

    private SkillResult walkTo(SkillContext context, BlockPos pos, String key) {
        SkillResult arrival = SkillNavigation.approach(context, pos, REACH_SQR, key);
        if (arrival == SkillResult.FAILED) {
            // The mine can be reached another shift; do not fail the whole job
            // over one awkward step.
            return cutThisShift > 0 ? SkillResult.COMPLETED : SkillResult.FAILED;
        }
        return SkillResult.RUNNING;
    }

    /** Place a block from the citizen's own stock, and tell the colony it is there. */
    private void place(SkillContext context, BlockPos pos, String blockId) {
        if (!context.level.getBlockState(pos).canBeReplaced()) return;
        CitizenInventory inventory = context.citizen.getInventory();
        if (!inventory.containsAtLeast(blockId, 1)) return;

        BlockState state = ConstructionManager.parseState(context.level, blockId);
        if (state == null || !PlacementSupport.canPlace(context.level, state, pos)) return;

        inventory.extract(blockId, 1);
        if (!context.level.setBlock(pos, state, 3)) {
            inventory.insert(new net.minecraft.world.item.ItemStack(
                    CitizenInventory.itemById(blockId)));
            return;
        }
        context.citizen.onBlockPlaced(blockId);
        LandmarkRegistry.get(context.level).notice(context.level, pos);
        ai.minecivilization.storage.StorageDiscovery.onContainerPlaced(context.level, pos);
    }

    private void collectDrops(SkillContext context) {
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

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        if (works == null) return "heading for the mine";
        if (target == null) return "mine complete";
        return "mining toward " + target.label() + " (y=" + works.currentY()
                + " of " + target.y() + ")";
    }
}
