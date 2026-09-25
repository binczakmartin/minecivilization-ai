package ai.minecivilization.work;

import java.util.Locale;

/**
 * What a colony does first when it cannot do everything.
 *
 * <p>The order is not a preference, it is a survival curve. A citizen that
 * paves a road while starving, or plants flowers while a creeper closes in, is
 * not showing initiative — it is showing that nobody ranked the work. Every
 * source of jobs declares its rank here, and the dispatcher never looks at a
 * lower rank while a higher one has something to offer.</p>
 *
 * <p>Declared lowest-number-first so {@link #ordinal()} is the rank, which
 * keeps the comparison in one obvious place.</p>
 */
public enum WorkPriority {
    /** Eat, flee, heal. Nothing outranks staying alive. */
    SURVIVAL("survival"),
    /** Something is actively hostile, here, now. */
    DANGER("danger"),
    /** Lost, stranded or buried — get the citizen back to the colony. */
    RETURN_HOME("return home"),
    /** The colony's larder, which is what population growth actually costs. */
    FOOD("food"),
    /** Tools and equipment: everything else is slower without them. */
    TOOLS("tools"),
    /** Putting up the buildings that make the place a settlement. */
    CONSTRUCTION("construction"),
    /** Wood, stone, ore — feeding construction and crafting. */
    RESOURCES("resources"),
    /** Hauling, stocking chests, lighting, repairs, husbandry. */
    MAINTENANCE("maintenance"),
    /** Finding what the colony does not yet have. */
    EXPLORATION("exploration"),
    /** Roads, signs, decoration — the part that makes it look like a town. */
    IMPROVEMENT("improvement");

    private final String label;

    WorkPriority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Lower is more urgent. */
    public int rank() {
        return ordinal();
    }

    public boolean outranks(WorkPriority other) {
        return other == null || rank() < other.rank();
    }

    /** True for work that justifies interrupting a citizen mid-task. */
    public boolean isInterrupting() {
        return this == SURVIVAL || this == DANGER || this == RETURN_HOME;
    }

    @Override
    public String toString() {
        return label.toUpperCase(Locale.ROOT);
    }
}
