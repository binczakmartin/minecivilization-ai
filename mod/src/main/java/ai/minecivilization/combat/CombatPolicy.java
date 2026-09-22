package ai.minecivilization.combat;

/**
 * Deterministic self-defence rules (pure math — unit tested without
 * Minecraft).
 *
 * <p>Combat is a survival reflex, not a policy decision: it must react
 * within a tick, so it never asks the AI service. Personality still shapes
 * it through the risk-tolerance radius.</p>
 */
public final class CombatPolicy {

    private CombatPolicy() {
    }

    /** Personality-scaled engagement radius: risk tolerance 0..1 → 0.5x..1.5x. */
    public static double effectiveRadius(double baseRadius, float riskTolerance) {
        float t = Math.min(1.0f, Math.max(0.0f, riskTolerance));
        return baseRadius * (0.5 + t);
    }

    /**
     * Whether the citizen should attack this hostile right now.
     *
     * <ul>
     *   <li>combat disabled → never;</li>
     *   <li>the mob that just hurt us → yes, while within the attacker leash
     *       (self-defence overrides starvation);</li>
     *   <li>starving citizens do not pick fights;</li>
     *   <li>otherwise only inside the personality-scaled radius.</li>
     * </ul>
     *
     * @param distanceSqr     squared distance to the candidate (blocks²)
     * @param effectiveRadius personality-scaled engagement radius (blocks)
     * @param attackerLeash   how far the mob that hit us may still be chased (blocks)
     */
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
