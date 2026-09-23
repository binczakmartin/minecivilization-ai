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

    /** How many blocks the citizen could place in total, across all usable materials. */
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
        return itemId != null && !NEVER.contains(itemId) && PREFERRED.contains(itemId);
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
