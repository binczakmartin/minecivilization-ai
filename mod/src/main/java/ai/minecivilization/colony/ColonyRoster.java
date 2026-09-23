package ai.minecivilization.colony;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * Who does what, and when someone should change trade.
 *
 * <p>Trades are handed out in spawn order, which is fine until someone dies.
 * A colony that loses its only farmer has no way back: the rotation never
 * revisits the gap, so it simply stops growing food and nobody notices. The
 * same happens in reverse when a wave of births leaves six lumberjacks and no
 * builder.</p>
 *
 * <p>Rebalancing is deliberately conservative. Changing trade throws away the
 * tools a citizen was carrying for its old job and the skill it had built up,
 * so it only happens when a trade is genuinely <em>absent</em> and another is
 * genuinely <em>crowded</em> — never to smooth out a difference of one.</p>
 *
 * <p>Pure: a census in, a reassignment out.</p>
 */
public final class ColonyRoster {

    /** Trades a working colony needs somebody in, in order of how badly. */
    public static final List<String> ESSENTIAL = List.of(
            "FARMER", "LUMBERJACK", "MINER", "BUILDER", "CRAFTER", "SHEPHERD");

    /** A trade must have this many more than the gap's share before it gives one up. */
    static final int CROWDING_MARGIN = 2;

    /** One citizen changing from one trade to another. */
    public record Reassignment(String from, String to) {
    }

    private ColonyRoster() {
    }

    /**
     * The one trade change the colony most needs, or {@code null} when the
     * roster is balanced enough to leave alone.
     *
     * @param census how many citizens hold each trade
     */
    @Nullable
    public static Reassignment rebalance(Map<String, Integer> census, int population) {
        if (census == null || population < ESSENTIAL.size()) {
            // Too small to specialise: a camp of four covering four trades is
            // doing the right thing already.
            return null;
        }

        String gap = firstMissing(census);
        if (gap == null) return null;

        String crowded = mostCrowded(census, population);
        if (crowded == null || crowded.equals(gap)) return null;
        return new Reassignment(crowded, gap);
    }

    /** The most important trade nobody currently holds. */
    @Nullable
    static String firstMissing(Map<String, Integer> census) {
        for (String trade : ESSENTIAL) {
            if (census.getOrDefault(trade, 0) <= 0) return trade;
        }
        return null;
    }

    /**
     * The trade with the most people to spare: the largest, and only if it is
     * clearly larger than an even share.
     */
    @Nullable
    static String mostCrowded(Map<String, Integer> census, int population) {
        int fairShare = Math.max(1, population / ESSENTIAL.size());
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : census.entrySet()) {
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (count < fairShare + CROWDING_MARGIN) continue;
            if (count > bestCount) {
                bestCount = count;
                best = entry.getKey();
            }
        }
        return best;
    }

    /** An empty census for every essential trade, for callers that tally up. */
    public static Map<String, Integer> emptyCensus() {
        Map<String, Integer> census = new LinkedHashMap<>();
        for (String trade : ESSENTIAL) census.put(trade, 0);
        return census;
    }
}
