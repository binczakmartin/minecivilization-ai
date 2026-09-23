package ai.minecivilization.forestry;

import java.util.List;
import java.util.Map;

/**
 * What grows back from what.
 *
 * <p>A lumberjack that fells without replanting is a colony that runs out of
 * wood. Every species a citizen meets on an expedition — spruce in the hills,
 * jungle down south — can be brought home and added to the managed forest, so
 * a plantation ends up mixed rather than a monoculture of whatever grew nearest
 * the spawn.</p>
 *
 * <p>Pure string mapping, so it needs no registry and is unit tested.</p>
 */
public final class TreeSpecies {

    /** Species whose sapling is not simply the log name with a different suffix. */
    private static final Map<String, String> IRREGULAR = Map.of(
            "minecraft:crimson_stem", "minecraft:crimson_fungus",
            "minecraft:warped_stem", "minecraft:warped_fungus",
            "minecraft:crimson_hyphae", "minecraft:crimson_fungus",
            "minecraft:warped_hyphae", "minecraft:warped_fungus",
            "minecraft:mangrove_log", "minecraft:mangrove_propagule",
            "minecraft:mangrove_wood", "minecraft:mangrove_propagule");

    /** The overworld species a colony can plant, in the order it tends to meet them. */
    public static final List<String> COMMON_SAPLINGS = List.of(
            "minecraft:oak_sapling",
            "minecraft:birch_sapling",
            "minecraft:spruce_sapling",
            "minecraft:jungle_sapling",
            "minecraft:acacia_sapling",
            "minecraft:dark_oak_sapling",
            "minecraft:cherry_sapling",
            "minecraft:mangrove_propagule");

    private TreeSpecies() {
    }

    /**
     * The sapling that regrows a given log, or {@code null} when the block is
     * not a tree trunk the colony knows how to replant.
     */
    public static String saplingFor(String logId) {
        if (logId == null || logId.isEmpty()) return null;
        String irregular = IRREGULAR.get(logId);
        if (irregular != null) return irregular;

        String name = logId.substring(logId.indexOf(':') + 1);
        // Stripped variants regrow the same tree as their unstripped form.
        if (name.startsWith("stripped_")) name = name.substring("stripped_".length());

        String species;
        if (name.endsWith("_log")) {
            species = name.substring(0, name.length() - "_log".length());
        } else if (name.endsWith("_wood")) {
            species = name.substring(0, name.length() - "_wood".length());
        } else {
            return null;
        }
        if (species.isEmpty()) return null;

        String sapling = "minecraft:" + species + "_sapling";
        return COMMON_SAPLINGS.contains(sapling) ? sapling : null;
    }

    /** True for a block id this mapping treats as a fellable trunk. */
    public static boolean isTrunk(String blockId) {
        return saplingFor(blockId) != null;
    }

    /** The log a sapling grows into — the inverse, for planning a plantation. */
    public static String logFor(String saplingId) {
        if (saplingId == null) return null;
        if ("minecraft:mangrove_propagule".equals(saplingId)) return "minecraft:mangrove_log";
        String name = saplingId.substring(saplingId.indexOf(':') + 1);
        if (!name.endsWith("_sapling")) return null;
        return "minecraft:" + name.substring(0, name.length() - "_sapling".length()) + "_log";
    }
}
