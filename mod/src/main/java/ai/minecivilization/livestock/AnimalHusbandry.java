package ai.minecivilization.livestock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which animals a colony keeps, and what they want to eat.
 *
 * <p>Livestock is the link the settlement is currently missing. Beds need wool,
 * wool needs sheep; births need beds. Leather, eggs and meat all sit behind the
 * same door. A colony that cannot herd animals is capped at whatever it started
 * with.</p>
 *
 * <p>Breeding food is not the same as luring food for every species, but in
 * vanilla it happens to be — the item that tempts an animal is the item that
 * breeds it — so one table serves both jobs.</p>
 *
 * <p>Pure data, unit tested, no registry.</p>
 */
public final class AnimalHusbandry {

    /** Farm animals worth the trouble, and the item that both lures and breeds them. */
    private static final Map<String, List<String>> FEED = buildFeed();

    /** What each species is kept for — used when deciding which to herd first. */
    private static final Map<String, String> YIELD = Map.of(
            "minecraft:sheep", "minecraft:white_wool",
            "minecraft:cow", "minecraft:leather",
            "minecraft:chicken", "minecraft:egg",
            "minecraft:pig", "minecraft:porkchop",
            "minecraft:rabbit", "minecraft:rabbit_hide",
            "minecraft:goat", "minecraft:milk_bucket");

    private static Map<String, List<String>> buildFeed() {
        Map<String, List<String>> feed = new LinkedHashMap<>();
        feed.put("minecraft:sheep", List.of("minecraft:wheat"));
        feed.put("minecraft:cow", List.of("minecraft:wheat"));
        feed.put("minecraft:mooshroom", List.of("minecraft:wheat"));
        feed.put("minecraft:goat", List.of("minecraft:wheat"));
        feed.put("minecraft:chicken", List.of(
                "minecraft:wheat_seeds", "minecraft:beetroot_seeds",
                "minecraft:melon_seeds", "minecraft:pumpkin_seeds"));
        feed.put("minecraft:pig", List.of(
                "minecraft:carrot", "minecraft:potato", "minecraft:beetroot"));
        feed.put("minecraft:rabbit", List.of(
                "minecraft:carrot", "minecraft:golden_carrot", "minecraft:dandelion"));
        return feed;
    }

    private AnimalHusbandry() {
    }

    /** True for an animal the colony keeps in its pasture. */
    public static boolean isLivestock(String entityTypeId) {
        return FEED.containsKey(entityTypeId);
    }

    /** Every species a colony would herd, in the order it should bother. */
    public static List<String> species() {
        return List.copyOf(FEED.keySet());
    }

    /** Items that tempt and breed this species; empty for anything not kept. */
    public static List<String> feedFor(String entityTypeId) {
        return FEED.getOrDefault(entityTypeId, List.of());
    }

    /** The one feed item to carry for a species, or null if it is not livestock. */
    public static String preferredFeed(String entityTypeId) {
        List<String> feed = feedFor(entityTypeId);
        return feed.isEmpty() ? null : feed.get(0);
    }

    /** True when this item will tempt and breed this species. */
    public static boolean isFeed(String entityTypeId, String itemId) {
        return itemId != null && feedFor(entityTypeId).contains(itemId);
    }

    /** What the colony gets out of keeping this species. */
    public static String yieldOf(String entityTypeId) {
        return YIELD.get(entityTypeId);
    }

    /**
     * The species to go and fetch first, given what the colony already keeps.
     *
     * <p>Sheep lead, because wool is beds and beds are the thing currently
     * capping the population. After that, variety beats depth: a herd of one
     * species cannot supply leather, eggs and mutton.</p>
     *
     * @param penned  how many of each species the pasture already holds
     * @param carried which feed items the citizen has, to lure with
     * @return the entity type to herd, or null when nothing can be fetched
     */
    public static String nextWanted(Map<String, Integer> penned, Map<String, Integer> carried) {
        String best = null;
        int bestHeld = Integer.MAX_VALUE;

        for (String type : FEED.keySet()) {
            if (!canLure(type, carried)) continue;
            int held = penned == null ? 0 : penned.getOrDefault(type, 0);
            // A breeding pair is the goal; beyond that the species is handled.
            if (held >= 2) continue;
            if (held < bestHeld) {
                bestHeld = held;
                best = type;
            }
        }
        return best;
    }

    /** Whether the citizen carries anything that will lead this species along. */
    public static boolean canLure(String entityTypeId, Map<String, Integer> carried) {
        if (carried == null) return false;
        for (String item : feedFor(entityTypeId)) {
            if (carried.getOrDefault(item, 0) > 0) return true;
        }
        return false;
    }

    /**
     * Feed items worth keeping on hand for herding, cheapest first. Wheat and
     * seeds both come out of the same field, which is why farming has to work
     * before livestock can.
     */
    public static List<String> shoppingList() {
        return List.of("minecraft:wheat", "minecraft:wheat_seeds", "minecraft:carrot");
    }
}
