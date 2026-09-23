package ai.minecivilization.colony;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The kinds of place a colony remembers.
 *
 * <p>Citizens had no memory of where anything was. Every craft re-scanned the
 * ground for a workbench, every smelt re-scanned for a furnace, and if the
 * nearest one happened to be more than a search radius away it may as well not
 * have existed — a citizen would stand beside a settlement full of workstations
 * and report that it could not find one.</p>
 *
 * <p>A remembered place is shared by the whole colony, so a furnace one citizen
 * built is a furnace everyone can use, however far away it is.</p>
 *
 * <p>Classification is pure string work on the block id, so the gazetteer's
 * rules are unit tested without a world.</p>
 */
public enum LandmarkKind {
    CRAFTING_TABLE("workbench"),
    FURNACE("furnace"),
    BLAST_FURNACE("blast furnace"),
    SMOKER("smoker"),
    STONECUTTER("stonecutter"),
    ENCHANTING_TABLE("enchanting table"),
    ANVIL("anvil"),
    BREWING_STAND("brewing stand"),
    SMITHING_TABLE("smithing table"),
    GRINDSTONE("grindstone"),
    LOOM("loom"),
    CARTOGRAPHY_TABLE("cartography table"),
    FLETCHING_TABLE("fletching table"),
    COMPOSTER("composter"),
    STORAGE("storage"),
    BED("bed"),
    /** Levers, buttons, pistons, hoppers — the parts a machine is made of. */
    MECHANISM("mechanism"),
    LIGHT("light");

    private final String label;

    LandmarkKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True for places a citizen goes to in order to do a job. */
    public boolean isWorkstation() {
        return switch (this) {
            case STORAGE, BED, MECHANISM, LIGHT -> false;
            default -> true;
        };
    }

    // ------------------------------------------------------------------ classification

    private static final Map<String, LandmarkKind> EXACT = Map.ofEntries(
            Map.entry("minecraft:crafting_table", CRAFTING_TABLE),
            Map.entry("minecraft:furnace", FURNACE),
            Map.entry("minecraft:blast_furnace", BLAST_FURNACE),
            Map.entry("minecraft:smoker", SMOKER),
            Map.entry("minecraft:stonecutter", STONECUTTER),
            Map.entry("minecraft:enchanting_table", ENCHANTING_TABLE),
            Map.entry("minecraft:brewing_stand", BREWING_STAND),
            Map.entry("minecraft:smithing_table", SMITHING_TABLE),
            Map.entry("minecraft:grindstone", GRINDSTONE),
            Map.entry("minecraft:loom", LOOM),
            Map.entry("minecraft:cartography_table", CARTOGRAPHY_TABLE),
            Map.entry("minecraft:fletching_table", FLETCHING_TABLE),
            Map.entry("minecraft:composter", COMPOSTER),
            Map.entry("minecraft:chest", STORAGE),
            Map.entry("minecraft:trapped_chest", STORAGE),
            Map.entry("minecraft:barrel", STORAGE),
            Map.entry("minecraft:hopper", MECHANISM),
            Map.entry("minecraft:dispenser", MECHANISM),
            Map.entry("minecraft:dropper", MECHANISM),
            Map.entry("minecraft:observer", MECHANISM),
            Map.entry("minecraft:lever", MECHANISM),
            Map.entry("minecraft:note_block", MECHANISM),
            Map.entry("minecraft:redstone_lamp", MECHANISM),
            Map.entry("minecraft:target", MECHANISM),
            Map.entry("minecraft:daylight_detector", MECHANISM),
            Map.entry("minecraft:comparator", MECHANISM),
            Map.entry("minecraft:repeater", MECHANISM),
            Map.entry("minecraft:piston", MECHANISM),
            Map.entry("minecraft:sticky_piston", MECHANISM),
            Map.entry("minecraft:torch", LIGHT),
            Map.entry("minecraft:wall_torch", LIGHT),
            Map.entry("minecraft:lantern", LIGHT),
            Map.entry("minecraft:campfire", LIGHT));

    private static final Set<String> ANVILS = Set.of(
            "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil");

    /**
     * What kind of place this block is, or {@code null} for ordinary ground.
     *
     * <p>Suffix rules cover the families that come in a colour or a wood per
     * variant — beds and shulker boxes — without listing sixteen of each.</p>
     */
    public static LandmarkKind of(String blockId) {
        if (blockId == null || blockId.isEmpty()) return null;

        LandmarkKind exact = EXACT.get(blockId);
        if (exact != null) return exact;
        if (ANVILS.contains(blockId)) return ANVIL;

        String name = blockId.substring(blockId.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (name.endsWith("_bed")) return BED;
        if (name.endsWith("shulker_box")) return STORAGE;
        if (name.endsWith("_button") || name.endsWith("_pressure_plate")) return MECHANISM;
        if (name.endsWith("_rail")) return MECHANISM;
        if (name.equals("rail")) return MECHANISM;
        return null;
    }

    /** True for a block worth writing down. */
    public static boolean isLandmark(String blockId) {
        return of(blockId) != null;
    }
}
