package ai.minecivilization.colony;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Decides what land the colony needs next.
 *
 * <p>A settlement does not plan its whole town on day one — it allots the next
 * piece of ground when the need appears. Four citizens need a second field
 * before they need a factory; nobody needs a pasture until there are enough
 * hands to tend it. This is that judgement, expressed as a target count per
 * zone type against the population, plus the order in which shortfalls get
 * settled.</p>
 *
 * <p>Pure — population and a census of what exists in, one zone type out — so
 * the growth curve of a town is unit tested rather than watched.</p>
 */
public final class ZonePlanner {

    /**
     * Which shortfall is settled first. Survival before comfort before
     * industry: the colony feeds and shelters itself before it builds workshops.
     */
    private static final List<ZoneType> PRIORITY = List.of(
            ZoneType.CIVIC,
            ZoneType.STORAGE,
            ZoneType.FOREST,
            ZoneType.FARM,
            ZoneType.RESIDENTIAL,
            ZoneType.MINE,
            ZoneType.PASTURE,
            ZoneType.INDUSTRIAL);

    private ZonePlanner() {
    }

    /**
     * How many zones of a type a colony of this size wants.
     *
     * <p>Returning 0 means "not yet": a two-person camp has no business
     * fencing a pasture it cannot staff.</p>
     */
    public static int desired(ZoneType type, int population) {
        if (population <= 0) return 0;
        return switch (type) {
            case CIVIC -> 1;
            case STORAGE -> 1 + population / 6;
            case RESIDENTIAL -> Math.max(1, (population + 1) / 2);
            case FOREST -> Math.max(1, population / 4);
            case FARM -> population >= 2 ? Math.max(1, population / 4) : 0;
            case MINE -> population >= 3 ? 1 + population / 10 : 0;
            case PASTURE -> population >= 4 ? 1 + population / 10 : 0;
            case INDUSTRIAL -> population >= 6 ? 1 + population / 12 : 0;
        };
    }

    /**
     * The one zone type the colony most needs and does not have enough of, or
     * {@code null} when the town is complete for its size.
     *
     * @param existing how many of each type already exist
     */
    @Nullable
    public static ZoneType nextNeeded(int population, Map<ZoneType, Integer> existing) {
        for (ZoneType type : PRIORITY) {
            int have = existing == null ? 0 : existing.getOrDefault(type, 0);
            if (have < desired(type, population)) return type;
        }
        return null;
    }

    /** The full plan for a colony of this size, for inspection and tests. */
    public static Map<ZoneType, Integer> plan(int population) {
        Map<ZoneType, Integer> out = new EnumMap<>(ZoneType.class);
        for (ZoneType type : ZoneType.values()) {
            int n = desired(type, population);
            if (n > 0) out.put(type, n);
        }
        return out;
    }

    // ------------------------------------------------------------------ world

    /**
     * Allot at most one new zone. Called periodically, so the town grows a plot
     * at a time rather than appearing all at once — and so a single call can
     * never spend an unbounded amount of a tick.
     *
     * @return the zone that was created, or null when nothing was needed
     */
    @Nullable
    public static Zone ensure(ServerLevel level, int population) {
        if (level == null || population <= 0) return null;
        ZoneManager manager = ZoneManager.get(level);

        Map<ZoneType, Integer> census = new EnumMap<>(ZoneType.class);
        for (ZoneType type : ZoneType.values()) {
            census.put(type, manager.count(type));
        }

        ZoneType needed = nextNeeded(population, census);
        if (needed == null) return null;
        return manager.create(level, needed);
    }
}
