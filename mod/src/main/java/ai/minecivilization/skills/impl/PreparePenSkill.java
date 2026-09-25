package ai.minecivilization.skills.impl;

import ai.minecivilization.construction.*;
import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.livestock.Pens;
import ai.minecivilization.navigation.PlacementSafety;
import ai.minecivilization.skills.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.item.ItemEntity;

/** Grade a shallow natural-soil site with actual dirt before building the fence ring. */
public final class PreparePenSkill implements CitizenSkill {
    public SkillType type() { return SkillType.PREPARE_PEN; }
    public boolean canStart(SkillContext c) { return c.params.projectId != null; }
    public void start(SkillContext c) {}
    public SkillResult tick(SkillContext c) {
        var pen = ConstructionManager.get(c.level).byId(c.params.projectId);
        if (pen == null) { c.fail(SkillFailure.notFound("missing pen project")); return SkillResult.FAILED; }
        if (Pens.prepared(c.level, pen)) return SkillResult.COMPLETED;
        for (BlockPos floor : BlockPos.betweenClosed(new BlockPos(pen.originX - 1, pen.originY - 1, pen.originZ - 1),
                new BlockPos(pen.originX + 9, pen.originY - 1, pen.originZ + 10))) {
            if (!c.level.isLoaded(floor)) continue;
            // Remove only the natural raised ground approved when the site was chosen.
            for (int dy = 3; dy >= 1; dy--) {
                BlockPos at = floor.above(dy);
                if (!Pens.natural(c.level.getBlockState(at))
                        || ConstructionManager.get(c.level).protectsCell(at)) continue;
                var walk = SkillNavigation.approach(c, at, 16, "pen.prepare");
                if (walk != SkillResult.COMPLETED) return walk;
                if (!c.level.destroyBlock(at, true, c.citizen)) { c.fail(SkillFailure.missing("cannot grade pen ground")); return SkillResult.FAILED; }
                c.citizen.animateAction(WorkAnimation.MINE, at);
                collect(c); c.startGameTime = c.level.getGameTime(); return SkillResult.RUNNING;
            }
            if (c.level.getBlockState(floor).isFaceSturdy(c.level, floor, Direction.UP)) continue;
            BlockPos at = floor.immutable();
            for (int depth = 0; depth < 2 && !c.level.getBlockState(at.below()).isFaceSturdy(c.level, at.below(), Direction.UP); depth++) at = at.below();
            if (ConstructionManager.get(c.level).protectsCell(at)
                    || !c.level.getBlockState(at).canBeReplaced() || !c.level.getFluidState(at).isEmpty()
                    || !c.level.getBlockState(at.below()).isFaceSturdy(c.level, at.below(), Direction.UP)) {
                c.fail(SkillFailure.notFound("pen site changed or lacks safe support")); return SkillResult.FAILED;
            }
            var walk = SkillNavigation.approach(c, at, 16, "pen.prepare");
            if (walk != SkillResult.COMPLETED) return walk;
            if (!c.citizen.getInventory().containsAtLeast("minecraft:dirt", 1)) { c.fail(SkillFailure.missing("dirt needed to level the pen")); return SkillResult.FAILED; }
            if (!PlacementSafety.canOccupy(c.level, c.citizen, at, false)) {
                c.fail(new SkillFailure("POSITION_OCCUPIED", "pen ground intersects a living entity", true));
                return SkillResult.FAILED;
            }
            if (c.level.setBlock(at, Blocks.DIRT.defaultBlockState(), 3)
                    && c.level.getBlockState(at).equals(Blocks.DIRT.defaultBlockState())) {
                c.citizen.getInventory().extract("minecraft:dirt", 1);
                c.citizen.animateAction(WorkAnimation.PLACE, at);
            }
            c.startGameTime = c.level.getGameTime(); return SkillResult.RUNNING;
        }
        c.fail(SkillFailure.notFound("pen site is obstructed")); return SkillResult.FAILED;
    }
    private void collect(SkillContext c) {
        for (ItemEntity e : c.level.getEntitiesOfClass(ItemEntity.class, c.citizen.getBoundingBox().inflate(4))) {
            if (e.getItem().isEmpty() || e.getItem().isDamageableItem()) continue;
            int left = c.citizen.getInventory().insert(e.getItem().copy());
            if (left == 0) e.discard(); else e.setItem(e.getItem().copyWithCount(left));
        }
    }
    public void cancel(SkillContext c) {
        CitizenSkill sub = c.get("pen.prepare", (CitizenSkill) null);
        SkillContext ctx = c.get("pen.prepare.ctx", (SkillContext) null);
        if (sub != null && ctx != null) sub.cancel(ctx);
        c.navigator.stop();
    }
    public String progressLabel(SkillContext c) { return "levelling ground for a closed animal pen"; }
}
