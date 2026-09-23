package ai.minecivilization.farming;

import java.util.Map;

/** One prerequisite at a time, re-evaluated against real stock after each job. */
public final class LightingPolicy {
    public record Job(String type, String item, int quantity) {}
    public static Job next(Map<String, Integer> inventory, boolean table, boolean furnace, boolean pickaxe) {
        if (inventory.getOrDefault("minecraft:torch", 0) > 0) return new Job("DECORATE", "minecraft:torch", 8);
        int fuel = Math.max(inventory.getOrDefault("minecraft:coal", 0), inventory.getOrDefault("minecraft:charcoal", 0));
        if (fuel > 0) return new Job("CRAFT", "minecraft:torch", Math.min(4, fuel) * 4);
        if (!furnace) {
            if (inventory.getOrDefault("minecraft:furnace", 0) > 0) return new Job("PLACE", "minecraft:furnace", 1);
            if (!table) return new Job(inventory.getOrDefault("minecraft:crafting_table", 0) > 0 ? "PLACE" : "CRAFT", "minecraft:crafting_table", 1);
            if (!pickaxe && inventory.getOrDefault("minecraft:cobblestone", 0) < 8)
                return new Job("CRAFT", "minecraft:wooden_pickaxe", 1);
            return new Job("CRAFT", "minecraft:furnace", 1);
        }
        return new Job("CRAFT", "minecraft:charcoal", 4);
    }
    private LightingPolicy() {}
}
