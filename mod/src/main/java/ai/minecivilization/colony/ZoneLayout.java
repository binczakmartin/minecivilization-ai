package ai.minecivilization.colony;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The colony's town plan: a grid of plots spiralling out from the town centre,
 * with streets between them.
 *
 * <p>Land is allotted rather than sprawled. Plot (0,0) sits on the town centre;
 * rings of plots surround it, and each {@link ZoneType} declares the closest
 * ring it may occupy — so warehouses and houses stay near the middle while
 * quarries and plantations go to the edge. Allocation walks the spiral outward
 * and takes the first free plot, which means the colony grows in a ring, not a
 * ribbon, and two runs of the planner always agree on where things go.</p>
 *
 * <p>The gap between {@link #PLOT_SIZE} and {@link #PLOT_PITCH} is the street
 * grid: four blocks of open ground between every pair of plots, which is what
 * makes the result read as a town rather than a warehouse floor.</p>
 *
 * <p>Pure integer geometry — no world, no registry — so the whole plan is unit
 * tested.</p>
 */
public final class ZoneLayout {

    /** Side of a buildable plot, in blocks. */
    public static final int PLOT_SIZE = 16;
    /** Centre-to-centre distance between plots: the difference is the street. */
    public static final int PLOT_PITCH = 20;
    /** How far out allocation will look before declaring the colony full. */
    public static final int MAX_RING = 6;

    private ZoneLayout() {
    }

    /** A plot's address in the town grid, in plots from the centre. */
    public record Plot(int px, int pz) {
        /** Chebyshev distance from the town centre, in plots. */
        public int ring() {
            return Math.max(Math.abs(px), Math.abs(pz));
        }
    }

    /** A plot's block footprint, inclusive on both ends. */
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public int centerX() {
            return (minX + maxX) / 2;
        }

        public int centerZ() {
            return (minZ + maxZ) / 2;
        }

        public int sizeX() {
            return maxX - minX + 1;
        }

        public int sizeZ() {
            return maxZ - minZ + 1;
        }
    }

    /**
     * Plots in allocation order out to {@code maxRing}: the centre, then each
     * ring, and within a ring the plots nearest the centre first. Deterministic
     * down to the tie-break, so re-planning never reshuffles a town.
     */
    public static List<Plot> spiral(int maxRing) {
        List<Plot> out = new ArrayList<>();
        for (int ring = 0; ring <= Math.max(0, maxRing); ring++) {
            addRing(out, ring);
        }
        return out;
    }

    private static void addRing(List<Plot> out, int ring) {
        if (ring == 0) {
            out.add(new Plot(0, 0));
            return;
        }
        List<int[]> cells = new ArrayList<>();
        for (int px = -ring; px <= ring; px++) {
            for (int pz = -ring; pz <= ring; pz++) {
                if (Math.max(Math.abs(px), Math.abs(pz)) != ring) continue;
                cells.add(new int[]{px * px + pz * pz, px, pz});
            }
        }
        // straights before diagonals, then a stable ordering
        cells.sort(Comparator.comparingInt((int[] c) -> c[0])
                .thenComparingInt(c -> c[1])
                .thenComparingInt(c -> c[2]));
        for (int[] c : cells) {
            out.add(new Plot(c[1], c[2]));
        }
    }

    /**
     * The next plot to give to a zone of this type, or {@code null} when the
     * town has no room left inside {@link #MAX_RING}.
     *
     * @param taken plots already allotted to something
     */
    public static Plot allocate(ZoneType type, Set<Plot> taken) {
        return allocate(type, taken, MAX_RING);
    }

    public static Plot allocate(ZoneType type, Set<Plot> taken, int maxRing) {
        if (type == null) return null;
        for (Plot plot : spiral(maxRing)) {
            if (plot.ring() < type.minRing) continue;
            if (taken != null && taken.contains(plot)) continue;
            return plot;
        }
        return null;
    }

    /** Where a plot sits in the world, given the town centre. */
    public static Bounds bounds(int centerX, int centerZ, Plot plot) {
        int originX = centerX + plot.px() * PLOT_PITCH - PLOT_SIZE / 2;
        int originZ = centerZ + plot.pz() * PLOT_PITCH - PLOT_SIZE / 2;
        return new Bounds(originX, originZ, originX + PLOT_SIZE - 1, originZ + PLOT_SIZE - 1);
    }

    /**
     * Which plot a world position falls in — including the streets, which
     * resolve to the plot they border. Used to answer "am I still in town?".
     */
    public static Plot plotAt(int centerX, int centerZ, int x, int z) {
        return new Plot(Math.floorDiv(x - centerX + PLOT_PITCH / 2, PLOT_PITCH),
                Math.floorDiv(z - centerZ + PLOT_PITCH / 2, PLOT_PITCH));
    }

    /** Radius, in blocks, of a town occupying every plot out to {@code ring}. */
    public static int townRadius(int ring) {
        return Math.max(0, ring) * PLOT_PITCH + PLOT_SIZE / 2;
    }

    /** True when no two of these footprints overlap — the invariant of a town plan. */
    public static boolean noOverlaps(Collection<Bounds> plots) {
        List<Bounds> list = new ArrayList<>(plots);
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                if (overlap(list.get(i), list.get(j))) return false;
            }
        }
        return true;
    }

    private static boolean overlap(Bounds a, Bounds b) {
        return a.minX() <= b.maxX() && b.minX() <= a.maxX()
                && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
    }
}
