package ai.minecivilization.citizen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Citizens self-organize by joining the role rotation in spawn order.
 * The assignment must be pure and deterministic — no randomness, no
 * duplicated roles for the same population index, no array escapes.
 */
class CitizenIdentityTest {

    /** A source of numbers, so name choice is testable without a world. */
    private static net.minecraft.util.RandomSource fixedRandom(int value) {
        return new net.minecraft.util.RandomSource() {
            @Override
            public net.minecraft.util.RandomSource fork() {
                return this;
            }

            @Override
            public net.minecraft.world.level.levelgen.PositionalRandomFactory forkPositional() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setSeed(long seed) {
            }

            @Override
            public int nextInt() {
                return value;
            }

            @Override
            public int nextInt(int bound) {
                return Math.floorMod(value, bound);
            }

            @Override
            public long nextLong() {
                return value;
            }

            @Override
            public boolean nextBoolean() {
                return false;
            }

            @Override
            public float nextFloat() {
                return 0f;
            }

            @Override
            public double nextDouble() {
                return 0;
            }

            @Override
            public double nextGaussian() {
                return 0;
            }
        };
    }

    @Test
    void namesAreNotHandedOutTwice() {
        // Sixteen citizens drawing uniformly from sixteen names produced four
        // called Hugo. Every command that takes a name then resolves to
        // whichever duplicate comes first, so most of the colony became
        // unaddressable — you could not inspect, track or rescue who you meant.
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < 16; i++) {
            String name = CitizenIdentity.uniqueName(fixedRandom(i), used);
            assertTrue(used.add(name), "name handed out twice: " + name);
        }
        assertEquals(16, used.size());
    }

    @Test
    void anExhaustedNamePoolStartsASecondGeneration() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < 40; i++) {
            String name = CitizenIdentity.uniqueName(fixedRandom(i), used);
            assertTrue(used.add(name), "name handed out twice: " + name);
            // Still something a player can type at a command prompt.
            assertFalse(name.isBlank());
        }
        assertEquals(40, used.size());
    }

    @Test
    void secondGenerationNamesReadAsNames() {
        assertEquals("II", CitizenIdentity.roman(2));
        assertEquals("V", CitizenIdentity.roman(5));
        assertEquals("X", CitizenIdentity.roman(10));
    }


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
