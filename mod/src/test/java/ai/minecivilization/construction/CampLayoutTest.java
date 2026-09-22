package ai.minecivilization.construction;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The camp row is a geometry contract: houses grow east of the anchor (the
 * player's bed) with room to walk, and never on top of each other.
 */
class CampLayoutTest {

    private static final int ANCHOR_X = 100;
    private static final int ANCHOR_Z = -20;

    @Test
    void rowGrowsEastOfTheAnchor() {
        assertEquals(ANCHOR_X + 6, CampLayout.slotX(ANCHOR_X, 0));
        assertEquals(ANCHOR_X + 6 + 9, CampLayout.slotX(ANCHOR_X, 1));
        assertEquals(ANCHOR_Z - 2, CampLayout.slotZ(ANCHOR_Z));
        // The first slot clears the bed: a 5-wide house starts 6 blocks east.
        assertTrue(CampLayout.slotX(ANCHOR_X, 0) - ANCHOR_X > 5);
    }

    @Test
    void slotsOfOneAnchorNeverCollide() {
        List<CampLayout.Rect> row = new ArrayList<>();
        for (int slot = 0; slot < CampLayout.MAX_SLOTS; slot++) {
            CampLayout.Rect next = new CampLayout.Rect(
                    CampLayout.slotX(ANCHOR_X, slot), CampLayout.slotZ(ANCHOR_Z), 5, 4);
            assertTrue(CampLayout.free(next, row), "slot " + slot + " collides with the row");
            row.add(next);
        }
    }

    @Test
    void isSlotOnlyMatchesItsOwnAnchor() {
        int x = CampLayout.slotX(ANCHOR_X, 2);
        int z = CampLayout.slotZ(ANCHOR_Z);
        assertTrue(CampLayout.isSlot(ANCHOR_X, ANCHOR_Z, x, z));
        assertFalse(CampLayout.isSlot(ANCHOR_X, ANCHOR_Z, x + 1, z));        // off-grid
        assertFalse(CampLayout.isSlot(ANCHOR_X, ANCHOR_Z, x, z + 1));        // off-row
        assertFalse(CampLayout.isSlot(ANCHOR_X + 40, ANCHOR_Z, x, z));       // other camp
        assertFalse(CampLayout.isSlot(ANCHOR_X, ANCHOR_Z,
                ANCHOR_X + CampLayout.OFFSET_X - CampLayout.SPACING, z));    // west of the row
    }

    @Test
    void footprintsKeepOneBlockClear() {
        CampLayout.Rect first = new CampLayout.Rect(0, 0, 5, 4);
        assertTrue(CampLayout.overlaps(first, new CampLayout.Rect(5, 0, 5, 4)), "touching shares a wall");
        assertFalse(CampLayout.overlaps(first, new CampLayout.Rect(6, 0, 5, 4)), "one block to walk through");
        assertFalse(CampLayout.overlaps(first, new CampLayout.Rect(0, 5, 5, 4)));
    }

    @Test
    void freeSlotSkipsOccupiedGroundAndGivesUpWhenFull() {
        List<CampLayout.Rect> occupied = new ArrayList<>();
        occupied.add(new CampLayout.Rect(CampLayout.slotX(ANCHOR_X, 0),
                CampLayout.slotZ(ANCHOR_Z), 5, 4));
        assertEquals(1, CampLayout.freeSlot(ANCHOR_X, ANCHOR_Z, 5, 4, occupied));

        List<CampLayout.Rect> full = new ArrayList<>();
        for (int slot = 0; slot < CampLayout.MAX_SLOTS; slot++) {
            full.add(new CampLayout.Rect(CampLayout.slotX(ANCHOR_X, slot),
                    CampLayout.slotZ(ANCHOR_Z), 5, 4));
        }
        assertEquals(-1, CampLayout.freeSlot(ANCHOR_X, ANCHOR_Z, 5, 4, full));
    }
}
