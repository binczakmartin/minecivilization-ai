package ai.minecivilization.combat;

import org.junit.jupiter.api.Test;

import static ai.minecivilization.combat.CombatPolicy.Response;
import static ai.minecivilization.combat.CombatPolicy.ThreatKind;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Which fights a citizen takes.
 *
 * <p>Written after a play session in which citizens charged creepers and died
 * to them, one for one.</p>
 */
class ThreatAssessmentTest {

    private static final double RADIUS = 12.0;
    private static final double LEASH = 16.0;

    private static double sqr(double distance) {
        return distance * distance;
    }

    /** A healthy, armed, well-fed citizen — the baseline. */
    private static Response assess(ThreatKind kind, double distance) {
        return CombatPolicy.assess(true, kind, sqr(distance), RADIUS, LEASH,
                false, 1.0f, false, true);
    }

    // ------------------------------------------------------------------ creepers

    @Test
    void aCreeperIsNeverEngaged() {
        // Killing a creeper in melee kills the citizen too: an even trade the
        // settlement cannot afford.
        for (double distance : new double[]{1, 3, 5, 7, 10, 20}) {
            assertNotEquals(Response.ENGAGE, assess(ThreatKind.EXPLOSIVE, distance),
                    "engaged a creeper at " + distance + " blocks");
        }
    }

    @Test
    void aCreeperNearbyIsWalkedAwayFrom() {
        assertEquals(Response.FLEE, assess(ThreatKind.EXPLOSIVE, 3));
        assertEquals(Response.FLEE, assess(ThreatKind.EXPLOSIVE, CombatPolicy.BLAST_DANGER_RADIUS));
        assertEquals(Response.IGNORE, assess(ThreatKind.EXPLOSIVE, 20),
                "a creeper across the field is not a reason to stop working");
    }

    @Test
    void aCreeperThatHitUsIsStillNotFought() {
        Response response = CombatPolicy.assess(true, ThreatKind.EXPLOSIVE, sqr(4), RADIUS, LEASH,
                true, 1.0f, false, true);
        assertEquals(Response.FLEE, response, "retaliating against a creeper is how you die to one");
    }

    // ------------------------------------------------------------------ ordinary fights

    @Test
    void anArmedHealthyCitizenFightsOrdinaryHostiles() {
        assertEquals(Response.ENGAGE, assess(ThreatKind.MELEE, 5));
        assertEquals(Response.ENGAGE, assess(ThreatKind.RANGED, 8),
                "closing on an archer is the right answer");
        assertEquals(Response.IGNORE, assess(ThreatKind.MELEE, 30));
    }

    @Test
    void bareHandedCitizensRetreatInsteadOfTrading() {
        Response response = CombatPolicy.assess(true, ThreatKind.MELEE, sqr(5), RADIUS, LEASH,
                false, 1.0f, false, false);
        assertEquals(Response.FLEE, response, "fists lose; a lost citizen is worse than a lost job");
    }

    @Test
    void aBadlyHurtCitizenFightsOnWhenItIsAlreadyInMelee() {
        // "Disengage when hurt" sounds humane and loses citizens: a zombie is
        // as fast as a villager, so running at arm's length only turns the
        // fight into a chase with free hits in the back. Breaking off is right
        // when there is distance to use — see the test below.
        Response cornered = CombatPolicy.assess(true, ThreatKind.MELEE, sqr(3), RADIUS, LEASH,
                true, 0.2f, false, true);
        assertEquals(Response.ENGAGE, cornered);
    }

    @Test
    void aBadlyHurtCitizenBreaksOffWhenTheFightIsStillAtRange() {
        Response response = CombatPolicy.assess(true, ThreatKind.MELEE, sqr(9), RADIUS, LEASH,
                true, 0.2f, false, true);
        assertEquals(Response.FLEE, response, "a dead citizen wins no fights later");
    }

    @Test
    void selfDefenceOverridesHunger() {
        Response starvingAttacked = CombatPolicy.assess(true, ThreatKind.MELEE, sqr(4),
                RADIUS, LEASH, true, 1.0f, true, true);
        assertEquals(Response.ENGAGE, starvingAttacked, "being hit is not negotiable");

        Response starvingUnprovoked = CombatPolicy.assess(true, ThreatKind.MELEE, sqr(4),
                RADIUS, LEASH, false, 1.0f, true, true);
        assertEquals(Response.IGNORE, starvingUnprovoked, "a starving citizen picks no fights");
    }

    // ------------------------------------------------------------------ the rest

    @Test
    void endermenAreLeftAloneUntilProvoked() {
        assertEquals(Response.IGNORE, assess(ThreatKind.NEUTRAL, 4),
                "attacking an enderman is how you get an enderman problem");

        Response provoked = CombatPolicy.assess(true, ThreatKind.NEUTRAL, sqr(4), RADIUS, LEASH,
                true, 1.0f, false, true);
        assertEquals(Response.ENGAGE, provoked);
    }

    @Test
    void aPeacefulEndermanIsNeitherFoughtNorFled() {
        // Unarmed or hurt used to mean "run from anything hostile", endermen
        // included — they are not hostile until provoked.
        assertEquals(Response.IGNORE, CombatPolicy.assess(true, ThreatKind.NEUTRAL, sqr(4),
                RADIUS, LEASH, false, 1.0f, false, false));
        assertEquals(Response.IGNORE, CombatPolicy.assess(true, ThreatKind.NEUTRAL, sqr(4),
                RADIUS, LEASH, false, 0.2f, false, true));
    }

    @Test
    void unwinnableThingsAreAvoidedAtRange() {
        assertEquals(Response.FLEE, assess(ThreatKind.DEADLY, 20));
        assertEquals(Response.IGNORE, assess(ThreatKind.DEADLY, 40));
        assertNotEquals(Response.ENGAGE, assess(ThreatKind.DEADLY, 2),
                "a villager with a stone axe does not fight a warden");
    }

    @Test
    void combatDisabledMeansNothingHappens() {
        for (ThreatKind kind : ThreatKind.values()) {
            assertEquals(Response.IGNORE, CombatPolicy.assess(false, kind, sqr(2), RADIUS, LEASH,
                    true, 0.1f, false, false), kind + " acted on with combat disabled");
        }
    }

    // ------------------------------------------------------------------ cornered

    @Test
    void somethingAlreadyHittingUsIsFoughtEvenBareHanded() {
        // Turning your back on a mob that has closed to melee buys it free
        // hits. Citizens backed away from endermen until they died.
        Response response = CombatPolicy.assess(true, ThreatKind.MELEE, sqr(2), RADIUS, LEASH,
                true, 1.0f, false, false);
        assertEquals(Response.ENGAGE, response);
    }

    @Test
    void anEndermanThatAttacksIsFoughtRatherThanFledFrom() {
        // It teleports: retreat is not an option, it is just a slower death.
        Response response = CombatPolicy.assess(true, ThreatKind.NEUTRAL, sqr(3), RADIUS, LEASH,
                true, 0.5f, false, false);
        assertEquals(Response.ENGAGE, response);
    }

    @Test
    void aWoundedCitizenStillBreaksOffWhenItHasRoomTo() {
        // The cornered rule is about arm's length, not about giving up on retreat.
        Response response = CombatPolicy.assess(true, ThreatKind.MELEE, sqr(10), RADIUS, LEASH,
                true, 0.2f, false, true);
        assertEquals(Response.FLEE, response);
    }

    @Test
    void beingCorneredNeverMakesACreeperWorthFighting() {
        Response response = CombatPolicy.assess(true, ThreatKind.EXPLOSIVE, sqr(2), RADIUS, LEASH,
                true, 1.0f, false, true);
        assertEquals(Response.FLEE, response, "a creeper at arm's length is the one to run from");
    }

    @Test
    void beingCorneredNeverMakesAWardenWorthFighting() {
        Response response = CombatPolicy.assess(true, ThreatKind.DEADLY, sqr(2), RADIUS, LEASH,
                true, 1.0f, false, true);
        assertEquals(Response.FLEE, response);
    }

    // ------------------------------------------------------------------ classification

    @Test
    void theDangerousMobsAreRecognised() {
        assertEquals(ThreatKind.EXPLOSIVE, CombatPolicy.classify("minecraft:creeper"));
        assertEquals(ThreatKind.DEADLY, CombatPolicy.classify("minecraft:warden"));
        assertEquals(ThreatKind.NEUTRAL, CombatPolicy.classify("minecraft:enderman"));
        assertEquals(ThreatKind.RANGED, CombatPolicy.classify("minecraft:skeleton"));
        assertEquals(ThreatKind.MELEE, CombatPolicy.classify("minecraft:zombie"));
    }

    @Test
    void anUnknownHostileIsTreatedAsAnOrdinaryFight() {
        assertEquals(ThreatKind.MELEE, CombatPolicy.classify("somemod:unknown_beast"));
        assertEquals(ThreatKind.MELEE, CombatPolicy.classify(null));
    }

    @Test
    void theEngagementRadiusStillScalesWithPersonality() {
        assertEquals(6.0, CombatPolicy.effectiveRadius(12.0, 0.0f), 1e-6);
        assertEquals(18.0, CombatPolicy.effectiveRadius(12.0, 1.0f), 1e-6);
    }
}
