package ai.minecivilization.network;

import org.junit.jupiter.api.Test;

import ai.minecivilization.citizen.CitizenPlan;
import ai.minecivilization.citizen.CitizenPlan.TaskType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The strict Java-side validation applied to every decision response. */
class AiBridgeParseDecisionTest {

    @Test
    void acceptsValidDecision() {
        CitizenPlan plan = AiBridge.parseDecision("""
                {"accepted": true,
                 "decision": {
                   "reasoning_summary": "stockpiling wood",
                   "goal": {"type": "GATHER_RESOURCE", "resource": "minecraft:oak_log",
                            "target_quantity": 16},
                   "tasks": [{"type": "GATHER", "resource": "minecraft:oak_log",
                              "quantity": 16}]}}""");
        assertNotNull(plan);
        assertEquals(TaskType.GATHER, plan.tasks.get(0).type);
        assertEquals(16, plan.goal.targetQuantity);
    }

    @Test
    void acceptsResponseWithoutAcceptedFlag() {
        CitizenPlan plan = AiBridge.parseDecision("""
                {"decision": {"reasoning_summary": "rest",
                 "goal": {"type": "REST"}, "tasks": [{"type": "REST"}]}}""");
        assertNotNull(plan);
    }

    @Test
    void rejectsServiceSideRejection() {
        assertNull(AiBridge.parseDecision(
                "{\"accepted\": false, \"reject_reason\": \"validator said no\"}"));
    }

    @Test
    void rejectsMissingDecisionObject() {
        assertNull(AiBridge.parseDecision("{\"accepted\": true}"));
    }

    @Test
    void rejectsSchemaViolation() {
        assertNull(AiBridge.parseDecision("""
                {"accepted": true,
                 "decision": {"reasoning_summary": "x",
                  "goal": {"type": "NOT_A_GOAL"}, "tasks": []}}"""));
    }

    @Test
    void rejectsGarbageWithoutThrowing() {
        assertNull(AiBridge.parseDecision("this is not json"));
        assertNull(AiBridge.parseDecision(""));
        assertNull(AiBridge.parseDecision("{\"decision\": null}"));
    }
}
