package ai.minecivilization.work;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * What workers are waiting for, so somebody else can bring it.
 *
 * <p>Every worker used to fetch its own materials: a builder short of planks
 * walked to the warehouse, or to the forest, and the wall waited. A colony
 * works the other way round — the builder keeps building and a runner brings
 * the planks. A request is posted by whoever is short (a builder missing a
 * material, a citizen missing a tool the stores hold) and taken by a courier,
 * who fetches or makes it and hands it over in person.</p>
 */
public final class MaterialRequests {

    /** A request not taken up within this long (two minutes) is dropped. */
    public static final long EXPIRE_TICKS = 2400;
    /** A taken request not delivered within this long is offered again. */
    public static final long CLAIM_TICKS = 2400;

    public static final class Request {
        public final UUID requester;
        public final String item;
        public final int quantity;
        public final String reason;
        public final long postedAt;
        UUID courier;
        long claimedAt;

        Request(UUID requester, String item, int quantity, String reason, long postedAt) {
            this.requester = requester;
            this.item = item;
            this.quantity = quantity;
            this.reason = reason;
            this.postedAt = postedAt;
        }

        public String key() {
            return requester + "|" + item;
        }
    }

    private static final Map<String, Request> OPEN = new LinkedHashMap<>();

    private MaterialRequests() {
    }

    /** Ask for {@code quantity} of {@code item}; repeating a request refreshes it. */
    public static synchronized void post(UUID requester, String item, int quantity,
                                         String reason, long now) {
        if (requester == null || item == null || quantity <= 0) return;
        String key = requester + "|" + item;
        Request existing = OPEN.get(key);
        if (existing != null && existing.courier != null) return;   // already on its way
        if (existing == null) {
            com.mojang.logging.LogUtils.getLogger().info("[Colony] request: {} x{} {}", item, quantity, reason);
        }
        OPEN.put(key, new Request(requester, item, quantity, reason, now));
    }

    /** The oldest request nobody is working on, which {@code courier} now takes. */
    @Nullable
    public static synchronized Request claim(UUID courier, long now,
                                             java.util.function.Predicate<Request> canServe) {
        expire(now);
        for (Request request : OPEN.values()) {
            if (request.requester.equals(courier)) continue;
            if (request.courier != null) continue;
            if (!canServe.test(request)) continue;
            request.courier = courier;
            request.claimedAt = now;
            return request;
        }
        return null;
    }

    /** Delivered (or given up): the request is closed. */
    public static synchronized void close(UUID requester, String item) {
        OPEN.remove(requester + "|" + item);
    }

    /** The courier could not do it: offer the request to someone else. */
    public static synchronized void release(UUID courier) {
        for (Request request : OPEN.values()) {
            if (courier.equals(request.courier)) request.courier = null;
        }
    }

    public static synchronized List<Request> snapshot() {
        return new ArrayList<>(OPEN.values());
    }

    public static synchronized void clear() {
        OPEN.clear();
    }

    private static void expire(long now) {
        Iterator<Request> it = OPEN.values().iterator();
        while (it.hasNext()) {
            Request request = it.next();
            if (request.courier == null && now - request.postedAt > EXPIRE_TICKS) it.remove();
            else if (request.courier != null && now - request.claimedAt > CLAIM_TICKS) request.courier = null;
        }
    }
}
