package ai.minecivilization.colony;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the colony writes on its signs, and what it reads back off them.
 *
 * <p>Signs are the settlement's interface in both directions: citizens write
 * what they know, and they believe what a player writes. That makes the
 * wording a contract rather than decoration — {@code DANGER} has to survive
 * the round trip through a four-line, fifteen-character sign and still parse
 * back as danger.</p>
 */
class SignPlanTest {

    // ------------------------------------------------------------------ writing

    @Test
    void aDistrictSignShoutsWhatTheDistrictIs() {
        SignPlan.Text text = SignPlan.district(ZoneType.RESIDENTIAL, "Housing 1", 1);
        assertEquals(SignKind.RESIDENTIAL, text.kind());
        assertEquals("RESIDENTIAL DISTRICT", text.title());
        assertEquals("Housing 1", text.detail());
    }

    @Test
    void asecondDistrictOfAKindIsNumbered() {
        // So a player can say "meet me at mine 2" and be understood.
        SignPlan.Text text = SignPlan.district(ZoneType.MINE, "Mine 2", 2);
        assertEquals("MINE 2", text.title());
    }

    @Test
    void aRoadSignSaysWhereTheRoadGoes() {
        SignPlan.Text text = SignPlan.road("iron mine");
        assertEquals(SignKind.ROAD, text.kind());
        assertEquals("ROAD", text.title());
        assertEquals("-> IRON MINE", text.detail());
    }

    @Test
    void aProjectSignCarriesItsProgress() {
        SignPlan.Text text = SignPlan.project("Town Hall", 42);
        assertEquals(SignKind.PROJECT, text.kind());
        assertTrue(text.detail().contains("42%"));
    }

    @Test
    void nonsensicalProgressIsClamped() {
        assertTrue(SignPlan.project("Hall", -20).detail().contains("0%"));
        assertTrue(SignPlan.project("Hall", 500).detail().contains("100%"));
    }

    @Test
    void anUnnamedColonyStillGetsAReadableTownHallSign() {
        // A blank sign is worse than a generic one.
        assertEquals("the colony", SignPlan.townHall(null).detail());
        assertEquals("the colony", SignPlan.townHall("  ").detail());
    }

    @Test
    void everySignFitsOnARealSign() {
        SignPlan.Text[] samples = {
                SignPlan.townHall("Riverhold"),
                SignPlan.district(ZoneType.FOREST, "Managed forest 3", 3),
                SignPlan.mine(1, "minecraft:deepslate_iron_ore", -16),
                SignPlan.road("the great northern forest"),
                SignPlan.danger("deep cave"),
                SignPlan.notice(14, 220, 3),
        };
        for (SignPlan.Text text : samples) {
            var lines = Signpost.layout(text.title(), text.detail());
            assertEquals(Signpost.LINES, lines.size());
            for (String line : lines) {
                assertTrue(line.length() <= Signpost.LINE_WIDTH,
                        "'" + line + "' would be cut off on a real sign");
            }
            // The headline must survive: a sign whose first line was dropped
            // cannot be read back by the colony.
            assertTrue(lines.get(0).length() > 0, "'" + text.flat() + "' lost its headline");
        }
    }

    // ------------------------------------------------------------------ reading back

    @Test
    void theColonyRecognisesItsOwnSigns() {
        assertEquals(SignKind.TOWN_HALL, SignKind.parse("TOWN HALL"));
        assertEquals(SignKind.MINE, SignKind.parse("MINE 2"));
        assertEquals(SignKind.WAREHOUSE, SignKind.parse("warehouse"));
    }

    @Test
    void aPlayerWarningIsBelieved() {
        // The point of reading signs back: a player labels a spot and the
        // colony treats it as labelled.
        assertEquals(SignKind.DANGER, SignKind.parse("DANGER"));
        assertEquals(SignKind.DANGER, SignKind.parse("danger - deep cave"));
        assertEquals(SignKind.CAVE, SignKind.parse("CAVE ENTRANCE"));
    }

    @Test
    void aRoadSignIsRecognisedByItsArrow() {
        assertEquals(SignKind.ROAD, SignKind.parse("ROAD -> IRON MINE"));
    }

    @Test
    void aShoppingListIsNotAFactAboutTheSettlement() {
        assertNull(SignKind.parse("buy milk"));
        assertNull(SignKind.parse(""));
        assertNull(SignKind.parse(null));
    }

    @Test
    void everyZoneKindHasASignToGoWithIt() {
        for (ZoneType type : ZoneType.values()) {
            SignKind kind = SignKind.forZone(type);
            assertNotNull(kind, type + " has no sign kind");
            // And each one reads back as itself, so a district the colony
            // labelled is a district the colony recognises later.
            assertEquals(kind, SignKind.parse(kind.headline()),
                    kind + " does not survive being read back");
        }
    }
}
