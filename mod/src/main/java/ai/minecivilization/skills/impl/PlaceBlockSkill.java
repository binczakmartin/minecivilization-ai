package ai.minecivilization.skills.impl;

import ai.minecivilization.citizen.CitizenTaskParams;
import ai.minecivilization.navigation.PlacementSupport;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillRegistry;
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

    private CitizenSkill approachSkill;
    private SkillContext approachContext;
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
        cancelApproach();
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
            return approach(context, pos);
        }
        if (approachSkill != null) {
            cancelApproach();
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

        if (!PlacementSupport.canPlace(context.level, state, pos)) {
            context.fail(new SkillFailure("UNSUPPORTED_BLOCK",
                    "block needs a floor or side support at "
                            + pos.getX() + "," + pos.getY() + "," + pos.getZ(), true));
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

        int extracted = context.citizen.getInventory().extract(itemId, 1);
        if (extracted != 1) {
            context.fail(SkillFailure.missing("no " + itemId + " in inventory to place"));
            return SkillResult.FAILED;
        }
        if (!context.level.setBlock(pos, state, 3)) {
            // The transaction is all-or-nothing: a failed setBlock must not
            // silently consume the citizen's building material.
            context.citizen.getInventory().insert(new net.minecraft.world.item.ItemStack(
                    ai.minecivilization.inventory.CitizenInventory.itemById(itemId)));
            context.fail(new SkillFailure("BLOCK_PLACE_FAILED",
                    "the world rejected the placement at " + pos, true));
            return SkillResult.FAILED;
        }
        // setBlock bypasses Forge's place event, so a chest a citizen puts down
        // would otherwise never join the settlement warehouse.
        ai.minecivilization.storage.StorageDiscovery.onContainerPlaced(context.level, pos);
        ai.minecivilization.colony.LandmarkRegistry.get(context.level)
                .notice(context.level, pos);
        context.citizen.getSkills().addXp("building", 0.05f);
        context.citizen.onBlockPlaced(context.params.block);
        return SkillResult.COMPLETED;
    }

    /** Walk to an explicitly requested placement, escalating through terrain. */
    private SkillResult approach(SkillContext context, BlockPos target) {
        if (approachSkill == null) {
            CitizenTaskParams params = new CitizenTaskParams();
            params.position = new int[]{target.getX(), target.getY(), target.getZ()};
            params.extra.put("traverse.arrival", "2");
            approachContext = new SkillContext(context.citizen, context.level,
                    context.navigator, params);
            approachContext.timeoutTicks = context.timeoutTicks;
            approachContext.startGameTime = context.level.getGameTime();
            approachSkill = SkillRegistry.create(SkillType.TRAVERSE);
            if (!approachSkill.canStart(approachContext)) {
                cancelApproach();
                context.fail(SkillFailure.unreachable("cannot make a way to the placement position"));
                return SkillResult.FAILED;
            }
            approachSkill.start(approachContext);
        }

        SkillResult result = approachSkill.tick(approachContext);
        if (result == SkillResult.RUNNING) return SkillResult.RUNNING;
        SkillFailure failure = approachContext.failure;
        cancelApproach();
        if (result != SkillResult.COMPLETED) {
            context.fail(failure != null ? failure
                    : SkillFailure.unreachable("could not reach the placement position"));
            return SkillResult.FAILED;
        }
        if (context.citizen.distanceToSqr(target.getX() + 0.5,
                target.getY() + 0.5, target.getZ() + 0.5) > 25.0) {
            context.fail(SkillFailure.unreachable("terrain route ended outside placement reach"));
            return SkillResult.FAILED;
        }
        return SkillResult.RUNNING;
    }

    private void cancelApproach() {
        if (approachSkill != null && approachContext != null) {
            approachSkill.cancel(approachContext);
        }
        approachSkill = null;
        approachContext = null;
    }

    @Override
    public void cancel(SkillContext context) {
        cancelApproach();
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "placing block";
    }
}
