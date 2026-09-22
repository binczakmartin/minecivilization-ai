package ai.minecivilization.citizen;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import ai.minecivilization.citizen.CitizenPlan.GoalType;
import ai.minecivilization.citizen.CitizenPlan.TaskType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision schema is the contract between the Python service and the game.
 * Anything unknown, malformed or impossible must be rejected before it can
 * influence the world.
 */
class CitizenPlanParseTest {

    private static CitizenPlan parse(String json) {
        return CitizenPlan.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static final String VALID = """
            {
              "reasoning_summary": "wood is the first prerequisite",
              "goal": {"type": "GATHER_RESOURCE", "resource": "minecraft:oak_log",
                       "target_quantity": 16},
              "tasks": [
                {"type": "GATHER", "resource": "minecraft:oak_log", "quantity": 16},
                {"type": "MOVE", "position": [10, 64, -3]}
              ]
            }""";

    @Test
    void parsesValidPlan() {
        CitizenPlan plan = parse(VALID);
        assertNotNull(plan);
        assertEquals("wood is the first prerequisite", plan.reasoningSummary);
        assertEquals(GoalType.GATHER_RESOURCE, plan.goal.type);
        assertEquals("minecraft:oak_log", plan.goal.resource);
        assertEquals(16, plan.goal.targetQuantity);
        assertEquals(2, plan.tasks.size());

        CitizenPlan.Task gather = plan.tasks.get(0);
        assertEquals(TaskType.GATHER, gather.type);
        assertEquals(16, gather.quantity);

        CitizenPlan.Task move = plan.tasks.get(1);
        assertEquals(TaskType.MOVE, move.type);
        assertEquals(3, move.position.length);
        assertEquals(-3, move.position[2]);
    }

    @Test
    void optionalFieldsDefaultCleanly() {
        CitizenPlan plan = parse("""
                {"reasoning_summary": "resting",
                 "goal": {"type": "REST"},
                 "tasks": [{"type": "REST"}]}""");
        assertNotNull(plan);
        assertNull(plan.goal.resource);
        assertEquals(-1, plan.goal.targetQuantity);
        assertEquals(-1, plan.tasks.get(0).quantity);
        assertNull(plan.tasks.get(0).position);
    }

    @Test
    void rejectsUnknownGoalType() {
        assertNull(parse("""
                {"reasoning_summary": "x", "goal": {"type": "OBLITERATE_EVERYTHING"},
                 "tasks": []}"""));
    }

    @Test
    void rejectsUnknownTaskType() {
        assertNull(parse("""
                {"reasoning_summary": "x", "goal": {"type": "IDLE"},
                 "tasks": [{"type": "SUMMON_WITHER"}]}"""));
    }

    @Test
    void rejectsUnnamespacedResource() {
        assertNull(parse("""
                {"reasoning_summary": "x", "goal": {"type": "GATHER_RESOURCE",
                 "resource": "oak_log"}, "tasks": []}"""));
    }

    @Test
    void rejectsResourceWithSpaces() {
        assertNull(parse("""
                {"reasoning_summary": "x", "goal": {"type": "GATHER_RESOURCE",
                 "resource": "minecraft:oak log"}, "tasks": []}"""));
    }

    @Test
    void rejectsOutOfRangeQuantities() {
        assertNull(parse("""
                {"reasoning_summary": "x", "goal": {"type": "GATHER_RESOURCE",
                 "resource": "minecraft:oak_log", "target_quantity": -5}, "tasks": []}"""));
        assertNull(parse("""
                {"reasoning_summary": "x", "goal": {"type": "GATHER_RESOURCE",
                 "resource": "minecraft:oak_log", "target_quantity": 2000000},
                 "tasks": []}"""));
        assertNull(parse("""
                {"reasoning_summary": "x", "goal": {"type": "IDLE"},
                 "tasks": [{"type": "GATHER", "resource": "minecraft:oak_log",
                            "quantity": 0}]}"""));
    }

    @Test
    void rejectsTooManyTasks() {
        StringBuilder tasks = new StringBuilder();
        for (int i = 0; i < 13; i++) {
            if (i > 0) tasks.append(',');
            tasks.append("{\"type\": \"IDLE\"}");
        }
        assertNull(parse("""
                {"reasoning_summary": "x", "goal": {"type": "IDLE"},
                 "tasks": [%s]}""".formatted(tasks)));
    }

    @Test
    void rejectsPositionThatIsNotThreeInts() {
        assertNull(parse("""
                {"reasoning_summary": "x", "goal": {"type": "IDLE"},
                 "tasks": [{"type": "MOVE", "position": [1, 2]}]}"""));
    }

    @Test
    void rejectsMissingOrOverlongReasoning() {
        assertNull(parse("{\"goal\": {\"type\": \"IDLE\"}, \"tasks\": []}"));
        assertNull(parse("""
                {"reasoning_summary": "%s", "goal": {"type": "IDLE"}, "tasks": []}"""
                .formatted("r".repeat(601))));
    }

    @Test
    void rejectsMalformedStructureWithoutThrowing() {
        assertNull(parse("{\"goal\": \"not an object\", \"tasks\": []}"));
        assertNull(parse("{\"reasoning_summary\": {\"nested\": true},"
                + " \"goal\": {\"type\": \"IDLE\"}}"));
        assertNull(parse("{\"reasoning_summary\": \"x\", \"goal\": []}"));
    }

    @Test
    void missingTasksArrayIsEmptyPlan() {
        CitizenPlan plan = parse("""
                {"reasoning_summary": "x", "goal": {"type": "IDLE"}}""");
        assertNotNull(plan);
        assertTrue(plan.tasks.isEmpty());
    }
}
