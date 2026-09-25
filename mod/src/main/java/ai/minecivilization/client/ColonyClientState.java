package ai.minecivilization.client;

import ai.minecivilization.network.ColonyPackets;
import ai.minecivilization.network.ModNetwork;
import ai.minecivilization.telemetry.ColonySnapshot;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * The client's copy of the colony.
 *
 * <p>One snapshot, replaced whenever the server sends a newer one. The window
 * reads it and never asks the world for anything, which is what keeps the
 * rendering code free of any question about what the client is and is not
 * allowed to know.</p>
 *
 * <p>Client-only: reached from the packet handler through {@code DistExecutor},
 * so a dedicated server never loads it.</p>
 */
public final class ColonyClientState {

    /** How often the open window asks for fresh data, in milliseconds. */
    private static final long REFRESH_INTERVAL_MS = 1000;

    @Nullable
    private static volatile ColonySnapshot snapshot;
    private static volatile long receivedAtMs;
    private static long lastRequestAtMs;

    private ColonyClientState() {
    }

    /** Called on the client thread when a snapshot arrives. */
    public static void accept(ColonySnapshot incoming) {
        snapshot = incoming;
        receivedAtMs = System.currentTimeMillis();
    }

    @Nullable
    public static ColonySnapshot snapshot() {
        return snapshot;
    }

    /** True while we have never heard from the server. */
    public static boolean isWaiting() {
        return snapshot == null;
    }

    /** Milliseconds since the data on screen was current. */
    public static long ageMs() {
        return receivedAtMs == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - receivedAtMs;
    }

    /**
     * Ask for fresh data, at most once per interval.
     *
     * <p>Rate limited here rather than at the call site so every caller — the
     * window's render loop included — can simply ask on every frame.</p>
     */
    public static void poll() {
        long now = System.currentTimeMillis();
        if (now - lastRequestAtMs < REFRESH_INTERVAL_MS) return;
        lastRequestAtMs = now;
        ModNetwork.sendToServer(ColonyPackets.Request.refresh());
    }

    /** Ask now, whatever the rate limit says — used right after an action. */
    public static void request(ColonyPackets.Request request) {
        lastRequestAtMs = System.currentTimeMillis();
        ModNetwork.sendToServer(request);
    }

    /** Forget everything on disconnect, so the next world starts clean. */
    public static void clear() {
        snapshot = null;
        receivedAtMs = 0;
        lastRequestAtMs = 0;
    }

    /** Open the supervision window, asking for data on the way. */
    public static void openScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        ModNetwork.sendToServer(ColonyPackets.Request.refresh());
        minecraft.setScreen(new ColonyScreen());
    }
}
