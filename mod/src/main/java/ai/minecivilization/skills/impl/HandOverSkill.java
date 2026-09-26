package ai.minecivilization.skills.impl;

import java.util.UUID;

import ai.minecivilization.construction.WoodSwap;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import ai.minecivilization.work.MaterialRequests;
import net.minecraft.world.item.ItemStack;

/**
 * HAND_OVER — take what somebody asked for to wherever they are, and put it in
 * their hands.
 *
 * <p>The courier's half of {@link MaterialRequests}: it follows the worker (a
 * builder moves along its wall while it waits) and, within arm's reach, moves
 * the items from its own pack into the other's. Any species of a wooden item
 * will do, the same as on the building site.</p>
 *
 * <p>Params: {@code target} the recipient's UUID, {@code resource} the item,
 * {@code quantity} how many.</p>
 */
public final class HandOverSkill implements CitizenSkill {

    private static final double HAND_REACH_SQR = 9.0;
    private static final int REPATH_TICKS = 20;

    private long lastRepathAt;

    @Override
    public SkillType type() {
        return SkillType.HAND_OVER;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.target != null && context.params.resource != null;
    }

    @Override
    public void start(SkillContext context) {
        lastRepathAt = Long.MIN_VALUE / 2;
    }

    @Override
    public SkillResult tick(SkillContext context) {
        UUID requester;
        try {
            requester = UUID.fromString(context.params.target);
        } catch (IllegalArgumentException ex) {
            context.fail(new SkillFailure("INVALID_TASK", "hand-over without a recipient", false));
            return SkillResult.FAILED;
        }
        String item = context.params.resource;
        if (!(context.level.getEntity(requester) instanceof CitizenEntity recipient) || !recipient.isAlive()) {
            MaterialRequests.close(requester, item);
            context.fail(SkillFailure.notFound("the citizen who asked is gone"));
            return SkillResult.FAILED;
        }
        CitizenInventory mine = context.citizen.getInventory();
        if (carried(mine, item) <= 0) {
            MaterialRequests.release(context.citizen.getUUID());
            context.fail(SkillFailure.missing("nothing to hand over: no " + item + " in the pack"));
            return SkillResult.FAILED;
        }

        long now = context.level.getGameTime();
        if (context.citizen.distanceToSqr(recipient) > HAND_REACH_SQR) {
            // The recipient keeps working and moving: follow it.
            if (now - lastRepathAt >= REPATH_TICKS) {
                context.navigator.stop();
                context.navigator.moveTo(recipient, 1.1);
                lastRepathAt = now;
            }
            context.navigator.tick();
            if (context.navigator.hasFailed()) {
                MaterialRequests.release(context.citizen.getUUID());
                context.fail(SkillFailure.unreachable("cannot get to " + recipient.getName().getString()));
                return SkillResult.FAILED;
            }
            context.startGameTime = now;   // walking to a moving target is progress
            return SkillResult.RUNNING;
        }

        context.navigator.stop();
        int wanted = context.params.quantity > 0 ? context.params.quantity : 1;
        int given = 0;
        for (String variant : WoodSwap.variants(item)) {
            while (given < wanted && mine.count(variant) > 0) {
                int batch = Math.min(wanted - given, mine.count(variant));
                ItemStack stack = new ItemStack(CitizenInventory.itemById(variant), batch);
                int leftover = recipient.getInventory().insert(stack);
                int moved = batch - leftover;
                if (moved <= 0) break;
                mine.extract(variant, moved);
                given += moved;
            }
        }
        if (given <= 0) {
            MaterialRequests.release(context.citizen.getUUID());
            context.fail(new SkillFailure("INVENTORY_FULL",
                    recipient.getName().getString() + " has no room", true));
            return SkillResult.FAILED;
        }
        context.citizen.animateAction(WorkAnimation.REACH, recipient.blockPosition());
        context.citizen.onInventoryChanged();
        recipient.onInventoryChanged();
        MaterialRequests.close(requester, item);
        recipient.getCitizenBrain().addEvent("received " + given + " " + shortName(item)
                + " from " + context.citizen.getIdentity().name);
        return SkillResult.COMPLETED;
    }

    private static int carried(CitizenInventory inventory, String item) {
        int total = 0;
        for (String variant : WoodSwap.variants(item)) total += inventory.count(variant);
        return total;
    }

    private static String shortName(String id) {
        return id.substring(id.indexOf(':') + 1).replace('_', ' ');
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "bringing " + (context.params.resource == null ? "materials"
                : shortName(context.params.resource)) + " to a co-worker";
    }
}
