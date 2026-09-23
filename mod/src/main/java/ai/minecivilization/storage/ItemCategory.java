package ai.minecivilization.storage;

import java.util.Set;

/**
 * What kind of thing an item is, for the purpose of sorting a warehouse.
 *
 * <p>A settlement with one chest is a settlement that spends its day searching.
 * Categories let {@link StorageManager} give every chest a purpose and every
 * item a home, so "where is the iron?" has an answer that does not involve
 * opening forty containers.</p>
 *
 * <p>Classification is pure string work on the item id, deliberately: it needs
 * no registry, no world and no server, so the whole sorting policy is unit
 * tested and behaves identically on a dedicated server and in a test.</p>
 */
public enum ItemCategory {
    FOOD,
    WOOD,
    STONE,
    EARTH,
    ORE,
    TOOLS,
    FARM,
    REDSTONE,
    MISC;

    private static final Set<String> FOODS = Set.of(
            "bread", "apple", "golden_apple", "enchanted_golden_apple", "carrot",
            "golden_carrot", "potato", "baked_potato", "poisonous_potato", "beetroot",
            "beetroot_soup", "mushroom_stew", "rabbit_stew", "suspicious_stew",
            "beef", "cooked_beef", "porkchop", "cooked_porkchop", "chicken",
            "cooked_chicken", "mutton", "cooked_mutton", "rabbit", "cooked_rabbit",
            "cod", "cooked_cod", "salmon", "cooked_salmon", "tropical_fish",
            "pufferfish", "melon_slice", "sweet_berries", "glow_berries",
            "dried_kelp", "honey_bottle", "pumpkin_pie", "cookie", "cake",
            "rotten_flesh", "spider_eye", "milk_bucket");

    private static final Set<String> EARTHS = Set.of(
            "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium",
            "sand", "red_sand", "gravel", "clay", "clay_ball", "mud", "soul_sand",
            "soul_soil", "snow", "snowball", "ice", "packed_ice", "blue_ice");

    private static final Set<String> STONES = Set.of(
            "stone", "cobblestone", "mossy_cobblestone", "smooth_stone", "deepslate",
            "cobbled_deepslate", "polished_deepslate", "granite", "polished_granite",
            "diorite", "polished_diorite", "andesite", "polished_andesite", "tuff",
            "calcite", "dripstone_block", "basalt", "blackstone", "netherrack",
            "obsidian", "sandstone", "red_sandstone", "quartz_block", "terracotta",
            "glass", "glass_pane", "bricks", "brick", "flint");

    private static final Set<String> ORES = Set.of(
            "coal", "charcoal", "diamond", "emerald", "lapis_lazuli", "quartz",
            "amethyst_shard", "netherite_scrap", "netherite_ingot", "flint_and_steel");

    private static final Set<String> FARMING = Set.of(
            "wheat", "sugar_cane", "bamboo", "cactus", "kelp", "seagrass",
            "bone_meal", "bone", "egg", "leather", "feather", "string", "wool",
            "pumpkin", "melon", "brown_mushroom", "red_mushroom", "cocoa_beans",
            "nether_wart", "lily_pad", "vine", "moss_block");

    private static final Set<String> REDSTONE_PARTS = Set.of(
            "redstone", "redstone_torch", "redstone_lamp", "repeater", "comparator",
            "piston", "sticky_piston", "slime_ball", "honey_block", "slime_block",
            "observer", "dispenser", "dropper", "hopper", "lever", "tripwire_hook",
            "target", "daylight_detector", "note_block", "rail", "powered_rail",
            "detector_rail", "activator_rail", "minecart", "hopper_minecart",
            "chest_minecart", "tnt", "dragon_egg", "lectern", "crafter");

    private static final Set<String> TOOL_SUFFIXES = Set.of(
            "_pickaxe", "_axe", "_shovel", "_hoe", "_sword", "_helmet", "_chestplate",
            "_leggings", "_boots", "_bucket", "_bow", "_fishing_rod", "_shears");

    /** Classify an item id such as {@code minecraft:oak_planks}. */
    public static ItemCategory of(String itemId) {
        if (itemId == null || itemId.isEmpty()) return MISC;
        String name = itemId.substring(itemId.indexOf(':') + 1);
        if (name.isEmpty()) return MISC;

        // Tools and armour first: "golden_apple" is food but "golden_axe" is not,
        // and an "iron_pickaxe" must never be filed with the iron ingots.
        for (String suffix : TOOL_SUFFIXES) {
            if (name.endsWith(suffix)) return TOOLS;
        }
        if (name.equals("shears") || name.equals("bow") || name.equals("crossbow")
                || name.equals("shield") || name.equals("trident")
                || name.equals("bucket") || name.equals("fishing_rod")
                || name.equals("flint_and_steel")) {
            return TOOLS;
        }

        if (FOODS.contains(name)) return FOOD;
        if (REDSTONE_PARTS.contains(name)) return REDSTONE;

        // Ore-family: the raw drop, the ore block and the refined metal all
        // belong together — that is the chest a smelter wants to stand next to.
        if (ORES.contains(name)) return ORE;
        if (name.startsWith("raw_") || name.endsWith("_ingot") || name.endsWith("_nugget")
                || name.endsWith("_ore") || name.equals("ancient_debris")) {
            return ORE;
        }

        if (name.endsWith("_log") || name.endsWith("_wood") || name.endsWith("_planks")
                || name.startsWith("stripped_") || name.equals("stick")
                || name.endsWith("_door") || name.endsWith("_fence")
                || name.endsWith("_fence_gate") || name.endsWith("_trapdoor")
                || name.equals("chest") || name.equals("barrel")
                || name.equals("crafting_table") || name.equals("ladder")) {
            return WOOD;
        }

        if (name.endsWith("_seeds") || name.endsWith("_sapling") || FARMING.contains(name)
                || name.endsWith("_wool") || name.endsWith("_dye")) {
            return FARM;
        }

        if (EARTHS.contains(name)) return EARTH;
        if (STONES.contains(name)) return STONE;
        if (name.endsWith("_bricks") || name.endsWith("_stairs") || name.endsWith("_slab")
                || name.endsWith("_wall") || name.endsWith("_stone")
                || name.endsWith("_deepslate") || name.endsWith("_sandstone")
                || name.equals("furnace") || name.equals("blast_furnace")
                || name.equals("smoker") || name.equals("cobblestone")) {
            return STONE;
        }
        if (name.endsWith("_leaves")) return FARM;

        return MISC;
    }

    /** Human-readable label for commands and chest signage. */
    public String label() {
        return switch (this) {
            case FOOD -> "food";
            case WOOD -> "wood & carpentry";
            case STONE -> "stone & masonry";
            case EARTH -> "earth & fill";
            case ORE -> "ores & metal";
            case TOOLS -> "tools & armour";
            case FARM -> "farming & livestock";
            case REDSTONE -> "redstone & machinery";
            case MISC -> "general";
        };
    }
}
