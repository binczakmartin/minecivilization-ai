package ai.minecivilization.skills.impl;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Blocks;

/**
 * CRAFT_ITEM — real crafting on the vanilla recipe book.
 *
 * <p>The citizen looks up the recipe that produces the requested item, walks to
 * a crafting table when the pattern needs one (vanilla rule: 2×2 or smaller can
 * be crafted anywhere, 3×3 needs a table), lays its own inventory into the
 * grid, verifies the recipe matches, consumes exactly one item per ingredient
 * and keeps the result. It repeats until the requested quantity is in the
 * inventory or materials run out.</p>
 *
 * <p>No free items, no invented recipes: the plan of stacks to consume is
 * computed and validated <em>before</em> anything is taken, so a missing
 * ingredient fails cleanly with MISSING_RESOURCE instead of eating part of the
 * grid.</p>
 */
public final class CraftItemSkill implements CitizenSkill {
    /** Same reach rule as the other workstation skills. */
    private static final int REACH_SQR = 12;
    /** Craft iterations per tick — keeps long chains off a single tick. */
    private static final int CRAFTS_PER_TICK = 16;

    @Override
    public SkillType type() {
        return SkillType.CRAFT_ITEM;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.resource != null;
    }

    @Override
    public void start(SkillContext context) {
        BlockScanner.reset(context);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        String target = context.params.resource;
        if (target == null) {
            context.fail(new SkillFailure("INVALID_TASK", "CRAFT without resource", false));
            return SkillResult.FAILED;
        }
        CitizenInventory inventory = context.citizen.getInventory();
        int targetQty = context.params.quantity > 0 ? context.params.quantity : 1;
        if (inventory.count(target) >= targetQty) return SkillResult.COMPLETED;

        RecipeHolder<CraftingRecipe> holder = context.get("recipe", (RecipeHolder<CraftingRecipe>) null);
        if (holder == null) {
            holder = findRecipe(context, target);
            if (holder == null) {
                context.fail(new SkillFailure("NO_RECIPE",
                        "no crafting recipe produces " + target, true));
                return SkillResult.FAILED;
            }
            context.put("recipe", holder);
        }
        CraftingRecipe recipe = holder.value();

        // vanilla station rule: only patterns bigger than 2x2 need a table
        if (needsTable(recipe)) {
            SkillResult station = ensureStation(context);
            if (station == SkillResult.FAILED) return SkillResult.FAILED;
            if (station == SkillResult.RUNNING) return SkillResult.RUNNING;
            // COMPLETED = standing at the table: fall through and craft
        }

        int crafted = 0;
        while (inventory.count(target) < targetQty && crafted < CRAFTS_PER_TICK) {
            SkillResult result = craftOnce(context, recipe, inventory, target);
            if (result != SkillResult.RUNNING) return result;
            crafted++;
        }
        if (inventory.count(target) >= targetQty) return SkillResult.COMPLETED;

        // more materials wanted than fit in one tick: keep going next tick
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ station

    /** @return RUNNING = searching/walking, COMPLETED = ready to craft, FAILED = abort. */
    private SkillResult ensureStation(SkillContext context) {
        BlockPos station = context.get("pos", (BlockPos) null);
        if (station == null) {
            station = BlockScanner.find(context, Blocks.CRAFTING_TABLE);
            if (station == null) {
                if (BlockScanner.done(context)) {
                    context.fail(SkillFailure.notFound(
                            "no crafting table within sight — this recipe needs a 3x3 grid"));
                    return SkillResult.FAILED;
                }
                return SkillResult.RUNNING;
            }
            context.put("pos", station);
        }

        if (!context.level.getBlockState(station).is(Blocks.CRAFTING_TABLE)) {
            // table was broken: search again from scratch
            context.data.remove("pos");
            BlockScanner.reset(context);
            return SkillResult.RUNNING;
        }

        if (context.citizen.distanceToSqr(station.getX() + 0.5, station.getY() + 0.5,
                station.getZ() + 0.5) > REACH_SQR) {
            context.navigator.moveTo(station, 1.0);
            context.navigator.tick();
            if (context.navigator.hasFailed()) {
                context.fail(context.navigator.failure());
                return SkillResult.FAILED;
            }
            return SkillResult.RUNNING;
        }
        context.navigator.stop();
        return SkillResult.COMPLETED;
    }

    // ------------------------------------------------------------------ crafting

    private SkillResult craftOnce(SkillContext context, CraftingRecipe recipe,
                                  CitizenInventory inventory, String target) {
        List<Ingredient> ingredients = recipe.getIngredients();
        Plan plan = planConsumption(inventory, ingredients);
        if (plan == null) {
            context.fail(SkillFailure.missing(
                    "missing ingredients for " + target + " (gather first)"));
            return SkillResult.FAILED;
        }

        CraftingInput input = buildInput(recipe, ingredients, inventory, plan.sources());
        if (input == null || !recipe.matches(input, context.level)) {
            context.fail(new SkillFailure("CRAFT_MISMATCH",
                    "recipe grid did not match " + target, true));
            return SkillResult.FAILED;
        }

        ItemStack result = recipe.assemble(input, context.level.registryAccess());
        if (result.isEmpty()) {
            context.fail(new SkillFailure("CRAFT_MISMATCH",
                    "recipe produced nothing for " + target, true));
            return SkillResult.FAILED;
        }
        if (!canInsert(inventory, result)) {
            context.fail(new SkillFailure("INVENTORY_FULL",
                    "no room for crafted " + target, true));
            return SkillResult.FAILED;
        }

        // commit: pay exactly the planned stacks, then keep the product
        consume(inventory, plan.reserved());
        int leftover = inventory.insert(result);
        if (leftover > 0) {
            context.fail(new SkillFailure("INVENTORY_FULL",
                    "no room for crafted " + target, true));
            return SkillResult.FAILED;
        }
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    /** Lay one stack per (non-empty) ingredient into a grid of the recipe's shape. */
    private CraftingInput buildInput(CraftingRecipe recipe, List<Ingredient> ingredients,
                                     CitizenInventory inventory, int[] sources) {
        List<ItemStack> grid = new ArrayList<>();
        int used = 0;
        if (recipe instanceof ShapedRecipe shaped) {
            int width = shaped.getRecipeWidth();
            int height = shaped.getRecipeHeight();
            for (Ingredient ingredient : ingredients) {
                if (ingredient.isEmpty()) {
                    grid.add(ItemStack.EMPTY);
                } else {
                    if (used >= sources.length) return null;
                    grid.add(inventory.get(sources[used++]).copyWithCount(1));
                }
            }
            if (grid.size() != width * height || used != sources.length) return null;
            return CraftingInput.of(width, height, grid);
        }
        // shapeless: one row, one cell per ingredient (order does not matter)
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            if (used >= sources.length) return null;
            grid.add(inventory.get(sources[used++]).copyWithCount(1));
        }
        if (used != sources.length || grid.isEmpty()) return null;
        return CraftingInput.of(grid.size(), 1, grid);
    }

    /**
     * How one craft is paid for: one representative stack per non-empty
     * ingredient ({@code sources}) plus the per-slot totals to remove
     * ({@code reserved}). Returns {@code null} when the inventory cannot pay
     * for the whole recipe — the grid is never partially consumed.
     */
    private record Plan(int[] sources, int[] reserved) {
    }

    private static Plan planConsumption(CitizenInventory inventory, List<Ingredient> ingredients) {
        int nonEmpty = 0;
        for (Ingredient ingredient : ingredients) {
            if (!ingredient.isEmpty()) nonEmpty++;
        }
        int[] sources = new int[nonEmpty];
        int[] reserved = new int[inventory.size()];
        int cursor = 0;

        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            boolean found = false;
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.get(slot);
                if (stack.isEmpty() || reserved[slot] >= stack.getCount()
                        || !ingredient.test(stack)) {
                    continue;
                }
                reserved[slot]++;
                sources[cursor++] = slot;
                found = true;
                break;
            }
            if (!found) return null;
        }
        return new Plan(sources, reserved);
    }

    private static void consume(CitizenInventory inventory, int[] reserved) {
        for (int slot = 0; slot < reserved.length; slot++) {
            int pay = reserved[slot];
            if (pay == 0) continue;
            ItemStack stack = inventory.get(slot);
            stack.shrink(pay);
            if (stack.isEmpty()) inventory.set(slot, ItemStack.EMPTY);
        }
    }

    private static boolean canInsert(CitizenInventory inventory, ItemStack result) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(stack, result)
                    && stack.getCount() + result.getCount() <= stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private RecipeHolder<CraftingRecipe> findRecipe(SkillContext context, String target) {
        for (RecipeHolder<CraftingRecipe> holder
                : context.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            String produced = CitizenInventory.idOf(
                    holder.value().getResultItem(context.level.registryAccess()));
            if (produced.equals(target)) return holder;
        }
        return null;
    }

    private static boolean needsTable(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getRecipeWidth() > 2 || shaped.getRecipeHeight() > 2;
        }
        return recipe.getIngredients().size() > 4;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        String target = context.params.resource == null ? "?" : context.params.resource;
        int want = context.params.quantity > 0 ? context.params.quantity : 1;
        return "crafting " + target + " ("
                + context.citizen.getInventory().count(target) + "/" + want + ")";
    }
}
