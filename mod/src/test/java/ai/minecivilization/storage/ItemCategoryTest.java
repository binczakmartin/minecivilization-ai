package ai.minecivilization.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The sorting policy of the warehouse — pure string work, no registry. */
class ItemCategoryTest {

    @Test
    void theOreFamilyIsFiledTogether() {
        // The smelter wants ore, raw drop and ingot within one chest's reach.
        assertEquals(ItemCategory.ORE, ItemCategory.of("minecraft:iron_ore"));
        assertEquals(ItemCategory.ORE, ItemCategory.of("minecraft:deepslate_iron_ore"));
        assertEquals(ItemCategory.ORE, ItemCategory.of("minecraft:raw_iron"));
        assertEquals(ItemCategory.ORE, ItemCategory.of("minecraft:iron_ingot"));
        assertEquals(ItemCategory.ORE, ItemCategory.of("minecraft:iron_nugget"));
        assertEquals(ItemCategory.ORE, ItemCategory.of("minecraft:coal"));
        assertEquals(ItemCategory.ORE, ItemCategory.of("minecraft:diamond"));
    }

    @Test
    void toolsWinOverTheMaterialTheyAreMadeOf() {
        // The trap: an iron pickaxe is not iron stock, and a golden axe is not food.
        assertEquals(ItemCategory.TOOLS, ItemCategory.of("minecraft:iron_pickaxe"));
        assertEquals(ItemCategory.TOOLS, ItemCategory.of("minecraft:golden_axe"));
        assertEquals(ItemCategory.TOOLS, ItemCategory.of("minecraft:diamond_chestplate"));
        assertEquals(ItemCategory.TOOLS, ItemCategory.of("minecraft:shears"));
        assertEquals(ItemCategory.TOOLS, ItemCategory.of("minecraft:water_bucket"));
    }

    @Test
    void foodIsNotConfusedWithItsGoldenNamesakes() {
        assertEquals(ItemCategory.FOOD, ItemCategory.of("minecraft:golden_apple"));
        assertEquals(ItemCategory.FOOD, ItemCategory.of("minecraft:golden_carrot"));
        assertEquals(ItemCategory.FOOD, ItemCategory.of("minecraft:bread"));
        assertEquals(ItemCategory.FOOD, ItemCategory.of("minecraft:cooked_beef"));
    }

    @Test
    void woodCoversTheWholeCarpentryLine() {
        assertEquals(ItemCategory.WOOD, ItemCategory.of("minecraft:oak_log"));
        assertEquals(ItemCategory.WOOD, ItemCategory.of("minecraft:stripped_spruce_log"));
        assertEquals(ItemCategory.WOOD, ItemCategory.of("minecraft:birch_planks"));
        assertEquals(ItemCategory.WOOD, ItemCategory.of("minecraft:stick"));
        assertEquals(ItemCategory.WOOD, ItemCategory.of("minecraft:crafting_table"));
        assertEquals(ItemCategory.WOOD, ItemCategory.of("minecraft:chest"));
    }

    @Test
    void scaffoldingAndMasonryAreSeparateShelves() {
        assertEquals(ItemCategory.EARTH, ItemCategory.of("minecraft:dirt"));
        assertEquals(ItemCategory.EARTH, ItemCategory.of("minecraft:gravel"));
        assertEquals(ItemCategory.EARTH, ItemCategory.of("minecraft:sand"));
        assertEquals(ItemCategory.STONE, ItemCategory.of("minecraft:cobblestone"));
        assertEquals(ItemCategory.STONE, ItemCategory.of("minecraft:stone_bricks"));
        assertEquals(ItemCategory.STONE, ItemCategory.of("minecraft:furnace"));
    }

    @Test
    void redstonePartsGoToTheMachineShop() {
        assertEquals(ItemCategory.REDSTONE, ItemCategory.of("minecraft:redstone"));
        assertEquals(ItemCategory.REDSTONE, ItemCategory.of("minecraft:hopper"));
        assertEquals(ItemCategory.REDSTONE, ItemCategory.of("minecraft:comparator"));
        assertEquals(ItemCategory.REDSTONE, ItemCategory.of("minecraft:sticky_piston"));
        // the ore block is still stock, not a component
        assertEquals(ItemCategory.ORE, ItemCategory.of("minecraft:redstone_ore"));
    }

    @Test
    void farmingCoversSeedsSaplingsAndLivestockProduce() {
        assertEquals(ItemCategory.FARM, ItemCategory.of("minecraft:wheat_seeds"));
        assertEquals(ItemCategory.FARM, ItemCategory.of("minecraft:oak_sapling"));
        assertEquals(ItemCategory.FARM, ItemCategory.of("minecraft:wheat"));
        assertEquals(ItemCategory.FARM, ItemCategory.of("minecraft:leather"));
        assertEquals(ItemCategory.FARM, ItemCategory.of("minecraft:egg"));
    }

    @Test
    void unknownAndMalformedIdsFallBackToGeneralStorage() {
        assertEquals(ItemCategory.MISC, ItemCategory.of("minecraft:nether_star"));
        assertEquals(ItemCategory.MISC, ItemCategory.of("somemod:strange_widget"));
        assertEquals(ItemCategory.MISC, ItemCategory.of(""));
        assertEquals(ItemCategory.MISC, ItemCategory.of(null));
        assertEquals(ItemCategory.MISC, ItemCategory.of("minecraft:"));
    }

    @Test
    void classificationIsTotalAndStable() {
        String[] sample = {
            "minecraft:oak_log", "minecraft:iron_ingot", "minecraft:bread",
            "minecraft:redstone", "minecraft:dirt", "minecraft:diamond_sword",
            "minecraft:wheat_seeds", "minecraft:cobblestone", "minecraft:nether_star"};
        for (String id : sample) {
            ItemCategory first = ItemCategory.of(id);
            assertNotNull(first, id + " must classify");
            assertEquals(first, ItemCategory.of(id), "classification must be stable for " + id);
            assertNotNull(first.label());
        }
    }
}
