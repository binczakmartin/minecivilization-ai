package ai.minecivilization.construction;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Short-lived, server-thread claims for physical work cells.
 *
 * <p>Claims prevent a whole colony from marching to the same blueprint cell or
 * road tile. They are deliberately not saved data: a disconnected/crashed
 * worker must not leave a permanent lock, and every claim has a bounded TTL as
 * a second line of defence.</p>
 */
public final class WorkClaimStore {

    private record Claim(String owner, long expiresAt, java.util.Set<String> holders) { }

    private static final Map<String, Claim> CLAIMS = new HashMap<>();

    private WorkClaimStore() {
    }

    public static synchronized boolean claim(ServerLevel level, String owner, BlockPos pos,
                                             String kind, long now, long ttlTicks) {
        return claim(level, owner, pos, kind, now, ttlTicks, 1);
    }

    /**
     * Claim a cell that several workers may share.
     *
     * <p>A blueprint cell takes exactly one builder; a workbench takes a few.
     * Both are the same kind of reservation, so they are the same store — only
     * the capacity differs.</p>
     */
    public static synchronized boolean claim(ServerLevel level, String owner, BlockPos pos,
                                             String kind, long now, long ttlTicks,
                                             int capacity) {
        if (level == null || owner == null || pos == null) return false;
        cleanup(now);
        String key = key(level, pos, kind);
        Claim current = CLAIMS.get(key);
        long expiry = now + Math.max(20, ttlTicks);

        if (current == null || current.expiresAt() <= now) {
            CLAIMS.put(key, new Claim(owner, expiry, new java.util.LinkedHashSet<>(
                    java.util.List.of(owner))));
            return true;
        }
        if (current.holders().contains(owner)) {
            CLAIMS.put(key, new Claim(current.owner(), expiry, current.holders()));
            return true;
        }
        if (current.holders().size() >= Math.max(1, capacity)) return false;

        current.holders().add(owner);
        CLAIMS.put(key, new Claim(current.owner(), expiry, current.holders()));
        return true;
    }

    public static synchronized void release(ServerLevel level, String owner, BlockPos pos,
                                            String kind) {
        if (level == null || owner == null || pos == null) return;
        String key = key(level, pos, kind);
        Claim current = CLAIMS.get(key);
        if (current == null) return;
        current.holders().remove(owner);
        if (current.holders().isEmpty()) CLAIMS.remove(key);
    }

    public static synchronized void releaseOwner(String owner) {
        if (owner == null) return;
        Iterator<Map.Entry<String, Claim>> it = CLAIMS.entrySet().iterator();
        while (it.hasNext()) {
            Claim claim = it.next().getValue();
            claim.holders().remove(owner);
            if (claim.holders().isEmpty()) it.remove();
        }
    }

    public static synchronized void clear() {
        CLAIMS.clear();
    }

    public static synchronized int size() {
        return CLAIMS.size();
    }

    private static void cleanup(long now) {
        Iterator<Map.Entry<String, Claim>> it = CLAIMS.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt() <= now) it.remove();
        }
    }

    private static String key(ServerLevel level, BlockPos pos, String kind) {
        return level.dimension().location() + "|" + kind + "|" + pos.asLong();
    }
}
