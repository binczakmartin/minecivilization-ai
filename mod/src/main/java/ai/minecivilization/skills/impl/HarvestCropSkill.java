package ai.minecivilization.skills.impl;

import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

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
        if (ai.minecivilization.construction.ConstructionManager.get(context.level)
                .protectsCell(pos)) {
            context.fail(new SkillFailure("PROTECTED_BLOCK",
                    "crop belongs to a colony construction footprint", true));
            return SkillResult.FAILED;
        }
        // Someone standing in the wheat does not stop it being cut.
        if (state.isAir()) {
            context.fail(new SkillFailure("CROP_GONE", "crop already harvested", true));
            return SkillResult.FAILED;
        }
        if (!ai.minecivilization.farming.Crops.ripe(state, context.level, pos)) {
            context.fail(new SkillFailure("CROP_NOT_RIPE", "crop is not ripe", true));
            return SkillResult.FAILED;
        }
        if (context.citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 20.0) {
            // Walk the last few blocks: the move before this aimed at the crop
            // cell itself, which is not a place to stand, and stopped short.
            SkillResult arrival = SkillNavigation.approach(context, pos, 16.0, "harvest.walk");
            if (arrival != SkillResult.FAILED) return SkillResult.RUNNING;
            context.fail(SkillFailure.unreachable("crop out of reach"));
            return SkillResult.FAILED;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)) {
            BlockState harvested = state.setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1);
            if (!context.level.setBlock(pos, harvested, 3)
                    || !context.level.getBlockState(pos).equals(harvested)) {
                context.fail(SkillFailure.missing("could not harvest berries")); return SkillResult.FAILED;
            }
            net.minecraft.world.level.block.Block.popResource(context.level, pos,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SWEET_BERRIES,
                    1 + context.level.random.nextInt(2) + (state.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE) == 3 ? 1 : 0)));
            context.citizen.animateAction(WorkAnimation.HARVEST, pos);
            return SkillResult.COMPLETED;
        }
        boolean dropped = context.level.destroyBlock(pos, true, context.citizen, 0);
        if (!dropped) {
            context.fail(new SkillFailure("HARVEST_FAILED", "crop dropped nothing", true));
            return SkillResult.FAILED;
        }
        context.citizen.animateAction(WorkAnimation.HARVEST, pos);
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
