package ai.minecivilization.telemetry;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colony seen from above.
 *
 * <p>The projection is the part worth testing: north has to be up, the centre
 * has to be the centre, and a colony that has spread out has to still fit on
 * the map. The glyphs are cosmetic; being able to see that the mine is on the
 * wrong side of the ravine is not.</p>
 */
class MiniMapTest {

    @Test
    void theCentreOfTheWorldIsTheCentreOfTheMap() {
        MiniMap map = new MiniMap(100, 200, 21, 11, 1);
        map.plot(100, 200, MiniMap.Cell.TOWN_HALL);
        assertEquals(MiniMap.Cell.TOWN_HALL, map.at(10, 5));
    }

    @Test
    void northIsUpAndEastIsRight() {
        MiniMap map = new MiniMap(0, 0, 21, 11, 1);
        map.plot(0, -3, MiniMap.Cell.CITIZEN);   // three blocks north
        map.plot(4, 0, MiniMap.Cell.CITIZEN);    // four blocks east

        assertEquals(MiniMap.Cell.CITIZEN, map.at(10, 2), "north should be above centre");
        assertEquals(MiniMap.Cell.CITIZEN, map.at(14, 5), "east should be right of centre");
    }

    @Test
    void anythingOffTheEdgeIsSimplyNotDrawn() {
        MiniMap map = new MiniMap(0, 0, 11, 11, 1);
        assertFalse(map.plot(10_000, 0, MiniMap.Cell.CITIZEN));
        assertTrue(map.plot(0, 0, MiniMap.Cell.CITIZEN));
    }

    @Test
    void theScaleIsChosenSoEverythingFits() {
        List<MiniMap.Marker> markers = List.of(
                new MiniMap.Marker(0, 0, MiniMap.Cell.TOWN_HALL),
                new MiniMap.Marker(600, 0, MiniMap.Cell.MINE),
                new MiniMap.Marker(0, -400, MiniMap.Cell.FARM));

        MiniMap map = MiniMap.fitting(0, 0, 41, 21, markers);
        assertTrue(map.scale() > 1, "a spread-out colony needs a coarser scale");
        // Every marker must actually be on the map, which is the whole point
        // of choosing the scale from the content.
        for (MiniMap.Marker marker : markers) {
            assertTrue(map.column(marker.x()) >= 0 && map.column(marker.x()) < map.width(),
                    "marker at x=" + marker.x() + " fell off the map");
            assertTrue(map.row(marker.z()) >= 0 && map.row(marker.z()) < map.height(),
                    "marker at z=" + marker.z() + " fell off the map");
        }
    }

    @Test
    void aTinyColonyKeepsAFineScale() {
        List<MiniMap.Marker> markers = List.of(new MiniMap.Marker(3, 3, MiniMap.Cell.CITIZEN));
        MiniMap map = MiniMap.fitting(0, 0, 41, 21, markers);
        assertEquals(2, map.scale());   // the 16-block floor over half of 21 rows
    }

    @Test
    void theMoreImportantThingWinsACell() {
        MiniMap map = new MiniMap(0, 0, 11, 11, 1);
        map.plot(0, 0, MiniMap.Cell.DISTRICT);
        map.plot(0, 0, MiniMap.Cell.CITIZEN);
        assertEquals(MiniMap.Cell.CITIZEN, map.at(5, 5),
                "a citizen standing in a district should read as a citizen");

        map.plot(0, 0, MiniMap.Cell.TROUBLE);
        assertEquals(MiniMap.Cell.TROUBLE, map.at(5, 5),
                "somebody in trouble is the thing you need to see");

        map.plot(0, 0, MiniMap.Cell.ROAD);
        assertEquals(MiniMap.Cell.TROUBLE, map.at(5, 5),
                "a road must not paint over a stranded citizen");
    }

    @Test
    void aRoadIsDrawnAsAContinuousLine() {
        MiniMap map = new MiniMap(0, 0, 21, 11, 1);
        map.plotLine(-8, 0, 8, 0, MiniMap.Cell.ROAD);
        for (int column = 2; column <= 18; column++) {
            assertEquals(MiniMap.Cell.ROAD, map.at(column, 5),
                    "gap in the road at column " + column);
        }
    }

    @Test
    void renderingProducesOneStringPerRow() {
        MiniMap map = new MiniMap(0, 0, 13, 7, 4);
        List<String> lines = map.render();
        assertEquals(7, lines.size());
        for (String line : lines) assertEquals(13, line.length());
    }

    @Test
    void theLegendOnlyExplainsSymbolsActuallyUsed() {
        MiniMap map = new MiniMap(0, 0, 11, 11, 1);
        map.plot(0, 0, MiniMap.Cell.TOWN_HALL);
        map.plot(1, 1, MiniMap.Cell.CITIZEN);

        var legend = map.legend();
        assertEquals(2, legend.size());
        assertTrue(legend.containsKey(MiniMap.Cell.TOWN_HALL.glyph));
        assertFalse(legend.containsKey(MiniMap.Cell.MINE.glyph));
    }
}
