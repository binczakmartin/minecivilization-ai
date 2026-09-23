package ai.minecivilization.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a citizen keeps for itself when it empties its bag into the warehouse. */
class DeliveryPolicyTest {

    @Test
    void foodIsNeverFullyHandedOver() {
        // A citizen that deposits its last loaf walks away hungry and comes
        // straight back for it.
        assertEquals(DeliveryPolicy.FOOD_RESERVE, DeliveryPolicy.keepBack("minecraft:bread"));
        assertEquals(0, DeliveryPolicy.depositable("minecraft:bread", 3),
                "below the reserve, nothing is handed over");
        assertEquals(6, DeliveryPolicy.depositable("minecraft:bread", 10));
    }

    @Test
    void bridgingBlocksAreKeptForTheJourneyBack() {
        assertEquals(DeliveryPolicy.SCAFFOLD_RESERVE, DeliveryPolicy.keepBack("minecraft:dirt"));
        assertEquals(DeliveryPolicy.SCAFFOLD_RESERVE,
                DeliveryPolicy.keepBack("minecraft:cobblestone"));
        assertEquals(56, DeliveryPolicy.depositable("minecraft:cobblestone", 64));
    }

    @Test
    void torchesAreKeptUntilTheFieldHasBeenLit() {
        assertEquals(0, DeliveryPolicy.depositable("minecraft:torch", 16));
        assertEquals(48, DeliveryPolicy.depositable("minecraft:torch", 64));
    }

    @Test
    void farmingSuppliesSurviveDelivery() {
        assertEquals(0, DeliveryPolicy.depositable("minecraft:water_bucket", 1));
        assertEquals(0, DeliveryPolicy.depositable("minecraft:bucket", 1));
        assertEquals(0, DeliveryPolicy.depositable("minecraft:wheat_seeds", 4));
        assertEquals(12, DeliveryPolicy.depositable("minecraft:sugar_cane", 16));
    }

    @Test
    void everythingElseBelongsToTheSettlement() {
        assertEquals(0, DeliveryPolicy.keepBack("minecraft:iron_ingot"));
        assertEquals(0, DeliveryPolicy.keepBack("minecraft:redstone"));
        assertEquals(64, DeliveryPolicy.depositable("minecraft:iron_ingot", 64));
    }

    @Test
    void theReserveNeverProducesNegativeOrNonsenseAmounts() {
        assertEquals(0, DeliveryPolicy.depositable("minecraft:bread", 0));
        assertEquals(0, DeliveryPolicy.depositable("minecraft:dirt", -5));
        assertEquals(0, DeliveryPolicy.keepBack(null));
        assertEquals(0, DeliveryPolicy.keepBack(""));
    }

    @Test
    void aReserveIsSmallEnoughToLeaveTheColonyTheBulk() {
        // The point is a personal buffer, not a private hoard.
        int carried = 64;
        for (String item : new String[]{"minecraft:bread", "minecraft:dirt"}) {
            assertTrue(DeliveryPolicy.depositable(item, carried) > carried / 2,
                    item + ": most of a full stack must still reach the warehouse");
        }
    }
}
