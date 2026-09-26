package ai.minecivilization.skills.impl;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        SkillResult result = craft(context);
        if (result != SkillResult.RUNNING) pickUpPortableTable(context);
        return result;
    }

    private SkillResult craft(SkillContext context) {
        String target = context.params.resource;
        if (target == null) {
            context.fail(new SkillFailure("INVALID_TASK", "CRAFT without resource", false));
            return SkillResult.FAILED;
        }
        CitizenInventory inventory = context.citizen.getInventory();
        int targetQty = context.params.quantity > 0 ? context.params.quantity : 1;
        if (inventory.count(target) >= targetQty) return SkillResult.COMPLETED;

        RecipeHolder<CraftingRecipe> holder = context.get("recipe", (RecipeHolder<CraftingRecipe>) null);
        if (holder != null
                && planConsumption(inventory, holder.value().getIngredients()) == null) {
            // What we were using has run out. Another recipe may still work —
            // materials change while a citizen works, so the choice is not final.
            holder = null;
            context.data.remove("recipe");
        }
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

    /** A workbench this close is used where it stands rather than setting one down. */
    private static final int NEARBY_TABLE = 6;
    /** Farther than this, a remembered table is not worth walking to. */
    private static final int FARTHEST_TABLE = 32;

    /**
     * Get to a workbench.
     *
     * <p>In order of preference: a table a few steps away; the citizen's own
     * table, set down beside it (made on the spot from planks or a log if it
     * is not carrying one); and only then the long walk to whichever table the
     * colony remembers. The old order — always walk to the colony's table —
     * failed 246 times in one session with "no route", because that table was
     * across a river or up a cliff from wherever the citizen happened to be.
     * A player carries a workbench; so does a citizen now.</p>
     *
     * @return RUNNING = searching/walking, COMPLETED = ready to craft, FAILED = abort.
     */
    private SkillResult ensureStation(SkillContext context) {
        context.citizen.clearWorkAnimation();
        BlockPos station = context.get("pos", (BlockPos) null);
        if (station == null) {
            station = nearbyTable(context);
            if (station == null) station = setDownPortableTable(context);
            if (station == null) {
                station = BlockScanner.find(context, Blocks.CRAFTING_TABLE);
                if (station == null) {
                    if (BlockScanner.done(context)) {
                        context.fail(SkillFailure.notFound(
                                "no crafting table within sight, none carried and no planks "
                                        + "to make one — this recipe needs a 3x3 grid"));
                        return SkillResult.FAILED;
                    }
                    return SkillResult.RUNNING;
                }
                if (station.distSqr(context.citizen.blockPosition()) > FARTHEST_TABLE * FARTHEST_TABLE) {
                    // A table a hundred blocks off is not a workbench, it is an
                    // expedition — and usually one with no route. Fail cheaply;
                    // the executor makes the citizen its own table next time.
                    BlockScanner.releaseStation(context);
                    context.fail(SkillFailure.notFound("no workbench within "
                            + FARTHEST_TABLE + " blocks and no wood to make one"));
                    return SkillResult.FAILED;
                }
            }
            context.put("pos", station);
        }

        if (!context.level.getBlockState(station).is(Blocks.CRAFTING_TABLE)) {
            // table was broken: search again from scratch
            context.data.remove("pos");
            context.data.remove("portable.pos");
            BlockScanner.reset(context);
            return SkillResult.RUNNING;
        }

        // Walk there, and make a way if walking will not do: a citizen standing
        // a few blocks from its own workbench should not report it unreachable
        // because a fence is in between.
        SkillResult arrival = SkillNavigation.approach(context, station, REACH_SQR, "craft.walk");
        if (arrival == SkillResult.FAILED && context.get("portable.pos", (BlockPos) null) == null) {
            // The far table cannot be reached. That is a reason to use our own,
            // not a reason to give up on the craft.
            BlockPos portable = setDownPortableTable(context);
            if (portable != null) {
                context.failure = null;
                BlockScanner.releaseStation(context);
                context.put("pos", portable);
                return SkillResult.RUNNING;
            }
        }
        return arrival;
    }

    /** A crafting table within a few blocks, at roughly the citizen's height. */
    private static BlockPos nearbyTable(SkillContext context) {
        BlockPos feet = context.citizen.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-NEARBY_TABLE, -2, -NEARBY_TABLE),
                feet.offset(NEARBY_TABLE, 2, NEARBY_TABLE))) {
            if (!context.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /**
     * Put the citizen's own workbench down next to it, making one first if it
     * has the wood. Returns where it went, or null when that is not possible.
     */
    private static BlockPos setDownPortableTable(SkillContext context) {
        CitizenInventory inventory = context.citizen.getInventory();
        if (inventory.count("minecraft:crafting_table") <= 0 && !makeTable(context, inventory)) {
            return null;
        }
        BlockPos spot = tableSpot(context);
        if (spot == null) return null;
        if (inventory.extract("minecraft:crafting_table", 1) != 1) return null;
        if (!context.level.setBlock(spot, Blocks.CRAFTING_TABLE.defaultBlockState(), 3)) {
            inventory.insert(new ItemStack(Items.CRAFTING_TABLE));
            return null;
        }
        context.citizen.animateAction(WorkAnimation.PLACE, spot);
        context.put("portable.pos", spot);
        return spot;
    }

    /** Two-by-two crafting, no table needed: log → planks → table. */
    private static boolean makeTable(SkillContext context, CitizenInventory inventory) {
        if (plankCount(inventory) < 4) {
            String log = null;
            for (String species : ai.minecivilization.architecture.Palette.SPECIES) {
                if (inventory.count("minecraft:" + species + "_log") > 0) {
                    log = species;
                    break;
                }
            }
            if (log == null) return false;
            if (!craftSimple(context, inventory, "minecraft:" + log + "_planks")) return false;
        }
        return plankCount(inventory) >= 4
                && craftSimple(context, inventory, "minecraft:crafting_table");
    }

    private static int plankCount(CitizenInventory inventory) {
        int planks = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            if (!stack.isEmpty() && stack.is(net.minecraft.tags.ItemTags.PLANKS)) {
                planks += stack.getCount();
            }
        }
        return planks;
    }

    /** One craft of a small recipe from the pack, all or nothing. */
    private static boolean craftSimple(SkillContext context, CitizenInventory inventory,
                                       String target) {
        for (RecipeHolder<CraftingRecipe> holder
                : context.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (needsTable(recipe)) continue;
            ItemStack result;
            try {
                result = recipe.getResultItem(context.level.registryAccess());
            } catch (RuntimeException ex) {
                continue;
            }
            if (result == null || result.isEmpty()
                    || !CitizenInventory.idOf(result).equals(target)) continue;
            Plan plan = planConsumption(inventory, recipe.getIngredients());
            if (plan == null || !canInsert(inventory, result)) continue;
            consume(inventory, plan.reserved());
            inventory.insert(result.copy());
            return true;
        }
        return false;
    }

    /** Solid ground beside the citizen with room for a table on it. */
    private static BlockPos tableSpot(SkillContext context) {
        BlockPos feet = context.citizen.blockPosition();
        var manager = ai.minecivilization.construction.ConstructionManager.get(context.level);
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
                BlockPos pos = feet.offset(d[0], dy, d[1]);
                var state = context.level.getBlockState(pos);
                if (!state.canBeReplaced() || !context.level.getFluidState(pos).isEmpty()) continue;
                if (!context.level.getBlockState(pos.below())
                        .isFaceSturdy(context.level, pos.below(), net.minecraft.core.Direction.UP)) {
                    continue;
                }
                if (manager.protectsCell(pos)) continue;
                return pos;
            }
        }
        return null;
    }

    /** Take the workbench we set down back into the pack. */
    private static void pickUpPortableTable(SkillContext context) {
        BlockPos pos = context.get("portable.pos", (BlockPos) null);
        if (pos == null) return;
        context.data.remove("portable.pos");
        if (!context.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return;
        ItemStack table = new ItemStack(Items.CRAFTING_TABLE);
        if (!canInsert(context.citizen.getInventory(), table)) return;   // leave it for the colony
        context.level.removeBlock(pos, false);
        context.citizen.getInventory().insert(table);
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
        context.citizen.animateAction(WorkAnimation.CRAFT,
                context.get("pos", (BlockPos) null));
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

    /**
     * A recipe for {@code target} that the citizen can actually pay for.
     *
     * <p>Most items have more than one recipe, and taking whichever the recipe
     * manager happens to list first is a trap: a stick can be made from planks
     * <em>or</em> from bamboo, so a citizen holding a stack of planks and no
     * bamboo would pick the bamboo recipe and report missing ingredients
     * forever. It did — a hundred and eight times in one session.</p>
     *
     * <p>Affordable recipes win. When none is affordable the first candidate is
     * returned anyway, so the failure names something the citizen was actually
     * trying to make rather than nothing at all.</p>
     */
    private RecipeHolder<CraftingRecipe> findRecipe(SkillContext context, String target) {
        CitizenInventory inventory = context.citizen.getInventory();
        RecipeHolder<CraftingRecipe> fallback = null;

        for (RecipeHolder<CraftingRecipe> holder
                : context.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack result;
            try {
                result = holder.value().getResultItem(context.level.registryAccess());
            } catch (RuntimeException ex) {
                continue;   // special recipes have no fixed result
            }
            if (result == null || result.isEmpty()) continue;
            if (!CitizenInventory.idOf(result).equals(target)) continue;

            if (fallback == null) fallback = holder;
            if (planConsumption(inventory, holder.value().getIngredients()) != null) {
                return holder;   // one we can pay for today
            }
        }
        return fallback;
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
        pickUpPortableTable(context);
        // Hand the workbench back. The claim would lapse on its own, but a
        // minute of a station nobody is standing at is a minute the colony
        // queues behind it.
        BlockScanner.releaseStation(context);
    }

    @Override
    public String progressLabel(SkillContext context) {
        String target = context.params.resource == null ? "?" : context.params.resource;
        int want = context.params.quantity > 0 ? context.params.quantity : 1;
        return "crafting " + target + " ("
                + context.citizen.getInventory().count(target) + "/" + want + ")";
    }
}
