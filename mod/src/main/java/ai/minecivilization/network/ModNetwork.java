package ai.minecivilization.network;

import ai.minecivilization.MineCivilization;
import ai.minecivilization.colony.CitizenMarkers;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.telemetry.CitizenTracker;
import ai.minecivilization.telemetry.ColonySnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;
import org.jetbrains.annotations.Nullable;

/**
 * The mod's own channel between server and client.
 *
 * <p>Only the supervision window uses it. Everything else the mod does is
 * server-side by design — the citizens are server entities and their decisions
 * never leave the server — but a window that shows the colony has to be told
 * what the colony is, and the client genuinely does not know.</p>
 *
 * <p>Marked optional in both directions: a vanilla client can still join a
 * server running this mod, and a client running it can still join a vanilla
 * server. It simply has no colony to look at.</p>
 */
public final class ModNetwork {

    private static final int PROTOCOL = 1;

    private static SimpleChannel channel;

    /**
     * The last snapshot taken, reused for a moment.
     *
     * <p>Taking one walks every container and every citizen in the colony.
     * Every viewer asks once a second, and with several players watching that
     * becomes several full walks per second for data that is identical each
     * time. One walk per window is plenty.</p>
     */
    private static ColonySnapshot cached;
    private static long cachedAtTick = Long.MIN_VALUE;
    /** Ticks a snapshot stays fresh. Half a second — below one refresh cycle. */
    private static final int CACHE_TICKS = 10;

    private ModNetwork() {
    }

    /** Called once during common setup, on the mod event bus. */
    public static void register() {
        if (channel != null) return;
        channel = ChannelBuilder
                .named(ResourceLocation.fromNamespaceAndPath(MineCivilization.MOD_ID, "colony"))
                .networkProtocolVersion(PROTOCOL)
                .optional()
                .simpleChannel();

        channel.messageBuilder(ColonyPackets.Snapshot.class)
                .encoder(ColonyPackets.Snapshot::encode)
                .decoder(ColonyPackets.Snapshot::decode)
                .consumerMainThread((message, context) -> {
                    // The handler lives in a client-only class reached through
                    // DistExecutor, so a dedicated server never loads a single
                    // rendering class in order to register this packet.
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> ai.minecivilization.client.ColonyClientState
                                    .accept(message.colony()));
                })
                .add();

        channel.messageBuilder(ColonyPackets.Request.class)
                .encoder(ColonyPackets.Request::encode)
                .decoder(ColonyPackets.Request::decode)
                .consumerMainThread((message, context) ->
                        handleRequest(message, context.getSender()))
                .add();
    }

    // ------------------------------------------------------------------ sending

    /** Send this player the colony as it stands. */
    public static void sendSnapshot(ServerPlayer player) {
        if (channel == null || player == null) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        channel.send(new ColonyPackets.Snapshot(snapshotFor(level)),
                PacketDistributor.PLAYER.with(player));
    }

    private static synchronized ColonySnapshot snapshotFor(ServerLevel level) {
        long now = level.getGameTime();
        if (cached != null && now - cachedAtTick < CACHE_TICKS && now >= cachedAtTick) {
            return cached;
        }
        cached = ColonySnapshot.take(level);
        cachedAtTick = now;
        return cached;
    }

    /** Drop the cache — the world changed out from under it. */
    public static synchronized void reset() {
        cached = null;
        cachedAtTick = Long.MIN_VALUE;
    }

    /** Client side: ask the server for something. */
    public static void sendToServer(ColonyPackets.Request request) {
        if (channel == null) return;
        channel.send(request, PacketDistributor.SERVER.noArg());
    }

    // ------------------------------------------------------------------ handling

    /**
     * Act on what a player asked the window for.
     *
     * <p>Every action here is one a player could already perform with
     * {@code /mciv}, and the same permission applies: the window is a
     * convenience, not a way around the command's op requirement.</p>
     */
    private static void handleRequest(ColonyPackets.Request request, @Nullable ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return;

        switch (request.action()) {
            case REFRESH -> { }
            case UNTRACK -> {
                CitizenTracker.stop(player);
                player.sendSystemMessage(Component.literal("Tracking off."));
            }
            case HIGHLIGHT -> {
                if (!player.hasPermissions(2)) return;
                CitizenMarkers.setHighlighted(!CitizenMarkers.highlighted());
            }
            case TRACK, RESCUE, THINK -> {
                CitizenEntity citizen = byName(request.citizen());
                if (citizen == null) {
                    player.sendSystemMessage(Component.literal(
                            "No citizen called '" + request.citizen() + "' any more."));
                    break;
                }
                switch (request.action()) {
                    case TRACK -> {
                        CitizenTracker.track(player, citizen, level.getGameTime());
                        player.sendSystemMessage(Component.literal(
                                "Tracking " + citizen.getIdentity().name + "."));
                    }
                    case RESCUE -> {
                        if (!player.hasPermissions(2)) return;
                        citizen.setWorkAllowed(true);
                        boolean engaged = citizen.getCitizenBrain().forceRescue(level, citizen);
                        player.sendSystemMessage(Component.literal(engaged
                                ? citizen.getIdentity().name + " is heading home."
                                : citizen.getIdentity().name + " is already home."));
                    }
                    case THINK -> {
                        if (!player.hasPermissions(2)) return;
                        citizen.setWorkAllowed(true);
                        citizen.getCitizenBrain().forceDecision(level, citizen);
                    }
                    default -> { }
                }
            }
        }
        // Whatever was asked, answer with the current state so the window
        // reflects the change immediately rather than on its next poll.
        sendSnapshot(player);
    }

    @Nullable
    private static CitizenEntity byName(String name) {
        if (name == null || name.isBlank()) return null;
        for (CitizenEntity citizen : CitizenIndex.all()) {
            if (name.equals(citizen.getIdentity().name)) return citizen;
        }
        return null;
    }
}
