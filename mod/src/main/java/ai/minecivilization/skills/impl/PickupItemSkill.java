package ai.minecivilization.skills.impl;

import java.util.List;

import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Walk to nearby dropped items and physically pick them up into the
 * citizen's inventory (partial inserts leave the remainder on the ground).
 */
public final class PickupItemSkill implements CitizenSkill {
    private static final double RADIUS = 14.0;

    @Override
    public SkillType type() {
        return SkillType.PICKUP_ITEM;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return true;
    }

    @Override
    public void start(SkillContext context) {
        context.put("pickedUp", 0);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        ItemEntity nearest = null;
        double best = RADIUS * RADIUS;
        AABB box = context.citizen.getBoundingBox().inflate(RADIUS);
        List<ItemEntity> items = context.level.getEntitiesOfClass(ItemEntity.class, box);
        for (ItemEntity item : items) {
            double d = item.distanceToSqr(context.citizen);
            if (d < best) {
                best = d;
                nearest = item;
            }
        }

        if (nearest == null) {
            // after mining, drops exist immediately; if nothing is around, we are done
            // only if we already picked something or nothing was expected
            Integer picked = context.get("pickedUp", 0);
            if (picked > 0) return SkillResult.COMPLETED;
            context.fail(SkillFailure.notFound("no dropped items within " + (int) RADIUS + " blocks"));
            return SkillResult.FAILED;
        }

        double distSqr = nearest.distanceToSqr(context.citizen);
        if (distSqr > 5.0) {
            context.navigator.moveTo(nearest, 1.0);
            context.navigator.tick();
            if (context.navigator.hasFailed()) {
                context.fail(context.navigator.failure());
                return SkillResult.FAILED;
            }
            return SkillResult.RUNNING;
        }

        // physically transfer
        ItemStack stack = nearest.getItem();
        int leftover = context.citizen.getInventory().insert(stack);
        int moved = stack.getCount() - leftover;
        if (leftover <= 0) {
            nearest.discard();
        } else {
            stack.setCount(leftover);
            nearest.setItem(stack);
        }
        if (moved > 0) {
            Integer picked = context.get("pickedUp", 0);
            context.put("pickedUp", picked + moved);
        }

        // keep collecting until nothing remains nearby
        return SkillResult.RUNNING;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "collecting (" + context.get("pickedUp", 0) + " items)";
    }
}
