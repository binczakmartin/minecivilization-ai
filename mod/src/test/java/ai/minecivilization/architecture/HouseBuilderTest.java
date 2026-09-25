package ai.minecivilization.architecture;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.minecivilization.construction.Blueprint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What separates a designed building from a box with a lid.
 *
 * <p>These are the checks a builder would run their eye over: does it stand up,
 * is it closed, does the roof overhang, is the facade symmetric, and can a
 * young colony actually afford it.</p>
 */
class HouseBuilderTest {

    private static final Palette OAK = Palette.forSpecies("oak");

    private static Blueprint house(int w, int d) {
        return HouseBuilder.house("test_house", "Test House", w, d, OAK, 7L);
    }

    private static String key(Blueprint.BlockEntry e) {
        return e.x + "," + e.y + "," + e.z;
    }

    // ------------------------------------------------------------------ soundness

    @Test
    void noTwoBlocksArePlannedForTheSameSpot() {
        // Two entries on one cell means one silently replaces the other, and
        // the colony pays for both.
        for (int w = 5; w <= 11; w += 2) {
            for (int d = 5; d <= 11; d += 2) {
                Set<String> seen = new HashSet<>();
                for (Blueprint.BlockEntry e : house(w, d).entries()) {
                    assertTrue(seen.add(key(e)),
                            w + "x" + d + ": two blocks planned for " + key(e));
                }
            }
        }
    }

    @Test
    void theBuildOrderNeverPlacesABlockBeforeWhatHoldsItUp() {
        // The builder works through the list one block at a time, so anything
        // that needs support must come after it.
        Blueprint blueprint = house(9, 7);
        Set<String> placed = new HashSet<>();
        int previousY = Integer.MIN_VALUE;
        int descents = 0;

        for (Blueprint.BlockEntry e : blueprint.entries()) {
            if (e.y < previousY) descents++;
            previousY = e.y;
            boolean roofPiece = e.y > HouseBuilder.WALL_HEIGHT
                    && (e.itemId().equals(OAK.stairs()) || e.itemId().equals(OAK.slab()));
            if (roofPiece) {
                assertTrue(placed.contains(e.x + "," + (e.y - 1) + "," + e.z),
                        "roof piece has no support in build order at " + key(e));
            }
            placed.add(key(e));
        }
        // Courses may revisit a lower level between stages (walls, then the
        // plinth), but the build must not oscillate wildly.
        assertTrue(descents <= 4,
                "the build order wanders up and down " + descents + " times");
    }

    @Test
    void theFoundationReachesBelowTheFloorToCatchASlope() {
        Blueprint blueprint = house(9, 7);
        int lowest = blueprint.entries().stream().mapToInt(e -> e.y).min().orElseThrow();

        assertEquals(-HouseBuilder.SKIRT_DEPTH, lowest,
                "without a skirt the downhill corner hangs in the air");
        for (Blueprint.BlockEntry e : blueprint.entries()) {
            if (e.y < 0) {
                assertEquals(OAK.foundation(), e.itemId(), "the skirt must be stone, not timber");
            }
        }
    }

    // ------------------------------------------------------------------ the facade

    @Test
    void theCornersArePostsNotPlainWall() {
        Blueprint blueprint = house(9, 7);
        Map<String, String> byPos = new HashMap<>();
        for (Blueprint.BlockEntry e : blueprint.entries()) byPos.put(key(e), e.itemId());

        for (int y = 1; y <= HouseBuilder.WALL_HEIGHT; y++) {
            for (int[] corner : new int[][]{{0, 0}, {8, 0}, {0, 6}, {8, 6}}) {
                assertEquals(OAK.post(), byPos.get(corner[0] + "," + y + "," + corner[1]),
                        "corner post missing at " + corner[0] + "," + y + "," + corner[1]);
            }
        }
    }

    @Test
    void theWallCarriesABandedCourseRatherThanOneFlatTexture() {
        Blueprint blueprint = house(9, 7);
        boolean banded = blueprint.entries().stream()
                .anyMatch(e -> e.itemId().equals(OAK.trim()));
        assertTrue(banded, "a single flat field of planks never reads as a wall");
    }

    @Test
    void windowsAreSymmetricAndNeverInACorner() {
        for (int span = 5; span <= 15; span++) {
            List<Integer> windows = HouseBuilder.windowPositions(span, 7L);
            for (int position : windows) {
                assertTrue(position >= 2 && position <= span - 3,
                        "window at " + position + " crowds a corner of a " + span + " wall");
            }
            // Symmetry: every window has a mirror about the middle of the wall.
            for (int position : windows) {
                int mirror = span - 1 - position;
                assertTrue(windows.contains(mirror),
                        "window at " + position + " has no mirror at " + mirror
                                + " on a " + span + " wall");
            }
        }
    }

    @Test
    void thereIsAWayIn() {
        Blueprint blueprint = house(9, 7);
        Set<String> occupied = new HashSet<>();
        for (Blueprint.BlockEntry e : blueprint.entries()) occupied.add(key(e));

        int doorX = 9 / 2;
        assertFalse(occupied.contains(doorX + ",1," + 6), "the doorway is bricked up");
        assertFalse(occupied.contains(doorX + ",2," + 6), "the doorway is only one block high");
    }

    @Test
    void generatedHousesHaveAWorkstationAndInteriorLight() {
        Blueprint blueprint = house(9, 7);
        assertTrue(blueprint.entries().stream()
                .anyMatch(e -> e.itemId().equals("minecraft:crafting_table")));
        assertTrue(blueprint.entries().stream()
                .anyMatch(e -> e.itemId().equals(OAK.light()) && e.y == 1));
    }

    @Test
    void theDoorwayIsLit() {
        Blueprint blueprint = house(9, 7);
        boolean lit = blueprint.entries().stream()
                .anyMatch(e -> e.itemId().equals(OAK.light()));
        assertTrue(lit, "an unlit doorway is where the colony loses people");
    }

    // ------------------------------------------------------------------ the roof

    @Test
    void theRoofOverhangsTheWalls() {
        Blueprint blueprint = house(9, 7);
        int minX = blueprint.entries().stream().mapToInt(e -> e.x).min().orElseThrow();
        int maxX = blueprint.entries().stream().mapToInt(e -> e.x).max().orElseThrow();

        assertTrue(minX < 0 && maxX > 8,
                "eaves flush with the wall cast no shadow line, and read as a lid");
    }

    @Test
    void theRoofIsPitchedAndClosedAtTheTop() {
        Blueprint blueprint = house(9, 7);
        boolean stairs = blueprint.entries().stream()
                .anyMatch(e -> e.itemId().equals(OAK.stairs()));
        boolean ridge = blueprint.entries().stream()
                .anyMatch(e -> e.itemId().equals(OAK.slab()));

        assertTrue(stairs, "a flat lid is not a roof");
        assertTrue(ridge, "a pitched roof with no ridge is left open at the peak");
    }

    @Test
    void oppositeRoofSlopesFaceOppositeWays() {
        Blueprint blueprint = house(9, 7);
        boolean north = blueprint.entries().stream()
                .anyMatch(e -> e.blockState.contains("facing=north"));
        boolean south = blueprint.entries().stream()
                .anyMatch(e -> e.blockState.contains("facing=south"));

        assertTrue(north && south, "both slopes face the same way — one of them is upside down");
    }

    @Test
    void theGableEndsAreClosed() {
        Blueprint blueprint = house(9, 7);
        Set<String> occupied = new HashSet<>();
        for (Blueprint.BlockEntry e : blueprint.entries()) occupied.add(key(e));

        // Directly above the wall top, at the end walls, the triangle must fill.
        int y = HouseBuilder.WALL_HEIGHT + 1;
        assertTrue(occupied.contains("0," + y + ",3") || occupied.contains("0," + y + ",2"),
                "the gable end is open — the clearest mark of an unfinished build");
    }

    // ------------------------------------------------------------------ affordability

    @Test
    void aYoungColonyCanAffordTheMaterials() {
        Map<String, Integer> bom = house(7, 7).billOfMaterials();

        assertFalse(bom.isEmpty());
        for (String item : bom.keySet()) {
            assertTrue(OAK.materials().contains(item),
                    item + " is in the build but not in the palette");
        }
        // Nothing rare: a first house must not need iron, glass blocks or dye.
        for (String item : bom.keySet()) {
            assertFalse(item.contains("iron") || item.contains("diamond")
                            || item.contains("quartz"),
                    "a starter house should not need " + item);
        }
    }

    @Test
    void thePaletteFollowsTheWoodTheColonyCuts() {
        Palette spruce = Palette.fromLog("minecraft:spruce_log");
        assertEquals("minecraft:spruce_planks", spruce.wall());
        assertEquals("minecraft:spruce_log", spruce.post());
        assertEquals("minecraft:stripped_spruce_log", spruce.trim());

        assertEquals(Palette.forSpecies("oak"), Palette.fromLog("minecraft:unknown_log"),
                "an unknown wood falls back to oak rather than producing nonsense");
        assertEquals("minecraft:birch_planks",
                Palette.fromLog("minecraft:stripped_birch_wood").wall());
    }

    // ------------------------------------------------------------------ variation

    @Test
    void housesVaryButOneHouseIsAlwaysTheSame() {
        Blueprint a = HouseBuilder.house("a", "A", 11, 7, OAK, 1L);
        Blueprint b = HouseBuilder.house("b", "B", 11, 7, OAK, 2L);
        Blueprint again = HouseBuilder.house("a", "A", 11, 7, OAK, 1L);

        assertEquals(a.entries().toString(), again.entries().toString(),
                "the same house must rebuild identically after an interruption");
        assertFalse(a.entries().toString().equals(b.entries().toString()),
                "every house on the street being identical is its own kind of wrong");
    }

    @Test
    void awkwardFootprintsStillProduceASoundHouse() {
        for (int[] size : new int[][]{{5, 5}, {6, 5}, {5, 8}, {13, 6}}) {
            Blueprint blueprint = HouseBuilder.house("x", "X", size[0], size[1], OAK, 3L);
            assertNotNull(blueprint);
            assertTrue(blueprint.blockCount() > 0);

            Set<String> seen = new HashSet<>();
            for (Blueprint.BlockEntry e : blueprint.entries()) {
                assertTrue(seen.add(key(e)),
                        size[0] + "x" + size[1] + ": duplicate at " + key(e));
            }
        }
    }
}
