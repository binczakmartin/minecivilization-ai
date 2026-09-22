package ai.minecivilization.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Self-defence rules — pure math, no Minecraft bootstrap. */
class CombatPolicyTest {

    // ---------------------------------------------------------------- radius

    @Test
    void radiusScalesWithRiskTolerance() {
        assertEquals(6.0, CombatPolicy.effectiveRadius(12.0, 0.0f), 1e-9);
        assertEquals(12.0, CombatPolicy.effectiveRadius(12.0, 0.5f), 1e-9);
        assertEquals(18.0, CombatPolicy.effectiveRadius(12.0, 1.0f), 1e-9);
    }

    @Test
    void radiusClampsOutOfRangeRiskTolerance() {
        assertEquals(6.0, CombatPolicy.effectiveRadius(12.0, -3.0f), 1e-9);
        assertEquals(18.0, CombatPolicy.effectiveRadius(12.0, 42.0f), 1e-9);
    }

    // ---------------------------------------------------------------- shouldEngage

    @Test
    void disabledCombatNeverEngages() {
        assertFalse(CombatPolicy.shouldEngage(false, 0.0, 12.0, 16.0, true, false));
    }

    @Test
    void attackerIsEngagedWithinLeashEvenWhenStarving() {
        // 10 blocks away (100 sqr) <= 16-block leash, starving: still fight back
        assertTrue(CombatPolicy.shouldEngage(true, 100.0, 6.0, 16.0, true, true));
        // 20 blocks away (400 sqr) > leash, starving: let it go
        assertFalse(CombatPolicy.shouldEngage(true, 400.0, 6.0, 16.0, true, true));
    }

    @Test
    void starvingCitizensDoNotPickFights() {
        // 2 blocks away, inside the radius, but not the attacker
        assertFalse(CombatPolicy.shouldEngage(true, 4.0, 6.0, 16.0, false, true));
    }

    @Test
    void engagesInsidePersonalityRadiusOnly() {
        assertTrue(CombatPolicy.shouldEngage(true, 36.0, 6.0, 16.0, false, false));  // boundary
        assertFalse(CombatPolicy.shouldEngage(true, 36.0001, 6.0, 16.0, false, false));
        assertFalse(CombatPolicy.shouldEngage(true, 100.0, 6.0, 16.0, false, false));
    }

    @Test
    void attackerBeyondLeashFallsBackToTheRadiusRule() {
        // 20 blocks away (400 sqr): beyond the 16-block leash, but inside a
        // 24-block radius → the normal radius rule keeps the fight on
        assertTrue(CombatPolicy.shouldEngage(true, 400.0, 24.0, 16.0, true, false));
        // …and outside a small radius → disengage
        assertFalse(CombatPolicy.shouldEngage(true, 400.0, 6.0, 16.0, true, false));
    }

    // ---------------------------------------------------------------- timing

    @Test
    void attackCooldownElapsed() {
        assertTrue(CombatPolicy.canAttack(10, 10));
        assertFalse(CombatPolicy.canAttack(9, 10));
    }

    @Test
    void repathThrottle() {
        assertTrue(CombatPolicy.shouldRepath(15, 5, 10));
        assertFalse(CombatPolicy.shouldRepath(14, 5, 10));
    }
}
