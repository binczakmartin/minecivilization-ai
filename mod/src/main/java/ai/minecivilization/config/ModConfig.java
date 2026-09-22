package ai.minecivilization.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Common config. The AI bridge is always localhost; internet is never required.
 *
 * <p>Endpoint and token are resolved at runtime (system property → environment
 * variable → local default) instead of living in the generated config file, so
 * a launcher script can point the mod at the service without editing anything.</p>
 */
public final class ModConfig {
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8765";
    public static final String DEFAULT_TOKEN = "dev-local-token";

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue AI_ENABLED = BUILDER
            .comment("Allow citizens to request decisions from the local AI service")
            .define("ai.enabled", true);

    public static final ForgeConfigSpec.IntValue AI_TIMEOUT_MS = BUILDER
            .comment("HTTP timeout for AI requests (never blocks the tick thread)")
            .defineInRange("ai.timeoutMs", 8000, 500, 60000);

    public static final ForgeConfigSpec.IntValue DECISION_COOLDOWN_TICKS = BUILDER
            .comment("Minimum ticks between cognition requests per citizen")
            .defineInRange("ai.decisionCooldownTicks", 400, 20, 72000);

    public static final ForgeConfigSpec.IntValue SKILL_TIMEOUT_TICKS = BUILDER
            .comment("Default per-skill timeout in ticks")
            .defineInRange("skills.timeoutTicks", 1200, 40, 20000);

    public static final ForgeConfigSpec.IntValue MOVE_TIMEOUT_TICKS = BUILDER
            .comment("Navigation timeout before a repath is attempted")
            .defineInRange("skills.moveTimeoutTicks", 200, 20, 2000);

    public static final ForgeConfigSpec.IntValue MAX_REPATHS = BUILDER
            .comment("Repath attempts before TARGET_UNREACHABLE")
            .defineInRange("skills.maxRepaths", 3, 1, 10);

    public static final ForgeConfigSpec.BooleanValue COMBAT_ENABLED = BUILDER
            .comment("Citizens defend themselves against hostile mobs (deterministic local reflex)")
            .define("combat.enabled", true);

    public static final ForgeConfigSpec.DoubleValue COMBAT_TRIGGER_RADIUS = BUILDER
            .comment("Base radius (blocks) in which citizens engage hostile mobs;",
                    "scaled per citizen by personality risk tolerance (0.5x..1.5x)")
            .defineInRange("combat.triggerRadius", 12.0D, 2.0D, 32.0D);

    public static final ForgeConfigSpec.IntValue COMBAT_ATTACK_INTERVAL_TICKS = BUILDER
            .comment("Ticks between melee attacks")
            .defineInRange("combat.attackIntervalTicks", 16, 4, 200);

    public static final ForgeConfigSpec.IntValue COMBAT_CHASE_TIMEOUT_TICKS = BUILDER
            .comment("Ticks without a landed hit before an unreachable target is abandoned")
            .defineInRange("combat.chaseTimeoutTicks", 200, 20, 2000);

    public static final ForgeConfigSpec.IntValue SIMULATION_SEED = BUILDER
            .comment("Seed for deterministic personality derivation")
            .defineInRange("simulation.seed", 42, 0, Integer.MAX_VALUE);

    public static final ForgeConfigSpec.BooleanValue DEBUG = BUILDER
            .comment("Verbose logging of skill/goal transitions (do not log every tick)")
            .define("debug", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    /** Runtime debug toggle (not persisted; set by /mciv debug on|off). */
    public static volatile boolean debugOverride = false;

    public static boolean debug() {
        return DEBUG.get() || debugOverride;
    }

    /** Base URL of the local cognition service: -D prop → env → default. */
    public static String aiBaseUrl() {
        return resolve("baseUrl", "MINECIV_AI_BASE_URL", DEFAULT_BASE_URL)
                .replaceAll("/+$", "");
    }

    /** Bearer token shared with the service (must equal MINECIV_API_TOKEN). */
    public static String aiToken() {
        return resolve("token", "MINECIV_API_TOKEN", DEFAULT_TOKEN);
    }

    private static String resolve(String propertySuffix, String envVar, String fallback) {
        String property = System.getProperty("minecivilization.ai." + propertySuffix);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv(envVar);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return fallback;
    }

    private ModConfig() {
    }
}
