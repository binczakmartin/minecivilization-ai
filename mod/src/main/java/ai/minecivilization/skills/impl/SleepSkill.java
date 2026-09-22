package ai.minecivilization.skills.impl;

import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;

/**
 * SLEEP — V1: bounded rest (like idle). Real bed sleeping comes with the
 * day/night cycle milestone.
 */
public final class SleepSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.SLEEP;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return true;
    }

    @Override
    public void start(SkillContext context) {
        context.put("until", context.level.getGameTime() + 200);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        context.navigator.stop();
        return context.level.getGameTime() >= context.get("until", 0L)
                ? SkillResult.COMPLETED : SkillResult.RUNNING;
    }

    @Override
    public void cancel(SkillContext context) {
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "sleeping";
    }
}
