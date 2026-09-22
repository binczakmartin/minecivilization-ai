package ai.minecivilization.skills.impl;

import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;

/**
 * MINE_AREA: mine N blocks of the target resource — a composite
 * find → move → mine → pickup loop running entirely deterministically.
 */
public final class MineAreaSkill implements CitizenSkill {
    private final FindBlockSkill find = new FindBlockSkill();
    private final MoveToSkill move = new MoveToSkill();
    private final MineBlockSkill mine = new MineBlockSkill();
    private final PickupItemSkill pickup = new PickupItemSkill();

    private enum Phase { FIND, MOVE, MINE, PICKUP }

    @Override
    public SkillType type() {
        return SkillType.MINE_AREA;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.resource != null || context.params.block != null;
    }

    @Override
    public void start(SkillContext context) {
        context.put("phase", Phase.FIND);
        context.put("mined", 0);
        context.params.position = null;
        find.start(context);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        int targetQty = context.params.quantity > 0 ? context.params.quantity : 1;
        Integer mined = context.get("mined", 0);
        if (mined >= targetQty) {
            return SkillResult.COMPLETED;
        }
        Phase phase = context.get("phase", Phase.FIND);

        switch (phase) {
            case FIND -> {
                SkillResult r = find.tick(context);
                if (r == SkillResult.COMPLETED) {
                    context.put("phase", Phase.MOVE);
                    move.start(context);
                } else if (r == SkillResult.FAILED) {
                    if (mined > 0) return SkillResult.COMPLETED; // partial success is fine
                    return r;
                }
            }
            case MOVE -> {
                SkillResult r = move.tick(context);
                if (r == SkillResult.COMPLETED) {
                    context.put("phase", Phase.MINE);
                    mine.start(context);
                    if (context.failure != null) {
                        // block vanished while walking: re-find
                        context.failure = null;
                        context.put("phase", Phase.FIND);
                        find.start(context);
                    }
                } else if (r == SkillResult.FAILED) {
                    return r;
                }
            }
            case MINE -> {
                SkillResult r = mine.tick(context);
                if (r == SkillResult.COMPLETED) {
                    context.put("phase", Phase.PICKUP);
                    pickup.start(context);
                } else if (r == SkillResult.FAILED) {
                    if (context.failure != null
                            && ("BLOCK_ALREADY_MINED".equals(context.failure.code))) {
                        context.failure = null;
                        context.put("phase", Phase.FIND);
                        find.start(context);
                    } else {
                        return r;
                    }
                }
            }
            case PICKUP -> {
                SkillResult r = pickup.tick(context);
                if (r == SkillResult.COMPLETED || r == SkillResult.FAILED) {
                    // drops collected (or already gone): count as one mined unit
                    mined++;
                    context.put("mined", mined);
                    context.put("phase", Phase.FIND);
                    context.failure = null;
                    context.params.position = null;
                    find.start(context);
                }
            }
        }
        return SkillResult.RUNNING;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "mining area " + context.get("mined", 0) + "/" + context.params.quantity;
    }
}
