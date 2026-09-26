package ai.minecivilization.crafting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns "I want an iron pickaxe" into an ordered list of things a citizen
 * already knows how to do.
 *
 * <p>Before this, a citizen could only craft what it could pay for on the spot:
 * asked for a pickaxe with an empty inventory it reported MISSING_RESOURCE and
 * gave up. The planner instead walks the recipe tree down to raw materials and
 * emits the withdraw/gather/smelt/craft steps in dependency order.</p>
 *
 * <p>It spends in the order a sensible person would: what is <em>in hand</em>
 * first, then what the <em>settlement already owns</em> (a walk to the
 * warehouse), and only then what must be dug out of the world. That single
 * ordering is what stops a colony re-mining iron it has three chests of.</p>
 *
 * <p>Recipe trees are not trees: iron ingots make iron blocks and iron blocks
 * make iron ingots. Resolution therefore tracks what is already being produced
 * and backtracks out of cycles and dead ends onto the next candidate recipe,
 * under hard depth, length and search budgets so planning always terminates.</p>
 *
 * <p>Pure — recipes arrive through {@link RecipeSource} and both inventories as
 * plain maps, so the whole thing is unit-tested without a world.</p>
 */
public final class CraftPlanner {

    public static final class Options {
        /** How deep a recipe tree may go before the plan is declared too complex. */
        public int maxDepth = 8;
        /** Hard ceiling on plan length — a citizen executing 60 steps is lost, not clever. */
        public int maxSteps = 48;
        /**
         * Hard ceiling on search work. Real ingredients accept wide item sets
         * ("any plank", "any wool"), so backtracking must be budgeted in
         * expansions, not just in depth, or one odd recipe stalls a tick.
         */
        public int maxExpansions = 4_000;

        public Options maxDepth(int depth) {
            this.maxDepth = depth;
            return this;
        }

        public Options maxSteps(int steps) {
            this.maxSteps = steps;
            return this;
        }
    }

    private CraftPlanner() {
    }

    /**
     * Plan the production of {@code quantity} x {@code target}.
     *
     * @param carried what the citizen holds (never mutated)
     * @param stored  what reachable settlement containers hold (never mutated)
     * @return the plan, or {@code null} when the item cannot be produced within
     *         the budget from what is reachable
     */
    public static CraftPlan plan(String target, int quantity, Map<String, Integer> carried,
                                 Map<String, Integer> stored, RecipeSource recipes,
                                 Options options) {
        if (target == null || recipes == null) return null;
        int want = Math.max(1, quantity);
        Options opts = options == null ? new Options() : options;

        Pools pools = new Pools(copyPositive(carried), copyPositive(stored));
        List<CraftPlan.Step> steps = new ArrayList<>();
        Context context = new Context(recipes, opts);

        if (!produce(target, want, pools, steps, new ArrayDeque<>(), 0, context)) {
            return null;
        }
        return new CraftPlan(target, want, merge(steps));
    }

    public static CraftPlan plan(String target, int quantity, Map<String, Integer> carried,
                                 Map<String, Integer> stored, RecipeSource recipes) {
        return plan(target, quantity, carried, stored, recipes, new Options());
    }

    /** Plan against the citizen's own inventory only — no warehouse in reach. */
    public static CraftPlan plan(String target, int quantity, Map<String, Integer> carried,
                                 RecipeSource recipes, Options options) {
        return plan(target, quantity, carried, Map.of(), recipes, options);
    }

    public static CraftPlan plan(String target, int quantity, Map<String, Integer> carried,
                                 RecipeSource recipes) {
        return plan(target, quantity, carried, Map.of(), recipes, new Options());
    }

    // ------------------------------------------------------------------ resolution

    /**
     * Make {@code need} of {@code item} exist.
     *
     * @return true with {@code pools} and {@code out} advanced; false with both
     *         restored exactly as they were on entry
     */
    private static boolean produce(String item, int need, Pools pools,
                                   List<CraftPlan.Step> out, Deque<String> inProgress,
                                   int depth, Context context) {
        if (need <= 0) return true;

        Pools entry = pools.copy();
        int entrySteps = out.size();

        // 1. What the citizen is carrying costs nothing to use.
        int missing = need - take(pools.carried, item, need);

        // 2. What the settlement owns costs a walk to the warehouse — far
        //    cheaper than a trip to the mine, and it stops the colony from
        //    producing what it already has.
        if (missing > 0) {
            int fromStore = take(pools.stored, item, missing);
            if (fromStore > 0) {
                out.add(new CraftPlan.Step(Production.Method.WITHDRAW, item,
                        fromStore, null, false));
                missing -= fromStore;
            }
        }
        if (missing == 0) return true;

        if (depth > context.options.maxDepth || inProgress.contains(item)
                || ++context.expansions > context.options.maxExpansions) {
            pools.restore(entry);
            truncate(out, entrySteps);
            return false;
        }

        inProgress.push(item);
        try {
            for (Production candidate : rank(context.recipes.productionsOf(item), pools)) {
                Pools saved = pools.copy();
                int savedSteps = out.size();

                int times = ceilDiv(missing, candidate.outputCount);
                boolean ok = true;
                for (Production.Need ingredient : candidate.inputs) {
                    if (!resolveNeed(ingredient, ingredient.count * times, pools, out,
                            inProgress, depth + 1, context)) {
                        ok = false;
                        break;
                    }
                }
                // A furnace also has to burn something. One coal carries eight
                // operations, so fuel is charged per batch, not per item.
                if (ok && candidate.fuel != null) {
                    int batches = ceilDiv(times, candidate.fuelSmeltsPerUnit);
                    ok = resolveNeed(candidate.fuel, batches * candidate.fuel.count,
                            pools, out, inProgress, depth + 1, context);
                }

                int produced = times * candidate.outputCount;
                if (ok && out.size() < context.options.maxSteps) {
                    out.add(new CraftPlan.Step(candidate.method, item, produced,
                            candidate.sourceBlock, candidate.needsWorkstation));
                    if (produced > missing) {
                        // anything over the requirement stays available to later steps
                        pools.carried.merge(item, produced - missing, Integer::sum);
                    }
                    return true;
                }
                pools.restore(saved);
                truncate(out, savedSteps);
            }
        } finally {
            inProgress.pop();
        }

        pools.restore(entry);
        truncate(out, entrySteps);
        return false;
    }

    /**
     * Satisfy one ingredient slot. Vanilla ingredients accept a set of items
     * ("any log"), so the option closest to hand is tried first and the rest
     * serve as fallbacks.
     */
    private static boolean resolveNeed(Production.Need need, int count, Pools pools,
                                       List<CraftPlan.Step> out, Deque<String> inProgress,
                                       int depth, Context context) {
        List<String> options = new ArrayList<>(need.options);
        options.sort(Comparator.comparingInt((String o) -> affinity(o, pools, context))
                .thenComparingInt(o -> -pools.available(o))
                .thenComparing(Comparator.naturalOrder()));

        for (String option : options) {
            Pools saved = pools.copy();
            int savedSteps = out.size();
            if (produce(option, count, pools, out, inProgress, depth, context)) {
                return true;
            }
            pools.restore(saved);
            truncate(out, savedSteps);
        }
        return false;
    }

    /**
     * How close an ingredient option is to hand: 0 carried, 1 in the warehouse,
     * 2 one recipe away from either, 3 needs a trip into the world.
     *
     * <p>Looking one level ahead is what stops a citizen carrying spruce from
     * walking off to find an oak tree because "any planks" listed oak first.</p>
     */
    private static int affinity(String option, Pools pools, Context context) {
        if (pools.carried.getOrDefault(option, 0) > 0) return 0;
        if (pools.stored.getOrDefault(option, 0) > 0) return 1;
        for (Production production : context.recipes.productionsOf(option)) {
            if (payableNow(production, pools)) return 2;
        }
        return 3;
    }

    /**
     * Candidate order: recipes payable from what is already to hand, then
     * crafting before smelting before mining, then the simplest recipe. Ties
     * break on the item id so a retry re-plans identically.
     */
    private static List<Production> rank(List<Production> candidates, Pools pools) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<Production> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator
                .comparingInt((Production p) -> payableNow(p, pools) ? 0 : 1)
                // Between recipes that both need work, the one made of common
                // stuff first. Sticks from planks and sticks from bamboo tie on
                // every other count, and the recipe book's order sent citizens
                // in an oak forest looking for bamboo.
                .thenComparingInt(p -> usesRareMaterial(p) ? 1 : 0)
                .thenComparingInt(p -> p.method.ordinal())
                .thenComparingInt(p -> p.inputs.size())
                .thenComparing(p -> p.sourceBlock == null ? "" : p.sourceBlock));
        return ranked;
    }

    /** Materials most of the world does not have within walking distance. */
    private static boolean isRare(String item) {
        return item.contains("bamboo") || item.contains("crimson") || item.contains("warped")
                || item.contains("blackstone") || item.contains("nether") || item.contains("prismarine");
    }

    /** True when some ingredient slot can only be filled with a rare material. */
    private static boolean usesRareMaterial(Production production) {
        for (Production.Need need : production.inputs) {
            if (!need.options.isEmpty() && need.options.stream().allMatch(CraftPlanner::isRare)) {
                return true;
            }
        }
        return false;
    }

    /** Every ingredient slot is covered by stock on hand, with no further production. */
    private static boolean payableNow(Production production, Pools pools) {
        // A furnace you cannot light is not a furnace you can use. Ignoring the
        // fuel here made the planner rate a coal-fired smelt as "payable" for a
        // colony holding only wood, so it went off to mine a coal seam instead
        // of burning the logs in its hands.
        if (production.fuel != null && !covered(production.fuel, pools)) {
            return false;
        }
        if (production.inputs.isEmpty()) {
            // Mining needs nothing in stock, but it does need a trip into the
            // world, so it is not "free" the way spending stock is.
            return production.method != Production.Method.MINE;
        }
        for (Production.Need need : production.inputs) {
            boolean covered = false;
            for (String option : need.options) {
                if (pools.available(option) >= need.count) {
                    covered = true;
                    break;
                }
            }
            if (!covered) return false;
        }
        return true;
    }

    /** True when one ingredient slot can be met from stock on hand. */
    private static boolean covered(Production.Need need, Pools pools) {
        for (String option : need.options) {
            if (pools.available(option) >= need.count) return true;
        }
        return false;
    }

    /**
     * Put the fetching first and collapse duplicates: resolving a tree naturally
     * asks for wood three separate times, and one trip beats three.
     *
     * <p>Withdrawals lead, then gathering, then everything that transforms them
     * — both fetching kinds depend on nothing, so they are free to move.</p>
     */
    private static List<CraftPlan.Step> merge(List<CraftPlan.Step> steps) {
        List<CraftPlan.Step> ordered = new ArrayList<>(steps.size());
        for (CraftPlan.Step step : steps) {
            if (step.method == Production.Method.WITHDRAW) ordered.add(step);
        }
        for (CraftPlan.Step step : steps) {
            if (step.method == Production.Method.MINE) ordered.add(step);
        }
        for (CraftPlan.Step step : steps) {
            if (step.method != Production.Method.WITHDRAW
                    && step.method != Production.Method.MINE) {
                ordered.add(step);
            }
        }

        List<CraftPlan.Step> merged = new ArrayList<>(ordered.size());
        for (CraftPlan.Step step : ordered) {
            boolean fetching = step.method == Production.Method.MINE
                    || step.method == Production.Method.WITHDRAW;
            int fold = -1;
            for (int i = merged.size() - 1; i >= 0; i--) {
                CraftPlan.Step earlier = merged.get(i);
                if (earlier.method == step.method && earlier.item.equals(step.item)
                        && java.util.Objects.equals(earlier.block, step.block)) {
                    fold = i;
                    break;
                }
                // Only fetching may fold across other steps; a craft in between
                // may well be what consumes this one's output.
                if (!fetching) break;
            }
            if (fold >= 0) {
                CraftPlan.Step earlier = merged.get(fold);
                merged.set(fold, new CraftPlan.Step(step.method, step.item,
                        earlier.count + step.count, step.block, step.needsWorkstation));
            } else {
                merged.add(step);
            }
        }
        return merged;
    }

    // ------------------------------------------------------------------ helpers

    /** Spend up to {@code want} of {@code item} from a pool; returns what was taken. */
    private static int take(Map<String, Integer> pool, String item, int want) {
        int held = pool.getOrDefault(item, 0);
        int taken = Math.min(held, want);
        if (taken > 0) pool.put(item, held - taken);
        return taken;
    }

    private static Map<String, Integer> copyPositive(Map<String, Integer> source) {
        Map<String, Integer> copy = new HashMap<>();
        if (source != null) {
            source.forEach((item, count) -> {
                if (item != null && count != null && count > 0) copy.put(item, count);
            });
        }
        return copy;
    }

    private static void truncate(List<CraftPlan.Step> steps, int size) {
        while (steps.size() > size) {
            steps.remove(steps.size() - 1);
        }
    }

    static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    /**
     * The two places a citizen can spend from. Kept apart because spending them
     * costs different things — reaching into your own bag is free, walking to
     * the warehouse is not — and because only one of them produces a step.
     */
    private static final class Pools {
        final Map<String, Integer> carried;
        final Map<String, Integer> stored;

        Pools(Map<String, Integer> carried, Map<String, Integer> stored) {
            this.carried = carried;
            this.stored = stored;
        }

        int available(String item) {
            return carried.getOrDefault(item, 0) + stored.getOrDefault(item, 0);
        }

        Pools copy() {
            return new Pools(new HashMap<>(carried), new HashMap<>(stored));
        }

        void restore(Pools snapshot) {
            carried.clear();
            carried.putAll(snapshot.carried);
            stored.clear();
            stored.putAll(snapshot.stored);
        }
    }

    private static final class Context {
        final RecipeSource recipes;
        final Options options;
        int expansions;

        Context(RecipeSource recipes, Options options) {
            this.recipes = recipes;
            this.options = options;
        }
    }
}
