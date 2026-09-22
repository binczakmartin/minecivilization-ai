package ai.minecivilization.citizen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parameter bag for one task execution — filled from the validated decision
 * (resource, quantity, target, position, project) and read by skills.
 */
public final class CitizenTaskParams {
    public String resource;      // item id, e.g. minecraft:oak_log
    public int quantity = -1;
    public String target;        // storage id / project id
    public String block;         // block id for BUILD/PLANT
    public String projectId;
    public int[] position;       // optional x,y,z

    public final Map<String, String> extra = new LinkedHashMap<>();

    public static CitizenTaskParams fromTask(CitizenPlan.Task task) {
        CitizenTaskParams p = new CitizenTaskParams();
        p.resource = task.resource;
        p.quantity = task.quantity;
        p.target = task.target;
        p.block = task.block;
        p.projectId = task.projectId;
        p.position = task.position;
        return p;
    }
}
