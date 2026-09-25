package ai.minecivilization.skills.impl;

import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import ai.minecivilization.storage.SettlementStock;
import ai.minecivilization.storage.StorageManager;
import ai.minecivilization.storage.StorageNode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Withdraw specific items from a registered storage container, physically,
 * while standing next to it.
 */
public final class WithdrawItemSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.WITHDRAW_ITEM;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.resource != null && resolveSource(context) != null;
    }

    /**
     * Which container to walk to. An explicit storage id wins; otherwise the
     * citizen picks the nearest one that actually holds the item right now —
     * which is what makes a withdrawal usable inside a craft plan, where the
     * planner knows the colony owns the item but not which chest it sits in.
     */
    private static StorageNode resolveSource(SkillContext context) {
        StorageNode named = StorageManager.resolve(context.level, context.params.target);
        if (named != null && canAccess(context, named)) return named;
        int wanted = context.params.quantity > 0 ? context.params.quantity : 1;
        return SettlementStock.locate(context.level, context.citizen.blockPosition(),
                context.params.resource, wanted);
    }

    private static boolean canAccess(SkillContext context, StorageNode node) {
        if (node == null) return false;
        if (node.isPublic()) return true;
        String owner = node.ownerId;
        return owner != null && owner.equals(
                context.citizen.getIdentity().citizenId.toString());
    }

    @Override
    public void start(SkillContext context) {
        StorageNode node = resolveSource(context);
        if (node != null) {
            context.put("node", node);
            context.put("pos", new BlockPos(node.containerX, node.containerY, node.containerZ));
            context.put("withdrawn", 0);
        }
    }

    @Override
    public SkillResult tick(SkillContext context) {
        StorageNode node = context.get("node", (StorageNode) null);
        if (node == null || !canAccess(context, node)) {
            context.fail(SkillFailure.notFound("no accessible registered storage"));
            return SkillResult.FAILED;
        }
        BlockPos pos = context.get("pos", (BlockPos) null);
        var be = context.level.getBlockEntity(pos);
        if (!(be instanceof Container container)) {
            context.fail(new SkillFailure("CONTAINER_DESTROYED",
                    "storage container missing", true));
            return SkillResult.FAILED;
        }

        double distSqr = context.citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSqr > 12.0) {
            // Walk there, and make a way if walking will not do.
            SkillResult arrival = SkillNavigation.approach(context, pos, 12.0, "withdraw.walk");
            if (arrival == SkillResult.FAILED) return SkillResult.FAILED;
            if (arrival == SkillResult.RUNNING) return SkillResult.RUNNING;
        }
        context.navigator.stop();

        String itemId = context.params.resource;
        int wanted = context.params.quantity > 0 ? context.params.quantity : 1;
        int withdrawn = context.get("withdrawn", 0);

        for (int i = 0; i < container.getContainerSize() && withdrawn < wanted; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || !CitizenInventory.idOf(stack).equals(itemId)) continue;
            int take = Math.min(stack.getCount(), wanted - withdrawn);
            ItemStack extracted = stack.copy();
            extracted.setCount(take);
            int leftover = context.citizen.getInventory().insert(extracted);
            int accepted = take - leftover;
            if (accepted > 0) {
                context.citizen.animateAction(WorkAnimation.REACH, pos);
                stack.shrink(accepted);
                if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                container.setChanged();
                withdrawn += accepted;
            }
            if (leftover > 0) break; // inventory full
        }
        context.put("withdrawn", withdrawn);

        if (withdrawn >= wanted) {
            return SkillResult.COMPLETED;
        }
        if (withdrawn == 0) {
            // Someone emptied this chest since the plan was made. Try the next
            // container holding the item before reporting a shortage.
            StorageNode elsewhere = SettlementStock.locate(context.level,
                    context.citizen.blockPosition(), itemId, wanted - withdrawn);
            if (elsewhere != null && !elsewhere.storageId.equals(node.storageId)) {
                context.put("node", elsewhere);
                context.put("pos", elsewhere.containerPos());
                context.startGameTime = context.level.getGameTime();
                return SkillResult.RUNNING;
            }
            context.fail(SkillFailure.missing(
                    "the settlement has no " + itemId + " left in storage"));
            return SkillResult.FAILED;
        }
        // inventory full with partial withdrawal
        context.fail(new SkillFailure("INVENTORY_FULL",
                "could only withdraw " + withdrawn + "/" + wanted, true));
        return SkillResult.FAILED;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "withdrawing " + context.get("withdrawn", 0) + "/" + context.params.quantity;
    }
}
