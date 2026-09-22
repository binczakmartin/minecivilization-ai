package ai.minecivilization.skills.impl;

import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
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
        return context.params.resource != null
                && StorageManager.resolve(context.level, context.params.target) != null;
    }

    @Override
    public void start(SkillContext context) {
        StorageNode node = StorageManager.resolve(context.level, context.params.target);
        if (node != null) {
            context.put("node", node);
            context.put("pos", new BlockPos(node.containerX, node.containerY, node.containerZ));
            context.put("withdrawn", 0);
        }
    }

    @Override
    public SkillResult tick(SkillContext context) {
        StorageNode node = context.get("node", (StorageNode) null);
        if (node == null) {
            context.fail(SkillFailure.notFound("no registered storage"));
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
            context.navigator.moveTo(pos, 1.0);
            context.navigator.tick();
            if (context.navigator.hasFailed()) {
                context.fail(context.navigator.failure());
                return SkillResult.FAILED;
            }
            return SkillResult.RUNNING;
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
            context.fail(SkillFailure.missing(
                    "storage " + node.storageId + " has no " + itemId));
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
