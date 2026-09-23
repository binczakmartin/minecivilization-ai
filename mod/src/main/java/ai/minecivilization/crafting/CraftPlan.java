package ai.minecivilization.crafting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ordered, executable answer to "how do I end up holding this?".
 *
 * <p>Every step is something a citizen already knows how to do — gather N of a
 * block, smelt N of an item, craft N of an item — and the order is such that
 * each step's inputs exist by the time it runs. Producing an iron pickaxe from
 * an empty inventory comes out as: gather wood, craft planks, craft sticks,
 * gather iron ore, gather coal, smelt ingots, craft the pickaxe.</p>
 */
public final class CraftPlan {

    public static final class Step {
        public final Production.Method method;
        public final String item;
        /** How many of {@link #item} this step is expected to yield. */
        public final int count;
        /** MINE only: the block to break. */
        public final String block;
        public final boolean needsWorkstation;

        public Step(Production.Method method, String item, int count,
                    String block, boolean needsWorkstation) {
            this.method = method;
            this.item = item;
            this.count = count;
            this.block = block;
            this.needsWorkstation = needsWorkstation;
        }

        @Override
        public String toString() {
            return method + " " + count + "x" + item
                    + (block == null ? "" : " (" + block + ")");
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Step other)) return false;
            return method == other.method && count == other.count
                    && item.equals(other.item)
                    && java.util.Objects.equals(block, other.block);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(method, item, count, block);
        }
    }

    private final List<Step> steps;
    private final String target;
    private final int targetCount;

    public CraftPlan(String target, int targetCount, List<Step> steps) {
        this.target = target;
        this.targetCount = targetCount;
        this.steps = List.copyOf(steps);
    }

    public List<Step> steps() {
        return steps;
    }

    public String target() {
        return target;
    }

    public int targetCount() {
        return targetCount;
    }

    /** Nothing to do — the citizen already holds what was asked for. */
    public boolean isSatisfied() {
        return steps.isEmpty();
    }

    public int size() {
        return steps.size();
    }

    /** Raw materials the citizen has to fetch from the world, item to total count. */
    public Map<String, Integer> rawMaterials() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Step step : steps) {
            if (step.method == Production.Method.MINE) {
                out.merge(step.item, step.count, Integer::sum);
            }
        }
        return out;
    }

    /** Items the citizen will fetch from settlement storage, item to total count. */
    public Map<String, Integer> withdrawals() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Step step : steps) {
            if (step.method == Production.Method.WITHDRAW) {
                out.merge(step.item, step.count, Integer::sum);
            }
        }
        return out;
    }

    /** True when the plan never leaves the inventory — pure crafting, no fetching. */
    public boolean usesOnlyCarriedMaterials() {
        return rawMaterials().isEmpty() && withdrawals().isEmpty();
    }

    /** True when nothing has to be dug out of the world. */
    public boolean needsNoGathering() {
        return rawMaterials().isEmpty();
    }

    @Override
    public String toString() {
        return targetCount + "x" + target + " via " + steps;
    }
}
