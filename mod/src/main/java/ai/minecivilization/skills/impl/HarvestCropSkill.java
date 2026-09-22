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
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop)
                || !crop.isMaxAge(state)) {
            context.fail(new SkillFailure("CROP_NOT_RIPE", "crop is not ripe", true));
            return SkillResult.FAILED;
        }
        if (context.citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 20.0) {
            context.fail(SkillFailure.unreachable("crop out of reach"));
            return SkillResult.FAILED;
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
