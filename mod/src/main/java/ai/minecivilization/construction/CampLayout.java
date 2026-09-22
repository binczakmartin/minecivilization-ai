package ai.minecivilization.construction;

import java.util.List;

/**
 * Pure geometry of the settlement camp: the row of house slots that grows east
 * from the camp anchor — the player's bed, or world spawn while no bed exists.
 *
 * <p>Deliberately free of Minecraft types so slot/overlap rules stay unit
 * testable; {@code ModEvents} supplies the world coordinates.</p>
 */
public final class CampLayout {

    /** X gap from the anchor to the first house origin — keeps the bed clear. */
    public static final int OFFSET_X = 6;
    /** Z offset from the anchor to the house row. */
    public static final int OFFSET_Z = -2;
    /** Distance between house origins along X, so the row never overlaps. */
    public static final int SPACING = 9;
    /** Clear blocks kept between two footprints. */
    public static final int GAP = 1;
    /** How far the row may grow before the search for a free slot gives up. */
    public static final int MAX_SLOTS = 12;

    private CampLayout() {
    }

    /** Ground footprint of a project: origin corner plus its blueprint size. */
    public static final class Rect {
        public final int x;
        public final int z;
        public final int sizeX;
        public final int sizeZ;

        public Rect(int x, int z, int sizeX, int sizeZ) {
            this.x = x;
            this.z = z;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
        }
    }

    /** X origin of camp slot {@code slot} for the given anchor. */
    public static int slotX(int anchorX, int slot) {
        return anchorX + OFFSET_X + slot * SPACING;
    }

    /** Z origin shared by every camp slot for the given anchor. */
    public static int slotZ(int anchorZ) {
        return anchorZ + OFFSET_Z;
    }

    /** True when (x, z) is exactly one of the camp slots for this anchor. */
    public static boolean isSlot(int anchorX, int anchorZ, int x, int z) {
        if (z != slotZ(anchorZ)) return false;
        int offset = x - anchorX - OFFSET_X;
        return offset >= 0 && offset % SPACING == 0 && offset / SPACING < MAX_SLOTS;
    }

    /** True when two footprints collide, {@link #GAP} blocks kept clear. */
    public static boolean overlaps(Rect a, Rect b) {
        return a.x < b.x + b.sizeX + GAP
                && b.x < a.x + a.sizeX + GAP
                && a.z < b.z + b.sizeZ + GAP
                && b.z < a.z + a.sizeZ + GAP;
    }

    /** True when a footprint collides with nothing that is already standing. */
    public static boolean free(Rect candidate, List<Rect> occupied) {
        for (Rect rect : occupied) {
            if (overlaps(candidate, rect)) return false;
        }
        return true;
    }

    /**
     * First camp slot east of the anchor whose footprint is still free,
     * or -1 when the row is full.
     */
    public static int freeSlot(int anchorX, int anchorZ, int sizeX, int sizeZ,
                               List<Rect> occupied) {
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            Rect candidate = new Rect(slotX(anchorX, slot), slotZ(anchorZ), sizeX, sizeZ);
            if (free(candidate, occupied)) return slot;
        }
        return -1;
    }
}
