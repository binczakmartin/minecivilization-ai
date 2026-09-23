package ai.minecivilization.farming;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CropsTest {
    @Test void plantingUsesTheActualProduceInsteadOfAlwaysWheat() {
        assertEquals("minecraft:carrot", Crops.seedFor("minecraft:carrots"));
        assertEquals("minecraft:potato", Crops.seedFor("minecraft:potatoes"));
        assertEquals("minecraft:sweet_berries", Crops.seedFor("minecraft:sweet_berry_bush"));
        assertNull(Crops.seedFor("minecraft:stone"));
    }
    @Test void blockIdsResolveToTheItemsTheyActuallyProduce() {
        assertEquals("minecraft:sweet_berries", Crops.produceFor("minecraft:sweet_berry_bush"));
        assertEquals("minecraft:pumpkin", Crops.produceFor("minecraft:pumpkin"));
        assertEquals("minecraft:carrots", Crops.produceFor("minecraft:carrots"));
        assertNull(Crops.produceFor("minecraft:stone"));
    }
    @Test void wildCropsDoNotRequireHoeOrFarmland() {
        assertFalse(Crops.needsFarmland("minecraft:sugar_cane"));
        assertFalse(Crops.needsFarmland("minecraft:sweet_berries"));
        assertFalse(Crops.needsFarmland("minecraft:nether_wart"));
        assertTrue(Crops.needsFarmland("minecraft:wheat_seeds"));
        assertTrue(Crops.needsFarmland("minecraft:carrot"));
    }
}
