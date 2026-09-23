package ai.minecivilization.combat;

import java.util.Set;

/**
 * Deterministic self-defence rules (pure math — unit tested without
 * Minecraft).
 *
 * <p>Combat is a survival reflex, not a policy decision: it must react within
 * a tick, so it never asks the AI service. Personality still shapes it through
 * the risk-tolerance radius.</p>
 *
 * <p>The rule that matters most is knowing which fights not to take. Citizens
 * used to charge anything hostile within range, including creepers — which is
 * a trade of one citizen for one creeper, every time, and a settlement cannot
 * afford it. A threat is now <em>assessed</em> before it is engaged: some are
 * fought, some are fled, and some are simply left alone.</p>
 */
public final class CombatPolicy {

    /** What kind of danger a mob represents. */
    public enum ThreatKind {
        /** Ordinary hostiles a healthy armed citizen can beat. */
        MELEE,
        /** Shoots from a distance; closing the gap is still the right answer. */
        RANGED,
        /** Kills whoever is standing next to it when it dies. Never melee one. */
        EXPLOSIVE,
        /** Harmless until provoked — attacking one creates the problem. */
        NEUTRAL,
        /** Cannot be beaten by a villager with a stone axe. Leave. */
        DEADLY
    }

    /** What to do about it. */
    public enum Response {
        /** Carry on working. */
        IGNORE,
        /** Close and attack. */
        ENGAGE,
        /** Put distance between us and it. */
        FLEE
    }

    /** How close a creeper may get before a citizen walks away from it. */
    public static final double BLAST_DANGER_RADIUS = 8.0;
    /** Distance kept from something that cannot be fought at all. */
    public static final double DEADLY_AVOID_RADIUS = 24.0;
    /** Below this share of maximum health, every fight is the wrong fight. */
    public static final float LOW_HEALTH_FRACTION = 0.4f;
    /**
     * Inside this range an attacker is already landing blows, and running only
     * buys it free hits at your back. Endermen made this obvious: they
     * teleport, so retreat never works, and unarmed citizens backed away until
     * they died.
     */
    public static final double CORNERED_RANGE = 5.0;

    private static final Set<String> EXPLOSIVE = Set.of("minecraft:creeper");

    private static final Set<String> DEADLY = Set.of(
            "minecraft:warden", "minecraft:wither", "minecraft:ender_dragon",
            "minecraft:ravager", "minecraft:elder_guardian", "minecraft:piglin_brute");

    private static final Set<String> NEUTRAL = Set.of(
            "minecraft:enderman", "minecraft:zombified_piglin", "minecraft:piglin");

    private static final Set<String> RANGED = Set.of(
            "minecraft:skeleton", "minecraft:stray", "minecraft:bogged",
            "minecraft:blaze", "minecraft:ghast", "minecraft:witch",
            "minecraft:pillager", "minecraft:shulker", "minecraft:breeze");

    private CombatPolicy() {
    }

    /** Personality-scaled engagement radius: risk tolerance 0..1 → 0.5x..1.5x. */
    public static double effectiveRadius(double baseRadius, float riskTolerance) {
        float t = Math.min(1.0f, Math.max(0.0f, riskTolerance));
        return baseRadius * (0.5 + t);
    }

    /** What kind of danger this entity type is. Unknown hostiles count as melee. */
    public static ThreatKind classify(String entityTypeId) {
        if (entityTypeId == null) return ThreatKind.MELEE;
        if (EXPLOSIVE.contains(entityTypeId)) return ThreatKind.EXPLOSIVE;
        if (DEADLY.contains(entityTypeId)) return ThreatKind.DEADLY;
        if (NEUTRAL.contains(entityTypeId)) return ThreatKind.NEUTRAL;
        if (RANGED.contains(entityTypeId)) return ThreatKind.RANGED;
        return ThreatKind.MELEE;
    }

    /**
     * What to do about one nearby hostile.
     *
     * <p>Ordered so that the reasons to <em>not</em> fight are considered
     * first. A citizen at a third of its health, or standing next to a
     * creeper, has no business weighing engagement radii.</p>
     *
     * @param distanceSqr      squared distance to the candidate (blocks²)
     * @param engageRadius     personality-scaled engagement radius (blocks)
     * @param attackerLeash    how far the mob that hit us may still be chased
     * @param isRecentAttacker this mob hurt us moments ago
     * @param healthFraction   current health as a share of maximum, 0..1
     * @param armed            carrying a weapon worth swinging
     */
    public static Response assess(boolean combatEnabled,
                                  ThreatKind kind,
                                  double distanceSqr,
                                  double engageRadius,
                                  double attackerLeash,
                                  boolean isRecentAttacker,
                                  float healthFraction,
                                  boolean starving,
                                  boolean armed) {
        if (!combatEnabled) return Response.IGNORE;

        // 1. Some things are simply not fights. Distance is the only answer,
        //    and being attacked first does not change that.
        if (kind == ThreatKind.DEADLY) {
            return within(distanceSqr, DEADLY_AVOID_RADIUS) ? Response.FLEE : Response.IGNORE;
        }
        // 2. A creeper killed in melee kills its killer. Never engage one —
        //    back away and let it wander off.
        if (kind == ThreatKind.EXPLOSIVE) {
            return within(distanceSqr, BLAST_DANGER_RADIUS) ? Response.FLEE : Response.IGNORE;
        }

        double alarmRadius = Math.max(engageRadius, isRecentAttacker ? attackerLeash : 0.0);

        // 3. Already being hit, at arm's length: fight, whatever we are holding
        //    and however hurt we are. Turning your back on something that has
        //    closed to melee is strictly worse than swinging at it — and
        //    against a teleporting enderman, retreat is not an option at all.
        if (isRecentAttacker && within(distanceSqr, CORNERED_RANGE)) {
            return Response.ENGAGE;
        }
        // 4. Badly hurt, with room to break off: disengage from anything.
        //    A dead citizen wins no fights later.
        if (healthFraction <= LOW_HEALTH_FRACTION) {
            return within(distanceSqr, alarmRadius) ? Response.FLEE : Response.IGNORE;
        }
        // 5. Bare hands lose a fight you chose. Retreat rather than trade a
        //    citizen for nothing — but only while there is still distance to use.
        if (!armed) {
            return within(distanceSqr, alarmRadius) ? Response.FLEE : Response.IGNORE;
        }
        // 6. Endermen and their kind are a problem only if you make one.
        if (kind == ThreatKind.NEUTRAL && !isRecentAttacker) {
            return Response.IGNORE;
        }
        // 7. Self-defence overrides hunger: being hit is not negotiable.
        if (isRecentAttacker && within(distanceSqr, attackerLeash)) {
            return Response.ENGAGE;
        }
        if (starving) return Response.IGNORE;
        return within(distanceSqr, engageRadius) ? Response.ENGAGE : Response.IGNORE;
    }

    private static boolean within(double distanceSqr, double radius) {
        return distanceSqr <= radius * radius;
    }

    /**
     * How far is far enough to stop running from a given kind of threat.
     *
     * <p>Retreat has to end somewhere, and the distance depends on what is
     * being fled: a creeper stops mattering at about twice its blast, while a
     * warden matters for a lot longer. Using one distance for everything left
     * citizens backing away from a creeper twelve blocks behind them, forever,
     * never working again.</p>
     */
    public static double safeDistance(ThreatKind kind, double engageRadius) {
        return switch (kind) {
            case DEADLY -> DEADLY_AVOID_RADIUS;
            case EXPLOSIVE -> BLAST_DANGER_RADIUS + 4.0;
            // Fled because we are hurt or unarmed: out of its notice is enough.
            default -> Math.max(engageRadius, 8.0) + 4.0;
        };
    }

    /**
     * Ticks a citizen will keep retreating before going back to work.
     *
     * <p>A threat it cannot get away from — one on the far side of a wall, or
     * following it around a pen — would otherwise keep a citizen running for
     * the rest of its life.</p>
     */
    public static final int MAX_FLEE_TICKS = 200;

    /**
     * Legacy yes/no engagement test, kept for the existing callers and tests.
     *
     * @deprecated prefer {@link #assess} — it can also answer "run away".
     */
    @Deprecated
    public static boolean shouldEngage(boolean combatEnabled,
                                       double distanceSqr,
                                       double effectiveRadius,
                                       double attackerLeash,
                                       boolean isRecentAttacker,
                                       boolean starving) {
        if (!combatEnabled) return false;
        if (isRecentAttacker && distanceSqr <= attackerLeash * attackerLeash) {
            return true;
        }
        if (starving) return false;
        return distanceSqr <= effectiveRadius * effectiveRadius;
    }

    /** Attack cooldown elapsed? */
    public static boolean canAttack(long now, long nextAttackAt) {
        return now >= nextAttackAt;
    }

    /** Chase repath throttle. */
    public static boolean shouldRepath(long now, long lastRepathAt, int intervalTicks) {
        return now - lastRepathAt >= intervalTicks;
    }
}
