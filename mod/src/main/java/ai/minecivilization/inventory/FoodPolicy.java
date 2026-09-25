package ai.minecivilization.inventory;

import java.util.Set;

/**
 * What a citizen chooses to eat.
 *
 * <p>Two opposite mistakes, both bad. A citizen that eats zombie flesh with
 * bread in its pack is poisoning itself for nothing; one that starves beside a
 * stack of flesh it refused has died of fastidiousness. The rule is that the
 * unpleasant food is ranked below everything wholesome, however filling, but
 * is never ruled out — hunger eventually outranks nausea.</p>
 *
 * <p>Pure: an item id and its nutrition in, a ranking out, so the colony's
 * diet is unit tested without a world.</p>
 */
public final class FoodPolicy {

    /**
     * Food a citizen eats only rather than starve.
     *
     * <p>Raw chicken belongs here and cooked chicken does not, which is the
     * whole reason the colony now bothers to build a furnace.</p>
     */
    private static final Set<String> LAST_RESORT = Set.of(
            "minecraft:rotten_flesh",
            "minecraft:spider_eye",
            "minecraft:chicken",
            "minecraft:pufferfish",
            "minecraft:poisonous_potato",
            "minecraft:suspicious_stew");

    /** Penalty that puts anything unpleasant below all wholesome food. */
    private static final int LAST_RESORT_PENALTY = 100;

    private FoodPolicy() {
    }

    public static boolean isLastResort(String itemId) {
        return itemId != null && LAST_RESORT.contains(itemId);
    }

    /**
     * How good a mouthful this is. Higher wins; ties keep the earlier slot.
     *
     * @param nutrition  what the item restores
     * @param lastResort whether it is food a citizen would rather not eat
     */
    public static int score(int nutrition, boolean lastResort) {
        return nutrition - (lastResort ? LAST_RESORT_PENALTY : 0);
    }

    /**
     * True when this item may be eaten at all given how desperate things are.
     */
    public static boolean edible(String itemId, boolean desperate) {
        return desperate || !isLastResort(itemId);
    }

    /**
     * The index of the best thing to eat, or -1 when nothing qualifies.
     *
     * @param itemIds    item id per slot, null for empty slots
     * @param nutrition  nutrition per slot, 0 for anything that is not food
     * @param desperate  true to consider food a citizen would normally refuse
     */
    public static int bestIndex(String[] itemIds, int[] nutrition, boolean desperate) {
        if (itemIds == null || nutrition == null) return -1;
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < itemIds.length && i < nutrition.length; i++) {
            String id = itemIds[i];
            if (id == null || nutrition[i] <= 0) continue;
            boolean nasty = isLastResort(id);
            if (!edible(id, desperate)) continue;

            int score = score(nutrition[i], nasty);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }
}
