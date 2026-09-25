package ai.minecivilization.colony;

import java.util.Locale;

/**
 * What a sign in the colony is telling you.
 *
 * <p>Signs are the colony's user interface. Everything the citizens know —
 * which district this is, where that road goes, which seam the mine is cut to,
 * that the cave ahead killed someone — exists as data the player cannot see.
 * Writing it on a post makes the settlement legible from inside the world, and
 * makes the knowledge durable: a sign survives a restart, a crash and a
 * mod update, and can be read back by the citizens who did not place it.</p>
 *
 * <p>The kind is stored alongside the text so a sign can be found again by
 * purpose ("where is the warehouse sign?") rather than by reading every post
 * in the town.</p>
 */
public enum SignKind {
    TOWN_HALL("TOWN HALL", true),
    DISTRICT("DISTRICT", true),
    RESIDENTIAL("RESIDENTIAL DISTRICT", true),
    FARM("FARM DISTRICT", true),
    MINE("MINE", true),
    WAREHOUSE("WAREHOUSE", true),
    WORKSHOP("WORKSHOP", true),
    PASTURE("PASTURE", true),
    FOREST("MANAGED FOREST", true),
    /** A road, signed at its ends with where it goes. */
    ROAD("ROAD", false),
    /** A building under construction, with its progress. */
    PROJECT("PROJECT", false),
    /** The mouth of a cave or a shaft — a way down, and a reason to be careful. */
    CAVE("CAVE ENTRANCE", false),
    /** Somewhere a citizen came to grief. */
    DANGER("DANGER", false),
    /** A notice board at the town centre: population, stores, projects. */
    NOTICE("NOTICE", false);

    private final String headline;
    /** True for signs that name a place, as opposed to describing a thing. */
    private final boolean placeName;

    SignKind(String headline, boolean placeName) {
        this.headline = headline;
        this.placeName = placeName;
    }

    /** The shouted first line, e.g. {@code TOWN HALL}. */
    public String headline() {
        return headline;
    }

    public boolean isPlaceName() {
        return placeName;
    }

    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** The sign kind a district of this zoning deserves. */
    public static SignKind forZone(ZoneType type) {
        if (type == null) return DISTRICT;
        return switch (type) {
            case CIVIC -> TOWN_HALL;
            case RESIDENTIAL -> RESIDENTIAL;
            case FARM -> FARM;
            case MINE -> MINE;
            case STORAGE -> WAREHOUSE;
            case INDUSTRIAL -> WORKSHOP;
            case PASTURE -> PASTURE;
            case FOREST -> FOREST;
        };
    }

    /**
     * Read a kind back off a sign's first line.
     *
     * <p>This is what lets citizens learn from signs they did not place — a
     * player who writes {@code DANGER} on a post has told the colony something,
     * and the colony should believe them.</p>
     */
    public static SignKind parse(String headline) {
        if (headline == null) return null;
        String text = headline.trim().toUpperCase(Locale.ROOT);
        if (text.isEmpty()) return null;
        for (SignKind kind : values()) {
            if (text.equals(kind.headline) || text.startsWith(kind.headline + " ")) return kind;
        }
        // "ROAD -> IRON MINE" and friends: the arrow is the giveaway.
        if (text.startsWith("ROAD")) return ROAD;
        if (text.contains("DANGER")) return DANGER;
        if (text.contains("CAVE")) return CAVE;
        return null;
    }
}
