package ai.minecivilization.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The skill registry must stay in lockstep with the {@link SkillType} enum. */
class SkillRegistryTest {

    @Test
    void everySkillTypeHasAnImplementation() {
        for (SkillType type : SkillType.values()) {
            assertTrue(SkillRegistry.isRegistered(type), "missing skill: " + type);
            assertDoesNotThrowCreate(type);
        }
    }

    private void assertDoesNotThrowCreate(SkillType type) {
        try {
            SkillRegistry.create(type);
        } catch (RuntimeException ex) {
            throw new AssertionError("SkillRegistry.create failed for " + type, ex);
        }
    }

    @Test
    void createReturnsAFreshInstanceOfTheRightType() {
        for (SkillType type : SkillType.values()) {
            CitizenSkill first = SkillRegistry.create(type);
            assertEquals(type, first.type());
            assertNotSame(first, SkillRegistry.create(type), type + " must be created fresh");
        }
    }

    @Test
    void unknownSkillIsRejected() {
        assertFalse(SkillRegistry.isRegistered(null));
        assertThrows(IllegalArgumentException.class, () -> SkillRegistry.create(null));
    }

    @Test
    void structuredFailuresCarryStableCodes() {
        assertEquals("TARGET_UNREACHABLE", SkillFailure.unreachable("x").code);
        assertEquals("TARGET_NOT_FOUND", SkillFailure.notFound("x").code);
        assertEquals("MISSING_RESOURCE", SkillFailure.missing("x").code);
        assertEquals("TIMEOUT", SkillFailure.timeout("x").code);
        assertEquals("NOT_IMPLEMENTED", SkillFailure.notImplemented("X").code);
        assertTrue(SkillFailure.missing("x").recoverable);
        assertTrue(SkillFailure.notImplemented("X").recoverable);
    }
}
