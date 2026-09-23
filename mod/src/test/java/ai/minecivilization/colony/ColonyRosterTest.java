package ai.minecivilization.colony;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * When a citizen should change trade.
 *
 * <p>The failure this guards against: a colony loses its only farmer, the spawn
 * rotation never revisits the gap, and the settlement quietly stops growing
 * food forever.</p>
 */
class ColonyRosterTest {

    private static Map<String, Integer> census(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>(ColonyRoster.emptyCensus());
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    @Test
    void aBalancedColonyChangesNobody() {
        Map<String, Integer> balanced = census(
                "FARMER", 2, "LUMBERJACK", 2, "MINER", 2,
                "BUILDER", 2, "CRAFTER", 2, "SHEPHERD", 2);

        assertNull(ColonyRoster.rebalance(balanced, 12),
                "changing trade throws away tools and skill; it needs a reason");
    }

    @Test
    void aMissingTradeIsFilledFromTheMostCrowdedOne() {
        Map<String, Integer> lopsided = census(
                "FARMER", 0, "LUMBERJACK", 6, "MINER", 2,
                "BUILDER", 2, "CRAFTER", 1, "SHEPHERD", 1);

        ColonyRoster.Reassignment change = ColonyRoster.rebalance(lopsided, 12);

        assertNotNull(change, "a colony with no farmer stops growing food forever");
        assertEquals("FARMER", change.to());
        assertEquals("LUMBERJACK", change.from());
    }

    @Test
    void theMostImportantGapIsFilledFirst() {
        Map<String, Integer> noFarmerNoMiner = census(
                "FARMER", 0, "LUMBERJACK", 6, "MINER", 0,
                "BUILDER", 2, "CRAFTER", 2, "SHEPHERD", 2);

        assertEquals("FARMER", ColonyRoster.rebalance(noFarmerNoMiner, 12).to(),
                "food before ore");
    }

    @Test
    void nobodyIsMovedOutOfATradeThatIsNotCrowded() {
        // Everyone is thinly spread: moving one person just creates a new gap.
        Map<String, Integer> thin = census(
                "FARMER", 0, "LUMBERJACK", 2, "MINER", 2,
                "BUILDER", 2, "CRAFTER", 2, "SHEPHERD", 2);

        assertNull(ColonyRoster.rebalance(thin, 10),
                "robbing Peter to pay Paul is not a rebalance");
    }

    @Test
    void aSmallCampIsLeftAlone() {
        // Four citizens covering four trades are already doing the right thing.
        Map<String, Integer> camp = census("FARMER", 0, "LUMBERJACK", 2, "MINER", 1, "BUILDER", 1);

        assertNull(ColonyRoster.rebalance(camp, 4));
    }

    @Test
    void aCitizenIsNeverReassignedToTheTradeItAlreadyHas() {
        Map<String, Integer> census = census(
                "FARMER", 0, "LUMBERJACK", 8, "MINER", 1,
                "BUILDER", 1, "CRAFTER", 1, "SHEPHERD", 1);

        ColonyRoster.Reassignment change = ColonyRoster.rebalance(census, 12);
        assertNotNull(change);
        assertEquals(false, change.from().equals(change.to()));
    }

    @Test
    void rebalancingConvergesRatherThanOscillating() {
        // Apply the roster's own advice repeatedly: it must settle, not flip
        // one citizen back and forth forever.
        Map<String, Integer> census = census(
                "FARMER", 0, "LUMBERJACK", 7, "MINER", 2,
                "BUILDER", 1, "CRAFTER", 1, "SHEPHERD", 1);
        int population = 12;

        for (int step = 0; step < 20; step++) {
            ColonyRoster.Reassignment change = ColonyRoster.rebalance(census, population);
            if (change == null) return;   // settled
            census.merge(change.from(), -1, Integer::sum);
            census.merge(change.to(), 1, Integer::sum);
        }
        throw new AssertionError("the roster never settled: " + census);
    }

    @Test
    void nullsAndEmptyColoniesAreHandled() {
        assertNull(ColonyRoster.rebalance(null, 10));
        assertNull(ColonyRoster.rebalance(Map.of(), 0));
    }
}
