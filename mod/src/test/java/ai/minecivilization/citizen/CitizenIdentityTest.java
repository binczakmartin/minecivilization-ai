package ai.minecivilization.citizen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Citizens self-organize by joining the role rotation in spawn order.
 * The assignment must be pure and deterministic — no randomness, no
 * duplicated roles for the same population index, no array escapes.
 */
class CitizenIdentityTest {

    @Test
    void rolesRotateInSpawnOrder() {
        assertEquals("LUMBERJACK", CitizenIdentity.professionForPopulation(0));
        assertEquals("MINER", CitizenIdentity.professionForPopulation(1));
        assertEquals("FARMER", CitizenIdentity.professionForPopulation(2));
        assertEquals("BUILDER", CitizenIdentity.professionForPopulation(3));
        assertEquals("CRAFTER", CitizenIdentity.professionForPopulation(4));
        // Livestock is what a fed and housed settlement reaches for next — and
        // what unlocks the tier above, since wool makes beds and beds allow births.
        assertEquals("SHEPHERD", CitizenIdentity.professionForPopulation(5));
        // ... and the rotation repeats for the next wave of citizens
        assertEquals("LUMBERJACK", CitizenIdentity.professionForPopulation(6));
        assertEquals("MINER", CitizenIdentity.professionForPopulation(7));
    }

    @Test
    void assignmentNeverLeavesTheRoleList() {
        for (int population = -10; population <= 100; population++) {
            String profession = CitizenIdentity.professionForPopulation(population);
            switch (profession) {
                case "LUMBERJACK", "MINER", "FARMER", "BUILDER", "CRAFTER", "SHEPHERD" -> {
                    // valid role
                }
                default -> throw new AssertionError(
                        "unexpected role " + profession + " for population " + population);
            }
        }
        // floorMod keeps negative inputs inside the array too
        assertEquals("SHEPHERD", CitizenIdentity.professionForPopulation(-1));
    }
}
