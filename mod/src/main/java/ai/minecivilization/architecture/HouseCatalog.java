package ai.minecivilization.architecture;

import java.util.List;
import java.util.Map;

import ai.minecivilization.construction.Blueprint;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.storage.SettlementStock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The colony's house designs, generated on demand.
 *
 * <p>Two things stop a settlement looking like a housing estate: the buildings
 * differ from each other, and they are made of what the place actually
 * produces. So the palette follows the wood the colony is cutting — a spruce
 * valley builds a spruce village — and the footprint varies from plot to plot
 * while staying the same for any given plot, so an interrupted build resumes
 * instead of turning into a different house.</p>
 */
public final class HouseCatalog {

    /** Prefix marking a project as a dwelling, whatever its variant. */
    public static final String ID_PREFIX = "house_";

    /** Footprints in the rotation. Odd numbers centre a door and a window. */
    private static final int[][] FOOTPRINTS = {
            {7, 7}, {9, 7}, {9, 9}, {11, 7}, {7, 9}, {11, 9}};

    private HouseCatalog() {
    }

    /** True for any project built from one of these designs. */
    public static boolean isHouse(String blueprintId) {
        return blueprintId != null && blueprintId.startsWith(ID_PREFIX);
    }

    /**
     * The design for house number {@code index}, registering it the first time
     * it is asked for.
     *
     * <p>Deterministic in {@code index} and in the colony's wood, so asking
     * twice gives the same building.</p>
     */
    public static Blueprint forColony(ServerLevel level, int index) {
        String species = dominantSpecies(level);
        int[] size = FOOTPRINTS[Math.floorMod(index, FOOTPRINTS.length)];
        String id = ID_PREFIX + species + "_" + size[0] + "x" + size[1] + "_v" + (index % 4);

        Blueprint existing = ConstructionManager.blueprint(id);
        if (existing != null) return existing;

        Blueprint house = HouseBuilder.house(id,
                capitalise(species) + " House", size[0], size[1],
                Palette.forSpecies(species), index);
        ConstructionManager.registerBlueprint(house);
        return house;
    }

    /**
     * The wood the colony has most of, which is the wood it should build with.
     *
     * <p>Falls back to oak before the first delivery, when the warehouse has
     * nothing to go on.</p>
     */
    public static String dominantSpecies(ServerLevel level) {
        try {
            BlockPos centre = ai.minecivilization.colony.ZoneManager.get(level).townCenter(level);
            Map<String, Integer> stock = SettlementStock.totals(level, centre);
            String best = null;
            int bestCount = 0;
            for (Map.Entry<String, Integer> entry : stock.entrySet()) {
                String item = entry.getKey();
                if (!item.endsWith("_log") && !item.endsWith("_planks")) continue;
                if (entry.getValue() <= bestCount) continue;
                String species = speciesOf(item);
                if (species == null) continue;
                bestCount = entry.getValue();
                best = species;
            }
            return best == null ? "oak" : best;
        } catch (RuntimeException ex) {
            return "oak";   // never let a cosmetic choice break the build loop
        }
    }

    private static String speciesOf(String itemId) {
        String name = itemId.substring(itemId.indexOf(':') + 1);
        if (name.startsWith("stripped_")) name = name.substring("stripped_".length());
        int cut = name.lastIndexOf("_log");
        if (cut < 0) cut = name.lastIndexOf("_planks");
        if (cut <= 0) return null;
        String species = name.substring(0, cut);
        return Palette.SPECIES.contains(species) ? species : null;
    }

    private static String capitalise(String word) {
        return word.isEmpty() ? word
                : Character.toUpperCase(word.charAt(0)) + word.substring(1).replace('_', ' ');
    }

    /** Every footprint the catalogue can produce, for planning and tests. */
    public static List<int[]> footprints() {
        return List.of(FOOTPRINTS);
    }
}
