package ai.minecivilization.skills.impl;

import ai.minecivilization.colony.Zone;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.colony.ZoneType;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * TILL_SOIL — turn ground into farmland.
 *
 * <p>The missing link between "we have seeds" and "we have a farm". A citizen
 * hoes open ground into farmland, preferring the colony's designated farmland
 * district so fields end up where the town plan put them rather than wherever
 * the farmer happened to be standing.</p>
 *
 * <p>Needs a real hoe and spends its durability, like any other tool use.</p>
 */
public final class TillSoilSkill implements CitizenSkill {

    /** How many blocks are turned over in one job. */
    private static final int DEFAULT_PLOTS = 9;
    /** Arm's reach, squared. */
    private static final double REACH_SQR = 16.0;
    /** How far from the field centre a citizen will look for tillable ground. */
    private static final int SEARCH_RADIUS = 12;
    /**
     * How many blocks of extra walking a citizen will accept to reach ground
     * that water can reach. Expressed as squared distance, so it outweighs any
     * plausible detour inside one field.
     */
    private static final double DRY_GROUND_PENALTY = 10_000.0;

    private BlockPos target;
    private int tilled;

    @Override
    public SkillType type() {
        return SkillType.TILL_SOIL;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return hoeSlot(context) >= 0;
    }

    @Override
    public void start(SkillContext context) {
        target = null;
        tilled = 0;
    }

    @Override
    public SkillResult tick(SkillContext context) {
        int wanted = context.params.quantity > 0 ? context.params.quantity : DEFAULT_PLOTS;
        if (tilled >= wanted) return SkillResult.COMPLETED;

        int hoe = hoeSlot(context);
        if (hoe < 0) {
            context.fail(SkillFailure.missing("no hoe to break the ground with"));
            return SkillResult.FAILED;
        }

        if (target == null || !isTillable(context, target)) {
            target = findGround(context);
            if (target == null) {
                // Some ground was turned: a short field is still a field.
                if (tilled > 0) return SkillResult.COMPLETED;
                context.fail(SkillFailure.notFound("no open ground to till nearby"));
                return SkillResult.FAILED;
            }
        }

        double distSqr = context.citizen.distanceToSqr(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (distSqr > REACH_SQR) {
            SkillResult arrival = SkillNavigation.approach(context, target,
                    REACH_SQR, "till.walk");
            if (arrival == SkillResult.FAILED) {
                target = null;   // try a different patch rather than failing
                return SkillResult.RUNNING;
            }
            if (arrival == SkillResult.RUNNING) return SkillResult.RUNNING;
        }
        context.navigator.stop();
        if (!PlacementSafety.canOccupy(context.level, context.citizen, target, false)) {
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "tilled plot intersects a living entity", true));
            return SkillResult.FAILED;
        }

        if (!context.level.setBlock(target, Blocks.FARMLAND.defaultBlockState(), 3)
                || !context.level.getBlockState(target).equals(Blocks.FARMLAND.defaultBlockState())) {
            context.fail(new SkillFailure("BLOCK_PLACE_FAILED",
                    "the world rejected the tilled plot at " + target, true));
            return SkillResult.FAILED;
        }
        context.citizen.animateAction(WorkAnimation.TILL, target);
        context.level.playSound(null, target, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        wearOutHoe(context, hoe);
        context.citizen.getSkills().addXp("farming", 0.03f);
        tilled++;
        target = null;
        context.startGameTime = context.level.getGameTime();
        return tilled >= wanted ? SkillResult.COMPLETED : SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ helpers

    private static int hoeSlot(SkillContext context) {
        CitizenInventory inventory = context.citizen.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && CitizenInventory.idOf(stack).endsWith("_hoe")) {
                return slot;
            }
        }
        return -1;
    }

    private static void wearOutHoe(SkillContext context, int slot) {
        CitizenInventory inventory = context.citizen.getInventory();
        ItemStack hoe = inventory.getItem(slot);
        if (hoe.isEmpty() || !hoe.isDamageableItem()) return;
        hoe.setDamageValue(hoe.getDamageValue() + 1);
        if (hoe.getDamageValue() >= hoe.getMaxDamage()) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
    }

    /** Dirt or grass with open sky above it, and nothing planted yet. */
    private static boolean isTillable(SkillContext context, BlockPos pos) {
        if (ai.minecivilization.construction.ConstructionManager.get(context.level)
                .protectsCell(pos)) return false;
        BlockState state = context.level.getBlockState(pos);
        if (!state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND)) return false;
        BlockPos below = pos.below();
        if (!context.level.getBlockState(below).isFaceSturdy(context.level, below, Direction.UP)) {
            return false;
        }
        return context.level.getBlockState(pos.above()).canBeReplaced();
    }

    /**
     * Where the field goes. The colony's farmland district first — that is the
     * point of having a town plan — and only otherwise wherever the citizen is.
     */
    private BlockPos findGround(SkillContext context) {
        BlockPos origin = context.citizen.blockPosition();
        Zone field = ZoneManager.get(context.level).nearest(ZoneType.FARM, origin);
        BlockPos center = field != null ? field.center() : origin;

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (field != null && !field.contains(x, z)) continue;

                int y = context.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                BlockPos pos = new BlockPos(x, y, z);
                if (!context.level.isLoaded(pos) || !isTillable(context, pos)) continue;

                // Dry farmland reverts to dirt and grows nothing worth eating.
                // Ground within four blocks of water stays hydrated, so it is
                // worth walking past a dozen nearer tiles to reach it.
                double score = origin.distSqr(pos);
                if (!isHydrated(context, pos)) score += DRY_GROUND_PENALTY;
                if (score < bestScore) {
                    bestScore = score;
                    best = pos;
                }
            }
        }
        return best;
    }

    /** Vanilla rule: farmland stays wet with water within four blocks, same level or one up. */
    private static boolean isHydrated(SkillContext context, BlockPos pos) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos probe = pos.offset(dx, dy, dz);
                    if (!context.level.isLoaded(probe)) continue;
                    if (context.level.getFluidState(probe)
                            .is(net.minecraft.tags.FluidTags.WATER)) {
                        return true;
                    }
                }
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
        int wanted = context.params.quantity > 0 ? context.params.quantity : DEFAULT_PLOTS;
        return "tilling " + tilled + "/" + wanted;
    }
}
