package ai.minecivilization.architecture;

/**
 * The materials one building is made of, by role rather than by name.
 *
 * <p>What makes a building read as designed rather than assembled is that its
 * materials play consistent parts: one thing is the wall, another frames it,
 * another caps it. A box made of a single block never looks built no matter how
 * large it is.</p>
 *
 * <p>Palettes are derived from the wood the colony actually cuts, so a spruce
 * settlement is a spruce settlement all the way through and a birch one looks
 * different without anyone choosing it.</p>
 */
public record Palette(
        String wall,
        String post,
        String trim,
        String stairs,
        String slab,
        String foundation,
        String foundationSlab,
        String window,
        String light) {

    /** Species the colony is likely to meet, in the order it usually meets them. */
    public static final java.util.List<String> SPECIES = java.util.List.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove");

    /**
     * A palette built around one wood species.
     *
     * @param species bare species name, e.g. {@code spruce}
     */
    public static Palette forSpecies(String species) {
        String name = species == null || species.isBlank() ? "oak" : species;
        if (!SPECIES.contains(name)) name = "oak";
        return new Palette(
                "minecraft:" + name + "_planks",
                "minecraft:" + name + "_log",
                "minecraft:stripped_" + name + "_log",
                "minecraft:" + name + "_stairs",
                "minecraft:" + name + "_slab",
                "minecraft:cobblestone",
                "minecraft:cobblestone_slab",
                "minecraft:glass_pane",
                "minecraft:torch");
    }

    /** The palette implied by a log the colony is carrying. */
    public static Palette fromLog(String logId) {
        if (logId == null) return forSpecies("oak");
        String name = logId.substring(logId.indexOf(':') + 1);
        if (name.startsWith("stripped_")) name = name.substring("stripped_".length());
        int suffix = name.lastIndexOf("_log");
        if (suffix < 0) suffix = name.lastIndexOf("_wood");
        return forSpecies(suffix > 0 ? name.substring(0, suffix) : name);
    }

    /**
     * Every distinct item this palette spends, so a colony can check it can
     * afford a design before anyone starts digging foundations.
     */
    public java.util.Set<String> materials() {
        return new java.util.LinkedHashSet<>(java.util.List.of(
                wall, post, trim, stairs, slab, foundation, foundationSlab, window, light,
                // Every generated house has a real workstation; keeping it in the
                // palette's BOM set makes the early-colony affordability check
                // honest instead of silently inventing a free table.
                "minecraft:crafting_table"));
    }
}
