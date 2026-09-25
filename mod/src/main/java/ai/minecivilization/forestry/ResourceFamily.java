package ai.minecivilization.forestry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Any tree will do."
 *
 * <p>A citizen told to fetch <em>oak</em> logs in a spruce forest used to
 * search for oak, fail, widen the radius, fail again, and spend a quarter of an
 * hour scanning empty ground — standing perfectly still the whole time. The
 * request was never really for oak; it was for wood.</p>
 *
 * <p>This maps a wanted item to the blocks that can satisfy it and the items
 * that count towards having it. Substitutes are only listed where they are
 * genuinely interchangeable downstream: every log makes planks, and every
 * "cobblestone-like" block makes stone tools, but granite is not cobblestone
 * and is left out.</p>
 *
 * <p>Pure string data, unit tested, no registry.</p>
 */
public final class ResourceFamily {

    private static final List<String> WOOD_SPECIES = List.of(
            "oak", "spruce", "birch", "jungle", "acacia",
            "dark_oak", "mangrove", "cherry");

    /** Blocks that all drop something a stone tool recipe accepts. */
    private static final List<String> STONE_BLOCKS = List.of(
            "minecraft:stone", "minecraft:deepslate", "minecraft:blackstone");
    private static final List<String> STONE_ITEMS = List.of(
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:blackstone");

    private ResourceFamily() {
    }

    /** True when the wanted item is any kind of log. */
    public static boolean isWood(String itemId) {
        if (itemId == null) return false;
        String name = itemId.substring(itemId.indexOf(':') + 1);
        return name.endsWith("_log") || name.endsWith("_wood");
    }

    private static boolean isStone(String itemId) {
        return STONE_ITEMS.contains(itemId);
    }

    /**
     * The blocks a citizen should look for to obtain {@code itemId}.
     *
     * <p>The wanted item's own block always comes first, so a colony in an oak
     * forest still cuts oak — the substitutes are a fallback, not a lottery.</p>
     */
    public static List<String> sourceBlocks(String itemId) {
        if (itemId == null) return List.of();

        if (isWood(itemId)) {
            List<String> blocks = new ArrayList<>();
            blocks.add(itemId);
            for (String species : WOOD_SPECIES) {
                String log = "minecraft:" + species + "_log";
                if (!blocks.contains(log)) blocks.add(log);
            }
            return blocks;
        }
        if (isStone(itemId)) {
            List<String> blocks = new ArrayList<>();
            // cobblestone comes from stone, cobbled_deepslate from deepslate…
            String own = switch (itemId) {
                case "minecraft:cobbled_deepslate" -> "minecraft:deepslate";
                case "minecraft:blackstone" -> "minecraft:blackstone";
                default -> "minecraft:stone";
            };
            blocks.add(own);
            for (String block : STONE_BLOCKS) {
                if (!blocks.contains(block)) blocks.add(block);
            }
            return blocks;
        }
        return List.of(itemId);
    }

    /**
     * Items that count towards "I have enough of this".
     *
     * <p>Without this a citizen sent for oak that comes home with spruce never
     * registers any progress and sets straight back out again.</p>
     */
    public static Set<String> equivalentItems(String itemId) {
        if (itemId == null) return Set.of();

        if (isWood(itemId)) {
            Set<String> items = new LinkedHashSet<>();
            items.add(itemId);
            for (String species : WOOD_SPECIES) {
                items.add("minecraft:" + species + "_log");
                items.add("minecraft:" + species + "_wood");
            }
            return items;
        }
        if (isStone(itemId)) {
            return new LinkedHashSet<>(STONE_ITEMS);
        }
        // Charcoal is coal for every purpose a colony has: it lights the same
        // torch and fires the same furnace. Treating them as different items
        // meant a settlement with a forest and no coal seam could never make a
        // torch, however much charcoal it had burned.
        if (FUEL_ITEMS.contains(itemId)) {
            return new LinkedHashSet<>(FUEL_ITEMS);
        }
        return Set.of(itemId);
    }

    /** True when {@code candidate} satisfies a request for {@code wanted}. */
    public static boolean satisfies(String wanted, String candidate) {
        return candidate != null && equivalentItems(wanted).contains(candidate);
    }

    /** Fuels that are interchangeable in every recipe the colony uses. */
    private static final Set<String> FUEL_ITEMS =
            new LinkedHashSet<>(List.of("minecraft:coal", "minecraft:charcoal"));

    /** True when this item has substitutes at all — most do not. */
    public static boolean hasSubstitutes(String itemId) {
        return equivalentItems(itemId).size() > 1;
    }
}
