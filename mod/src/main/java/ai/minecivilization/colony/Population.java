package ai.minecivilization.colony;

/**
 * When the colony can afford another mouth.
 *
 * <p>Growth is the clearest signal that a settlement is working, so it is
 * deliberately not free. A birth costs real food out of the warehouse and
 * needs a bed standing empty for the newcomer — which means the colony has to
 * have solved farming <em>and</em> housing, and housing means wool, which means
 * livestock. A colony that grows has therefore genuinely built something.</p>
 *
 * <p>The food bar rises with the population: feeding twenty takes more slack
 * than feeding four, and a colony that grows until it starves is not a colony
 * that is doing well.</p>
 *
 * <p>Pure arithmetic, so the whole demography is unit tested.</p>
 */
public final class Population {

    /** Hard ceiling — a village, not a server-melting crowd. */
    public static final int MAX_POPULATION = 24;
    /** Two citizens are needed to raise a third. */
    public static final int MIN_PARENTS = 2;
    /** Ticks between births: 20 minutes of game time, a Minecraft day. */
    public static final int BIRTH_COOLDOWN_TICKS = 24_000;
    /** Food eaten by a birth, out of the settlement's stores. */
    public static final int FOOD_COST = 16;

    private Population() {
    }

    /**
     * Food the warehouse must hold before the colony will grow again.
     *
     * <p>Base surplus plus a share per existing citizen: a settlement only
     * takes on another mouth once it is comfortably ahead, not merely fed.</p>
     */
    public static int foodNeededFor(int population) {
        if (population <= 0) return 0;
        return 64 + 24 * population;
    }

    /** Beds the colony must own before the colony will grow again. */
    public static int bedsNeededFor(int population) {
        // One each, plus one standing empty for the newcomer.
        return Math.max(0, population) + 1;
    }

    /**
     * Whether a birth may happen right now.
     *
     * @param ticksSinceLastBirth {@link Long#MAX_VALUE} when none has happened yet
     */
    public static boolean canReproduce(int population, int foodReserve, int beds,
                                       long ticksSinceLastBirth) {
        return blocker(population, foodReserve, beds, ticksSinceLastBirth) == null;
    }

    /**
     * Why the colony is not growing, in words fit for {@code /mciv colony}, or
     * {@code null} when it is ready. Diagnosing "why is nobody being born?" is
     * otherwise guesswork.
     */
    public static String blocker(int population, int foodReserve, int beds,
                                 long ticksSinceLastBirth) {
        if (population < MIN_PARENTS) {
            return "needs at least " + MIN_PARENTS + " citizens";
        }
        if (population >= MAX_POPULATION) {
            return "at the population limit of " + MAX_POPULATION;
        }
        int bedsNeeded = bedsNeededFor(population);
        if (beds < bedsNeeded) {
            return "needs " + bedsNeeded + " beds, has " + beds;
        }
        int foodNeeded = foodNeededFor(population);
        if (foodReserve < foodNeeded) {
            return "needs " + foodNeeded + " food in store, has " + foodReserve;
        }
        if (ticksSinceLastBirth < BIRTH_COOLDOWN_TICKS) {
            long left = (BIRTH_COOLDOWN_TICKS - ticksSinceLastBirth) / 20;
            return "too soon after the last birth (" + left + "s)";
        }
        return null;
    }
}
