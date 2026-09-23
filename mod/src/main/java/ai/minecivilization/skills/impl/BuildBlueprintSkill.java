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
        if (ai.minecivilization.construction.AnimalPen.isPen(project.blueprintId)) {
            var existing = context.level.getBlockState(next.pos);
            if (existing.is(state.getBlock())) {
                project.placed.add(project.key(next.pos.getX(), next.pos.getY(), next.pos.getZ()));
                manager.setDirty();
                return SkillResult.RUNNING;
            }
            if (!existing.canBeReplaced() || !context.level.getFluidState(next.pos).isEmpty()) {
                context.fail(new SkillFailure("PEN_OBSTRUCTED", "pen construction must not overwrite existing structures", true));
                return SkillResult.FAILED;
            }
        }
        Item blockItem = net.minecraft.world.item.BlockItem.byBlock(state.getBlock());
        if (blockItem == net.minecraft.world.item.Items.AIR) {
            context.fail(new SkillFailure("INVALID_BLOCK_STATE",
                    "no item for " + next.blockState, false));
            return SkillResult.FAILED;
        }
        String itemId = CitizenInventory.idOf(new ItemStack(blockItem));

        // Missing materials: walk to storage and withdraw. "Still walking" is
        // not the same answer as "the material is absent"; the old boolean
        // helper failed the build on the first tick of every long walk.
        if (!context.citizen.getInventory().containsAtLeast(itemId, 1)) {
            SkillResult withdrawal = tryWithdrawFromStorage(context, itemId);
            if (withdrawal == SkillResult.RUNNING) return withdrawal;
            if (withdrawal == SkillResult.FAILED) return SkillResult.FAILED;
            if (!context.citizen.getInventory().containsAtLeast(itemId, 1)) {
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
            SkillResult arrival = SkillNavigation.approach(context, next.pos, 25.0, "build.walk");
            if (arrival != SkillResult.COMPLETED) return arrival;
        }
        context.navigator.stop();

        // Physically place: consume exactly one item, and refund it if the world
        // rejects the write.  A project must never advance on a phantom block.
        context.citizen.getInventory().extract(itemId, 1);
        if (!context.level.setBlock(next.pos, state, 3)) {
            context.citizen.getInventory().insert(new ItemStack(blockItem));
            context.fail(new SkillFailure("BLOCK_PLACE_FAILED",
                    "the world rejected blueprint block at " + next.pos, true));
            return SkillResult.FAILED;
        }
        // A blueprint that includes chests registers them as it raises them.
        ai.minecivilization.storage.StorageDiscovery.onContainerPlaced(context.level, next.pos);
        ai.minecivilization.colony.LandmarkRegistry.get(context.level)
                .notice(context.level, next.pos);
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
            // A blueprint can place a sign but cannot say anything on it.
            ai.minecivilization.colony.DistrictMarker.stamp(context.level, project);
            context.citizen.onProjectCompleted(project);
            return SkillResult.COMPLETED;
        }
        return SkillResult.RUNNING;
    }

    private SkillResult tryWithdrawFromStorage(SkillContext context, String itemId) {
        StorageNode storage = StorageManager.nearestWith(context.level,
                context.citizen.blockPosition(), itemId, 1);
        if (storage == null) return SkillResult.COMPLETED;
        var be = context.level.getBlockEntity(storage.containerPos());
        if (!(be instanceof net.minecraft.world.Container container)) return SkillResult.COMPLETED;

        double distSqr = context.citizen.distanceToSqr(storage.containerPos().getX() + 0.5,
                storage.containerPos().getY() + 0.5, storage.containerPos().getZ() + 0.5);
        if (distSqr > 12.0) {
            SkillResult arrival = SkillNavigation.approach(context, storage.containerPos(),
                    12.0, "build.withdraw");
            if (arrival != SkillResult.COMPLETED) return arrival;
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
                return SkillResult.COMPLETED;
            }
        }
        return SkillResult.COMPLETED;
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
