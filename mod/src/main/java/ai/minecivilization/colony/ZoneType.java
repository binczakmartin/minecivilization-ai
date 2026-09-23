package ai.minecivilization.colony;

/**
 * What a piece of the colony's land is for.
 *
 * <p>Zoning is what turns a crowd of citizens into a settlement. Without it a
 * lumberjack walks in whatever direction it happens to face, and the "farm" is
 * wherever someone once planted wheat. With it, every trade has somewhere to
 * be, the colony has a shape, and a citizen far from home knows which way home
 * is.</p>
 *
 * <p>Each type declares how far from the town centre it belongs, which is the
 * whole of the city plan: quiet civic core, houses around it, workshops and
 * fields beyond, forestry and mining on the outskirts where the noise and the
 * holes do no harm.</p>
 */
public enum ZoneType {
    /** The town centre: plaza, monuments, meeting ground. Always plot (0,0). */
    CIVIC(0, "civic centre"),
    /** Warehouses. Close in, because everyone walks here all day. */
    STORAGE(1, "warehouse district"),
    /** Houses and beds. */
    RESIDENTIAL(1, "housing"),
    /** Crop fields. */
    FARM(2, "farmland"),
    /** Fenced pasture for livestock. */
    PASTURE(2, "pasture"),
    /** Workshops, furnaces, and eventually automated factories. */
    INDUSTRIAL(2, "industry"),
    /** Managed woodland: felled, then replanted with the species the colony knows. */
    FOREST(3, "managed forest"),
    /** Quarry and shafts — the only zone that owns the ground beneath it. */
    MINE(3, "mine");

    /** Closest ring of plots, in plots from the town centre, this may occupy. */
    public final int minRing;
    private final String label;

    ZoneType(int minRing, String label) {
        this.minRing = minRing;
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True for the one zone that extends downward instead of upward. */
    public boolean isUnderground() {
        return this == MINE;
    }

    /** Zones a citizen works in, as opposed to lives or stores things in. */
    public boolean isWorkplace() {
        return this == FARM || this == PASTURE || this == INDUSTRIAL
                || this == FOREST || this == MINE;
    }

    /**
     * Where a trade goes to work.
     *
     * <p>This is what turns a profession from a label into a place: a
     * lumberjack heads for the managed forest rather than the nearest tree it
     * happens to see, so the colony's woodland is worked and replanted instead
     * of the countryside being stripped outward in a ring.</p>
     *
     * @return the zone type, or null for trades that work wherever they are
     *         needed (builders follow their construction site)
     */
    public static ZoneType forProfession(String profession) {
        if (profession == null) return null;
        return switch (profession.toUpperCase(java.util.Locale.ROOT)) {
            case "LUMBERJACK", "FORESTER" -> FOREST;
            case "MINER" -> MINE;
            case "FARMER" -> FARM;
            case "SHEPHERD", "RANCHER", "BREEDER" -> PASTURE;
            case "CRAFTER", "SMITH", "ENGINEER" -> INDUSTRIAL;
            case "LOGISTICS", "TRADER" -> STORAGE;
            default -> null;
        };
    }
}
