package ai.minecivilization.colony;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The town plan: pure integer geometry, no world. */
class ZoneLayoutTest {

    private static final int CX = 100;
    private static final int CZ = -40;

    // ------------------------------------------------------------------ the spiral

    @Test
    void theTownCentreIsAllottedFirst() {
        List<ZoneLayout.Plot> order = ZoneLayout.spiral(2);

        assertEquals(new ZoneLayout.Plot(0, 0), order.get(0));
        assertEquals(0, order.get(0).ring());
    }

    @Test
    void plotsAreAllottedRingByRingNearestFirst() {
        List<ZoneLayout.Plot> order = ZoneLayout.spiral(3);

        int previousRing = -1;
        for (ZoneLayout.Plot plot : order) {
            assertTrue(plot.ring() >= previousRing,
                    "a town grows outward, never back inward: " + plot);
            previousRing = plot.ring();
        }
        // 1 + 8 + 16 + 24 plots for rings 0..3
        assertEquals(49, order.size());
    }

    @Test
    void straightNeighboursComeBeforeDiagonalsInARing() {
        List<ZoneLayout.Plot> order = ZoneLayout.spiral(1);

        // ring 1: the four edge plots (distance 1) before the four corners (2)
        List<ZoneLayout.Plot> ringOne = order.subList(1, order.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(1, ringOne.get(i).px() * ringOne.get(i).px()
                    + ringOne.get(i).pz() * ringOne.get(i).pz(),
                    "edges first: " + ringOne.get(i));
        }
    }

    @Test
    void theSpiralHasNoDuplicates() {
        List<ZoneLayout.Plot> order = ZoneLayout.spiral(ZoneLayout.MAX_RING);
        assertEquals(order.size(), new HashSet<>(order).size());
    }

    // ------------------------------------------------------------------ allocation

    @Test
    void everyZoneTypeRespectsItsDistanceFromTheCentre() {
        for (ZoneType type : ZoneType.values()) {
            ZoneLayout.Plot plot = ZoneLayout.allocate(type, Set.of());
            assertNotNull(plot, type + " must fit in an empty town");
            assertTrue(plot.ring() >= type.minRing,
                    type + " was placed at ring " + plot.ring()
                            + ", closer than its minimum " + type.minRing);
        }
    }

    @Test
    void theCivicCentreTakesThePlotOnTheTownCentre() {
        assertEquals(new ZoneLayout.Plot(0, 0), ZoneLayout.allocate(ZoneType.CIVIC, Set.of()));
    }

    @Test
    void noisyTradesAreKeptAwayFromHouses() {
        ZoneLayout.Plot houses = ZoneLayout.allocate(ZoneType.RESIDENTIAL, Set.of());
        ZoneLayout.Plot mine = ZoneLayout.allocate(ZoneType.MINE, Set.of());

        assertTrue(mine.ring() > houses.ring(),
                "a quarry does not belong next to the bedrooms");
    }

    @Test
    void allocationNeverHandsOutTheSamePlotTwice() {
        Set<ZoneLayout.Plot> taken = new HashSet<>();
        ZoneType[] wanted = {
            ZoneType.CIVIC, ZoneType.STORAGE, ZoneType.RESIDENTIAL, ZoneType.RESIDENTIAL,
            ZoneType.FARM, ZoneType.PASTURE, ZoneType.INDUSTRIAL, ZoneType.FOREST,
            ZoneType.MINE, ZoneType.RESIDENTIAL, ZoneType.FARM};

        for (ZoneType type : wanted) {
            ZoneLayout.Plot plot = ZoneLayout.allocate(type, taken);
            assertNotNull(plot, "the town filled up too early at " + type);
            assertTrue(taken.add(plot), "plot handed out twice: " + plot);
        }
        assertEquals(wanted.length, taken.size());
    }

    @Test
    void aFullTownRefusesRatherThanOverlapping() {
        Set<ZoneLayout.Plot> taken = new HashSet<>(ZoneLayout.spiral(2));

        // Everything inside ring 2 is spoken for, so a house has to go further out.
        ZoneLayout.Plot house = ZoneLayout.allocate(ZoneType.RESIDENTIAL, taken, 2);
        assertNull(house, "with no room inside the limit, allocation must decline");

        assertNotNull(ZoneLayout.allocate(ZoneType.RESIDENTIAL, taken, 3),
                "a wider limit finds room again");
    }

    @Test
    void allocationIsDeterministic() {
        Set<ZoneLayout.Plot> taken = Set.of(new ZoneLayout.Plot(0, 0));
        assertEquals(ZoneLayout.allocate(ZoneType.STORAGE, taken),
                ZoneLayout.allocate(ZoneType.STORAGE, taken));
    }

    // ------------------------------------------------------------------ world bounds

    @Test
    void theCentralPlotIsCentredOnTheTownCentre() {
        ZoneLayout.Bounds b = ZoneLayout.bounds(CX, CZ, new ZoneLayout.Plot(0, 0));

        assertEquals(ZoneLayout.PLOT_SIZE, b.sizeX());
        assertEquals(ZoneLayout.PLOT_SIZE, b.sizeZ());
        assertTrue(b.contains(CX, CZ), "the anchor must fall inside its own plot");
    }

    @Test
    void plotsAreSeparatedByStreets() {
        ZoneLayout.Bounds a = ZoneLayout.bounds(CX, CZ, new ZoneLayout.Plot(0, 0));
        ZoneLayout.Bounds b = ZoneLayout.bounds(CX, CZ, new ZoneLayout.Plot(1, 0));

        int gap = b.minX() - a.maxX() - 1;
        assertEquals(ZoneLayout.PLOT_PITCH - ZoneLayout.PLOT_SIZE, gap,
                "the gap between plots is what makes a street");
        assertTrue(gap > 0, "plots that touch leave nowhere to walk");
    }

    @Test
    void noTwoPlotsEverOverlap() {
        List<ZoneLayout.Bounds> all = new ArrayList<>();
        for (ZoneLayout.Plot plot : ZoneLayout.spiral(3)) {
            all.add(ZoneLayout.bounds(CX, CZ, plot));
        }
        assertTrue(ZoneLayout.noOverlaps(all));
    }

    @Test
    void aPositionResolvesBackToItsOwnPlot() {
        for (ZoneLayout.Plot plot : ZoneLayout.spiral(3)) {
            ZoneLayout.Bounds b = ZoneLayout.bounds(CX, CZ, plot);
            assertEquals(plot, ZoneLayout.plotAt(CX, CZ, b.centerX(), b.centerZ()),
                    "round trip failed for " + plot);
            assertEquals(plot, ZoneLayout.plotAt(CX, CZ, b.minX(), b.minZ()),
                    "the near corner belongs to its own plot: " + plot);
            assertEquals(plot, ZoneLayout.plotAt(CX, CZ, b.maxX(), b.maxZ()),
                    "the far corner belongs to its own plot: " + plot);
        }
    }

    @Test
    void theTownRadiusGrowsWithItsRings() {
        assertTrue(ZoneLayout.townRadius(3) > ZoneLayout.townRadius(1));
        ZoneLayout.Bounds far = ZoneLayout.bounds(0, 0, new ZoneLayout.Plot(3, 0));
        assertTrue(far.maxX() <= ZoneLayout.townRadius(3),
                "the radius must actually cover the outermost plot");
    }
}
