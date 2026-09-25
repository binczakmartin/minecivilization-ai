package ai.minecivilization.citizen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * What a trade carries in order to do its job.
 *
 * <p>This is the piece the colony was missing, and its absence explains most
 * of what looked like stupidity. A shepherd with no wheat cannot lead a sheep
 * anywhere, so it never breeds one, so the colony never has wool, so it never
 * has beds, so it never grows. A miner with no wood cannot replace the pickaxe
 * it wore out and cannot make a torch from the coal in its own pack, so it
 * mines in the dark until something kills it. Nobody was carrying torches, so
 * nothing was ever lit. Nobody was carrying planks, so no sign was ever put
 * up.</p>
 *
 * <p>Each of those is the same bug: the colony had jobs but no notion of
 * <em>equipment</em>. A citizen would set out for work it was not carrying the
 * means to do, fail, and be handed the same job again.</p>
 *
 * <p>A kit is declarative — a list of what a trade should have on it — and the
 * work board turns any shortfall into an ordinary job. Adding a trade, or
 * changing what one carries, is an edit to this file and nothing else.</p>
 *
 * <p>Pure: profession and inventory counts in, the next missing item out, so
 * the whole equipment policy of the colony is unit tested.</p>
 */
public final class Loadout {

    /** How a citizen is expected to obtain something. */
    public enum Source {
        /** Make it at a workbench, resolving its recipe tree. */
        CRAFT,
        /** Find it in the world and take it. */
        GATHER,
        /** Take it out of the colony's stores. */
        WITHDRAW
    }

    /**
     * One line of a kit.
     *
     * @param itemId   what to carry
     * @param quantity how many before the need is met
     * @param source   how to get it
     * @param spare    true when it is nice to have rather than needed to work
     */
    public record Need(String itemId, int quantity, Source source, boolean spare) {

        public String label() {
            return itemId.substring(itemId.indexOf(':') + 1).replace('_', ' ');
        }
    }

    /**
     * Tools every citizen carries, whatever its trade.
     *
     * <p>Quantities are deliberately small. A kit is what a citizen needs to
     * <em>start</em> working, not a target stock level: asking a newly spawned
     * citizen for thirty-two planks before it may do anything turns its first
     * job into felling four trees, and the colony spends its first ten minutes
     * shopping. Bulk comes later, through ordinary gathering, and the spare
     * flag keeps the nice-to-haves out of the way until the stores exist to
     * fill them from.</p>
     */
    private static final List<Need> UNIVERSAL = List.of(
            // A weapon first. Citizens were being killed by mobs they had no
            // means to answer, which no amount of clever work scheduling fixes.
            new Need("minecraft:wooden_sword", 1, Source.CRAFT, false),
            new Need("minecraft:wooden_axe", 1, Source.CRAFT, false),
            new Need("minecraft:wooden_pickaxe", 1, Source.CRAFT, false),
            // Light is safety: a lit workplace does not spawn what kills you.
            new Need("minecraft:torch", 4, Source.CRAFT, true),
            // The material every other need is made from. A citizen holding a
            // few planks can replace a broken tool on the spot instead of
            // walking home for one.
            new Need("minecraft:oak_planks", 4, Source.CRAFT, true));

    private Loadout() {
    }

    /**
     * The full kit for a trade: its own tools first, then the common ones.
     *
     * <p>Trade-specific needs lead because they are the ones that unblock work
     * nobody else can do — a colony with no shears has no wool however many
     * axes it owns.</p>
     */
    public static List<Need> forProfession(@Nullable String profession) {
        List<Need> kit = new ArrayList<>(specialised(profession));
        for (Need universal : UNIVERSAL) {
            if (kit.stream().noneMatch(need -> need.itemId().equals(universal.itemId()))) {
                kit.add(universal);
            }
        }
        return kit;
    }

    private static List<Need> specialised(@Nullable String profession) {
        String trade = profession == null ? "" : profession.toUpperCase(Locale.ROOT);
        return switch (trade) {
            case "SHEPHERD", "RANCHER", "BREEDER" -> List.of(
                    // Shears are the only way to get wool without killing the
                    // flock, and wool is what beds — and therefore growth —
                    // are made of.
                    new Need("minecraft:shears", 1, Source.CRAFT, false),
                    // Animals follow food. Without a lure a shepherd cannot
                    // lead anything into a pen or persuade a pair to breed, so
                    // every livestock job it was given failed on arrival.
                    new Need("minecraft:wheat", 4, Source.WITHDRAW, false),
                    new Need("minecraft:wheat_seeds", 4, Source.GATHER, true),
                    new Need("minecraft:carrot", 4, Source.WITHDRAW, true),
                    // Fencing, so a herded animal stays herded.
                    new Need("minecraft:oak_fence", 8, Source.CRAFT, true));

            case "MINER" -> List.of(
                    new Need("minecraft:stone_pickaxe", 1, Source.CRAFT, true),
                    new Need("minecraft:wooden_shovel", 1, Source.CRAFT, true),
                    // A mine is dark, and a dark mine is a mob farm.
                    new Need("minecraft:torch", 8, Source.CRAFT, true),
                    // Wood underground is how a worn-out pickaxe gets replaced
                    // without a walk back to the surface — and, with the coal
                    // it is already digging, how the torches get made.
                    new Need("minecraft:oak_planks", 8, Source.WITHDRAW, true));

            case "LUMBERJACK", "FORESTER" -> List.of(
                    new Need("minecraft:stone_axe", 1, Source.CRAFT, true),
                    // Put the forest back: saplings come off the canopy the
                    // job just felled, so this costs the colony nothing.
                    new Need("minecraft:oak_sapling", 4, Source.GATHER, true));

            case "FARMER" -> List.of(
                    new Need("minecraft:wooden_hoe", 1, Source.CRAFT, false),
                    new Need("minecraft:wheat_seeds", 8, Source.GATHER, true));

            case "BUILDER" -> List.of(
                    // A builder with no blocks is a spectator.
                    new Need("minecraft:oak_planks", 16, Source.WITHDRAW, true),
                    new Need("minecraft:cobblestone", 16, Source.WITHDRAW, true),
                    new Need("minecraft:torch", 8, Source.CRAFT, true));

            case "CRAFTER", "SMITH", "ENGINEER" -> List.of(
                    // Carrying a workbench means never queueing for one.
                    new Need("minecraft:crafting_table", 1, Source.CRAFT, false),
                    new Need("minecraft:oak_planks", 12, Source.WITHDRAW, true),
                    new Need("minecraft:furnace", 1, Source.CRAFT, true));

            case "LOGISTICS", "TRADER" -> List.of(
                    new Need("minecraft:chest", 1, Source.CRAFT, true));

            default -> List.of();
        };
    }

    // ------------------------------------------------------------------ shortfall

    /**
     * The next thing this citizen should go and get, or null when kitted out.
     *
     * <p>Needs are answered in order, and the essentials of a trade come
     * before anyone's spares — a shepherd without shears is a more urgent
     * problem than a builder without a tidy stock of planks.</p>
     *
     * @param carried item id to count, as the citizen's pack reports it
     */
    @Nullable
    public static Need nextMissing(@Nullable String profession, Map<String, Integer> carried) {
        return nextMissing(profession, carried, false);
    }

    /**
     * @param includeSpares true to also top up the nice-to-haves
     */
    @Nullable
    public static Need nextMissing(@Nullable String profession, Map<String, Integer> carried,
                                   boolean includeSpares) {
        List<Need> kit = forProfession(profession);
        // Essentials across the whole kit first, then spares.
        for (Need need : kit) {
            if (need.spare()) continue;
            if (!satisfied(need, carried)) return need;
        }
        if (!includeSpares) return null;
        for (Need need : kit) {
            if (!need.spare()) continue;
            if (!satisfied(need, carried)) return need;
        }
        return null;
    }

    /**
     * Whether a need is already met.
     *
     * <p>A better tool of the same kind counts: a citizen holding a stone
     * pickaxe does not need a wooden one, and asking it to make one anyway is
     * how a colony burns an afternoon on tools it already has.</p>
     */
    public static boolean satisfied(Need need, Map<String, Integer> carried) {
        if (carried == null) return false;
        int have = carried.getOrDefault(need.itemId(), 0);
        if (have >= need.quantity()) return true;

        String upgrade = betterThan(need.itemId());
        while (upgrade != null) {
            if (carried.getOrDefault(upgrade, 0) >= need.quantity()) return true;
            upgrade = betterThan(upgrade);
        }
        return false;
    }

    /** The next tier up of the same tool, or null when there is none. */
    @Nullable
    public static String betterThan(String itemId) {
        if (itemId == null) return null;
        int colon = itemId.indexOf(':');
        String name = colon < 0 ? itemId : itemId.substring(colon + 1);
        for (int i = 0; i < TIERS.length - 1; i++) {
            if (name.startsWith(TIERS[i] + "_")) {
                return "minecraft:" + TIERS[i + 1] + name.substring(TIERS[i].length());
            }
        }
        return null;
    }

    /** Tool materials, worst to best. */
    private static final String[] TIERS = {
            "wooden", "stone", "iron", "golden", "diamond", "netherite"
    };
}
