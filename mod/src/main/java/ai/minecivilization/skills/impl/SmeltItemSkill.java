package ai.minecivilization.skills.impl;

import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;

/**
 * SMELT_ITEM — real furnace automation.
 *
 * <p>The citizen finds a furnace, walks to it, loads <b>its own</b> input items
 * and fuel into the vanilla furnace block entity, waits while it burns, and
 * pulls the finished result back into its inventory. Nothing is conjured: one
 * output item always costs one input item plus real fuel.</p>
 *
 * <p>Failure modes are structured (MISSING_RESOURCE for missing ingredients or
 * fuel, NO_RECIPE when nothing smelts into the requested item, FURNACE_BUSY
 * when someone else left a foreign input in the slot) and the timeout window
 * only ticks while the furnace is making progress.</p>
 */
public final class SmeltItemSkill implements CitizenSkill {
    /** Same reach rule as deposit/withdraw: the block must be next to the citizen. */
    private static final int REACH_SQR = 12;
    /** Vanilla AbstractFurnaceBlockEntity slot layout. */
    private static final int SLOT_INPUT = 0;
    private static final int SLOT_FUEL = 1;
    private static final int SLOT_RESULT = 2;
    /** Items moved per interaction tick — keeps each tick cheap. */
    private static final int LOAD_PER_TICK = 8;

    private enum Phase { FIND, MOVE, WORK }

    @Override
    public SkillType type() {
        return SkillType.SMELT_ITEM;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.resource != null;
    }

    @Override
    public void start(SkillContext context) {
        context.put("phase", Phase.FIND);
        BlockScanner.reset(context);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        return switch (context.get("phase", Phase.FIND)) {
            case FIND -> find(context);
            case MOVE -> move(context);
            case WORK -> work(context);
        };
    }

    // ------------------------------------------------------------------ phases

    private SkillResult find(SkillContext context) {
        BlockPos furnace = BlockScanner.find(context, Blocks.FURNACE);
        if (furnace != null) {
            context.put("pos", furnace);
            context.put("phase", Phase.MOVE);
            context.navigator.moveTo(furnace, 1.0);
            return SkillResult.RUNNING;
        }
        if (BlockScanner.done(context)) {
            context.fail(SkillFailure.notFound("no furnace within sight — one must be built first"));
            return SkillResult.FAILED;
        }
        return SkillResult.RUNNING;
    }

    private SkillResult move(SkillContext context) {
        context.citizen.clearWorkAnimation();
        BlockPos pos = context.get("pos", (BlockPos) null);
        if (pos == null) {
            context.put("phase", Phase.FIND);
            return SkillResult.RUNNING;
        }
        // Same stubbornness as everywhere else: walk, and make a way if walking
        // will not do. A furnace behind a step is not an unreachable furnace.
        SkillResult arrival = SkillNavigation.approach(context, pos, REACH_SQR, "smelt.walk");
        if (arrival == SkillResult.FAILED) return SkillResult.FAILED;
        if (arrival == SkillResult.COMPLETED || inReach(context, pos)) {
            context.navigator.stop();
            context.put("phase", Phase.WORK);
        }
        return SkillResult.RUNNING;
    }

    private SkillResult work(SkillContext context) {
        context.citizen.clearWorkAnimation();
        BlockPos pos = context.get("pos", (BlockPos) null);
        if (pos == null) {
            context.put("phase", Phase.FIND);
            return SkillResult.RUNNING;
        }
        if (!inReach(context, pos)) {
            context.put("phase", Phase.MOVE);
            context.navigator.moveTo(pos, 1.0);
            return SkillResult.RUNNING;
        }
        context.navigator.stop();

        if (!(context.level.getBlockEntity(pos) instanceof Container furnace)) {
            // furnace was broken while we worked: search again from scratch
            context.put("phase", Phase.FIND);
            BlockScanner.reset(context);
            return SkillResult.RUNNING;
        }

        CitizenInventory inventory = context.citizen.getInventory();
        String target = context.params.resource;
        int targetQty = context.params.quantity > 0 ? context.params.quantity : 1;

        // 1) collect whatever the furnace already produced
        SkillResult collected = collect(context, furnace, inventory, target, targetQty);
        if (collected != null) return collected;

        int have = inventory.count(target);
        if (have >= targetQty) return SkillResult.COMPLETED;

        // 2) resolve the smelting recipe once
        RecipeHolder<SmeltingRecipe> recipe = context.get("recipe", (RecipeHolder<SmeltingRecipe>) null);
        if (recipe == null) {
            recipe = findRecipe(context, target);
            if (recipe == null) {
                context.fail(new SkillFailure("NO_RECIPE", "no smelting recipe produces " + target, true));
                return SkillResult.FAILED;
            }
            context.put("recipe", recipe);
        }
        Ingredient input = recipe.value().getIngredients().getFirst();

        ItemStack inputStack = furnace.getItem(SLOT_INPUT);
        if (!inputStack.isEmpty() && !input.test(inputStack)) {
            context.fail(new SkillFailure("FURNACE_BUSY",
                    "furnace input slot holds a different item", true));
            return SkillResult.FAILED;
        }

        // 3) load as many input items as are still needed
        int pending = (inputStack.isEmpty() ? 0 : inputStack.getCount());
        ItemStack output = furnace.getItem(SLOT_RESULT);
        if (!output.isEmpty() && CitizenInventory.idOf(output).equals(target)) {
            pending += output.getCount();
        }
        int stillNeeded = targetQty - have - pending;
        if (stillNeeded > 0) {
            int alreadyLoaded = furnace.getItem(SLOT_INPUT).getCount();
            int want = Math.min(LOAD_PER_TICK, stillNeeded - alreadyLoaded);
            int moved = want > 0 ? load(context, furnace, SLOT_INPUT, input, want) : 0;
            if (moved == 0 && pending == 0 && furnace.getItem(SLOT_INPUT).isEmpty()) {
                context.fail(SkillFailure.missing("no smeltable " + target + " in inventory"));
                return SkillResult.FAILED;
            }
        }

        // 4) keep the furnace burning — fuel comes from the citizen's inventory
        if (furnace.getItem(SLOT_FUEL).isEmpty() && !isBurning(context, pos)) {
            if (loadFuel(context, furnace, LOAD_PER_TICK) == 0) {
                context.fail(SkillFailure.missing("no fuel in inventory for the furnace"));
                return SkillResult.FAILED;
            }
        }

        // 5) a burning/progressing furnace never times out; a stalled one does
        String signature = signature(furnace);
        if (!signature.equals(context.get("sig", "")) || isBurning(context, pos)) {
            context.put("sig", signature);
            context.startGameTime = context.level.getGameTime();
        }
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ helpers

    private SkillResult collect(SkillContext context, Container furnace, CitizenInventory inventory,
                                String target, int targetQty) {
        ItemStack output = furnace.getItem(SLOT_RESULT);
        if (output.isEmpty() || !CitizenInventory.idOf(output).equals(target)) return null;

        int leftover = inventory.insert(output.copy());
        int moved = output.getCount() - leftover;
        if (moved > 0) context.citizen.animateAction(WorkAnimation.SMELT,
                context.get("pos", (BlockPos) null));
        if (moved > 0) {
            output.shrink(moved);
            if (output.isEmpty()) {
                furnace.setItem(SLOT_RESULT, ItemStack.EMPTY);
            }
            furnace.setChanged();
        }
        if (inventory.count(target) >= targetQty) return SkillResult.COMPLETED;
        if (leftover > 0) {
            context.fail(new SkillFailure("INVENTORY_FULL",
                    "no room for smelted " + target, true));
            return SkillResult.FAILED;
        }
        return null;
    }

    private RecipeHolder<SmeltingRecipe> findRecipe(SkillContext context, String target) {
        for (RecipeHolder<SmeltingRecipe> holder
                : context.level.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING)) {
            String produced = CitizenInventory.idOf(
                    holder.value().getResultItem(context.level.registryAccess()));
            if (produced.equals(target)) return holder;
        }
        return null;
    }

    /** Move up to {@code limit} items matching {@code ingredient} into one furnace slot. */
    private static int load(SkillContext context, Container furnace, int slot,
                            Ingredient ingredient, int limit) {
        return transfer(context, furnace, slot, limit, ingredient::test);
    }

    private static int loadFuel(SkillContext context, Container furnace, int limit) {
        // Load one fuel at a time. Bulk-loading consumed the sticks reserved
        // for torches and left most of the batch unused inside the furnace.
        int moved = transfer(context, furnace, SLOT_FUEL, 1,
                stack -> stack.is(net.minecraft.tags.ItemTags.PLANKS)
                    && net.minecraftforge.common.ForgeHooks.getBurnTime(stack, RecipeType.SMELTING) > 0);
        if (moved > 0) return moved;
        return transfer(context, furnace, SLOT_FUEL, 1,
                stack -> !stack.isDamageableItem()
                    && !CitizenInventory.idOf(stack).equals(context.params.resource)
                    && !CitizenInventory.idOf(stack).equals("minecraft:stick")
                    && (stack.is(net.minecraft.tags.ItemTags.LOGS)
                        || stack.is(net.minecraft.world.item.Items.COAL)
                        || stack.is(net.minecraft.world.item.Items.CHARCOAL))
                    && net.minecraftforge.common.ForgeHooks.getBurnTime(stack, RecipeType.SMELTING) > 0);
    }

    private static int transfer(SkillContext context, Container furnace, int slot,
                                int limit, java.util.function.Predicate<ItemStack> matches) {
        if (limit <= 0) return 0;
        CitizenInventory inventory = context.citizen.getInventory();
        ItemStack dest = furnace.getItem(slot);
        if (!dest.isEmpty() && !matches.test(dest)) return 0;
        int maxStack = dest.isEmpty() ? 0 : dest.getMaxStackSize();
        if (!dest.isEmpty() && dest.getCount() >= maxStack) return 0;

        int moved = 0;
        for (int i = 0; i < inventory.size() && moved < limit; i++) {
            ItemStack stack = inventory.get(i);
            if (stack.isEmpty() || !matches.test(stack)) continue;
            dest = furnace.getItem(slot);
            if (!dest.isEmpty() && !ItemStack.isSameItemSameComponents(stack, dest)) continue;
            int room = dest.isEmpty()
                    ? stack.getMaxStackSize()
                    : dest.getMaxStackSize() - dest.getCount();
            if (room <= 0) break;
            int take = Math.min(Math.min(room, limit - moved), stack.getCount());
            if (take <= 0) break;
            if (dest.isEmpty()) {
                furnace.setItem(slot, stack.copyWithCount(take));
            } else {
                dest.grow(take);
            }
            stack.shrink(take);
            if (stack.isEmpty()) inventory.set(i, ItemStack.EMPTY);
            moved += take;
        }
        if (moved > 0) {
            context.citizen.animateAction(WorkAnimation.SMELT,
                    context.get("pos", (BlockPos) null));
            furnace.setChanged();
        }
        return moved;
    }

    private static boolean inReach(SkillContext context, BlockPos pos) {
        return context.citizen.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= REACH_SQR;
    }

    private static boolean isBurning(SkillContext context, BlockPos pos) {
        var state = context.level.getBlockState(pos);
        return state.hasProperty(AbstractFurnaceBlock.LIT)
                && state.getValue(AbstractFurnaceBlock.LIT);
    }

    private static String signature(Container furnace) {
        return furnace.getItem(SLOT_INPUT).getCount()
                + ":" + furnace.getItem(SLOT_FUEL).getCount()
                + ":" + furnace.getItem(SLOT_RESULT).getCount();
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
        // Hand the workbench back. The claim would lapse on its own, but a
        // minute of a station nobody is standing at is a minute the colony
        // queues behind it.
        BlockScanner.releaseStation(context);
    }

    @Override
    public String progressLabel(SkillContext context) {
        String target = context.params.resource == null ? "?" : context.params.resource;
        int want = context.params.quantity > 0 ? context.params.quantity : 1;
        return "smelting " + target + " ("
                + context.citizen.getInventory().count(target) + "/" + want + ")";
    }
}
