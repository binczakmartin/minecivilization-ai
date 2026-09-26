package ai.minecivilization.storage;

import ai.minecivilization.navigation.ScaffoldMaterial;

/**
 * How much of a thing a citizen keeps for itself when emptying its bag into the
 * warehouse.
 *
 * <p>A citizen that deposits literally everything walks away from the chest
 * hungry and unable to bridge the ravine it just crossed — and then asks the
 * warehouse for it all back. A small personal reserve costs the settlement
 * nothing and removes a whole class of pointless round trips.</p>
 *
 * <p>Pure and total: one item id in, a count to retain out.</p>
 */
public final class DeliveryPolicy {

    /** Meals kept on hand, so hunger never depends on a trip to the chest. */
    public static final int FOOD_RESERVE = 4;
    /** Blocks kept for bridging and pillaring on the way to the next job. */
    public static final int SCAFFOLD_RESERVE = 8;

    private DeliveryPolicy() {
    }

    /**
     * How many of {@code itemId} the citizen holds back rather than depositing.
     *
     * @return 0 for everything the settlement should own centrally
     */
    public static int keepBack(String itemId) {
        if (itemId == null || itemId.isEmpty()) return 0;
        if (itemId.equals("minecraft:bone")) return 8;
        if (itemId.equals("minecraft:wheat")) return 8;
        if (itemId.equals("minecraft:torch")) return 16;
        // The portable workbench every citizen carries: depositing it only
        // means crafting another one on the next job.
        if (itemId.equals("minecraft:crafting_table")) return 1;
        // Saplings are for planting, not for the warehouse.
        if (itemId.endsWith("_sapling")) return 16;
        if (itemId.equals("minecraft:bucket") || itemId.equals("minecraft:water_bucket")) return 1;
        if (itemId.endsWith("_seeds") || itemId.equals("minecraft:sugar_cane")
                || itemId.equals("minecraft:nether_wart")) return 4;
        if (ItemCategory.of(itemId) == ItemCategory.FOOD) return FOOD_RESERVE;
        if (ScaffoldMaterial.isExpendable(itemId)) return SCAFFOLD_RESERVE;
        return 0;
    }

    /**
     * How many of a carried stack may actually be deposited.
     *
     * @param carried how many the citizen holds in total
     */
    public static int depositable(String itemId, int carried) {
        return Math.max(0, carried - keepBack(itemId));
    }
}
