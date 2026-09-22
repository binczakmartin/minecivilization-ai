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
import net.minecraft.world.item.ItemStack;

/**
 * Walk to a registered storage container and physically transfer items in.
 * Containers must be within reach — no remote container access.
 */
public final class DepositItemSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.DEPOSIT_ITEM;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return StorageManager.resolve(context.level, context.params.target) != null;
    }

    @Override
    public void start(SkillContext context) {
        StorageNode node = StorageManager.resolve(context.level, context.params.target);
        if (node != null) {
            context.put("node", node);
            context.put("pos", new BlockPos(node.containerX, node.containerY, node.containerZ));
        }
    }

    @Override
    public SkillResult tick(SkillContext context) {
        StorageNode node = context.get("node", (StorageNode) null);
        if (node == null) {
            context.fail(SkillFailure.notFound("no registered storage available"));
            return SkillResult.FAILED;
        }
        BlockPos pos = context.get("pos", (BlockPos) null);
        var be = context.level.getBlockEntity(pos);
        if (!(be instanceof Container container)) {
            context.fail(new SkillFailure("CONTAINER_DESTROYED",
                    "storage container at " + node.storageId + " is gone", true));
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

        // deposit non-damageable stacks (tools stay with the citizen)
        String onlyResource = context.params.resource; // null = everything
        int requested = context.params.quantity;       // -1 = everything matching
        int movedTotal = 0;

        for (int slot = 0; slot < context.citizen.getInventory().items().size(); slot++) {
            ItemStack stack = context.citizen.getInventory().get(slot);
            if (stack.isEmpty() || stack.isDamageableItem()) continue;
            String id = CitizenInventory.idOf(stack);
            if (onlyResource != null && !id.equals(onlyResource)) continue;

            int want = stack.getCount();
            if (requested > 0) want = Math.min(want, requested - movedTotal);

            int moved = insertInto(container, context.citizen.getInventory().items().get(slot), want);
            if (moved > 0) {
                stack.shrink(moved);
                if (stack.isEmpty()) {
                    context.citizen.getInventory().items().set(slot, ItemStack.EMPTY);
                }
                movedTotal += moved;
            }
            if (requested > 0 && movedTotal >= requested) break;
        }

        boolean allDone = requested > 0
                ? movedTotal >= requested
                : !hasDeliverables(context);
        if (allDone) {
            if (movedTotal > 0) {
                StorageManager.markDeposited(context.level, node, movedTotal);
            }
            return SkillResult.COMPLETED;
        }
        if (movedTotal == 0) {
            context.fail(SkillFailure.missing("nothing deliverable (only tools/insufficient space?)"));
            return SkillResult.FAILED;
        }
        return SkillResult.RUNNING;
    }

    private boolean hasDeliverables(SkillContext context) {
        for (ItemStack stack : context.citizen.getInventory().items()) {
            if (!stack.isEmpty() && !stack.isDamageableItem()) return true;
        }
        return false;
    }

    static int insertInto(Container container, ItemStack stack, int limit) {
        if (stack.isEmpty() || limit <= 0) return 0;
        int toMove = Math.min(limit, stack.getCount());
        int moved = 0;
        // merge first
        for (int i = 0; i < container.getContainerSize() && moved < toMove; i++) {
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(existing, stack)) continue;
            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) continue;
            int m = Math.min(space, toMove - moved);
            existing.grow(m);
            container.setChanged();
            moved += m;
        }
        // then empty slots
        for (int i = 0; i < container.getContainerSize() && moved < toMove; i++) {
            if (!container.getItem(i).isEmpty()) continue;
            int m = Math.min(stack.getMaxStackSize(), toMove - moved);
            ItemStack insert = stack.copy();
            insert.setCount(m);
            container.setItem(i, insert);
            container.setChanged();
            moved += m;
        }
        return moved;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        StorageNode node = context.get("node", (StorageNode) null);
        return node == null ? "depositing" : "depositing at " + node.storageId;
    }
}
