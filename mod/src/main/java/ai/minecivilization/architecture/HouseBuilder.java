package ai.minecivilization.architecture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ai.minecivilization.construction.Blueprint;

/**
 * Generates a house that looks designed rather than assembled.
 *
 * <p>The colony's first building was a plank box: four flat walls, a flat lid,
 * one material. Scale does not fix that — a bigger box is a bigger box. What
 * makes a building read as architecture is a small number of deliberate moves,
 * and this generator makes all of them:</p>
 *
 * <ul>
 *   <li><b>A base that meets the ground.</b> A stone foundation with a skirt
 *       that continues below the floor, so a house on a slope sits <em>in</em>
 *       the hill instead of hovering over it on one side.</li>
 *   <li><b>Corner posts.</b> Logs at the corners, running the full height:
 *       the single cheapest thing that turns a wall into a facade.</li>
 *   <li><b>A banded wall.</b> Planks with a stripped-log course under the eaves,
 *       so the wall has a horizontal line in it rather than being one flat
 *       field of texture.</li>
 *   <li><b>Windows on a rhythm.</b> Evenly spaced, never in a corner, always
 *       symmetric about the middle of each wall.</li>
 *   <li><b>A real roof.</b> Stairs in courses rising to a slab ridge, with the
 *       eaves overhanging the walls — an overhang is what casts the shadow line
 *       that makes a roof look like a roof.</li>
 *   <li><b>Closed gables.</b> The triangular ends filled in, because an open
 *       gable is the single most obvious mark of a build nobody finished.</li>
 * </ul>
 *
 * <p>Everything is emitted in build order — foundation, floor, walls, gables,
 * roof, details — because the builder places blocks one at a time and defers
 * anything without support. Pure geometry, no world, so the architecture is
 * unit tested.</p>
 */
public final class HouseBuilder {

    /** Wall height in blocks, floor to eaves. */
    public static final int WALL_HEIGHT = 5;
    /** How far the foundation continues below the floor to catch a slope. */
    public static final int SKIRT_DEPTH = 3;
    /** How far the eaves reach past the wall. */
    public static final int OVERHANG = 1;

    private HouseBuilder() {
    }

    /**
     * A house of the given footprint.
     *
     * @param width  east-west size, at least 5
     * @param depth  north-south size, at least 5
     * @param seed   varies the window rhythm between houses, deterministically
     */
    public static Blueprint house(String id, String name, int width, int depth,
                                  Palette palette, long seed) {
        int w = Math.max(5, width);
        int d = Math.max(5, depth);
        Palette p = palette == null ? Palette.forSpecies("oak") : palette;

        List<Blueprint.BlockEntry> out = new ArrayList<>();

        foundation(out, w, d, p);
        floor(out, w, d, p);
        walls(out, w, d, p, seed);
        gables(out, w, d, p);
        roof(out, w, d, p);
        details(out, w, d, p);

        // Declared size covers the eaves and plinth, not just the walls: the
        // camp layout spaces buildings by this, and a roof that overhangs into
        // the neighbour's plot is how a village turns into a pile-up.
        int height = SKIRT_DEPTH + WALL_HEIGHT + ridgeHeight(d) + 2;
        return new Blueprint(id, name, w + 2 * OVERHANG, height, d + 2 * OVERHANG,
                withRoofSupports(out, p));
    }

    /** Total blueprint height above the floor, for sizing. */
    static int ridgeHeight(int depth) {
        return (depth + 1) / 2;
    }

    /**
     * Re-order the generated plan so every roof piece has a real vertical
     * support chain.  A local canPlace check only sees the cell immediately
     * below; without this pass an overhang stair and a ridge slab are valid
     * blueprint entries that remain permanently deferred in the world.
     * Existing house geometry is not changed: the pass adds only missing
     * foundation cells and emits them before the roof.
     */
    private static List<Blueprint.BlockEntry> withRoofSupports(List<Blueprint.BlockEntry> original,
                                                                Palette p) {
        Set<String> all = new HashSet<>();
        for (Blueprint.BlockEntry entry : original) {
            all.add(key(entry.x, entry.y, entry.z));
        }
        Set<String> supports = new HashSet<>();
        for (Blueprint.BlockEntry entry : original) {
            if (!isRoofPiece(entry, p)) continue;
            for (int y = entry.y - 1; y >= 0; y--) {
                String below = key(entry.x, y, entry.z);
                if (all.contains(below) || supports.contains(below)) break;
                supports.add(below);
            }
        }

        List<Blueprint.BlockEntry> ordered = new ArrayList<>(original.size() + supports.size());
        for (Blueprint.BlockEntry entry : original) {
            if (!isRoofPiece(entry, p)) ordered.add(entry);
        }
        List<String> supportKeys = new ArrayList<>(supports);
        supportKeys.sort(Comparator.comparingInt(HouseBuilder::supportY)
                .thenComparingInt(HouseBuilder::supportX)
                .thenComparingInt(HouseBuilder::supportZ));
        for (String support : supportKeys) {
            String[] parts = support.split(",");
            ordered.add(new Blueprint.BlockEntry(Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), p.foundation()));
        }
        for (Blueprint.BlockEntry entry : original) {
            if (isRoofPiece(entry, p)) ordered.add(entry);
        }
        return ordered;
    }

    private static boolean isRoofPiece(Blueprint.BlockEntry entry, Palette p) {
        return entry.y > WALL_HEIGHT
                && (entry.itemId().equals(p.stairs()) || entry.itemId().equals(p.slab()));
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static int supportX(String key) {
        return Integer.parseInt(key.substring(0, key.indexOf(',')));
    }

    private static int supportY(String key) {
        int comma = key.indexOf(',');
        return Integer.parseInt(key.substring(comma + 1, key.lastIndexOf(',')));
    }

    private static int supportZ(String key) {
        return Integer.parseInt(key.substring(key.lastIndexOf(',') + 1));
    }

    // ------------------------------------------------------------------ base

    /**
     * Stone base, plus a skirt that runs down below the floor.
     *
     * <p>The skirt is what integrates the house with the terrain: on flat
     * ground it is buried and costs a little stone, and on a slope it becomes
     * the retaining wall that stops the downhill corner hanging in the air.</p>
     */
    private static void foundation(List<Blueprint.BlockEntry> out, int w, int d, Palette p) {
        for (int y = -SKIRT_DEPTH; y < 0; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    if (!isPerimeter(x, z, w, d)) continue;   // a skirt, not a cellar
                    out.add(new Blueprint.BlockEntry(x, y, z, p.foundation()));
                }
            }
        }
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                out.add(new Blueprint.BlockEntry(x, 0, z, p.foundation()));
            }
        }
    }

    private static void floor(List<Blueprint.BlockEntry> out, int w, int d, Palette p) {
        for (int x = 1; x < w - 1; x++) {
            for (int z = 1; z < d - 1; z++) {
                // The workstation replaces this one interior floor cell; its
                // foundation block below remains the support, so a table never
                // gets planned twice.
                if (x == 1 && z <= 2) continue;
                out.add(new Blueprint.BlockEntry(x, 1, z, p.wall()));
            }
        }
    }

    // ------------------------------------------------------------------ walls

    private static void walls(List<Blueprint.BlockEntry> out, int w, int d,
                              Palette p, long seed) {
        int top = WALL_HEIGHT;
        int doorX = w / 2;
        List<Integer> windowsX = windowPositions(w, seed);
        List<Integer> windowsZ = windowPositions(d, seed + 1);

        for (int y = 1; y <= top; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    if (!isPerimeter(x, z, w, d)) continue;

                    // Corner posts: the cheapest move that turns a wall into a facade.
                    if (isCorner(x, z, w, d)) {
                        out.add(new Blueprint.BlockEntry(x, y, z, p.post()));
                        continue;
                    }
                    // Doorway: a clear opening, left unblocked.
                    if (z == d - 1 && x == doorX && y <= 2) continue;

                    if (isWindow(x, z, w, d, y, windowsX, windowsZ)) {
                        out.add(new Blueprint.BlockEntry(x, y, z, p.window()));
                        continue;
                    }
                    // A banded course under the eaves gives the wall a line.
                    String material = y == top - 1 ? p.trim() : p.wall();
                    out.add(new Blueprint.BlockEntry(x, y, z, material));
                }
            }
        }
    }

    /**
     * Where windows go along a wall of this length: evenly spaced, symmetric,
     * never in or beside a corner. A wall too short for a rhythm gets one
     * window in the middle rather than a cramped pair.
     */
    static List<Integer> windowPositions(int span, long seed) {
        int lo = 2;                     // never in or beside a corner
        int hi = span - 3;
        if (hi < lo) return List.of();

        int spacing = 2 + (int) Math.floorMod(seed, 2);   // 2 or 3, per building
        java.util.Set<Integer> candidates = new java.util.TreeSet<>();

        if (span % 2 == 1) {
            int middle = span / 2;
            candidates.add(middle);
            for (int step = spacing + 1; middle - step >= lo; step += spacing + 1) {
                candidates.add(middle - step);
                candidates.add(middle + step);
            }
        } else {
            // An even wall has no middle block, so the rhythm starts as a pair
            // straddling the centre line. Treating span/2 as "the middle" is
            // what made even-width facades come out lopsided.
            int left = span / 2 - 1;
            int right = span / 2;
            candidates.add(left);
            candidates.add(right);
            for (int step = spacing + 1; left - step >= lo; step += spacing + 1) {
                candidates.add(left - step);
                candidates.add(right + step);
            }
        }

        // Keep only pairs whose mirror also fits, so the facade is symmetric by
        // construction rather than by arithmetic that has to be re-checked.
        java.util.Set<Integer> placed = new java.util.TreeSet<>();
        for (int position : candidates) {
            int mirror = span - 1 - position;
            if (position < lo || position > hi) continue;
            if (mirror < lo || mirror > hi) continue;
            placed.add(position);
            placed.add(mirror);
        }
        return new ArrayList<>(placed);
    }

    private static boolean isWindow(int x, int z, int w, int d, int y,
                                    List<Integer> windowsX, List<Integer> windowsZ) {
        if (y < 2 || y > 3) return false;            // sill at 2, head at 3
        boolean alongX = z == 0 || z == d - 1;
        boolean alongZ = x == 0 || x == w - 1;
        if (alongX && windowsX.contains(x)) return true;
        return alongZ && windowsZ.contains(z);
    }

    // ------------------------------------------------------------------ roof

    /**
     * The triangular wall at each end, closed.
     *
     * <p>An open gable is the clearest sign of a build nobody finished, and it
     * also means the roof has nothing to sit on at the ends.</p>
     */
    private static void gables(List<Blueprint.BlockEntry> out, int w, int d, Palette p) {
        int courses = ridgeHeight(d);
        for (int i = 1; i < courses; i++) {
            int y = WALL_HEIGHT + i;
            for (int x : new int[]{0, w - 1}) {
                for (int z = i; z <= d - 1 - i; z++) {
                    out.add(new Blueprint.BlockEntry(x, y, z, p.wall()));
                }
            }
        }
    }

    /**
     * A pitched roof of stairs rising to a slab ridge, overhanging the walls.
     *
     * <p>Stair facing follows the slope: each course faces outward, away from
     * the ridge, so the steps descend toward the eaves.</p>
     */
    private static void roof(List<Blueprint.BlockEntry> out, int w, int d, Palette p) {
        int courses = ridgeHeight(d);
        String stairsNorth = p.stairs() + "[facing=north]";
        String stairsSouth = p.stairs() + "[facing=south]";

        for (int i = 0; i < courses; i++) {
            int y = WALL_HEIGHT + 1 + i;
            int zLow = i - OVERHANG;
            int zHigh = d - 1 - i + OVERHANG;
            if (zLow > zHigh) break;

            for (int x = -OVERHANG; x < w + OVERHANG; x++) {
                out.add(new Blueprint.BlockEntry(x, y, zLow, stairsSouth));
                if (zHigh != zLow) {
                    out.add(new Blueprint.BlockEntry(x, y, zHigh, stairsNorth));
                }
            }
        }

        // Ridge: a run of slabs closing the peak along the length of the house.
        int ridgeY = WALL_HEIGHT + 1 + courses;
        int ridgeZ = (d - 1) / 2;
        for (int x = -OVERHANG; x < w + OVERHANG; x++) {
            out.add(new Blueprint.BlockEntry(x, ridgeY, ridgeZ, p.slab()));
            if (d % 2 == 0) {
                out.add(new Blueprint.BlockEntry(x, ridgeY, ridgeZ + 1, p.slab()));
            }
        }
    }

    // ------------------------------------------------------------------ detail

    /** The small things: a lit doorway and a stone course at the base. */
    private static void details(List<Blueprint.BlockEntry> out, int w, int d, Palette p) {
        int doorX = w / 2;
        // A real workstation belongs to the house plan, not to a random citizen
        // that happens to pass through it.  It sits on the y=0 foundation/floor
        // and leaves the doorway and the front approach clear.
        out.add(new Blueprint.BlockEntry(1, 1, 1, "minecraft:crafting_table"));
        // A floor-standing interior light is supported by the same foundation as
        // the table; unlike a wall torch it cannot be stranded on an unbuilt
        // facade.  The outside lights below still mark the entrance.
        out.add(new Blueprint.BlockEntry(1, 1, 2, p.light()));
        // Lights either side of the threshold, standing on the plinth outside
        // the wall. Putting them *in* the wall line simply deleted the wall.
        out.add(new Blueprint.BlockEntry(doorX - 1, 2, d, p.light()));
        out.add(new Blueprint.BlockEntry(doorX + 1, 2, d, p.light()));
        // A plinth: a slab course running around the outside of the base, where
        // the stone meets the timber. It reads as a deliberate step rather than
        // a seam, and it softens the line where the building meets the ground.
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                boolean outerRing = x == -1 || z == -1 || x == w || z == d;
                if (!outerRing) continue;
                if (z == d && x == doorX) continue;   // keep the threshold clear
                out.add(new Blueprint.BlockEntry(x, 1, z, p.foundationSlab()));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    static boolean isPerimeter(int x, int z, int w, int d) {
        return x == 0 || z == 0 || x == w - 1 || z == d - 1;
    }

    static boolean isCorner(int x, int z, int w, int d) {
        return (x == 0 || x == w - 1) && (z == 0 || z == d - 1);
    }
}
