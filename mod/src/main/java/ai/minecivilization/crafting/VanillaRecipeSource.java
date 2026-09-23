package ai.minecivilization.crafting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.minecivilization.inventory.CitizenInventory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The real recipe book: every vanilla crafting and smelting recipe, plus the
 * blocks that yield the raw materials those recipes bottom out in.
 *
 * <p>Nothing is invented here. Crafting and smelting come straight from the
 * {@link RecipeManager}, so a datapack that changes a recipe changes what
 * citizens plan. Mining is only ever offered for items no recipe produces —
 * otherwise a citizen would "obtain" oak planks by tearing them out of a
 * neighbour's wall.</p>
 *
 * <p>The index is built once per recipe manager and shared: scanning a couple
 * of thousand recipes on every node of a craft tree would be the slowest thing
 * in the mod.</p>
 */
public final class VanillaRecipeSource implements RecipeSource {

    /** Proper fuel: one lump carries eight items through the furnace. */
    private static final List<String> COAL_FUEL =
            List.of("minecraft:coal", "minecraft:charcoal");

    /**
     * Wood burns too, and a colony has wood long before it has coal.
     *
     * <p>Without this the fuel model was circular: charcoal is made by smelting
     * a log, so a colony with no coal could never make the charcoal it needed
     * to smelt anything — including the charcoal itself. A plank is worth one
     * and a half items in a furnace, counted as one so the fire never dies
     * halfway through a batch.</p>
     */
    private static final List<String> WOOD_FUEL = List.of(
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
            "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks",
            "minecraft:cherry_planks", "minecraft:mangrove_planks",
            "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
            "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
            "minecraft:cherry_log", "minecraft:mangrove_log");

    /**
     * Raw materials whose item differs from the block that drops it. Everything
     * else falls back to the block matching the item (logs, dirt, sand).
     */
    private static final Map<String, String> WORLD_SOURCES = Map.ofEntries(
            Map.entry("minecraft:cobblestone", "minecraft:stone"),
            Map.entry("minecraft:cobbled_deepslate", "minecraft:deepslate"),
            Map.entry("minecraft:raw_iron", "minecraft:iron_ore"),
            Map.entry("minecraft:raw_copper", "minecraft:copper_ore"),
            Map.entry("minecraft:raw_gold", "minecraft:gold_ore"),
            Map.entry("minecraft:coal", "minecraft:coal_ore"),
            Map.entry("minecraft:diamond", "minecraft:diamond_ore"),
            Map.entry("minecraft:emerald", "minecraft:emerald_ore"),
            Map.entry("minecraft:redstone", "minecraft:redstone_ore"),
            Map.entry("minecraft:lapis_lazuli", "minecraft:lapis_ore"),
            Map.entry("minecraft:quartz", "minecraft:nether_quartz_ore"),
            Map.entry("minecraft:flint", "minecraft:gravel"),
            Map.entry("minecraft:clay_ball", "minecraft:clay"),
            Map.entry("minecraft:wheat", "minecraft:wheat"),
            Map.entry("minecraft:apple", "minecraft:oak_leaves"));

    // Rebuilt whenever the server swaps recipe managers (datapack reload).
    private static RecipeManager indexedManager;
    private static Map<String, List<Production>> index = Map.of();

    private final Map<String, List<Production>> recipes;

    public VanillaRecipeSource(ServerLevel level) {
        this.recipes = indexFor(level);
    }

    @Override
    public List<Production> productionsOf(String itemId) {
        List<Production> known = recipes.get(itemId);
        if (known != null && !known.isEmpty()) return known;
        Production mined = worldSource(itemId);
        return mined == null ? List.of() : List.of(mined);
    }

    /** A citizen's carried items, in the shape the planner expects. */
    public static Map<String, Integer> inventorySnapshot(CitizenInventory inventory) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            counts.merge(CitizenInventory.idOf(stack), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------ indexing

    private static synchronized Map<String, List<Production>> indexFor(ServerLevel level) {
        RecipeManager manager = level.getRecipeManager();
        if (manager == indexedManager) return index;

        Map<String, List<Production>> built = new LinkedHashMap<>();
        var registries = level.registryAccess();

        for (RecipeHolder<CraftingRecipe> holder : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            ItemStack result;
            try {
                result = recipe.getResultItem(registries);
            } catch (RuntimeException ex) {
                continue; // special recipes (map cloning, fireworks) have no fixed result
            }
            if (result == null || result.isEmpty()) continue;
            List<Production.Need> needs = needsOf(recipe.getIngredients());
            if (needs.isEmpty()) continue;
            built.computeIfAbsent(CitizenInventory.idOf(result), k -> new ArrayList<>())
                    .add(Production.craft(CitizenInventory.idOf(result), result.getCount(),
                            needs, needsTable(recipe)));
        }

        Production.Need coal = new Production.Need(COAL_FUEL, 1);
        Production.Need wood = new Production.Need(WOOD_FUEL, 1);
        for (RecipeHolder<SmeltingRecipe> holder : manager.getAllRecipesFor(RecipeType.SMELTING)) {
            SmeltingRecipe recipe = holder.value();
            ItemStack result;
            try {
                result = recipe.getResultItem(registries);
            } catch (RuntimeException ex) {
                continue;
            }
            if (result == null || result.isEmpty()) continue;
            List<Production.Need> inputs = needsOf(recipe.getIngredients());
            if (inputs.size() != 1) continue;
            // Two ways to run the same furnace. The planner ranks by what the
            // citizen can pay for, so a colony with coal uses coal and a colony
            // with only wood still gets to smelt.
            String output = CitizenInventory.idOf(result);
            List<Production> ways = built.computeIfAbsent(output, k -> new ArrayList<>());
            // Without stocked fuel, bootstrap charcoal from wood instead of
            // planning a coal expedition to make the first charcoal.
            if (output.equals("minecraft:charcoal")) {
                ways.add(Production.smelt(output, inputs.get(0), wood, 1));
                ways.add(Production.smelt(output, inputs.get(0), coal));
            } else {
                ways.add(Production.smelt(output, inputs.get(0), coal));
                ways.add(Production.smelt(output, inputs.get(0), wood, 1));
            }
        }

        indexedManager = manager;
        index = Map.copyOf(built);
        return index;
    }

    /**
     * Collapse a recipe's ingredient slots into needs: three slots that all
     * accept "any plank" become one need for three planks.
     */
    private static List<Production.Need> needsOf(List<Ingredient> ingredients) {
        Map<String, List<String>> optionsByKey = new LinkedHashMap<>();
        Map<String, Integer> countByKey = new LinkedHashMap<>();

        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            List<String> options = Arrays.stream(ingredient.getItems())
                    .filter(stack -> !stack.isEmpty())
                    .map(CitizenInventory::idOf)
                    .distinct()
                    .sorted()
                    .toList();
            if (options.isEmpty()) return List.of(); // unresolvable ingredient: skip the recipe
            String key = String.join("|", options);
            optionsByKey.putIfAbsent(key, options);
            countByKey.merge(key, 1, Integer::sum);
        }

        List<Production.Need> needs = new ArrayList<>(countByKey.size());
        for (Map.Entry<String, Integer> entry : countByKey.entrySet()) {
            needs.add(new Production.Need(optionsByKey.get(entry.getKey()), entry.getValue()));
        }
        return needs;
    }

    /** Vanilla rule, same as {@code CraftItemSkill}: only patterns over 2x2 need a table. */
    private static boolean needsTable(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getRecipeWidth() > 2 || shaped.getRecipeHeight() > 2;
        }
        return recipe.getIngredients().size() > 4;
    }

    // ------------------------------------------------------------------ raw materials

    /**
     * The block to break for an item no recipe produces. Returns null when the
     * item has no block form at all, which is how the planner learns that
     * something is genuinely out of reach.
     */
    static Production worldSource(String itemId) {
        String override = WORLD_SOURCES.get(itemId);
        if (override != null) {
            return Production.mine(itemId, override);
        }
        var item = CitizenInventory.itemById(itemId);
        if (item == null || item == Items.AIR) return null;
        Block block = Block.byItem(item);
        if (block == null || block == Blocks.AIR) return null;
        return Production.mine(itemId, itemId);
    }
}
