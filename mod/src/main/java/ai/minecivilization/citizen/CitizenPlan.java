package ai.minecivilization.citizen;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * GOAL → TASK → SKILL hierarchy.
 * The LLM chooses goals/tasks; Minecraft code performs skills.
 *
 * <p>This class is the validated Java-side model of a decision from the AI
 * service. Anything unknown is rejected before it can influence the world.</p>
 */
public final class CitizenPlan {
    public enum GoalType {
        IDLE, INCREASE_RESOURCE, GATHER_RESOURCE, DELIVER_RESOURCE,
        BUILD_PROJECT, HARVEST_FOOD, CRAFT_ITEM, REST, EXPLORE
    }

    public enum TaskType {
        IDLE, REST, GATHER, HARVEST, PLANT, DELIVER, WITHDRAW,
        BUILD, CRAFT, SMELT, MOVE, INSPECT
    }

    public static final class Goal {
        public final GoalType type;
        public final String resource;       // item id or null
        public final int targetQuantity;    // -1 = unspecified
        public final String projectId;      // or null
        public final String description;

        public Goal(GoalType type, String resource, int targetQuantity,
                    String projectId, String description) {
            this.type = type;
            this.resource = resource;
            this.targetQuantity = targetQuantity;
            this.projectId = projectId;
            this.description = description == null ? "" : description;
        }
    }

    public static final class Task {
        public final TaskType type;
        public final String resource;
        public final int quantity;          // -1 = unspecified
        public final String target;         // storage id / project id / citizen id
        public final String block;
        public final String projectId;
        public final int[] position;        // optional x,y,z

        public Task(TaskType type, String resource, int quantity,
                    String target, String block, String projectId, int[] position) {
            this.type = type;
            this.resource = resource;
            this.quantity = quantity;
            this.target = target;
            this.block = block;
            this.projectId = projectId;
            this.position = position;
        }
    }

    public final String reasoningSummary;
    public final Goal goal;
    public final List<Task> tasks;

    public CitizenPlan(String reasoningSummary, Goal goal, List<Task> tasks) {
        this.reasoningSummary = reasoningSummary;
        this.goal = goal;
        this.tasks = new ArrayList<>(tasks);
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Strict parse: unknown goal/task types, malformed ids, out-of-range
     * quantities or extra structure cause rejection (Optional.empty()).
     */
    public static CitizenPlan parse(JsonObject root) {
        try {
            String summary = root.get("reasoning_summary").getAsString();
            if (summary.isEmpty() || summary.length() > 600) return null;

            JsonObject goalJson = root.getAsJsonObject("goal");
            GoalType goalType = enumOr(GoalType.class, goalJson.get("type").getAsString());
            if (goalType == null) return null;

            String resource = optString(goalJson, "resource");
            if (resource != null && !isValidId(resource)) return null;
            // Optional, mirrors the Python Goal schema: absent/null = unspecified,
            // otherwise 0..1_000_000. Rejecting "absent" here would silently drop
            // every quantity-less goal (REST, BUILD_PROJECT, HARVEST_FOOD, ...).
            int targetQty = -1;
            if (goalJson.has("target_quantity") && !goalJson.get("target_quantity").isJsonNull()) {
                int requested = goalJson.get("target_quantity").getAsInt();
                if (requested < 0 || requested > 1_000_000) return null;
                targetQty = requested;
            }
            String projectId = optString(goalJson, "project_id");
            String description = optString(goalJson, "description");

            Goal goal = new Goal(goalType, resource, targetQty, projectId, description);

            List<Task> tasks = new ArrayList<>();
            JsonArray taskArray = root.has("tasks") && root.get("tasks").isJsonArray()
                    ? root.getAsJsonArray("tasks") : new JsonArray();
            if (taskArray.size() > 12) return null;
            for (JsonElement el : taskArray) {
                if (!el.isJsonObject()) return null;
                JsonObject t = el.getAsJsonObject();
                TaskType type = enumOr(TaskType.class, t.get("type").getAsString());
                if (type == null) return null;
                String tResource = optString(t, "resource");
                String block = optString(t, "block");
                if (tResource != null && !isValidId(tResource)) return null;
                if (block != null && !isValidId(block)) return null;
                int qty = t.has("quantity") && !t.get("quantity").isJsonNull()
                        ? t.get("quantity").getAsInt() : -1;
                if (qty != -1 && (qty < 1 || qty > 100_000)) return null;
                int[] pos = null;
                if (t.has("position") && t.get("position").isJsonArray()) {
                    JsonArray arr = t.getAsJsonArray("position");
                    if (arr.size() != 3) return null;
                    pos = new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()};
                }
                tasks.add(new Task(type, tResource, qty, optString(t, "target"),
                        block, optString(t, "project_id"), pos));
            }
            return new CitizenPlan(summary, goal, tasks);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isValidId(String id) {
        if (id == null || id.isEmpty() || id.length() > 128) return false;
        if (id.contains(" ") || id.contains(";")) return false;
        int colon = id.indexOf(':');
        return colon > 0 && colon < id.length() - 1;
    }

    private static String optString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
