package ai.minecivilization.skills.impl;

import java.util.List;

import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.navigation.LevelBlockView;
import ai.minecivilization.navigation.StaircasePlan;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import ai.minecivilization.telemetry.ColonyEventLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * DIG_TO_SURFACE — the escape that always works.
 *
 * <p>Every other way of getting somewhere can fail: a path may not exist, a
 * bridge may run out of blocks, a cave may have no opening. Cutting upward
 * cannot, because there is always sky above and the citizen is always able to
 * remove what is between. This is the last rung of the rescue ladder, and it
 * is the reason a citizen that falls into a ravine or gets sealed in a cave is
 * never written off.</p>
 *
 * <p>It cuts a real staircase — one block forward, one block up, head room
 * included — aimed at the colony, so a citizen that surfaces has also closed
 * some of the distance home. Everything it breaks it picks up, so a long climb
 * pays for itself in stone. Where a tread has nothing underneath it (the stair
 * crosses a cavern) it floors the gap from its own stock, or from the very rock
 * it just cut.</p>
 *
 * <p>Slow by design. A hundred blocks of stone is several minutes of work, and
 * that is the honest cost of being lost under a mountain.</p>
 */
public final class SurfaceEscapeSkill implements CitizenSkill {

    /** Arm's reach, squared. */
    private static final double REACH_SQR = 20.0;
    /** Ticks of work put into one block before it breaks, at hardness 1. */
    private static final double HARDNESS_TICKS = 24.0;
    /** How often progress is reported to the colony log, in treads. */
    private static final int REPORT_EVERY = 16;

    private List<StaircasePlan.Step> steps;
    private int cursor;
    private float progress;
    private BlockPos cutting;
    private int startY;
    private int targetY;
    private int cutBlocks;
    private int reportedAt;

    @Override
    public SkillType type() {
        return SkillType.DIG_TO_SURFACE;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.citizen != null;
    }

    @Override
    public void start(SkillContext context) {
        BlockPos at = context.citizen.blockPosition();
        startY = at.getY();
        targetY = surfaceAbove(context.level, at);

        int[] toward = destination(context);
        int[] dir = StaircasePlan.cardinalToward(at.getX(), at.getZ(), toward[0], toward[1]);
        steps = StaircasePlan.upward(at.getX(), at.getY(), at.getZ(), targetY, dir[0], dir[1]);
        cursor = 0;
        progress = 0f;
        cutting = null;
        cutBlocks = 0;
        reportedAt = 0;

        if (!steps.isEmpty()) {
            ColonyEventLog.of(context.level).rescue(context.citizen.getIdentity().name,
                    "started an emergency staircase at y=" + startY + " toward y=" + targetY
                            + " (" + StaircasePlan.blocksToRemove(steps.size()) + " blocks)");
        }
    }

    @Override
    public SkillResult tick(SkillContext context) {
        ServerLevel level = context.level;
        BlockPos at = context.citizen.blockPosition();

        // Out already: open sky, or climbed past the surface the plan aimed at.
        if (level.canSeeSky(at.above()) || at.getY() >= targetY) {
            ColonyEventLog.of(level).rescue(context.citizen.getIdentity().name,
                    "reached daylight at y=" + at.getY() + " after cutting "
                            + cutBlocks + " block(s)");
            return SkillResult.COMPLETED;
        }
        if (steps == null || cursor >= steps.size()) {
            // The plan ran out without reaching sky — the world moved, or the
            // stair broke out into a cavern higher up. Re-plan from here
            // instead of reporting failure: there is still sky above.
            start(context);
            if (steps.isEmpty()) {
                context.fail(SkillFailure.unreachable("no way up from y=" + at.getY()));
                return SkillResult.FAILED;
            }
            return SkillResult.RUNNING;
        }

        StaircasePlan.Step step = steps.get(cursor);
        BlockPos feet = new BlockPos(step.x(), step.y(), step.z());

        if (context.citizen.distanceToSqr(feet.getX() + 0.5, feet.getY() + 0.5,
                feet.getZ() + 0.5) > REACH_SQR) {
            // Walking one tread is not a journey; if even that fails, cut the
            // next cell from where we stand rather than giving up the escape.
            context.navigator.moveToSafe(feet, 1.0);
            context.navigator.tick();
            if (!context.navigator.hasFailed()) return SkillResult.RUNNING;
            context.navigator.stop();
        }

        // Clear head room and foot room, one block at a time.
        BlockPos blocking = firstBlocking(level, feet);
        if (blocking != null) {
            return cut(context, blocking);
        }

        // A tread with nothing under it is a fall, not a stair.
        if (!floor(context, feet.below())) {
            return SkillResult.RUNNING;
        }

        collectDrops(context);
        cursor++;
        if (cursor - reportedAt >= REPORT_EVERY) {
            reportedAt = cursor;
            ColonyEventLog.of(level).rescue(context.citizen.getIdentity().name,
                    "climbing out — y=" + feet.getY() + " of " + targetY);
        }
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ cutting

    /** The first of a tread's two body cells that is not yet clear. */
    private static BlockPos firstBlocking(ServerLevel level, BlockPos feet) {
        for (BlockPos cell : new BlockPos[]{feet, feet.above()}) {
            BlockState state = level.getBlockState(cell);
            if (state.isAir()) continue;
            if (state.getCollisionShape(level, cell).isEmpty()
                    && state.getFluidState().isEmpty()) continue;   // grass, torches
            return cell;
        }
        return null;
    }

    /**
     * Work at one block until it breaks.
     *
     * <p>Hardness-based, and helped by whatever tool the citizen happens to
     * carry, so an escape with a pickaxe is much faster than one with bare
     * hands — but bare hands always finish eventually, which is the point.</p>
     */
    private SkillResult cut(SkillContext context, BlockPos pos) {
        ServerLevel level = context.level;
        BlockState state = level.getBlockState(pos);

        if (state.getDestroySpeed(level, pos) < 0f
                || !new LevelBlockView(level).diggable(pos)) {
            // Bedrock, a chest, somebody's front door: go around it by stepping
            // the whole staircase one block sideways and carrying on up.
            return sidestep(context);
        }
        if (!pos.equals(cutting)) {
            cutting = pos;
            progress = 0f;
        }

        context.citizen.animateAction(WorkAnimation.MINE, pos);
        float hardness = Math.max(0.15f, state.getDestroySpeed(level, pos));
        float speed = (float) (1.0 / (hardness * HARDNESS_TICKS + 1.0));
        speed *= toolBonus(context, state);
        progress += speed;
        if (progress < 1.0f) return SkillResult.RUNNING;

        progress = 0f;
        cutting = null;
        if (level.destroyBlock(pos, true, context.citizen)) {
            cutBlocks++;
            context.citizen.getSkills().addXp("mining", 0.02f);
            collectDrops(context);
        }
        context.startGameTime = level.getGameTime();
        return SkillResult.RUNNING;
    }

    /**
     * Shift the whole remaining staircase sideways around an obstacle it must
     * not cut through, and keep climbing.
     */
    private SkillResult sidestep(SkillContext context) {
        BlockPos at = context.citizen.blockPosition();
        int[] toward = destination(context);
        int[] dir = StaircasePlan.cardinalToward(at.getX(), at.getZ(), toward[0], toward[1]);
        // Turn ninety degrees: the blocked direction has already been tried.
        int turnedX = dir[0] == 0 ? 1 : 0;
        int turnedZ = dir[0] == 0 ? 0 : 1;
        steps = StaircasePlan.upward(at.getX(), at.getY(), at.getZ(), targetY,
                turnedX, turnedZ);
        cursor = 0;
        progress = 0f;
        cutting = null;
        if (steps.isEmpty()) {
            context.fail(SkillFailure.unreachable("nothing above to climb toward"));
            return SkillResult.FAILED;
        }
        return SkillResult.RUNNING;
    }

    /**
     * Make sure a tread has something to stand on.
     *
     * @return true when the tread is ready to be walked on
     */
    private boolean floor(SkillContext context, BlockPos below) {
        ServerLevel level = context.level;
        if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return true;

        CitizenInventory inventory = context.citizen.getInventory();
        for (String id : new String[]{"minecraft:cobblestone", "minecraft:dirt",
                "minecraft:stone", "minecraft:cobbled_deepslate", "minecraft:andesite",
                "minecraft:granite", "minecraft:diorite", "minecraft:tuff",
                "minecraft:gravel", "minecraft:oak_planks"}) {
            if (inventory.count(id) <= 0) continue;
            BlockState state = ai.minecivilization.construction.ConstructionManager
                    .parseState(level, id);
            if (state == null) continue;
            inventory.extract(id, 1);
            if (level.setBlock(below, state, 3)) {
                context.citizen.animateAction(WorkAnimation.BUILD, below);
                return true;
            }
            inventory.insert(new net.minecraft.world.item.ItemStack(
                    CitizenInventory.itemById(id)));
        }

        // Nothing to floor it with. Cobblestone is one block of rock away in
        // any direction underground, so mine a wall rather than stopping.
        BlockPos source = nearbyRock(level, context.citizen.blockPosition());
        if (source != null) {
            cut(context, source);
            return false;
        }
        // Above ground with an empty pack and no rock to hand: a fall of one
        // block is survivable, so keep climbing rather than deadlocking.
        return true;
    }

    /** A block of ordinary stone or soil within reach, for making a floor from. */
    private static BlockPos nearbyRock(ServerLevel level, BlockPos origin) {
        LevelBlockView view = new LevelBlockView(level);
        for (int dy = 0; dy <= 1; dy++) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos probe = origin.above(dy).relative(dir);
                BlockState state = level.getBlockState(probe);
                if (state.isAir() || !view.diggable(probe)) continue;
                if (state.getDestroySpeed(level, probe) < 0f) continue;
                if (state.is(Blocks.WATER) || state.is(Blocks.LAVA)) continue;
                return probe;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** Open sky above this column, or the top of the world if there is none. */
    private static int surfaceAbove(ServerLevel level, BlockPos at) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                at.getX(), at.getZ());
        // At least a few blocks of climb, even standing on the surface already:
        // "escape" from inside a sealed box still means going up.
        return Math.min(level.getMaxBuildHeight() - 2, Math.max(surface + 1, at.getY() + 3));
    }

    /** Where the staircase should lean — the colony, unless told otherwise. */
    private static int[] destination(SkillContext context) {
        int[] given = context.params.position;
        if (given != null && given.length == 3) return new int[]{given[0], given[2]};
        BlockPos centre = ai.minecivilization.colony.ZoneManager.get(context.level)
                .townCenter(context.level);
        return new int[]{centre.getX(), centre.getZ()};
    }

    private static float toolBonus(SkillContext context, BlockState state) {
        var inventory = context.citizen.getInventory();
        float best = 1.0f;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) continue;
            best = Math.max(best, stack.getDestroySpeed(state));
        }
        return best;
    }

    private static void collectDrops(SkillContext context) {
        var inventory = context.citizen.getInventory();
        var box = context.citizen.getBoundingBox().inflate(4.0);
        for (ItemEntity item : context.level.getEntitiesOfClass(ItemEntity.class, box)) {
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
        context.citizen.clearWorkAnimation();
    }

    @Override
    public String progressLabel(SkillContext context) {
        int y = context.citizen.blockPosition().getY();
        return "digging out: y " + y + " → " + targetY + " (" + cutBlocks + " cut)";
    }
}
