package ai.minecivilization.navigation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.minecivilization.inventory.CitizenInventory;

/**
 * Which carried block a citizen is willing to spend on a bridge deck or a
 * pillar support.
 *
 * <p>Scaffolding is throwaway construction, so the choice matters: spending the
 * only iron ore on a footbridge is a worse outcome than not crossing. The
 * preference list runs cheap-and-abundant first, and a deny list keeps
 * valuables, containers and gravity-affected blocks (a sand bridge falls out
 * from under its builder) out of the running entirely.</p>
 *
 * <p>The choice itself is pure — a map of item ids to counts in, an item id
 * out — so it is unit-tested without a world.</p>
 */
public final class ScaffoldMaterial {

    /** Cheap, plentiful, gravity-stable blocks, best first. */
    static final List<String> PREFERRED = List.of(
            "minecraft:dirt",
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:coarse_dirt",
            "minecraft:andesite",
            "minecraft:diorite",
            "minecraft:granite",
            "minecraft:tuff",
            "minecraft:stone",
            "minecraft:deepslate",
            "minecraft:netherrack",
            "minecraft:oak_planks",
            "minecraft:spruce_planks",
            "minecraft:birch_planks",
            "minecraft:jungle_planks",
            "minecraft:acacia_planks",
            "minecraft:dark_oak_planks",
            // Logs are stable full blocks too.  They are a last-resort bridge
            // material: spending a resource is better than leaving a citizen
            // stranded, but planks/dirt/cobble always win first.
            "minecraft:oak_log",
            "minecraft:spruce_log",
            "minecraft:birch_log",
            "minecraft:jungle_log",
            "minecraft:acacia_log",
            "minecraft:dark_oak_log",
            "minecraft:mangrove_log",
            "minecraft:cherry_log");

    /** Stair blocks used when a route needs a climb rather than a full pillar. */
    static final List<String> STAIR_PREFERRED = List.of(
            "minecraft:oak_stairs",
            "minecraft:spruce_stairs",
            "minecraft:birch_stairs",
            "minecraft:jungle_stairs",
            "minecraft:acacia_stairs",
            "minecraft:dark_oak_stairs",
            "minecraft:cobblestone_stairs",
            "minecraft:stone_stairs",
            "minecraft:andesite_stairs",
            "minecraft:granite_stairs",
            "minecraft:diorite_stairs",
            "minecraft:cobbled_deepslate_stairs",
            "minecraft:deepslate_stairs",
            "minecraft:tuff_stairs",
            "minecraft:netherrack_stairs");

    /**
     * Never spent on scaffolding: gravity blocks fall out from under the
     * builder, and the rest are worth more than a shortcut.
     */
    static final Set<String> NEVER = Set.of(
            "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
            "minecraft:anvil", "minecraft:chest", "minecraft:trapped_chest",
            "minecraft:barrel", "minecraft:furnace", "minecraft:blast_furnace",
            "minecraft:crafting_table", "minecraft:smoker",
            "minecraft:iron_block", "minecraft:gold_block", "minecraft:diamond_block",
            "minecraft:emerald_block", "minecraft:netherite_block",
            "minecraft:copper_block", "minecraft:lapis_block", "minecraft:redstone_block",
            "minecraft:tnt", "minecraft:bed", "minecraft:torch");

    private ScaffoldMaterial() {
    }

    /**
     * Best block to spend, or {@code null} when the citizen carries nothing it
     * should part with.
     *
     * <p>A material that covers the whole job wins over a more-preferred one
     * that would run out halfway, because a half-built bridge strands the
     * citizen on the wrong side.</p>
     *
     * @param counts  item id to carried count
     * @param needed  how many blocks the route wants to place
     */
    public static String choose(Map<String, Integer> counts, int needed) {
        if (counts == null || counts.isEmpty()) return null;
        int want = Math.max(1, needed);

        String bestPartial = null;
        int bestPartialCount = 0;

        for (String candidate : PREFERRED) {
            int held = counts.getOrDefault(candidate, 0);
            if (held <= 0) continue;
            if (held >= want) return candidate;
            if (held > bestPartialCount) {
                bestPartialCount = held;
                bestPartial = candidate;
            }
        }
        return bestPartial;
    }

    /** Best usable construction block, including stairs. */
    /**
     * Rubble: material worth nothing, which is what a pillar should be made of.
     *
     * <p>A citizen that has just felled a tree is holding logs, and spending
     * one as a stepping stone costs the colony a plank's worth of building
     * material to save itself digging a block of dirt. Dirt is underfoot
     * everywhere and worth nothing; the logs are the entire point of the
     * job.</p>
     */
    private static final List<String> CHEAP = List.of(
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:gravel",
            "minecraft:sand",
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:andesite",
            "minecraft:diorite",
            "minecraft:granite",
            "minecraft:tuff",
            "minecraft:netherrack");

    /** How much expendable rubble the citizen is carrying. */
    public static int availableCheap(Map<String, Integer> counts) {
        if (counts == null) return 0;
        int total = 0;
        for (String candidate : CHEAP) {
            total += Math.max(0, counts.getOrDefault(candidate, 0));
        }
        return total;
    }

    public static int availableCheap(CitizenInventory inventory) {
        return availableCheap(snapshot(inventory));
    }

    /** True for material a colony would rather build with than stand on. */
    public static boolean isPrecious(String itemId) {
        return itemId != null && !CHEAP.contains(itemId);
    }

    public static String chooseAny(Map<String, Integer> counts, int needed) {
        if (counts == null || counts.isEmpty()) return null;
        int want = Math.max(1, needed);
        String full = choose(counts, want);
        if (full != null && counts.getOrDefault(full, 0) >= want) return full;
        String stair = chooseStair(counts, want);
        if (stair != null && counts.getOrDefault(stair, 0) >= want) return stair;
        return full != null ? full : stair;
    }

    public static String chooseAny(CitizenInventory inventory, int needed) {
        return chooseAny(snapshot(inventory), needed);
    }

    public static int availableAny(Map<String, Integer> counts) {
        return available(counts) + availableStairs(counts);
    }

    public static int availableAny(CitizenInventory inventory) {
        return availableAny(snapshot(inventory));
    }

    private static int availableStairs(Map<String, Integer> counts) {
        if (counts == null) return 0;
        int total = 0;
        for (String candidate : STAIR_PREFERRED) {
            total += Math.max(0, counts.getOrDefault(candidate, 0));
        }
        return total;
    }

    /** Best stair material currently carried, or null when only full blocks are available. */
    public static String chooseStair(Map<String, Integer> counts, int needed) {
        if (counts == null || counts.isEmpty()) return null;
        int want = Math.max(1, needed);
        for (String candidate : STAIR_PREFERRED) {
            if (counts.getOrDefault(candidate, 0) >= want) return candidate;
        }
        String bestPartial = null;
        int bestPartialCount = 0;
        for (String candidate : STAIR_PREFERRED) {
            int held = counts.getOrDefault(candidate, 0);
            if (held > bestPartialCount) {
                bestPartialCount = held;
                bestPartial = candidate;
            }
        }
        return bestPartial;
    }

    public static String chooseStair(CitizenInventory inventory, int needed) {
        return chooseStair(snapshot(inventory), needed);
    }

    public static int availableStairs(CitizenInventory inventory) {
        Map<String, Integer> counts = snapshot(inventory);
        int total = 0;
        for (String candidate : STAIR_PREFERRED) {
            total += Math.max(0, counts.getOrDefault(candidate, 0));
        }
        return total;
    }

    public static int available(Map<String, Integer> counts) {
        if (counts == null) return 0;
        int total = 0;
        for (String candidate : PREFERRED) {
            total += Math.max(0, counts.getOrDefault(candidate, 0));
        }
        return total;
    }

    /** True when this item may be spent on throwaway construction. */
    public static boolean isExpendable(String itemId) {
        return itemId != null && !NEVER.contains(itemId)
                && (PREFERRED.contains(itemId) || STAIR_PREFERRED.contains(itemId));
    }

    // ------------------------------------------------------------------ world adapter

    /** Snapshot of a citizen's inventory as item id to count. */
    public static Map<String, Integer> snapshot(CitizenInventory inventory) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            counts.merge(CitizenInventory.idOf(stack), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** Best scaffolding block the citizen currently carries, or null. */
    public static String choose(CitizenInventory inventory, int needed) {
        return choose(snapshot(inventory), needed);
    }

    /** Total placeable scaffolding blocks the citizen carries. */
    public static int available(CitizenInventory inventory) {
        return available(snapshot(inventory));
    }
}
