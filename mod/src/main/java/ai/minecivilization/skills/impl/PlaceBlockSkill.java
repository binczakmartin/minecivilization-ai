package ai.minecivilization.skills.impl;

import ai.minecivilization.citizen.CitizenTaskParams;
import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.navigation.PlacementSafety;
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
import net.minecraft.world.phys.Vec3;

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

        boolean pillar = context.params.extra.containsKey("pillar")
                || context.params.extra.containsKey("raiseSelf");
        if (pillar) {
            return placePillar(context, pos, state);
        }

        if (ai.minecivilization.construction.ConstructionManager.get(context.level)
                .protectsCell(pos)
                && !context.params.extra.containsKey("authorizedProject")) {
            context.fail(new SkillFailure("PROTECTED_BLOCK",
                    "target belongs to a colony construction footprint", true));
            return SkillResult.FAILED;
        }
        if (!context.level.getBlockState(pos).canBeReplaced()) {
            // someone else filled it — that is fine, placement goal achieved only if ours
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "target position is occupied", true));
            return SkillResult.FAILED;
        }
        // Only a solid block needs the cell empty. A sapling, torch or flower
        // goes down at someone's feet as happily as anywhere — refusing that
        // failed every sapling a planter was standing over.
        boolean solid = !state.getCollisionShape(context.level, pos).isEmpty();
        if (solid && !PlacementSafety.canOccupy(context.level, context.citizen, pos, false)) {
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "placement cell intersects a living entity", true));
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
        if (!claim(context, pos, "place")) {
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "another worker already claimed this placement cell", true));
            return SkillResult.FAILED;
        }

        int extracted = context.citizen.getInventory().extract(itemId, 1);
        if (extracted != 1) {
            releaseClaim(context);
            context.fail(SkillFailure.missing("no " + itemId + " in inventory to place"));
            return SkillResult.FAILED;
        }
        // A bed is two blocks: its head goes in the cell it faces, which must
        // be free before the foot goes down.
        BlockPos bedHead = null;
        if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                && state.getValue(net.minecraft.world.level.block.BedBlock.PART)
                        == net.minecraft.world.level.block.state.properties.BedPart.FOOT) {
            bedHead = pos.relative(state.getValue(net.minecraft.world.level.block.BedBlock.FACING));
            if (!context.level.getBlockState(bedHead).canBeReplaced()) {
                context.citizen.getInventory().insert(new net.minecraft.world.item.ItemStack(
                        ai.minecivilization.inventory.CitizenInventory.itemById(itemId)));
                releaseClaim(context);
                context.fail(new SkillFailure("POSITION_OCCUPIED", "no room for the head of the bed", true));
                return SkillResult.FAILED;
            }
        }
        BlockState oldState = context.level.getBlockState(pos);
        boolean wrote = context.level.setBlock(pos, state, 3);
        if (wrote && bedHead != null) {
            context.level.setBlock(bedHead, state.setValue(net.minecraft.world.level.block.BedBlock.PART,
                    net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
        }
        if (!wrote || !context.level.getBlockState(pos).equals(state)) {
            if (wrote) context.level.setBlock(pos, oldState, 3);
            // The transaction is all-or-nothing: a failed setBlock must not
            // silently consume the citizen's building material.
            context.citizen.getInventory().insert(new net.minecraft.world.item.ItemStack(
                    ai.minecivilization.inventory.CitizenInventory.itemById(itemId)));
            releaseClaim(context);
            context.fail(new SkillFailure("BLOCK_PLACE_FAILED",
                    "the world rejected the placement at " + pos, true));
            return SkillResult.FAILED;
        }
        context.citizen.animateAction(WorkAnimation.PLACE, pos);
        recordPlacement(context, pos, context.params.block);
        context.put("scaffoldOwned", true);
        releaseClaim(context);
        return SkillResult.COMPLETED;
    }

    /** Execute the special, atomic block-under-feet operation. */
    private SkillResult placePillar(SkillContext context, BlockPos pos, BlockState state) {
        if (ai.minecivilization.construction.ConstructionManager.get(context.level)
                .protectsCell(pos)
                && !context.params.extra.containsKey("authorizedProject")) {
            context.fail(new SkillFailure("PROTECTED_BLOCK",
                    "pillar target belongs to a colony construction footprint", true));
            return SkillResult.FAILED;
        }
        if (!pos.equals(context.citizen.blockPosition())) {
            context.fail(new SkillFailure("INVALID_PILLAR",
                    "pillar target is not the worker's feet cell", true));
            return SkillResult.FAILED;
        }
        if (!PlacementSupport.canPlace(context.level, state, pos)) {
            context.fail(new SkillFailure("UNSUPPORTED_BLOCK",
                    "pillar has no sturdy support at " + pos, true));
            return SkillResult.FAILED;
        }

        BlockState existing = context.level.getBlockState(pos);
        if (!existing.canBeReplaced()) {
            // Another worker (or an earlier failed transaction) may already
            // have installed the support. Reuse it only when it is a plain,
            // sturdy, non-container block; never climb a chest or machine.
            if (context.level.getBlockEntity(pos) != null
                    || existing.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                    || !existing.isFaceSturdy(context.level, pos,
                    net.minecraft.core.Direction.UP)) {
                context.fail(new SkillFailure("POSITION_OCCUPIED",
                        "pillar cell is occupied by a non-scaffold block", true));
                return SkillResult.FAILED;
            }
            if (!PlacementSafety.canRaiseOne(context.level, context.citizen, pos)) {
                context.fail(new SkillFailure("POSITION_OCCUPIED",
                        "pillar destination intersects a body or another entity", true));
                return SkillResult.FAILED;
            }
            context.put("scaffoldOwned", false);
            SkillResult raised = raiseCitizen(context, pos, false);
            if (raised == SkillResult.COMPLETED) {
                context.citizen.animateAction(WorkAnimation.PLACE, pos);
            }
            return raised;
        }

        String itemId = context.params.extra.get("item");
        if (itemId == null) {
            net.minecraft.world.item.Item blockItem =
                    net.minecraft.world.item.BlockItem.byBlock(state.getBlock());
            if (blockItem == net.minecraft.world.item.Items.AIR) {
                context.fail(new SkillFailure("INVALID_BLOCK_STATE",
                        "pillar block has no corresponding item", false));
                return SkillResult.FAILED;
            }
            itemId = ai.minecivilization.inventory.CitizenInventory.idOf(
                    new net.minecraft.world.item.ItemStack(blockItem));
        }
        if (!context.citizen.getInventory().containsAtLeast(itemId, 1)
                || context.citizen.getInventory().extract(itemId, 1) != 1) {
            context.fail(SkillFailure.missing("no " + itemId + " in inventory to place"));
            return SkillResult.FAILED;
        }
        if (!PlacementSafety.canRaiseOne(context.level, context.citizen, pos)) {
            context.citizen.getInventory().insert(new net.minecraft.world.item.ItemStack(
                    ai.minecivilization.inventory.CitizenInventory.itemById(itemId)));
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "pillar destination intersects a body or another entity", true));
            return SkillResult.FAILED;
        }
        if (!claim(context, pos, "pillar")) {
            context.citizen.getInventory().insert(new net.minecraft.world.item.ItemStack(
                    ai.minecivilization.inventory.CitizenInventory.itemById(itemId)));
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "another worker already claimed this pillar cell", true));
            return SkillResult.FAILED;
        }

        BlockState oldState = existing;
        Vec3 oldPosition = context.citizen.position();
        Vec3 oldVelocity = context.citizen.getDeltaMovement();
        float oldFallDistance = context.citizen.fallDistance;
        boolean oldOnGround = context.citizen.onGround();
        boolean wrote = false;
        boolean changed = false;
        context.citizen.beginSelfSupportTransaction();
        try {
            wrote = context.level.setBlock(pos, state, 3);
            changed = wrote && context.level.getBlockState(pos).equals(state);
            if (!changed) {
                context.fail(new SkillFailure("BLOCK_PLACE_FAILED",
                        "the world rejected the pillar at " + pos, true));
            } else if (!PlacementSafety.canRaiseOne(context.level, context.citizen, pos)) {
                context.fail(new SkillFailure("POSITION_OCCUPIED",
                        "pillar destination became occupied before the raise", true));
            } else {
                SkillResult raised = raiseCitizen(context, pos, true);
                if (raised == SkillResult.COMPLETED) {
                    context.put("scaffoldOwned", true);
                    context.citizen.animateAction(WorkAnimation.PLACE, pos);
                    releaseClaim(context);
                    return raised;
                }
            }
        } finally {
            context.citizen.endSelfSupportTransaction();
        }

        if (wrote && context.level.getBlockState(pos).equals(state)) {
            context.level.setBlock(pos, oldState, 3);
        }
        context.citizen.setPos(oldPosition);
        context.citizen.setDeltaMovement(oldVelocity);
        context.citizen.fallDistance = oldFallDistance;
        context.citizen.setOnGround(oldOnGround);
        context.citizen.getInventory().insert(new net.minecraft.world.item.ItemStack(
                ai.minecivilization.inventory.CitizenInventory.itemById(itemId)));
        releaseClaim(context);
        return SkillResult.FAILED;
    }

    /** Raise exactly one block, with a post-condition and full rollback on failure. */
    private SkillResult raiseCitizen(SkillContext context, BlockPos support,
                                     boolean newlyPlaced) {
        Vec3 oldPosition = context.citizen.position();
        Vec3 oldVelocity = context.citizen.getDeltaMovement();
        float oldFallDistance = context.citizen.fallDistance;
        boolean oldOnGround = context.citizen.onGround();
        BlockPos destination = support.above();
        context.citizen.setPos(oldPosition.x, oldPosition.y + 1.0, oldPosition.z);
        context.citizen.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        context.citizen.fallDistance = 0.0f;
        context.citizen.setOnGround(true);
        if (context.citizen.blockPosition().equals(destination)
                && PlacementSafety.canStand(context.level, context.citizen, destination)) {
            if (newlyPlaced) recordPlacement(context, support, context.params.block);
            return SkillResult.COMPLETED;
        }

        context.citizen.setPos(oldPosition);
        context.citizen.setDeltaMovement(oldVelocity);
        context.citizen.fallDistance = oldFallDistance;
        context.citizen.setOnGround(oldOnGround);
        context.fail(new SkillFailure("POSITION_OCCUPIED",
                "could not raise safely onto " + destination, true));
        return SkillResult.FAILED;
    }

    private boolean claim(SkillContext context, BlockPos pos, String kind) {
        String owner = context.citizen.getIdentity().citizenId.toString();
        boolean claimed = ai.minecivilization.construction.WorkClaimStore.claim(
                context.level, owner, pos, kind, context.level.getGameTime(), 200);
        if (claimed) context.put("claim.pos", pos);
        return claimed;
    }

    private void releaseClaim(SkillContext context) {
        BlockPos pos = context.get("claim.pos", (BlockPos) null);
        if (pos == null) return;
        String owner = context.citizen.getIdentity().citizenId.toString();
        ai.minecivilization.construction.WorkClaimStore.release(
                context.level, owner, pos, "place");
        ai.minecivilization.construction.WorkClaimStore.release(
                context.level, owner, pos, "pillar");
        context.data.remove("claim.pos");
    }

    private void recordPlacement(SkillContext context, BlockPos pos, String blockId) {
        // setBlock bypasses Forge's place event, so a chest a citizen puts down
        // would otherwise never join the settlement warehouse.
        ai.minecivilization.storage.StorageDiscovery.onContainerPlaced(context.level, pos);
        ai.minecivilization.colony.LandmarkRegistry.get(context.level)
                .notice(context.level, pos);
        context.citizen.getSkills().addXp("building", 0.05f);
        context.citizen.onBlockPlaced(blockId);
    }

    private SkillResult approach(SkillContext context, BlockPos target) {
        context.citizen.clearWorkAnimation();
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
        releaseClaim(context);
        cancelApproach();
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "placing block";
    }
}
