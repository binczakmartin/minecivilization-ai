package ai.minecivilization.skills.impl;

import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;

/**
 * Move to params.position (or block target). Recovery (repath/timeout/
 * unreachable) is delegated to the deterministic navigator.
 */
public final class MoveToSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.MOVE_TO;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.position != null;
    }

    @Override
    public void start(SkillContext context) {
        int[] p = context.params.position;
        BlockPos pos = new BlockPos(p[0], p[1], p[2]);
        context.put("target", pos);
        context.navigator.moveTo(pos, 1.0);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        context.navigator.tick();
        if (context.navigator.hasFailed()) {
            context.fail(context.navigator.failure());
            return SkillResult.FAILED;
        }
        if (context.navigator.isInRange() && !context.navigator.isMoving()) {
            return SkillResult.COMPLETED;
        }
        return SkillResult.RUNNING;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "moving to " + context.params.position[0] + ","
                + context.params.position[1] + "," + context.params.position[2];
    }
}
