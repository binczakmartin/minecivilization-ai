package ai.minecivilization.skills.impl;

import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MAKE_PATH — what a player does with a shovel and a right click: turn the
 * grass into a path.
 *
 * <p>No material, only the shovel's wear, which is what makes it the colony's
 * first road surface: a route walked often enough becomes a trodden path, and
 * a trodden path is faster to walk on (see {@code CitizenEntity}).</p>
 *
 * <p>Params: {@code position} the block to turn.</p>
 */
public final class MakePathSkill implements CitizenSkill {

    private static final double REACH_SQR = 16.0;

    @Override
    public SkillType type() {
        return SkillType.MAKE_PATH;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.position != null;
    }

    @Override
    public void start(SkillContext context) {
    }

    /** Ground a shovel turns into a path. */
    public static boolean turnable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        int[] p = context.params.position;
        BlockPos pos = new BlockPos(p[0], p[1], p[2]);
        BlockState state = context.level.getBlockState(pos);
        if (state.is(Blocks.DIRT_PATH)) return SkillResult.COMPLETED;
        if (!turnable(state) || !context.level.getBlockState(pos.above()).isAir()) {
            context.fail(new SkillFailure("TARGET_CHANGED", "not ground a shovel can turn", true));
            return SkillResult.FAILED;
        }
        int slot = shovelSlot(context.citizen.getInventory());
        if (slot < 0) {
            context.fail(SkillFailure.missing("no shovel to make a path with"));
            return SkillResult.FAILED;
        }
        if (context.citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5) > REACH_SQR) {
            return SkillNavigation.approach(context, pos.above(), REACH_SQR, "path.walk") == SkillResult.FAILED
                    ? SkillResult.FAILED : SkillResult.RUNNING;
        }
        context.navigator.stop();
        if (!context.level.setBlock(pos, Blocks.DIRT_PATH.defaultBlockState(), 3)) {
            context.fail(new SkillFailure("BLOCK_PLACE_FAILED", "the ground did not turn", true));
            return SkillResult.FAILED;
        }
        context.level.playSound(null, pos, SoundEvents.SHOVEL_FLATTEN, SoundSource.BLOCKS, 1.0f, 1.0f);
        context.citizen.animateAction(WorkAnimation.TILL, pos);
        ItemStack shovel = context.citizen.getInventory().get(slot);
        shovel.setDamageValue(shovel.getDamageValue() + 1);
        if (shovel.getDamageValue() >= shovel.getMaxDamage()) {
            context.citizen.getInventory().set(slot, ItemStack.EMPTY);
        }
        context.citizen.getSkills().addXp("building", 0.02f);
        return SkillResult.COMPLETED;
    }

    private static int shovelSlot(CitizenInventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.get(slot).getItem() instanceof ShovelItem) return slot;
        }
        return -1;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "making a path";
    }
}
