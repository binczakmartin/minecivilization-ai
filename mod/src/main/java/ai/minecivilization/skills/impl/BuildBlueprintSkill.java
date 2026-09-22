package ai.minecivilization.skills.impl;

import java.util.Optional;

import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import ai.minecivilization.storage.StorageManager;
import ai.minecivilization.storage.StorageNode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * BUILD_BLUEPRINT — advance a construction project block by block:
 * find next required block → check inventory → (missing: fail
 * MISSING_MATERIALS so cognition can arrange gathering/withdrawal) →
 * navigate → validate support → place (consuming the item) → repeat.
 *
 * <p>NO creative pasting. Every placed block comes from the citizen's
 * inventory.</p>
 */
public final class BuildBlueprintSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.BUILD_BLUEPRINT;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return true;
    }

    @Override
    public void start(SkillContext context) {
        context.put("checkedStorage", false);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        ConstructionProject project = ConstructionManager.resolve(
                context.level, context.params.projectId, context.citizen.blockPosition());
        if (project == null) {
            context.fail(SkillFailure.notFound("no active construction project"));
            return SkillResult.FAILED;
        }
        ConstructionManager manager = ConstructionManager.get(context.level);
        var blueprint = ConstructionManager.blueprint(project.blueprintId);
        if (blueprint == null) {
            context.fail(new SkillFailure("UNKNOWN_BLUEPRINT",
                    "blueprint " + project.blueprintId + " missing", false));
            return SkillResult.FAILED;
        }

        if (project.isFinished(blueprint)) {
            project.status = ConstructionProject.Status.COMPLETED;
            manager.setDirty();
            return SkillResult.COMPLETED;
        }

        Optional<ConstructionManager.NextStep> step = manager.nextStep(context.level, project);
        if (step.isEmpty()) {
            context.fail(new SkillFailure("PROJECT_BLOCKED",
                    "all remaining blocks lack support", true));
            return SkillResult.FAILED;
        }
        ConstructionManager.NextStep next = step.get();
        if (next.unsupported) {
            context.fail(new SkillFailure("UNSUPPORTED_BLOCK", next.detail, true));
            return SkillResult.FAILED;
        }

        BlockState state = ConstructionManager.parseState(context.level, next.blockState);
        if (state == null) {
            context.fail(new SkillFailure("INVALID_BLOCK_STATE", next.blockState, false));
            return SkillResult.FAILED;
        }
        Item blockItem = net.minecraft.world.item.BlockItem.byBlock(state.getBlock());
        if (blockItem == net.minecraft.world.item.Items.AIR) {
            context.fail(new SkillFailure("INVALID_BLOCK_STATE",
                    "no item for " + next.blockState, false));
            return SkillResult.FAILED;
        }
        String itemId = CitizenInventory.idOf(new ItemStack(blockItem));

        // missing materials: try to withdraw from nearest registered storage once
        if (!context.citizen.getInventory().containsAtLeast(itemId, 1)) {
            boolean withdrew = tryWithdrawFromStorage(context, itemId);
            if (!withdrew || !context.citizen.getInventory().containsAtLeast(itemId, 1)) {
                project.status = ConstructionProject.Status.WAITING_FOR_RESOURCES;
                manager.setDirty();
                context.fail(SkillFailure.missing(
                        "need " + itemId + " for " + project.name));
                return SkillResult.FAILED;
            }
        }

        project.status = ConstructionProject.Status.BUILDING;
        manager.setDirty();

        double distSqr = context.citizen.distanceToSqr(
                next.pos.getX() + 0.5, next.pos.getY() + 0.5, next.pos.getZ() + 0.5);
        if (distSqr > 25.0) {
            context.navigator.moveTo(next.pos, 1.0);
            context.navigator.tick();
            if (context.navigator.hasFailed()) {
                context.fail(context.navigator.failure());
                return SkillResult.FAILED;
            }
            // remember we are walking; next tick will place when close
            context.put("pendingProject", project.id);
            return SkillResult.RUNNING;
        }
        context.navigator.stop();

        // physically place: consume exactly one item
        context.citizen.getInventory().extract(itemId, 1);
        context.level.setBlock(next.pos, state, 3);
        project.placed.add(project.key(next.pos.getX(), next.pos.getY(), next.pos.getZ()));
        context.citizen.getSkills().addXp("building", 0.05f);
        context.citizen.onBlockPlaced(itemId);
        manager.setDirty();
        // each placed block resets the per-attempt timeout window: a long build
        // is a sequence of healthy steps, not a hung skill
        context.startGameTime = context.level.getGameTime();

        context.citizen.onProjectProgress(project);
        if (project.isFinished(blueprint)) {
            project.status = ConstructionProject.Status.COMPLETED;
            manager.setDirty();
            context.citizen.onProjectCompleted(project);
            return SkillResult.COMPLETED;
        }
        return SkillResult.RUNNING;
    }

    private boolean tryWithdrawFromStorage(SkillContext context, String itemId) {
        StorageNode storage = StorageManager.nearest(context.level, context.citizen.blockPosition());
        if (storage == null) return false;
        var be = context.level.getBlockEntity(storage.containerPos());
        if (!(be instanceof net.minecraft.world.Container container)) return false;

        double distSqr = context.citizen.distanceToSqr(storage.containerPos().getX() + 0.5,
                storage.containerPos().getY() + 0.5, storage.containerPos().getZ() + 0.5);
        if (distSqr > 12.0) {
            context.navigator.moveTo(storage.containerPos(), 1.0);
            context.navigator.tick();
            return false; // keep trying next tick
        }
        context.navigator.stop();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || !CitizenInventory.idOf(stack).equals(itemId)) continue;
            int leftover = context.citizen.getInventory().insert(stack.copy());
            int taken = stack.getCount() - leftover;
            if (taken > 0) {
                stack.shrink(taken);
                if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                container.setChanged();
                return true;
            }
        }
        return false;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        ConstructionProject p = ConstructionManager.resolve(context.level,
                context.params.projectId, context.citizen.blockPosition());
        if (p == null) return "building";
        var bp = ConstructionManager.blueprint(p.blueprintId);
        return "building " + p.name + " " + (bp == null ? "?" : String.format("%.0f%%",
                p.progress(bp) * 100));
    }
}
