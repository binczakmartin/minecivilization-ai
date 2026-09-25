package ai.minecivilization.citizen;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each trade carries.
 *
 * <p>Written after a session in which the colony did essentially nothing, and
 * every reason traced back to the same missing idea. The shepherd had no wheat,
 * so it could not lead a sheep, so there was never wool, so there were never
 * beds, so the colony could not grow. The miner had no wood, so it could not
 * replace a worn pickaxe or turn its own coal into a torch. Nobody carried
 * torches, so nothing was ever lit; nobody carried planks, so no sign was ever
 * put up. Equipment was the bottleneck behind all of it.</p>
 */
class LoadoutTest {

    private static Map<String, Integer> carrying(Object... pairs) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    private static boolean kitContains(String profession, String itemId) {
        return Loadout.forProfession(profession).stream()
                .anyMatch(need -> need.itemId().equals(itemId));
    }

    // ------------------------------------------------------------------ the kits

    @Test
    void aShepherdCarriesShearsAndSomethingToLureAnimalsWith() {
        // Without a lure it cannot lead an animal into a pen or persuade a
        // pair to breed, and without shears the colony never gets wool —
        // which is what beds, and therefore population growth, are made of.
        assertTrue(kitContains("SHEPHERD", "minecraft:shears"));
        assertTrue(kitContains("SHEPHERD", "minecraft:wheat"));
    }

    @Test
    void aMinerCarriesWoodAndTorches() {
        // Wood underground replaces a worn pickaxe without walking home, and
        // with the coal it is already digging it becomes light.
        assertTrue(kitContains("MINER", "minecraft:oak_planks"));
        assertTrue(kitContains("MINER", "minecraft:torch"));
    }

    @Test
    void aBuilderCarriesBlocksToBuildWith() {
        assertTrue(kitContains("BUILDER", "minecraft:oak_planks"));
        assertTrue(kitContains("BUILDER", "minecraft:cobblestone"));
    }

    @Test
    void everyTradeCarriesAWeaponAndALight() {
        for (String trade : new String[]{"SHEPHERD", "MINER", "LUMBERJACK", "FARMER",
                "BUILDER", "CRAFTER", "LOGISTICS", "UNASSIGNED"}) {
            assertTrue(kitContains(trade, "minecraft:wooden_sword"),
                    trade + " goes out unarmed");
            assertTrue(kitContains(trade, "minecraft:torch"),
                    trade + " goes out with no light");
        }
    }

    @Test
    void anUnknownTradeStillGetsTheCommonKit() {
        assertFalse(Loadout.forProfession("SOMETHING_NEW").isEmpty());
        assertFalse(Loadout.forProfession(null).isEmpty());
    }

    @Test
    void aTradeNeverAsksForTheSameThingTwice() {
        for (String trade : new String[]{"SHEPHERD", "MINER", "LUMBERJACK", "FARMER",
                "BUILDER", "CRAFTER", "LOGISTICS"}) {
            var ids = Loadout.forProfession(trade).stream().map(Loadout.Need::itemId).toList();
            assertEquals(ids.size(), ids.stream().distinct().count(),
                    trade + " has a duplicated need: " + ids);
        }
    }

    // ------------------------------------------------------------------ shortfall

    @Test
    void anEmptyPackAsksForSomething() {
        Loadout.Need need = Loadout.nextMissing("SHEPHERD", carrying());
        assertNotNull(need);
    }

    @Test
    void essentialsComeBeforeSpares() {
        // A shepherd without shears is a more urgent problem than one without
        // a tidy stock of fencing.
        Loadout.Need need = Loadout.nextMissing("SHEPHERD", carrying(), true);
        assertNotNull(need);
        assertFalse(need.spare(), "asked for a spare while an essential was missing");
    }

    @Test
    void aFullyEquippedCitizenAsksForNothing() {
        Map<String, Integer> pack = new HashMap<>();
        for (Loadout.Need need : Loadout.forProfession("MINER")) {
            pack.put(need.itemId(), need.quantity());
        }
        assertNull(Loadout.nextMissing("MINER", pack, true));
    }

    @Test
    void sparesAreSkippedUntilAskedFor() {
        Map<String, Integer> pack = new HashMap<>();
        for (Loadout.Need need : Loadout.forProfession("LUMBERJACK")) {
            if (!need.spare()) pack.put(need.itemId(), need.quantity());
        }
        assertNull(Loadout.nextMissing("LUMBERJACK", pack),
                "essentials are met, so nothing is needed");
        assertNotNull(Loadout.nextMissing("LUMBERJACK", pack, true),
                "a spare should be offered once the essentials are covered");
    }

    // ------------------------------------------------------------------ upgrades

    @Test
    void aBetterToolSatisfiesTheNeedForAWorseOne() {
        // A citizen holding an iron pickaxe must not spend its afternoon
        // making a wooden one.
        Loadout.Need wooden = new Loadout.Need("minecraft:wooden_pickaxe", 1,
                Loadout.Source.CRAFT, false);
        assertTrue(Loadout.satisfied(wooden, carrying("minecraft:stone_pickaxe", 1)));
        assertTrue(Loadout.satisfied(wooden, carrying("minecraft:iron_pickaxe", 1)));
        assertTrue(Loadout.satisfied(wooden, carrying("minecraft:netherite_pickaxe", 1)));
        assertFalse(Loadout.satisfied(wooden, carrying("minecraft:wooden_axe", 1)));
    }

    @Test
    void aWorseToolDoesNotSatisfyTheNeedForABetterOne() {
        Loadout.Need stone = new Loadout.Need("minecraft:stone_axe", 1,
                Loadout.Source.CRAFT, false);
        assertFalse(Loadout.satisfied(stone, carrying("minecraft:wooden_axe", 1)));
    }

    @Test
    void theUpgradeLadderClimbsAndThenStops() {
        assertEquals("minecraft:stone_axe", Loadout.betterThan("minecraft:wooden_axe"));
        assertEquals("minecraft:iron_axe", Loadout.betterThan("minecraft:stone_axe"));
        assertNull(Loadout.betterThan("minecraft:netherite_axe"));
        assertNull(Loadout.betterThan("minecraft:oak_planks"));
        assertNull(Loadout.betterThan(null));
    }

    @Test
    void partialStockStillCountsAsMissing() {
        Loadout.Need torches = new Loadout.Need("minecraft:torch", 8,
                Loadout.Source.CRAFT, false);
        assertFalse(Loadout.satisfied(torches, carrying("minecraft:torch", 3)));
        assertTrue(Loadout.satisfied(torches, carrying("minecraft:torch", 8)));
    }
}
