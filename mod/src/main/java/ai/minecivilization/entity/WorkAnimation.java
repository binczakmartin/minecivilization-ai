package ai.minecivilization.entity;

/**
 * Semantic work gestures shared by the server and the client renderer.
 *
 * <p>This is deliberately separate from {@code EntityPose}: a crouching pose
 * changes collision dimensions, while these values only describe what the
 * citizen is doing with its arms.</p>
 */
public enum WorkAnimation {
    NONE(0),
    MINE(6),
    CHOP(7),
    PLACE(7),
    BUILD(7),
    CRAFT(6),
    SMELT(8),
    HARVEST(6),
    TILL(7),
    PLANT(6),
    FORAGE(6),
    REACH(6),
    EAT(8),
    COMBAT(8);

    private final int intervalTicks;

    WorkAnimation(int intervalTicks) {
        this.intervalTicks = intervalTicks;
    }

    public int intervalTicks() {
        return this.intervalTicks;
    }

    public static WorkAnimation byOrdinal(int ordinal) {
        WorkAnimation[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
    }
}
