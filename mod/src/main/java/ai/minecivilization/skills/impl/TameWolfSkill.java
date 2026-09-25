package ai.minecivilization.skills.impl;

import ai.minecivilization.config.ModConfig;
import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.livestock.*;
import ai.minecivilization.skills.*;
import net.minecraft.world.entity.animal.Wolf;

/** Wild wolves only, real bones and vanilla's one-in-three taming chance. */
public final class TameWolfSkill implements CitizenSkill {
    private Wolf wolf;
    private long nextOffer;
    public SkillType type() { return SkillType.TAME_WOLF; }
    public boolean canStart(SkillContext c) { return true; }
    public void start(SkillContext c) { wolf = null; }
    public SkillResult tick(SkillContext c) {
        var registry = HerdRegistry.get(c.level);
        if (!HerdPolicy.canRecruit(registry.committed("minecraft:wolf", c.level.getGameTime()), ModConfig.WOLF_LIMIT.get())) return SkillResult.COMPLETED;
        if (wolf == null) {
            wolf = c.level.getEntitiesOfClass(Wolf.class, c.citizen.getBoundingBox().inflate(40),
                w -> w.isAlive() && !w.isTame() && !w.isAngry() && !w.isBaby() && !w.isLeashed() && !w.hasCustomName())
                .stream().min(java.util.Comparator.comparingDouble(c.citizen::distanceToSqr)).orElse(null);
        }
        if (wolf == null) { c.fail(SkillFailure.notFound("no wild wolf nearby")); return SkillResult.FAILED; }
        if (!wolf.isAlive() || wolf.isTame() || wolf.isAngry() || wolf.isLeashed()) return SkillResult.COMPLETED;
        if (c.citizen.distanceToSqr(wolf) > 9) {
            c.citizen.clearWorkAnimation();
            c.navigator.moveTo(wolf, 1); c.navigator.tick();
            if (c.navigator.hasFailed()) { c.fail(c.navigator.failure()); return SkillResult.FAILED; }
            return SkillResult.RUNNING;
        }
        c.navigator.stop();
        if (c.level.getGameTime() < nextOffer) {
            c.citizen.clearWorkAnimation();
            return SkillResult.RUNNING;
        }
        if (!c.citizen.getInventory().containsAtLeast("minecraft:bone", 1)) {
            c.fail(SkillFailure.missing("wolf taming needs bones")); return SkillResult.FAILED;
        }
        c.citizen.animateAction(WorkAnimation.REACH, wolf.blockPosition());
        c.citizen.getInventory().extract("minecraft:bone", 1); nextOffer = c.level.getGameTime() + 20;
        if (c.level.random.nextInt(3) != 0) { c.level.broadcastEntityEvent(wolf, (byte) 6); return SkillResult.RUNNING; }
        wolf.setTame(true, true); wolf.setOwnerUUID(c.citizen.getUUID()); wolf.setOrderedToSit(false);
        wolf.setHealth(wolf.getMaxHealth());
        registry.register(wolf); ColonyWolves.attach(wolf);
        c.level.broadcastEntityEvent(wolf, (byte) 7);
        return SkillResult.COMPLETED;
    }
    public void cancel(SkillContext c) { c.navigator.stop(); }
    public String progressLabel(SkillContext c) { return "taming a colony guard wolf"; }
}
