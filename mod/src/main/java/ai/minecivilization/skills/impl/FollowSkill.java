package ai.minecivilization.skills.impl;

import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;

/** Follow another citizen by name (used for social tasks later). */
public final class FollowSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.FOLLOW;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.target != null;
    }

    @Override
    public void start(SkillContext context) {
    }

    @Override
    public SkillResult tick(SkillContext context) {
        var target = context.level.getNearestPlayer(
                context.citizen, 32);
        // resolve by exact name match among loaded players/citizens
        var entities = context.level.getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class,
                context.citizen.getBoundingBox().inflate(32),
                e -> e != context.citizen
                        && e.getName().getString().equals(context.params.target));
        if (entities.isEmpty()) {
            context.fail(SkillFailure.notFound("cannot see " + context.params.target));
            return SkillResult.FAILED;
        }
        var other = entities.get(0);
        if (context.citizen.distanceToSqr(other) < 4.0) {
            context.navigator.stop();
            return SkillResult.COMPLETED;
        }
        context.navigator.moveTo(other, 1.0);
        context.navigator.tick();
        if (context.navigator.hasFailed()) {
            context.fail(context.navigator.failure());
            return SkillResult.FAILED;
        }
        return SkillResult.RUNNING;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "following " + context.params.target;
    }
}
