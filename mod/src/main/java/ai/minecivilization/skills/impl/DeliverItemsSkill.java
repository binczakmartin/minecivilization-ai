package ai.minecivilization.skills.impl;

import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import ai.minecivilization.storage.StorageManager;
import ai.minecivilization.storage.StorageNode;
import net.minecraft.core.BlockPos;

/**
 * DELIVER_ITEMS: find the target registered storage (explicit id or nearest),
 * walk there, deposit all non-tool items. Delegates the transfer to
 * {@link DepositItemSkill} once in reach.
 */
public final class DeliverItemsSkill implements CitizenSkill {
    private DepositItemSkill deposit = new DepositItemSkill();

    @Override
    public SkillType type() {
        return SkillType.DELIVER_ITEMS;
    }

    @Override
    public boolean canStart(SkillContext context) {
        StorageNode node = StorageManager.resolve(context.level, context.params.target);
        if (node == null) {
            node = StorageManager.nearest(context.level, context.citizen.blockPosition());
        }
        return node != null;
    }

    @Override
    public void start(SkillContext context) {
        StorageNode node = StorageManager.resolve(context.level, context.params.target);
        if (node == null) {
            node = StorageManager.nearest(context.level, context.citizen.blockPosition());
        }
        if (node != null) {
            context.put("node", node);
            // deposit skill resolves the same node via params.target
            context.params.target = node.storageId;
            deposit.start(context);
        }
    }

    @Override
    public SkillResult tick(SkillContext context) {
        StorageNode node = context.get("node", (StorageNode) null);
        if (node == null) {
            context.fail(SkillFailure.notFound("no registered storage to deliver to"));
            return SkillResult.FAILED;
        }

        // nothing deliverable at all -> nothing to do
        boolean any = false;
        for (var stack : context.citizen.getInventory().items()) {
            if (!stack.isEmpty() && !stack.isDamageableItem()) {
                any = true;
                break;
            }
        }
        if (!any) {
            return SkillResult.COMPLETED;
        }

        BlockPos pos = new BlockPos(node.containerX, node.containerY, node.containerZ);
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
        return deposit.tick(context);
    }

    @Override
    public void cancel(SkillContext context) {
        deposit.cancel(context);
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        StorageNode node = context.get("node", (StorageNode) null);
        return node == null ? "delivering" : "delivering to " + node.storageId;
    }
}
