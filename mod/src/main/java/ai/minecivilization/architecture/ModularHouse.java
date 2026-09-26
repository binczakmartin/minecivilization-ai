package ai.minecivilization.architecture;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.construction.Blueprint;

/**
 * A home that grows the way a settler's house does: a cabin first, then a room
 * on the side, then a floor on top.
 *
 * <p>The colony used to plan a row of large houses — deep foundations, pitched
 * roofs, a hundred and more blocks each — all at once. Builders spread across
 * all of them and none ever passed a few per cent. A home is now three small
 * projects on the same plot, each one finished and lived in before the next
 * begins: a village that is visibly growing instead of eleven empty plots.</p>
 *
 * <p>All stages share the plot's origin and are written in the colony's wood
 * (the builders substitute species anyway, see
 * {@link ai.minecivilization.construction.WoodSwap}).</p>
 */
public final class ModularHouse {

    public static final String ID_PREFIX = "home_";
    /** Stages: 1 cabin, 2 side room, 3 upper storey. */
    public static final int STAGES = 3;
    /** Plot size the whole house will eventually take, for spacing plots. */
    public static final int PLOT_WIDTH = 9;
    public static final int PLOT_DEPTH = 5;

    private static final int CABIN_W = 5;
    private static final int ROOM_W = 4;
    private static final int DEPTH = 5;
    private static final int WALL = 3;

    private ModularHouse() {
    }

    public static boolean isHome(String blueprintId) {
        return blueprintId != null && blueprintId.startsWith(ID_PREFIX);
    }

    public static String id(String species, int stage) {
        return ID_PREFIX + species + "_s" + stage;
    }

    /** The stage number in a home blueprint id, or 0. */
    public static int stageOf(String blueprintId) {
        if (!isHome(blueprintId)) return 0;
        int at = blueprintId.lastIndexOf("_s");
        try {
            return Integer.parseInt(blueprintId.substring(at + 2));
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    public static String speciesOf(String blueprintId) {
        if (!isHome(blueprintId)) return "oak";
        return blueprintId.substring(ID_PREFIX.length(), blueprintId.lastIndexOf("_s"));
    }

    public static String stageName(int stage) {
        return switch (stage) {
            case 1 -> "cabin";
            case 2 -> "extension";
            default -> "upper floor";
        };
    }

    /** Rebuild a home blueprint from its id (after a restart, or on first use). */
    public static Blueprint create(String blueprintId) {
        int stage = stageOf(blueprintId);
        if (stage < 1 || stage > STAGES) return null;
        String species = speciesOf(blueprintId);
        if (!Palette.SPECIES.contains(species)) species = "oak";
        String planks = "minecraft:" + species + "_planks";
        String log = "minecraft:" + species + "_log";
        String slab = "minecraft:" + species + "_slab[type=bottom]";

        List<Blueprint.BlockEntry> e = new ArrayList<>();
        int sizeX;
        int sizeY;
        switch (stage) {
            case 1 -> {
                box(e, 0, CABIN_W - 1, planks, log);
                // Doorway in the middle of the south wall, a window each side.
                cut(e, 2, 1, DEPTH - 1);
                cut(e, 2, 2, DEPTH - 1);
                cut(e, 0, 2, 2);
                cut(e, CABIN_W - 1, 2, 2);
                roof(e, 0, CABIN_W - 1, WALL + 1, planks, slab);
                e.add(new Blueprint.BlockEntry(3, 1, 1, "minecraft:torch"));
                sizeX = CABIN_W + 1;
                sizeY = WALL + 3;
            }
            case 2 -> {
                // A room against the cabin's east wall, which it shares.
                int x0 = CABIN_W;
                int x1 = CABIN_W + ROOM_W - 1;
                floor(e, x0, x1, planks);
                for (int y = 1; y <= WALL; y++) {
                    for (int x = x0; x <= x1; x++) {
                        e.add(wall(x, y, 0, x == x1, planks, log));
                        e.add(wall(x, y, DEPTH - 1, x == x1, planks, log));
                    }
                    for (int z = 1; z < DEPTH - 1; z++) {
                        e.add(new Blueprint.BlockEntry(x1, y, z, planks));
                    }
                }
                cut(e, x0 + 1, 1, DEPTH - 1);
                cut(e, x0 + 1, 2, DEPTH - 1);
                cut(e, x1, 2, 2);
                roof(e, x0, x1, WALL + 1, planks, slab);
                e.add(new Blueprint.BlockEntry(x0 + 2, 1, 1, "minecraft:torch"));
                sizeX = x1 + 2;
                sizeY = WALL + 3;
            }
            default -> {
                // A storey on the roof of the whole house, and a new roof.
                int x1 = CABIN_W + ROOM_W - 1;
                int base = WALL + 1;             // the old roof is the new floor
                for (int y = base + 1; y <= base + WALL; y++) {
                    for (int x = 0; x <= x1; x++) {
                        for (int z = 0; z < DEPTH; z++) {
                            boolean edge = x == 0 || x == x1 || z == 0 || z == DEPTH - 1;
                            if (!edge) continue;
                            boolean corner = (x == 0 || x == x1) && (z == 0 || z == DEPTH - 1);
                            boolean window = y == base + 2 && !corner && (x % 3 == 1 || z == 2);
                            if (window) continue;
                            e.add(new Blueprint.BlockEntry(x, y, z, corner ? log : planks));
                        }
                    }
                }
                roof(e, 0, x1, base + WALL + 1, planks, slab);
                sizeX = x1 + 2;
                sizeY = base + WALL + 3;
            }
        }
        String name = capitalise(species) + " home (" + stageName(stage) + ")";
        return new Blueprint(blueprintId, name, sizeX, sizeY, DEPTH + 1, e);
    }

    /** Floor, and log-cornered walls three high, for a room x0..x1 × the full depth. */
    private static void box(List<Blueprint.BlockEntry> e, int x0, int x1,
                            String planks, String log) {
        floor(e, x0, x1, planks);
        for (int y = 1; y <= WALL; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = 0; z < DEPTH; z++) {
                    boolean edge = x == x0 || x == x1 || z == 0 || z == DEPTH - 1;
                    if (!edge) continue;
                    boolean corner = (x == x0 || x == x1) && (z == 0 || z == DEPTH - 1);
                    e.add(new Blueprint.BlockEntry(x, y, z, corner ? log : planks));
                }
            }
        }
    }

    private static void floor(List<Blueprint.BlockEntry> e, int x0, int x1, String planks) {
        for (int x = x0; x <= x1; x++) {
            for (int z = 0; z < DEPTH; z++) {
                e.add(new Blueprint.BlockEntry(x, 0, z, planks));
            }
        }
    }

    private static Blueprint.BlockEntry wall(int x, int y, int z, boolean corner,
                                             String planks, String log) {
        return new Blueprint.BlockEntry(x, y, z, corner ? log : planks);
    }

    /** A flat plank roof with a slab rim a step higher, so it reads as a roof. */
    private static void roof(List<Blueprint.BlockEntry> e, int x0, int x1, int y,
                             String planks, String slab) {
        for (int x = x0; x <= x1; x++) {
            for (int z = 0; z < DEPTH; z++) {
                e.add(new Blueprint.BlockEntry(x, y, z, planks));
            }
        }
        for (int x = x0; x <= x1; x++) {
            e.add(new Blueprint.BlockEntry(x, y + 1, 0, slab));
            e.add(new Blueprint.BlockEntry(x, y + 1, DEPTH - 1, slab));
        }
    }

    /** Leave this cell open (a doorway or a window). */
    private static void cut(List<Blueprint.BlockEntry> e, int x, int y, int z) {
        e.removeIf(entry -> entry.x == x && entry.y == y && entry.z == z);
    }

    /** Where a bed goes in a finished stage: {footX, y, footZ, headX, headZ, facing}. */
    public static int[] bedSpot(int stage) {
        return switch (stage) {
            case 1 -> new int[]{1, 1, 3, 1, 2};
            case 2 -> new int[]{CABIN_W + 2, 1, 3, CABIN_W + 2, 2};
            default -> null;
        };
    }

    private static String capitalise(String word) {
        return word.isEmpty() ? word
                : Character.toUpperCase(word.charAt(0)) + word.substring(1).replace('_', ' ');
    }
}
