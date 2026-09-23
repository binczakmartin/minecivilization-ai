package ai.minecivilization.skills.impl;

import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;

/**
 * Break a ripe crop at params.position so it drops its produce.
 */
public final class HarvestCropSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.HARVEST_CROP;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.position != null;
    }

    @Override
    public void start(SkillContext context) {
        int[] p = context.params.position;
        context.put("pos", new BlockPos(p[0], p[1], p[2]));
    }

    @Override
    public SkillResult tick(SkillContext context) {
        BlockPos pos = context.get("pos", (BlockPos) null);
        if (pos == null) {
            context.fail(SkillFailure.notFound("no crop position"));
            return SkillResult.FAILED;
        }
        var state = context.level.getBlockState(pos);
        if (state.isAir()) {
            context.fail(new SkillFailure("CROP_GONE", "crop already harvested", true));
            return SkillResult.FAILED;
        }
        if (!ai.minecivilization.farming.Crops.ripe(state, context.level, pos)) {
            context.fail(new SkillFailure("CROP_NOT_RIPE", "crop is not ripe", true));
            return SkillResult.FAILED;
        }
        if (context.citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 20.0) {
            context.fail(SkillFailure.unreachable("crop out of reach"));
            return SkillResult.FAILED;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)) {
            if (!context.level.setBlock(pos, state.setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1), 3)) {
                context.fail(SkillFailure.missing("could not harvest berries")); return SkillResult.FAILED;
            }
            net.minecraft.world.level.block.Block.popResource(context.level, pos,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SWEET_BERRIES,
                    1 + context.level.random.nextInt(2) + (state.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE) == 3 ? 1 : 0)));
            return SkillResult.COMPLETED;
        }
        boolean dropped = context.level.destroyBlock(pos, true, context.citizen, 0);
        if (!dropped) {
            context.fail(new SkillFailure("HARVEST_FAILED", "crop dropped nothing", true));
            return SkillResult.FAILED;
        }
        context.citizen.getSkills().addXp("farming", 0.05f);
        return SkillResult.COMPLETED;
    }

    @Override
    public void cancel(SkillContext context) {
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "harvesting";
    }
}
