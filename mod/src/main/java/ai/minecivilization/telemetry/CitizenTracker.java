package ai.minecivilization.telemetry;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import ai.minecivilization.colony.CitizenMarkers;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Finding one citizen in a world.
 *
 * <p>A colony spread over several hundred blocks, with people down mineshafts
 * and behind hills, is genuinely hard to navigate. Name tags are invisible past
 * about thirty blocks and a coordinate in chat is something you then have to
 * hold in your head while you walk.</p>
 *
 * <p>Tracking a citizen replaces all of that with a live line on the action
 * bar: which way to turn, how far, how deep, and what they are doing — updated
 * as both of you move. It is the difference between "they are somewhere south"
 * and being able to walk straight to them.</p>
 */
public final class CitizenTracker {

    /** Ticks between action-bar updates. Four a second reads as live. */
    private static final int UPDATE_INTERVAL = 5;
    /** Tracking lapses after this long, so a forgotten tracker is not forever. */
    private static final int EXPIRY_TICKS = 24_000;

    private record Tracked(UUID citizen, long startedAt) {
    }

    private static final Map<UUID, Tracked> BY_PLAYER = new HashMap<>();
    private static long nextUpdateAt;

    private CitizenTracker() {
    }

    // ------------------------------------------------------------------ control

    /** Follow this citizen on the player's action bar. */
    public static void track(ServerPlayer player, CitizenEntity citizen, long now) {
        if (player == null || citizen == null) return;
        BY_PLAYER.put(player.getUUID(), new Tracked(citizen.getUUID(), now));
        // The outline is what finds someone through a hillside; the action bar
        // is what tells you which hillside.
        CitizenMarkers.setHighlighted(true);
    }

    public static void stop(ServerPlayer player) {
        if (player != null) BY_PLAYER.remove(player.getUUID());
    }

    public static boolean isTracking(ServerPlayer player) {
        return player != null && BY_PLAYER.containsKey(player.getUUID());
    }

    @Nullable
    public static CitizenEntity tracked(ServerPlayer player) {
        Tracked tracked = player == null ? null : BY_PLAYER.get(player.getUUID());
        if (tracked == null) return null;
        for (CitizenEntity citizen : CitizenIndex.all()) {
            if (citizen.getUUID().equals(tracked.citizen())) return citizen;
        }
        return null;
    }

    public static void reset() {
        BY_PLAYER.clear();
        nextUpdateAt = 0;
    }

    // ------------------------------------------------------------------ the line

    /** Called every server tick; updates occasionally. */
    public static void tick(ServerLevel level) {
        if (level == null || BY_PLAYER.isEmpty()) return;
        long now = level.getGameTime();
        if (now < nextUpdateAt) return;
        nextUpdateAt = now + UPDATE_INTERVAL;

        var server = level.getServer();
        if (server == null) return;

        BY_PLAYER.entrySet().removeIf(entry -> now - entry.getValue().startedAt() > EXPIRY_TICKS);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Tracked tracked = BY_PLAYER.get(player.getUUID());
            if (tracked == null) continue;

            CitizenEntity citizen = tracked(player);
            if (citizen == null || citizen.isRemoved()) {
                player.sendSystemMessage(Component.literal("The tracked citizen is gone.")
                        .withStyle(ChatFormatting.RED));
                BY_PLAYER.remove(player.getUUID());
                continue;
            }
            player.sendSystemMessage(line(player, citizen), true);
        }
    }

    /**
     * Direction, distance, depth and activity, in one action-bar line.
     *
     * <p>The bearing is relative to where the player is looking, not to north,
     * because "behind you" is instantly actionable and "north-east" is not.</p>
     */
    public static Component line(ServerPlayer player, CitizenEntity citizen) {
        double dx = citizen.getX() - player.getX();
        double dz = citizen.getZ() - player.getZ();
        double dy = citizen.getY() - player.getY();
        int distance = (int) Math.sqrt(dx * dx + dz * dz);

        String arrow = relativeArrow(player.getYRot(), dx, dz);
        String compass = CitizenMarkers.bearing(dx, dz);
        String depth = Math.abs(dy) < 3 ? ""
                : (dy < 0 ? "  ▼" + (int) -dy + " below" : "  ▲" + (int) dy + " above");

        var brain = citizen.getCitizenBrain();
        var task = brain.currentTaskOrNull();
        String doing = task == null ? citizen.getStatusName() : task.type.name();

        ChatFormatting colour = brain.isLost() ? ChatFormatting.RED
                : distance <= 8 ? ChatFormatting.GREEN : ChatFormatting.AQUA;

        return Component.literal(String.format("%s %s  %dm %s%s  %s  [%d,%d,%d]",
                arrow, citizen.getIdentity().name, distance, compass, depth, doing,
                (int) citizen.getX(), (int) citizen.getY(), (int) citizen.getZ()))
                .withStyle(colour);
    }

    /**
     * An arrow pointing at the target from the player's own facing.
     *
     * <p>Minecraft yaw is degrees clockwise from south, which is why this looks
     * like it has a sign error and does not.</p>
     */
    public static String relativeArrow(float playerYaw, double dx, double dz) {
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = targetYaw - playerYaw;
        while (relative < -180) relative += 360;
        while (relative >= 180) relative -= 360;
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int index = (int) Math.round((relative + 360) / 45.0) % 8;
        return arrows[index];
    }
}
