package ai.minecivilization.skills.impl;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.navigation.PlacementSafety;
import ai.minecivilization.navigation.PlacementSupport;
import ai.minecivilization.colony.DecorPlan;
import ai.minecivilization.colony.Zone;
import ai.minecivilization.colony.ZoneLayout;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.colony.ZoneType;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * DECORATE — light the streets, lay the hearth, plant the flowers.
 *
 * <p>Half of this keeps citizens alive. An unlit settlement spawns hostile mobs
 * inside its own streets after dark, and citizens die to things that should
 * never have been there; a torch grid is the cheapest defence a colony has. The
 * campfire and the flower beds are the other half, and a settlement that looks
 * lived in is the reason for building one at all.</p>
 *
 * <p>Nothing is conjured: every torch and flower comes out of the citizen's own
 * inventory, and the job quietly finishes when it runs out.</p>
 */
public final class DecorateSkill implements CitizenSkill {

    /** Arm's reach for placing, squared. */
    private static final double REACH_SQR = 20.0;
    /** Spots attempted per job — decorating is never urgent. */
    private static final int MAX_SPOTS = 24;

    private List<DecorPlan.Spot> spots;
    private int index;
    private int placed;

    @Override
    public SkillType type() {
        return SkillType.DECORATE;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return !ZoneManager.get(context.level).all().isEmpty();
    }

    @Override
    public void start(SkillContext context) {
        spots = null;
        index = 0;
        placed = 0;
    }

    @Override
    public SkillResult tick(SkillContext context) {
        if (spots == null) {
            spots = plan(context);
            if (spots.isEmpty()) {
                context.fail(SkillFailure.missing("nothing on hand to decorate with"));
                return SkillResult.FAILED;
            }
        }

        // Skip anything already done, or that the citizen can no longer afford.
        while (index < spots.size() && !stillWanted(context, spots.get(index))) {
            index++;
        }
        if (index >= spots.size() || placed >= MAX_SPOTS) {
            return placed > 0 ? SkillResult.COMPLETED : finishEmpty(context);
        }

        DecorPlan.Spot spot = spots.get(index);
        double distSqr = context.citizen.distanceToSqr(
                spot.pos().getX() + 0.5, spot.pos().getY() + 0.5, spot.pos().getZ() + 0.5);
        if (distSqr > REACH_SQR) {
            SkillResult arrival = SkillNavigation.approach(context, spot.pos(),
                    REACH_SQR, "decorate.walk");
            if (arrival == SkillResult.FAILED) {
                index++;   // this spot is not worth abandoning the whole job over
                return SkillResult.RUNNING;
            }
            if (arrival == SkillResult.RUNNING) return SkillResult.RUNNING;
        }
        context.navigator.stop();

        if (place(context, spot)) {
            placed++;
            context.citizen.getSkills().addXp("architecture", 0.02f);
        }
        index++;
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ planning

    /** The nearest district's decoration list, filtered to what is carried. */
    private List<DecorPlan.Spot> plan(SkillContext context) {
        ZoneManager zones = ZoneManager.get(context.level);
        BlockPos here = context.citizen.blockPosition();

        // The district that most needs the work, nearest first — not simply the
        // one underfoot. Crops will not grow below light level nine, so an
        // unlit field is a field that produces nothing after dark.
        Zone district = null;
        double bestDistance = Double.MAX_VALUE;
        for (Zone candidate : zones.all()) {
            double distance = candidate.distanceSqrTo(here);
            boolean wanted = candidate.type == ZoneType.FARM
                    || candidate.type == ZoneType.CIVIC
                    || candidate.type == ZoneType.RESIDENTIAL
                    || candidate.type == ZoneType.PASTURE;
            if (!wanted || distance >= bestDistance) continue;
            bestDistance = distance;
            district = candidate;
        }
        if (district == null) district = zones.at(here);
        if (district == null) return List.of();

        CitizenInventory inventory = context.citizen.getInventory();
        List<String> carried = new ArrayList<>(
                ai.minecivilization.navigation.ScaffoldMaterial.snapshot(inventory).keySet());
        List<String> flowers = DecorPlan.flowersAmong(carried);

        ZoneLayout.Bounds bounds = new ZoneLayout.Bounds(
                district.minX, district.minZ, district.maxX, district.maxZ);
        int surface = context.level.getHeight(Heightmap.Types.WORLD_SURFACE,
                bounds.centerX(), bounds.centerZ());

        List<DecorPlan.Spot> wanted = new ArrayList<>();
        for (DecorPlan.Spot spot : DecorPlan.forDistrict(district.type, bounds, surface, flowers)) {
            if (inventory.containsAtLeast(spot.block(), 1)) wanted.add(spot);
        }
        return wanted;
    }

    /** True when this spot is still empty, still affordable and still standable. */
    private boolean stillWanted(SkillContext context, DecorPlan.Spot spot) {
        if (!context.citizen.getInventory().containsAtLeast(spot.block(), 1)) return false;
        if (ConstructionManager.get(context.level).protectsCell(groundAt(context, spot.pos()))) return false;
        BlockPos ground = groundAt(context, spot.pos());
        return context.level.getBlockState(ground).canBeReplaced();
    }

    /** Decoration sits on the surface, whatever height the plan guessed. */
    private static BlockPos groundAt(SkillContext context, BlockPos planned) {
        int y = context.level.getHeight(Heightmap.Types.WORLD_SURFACE,
                planned.getX(), planned.getZ());
        return new BlockPos(planned.getX(), y, planned.getZ());
    }

    private boolean place(SkillContext context, DecorPlan.Spot spot) {
        BlockPos pos = groundAt(context, spot.pos());
        if (ConstructionManager.get(context.level).protectsCell(pos)) return false;
        BlockState state = ConstructionManager.parseState(context.level, spot.block());
        if (state == null || !PlacementSupport.canPlace(context.level, state, pos)
                || !PlacementSafety.canOccupy(context.level, context.citizen, pos, false)) return false;
        if (!context.level.getBlockState(pos).canBeReplaced()) return false;

        CitizenInventory inventory = context.citizen.getInventory();
        if (!inventory.containsAtLeast(spot.block(), 1)) return false;

        inventory.extract(spot.block(), 1);
        if (!context.level.setBlock(pos, state, 3)
                || !context.level.getBlockState(pos).equals(state)) {
            inventory.insert(new ItemStack(CitizenInventory.itemById(spot.block())));
            return false;
        }
        context.citizen.animateAction(WorkAnimation.PLACE, pos);
        context.citizen.onBlockPlaced(spot.block());
        return true;
    }

    private SkillResult finishEmpty(SkillContext context) {
        context.fail(SkillFailure.missing("nothing left to decorate with"));
        return SkillResult.FAILED;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        if (spots == null) return "planning the district";
        return "decorating " + placed + "/" + Math.min(spots.size(), MAX_SPOTS);
    }
}
