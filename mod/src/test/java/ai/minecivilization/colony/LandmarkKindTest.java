package ai.minecivilization.colony;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the colony writes down, and what it walks past. */
class LandmarkKindTest {

    @Test
    void everyWorkstationIsRecognised() {
        assertEquals(LandmarkKind.CRAFTING_TABLE, LandmarkKind.of("minecraft:crafting_table"));
        assertEquals(LandmarkKind.FURNACE, LandmarkKind.of("minecraft:furnace"));
        assertEquals(LandmarkKind.BLAST_FURNACE, LandmarkKind.of("minecraft:blast_furnace"));
        assertEquals(LandmarkKind.SMOKER, LandmarkKind.of("minecraft:smoker"));
        assertEquals(LandmarkKind.STONECUTTER, LandmarkKind.of("minecraft:stonecutter"));
        assertEquals(LandmarkKind.ENCHANTING_TABLE,
                LandmarkKind.of("minecraft:enchanting_table"));
        assertEquals(LandmarkKind.SMITHING_TABLE, LandmarkKind.of("minecraft:smithing_table"));
        assertEquals(LandmarkKind.BREWING_STAND, LandmarkKind.of("minecraft:brewing_stand"));
    }

    @Test
    void theSmeltersAreKeptApartBecauseTheyDoDifferentJobs() {
        // A smoker cannot smelt ore and a blast furnace cannot cook food, so
        // lumping them together would send citizens to the wrong building.
        assertFalse(LandmarkKind.of("minecraft:smoker") == LandmarkKind.of("minecraft:furnace"));
        assertFalse(LandmarkKind.of("minecraft:blast_furnace")
                == LandmarkKind.of("minecraft:furnace"));
    }

    @Test
    void anvilsAreRecognisedHoweverBatteredTheyAre() {
        assertEquals(LandmarkKind.ANVIL, LandmarkKind.of("minecraft:anvil"));
        assertEquals(LandmarkKind.ANVIL, LandmarkKind.of("minecraft:chipped_anvil"));
        assertEquals(LandmarkKind.ANVIL, LandmarkKind.of("minecraft:damaged_anvil"));
    }

    @Test
    void containersAndBedsAreRememberedWhateverColourTheyCome() {
        assertEquals(LandmarkKind.STORAGE, LandmarkKind.of("minecraft:chest"));
        assertEquals(LandmarkKind.STORAGE, LandmarkKind.of("minecraft:barrel"));
        assertEquals(LandmarkKind.STORAGE, LandmarkKind.of("minecraft:red_shulker_box"));
        assertEquals(LandmarkKind.BED, LandmarkKind.of("minecraft:red_bed"));
        assertEquals(LandmarkKind.BED, LandmarkKind.of("minecraft:light_blue_bed"));
    }

    @Test
    void theMachineryPartsAreRemembered() {
        // Levers, buttons and pistons are how a factory is found again later.
        assertEquals(LandmarkKind.MECHANISM, LandmarkKind.of("minecraft:lever"));
        assertEquals(LandmarkKind.MECHANISM, LandmarkKind.of("minecraft:stone_button"));
        assertEquals(LandmarkKind.MECHANISM, LandmarkKind.of("minecraft:oak_pressure_plate"));
        assertEquals(LandmarkKind.MECHANISM, LandmarkKind.of("minecraft:sticky_piston"));
        assertEquals(LandmarkKind.MECHANISM, LandmarkKind.of("minecraft:hopper"));
        assertEquals(LandmarkKind.MECHANISM, LandmarkKind.of("minecraft:observer"));
        assertEquals(LandmarkKind.MECHANISM, LandmarkKind.of("minecraft:powered_rail"));
    }

    @Test
    void ordinaryGroundIsNotWrittenDown() {
        // The gazetteer must stay small enough to be worth searching.
        assertNull(LandmarkKind.of("minecraft:stone"));
        assertNull(LandmarkKind.of("minecraft:oak_planks"));
        assertNull(LandmarkKind.of("minecraft:dirt"));
        assertNull(LandmarkKind.of("minecraft:wheat"));
        assertNull(LandmarkKind.of(null));
        assertNull(LandmarkKind.of(""));
        assertFalse(LandmarkKind.isLandmark("minecraft:cobblestone"));
        assertTrue(LandmarkKind.isLandmark("minecraft:furnace"));
    }

    @Test
    void placesToWorkAreToldApartFromPlacesToStoreAndSleep() {
        assertTrue(LandmarkKind.CRAFTING_TABLE.isWorkstation());
        assertTrue(LandmarkKind.STONECUTTER.isWorkstation());
        assertFalse(LandmarkKind.STORAGE.isWorkstation());
        assertFalse(LandmarkKind.BED.isWorkstation());
        assertFalse(LandmarkKind.MECHANISM.isWorkstation());
    }

    @Test
    void everyKindCanNameItself() {
        for (LandmarkKind kind : LandmarkKind.values()) {
            assertNotNull(kind.label());
            assertFalse(kind.label().isBlank(), kind + " has no label");
        }
    }

    @Test
    void classificationIsStable() {
        for (String id : new String[]{"minecraft:furnace", "minecraft:lever",
                "minecraft:chest", "minecraft:stone"}) {
            assertEquals(LandmarkKind.of(id), LandmarkKind.of(id));
        }
    }
}
