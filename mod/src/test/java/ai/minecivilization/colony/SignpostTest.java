package ai.minecivilization.colony;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fitting a name onto four short lines without silently losing it. */
class SignpostTest {

    @Test
    void aSignAlwaysHasExactlyFourLines() {
        for (String title : new String[]{"", "Mine", "Warehouse district number four"}) {
            assertEquals(Signpost.LINES, Signpost.layout(title, "at 10, -20").size(),
                    "wrong line count for " + title);
        }
    }

    @Test
    void nothingOverrunsTheWidthOfASign() {
        List<String> lines = Signpost.layout(
                "Managed forest plantation", "nearest 120 blocks north");
        for (String line : lines) {
            assertTrue(line.length() <= Signpost.LINE_WIDTH,
                    "line overruns and would be cut off: '" + line + "'");
        }
    }

    @Test
    void theTitleGetsTheRoomItNeedsBeforeTheDetail() {
        // A district's name matters more than its coordinates.
        List<String> lines = Signpost.layout("Iron level", "y=16");
        assertTrue(lines.get(0).contains("Iron"));
        assertTrue(String.join(" ", lines).contains("y=16"));
    }

    @Test
    void aLongTitleTakesTheWholeSignRatherThanBeingTruncated() {
        List<String> lines = Signpost.layout(
                "Redstone and machinery workshop district", "detail dropped");
        String joined = String.join(" ", lines).trim();
        assertTrue(joined.startsWith("Redstone"), joined);
        assertFalse(joined.contains("detail"), "the detail should give way to the name");
    }

    @Test
    void aWordTooLongForALineIsCutRatherThanDropped() {
        // A truncated label still says more than a blank sign.
        List<String> lines = Signpost.wrap("Supercalifragilisticexpialidocious", 4);
        assertFalse(lines.isEmpty());
        assertTrue(lines.get(0).startsWith("Supercali"));
        for (String line : lines) {
            assertTrue(line.length() <= Signpost.LINE_WIDTH);
        }
    }

    @Test
    void wrappingBreaksOnWordsWhereItCan() {
        List<String> lines = Signpost.wrap("Coal level here", 4);
        for (String line : lines) {
            assertFalse(line.startsWith(" ") || line.endsWith(" "), "ragged line: '" + line + "'");
        }
        assertEquals("Coal level here", String.join(" ", lines));
    }

    @Test
    void emptyInputProducesABlankSignRatherThanNonsense() {
        assertTrue(Signpost.wrap(null, 4).isEmpty());
        assertTrue(Signpost.wrap("   ", 4).isEmpty());
        assertTrue(Signpost.wrap("anything", 0).isEmpty());
        for (String line : Signpost.layout(null, null)) {
            assertEquals("", line);
        }
    }

    @Test
    void zonesAndOresGetReadableNames() {
        assertEquals("Managed forest", Signpost.titleFor(ZoneType.FOREST));
        assertEquals("Farmland", Signpost.titleFor(ZoneType.FARM));

        assertEquals("Iron", Signpost.titleForOre("minecraft:iron_ore"));
        assertEquals("Iron", Signpost.titleForOre("minecraft:deepslate_iron_ore"));
        assertEquals("Lapis", Signpost.titleForOre("minecraft:lapis_ore"));
        assertEquals("Ore", Signpost.titleForOre(null));
    }
}
