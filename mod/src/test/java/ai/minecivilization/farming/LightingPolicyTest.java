package ai.minecivilization.farming;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightingPolicyTest {
    @Test void startsFromNothingAndBuildsMissingInfrastructureInOrder() {
        assertEquals(new LightingPolicy.Job("CRAFT", "minecraft:crafting_table", 1), LightingPolicy.next(Map.of(), false, false, false));
        assertEquals(new LightingPolicy.Job("PLACE", "minecraft:crafting_table", 1), LightingPolicy.next(Map.of("minecraft:crafting_table", 1), false, false, false));
        assertEquals(new LightingPolicy.Job("CRAFT", "minecraft:wooden_pickaxe", 1), LightingPolicy.next(Map.of(), true, false, false));
        assertEquals(new LightingPolicy.Job("CRAFT", "minecraft:furnace", 1), LightingPolicy.next(Map.of(), true, false, true));
        assertEquals(new LightingPolicy.Job("PLACE", "minecraft:furnace", 1), LightingPolicy.next(Map.of("minecraft:furnace", 1), true, false, true));
        assertEquals(new LightingPolicy.Job("CRAFT", "minecraft:charcoal", 4), LightingPolicy.next(Map.of(), true, true, true));
        assertEquals(new LightingPolicy.Job("CRAFT", "minecraft:torch", 16), LightingPolicy.next(Map.of("minecraft:charcoal", 4), true, true, true));
        assertEquals(new LightingPolicy.Job("DECORATE", "minecraft:torch", 8), LightingPolicy.next(Map.of("minecraft:torch", 16), true, true, true));
    }
    @Test void existingFuelOrTorchesBypassUnnecessaryInfrastructure() {
        assertEquals("minecraft:torch", LightingPolicy.next(Map.of("minecraft:coal", 4), false, false, false).item());
        assertEquals("DECORATE", LightingPolicy.next(Map.of("minecraft:torch", 1), false, false, false).type());
        assertEquals("minecraft:charcoal", LightingPolicy.next(Map.of(), false, true, false).item());
    }
    @Test void oneCharcoalAlreadyMakesFourTorchesWithoutAFurnace() {
        assertEquals(new LightingPolicy.Job("CRAFT", "minecraft:torch", 4),
            LightingPolicy.next(Map.of("minecraft:charcoal", 1), false, false, false));
    }
    @Test void enoughStoneDoesNotRequireAnotherPickaxe() {
        assertEquals("minecraft:furnace", LightingPolicy.next(Map.of("minecraft:cobblestone", 8), true, false, false).item());
    }
    @Test void daylightMustNotHideTheNeedForNightLighting() {
        assertTrue(FarmLighting.needsLight(0));
        assertTrue(FarmLighting.needsLight(8));
        assertFalse(FarmLighting.needsLight(9));
        assertFalse(FarmLighting.needsLight(14));
    }
}
