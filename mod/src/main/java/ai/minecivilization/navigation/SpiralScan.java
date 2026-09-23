package ai.minecivilization.navigation;

/**
 * Walks the cells around a point from the nearest outward.
 *
 * <p>A raster scan of a cube starts in a corner — for a 48-block search radius
 * that is 48 blocks down and 48 blocks sideways, the least useful place to
 * look. A tree five blocks away was found only after roughly half a million
 * wasted cells, which is why citizens appeared to stand still doing nothing:
 * they were searching, in the worst possible order.</p>
 *
 * <p>This enumerates the same cells grouped into cube shells, nearest shell
 * first, so anything close is found almost immediately and the expensive
 * distance is only paid when the neighbourhood really is empty.</p>
 *
 * <p>Pure integer geometry, unit tested — the cursor keeps its place across
 * ticks so a search can be sliced without losing progress.</p>
 */
public final class SpiralScan {

    private SpiralScan() {
    }

    /** Total cells inside a cube of this radius — identical to a raster scan. */
    public static long cellCount(int radius) {
        long side = 2L * radius + 1L;
        return side * side * side;
    }

    /**
     * A resumable position in the walk. Cheap to keep in a skill's scratch
     * space between ticks.
     */
    public static final class Cursor {
        private int ring;
        private int phase;   // 0 = the shell's side walls, 1 = its top and bottom caps
        private int index;   // position within the current phase
        private boolean done;

        /**
         * Write the next offset into {@code out} as {dx, dy, dz}.
         *
         * @return false once every cell inside {@code radius} has been visited
         */
        public boolean next(int radius, int[] out) {
            while (!done) {
                if (ring > radius) {
                    done = true;
                    return false;
                }
                int available = phase == 0 ? sideWallCells(ring) : capCells(ring);
                if (index >= available) {
                    index = 0;
                    if (phase == 0) {
                        phase = 1;
                    } else {
                        phase = 0;
                        ring++;
                    }
                    continue;
                }
                decode(ring, phase, index++, out);
                return true;
            }
            return false;
        }

        /** Cells visited so far — for progress reporting. */
        public long visited(int radius) {
            long total = 0;
            for (int r = 0; r < ring; r++) {
                total += sideWallCells(r) + capCells(r);
            }
            if (phase == 1) total += sideWallCells(ring);
            return total + index;
        }

        public boolean exhausted() {
            return done;
        }
    }

    // ------------------------------------------------------------------ shells

    /** Cells on the four vertical sides of a shell: the 2D ring at every height. */
    static int sideWallCells(int ring) {
        return ringCells(ring) * (2 * ring + 1);
    }

    /** Cells on the shell's top and bottom faces, excluding the sides already counted. */
    static int capCells(int ring) {
        if (ring == 0) return 0;
        int inner = 2 * ring - 1;
        return 2 * inner * inner;
    }

    /** Cells on a 2D ring at Chebyshev radius {@code r}. */
    static int ringCells(int r) {
        return r == 0 ? 1 : 8 * r;
    }

    private static void decode(int ring, int phase, int index, int[] out) {
        if (phase == 0) {
            int perRing = ringCells(ring);
            int dy = index / perRing - ring;
            ring2D(ring, index % perRing, out);
            out[1] = dy;
            return;
        }
        // Caps: the flat discs above and below, minus the rim already walked.
        int inner = 2 * ring - 1;
        int half = inner * inner;
        int side = index < half ? -ring : ring;
        int local = index < half ? index : index - half;
        out[0] = local % inner - (ring - 1);
        out[1] = side;
        out[2] = local / inner - (ring - 1);
    }

    /** The {@code i}-th cell of a 2D ring at radius {@code r}, as {dx, _, dz}. */
    static void ring2D(int r, int i, int[] out) {
        if (r == 0) {
            out[0] = 0;
            out[2] = 0;
            return;
        }
        int edge = 2 * r + 1;
        if (i < edge) {                      // near side
            out[0] = i - r;
            out[2] = -r;
        } else if (i < 2 * edge) {           // far side
            out[0] = (i - edge) - r;
            out[2] = r;
        } else {
            int rest = i - 2 * edge;
            int column = 2 * r - 1;
            if (rest < column) {             // left side, corners excluded
                out[0] = -r;
                out[2] = rest - r + 1;
            } else {                         // right side
                out[0] = r;
                out[2] = (rest - column) - r + 1;
            }
        }
    }
}
