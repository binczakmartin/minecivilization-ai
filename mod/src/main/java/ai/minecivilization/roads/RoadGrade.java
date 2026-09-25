package ai.minecivilization.roads;

/**
 * How built-up a route is.
 *
 * <p>A colony's roads are not designed, they are worn in. The first citizen to
 * walk from the town centre to a coal seam leaves nothing behind; the fiftieth
 * is walking a lit, paved, signposted road, because each of the intervening
 * forty-nine improved it slightly while passing. That is the whole idea, and
 * this is the ladder it climbs.</p>
 *
 * <p>Each grade is a promise about what the route physically has, and a
 * citizen upgrading a road does exactly the work the next grade requires — no
 * more, so a single trip is never a construction project.</p>
 */
public enum RoadGrade {
    /** Footprints. Remembered, but nothing has been built. */
    TRACK("track", 0),
    /** Obstacles removed and holes filled: walkable without climbing. */
    CLEARED("cleared path", 8),
    /** A surface laid down, so it reads as a road and does not erode into mud. */
    PAVED("paved road", 24),
    /** Torches along it, because an unlit road at night is where citizens die. */
    LIT("lit road", 48),
    /** Signed at both ends, so it is part of the colony's map. */
    SIGNPOSTED("signposted road", 80);

    private final String label;
    /** Trips along the route before it deserves this grade. */
    public final int usesRequired;

    RoadGrade(String label, int usesRequired) {
        this.label = label;
        this.usesRequired = usesRequired;
    }

    public String label() {
        return label;
    }

    /** The grade above this one, or null when the road is finished. */
    public RoadGrade above() {
        RoadGrade[] all = values();
        return ordinal() + 1 < all.length ? all[ordinal() + 1] : null;
    }

    /**
     * The highest grade this much traffic has earned.
     *
     * <p>Deliberately monotone in use count: roads do not get downgraded
     * because a week went quietly by, they just stop being improved.</p>
     */
    public static RoadGrade earnedBy(int uses) {
        RoadGrade best = TRACK;
        for (RoadGrade grade : values()) {
            if (uses >= grade.usesRequired) best = grade;
        }
        return best;
    }
}
