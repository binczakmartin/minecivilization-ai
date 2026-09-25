package ai.minecivilization.construction;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.architecture.Palette;

/**
 * The building that turns a camp into a colony.
 *
 * <p>A settlement whose citizens gather wood and stone for an hour and have
 * nothing to show for it is not a civilization, and that was the honest state
 * of things: resources accumulated, chests filled, and the place still looked
 * like open countryside with people milling about. The town hall is the fix —
 * the first real structure, planned early and deliberately made of materials a
 * young colony already has.</p>
 *
 * <p>It is a workplace, not a monument. Under one roof it puts everything the
 * colony keeps failing to find: a bank of chests, furnaces, crafting tables,
 * light, and a notice board out front. Once it stands, "go to the workbench"
 * and "put this in storage" stop being searches.</p>
 *
 * <p>Deliberately buildable from cobblestone, dirt-cheap wood and a handful of
 * crafted stations, because a town hall that needs a quarry first is a town
 * hall that never gets built.</p>
 */
public final class TownHall {

    public static final String ID_PREFIX = "town_hall_";

    /** Outer footprint. Odd numbers so the doorway and the aisle are centred. */
    public static final int WIDTH = 11;
    public static final int DEPTH = 9;
    /** Floor to eaves. */
    public static final int WALL_HEIGHT = 4;
    /** How far the eaves reach past the walls. */
    private static final int OVERHANG = 1;

    private TownHall() {
    }

    public static boolean isTownHall(String blueprintId) {
        return blueprintId != null && blueprintId.startsWith(ID_PREFIX);
    }

    /** The town hall in the colony's own wood, registered on first use. */
    public static Blueprint create(String species) {
        String wood = species == null || species.isBlank() ? "oak" : species;
        String id = ID_PREFIX + wood;
        Blueprint existing = ConstructionManager.blueprint(id);
        if (existing != null) return existing;

        Palette palette = Palette.forSpecies(wood);
        List<Blueprint.BlockEntry> entries = new ArrayList<>();

        int roofY = WALL_HEIGHT + 1;
        int doorX = WIDTH / 2;

        // 1) Foundation. Laid first because everything else needs to stand on it.
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                entries.add(new Blueprint.BlockEntry(x, 0, z, palette.foundation()));
            }
        }

        // 2) Walls, with corner posts, a centred doorway and windows that let
        //    the inside be seen — a sealed box reads as a bunker.
        for (int y = 1; y <= WALL_HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < DEPTH; z++) {
                    if (!isPerimeter(x, z)) continue;
                    if (isDoorway(x, y, z, doorX)) continue;

                    boolean corner = (x == 0 || x == WIDTH - 1) && (z == 0 || z == DEPTH - 1);
                    if (corner) {
                        entries.add(new Blueprint.BlockEntry(x, y, z, palette.post()));
                        continue;
                    }
                    if (isWindow(x, y, z, doorX)) {
                        entries.add(new Blueprint.BlockEntry(x, y, z, palette.window()));
                        continue;
                    }
                    // A stone skirt under a timber upper storey: the cheapest
                    // way to make a building look designed.
                    entries.add(new Blueprint.BlockEntry(x, y, z,
                            y == 1 ? palette.foundation() : palette.wall()));
                }
            }
        }

        // 3) A pitched roof of stairs rising to a slab ridge, overhanging the
        //    walls. A flat deck is quicker and reads as a crate; the colony's
        //    centrepiece should look like a building from across the valley.
        pitchedRoof(entries, palette, roofY);

        // 4) The fittings — the whole reason the building exists. Along the back
        //    wall so the middle stays clear to walk through.
        addFittings(entries, palette, doorX);

        Blueprint blueprint = new Blueprint(id, "Town Hall", WIDTH, roofY + 1, DEPTH, entries);
        ConstructionManager.registerBlueprint(blueprint);
        return blueprint;
    }

    /**
     * Storage, heat, workbenches, light and a notice board.
     *
     * <p>Laid out so nothing blocks anything: containers against the back wall
     * with a clear aisle, stations down one side, torches high enough not to be
     * walked into, and the sign outside where it can be read without entering.</p>
     */
    private static void addFittings(List<Blueprint.BlockEntry> entries, Palette palette,
                                    int doorX) {
        int back = 1;                  // the row just inside the north wall
        int side = DEPTH - 2;          // the row just inside the south wall

        // A bank of chests: the colony's main warehouse, adjacent pairs forming
        // double chests.
        for (int x = 2; x <= WIDTH - 3; x++) {
            entries.add(new Blueprint.BlockEntry(x, 1, back, "minecraft:chest"));
        }

        // Heat and work, kept apart so two citizens can use them at once.
        entries.add(new Blueprint.BlockEntry(2, 1, side, "minecraft:furnace"));
        entries.add(new Blueprint.BlockEntry(3, 1, side, "minecraft:furnace"));
        entries.add(new Blueprint.BlockEntry(WIDTH - 3, 1, side, "minecraft:crafting_table"));
        entries.add(new Blueprint.BlockEntry(WIDTH - 4, 1, side, "minecraft:crafting_table"));

        // Light: enough that nothing spawns indoors, which is the practical
        // difference between a hall and a mob farm.
        for (int x : new int[]{1, WIDTH - 2}) {
            for (int z : new int[]{2, DEPTH - 3}) {
                entries.add(new Blueprint.BlockEntry(x, WALL_HEIGHT, z, palette.light()));
            }
        }
        entries.add(new Blueprint.BlockEntry(doorX, WALL_HEIGHT, DEPTH / 2, palette.light()));

        // The notice board, outside the door where a passer-by reads it.
        entries.add(new Blueprint.BlockEntry(doorX - 1, 1, DEPTH,
                "minecraft:" + species(palette) + "_fence"));
        entries.add(new Blueprint.BlockEntry(doorX - 1, 2, DEPTH,
                "minecraft:" + species(palette) + "_sign"));
        // And a lamp beside it, so the town hall is findable after dark.
        entries.add(new Blueprint.BlockEntry(doorX + 1, 1, DEPTH, palette.post()));
        entries.add(new Blueprint.BlockEntry(doorX + 1, 2, DEPTH, palette.light()));
    }

    /**
     * Courses of stairs climbing from both eaves to a slab ridge.
     *
     * <p>Each course faces outward, away from the ridge, so the steps descend
     * toward the eaves the way a real roof does. The overhang is what casts a
     * shadow on the wall below and stops the building reading as a box.</p>
     */
    private static void pitchedRoof(List<Blueprint.BlockEntry> entries, Palette palette,
                                    int eavesY) {
        String facingNorth = palette.stairs() + "[facing=north]";
        String facingSouth = palette.stairs() + "[facing=south]";
        int courses = (DEPTH + 1) / 2;

        for (int course = 0; course < courses; course++) {
            int y = eavesY + course;
            int near = course - OVERHANG;
            int far = DEPTH - 1 - course + OVERHANG;
            if (near > far) break;

            for (int x = -OVERHANG; x < WIDTH + OVERHANG; x++) {
                entries.add(new Blueprint.BlockEntry(x, y, near, facingSouth));
                if (far != near) {
                    entries.add(new Blueprint.BlockEntry(x, y, far, facingNorth));
                }
            }
        }

        // The ridge closes the peak along the length of the hall.
        int ridgeY = eavesY + courses;
        int ridgeZ = (DEPTH - 1) / 2;
        for (int x = -OVERHANG; x < WIDTH + OVERHANG; x++) {
            entries.add(new Blueprint.BlockEntry(x, ridgeY, ridgeZ, palette.slab()));
            if (DEPTH % 2 == 0) {
                entries.add(new Blueprint.BlockEntry(x, ridgeY, ridgeZ + 1, palette.slab()));
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    private static boolean isPerimeter(int x, int z) {
        return x == 0 || x == WIDTH - 1 || z == 0 || z == DEPTH - 1;
    }

    /** A two-wide, two-high gap in the middle of the south wall. */
    private static boolean isDoorway(int x, int y, int z, int doorX) {
        return z == DEPTH - 1 && y <= 2 && (x == doorX || x == doorX - 1);
    }

    /** Windows at eye height along both long walls, clear of the doorway. */
    private static boolean isWindow(int x, int y, int z, int doorX) {
        if (y != 3) return false;
        boolean longWall = z == 0 || z == DEPTH - 1;
        if (longWall) return x > 1 && x < WIDTH - 2 && x != doorX && x != doorX - 1;
        return z > 1 && z < DEPTH - 2;
    }

    /** The species a palette was built from, recovered from its wall block. */
    private static String species(Palette palette) {
        String wall = palette.wall();                       // minecraft:spruce_planks
        String name = wall.substring(wall.indexOf(':') + 1);
        int suffix = name.lastIndexOf("_planks");
        return suffix > 0 ? name.substring(0, suffix) : "oak";
    }

    /** Where the notice sign ends up, relative to the project origin. */
    public static int[] noticeSignOffset() {
        return new int[]{WIDTH / 2 - 1, 2, DEPTH};
    }
}
