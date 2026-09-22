package ai.minecivilization.skills.impl;

import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;

/** Waits for a bounded number of ticks, then completes. Used by REST/idle tasks. */
public final class IdleSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.IDLE;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return true;
    }

    @Override
    public void start(SkillContext context) {
        context.put("until", context.level.getGameTime() + idleTicks(context));
    }

    @Override
    public SkillResult tick(SkillContext context) {
        long until = context.get("until", 0L);
        if (context.level.getGameTime() >= until) {
            return SkillResult.COMPLETED;
        }
        // stop any leftover navigation while resting
        context.navigator.stop();
        return SkillResult.RUNNING;
    }

    private int idleTicks(SkillContext context) {
        String v = context.params.extra.get("idleTicks");
        if (v != null) {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return 100;
    }

    @Override
    public void cancel(SkillContext context) {
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "resting";
    }
}
