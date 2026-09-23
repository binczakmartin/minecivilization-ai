package ai.minecivilization.crafting;

import java.util.List;

/**
 * One way to obtain an item: craft it, smelt it, or take it out of the world.
 *
 * <p>An item usually has several. Iron ingots are smelted from raw iron, but
 * also crafted from an iron block or from nuggets; cobblestone only ever comes
 * from mining stone. {@link CraftPlanner} weighs the alternatives against what
 * the citizen is actually carrying, which is why a production is data rather
 * than a hard-coded branch.</p>
 */
public final class Production {

    public enum Method {
        /** A crafting-grid recipe. */
        CRAFT,
        /** A furnace recipe — consumes fuel as well as input. */
        SMELT,
        /** Break a block in the world and pick up what drops. */
        MINE,
        /**
         * Take it out of a settlement container. Never returned by a
         * {@link RecipeSource} — the planner emits this itself when the colony
         * already owns what a recipe asks for.
         */
        WITHDRAW
    }

    /**
     * One ingredient slot: any {@code count} of the listed items will do.
     *
     * <p>Vanilla ingredients are sets, not single items — "any log" makes
     * planks, "any plank" makes sticks. Collapsing that to one id early is what
     * makes a planner brittle, so the choice is deferred to planning time, when
     * the citizen's inventory is known.</p>
     */
    public static final class Need {
        public final List<String> options;
        public final int count;

        public Need(List<String> options, int count) {
            if (options == null || options.isEmpty()) {
                throw new IllegalArgumentException("an ingredient needs at least one option");
            }
            if (count < 1) {
                throw new IllegalArgumentException("ingredient count must be positive");
            }
            this.options = List.copyOf(options);
            this.count = count;
        }

        public static Need of(String item, int count) {
            return new Need(List.of(item), count);
        }

        @Override
        public String toString() {
            return count + "x" + (options.size() == 1 ? options.get(0) : options);
        }
    }

    public final Method method;
    public final String output;
    /** How many are produced per application (4 planks per log, 4 sticks per craft). */
    public final int outputCount;
    public final List<Need> inputs;
    /** MINE only: the block to break, which often differs from the item it drops. */
    public final String sourceBlock;
    /** CRAFT needing a 3x3 grid, or SMELT — both require walking to a station. */
    public final boolean needsWorkstation;
    /**
     * SMELT only: what the furnace burns, counted per eight items smelted —
     * one lump of coal is worth eight operations. Null for every other method.
     * Without this a citizen plans a smelt it has no way to light.
     */
    public final Need fuel;
    /**
     * How many items one unit of this production's fuel carries through the
     * furnace. Coal is worth eight; a plank is worth one and a half, counted
     * as one so a citizen never runs the fire out halfway through a batch.
     */
    public final int fuelSmeltsPerUnit;

    public Production(Method method, String output, int outputCount,
                      List<Need> inputs, String sourceBlock, boolean needsWorkstation) {
        this(method, output, outputCount, inputs, sourceBlock, needsWorkstation, null);
    }

    public Production(Method method, String output, int outputCount, List<Need> inputs,
                      String sourceBlock, boolean needsWorkstation, Need fuel) {
        this(method, output, outputCount, inputs, sourceBlock, needsWorkstation,
                fuel, SMELTS_PER_FUEL);
    }

    public Production(Method method, String output, int outputCount, List<Need> inputs,
                      String sourceBlock, boolean needsWorkstation, Need fuel,
                      int fuelSmeltsPerUnit) {
        this.fuelSmeltsPerUnit = Math.max(1, fuelSmeltsPerUnit);
        this.method = method;
        this.output = output;
        this.outputCount = Math.max(1, outputCount);
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
        this.sourceBlock = sourceBlock;
        this.needsWorkstation = needsWorkstation;
        this.fuel = fuel;
    }

    /** How many items one unit of fuel carries through the furnace. */
    public static final int SMELTS_PER_FUEL = 8;

    public static Production craft(String output, int outputCount, List<Need> inputs,
                                   boolean needsTable) {
        return new Production(Method.CRAFT, output, outputCount, inputs, null, needsTable);
    }

    public static Production smelt(String output, Need input, Need fuel) {
        return new Production(Method.SMELT, output, 1, List.of(input), null, true, fuel);
    }

    /** A smelt burning something other than coal, with its own fuel value. */
    public static Production smelt(String output, Need input, Need fuel, int smeltsPerFuel) {
        return new Production(Method.SMELT, output, 1, List.of(input), null, true,
                fuel, smeltsPerFuel);
    }

    /** Mined straight out of the world — no inputs, one drop per block broken. */
    public static Production mine(String output, String sourceBlock) {
        return new Production(Method.MINE, output, 1, List.of(), sourceBlock, false);
    }

    /** True when nothing has to be produced first — the leaves of the craft tree. */
    public boolean isRaw() {
        return inputs.isEmpty();
    }

    @Override
    public String toString() {
        return method + " " + outputCount + "x" + output
                + (inputs.isEmpty() ? (sourceBlock == null ? "" : " from " + sourceBlock)
                                    : " <- " + inputs);
    }
}
