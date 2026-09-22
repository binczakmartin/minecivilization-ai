package ai.minecivilization.skills.impl;

import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Place one block from the citizen's inventory at params.position with
 * params.block (block-state string, e.g. "minecraft:oak_stairs[facing=east]").
 * Requires support (canSurvive) and consumes exactly one item.
 */
public final class PlaceBlockSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.PLACE_BLOCK;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.position != null && context.params.block != null;
    }

    @Override
    public void start(SkillContext context) {
        int[] p = context.params.position;
        BlockPos pos = new BlockPos(p[0], p[1], p[2]);
        context.put("pos", pos);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        BlockPos pos = context.get("pos", (BlockPos) null);
        if (pos == null) {
            context.fail(new SkillFailure("INVALID_TARGET", "no placement position", true));
            return SkillResult.FAILED;
        }

        if (context.citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 25.0) {
            context.fail(SkillFailure.unreachable("placement position out of reach"));
            return SkillResult.FAILED;
        }

        BlockState state;
        try {
            state = BlockStateParser.parseForBlock(
                    context.level.registryAccess().lookupOrThrow(Registries.BLOCK),
                    context.params.block, false).blockState();
        } catch (Exception ex) {
            context.fail(new SkillFailure("INVALID_BLOCK_STATE",
                    "cannot parse '" + context.params.block + "': " + ex.getMessage(), false));
            return SkillResult.FAILED;
        }

        if (!context.level.getBlockState(pos).canBeReplaced()) {
            // someone else filled it — that is fine, placement goal achieved only if ours
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "target position is occupied", true));
            return SkillResult.FAILED;
        }

        if (!state.canSurvive(context.level, pos)) {
            context.fail(new SkillFailure("UNSUPPORTED_BLOCK",
                    "block needs support at " + pos.getX() + "," + pos.getY() + "," + pos.getZ(), true));
            return SkillResult.FAILED;
        }

        String itemId = context.params.extra.get("item");
        if (itemId == null) {
            net.minecraft.world.item.Item blockItem = net.minecraft.world.item.BlockItem.byBlock(state.getBlock());
            if (blockItem == net.minecraft.world.item.Items.AIR) {
                context.fail(new SkillFailure("INVALID_BLOCK_STATE",
                        "block has no corresponding item", false));
                return SkillResult.FAILED;
            }
            itemId = ai.minecivilization.inventory.CitizenInventory.idOf(new net.minecraft.world.item.ItemStack(blockItem));
        }
        if (!context.citizen.getInventory().containsAtLeast(itemId, 1)) {
            context.fail(SkillFailure.missing("no " + itemId + " in inventory to place"));
            return SkillResult.FAILED;
        }

        context.citizen.getInventory().extract(itemId, 1);
        context.level.setBlock(pos, state, 3);
        context.citizen.getSkills().addXp("building", 0.05f);
        context.citizen.onBlockPlaced(context.params.block);
        return SkillResult.COMPLETED;
    }

    @Override
    public void cancel(SkillContext context) {
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "placing block";
    }
}
