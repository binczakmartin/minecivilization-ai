package ai.minecivilization.skills.impl;

import ai.minecivilization.colony.Zone;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.colony.ZoneType;
import ai.minecivilization.livestock.AnimalHusbandry;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/**
 * HUNT — kill an animal for meat, when there is nothing else left to eat.
 *
 * <p>A colony should not live by hunting: an animal led home breeds and feeds
 * the settlement for good, while one killed in a field feeds it once. So this
 * is a last resort, gated by the decision policy on there being no bread, no
 * wheat and nothing in the larder.</p>
 *
 * <p>Two rules keep it from eating the colony's future. Animals standing inside
 * the pasture are <b>never</b> hunted — that is breeding stock, and killing it
 * to get through one hungry evening costs every meal it would have produced.
 * And the wild animal chosen is the one furthest from the pasture, so the herd
 * that has not been penned yet is left alone as long as anything else is
 * available.</p>
 */
public final class HuntSkill implements CitizenSkill {

    /** How far a starving citizen will range for a meal. */
    private static final double SEARCH_RADIUS = 48.0;
    /** Melee reach, squared. */
    private static final double REACH_SQR = 6.0;
    /** Ticks between blows. */
    private static final int ATTACK_INTERVAL = 16;

    private Animal quarry;
    private long nextAttackAt;

    @Override
    public SkillType type() {
        return SkillType.HUNT;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return true;
    }

    @Override
    public void start(SkillContext context) {
        quarry = null;
        nextAttackAt = 0;
    }

    @Override
    public SkillResult tick(SkillContext context) {
        if (quarry != null && !quarry.isAlive()) {
            collectMeat(context);
            return SkillResult.COMPLETED;
        }
        if (quarry == null) {
            quarry = findQuarry(context);
            if (quarry == null) {
                context.fail(SkillFailure.notFound(
                        "no wild animal to hunt within " + (int) SEARCH_RADIUS + " blocks"));
                return SkillResult.FAILED;
            }
        }

        double distSqr = context.citizen.distanceToSqr(quarry);
        if (distSqr > REACH_SQR) {
            context.navigator.moveTo(quarry, 1.1);
            context.navigator.tick();
            if (context.navigator.hasFailed()) {
                context.navigator.stop();
                quarry = null;   // pick another rather than fail the job
            }
            return SkillResult.RUNNING;
        }
        context.navigator.stop();
        context.citizen.getLookControl().setLookAt(quarry);

        long now = context.level.getGameTime();
        if (now >= nextAttackAt) {
            context.citizen.swing(InteractionHand.MAIN_HAND);
            context.citizen.doHurtTarget(quarry);
            nextAttackAt = now + ATTACK_INTERVAL;
            context.startGameTime = now;
        }
        if (!quarry.isAlive()) {
            collectMeat(context);
            return SkillResult.COMPLETED;
        }
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ choosing

    /**
     * A wild animal, never one from the pasture, and the one furthest from the
     * pasture where there is a choice — the unpenned herd nearby is tomorrow's
     * breeding stock.
     */
    private Animal findQuarry(SkillContext context) {
        Zone pasture = ZoneManager.get(context.level)
                .nearest(ZoneType.PASTURE, context.citizen.blockPosition());
        AABB box = context.citizen.getBoundingBox().inflate(SEARCH_RADIUS);

        Animal best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Animal candidate : context.level.getEntitiesOfClass(Animal.class, box)) {
            if (!candidate.isAlive() || candidate.isBaby() || candidate.hasCustomName() || candidate.isLeashed()
                    || ai.minecivilization.livestock.HerdRegistry.owned(candidate)) continue;
            String type = HerdAnimalSkill.typeOf(candidate);
            if (!AnimalHusbandry.isLivestock(type)) continue;

            // Breeding stock is not food. Killing it to get through one hungry
            // evening costs every meal it would have produced.
            if (pasture != null && pasture.contains(
                    candidate.blockPosition().getX(), candidate.blockPosition().getZ())) {
                continue;
            }

            double fromPasture = pasture == null ? 0
                    : pasture.distanceSqrTo(candidate.blockPosition());
            double fromHere = context.citizen.distanceToSqr(candidate);
            // Prefer far from the pasture, then near to us.
            double score = fromPasture - fromHere * 0.25;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /** Pick up what the kill dropped. */
    private void collectMeat(SkillContext context) {
        var inventory = context.citizen.getInventory();
        AABB box = context.citizen.getBoundingBox().inflate(6.0);
        for (ItemEntity item : context.level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (!item.isAlive() || item.getItem().isEmpty()) continue;
            int leftover = inventory.insert(item.getItem().copy());
            if (leftover <= 0) {
                item.discard();
            } else {
                var stack = item.getItem();
                stack.setCount(leftover);
                item.setItem(stack);
            }
        }
        context.citizen.getSkills().addXp("farming", 0.03f);
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
        quarry = null;
    }

    @Override
    public String progressLabel(SkillContext context) {
        if (quarry == null) return "looking for game";
        String type = HerdAnimalSkill.typeOf(quarry);
        return "hunting " + type.substring(type.indexOf(':') + 1);
    }
}
