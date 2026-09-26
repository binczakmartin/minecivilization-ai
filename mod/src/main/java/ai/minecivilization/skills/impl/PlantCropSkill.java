package ai.minecivilization.skills.impl;

import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.navigation.PlacementSafety;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Plant seeds on farmland at params.position (the farmland block).
 * Requires seeds physically present in the citizen's inventory.
 */
public final class PlantCropSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.PLANT_CROP;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.position != null;
    }

    @Override
    public void start(SkillContext context) {
        int[] p = context.params.position;
        context.put("farmland", new BlockPos(p[0], p[1], p[2]));
    }

    @Override
    public SkillResult tick(SkillContext context) {
        BlockPos farmland = context.get("farmland", (BlockPos) null);
        if (farmland == null) {
            context.fail(SkillFailure.notFound("no farmland position"));
            return SkillResult.FAILED;
        }
        BlockPos above = farmland.above();
        BlockPos support = farmland.below();
        if (ai.minecivilization.construction.ConstructionManager.get(context.level)
                .protectsCell(farmland)
                || ai.minecivilization.construction.ConstructionManager.get(context.level)
                .protectsCell(above)) {
            context.fail(new SkillFailure("PROTECTED_BLOCK",
                    "farmland belongs to a colony construction footprint", true));
            return SkillResult.FAILED;
        }
        if (!context.level.getBlockState(farmland).is(Blocks.FARMLAND)
                || !context.level.getBlockState(support).isFaceSturdy(
                        context.level, support, Direction.UP)) {
            context.fail(new SkillFailure("UNSUPPORTED_BLOCK",
                    "farmland has no supporting ground", true));
            return SkillResult.FAILED;
        }
        if (!context.level.getBlockState(above).canBeReplaced()) {
            context.fail(new SkillFailure("POSITION_OCCUPIED", "space above farmland occupied", true));
            return SkillResult.FAILED;
        }
        if (!PlacementSafety.canOccupy(context.level, context.citizen, above, false)) {
            context.fail(new SkillFailure("POSITION_OCCUPIED", "crop cell intersects a living entity", true));
            return SkillResult.FAILED;
        }

        String seedId = context.params.extra.getOrDefault("seed", context.params.resource == null ? "minecraft:wheat_seeds" : context.params.resource);
        if (!context.citizen.getInventory().containsAtLeast(seedId, 1)) {
            context.fail(SkillFailure.missing("no " + seedId + " to plant"));
            return SkillResult.FAILED;
        }

        if (context.citizen.distanceToSqr(above.getX() + 0.5, above.getY() + 0.5, above.getZ() + 0.5) > 20.0) {
            context.fail(SkillFailure.unreachable("farmland out of reach"));
            return SkillResult.FAILED;
        }

        BlockState cropState = ai.minecivilization.farming.Crops.state(seedId);
        if (cropState == null) {
            context.fail(SkillFailure.notImplemented("unsupported seed " + seedId));
            return SkillResult.FAILED;
        }
        if (!cropState.canSurvive(context.level, above)) {
            context.fail(new SkillFailure("UNSUPPORTED_BLOCK", "cannot grow here", true));
            return SkillResult.FAILED;
        }

        if (!context.level.setBlock(above, cropState, 3)
                || !context.level.getBlockState(above).equals(cropState)) {
            context.fail(SkillFailure.missing("could not plant at target"));
            return SkillResult.FAILED;
        }
        context.citizen.getInventory().extract(seedId, 1);
        // Remember the field, or nobody will know to come back for the harvest.
        ai.minecivilization.farming.FieldRegistry.add(context.level, above);
        context.citizen.animateAction(WorkAnimation.PLANT, above);
        context.citizen.getSkills().addXp("farming", 0.03f);
        return SkillResult.COMPLETED;
    }

    @Override
    public void cancel(SkillContext context) {
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "planting";
    }
}
