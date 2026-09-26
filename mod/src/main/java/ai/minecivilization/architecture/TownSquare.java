package ai.minecivilization.architecture;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.construction.Blueprint;

/**
 * The colony's square: somewhere to sit, flowers in planters, a lamp.
 *
 * <p>A colony of bare cabins in a field reads as a camp. What turns it into a
 * village is the small, useless, cared-for things between the buildings, and
 * those were missing entirely. The square is deliberately small — a couple of
 * dozen blocks, all things the colony can make or pick — so it is actually
 * finished, and it is built by the ordinary builders like any other project.</p>
 *
 * <p>Layout, 9×9 at ground level (y 0 = standing level): a bench of two stairs
 * on each side facing the middle, a planter in each corner (a raised soil
 * block, a flower on it, trapdoors boxing its outer sides), a lamp post in the
 * centre.</p>
 */
public final class TownSquare {

    public static final String ID_PREFIX = "decor_square_";
    public static final int SIZE = 9;

    private TownSquare() {
    }

    public static boolean isSquare(String blueprintId) {
        return blueprintId != null && blueprintId.startsWith(ID_PREFIX);
    }

    public static Blueprint create(String blueprintId) {
        String species = blueprintId.substring(ID_PREFIX.length());
        if (!Palette.SPECIES.contains(species)) species = "oak";
        String stairs = "minecraft:" + species + "_stairs";
        String trapdoor = "minecraft:" + species + "_trapdoor";
        String log = "minecraft:" + species + "_log";

        List<Blueprint.BlockEntry> e = new ArrayList<>();
        // Planters first: their soil is what the trapdoors hang on.
        int[][] corners = {{1, 1}, {7, 1}, {1, 7}, {7, 7}};
        String[] flowers = {"minecraft:poppy", "minecraft:dandelion", "minecraft:dandelion", "minecraft:poppy"};
        for (int i = 0; i < corners.length; i++) {
            int x = corners[i][0];
            int z = corners[i][1];
            e.add(new Blueprint.BlockEntry(x, 0, z, "minecraft:dirt"));
            e.add(new Blueprint.BlockEntry(x, 1, z, flowers[i]));
            // Box the two outer sides; an open trapdoor clings to the soil.
            String westEast = x < 4 ? "west" : "east";
            String northSouth = z < 4 ? "north" : "south";
            int ox = x < 4 ? x - 1 : x + 1;
            int oz = z < 4 ? z - 1 : z + 1;
            e.add(new Blueprint.BlockEntry(ox, 0, z,
                    trapdoor + "[facing=" + westEast + ",half=bottom,open=true]"));
            e.add(new Blueprint.BlockEntry(x, 0, oz,
                    trapdoor + "[facing=" + northSouth + ",half=bottom,open=true]"));
        }
        // Benches: two stairs a side, backs to the outside, seats to the middle.
        bench(e, stairs, 3, 1, "north");
        bench(e, stairs, 5, 1, "north");
        bench(e, stairs, 3, 7, "south");
        bench(e, stairs, 5, 7, "south");
        bench(e, stairs, 1, 3, "west");
        bench(e, stairs, 1, 5, "west");
        bench(e, stairs, 7, 3, "east");
        bench(e, stairs, 7, 5, "east");
        // The lamp.
        e.add(new Blueprint.BlockEntry(4, 0, 4, log));
        e.add(new Blueprint.BlockEntry(4, 1, 4, "minecraft:torch"));
        return new Blueprint(blueprintId, "Town square", SIZE, 3, SIZE, e);
    }

    private static void bench(List<Blueprint.BlockEntry> e, String stairs, int x, int z, String back) {
        e.add(new Blueprint.BlockEntry(x, 0, z, stairs + "[facing=" + back + ",half=bottom,shape=straight]"));
    }
}
