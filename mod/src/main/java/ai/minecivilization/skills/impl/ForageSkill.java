package ai.minecivilization.skills.impl;

import java.util.HashSet;
import java.util.Set;

import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.forestry.Forageables;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.navigation.SpiralScan;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * FORAGE — thrash the undergrowth for what it drops.
 *
 * <p>This is where a colony's first wheat seed comes from. Without it a farmer
 * with no field to work simply reported "no reachable wheat" forever: there was
 * no step anywhere that could turn an empty meadow into a farm, so the failure
 * repeated until the end of time.</p>
 *
 * <p>Grass is stingy — most tufts drop nothing — so foraging is a numbers game,
 * budgeted rather than open-ended. Whatever else falls out along the way
 * (flowers, cane, mushrooms) is kept too: the colony's palette of materials
 * widens by walking through places it has not been.</p>
 */
public final class ForageSkill implements CitizenSkill {

    /** How far a citizen will wander looking for something to pick. */
    private static final int SEARCH_RADIUS = 40;
    /** Cells examined per tick — the search is sliced like every other. */
    private static final int SCAN_PER_TICK = 4000;
    /** Plants broken before giving up — grass drops seeds about one time in eight. */
    private static final int MAX_PLANTS = 96;
    /** Arm's reach, squared. */
    private static final double REACH_SQR = 16.0;

    private final Set<BlockPos> attempted = new HashSet<>();
    /** Edible items carried when a food forage started; -1 until then. */
    private int foodBaseline = -1;
    private BlockPos target;
    private int broken;
    private SpiralScan.Cursor scan;
    private BlockPos scanOrigin;

    @Override
    public SkillType type() {
        return SkillType.FORAGE;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return true;
    }

    @Override
    public void start(SkillContext context) {
        attempted.clear();
        foodBaseline = -1;
        target = null;
        broken = 0;
        scan = null;
        scanOrigin = null;
    }

    @Override
    public SkillResult tick(SkillContext context) {
        String wanted = context.params.resource;
        int quantity = context.params.quantity > 0 ? context.params.quantity : 1;
        CitizenInventory inventory = context.citizen.getInventory();

        boolean forFood = Forageables.FOOD.equals(wanted);
        if (forFood) {
            // Count meals, not one item: whatever edible thing the bushes gave.
            if (foodBaseline < 0) foodBaseline = foodCount(inventory);
            if (foodCount(inventory) - foodBaseline >= Math.max(quantity, 4)) {
                collectDrops(context);
                return SkillResult.COMPLETED;
            }
        } else if (wanted != null && inventory.count(wanted) >= quantity) {
            collectDrops(context);
            return SkillResult.COMPLETED;
        }
        if (broken >= MAX_PLANTS) {
            // Budget spent. Anything picked up along the way is still a gain,
            // so this only fails when the meadow gave up nothing at all.
            collectDrops(context);
            if (wanted == null || (forFood ? foodCount(inventory) > foodBaseline
                    : inventory.count(wanted) > 0)) return SkillResult.COMPLETED;
            context.fail(SkillFailure.missing(
                    "broke " + broken + " plants without finding " + wanted));
            return SkillResult.FAILED;
        }

        if (target == null || !isForageable(context, target)) {
            target = findPlant(context, wanted);
            if (target == PENDING) {
                target = null;
                return SkillResult.RUNNING;   // the search is still walking outward
            }
            if (target == null) {
                collectDrops(context);
                context.fail(SkillFailure.notFound(
                        "nothing to forage within " + SEARCH_RADIUS + " blocks"));
                return SkillResult.FAILED;
            }
            scan = null;   // found one: start the next search fresh from here
        }

        double distSqr = context.citizen.distanceToSqr(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (distSqr > REACH_SQR) {
            SkillResult arrival = SkillNavigation.approach(context, target,
                    REACH_SQR, "forage.walk");
            if (arrival == SkillResult.FAILED) {
                // One stubborn tuft is not worth a failed job: pick another.
                attempted.add(target);
                target = null;
                context.navigator.stop();
            }
            return SkillResult.RUNNING;
        }
        context.navigator.stop();

        // Plants break instantly and drop where they stood.
        if (ai.minecivilization.construction.ConstructionManager.get(context.level)
                .protectsCell(target)) {
            attempted.add(target);
            target = null;
            return SkillResult.RUNNING;
        }
        if (!context.level.destroyBlock(target, true, context.citizen)) {
            attempted.add(target);
            target = null;
            return SkillResult.RUNNING;
        }
        context.citizen.animateAction(WorkAnimation.FORAGE, target);
        attempted.add(target);
        broken++;
        target = null;
        collectDrops(context);
        context.citizen.getSkills().addXp("farming", 0.01f);
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ helpers

    private static int foodCount(CitizenInventory inventory) {
        int food = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (!stack.isEmpty() && stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
                food += stack.getCount();
            }
        }
        return food;
    }

    private boolean isForageable(SkillContext context, BlockPos pos) {
        if (ai.minecivilization.construction.ConstructionManager.get(context.level)
                .protectsCell(pos)) return false;
        var block = context.level.getBlockState(pos).getBlock();
        var key = ForgeRegistries.BLOCKS.getKey(block);
        return key != null && Forageables.isForageable(key.toString());
    }

    /**
     * Nearest untouched plant of the right sort.
     *
     * <p>Walked outward from the citizen's own feet so the patch it is standing
     * in is worked before it goes wandering, and sliced across ticks so a wide
     * search never costs a tick. A meadow is usually found in the first few
     * hundred cells; the forty-block reach only gets paid for when the ground
     * really is bare.</p>
     */
    private BlockPos findPlant(SkillContext context, String wanted) {
        Set<String> sources = new HashSet<>(Forageables.sourcesFor(wanted));
        BlockPos origin = context.citizen.blockPosition();

        if (scan == null || !origin.equals(scanOrigin)) {
            scan = new SpiralScan.Cursor();
            scanOrigin = origin;
        }

        int[] offset = new int[3];
        int examined = 0;
        while (examined < SCAN_PER_TICK && scan.next(SEARCH_RADIUS, offset)) {
            examined++;
            // Plants grow on the surface: a tall column of cells is wasted effort.
            if (Math.abs(offset[1]) > 5) continue;

            BlockPos pos = origin.offset(offset[0], offset[1], offset[2]);
            if (attempted.contains(pos)) continue;
            if (!context.level.isLoaded(pos)) continue;
            var key = ForgeRegistries.BLOCKS.getKey(context.level.getBlockState(pos).getBlock());
            if (key == null || !sources.contains(key.toString())) continue;
            return pos.immutable();
        }
        // Still walking: report "nothing yet" rather than "nothing at all".
        return scan.exhausted() ? null : PENDING;
    }

    /** Sentinel: the search has not finished, so no conclusion can be drawn. */
    private static final BlockPos PENDING = new BlockPos(0, Integer.MIN_VALUE, 0);

    /** Sweep up what fell nearby — drops land at the citizen's feet. */
    private void collectDrops(SkillContext context) {
        CitizenInventory inventory = context.citizen.getInventory();
        AABB box = context.citizen.getBoundingBox().inflate(4.0);
        for (ItemEntity item : context.level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (!item.isAlive()) continue;
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) continue;
            int leftover = inventory.insert(stack.copy());
            if (leftover <= 0) {
                item.discard();
            } else if (leftover < stack.getCount()) {
                stack.setCount(leftover);
                item.setItem(stack);
            }
        }
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        String wanted = context.params.resource;
        if (wanted == null) return "foraging (" + broken + " plants)";
        return "foraging for " + wanted + " ("
                + context.citizen.getInventory().count(wanted) + "/"
                + Math.max(1, context.params.quantity) + ", " + broken + " plants)";
    }
}
