package ai.minecivilization.colony;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Small announcements about the life of the colony.
 *
 * <p>Most of what citizens do is invisible from a distance — a settlement of
 * twenty is a lot of small errands. The moments worth interrupting a player
 * for are the ones that change what the colony <em>is</em>: someone is born,
 * someone dies, a new district is laid out, a species is brought home. Those
 * are announced; the errands are not.</p>
 *
 * <p>Messages go to players in the colony's own level only, so a player mining
 * in the Nether is not told about a birth they cannot see.</p>
 */
public final class ColonyNotifier {

    private ColonyNotifier() {
    }

    /** One line, prefixed and coloured so colony news is recognisable at a glance. */
    public static void announce(ServerLevel level, ChatFormatting colour, String message) {
        if (level == null || message == null || message.isEmpty()) return;
        var server = level.getServer();
        if (server == null) return;

        Component line = Component.literal("⌂ ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(message).withStyle(colour));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.level().dimension().equals(level.dimension())) continue;
            player.sendSystemMessage(line);
        }
    }

    public static void birth(ServerLevel level, String childName, int population) {
        announce(level, ChatFormatting.GREEN,
                childName + " was born. The colony is now " + population + " strong.");
    }

    public static void death(ServerLevel level, String name, String cause) {
        String detail = cause == null || cause.isBlank() ? "" : " (" + cause + ")";
        announce(level, ChatFormatting.RED, name + " has died" + detail + ".");
    }

    public static void zoneAllotted(ServerLevel level, Zone zone) {
        if (zone == null) return;
        announce(level, ChatFormatting.AQUA,
                "New " + zone.type.label() + " laid out at "
                        + zone.centerX() + ", " + zone.centerZ() + ".");
    }

    public static void speciesDiscovered(ServerLevel level, String saplingId) {
        if (saplingId == null) return;
        String name = saplingId.substring(saplingId.indexOf(':') + 1).replace('_', ' ');
        announce(level, ChatFormatting.DARK_GREEN,
                "The colony can now grow " + name + ".");
    }

    public static void milestone(ServerLevel level, String message) {
        announce(level, ChatFormatting.YELLOW, message);
    }
}
