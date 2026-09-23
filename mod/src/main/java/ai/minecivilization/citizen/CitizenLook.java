package ai.minecivilization.citizen;

import java.util.List;
import java.util.Locale;

/**
 * What a citizen looks like, and what it should be holding.
 *
 * <p>A settlement where everyone is the same figure in the same clothes is
 * unreadable: you cannot tell at a glance who is the farmer, or whether the
 * miner ever got its pickaxe. Two cheap signals fix that — a different face per
 * trade, and the tool of the moment actually in hand.</p>
 *
 * <p>The faces are Minecraft's own default player skins, which ship with the
 * game. No new art, no download, and they already differ in clothing as well as
 * complexion — which is exactly what is wanted.</p>
 *
 * <p>Pure string mapping, unit tested.</p>
 */
public final class CitizenLook {

    /** Default player skins shipped with the game, on the wide player model. */
    private static final List<String> FACES = List.of(
            "minecraft:textures/entity/player/wide/steve.png",
            "minecraft:textures/entity/player/wide/sunny.png",
            "minecraft:textures/entity/player/wide/zuri.png",
            "minecraft:textures/entity/player/wide/noor.png",
            "minecraft:textures/entity/player/wide/efe.png",
            "minecraft:textures/entity/player/wide/kai.png",
            "minecraft:textures/entity/player/wide/makena.png",
            "minecraft:textures/entity/player/wide/ari.png");

    /** Trades in a fixed order, so a profession always gets the same face. */
    private static final List<String> TRADES = List.of(
            "LUMBERJACK", "MINER", "FARMER", "BUILDER", "CRAFTER", "SHEPHERD", "LOGISTICS");

    private CitizenLook() {
    }

    /**
     * The skin for a trade. Unknown or unassigned citizens get the first face,
     * so a newcomer is visibly a newcomer until it takes up a trade.
     */
    public static String faceFor(String profession) {
        if (profession == null) return FACES.get(0);
        int index = TRADES.indexOf(profession.toUpperCase(Locale.ROOT));
        if (index < 0) return FACES.get(0);
        return FACES.get((index + 1) % FACES.size());
    }

    /**
     * The tool a citizen should be seen holding for the work it is doing.
     *
     * <p>Falls through to the trade's usual tool when it is between tasks, so a
     * miner standing idle still reads as a miner.</p>
     *
     * @param taskType current task name, or null when idle
     * @return an item-id suffix such as {@code _pickaxe}, or null for bare hands
     */
    public static String toolSuffixFor(String profession, String taskType) {
        if (taskType != null) {
            String byTask = switch (taskType.toUpperCase(Locale.ROOT)) {
                case "GATHER" -> "_pickaxe";
                case "HARVEST", "PLANT" -> "_hoe";
                case "BUILD", "PLACE", "DECORATE" -> "_axe";
                default -> null;
            };
            if (byTask != null) return byTask;
        }
        if (profession == null) return null;
        return switch (profession.toUpperCase(Locale.ROOT)) {
            case "LUMBERJACK" -> "_axe";
            case "MINER" -> "_pickaxe";
            case "FARMER" -> "_hoe";
            case "BUILDER", "CRAFTER" -> "_axe";
            default -> null;
        };
    }

    /** Every skin the mod may ask the client for — used to keep the list honest. */
    public static List<String> faces() {
        return FACES;
    }
}
