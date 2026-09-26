package ai.minecivilization.skills.impl;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.citizen.NightPolicy;
import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.navigation.LevelBlockView;
import ai.minecivilization.navigation.ScaffoldMaterial;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * SHELTER — survive the night the way a player does on day one: wall yourself
 * in, wait for morning, take the walls down again.
 *
 * <p>Four walls two blocks high and a roof, around wherever the citizen stands
 * — nine blocks at most, fewer against a hillside or inside a house, where the
 * world already provides some of them. Nothing hostile can reach a citizen in
 * a closed one-by-one cell: zombies cannot break the walls, skeletons cannot
 * see in and nothing can spawn inside.</p>
 *
 * <p>Physically honest like every other skill. Each block comes out of the
 * pack; a citizen without enough digs dirt (or, with a pickaxe, stone) from
 * the ground within reach first. At dawn the blocks are taken back, so a night
 * costs nothing but the time.</p>
 *
 * <p>Modes: the default builds, waits and dismantles; {@code shelter.mode =
 * dismantle} only takes down a shelter left standing from a night that was
 * interrupted.</p>
 */
public final class ShelterSkill implements CitizenSkill {

    /** Ticks between two block operations: quick, but visibly one at a time. */
    private static final int OP_INTERVAL = 5;
    /** Arm's reach for digging material, squared. */
    private static final double REACH_SQR = 20.0;
    /** Shelter blocks further away than this are left where they stand. */
    private static final double RECOVER_RANGE_SQR = 36.0;
    /** Material digs before settling for a partial shelter. */
    private static final int MAX_DIGS = 16;

    private enum Stage { BUILD, WAIT, DISMANTLE }

    private Stage stage;
    private BlockPos feet;
    private long nextOpAt;
    private int digs;
    private boolean enclosed;

    @Override
    public SkillType type() {
        return SkillType.SHELTER;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return true;
    }

    @Override
    public void start(SkillContext context) {
        context.navigator.stop();
        boolean dismantleOnly = "dismantle".equals(context.params.extra.get("shelter.mode"));
        stage = dismantleOnly || !isShelterTime(context) ? Stage.DISMANTLE : Stage.BUILD;
        feet = context.citizen.blockPosition();
        nextOpAt = 0;
        digs = 0;
        enclosed = false;
    }

    @Override
    public SkillResult tick(SkillContext context) {
        long now = context.level.getGameTime();
        // Sitting still all night is the job, not a stall: keep the clock fresh.
        context.startGameTime = now;
        context.navigator.stop();

        if (stage != Stage.DISMANTLE && !isShelterTime(context)) {
            leaveShelter(context);
            stage = Stage.DISMANTLE;
        }
        if (now < nextOpAt) return SkillResult.RUNNING;

        return switch (stage) {
            case BUILD -> build(context, now);
            case WAIT -> waitForMorning(context, now);
            case DISMANTLE -> dismantle(context, now);
        };
    }

    // ------------------------------------------------------------------ build

    private SkillResult build(SkillContext context, long now) {
        if (!context.citizen.onGround() && !context.citizen.isInWater()) {
            return SkillResult.RUNNING;   // landing first; walls around a fall are no use
        }
        // Knocked out of place (a hit, a push): the walls go where it stands now.
        BlockPos standing = context.citizen.blockPosition();
        if (!standing.equals(feet) || !centred(context)) {
            feet = standing;
            // Step to the middle of the cell. A citizen standing near an edge
            // overlaps the neighbouring cell, which then can never be walled.
            context.citizen.setPos(feet.getX() + 0.5, context.citizen.getY(), feet.getZ() + 0.5);
        }

        List<BlockPos> open = openCells(context);
        if (open.isEmpty()) {
            enclosed = true;
            context.citizen.setSheltered(true);
            context.citizen.clearWorkAnimation();
            stage = Stage.WAIT;
            return SkillResult.RUNNING;
        }

        String material = ScaffoldMaterial.choose(context.citizen.getInventory(), 1);
        if (material == null) {
            if (digs < MAX_DIGS && digMaterial(context)) {
                digs++;
                nextOpAt = now + OP_INTERVAL;
                return SkillResult.RUNNING;
            }
            // Nothing in the pack and nothing diggable within reach: a partial
            // shelter still beats standing in the open, and the citizen keeps
            // its weapon hand free because it is not marked sheltered.
            stage = Stage.WAIT;
            return SkillResult.RUNNING;
        }

        for (BlockPos cell : open) {
            if (occupied(context, cell)) continue;
            if (place(context, cell, material)) {
                nextOpAt = now + OP_INTERVAL;
                return SkillResult.RUNNING;
            }
        }
        // Every open cell has somebody standing in it right now. Try again shortly.
        nextOpAt = now + OP_INTERVAL * 4;
        return SkillResult.RUNNING;
    }

    /** Wall and roof cells around the citizen that are still open, in build order. */
    private List<BlockPos> openCells(SkillContext context) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int dy = 0; dy <= 1; dy++) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                cells.add(feet.relative(side).above(dy));
            }
        }
        cells.add(feet.above(2));
        List<BlockPos> open = new ArrayList<>(cells.size());
        for (BlockPos cell : cells) {
            BlockState state = context.level.getBlockState(cell);
            if (state.getCollisionShape(context.level, cell).isEmpty() || state.canBeReplaced()) {
                open.add(cell);
            }
        }
        return open;
    }

    private static boolean occupied(SkillContext context, BlockPos cell) {
        return !context.level.getEntitiesOfClass(LivingEntity.class, new AABB(cell),
                entity -> entity != context.citizen && entity.isAlive()).isEmpty();
    }

    private boolean centred(SkillContext context) {
        return Math.abs(context.citizen.getX() - (feet.getX() + 0.5)) < 0.1
                && Math.abs(context.citizen.getZ() - (feet.getZ() + 0.5)) < 0.1;
    }

    private boolean place(SkillContext context, BlockPos cell, String material) {
        BlockState existing = context.level.getBlockState(cell);
        if (context.level.getBlockEntity(cell) != null) return false;
        if (!existing.canBeReplaced()) {
            // A flower, a torch, a sapling: walk-through clutter in a wall
            // cell. Pick it up (it drops normally) and wall over it.
            if (!existing.getCollisionShape(context.level, cell).isEmpty()
                    || !new LevelBlockView(context.level).diggable(cell)) return false;
            context.level.destroyBlock(cell, true, context.citizen);
        }
        if (!(CitizenInventory.itemById(material) instanceof BlockItem blockItem)) return false;
        BlockState state = blockItem.getBlock().defaultBlockState();
        CitizenInventory inventory = context.citizen.getInventory();
        if (inventory.extract(material, 1) != 1) return false;
        if (!context.level.setBlock(cell, state, 3)) {
            inventory.insert(new ItemStack(blockItem));
            return false;
        }
        context.citizen.rememberShelterBlock(cell);
        context.citizen.animateAction(WorkAnimation.PLACE, cell);
        context.citizen.onInventoryChanged();
        return true;
    }

    /** Dig one block of earth (or stone, with a pickaxe) from within reach. */
    private boolean digMaterial(SkillContext context) {
        ItemStack pickaxe = bestPickaxe(context.citizen.getInventory());
        LevelBlockView view = new LevelBlockView(context.level);
        var manager = ai.minecivilization.construction.ConstructionManager.get(context.level);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    // Never the ground under the shelter or its own walls.
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
                    BlockPos pos = feet.offset(dx, dy, dz);
                    double d = context.citizen.distanceToSqr(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (d > REACH_SQR || d >= bestDist) continue;
                    BlockState state = context.level.getBlockState(pos);
                    boolean earth = state.is(BlockTags.DIRT) && !state.is(Blocks.FARMLAND);
                    boolean stone = !pickaxe.isEmpty() && state.is(BlockTags.BASE_STONE_OVERWORLD);
                    if (!earth && !stone) continue;
                    if (context.level.getBlockEntity(pos) != null || !view.diggable(pos)
                            || manager.protectsCell(pos)) continue;
                    BlockState above = context.level.getBlockState(pos.above());
                    if (above.is(BlockTags.CROPS) || context.level.getBlockEntity(pos.above()) != null
                            || above.is(Blocks.CRAFTING_TABLE)) continue;
                    best = pos;
                    bestDist = d;
                }
            }
        }
        if (best == null) return false;

        BlockState state = context.level.getBlockState(best);
        ItemStack tool = state.is(BlockTags.DIRT) ? ItemStack.EMPTY : pickaxe;
        List<ItemStack> drops = Block.getDrops(state, context.level, best, null,
                context.citizen, tool);
        if (!context.level.removeBlock(best, false)) return false;
        for (ItemStack drop : drops) {
            int leftover = context.citizen.getInventory().insert(drop.copy());
            if (leftover > 0) {
                ItemStack rest = drop.copy();
                rest.setCount(leftover);
                Block.popResource(context.level, best, rest);
            }
        }
        context.citizen.animateAction(WorkAnimation.MINE, best);
        context.citizen.onInventoryChanged();
        return true;
    }

    private static ItemStack bestPickaxe(CitizenInventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof PickaxeItem) return stack;
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ wait

    private SkillResult waitForMorning(SkillContext context, long now) {
        // Walls get broken — by a creeper, by another citizen's work — and a
        // citizen that was pushed has left its cell. Either way: build again.
        if (now % 40 == 0 && (!context.citizen.blockPosition().equals(feet)
                || !openCells(context).isEmpty())) {
            if (digs < MAX_DIGS || ScaffoldMaterial.choose(context.citizen.getInventory(), 1) != null) {
                context.citizen.setSheltered(false);
                enclosed = false;
                stage = Stage.BUILD;
            }
        }
        context.citizen.clearWorkAnimation();
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ dismantle

    private SkillResult dismantle(SkillContext context, long now) {
        leaveShelter(context);
        for (BlockPos pos : context.citizen.shelterBlocks()) {
            if (context.citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5,
                    pos.getZ() + 0.5) > RECOVER_RANGE_SQR) {
                // Too far to take back: it stays, a small cairn in the landscape.
                context.citizen.forgetShelterBlock(pos);
                continue;
            }
            BlockState state = context.level.getBlockState(pos);
            context.citizen.forgetShelterBlock(pos);
            if (state.isAir() || state.canBeReplaced()
                    || context.level.getBlockEntity(pos) != null) continue;
            ItemStack back = returnedItem(state);
            if (back.isEmpty()) continue;
            if (context.citizen.getInventory().insert(back.copy()) > 0) {
                continue;   // no room in the pack: leave the block standing
            }
            context.level.removeBlock(pos, false);
            context.citizen.animateAction(WorkAnimation.MINE, pos);
            context.citizen.onInventoryChanged();
            nextOpAt = now + OP_INTERVAL;
            return SkillResult.RUNNING;
        }
        context.citizen.clearWorkAnimation();
        return SkillResult.COMPLETED;
    }

    /** What taking a shelter block down gives back. Grass that grew over dirt is still dirt. */
    private static ItemStack returnedItem(BlockState state) {
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL)) {
            return new ItemStack(Items.DIRT);
        }
        var item = state.getBlock().asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isShelterTime(SkillContext context) {
        return NightPolicy.shelterTime(context.level.getDayTime(), context.level.isThundering());
    }

    private void leaveShelter(SkillContext context) {
        enclosed = false;
        context.citizen.setSheltered(false);
    }

    @Override
    public void cancel(SkillContext context) {
        // The walls stay up (the citizen remembers them and takes them down
        // later); only the "safe inside" flag must never outlive the skill.
        leaveShelter(context);
        context.citizen.clearWorkAnimation();
    }

    @Override
    public String progressLabel(SkillContext context) {
        if (stage == null) return "sheltering";
        return switch (stage) {
            case BUILD -> "walling in for the night";
            case WAIT -> enclosed ? "sheltering until morning" : "keeping watch in a half-built shelter";
            case DISMANTLE -> "taking the night shelter down";
        };
    }
}
