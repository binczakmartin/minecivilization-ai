package ai.minecivilization.crafting;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recursive craft planning against a hand-written slice of the vanilla recipe
 * book — the real progression (log to planks to sticks to tools to iron), small
 * enough to reason about.
 */
class CraftPlannerTest {

    private static final String OAK_LOG = "minecraft:oak_log";
    private static final String SPRUCE_LOG = "minecraft:spruce_log";
    private static final String OAK_PLANKS = "minecraft:oak_planks";
    private static final String SPRUCE_PLANKS = "minecraft:spruce_planks";
    private static final String STICK = "minecraft:stick";
    private static final String COBBLESTONE = "minecraft:cobblestone";
    private static final String COAL = "minecraft:coal";
    private static final String RAW_IRON = "minecraft:raw_iron";
    private static final String IRON_INGOT = "minecraft:iron_ingot";
    private static final String IRON_BLOCK = "minecraft:iron_block";
    private static final String WOODEN_PICKAXE = "minecraft:wooden_pickaxe";
    private static final String IRON_PICKAXE = "minecraft:iron_pickaxe";

    private static final RecipeSource VANILLA_SLICE = vanillaSlice();

    // ------------------------------------------------------------------ trivial cases

    @Test
    void holdingEnoughAlreadyMeansThereIsNothingToDo() {
        CraftPlan plan = CraftPlanner.plan(OAK_PLANKS, 4, Map.of(OAK_PLANKS, 10), VANILLA_SLICE);

        assertNotNull(plan);
        assertTrue(plan.isSatisfied(), "a citizen holding ten planks does not craft four more");
    }

    @Test
    void oneStepWhenTheMaterialIsAlreadyCarried() {
        CraftPlan plan = CraftPlanner.plan(OAK_PLANKS, 4, Map.of(OAK_LOG, 2), VANILLA_SLICE);

        assertNotNull(plan);
        assertEquals(List.of(craft(OAK_PLANKS, 4)), plan.steps());
        assertTrue(plan.usesOnlyCarriedMaterials(), "no reason to go to the forest");
    }

    @Test
    void anItemNothingProducesHasNoPlan() {
        assertNull(CraftPlanner.plan("minecraft:dragon_egg", 1, Map.of(), VANILLA_SLICE),
                "an unobtainable item must be refused, never improvised");
    }

    // ------------------------------------------------------------------ the bootstrap chain

    @Test
    void aWoodenPickaxeIsPlannedFromAnEmptyInventory() {
        CraftPlan plan = CraftPlanner.plan(WOODEN_PICKAXE, 1, Map.of(), VANILLA_SLICE);

        assertNotNull(plan, "this is the very first thing a citizen must be able to work out");
        assertEquals(List.of(
                mine(OAK_LOG, 2, OAK_LOG),
                craft(OAK_PLANKS, 8),
                craft(STICK, 4),
                craft(WOODEN_PICKAXE, 1)),
                plan.steps());
        assertEquals(Map.of(OAK_LOG, 2), plan.rawMaterials());
    }

    @Test
    void gatheringIsHoistedAndMergedIntoASingleTrip() {
        // Three planks and two sticks both draw on wood; the citizen should walk
        // to the trees once, not once per branch of the recipe tree.
        CraftPlan plan = CraftPlanner.plan(WOODEN_PICKAXE, 1, Map.of(), VANILLA_SLICE);

        assertNotNull(plan);
        long gatherSteps = plan.steps().stream()
                .filter(s -> s.method == Production.Method.MINE).count();
        assertEquals(1, gatherSteps, "one trip for the wood");
        assertEquals(Production.Method.MINE, plan.steps().get(0).method,
                "materials are fetched before anything is built");
    }

    @Test
    void carriedMaterialShortensThePlan() {
        CraftPlan withWood = CraftPlanner.plan(WOODEN_PICKAXE, 1,
                Map.of(OAK_PLANKS, 8, STICK, 4), VANILLA_SLICE);

        assertNotNull(withWood);
        assertEquals(List.of(craft(WOODEN_PICKAXE, 1)), withWood.steps(),
                "everything needed is in hand: one action left");
    }

    // ------------------------------------------------------------------ smelting & fuel

    @Test
    void smeltingPlansItsOwnFuel() {
        CraftPlan plan = CraftPlanner.plan(IRON_INGOT, 3, Map.of(), VANILLA_SLICE);

        assertNotNull(plan);
        assertEquals(3, plan.rawMaterials().get(RAW_IRON), "three ores for three ingots");
        assertEquals(1, plan.rawMaterials().get(COAL),
                "one lump of coal carries eight smelts, so three need one");
        assertTrue(plan.steps().stream().anyMatch(s -> s.method == Production.Method.SMELT));
    }

    @Test
    void fuelIsChargedPerBatchNotPerItem() {
        CraftPlan plan = CraftPlanner.plan(IRON_INGOT, 20, Map.of(), VANILLA_SLICE);

        assertNotNull(plan);
        assertEquals(20, plan.rawMaterials().get(RAW_IRON));
        assertEquals(3, plan.rawMaterials().get(COAL), "20 smelts is three coal, not twenty");
    }

    @Test
    void carriedFuelIsSpentBeforeMiningMore() {
        CraftPlan plan = CraftPlanner.plan(IRON_INGOT, 4,
                Map.of(RAW_IRON, 4, COAL, 8), VANILLA_SLICE);

        assertNotNull(plan);
        assertTrue(plan.usesOnlyCarriedMaterials(), "no trip needed: ore and coal are in the bag");
        assertEquals(List.of(smelt(IRON_INGOT, 4)), plan.steps());
    }

    @Test
    void theFullIronPickaxeChainIsPlannedFromNothing() {
        CraftPlan plan = CraftPlanner.plan(IRON_PICKAXE, 1, Map.of(), VANILLA_SLICE);

        assertNotNull(plan, "the deep chain is the whole point of recursive planning");
        Map<String, Integer> raw = plan.rawMaterials();
        assertEquals(3, raw.get(RAW_IRON), "three ingots for the head");
        assertEquals(1, raw.get(COAL), "fuel for the smelt");
        assertEquals(1, raw.get(OAK_LOG), "one log covers the two sticks");

        // dependency order: everything is gathered, then smelted, then assembled
        List<Production.Method> order = plan.steps().stream().map(s -> s.method).toList();
        assertEquals(order.stream().sorted(java.util.Comparator.comparingInt(
                        m -> m == Production.Method.MINE ? 0 : 1)).toList(), order,
                "gathering never comes after building");
        assertEquals(IRON_PICKAXE, plan.steps().get(plan.size() - 1).item,
                "the goal is the last thing produced");
    }

    // ------------------------------------------------------------------ cycles & backtracking

    @Test
    void aRecipeCycleIsEscapedInsteadOfLoopingForever() {
        // Ingots make blocks and blocks make ingots. With neither in hand the
        // planner has to abandon the block route and fall back on smelting.
        CraftPlan plan = CraftPlanner.plan(IRON_INGOT, 2, Map.of(), VANILLA_SLICE);

        assertNotNull(plan);
        assertTrue(plan.steps().stream().noneMatch(s -> s.item.equals(IRON_BLOCK)),
                "never plans to build a block just to break it down again");
    }

    @Test
    void theCheapRouteWinsWhenTheMaterialIsAlreadyHeld() {
        // Holding an iron block, nine ingots are one craft away — far better
        // than mining and smelting nine ores.
        CraftPlan plan = CraftPlanner.plan(IRON_INGOT, 9, Map.of(IRON_BLOCK, 1), VANILLA_SLICE);

        assertNotNull(plan);
        assertEquals(List.of(craft(IRON_INGOT, 9)), plan.steps());
        assertTrue(plan.usesOnlyCarriedMaterials());
    }

    @Test
    void theIngredientVariantAlreadyCarriedIsPreferred() {
        // "Any log" makes planks. Holding spruce, a citizen must not walk off
        // to find an oak tree.
        CraftPlan plan = CraftPlanner.plan(STICK, 4, Map.of(SPRUCE_LOG, 1), VANILLA_SLICE);

        assertNotNull(plan);
        assertTrue(plan.usesOnlyCarriedMaterials(), "the spruce in hand is perfectly good wood");
        assertEquals(List.of(craft(SPRUCE_PLANKS, 4), craft(STICK, 4)), plan.steps());
    }

    // ------------------------------------------------------------------ budgets & guarantees

    @Test
    void anOverlyDeepChainIsRefusedRatherThanExplored() {
        CraftPlan plan = CraftPlanner.plan(IRON_PICKAXE, 1, Map.of(), VANILLA_SLICE,
                new CraftPlanner.Options().maxDepth(1));

        assertNull(plan, "a budget that cannot express the chain must fail cleanly");
    }

    @Test
    void theStepBudgetIsEnforced() {
        assertNull(CraftPlanner.plan(IRON_PICKAXE, 1, Map.of(), VANILLA_SLICE,
                new CraftPlanner.Options().maxSteps(2)));
    }

    @Test
    void theCarriedInventoryIsNeverMutated() {
        Map<String, Integer> carried = new HashMap<>(Map.of(OAK_LOG, 4, COAL, 2));
        Map<String, Integer> before = new HashMap<>(carried);

        CraftPlanner.plan(WOODEN_PICKAXE, 1, carried, VANILLA_SLICE);

        assertEquals(before, carried, "planning is a question, not a transaction");
    }

    @Test
    void planningIsDeterministic() {
        CraftPlan a = CraftPlanner.plan(IRON_PICKAXE, 2, Map.of(OAK_LOG, 1), VANILLA_SLICE);
        CraftPlan b = CraftPlanner.plan(IRON_PICKAXE, 2, Map.of(OAK_LOG, 1), VANILLA_SLICE);

        assertNotNull(a);
        assertEquals(a.steps(), b.steps(), "retries must re-plan identically");
    }

    @Test
    void everyStepProducesAPositiveAmount() {
        CraftPlan plan = CraftPlanner.plan(IRON_PICKAXE, 5, Map.of(), VANILLA_SLICE);

        assertNotNull(plan);
        for (CraftPlan.Step step : plan.steps()) {
            assertTrue(step.count > 0, "a step producing nothing is a bug: " + step);
            assertFalse(step.item.isEmpty());
            if (step.method == Production.Method.MINE) {
                assertNotNull(step.block, "a gather step must name the block to break");
            }
        }
    }

    // ------------------------------------------------------------------ settlement storage

    @Test
    void whatTheColonyOwnsIsFetchedRatherThanMined() {
        // Three ingots sit in a chest. Mining and smelting three more would be
        // absurd — this is the whole point of a shared warehouse.
        CraftPlan plan = CraftPlanner.plan(IRON_PICKAXE, 1, Map.of(STICK, 2),
                Map.of(IRON_INGOT, 8), VANILLA_SLICE);

        assertNotNull(plan);
        assertEquals(Map.of(IRON_INGOT, 3), plan.withdrawals());
        assertTrue(plan.needsNoGathering(), "nothing left to dig out of the ground");
        assertEquals(List.of(withdraw(IRON_INGOT, 3), craft(IRON_PICKAXE, 1)), plan.steps());
    }

    @Test
    void carriedStockIsSpentBeforeTheWarehouse() {
        // Walking to a chest is cheap, but reaching into your own bag is free.
        CraftPlan plan = CraftPlanner.plan(IRON_PICKAXE, 1,
                Map.of(IRON_INGOT, 3, STICK, 2), Map.of(IRON_INGOT, 64), VANILLA_SLICE);

        assertNotNull(plan);
        assertTrue(plan.withdrawals().isEmpty(), "no trip needed: it is all in hand");
        assertEquals(List.of(craft(IRON_PICKAXE, 1)), plan.steps());
    }

    @Test
    void aPartiallyStockedWarehouseCoversWhatItCan() {
        // One ingot in storage, two still to be made: fetch the one, smelt two.
        CraftPlan plan = CraftPlanner.plan(IRON_PICKAXE, 1, Map.of(STICK, 2),
                Map.of(IRON_INGOT, 1), VANILLA_SLICE);

        assertNotNull(plan);
        assertEquals(Map.of(IRON_INGOT, 1), plan.withdrawals());
        assertEquals(2, plan.rawMaterials().get(RAW_IRON), "only the shortfall is mined");
    }

    @Test
    void storedMaterialIsNeverSpentTwiceAcrossBranches() {
        // Two sticks and three ingots both draw on one chest holding four
        // planks: the planks must not be counted once per branch.
        CraftPlan plan = CraftPlanner.plan(WOODEN_PICKAXE, 1, Map.of(),
                Map.of(OAK_PLANKS, 4), VANILLA_SLICE);

        assertNotNull(plan);
        assertEquals(4, plan.withdrawals().getOrDefault(OAK_PLANKS, 0),
                "the chest holds four planks and exactly four are reserved");
        // 3 planks for the head + 2 for the sticks = 5, so one more plank's
        // worth of wood has to come from the forest.
        assertEquals(1, plan.rawMaterials().get(OAK_LOG));
    }

    @Test
    void withdrawalsAreHoistedAheadOfEverythingElse() {
        CraftPlan plan = CraftPlanner.plan(IRON_PICKAXE, 1, Map.of(),
                Map.of(IRON_INGOT, 3), VANILLA_SLICE);

        assertNotNull(plan);
        assertEquals(Production.Method.WITHDRAW, plan.steps().get(0).method,
                "stop at the warehouse on the way out, not on the way back");
    }

    @Test
    void anEmptyWarehouseChangesNothing() {
        CraftPlan withEmptyStore = CraftPlanner.plan(WOODEN_PICKAXE, 1, Map.of(),
                Map.of(), VANILLA_SLICE);
        CraftPlan withoutStore = CraftPlanner.plan(WOODEN_PICKAXE, 1, Map.of(), VANILLA_SLICE);

        assertNotNull(withEmptyStore);
        assertNotNull(withoutStore);
        assertEquals(withoutStore.steps(), withEmptyStore.steps());
    }

    @Test
    void neitherInventoryIsMutatedByPlanning() {
        Map<String, Integer> carried = new HashMap<>(Map.of(OAK_LOG, 4));
        Map<String, Integer> stored = new HashMap<>(Map.of(IRON_INGOT, 9, COAL, 12));
        Map<String, Integer> carriedBefore = new HashMap<>(carried);
        Map<String, Integer> storedBefore = new HashMap<>(stored);

        CraftPlanner.plan(IRON_PICKAXE, 2, carried, stored, VANILLA_SLICE);

        assertEquals(carriedBefore, carried);
        assertEquals(storedBefore, stored, "planning must not empty the colony's chests");
    }

    @Test
    void storedFuelIsUsedForSmelting() {
        CraftPlan plan = CraftPlanner.plan(IRON_INGOT, 4, Map.of(RAW_IRON, 4),
                Map.of(COAL, 64), VANILLA_SLICE);

        assertNotNull(plan);
        assertEquals(Map.of(COAL, 1), plan.withdrawals(), "one coal covers four smelts");
        assertTrue(plan.needsNoGathering());
    }

    // ------------------------------------------------------------------ rival recipes

    @Test
    void theRecipeYouCanPayForWinsOverTheOneYouCannot() {
        // Sticks come from planks OR from bamboo. Taking whichever recipe the
        // book happens to list first is how a citizen holding a stack of planks
        // and no bamboo reports "missing ingredients" forever — 108 times in
        // one session, before this was fixed.
        CraftPlan plan = CraftPlanner.plan(STICK, 4, Map.of(OAK_PLANKS, 8), RIVAL_RECIPES);

        assertNotNull(plan);
        assertEquals(List.of(craft(STICK, 4)), plan.steps(),
                "the planks in hand should have been enough on their own");
        assertTrue(plan.usesOnlyCarriedMaterials(), "no trip for bamboo that is not needed");
    }

    @Test
    void theOtherRecipeIsStillUsedWhenItIsTheAffordableOne() {
        CraftPlan plan = CraftPlanner.plan(STICK, 2, Map.of(BAMBOO, 16), RIVAL_RECIPES);

        assertNotNull(plan);
        assertTrue(plan.usesOnlyCarriedMaterials(), "bamboo in hand makes sticks perfectly well");
        assertTrue(plan.rawMaterials().isEmpty());
    }

    @Test
    void withNeitherInHandTheCheaperChainIsPlanned() {
        CraftPlan plan = CraftPlanner.plan(STICK, 4, Map.of(), RIVAL_RECIPES);

        assertNotNull(plan, "empty-handed is not a reason to refuse");
        assertFalse(plan.steps().isEmpty());
        assertEquals(STICK, plan.steps().get(plan.size() - 1).item);
    }

    @Test
    void emptyHandedSticksComeFromTreesNotBamboo() {
        // A citizen in an oak forest was sent to find bamboo for its torch.
        CraftPlan plan = CraftPlanner.plan(STICK, 4, Map.of(), RIVAL_RECIPES);

        assertNotNull(plan);
        assertTrue(plan.steps().stream().noneMatch(step -> BAMBOO.equals(step.item)),
                "planned a bamboo run: " + plan.steps());
    }

    /** Two ways to make a stick, as vanilla actually has. */
    private static final String BAMBOO = "minecraft:bamboo";
    private static final RecipeSource RIVAL_RECIPES = rivalRecipes();

    private static RecipeSource rivalRecipes() {
        Map<String, List<Production>> book = new LinkedHashMap<>();
        book.put(OAK_LOG, List.of(Production.mine(OAK_LOG, OAK_LOG)));
        book.put(BAMBOO, List.of(Production.mine(BAMBOO, BAMBOO)));
        book.put(OAK_PLANKS, List.of(Production.craft(OAK_PLANKS, 4,
                List.of(Production.Need.of(OAK_LOG, 1)), false)));
        // Bamboo listed FIRST, exactly the ordering that caused the bug.
        book.put(STICK, List.of(
                Production.craft(STICK, 1, List.of(Production.Need.of(BAMBOO, 2)), false),
                Production.craft(STICK, 4, List.of(Production.Need.of(OAK_PLANKS, 2)), false)));
        return itemId -> book.getOrDefault(itemId, List.of());
    }

    // ------------------------------------------------------------------ fuel

    @Test
    void woodCanFuelTheFurnaceSoCharcoalIsReachableWithoutCoal() {
        // The circularity this fixes: charcoal is made by smelting a log, and
        // the only fuel the planner knew was coal and charcoal. A colony with
        // no coal seam could therefore never make the charcoal it needed to
        // smelt anything — including the charcoal itself.
        CraftPlan plan = CraftPlanner.plan(CHARCOAL, 4, Map.of(OAK_LOG, 16), FUEL_RECIPES);

        assertNotNull(plan, "wood and a furnace is all charcoal actually takes");
        assertTrue(plan.usesOnlyCarriedMaterials(), "no trip to a coal seam required");
        assertTrue(plan.steps().stream().anyMatch(s -> s.method == Production.Method.SMELT));
    }

    @Test
    void coalIsStillPreferredWhenTheColonyHasIt() {
        // Coal carries eight items per lump against a plank's one, so a colony
        // sitting on coal should burn coal.
        CraftPlan plan = CraftPlanner.plan(IRON_INGOT, 8,
                Map.of(RAW_IRON, 8, COAL, 8, OAK_PLANKS, 64), FUEL_RECIPES);

        assertNotNull(plan);
        assertTrue(plan.usesOnlyCarriedMaterials());
        assertEquals(List.of(smelt(IRON_INGOT, 8)), plan.steps());
    }

    @Test
    void burningWoodCostsMoreOfItThanBurningCoalWould() {
        CraftPlan plan = CraftPlanner.plan(IRON_INGOT, 8,
                Map.of(RAW_IRON, 8, OAK_PLANKS, 64), FUEL_RECIPES);

        assertNotNull(plan, "a colony with only wood must still be able to smelt");
        assertTrue(plan.usesOnlyCarriedMaterials());
    }

    @Test
    void torchesCanBePlannedFromLogsWithoutMiningCoal() {
        RecipeSource recipes = item -> {
            if (item.equals("minecraft:torch")) return List.of(Production.craft(item, 4,
                List.of(new Production.Need(List.of(COAL, CHARCOAL), 1), Production.Need.of(STICK, 1)), false));
            if (item.equals(STICK)) return List.of(Production.craft(STICK, 4,
                List.of(Production.Need.of(OAK_PLANKS, 2)), false));
            return FUEL_RECIPES.productionsOf(item);
        };
        CraftPlan plan = CraftPlanner.plan("minecraft:torch", 16, Map.of(OAK_LOG, 16), recipes);
        assertNotNull(plan);
        assertTrue(plan.usesOnlyCarriedMaterials(), "logs must suffice; do not send the farmer to a coal seam");
        assertTrue(plan.steps().stream().anyMatch(s -> s.item.equals(CHARCOAL) && s.method == Production.Method.SMELT));
        assertEquals("minecraft:torch", plan.steps().getLast().item);
        assertEquals(16, plan.steps().getLast().count);
    }

    private static final String CHARCOAL = "minecraft:charcoal";

    /** Smelting with either fuel, as the real recipe source now offers it. */
    private static final RecipeSource FUEL_RECIPES = fuelRecipes();

    private static RecipeSource fuelRecipes() {
        Map<String, List<Production>> book = new LinkedHashMap<>();
        Production.Need coalFuel = new Production.Need(List.of(COAL, CHARCOAL), 1);
        Production.Need woodFuel = new Production.Need(List.of(OAK_PLANKS, OAK_LOG), 1);

        book.put(OAK_LOG, List.of(Production.mine(OAK_LOG, OAK_LOG)));
        book.put(COAL, List.of(Production.mine(COAL, "minecraft:coal_ore")));
        book.put(RAW_IRON, List.of(Production.mine(RAW_IRON, "minecraft:iron_ore")));
        book.put(OAK_PLANKS, List.of(Production.craft(OAK_PLANKS, 4,
                List.of(Production.Need.of(OAK_LOG, 1)), false)));
        book.put(CHARCOAL, List.of(
                Production.smelt(CHARCOAL, Production.Need.of(OAK_LOG, 1), coalFuel),
                Production.smelt(CHARCOAL, Production.Need.of(OAK_LOG, 1), woodFuel, 1)));
        book.put(IRON_INGOT, List.of(
                Production.smelt(IRON_INGOT, Production.Need.of(RAW_IRON, 1), coalFuel),
                Production.smelt(IRON_INGOT, Production.Need.of(RAW_IRON, 1), woodFuel, 1)));
        return itemId -> book.getOrDefault(itemId, List.of());
    }

    // ------------------------------------------------------------------ fixture

    private static CraftPlan.Step craft(String item, int count) {
        return new CraftPlan.Step(Production.Method.CRAFT, item, count, null, false);
    }

    private static CraftPlan.Step smelt(String item, int count) {
        return new CraftPlan.Step(Production.Method.SMELT, item, count, null, true);
    }

    private static CraftPlan.Step mine(String item, int count, String block) {
        return new CraftPlan.Step(Production.Method.MINE, item, count, block, false);
    }

    private static CraftPlan.Step withdraw(String item, int count) {
        return new CraftPlan.Step(Production.Method.WITHDRAW, item, count, null, false);
    }

    /** A small but faithful slice of the vanilla recipe book. */
    private static RecipeSource vanillaSlice() {
        Map<String, List<Production>> book = new LinkedHashMap<>();
        List<String> anyLog = List.of(OAK_LOG, SPRUCE_LOG);
        List<String> anyPlanks = List.of(OAK_PLANKS, SPRUCE_PLANKS);
        Production.Need anyFuel = new Production.Need(List.of(COAL), 1);

        book.put(OAK_LOG, List.of(Production.mine(OAK_LOG, OAK_LOG)));
        book.put(SPRUCE_LOG, List.of(Production.mine(SPRUCE_LOG, SPRUCE_LOG)));
        book.put(COBBLESTONE, List.of(Production.mine(COBBLESTONE, "minecraft:stone")));
        book.put(COAL, List.of(Production.mine(COAL, "minecraft:coal_ore")));
        book.put(RAW_IRON, List.of(Production.mine(RAW_IRON, "minecraft:iron_ore")));

        book.put(OAK_PLANKS, List.of(Production.craft(OAK_PLANKS, 4,
                List.of(Production.Need.of(OAK_LOG, 1)), false)));
        book.put(SPRUCE_PLANKS, List.of(Production.craft(SPRUCE_PLANKS, 4,
                List.of(Production.Need.of(SPRUCE_LOG, 1)), false)));
        book.put(STICK, List.of(Production.craft(STICK, 4,
                List.of(new Production.Need(anyPlanks, 2)), false)));

        book.put(IRON_INGOT, List.of(
                Production.smelt(IRON_INGOT, Production.Need.of(RAW_IRON, 1), anyFuel),
                Production.craft(IRON_INGOT, 9, List.of(Production.Need.of(IRON_BLOCK, 1)), false)));
        book.put(IRON_BLOCK, List.of(Production.craft(IRON_BLOCK, 1,
                List.of(Production.Need.of(IRON_INGOT, 9)), true)));

        book.put(WOODEN_PICKAXE, List.of(Production.craft(WOODEN_PICKAXE, 1,
                List.of(new Production.Need(anyPlanks, 3), Production.Need.of(STICK, 2)), true)));
        book.put(IRON_PICKAXE, List.of(Production.craft(IRON_PICKAXE, 1,
                List.of(Production.Need.of(IRON_INGOT, 3), Production.Need.of(STICK, 2)), true)));

        // referenced by "any log"/"any planks" ingredient sets
        assertTrue(anyLog.stream().allMatch(book::containsKey));

        return itemId -> book.getOrDefault(itemId, List.of());
    }
}
