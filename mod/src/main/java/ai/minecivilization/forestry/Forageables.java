package ai.minecivilization.forestry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plants worth breaking, and what a colony gets out of them.
 *
 * <p>Wheat does not appear on its own. A settlement that cannot find a crop
 * has to <em>start</em> one, and the first seed comes from thrashing the long
 * grass — which is the step that was missing when farmers spent their whole
 * lives reporting "no reachable wheat within 24 blocks" and nothing ever
 * changed.</p>
 *
 * <p>Foraging is also how the colony's palette of materials widens: every
 * expedition that walks through a new biome can bring back flowers for dye,
 * cane for paper, berries and saplings it did not have before. Producing
 * things at home starts with having the ingredient at all.</p>
 *
 * <p>Pure string data, unit tested, no registry.</p>
 */
public final class Forageables {

    /** Grass and ferns: the only source of wheat seeds before a farm exists. */
    public static final List<String> SEED_SOURCES = List.of(
            "minecraft:short_grass",
            "minecraft:tall_grass",
            "minecraft:fern",
            "minecraft:large_fern");

    /** Plants that yield a useful item and cost nothing to take. */
    private static final Set<String> OTHER_FORAGE = Set.of(
            "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
            "minecraft:allium", "minecraft:azure_bluet", "minecraft:oxeye_daisy",
            "minecraft:cornflower", "minecraft:lily_of_the_valley", "minecraft:torchflower",
            "minecraft:red_tulip", "minecraft:orange_tulip", "minecraft:white_tulip",
            "minecraft:pink_tulip", "minecraft:sunflower", "minecraft:lilac",
            "minecraft:rose_bush", "minecraft:peony", "minecraft:pink_petals",
            "minecraft:sugar_cane", "minecraft:bamboo", "minecraft:cactus",
            "minecraft:brown_mushroom", "minecraft:red_mushroom",
            "minecraft:sweet_berry_bush", "minecraft:glow_berries",
            "minecraft:melon", "minecraft:pumpkin", "minecraft:moss_block",
            "minecraft:sea_pickle", "minecraft:kelp", "minecraft:dead_bush");

    /** Pseudo-resource: "anything edible", for a forage that replaces a failed hunt. */
    public static final String FOOD = "food";

    /** Plants that are, or drop, something a citizen can eat. */
    public static final List<String> FOOD_SOURCES = List.of(
            "minecraft:sweet_berry_bush",
            "minecraft:melon",
            "minecraft:cave_vines",
            "minecraft:cave_vines_plant");

    private Forageables() {
    }

    /** Every plant a citizen may break while foraging. */
    public static Set<String> all() {
        Set<String> out = new LinkedHashSet<>(SEED_SOURCES);
        out.addAll(OTHER_FORAGE);
        return out;
    }

    public static boolean isForageable(String blockId) {
        return blockId != null && (SEED_SOURCES.contains(blockId) || OTHER_FORAGE.contains(blockId)
                || FOOD_SOURCES.contains(blockId));
    }

    /** True for the plants that can drop wheat seeds. */
    public static boolean yieldsSeeds(String blockId) {
        return SEED_SOURCES.contains(blockId);
    }

    /**
     * Which plants to break when hunting for a particular item.
     *
     * <p>Returns the seed sources for seeds, and everything otherwise: a
     * citizen told simply to "go foraging" takes whatever the countryside
     * offers, which is how the colony's material palette grows.</p>
     */
    public static List<String> sourcesFor(String itemId) {
        if (itemId == null) return List.copyOf(all());
        if (FOOD.equals(itemId)) return FOOD_SOURCES;
        if (itemId.endsWith("_seeds") || "minecraft:wheat".equals(itemId)) {
            return SEED_SOURCES;
        }
        return List.copyOf(all());
    }
}
