package ai.minecivilization.network;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import ai.minecivilization.MineCivilization;
import ai.minecivilization.citizen.CitizenPlan;
import ai.minecivilization.config.ModConfig;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.skills.SkillFailure;
import org.slf4j.Logger;

/**
 * Async Forge ⇄ AI service bridge.
 *
 * <p>All requests run off the server thread (java.net.http async), have short
 * timeouts, validate responses strictly, and enqueue results for safe
 * application on the server thread. A circuit breaker degrades gracefully when
 * the AI service is offline — Minecraft stays fully playable.</p>
 */
public final class AiBridge {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            // Force HTTP/1.1: java.net.http defaults to HTTP/2 cleartext (h2c)
            // "Upgrade: h2c" which uvicorn/h11 does not support — it answers 400
            // and the circuit breaker opens after 3 tries (citizens never decide).
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** Callbacks/results waiting for the Minecraft server thread. */
    private static final ConcurrentLinkedQueue<Runnable> MAIN_THREAD = new ConcurrentLinkedQueue<>();

    /** Circuit breaker: N consecutive failures -> degraded mode with retry window. */
    private static final int FAILURE_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 30_000;
    private static final long REGISTER_MIN_INTERVAL_MS = 5_000;
    private static final AtomicInteger consecutiveFailures = new AtomicInteger();
    private static volatile long openUntil = 0;
    private static volatile boolean everSucceeded = false;
    private static volatile long lastLatencyMs = -1;
    /** Per citizen, never colony-wide: one citizen must not hold up every other. */
    private static final Map<UUID, Long> lastRegisterAttempt = new ConcurrentHashMap<>();
    private static volatile String lastError = "";
    private static final Map<String, Boolean> loggedOnce = new ConcurrentHashMap<>();

    private AiBridge() {
    }

    // ------------------------------------------------------------------ circuit

    public static boolean shouldSkip() {
        return System.currentTimeMillis() < openUntil;
    }

    public static boolean isDegraded() {
        return shouldSkip();
    }

    public static String statusString() {
        if (shouldSkip()) {
            long secs = Math.max(0, (openUntil - System.currentTimeMillis()) / 1000);
            return "DEGRADED (retry in " + secs + "s) lastError=" + lastError;
        }
        return "CONNECTED lastLatency=" + lastLatencyMs + "ms"
                + (everSucceeded ? "" : " (untested)");
    }

    public static void reconnect() {
        consecutiveFailures.set(0);
        openUntil = 0;
        lastError = "";
        lastRegisterAttempt.clear();
        loggedOnce.clear();
        LOGGER.info("[Circuit] reset — will retry AI service immediately");
        healthProbe();
    }

    public static long getLastLatencyMs() {
        return lastLatencyMs;
    }

    public static int pendingMainThreadTasks() {
        return MAIN_THREAD.size();
    }

    // ------------------------------------------------------------------ main thread drain

    /** Called every server tick (END phase) on the server thread. */
    public static void drain() {
        Runnable runnable;
        int budget = 64; // bounded per tick
        while (budget-- > 0 && (runnable = MAIN_THREAD.poll()) != null) {
            try {
                runnable.run();
            } catch (Exception ex) {
                LOGGER.error("[Cognition] failed to apply AI result", ex);
            }
        }
    }

    public static void enqueue(Runnable r) {
        MAIN_THREAD.add(r);
    }

    // ------------------------------------------------------------------ HTTP

    private static String baseUrl() {
        return ModConfig.aiBaseUrl();
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofMillis(ModConfig.AI_TIMEOUT_MS.get()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ModConfig.aiToken());
    }

    private static void onFailure(String what, Throwable error, long startNanos) {
        int failures = consecutiveFailures.incrementAndGet();
        lastError = what + ": " + (error == null ? "?" : error.getMessage());
        if (failures >= FAILURE_THRESHOLD) {
            openUntil = System.currentTimeMillis() + COOLDOWN_MS;
            if (loggedOnce.putIfAbsent("open", Boolean.TRUE) == null) {
                LOGGER.warn("[Circuit] AI service unavailable ({} consecutive failures) — "
                        + "degraded mode for {}s. Citizens continue deterministically.",
                        failures, COOLDOWN_MS / 1000);
            }
        } else if (ModConfig.debug()) {
            LOGGER.debug("[Cognition] {} failed: {}", what, lastError);
        }
    }

    private static void onSuccess(long startNanos) {
        lastLatencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (consecutiveFailures.get() > 0 || openUntil > 0) {
            LOGGER.info("[Circuit] AI service reachable again ({}ms)", lastLatencyMs);
        }
        consecutiveFailures.set(0);
        openUntil = 0;
        everSucceeded = true;
        loggedOnce.remove("open");
    }

    private static void healthProbe() {
        long start = System.nanoTime();
        CLIENT.sendAsync(request("/health").GET().build(), HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() == 200) onSuccess(start);
                    else onFailure("health HTTP " + resp.statusCode(), null, start);
                })
                .exceptionally(ex -> {
                    onFailure("health", ex.getCause() == null ? ex : ex.getCause(), start);
                    return null;
                });
    }

    // ------------------------------------------------------------------ citizen API

    public static void registerCitizen(CitizenEntity citizen) {
        if (shouldSkip()) return;
        long now = System.currentTimeMillis();
        UUID citizenId = citizen.getIdentity().citizenId;
        Long lastAttempt = lastRegisterAttempt.get(citizenId);
        if (lastAttempt != null && now - lastAttempt < REGISTER_MIN_INTERVAL_MS) {
            return; // throttle this citizen, never the whole colony
        }
        lastRegisterAttempt.put(citizenId, now);
        JsonObject body = new JsonObject();
        body.addProperty("citizen_id", citizen.getIdentity().citizenId.toString());
        body.addProperty("name", citizen.getIdentity().name);
        body.addProperty("profession", citizen.getIdentity().profession);
        body.addProperty("position", (int) citizen.getX() + "," + (int) citizen.getY()
                + "," + (int) citizen.getZ());
        JsonObject personality = new JsonObject();
        var p = citizen.getPersonality();
        personality.addProperty("curiosity", p.curiosity);
        personality.addProperty("ambition", p.ambition);
        personality.addProperty("sociability", p.sociability);
        personality.addProperty("patience", p.patience);
        personality.addProperty("risk_tolerance", p.riskTolerance);
        personality.addProperty("cooperation", p.cooperation);
        personality.addProperty("creativity", p.creativity);
        body.add("personality", personality);
        JsonObject skills = new JsonObject();
        var s = citizen.getSkills();
        skills.addProperty("mining", s.mining);
        skills.addProperty("farming", s.farming);
        skills.addProperty("building", s.building);
        skills.addProperty("crafting", s.crafting);
        skills.addProperty("logistics", s.logistics);
        skills.addProperty("redstone", s.redstone);
        skills.addProperty("architecture", s.architecture);
        skills.addProperty("research", s.research);
        skills.addProperty("trading", s.trading);
        body.add("skills", skills);

        long start = System.nanoTime();
        CLIENT.sendAsync(request("/v1/citizens/register")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        onSuccess(start);
                        lastRegisterAttempt.remove(citizenId);
                        // flag flips on the server thread — HTTP threads never touch world state
                        enqueue(() -> citizen.setRegisteredWithService(true));
                        LOGGER.debug("[Cognition] citizen registered: {}",
                                citizen.getIdentity().name);
                    } else {
                        onFailure("register HTTP " + resp.statusCode(), null, start);
                    }
                })
                .exceptionally(ex -> {
                    onFailure("register", ex.getCause() == null ? ex : ex.getCause(), start);
                    return null;
                });
    }

    /**
     * Async decision request. The callback receives a strictly validated plan
     * (or null when rejected) and is executed on the server thread.
     */
    public static void requestDecision(CitizenEntity citizen, String observationJson,
                                       String priority, String reason, Consumer<CitizenPlan> onPlan) {
        if (shouldSkip()) {
            return;
        }
        JsonObject body = new JsonObject();
        try {
            body.add("observation", JsonParser.parseString(observationJson));
        } catch (RuntimeException ex) {
            LOGGER.error("[Cognition] observation was not valid JSON: {}", ex.getMessage());
            return;
        }
        body.addProperty("priority", priority);
        body.addProperty("reason", reason == null || reason.isBlank() ? "periodic" : reason);

        long start = System.nanoTime();
        String id = citizen.getIdentity().citizenId.toString();
        CLIENT.sendAsync(request("/v1/citizens/" + id + "/decision")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() == 429) {
                        // Backpressure proves the service is alive. Counting it
                        // as an outage opened the circuit precisely when the
                        // service was healthy but briefly busy.
                        if (ModConfig.debug()) {
                            LOGGER.debug("[Cognition] decision backpressure; retry later");
                        }
                        return;
                    }
                    if (resp.statusCode() == 404) {
                        // The world may have survived a service/database reset.
                        // Forget the stale registration and let the brain retry
                        // instead of asking this same unknown id forever.
                        onSuccess(start);
                        enqueue(() -> {
                            citizen.setRegisteredWithService(false);
                            onPlan.accept(null);
                        });
                        return;
                    }
                    if (resp.statusCode() == 503) {
                        onFailure("decision HTTP 503", null, start);
                        return;
                    }
                    if (resp.statusCode() != 200) {
                        onFailure("decision HTTP " + resp.statusCode(), null, start);
                        return;
                    }
                    onSuccess(start);
                    CitizenPlan plan = parseDecision(resp.body());
                    enqueue(() -> onPlan.accept(plan));
                })
                .exceptionally(ex -> {
                    onFailure("decision", ex.getCause() == null ? ex : ex.getCause(), start);
                    return null;
                });
    }

    /** Strict validation: unknown/malformed decisions are rejected (null). */
    static CitizenPlan parseDecision(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (root.has("accepted") && !root.get("accepted").getAsBoolean()) {
                LOGGER.warn("[Cognition] decision rejected by service: {}",
                        root.has("reject_reason") ? root.get("reject_reason").getAsString() : "?");
                return null;
            }
            if (!root.has("decision") || !root.get("decision").isJsonObject()) {
                LOGGER.warn("[Cognition] response missing decision object");
                return null;
            }
            CitizenPlan plan = CitizenPlan.parse(root.getAsJsonObject("decision"));
            if (plan == null) {
                LOGGER.warn("[Cognition] malformed decision rejected (schema violation)");
            }
            return plan;
        } catch (RuntimeException ex) {
            LOGGER.warn("[Cognition] unparseable decision response: {}", ex.getMessage());
            return null;
        }
    }

    public static void postTaskResult(CitizenEntity citizen, ai.minecivilization.citizen.CitizenPlan.Task task,
                                      String outcome, SkillFailure failure, Map<String, Object> details) {
        if (shouldSkip()) return;
        JsonObject body = new JsonObject();
        body.addProperty("task_type", task.type.name());
        body.addProperty("outcome", outcome);
        if (failure != null) {
            JsonObject f = new JsonObject();
            f.addProperty("code", failure.code);
            f.addProperty("message", failure.message);
            f.addProperty("recoverable", failure.recoverable);
            body.add("failure", f);
        }
        body.addProperty("progress", "COMPLETED".equals(outcome) ? 1.0 : 0.0);
        if (details != null && !details.isEmpty()) {
            JsonObject d = new JsonObject();
            details.forEach((key, value) -> d.addProperty(String.valueOf(key), String.valueOf(value)));
            body.add("details", d);
        }

        long start = System.nanoTime();
        String id = citizen.getIdentity().citizenId.toString();
        CLIENT.sendAsync(request("/v1/citizens/" + id + "/task-result")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() == 200) onSuccess(start);
                    else onFailure("task-result HTTP " + resp.statusCode(), null, start);
                })
                .exceptionally(ex -> {
                    onFailure("task-result", ex.getCause() == null ? ex : ex.getCause(), start);
                    return null;
                });
    }

    public static void postEvent(CitizenEntity citizen, String type, JsonObject payload) {
        if (shouldSkip()) return;
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        if (citizen != null) {
            body.addProperty("citizen_id", citizen.getIdentity().citizenId.toString());
        }
        body.add("payload", payload == null ? new JsonObject() : payload);
        long start = System.nanoTime();
        CLIENT.sendAsync(request("/v1/events")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() == 200) onSuccess(start);
                    else onFailure("event HTTP " + resp.statusCode(), null, start);
                })
                .exceptionally(ex -> {
                    onFailure("event", ex.getCause() == null ? ex : ex.getCause(), start);
                    return null;
                });
    }

    public static void logStatusOnce() {
        if (loggedOnce.putIfAbsent("status", Boolean.TRUE) == null) {
            LOGGER.info("[Cognition] bridge ready -> {}", baseUrl());
        }
    }
}
