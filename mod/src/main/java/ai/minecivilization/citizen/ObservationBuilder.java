package ai.minecivilization.citizen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ai.minecivilization.combat.CombatPolicy;
import ai.minecivilization.config.ModConfig;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.storage.StorageManager;
import ai.minecivilization.storage.StorageNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Compact observation for the LLM — never a raw world dump.
 * Only legitimately known/seen things are included:
 *  - own inventory & state
 *  - registered storage (civilization knowledge)
 *  - resources the citizen itself discovered by looking
 *  - aggregate civilization summaries
 */
public final class ObservationBuilder {

    private ObservationBuilder() {
    }

    public static String build(CitizenEntity self, String reason, CitizenBrain brain) {
        ServerLevel level = (ServerLevel) self.level();
        JsonObject root = new JsonObject();

        JsonObject citizen = new JsonObject();
        citizen.addProperty("name", self.getName().getString());
        citizen.addProperty("profession", self.getIdentity().profession);
        citizen.addProperty("health", round(self.getHealth()));
        citizen.addProperty("hunger", round(self.getHunger()));
        citizen.addProperty("energy", round(self.getEnergy()));
        root.add("citizen", citizen);

        JsonArray pos = new JsonArray();
        pos.add((int) Math.floor(self.getX()));
        pos.add((int) Math.floor(self.getY()));
        pos.add((int) Math.floor(self.getZ()));
        root.add("position", pos);

        CitizenPlan.Goal goal = brain.currentGoal();
        root.addProperty("current_goal", goal == null ? null : goalLabel(goal));
        CitizenPlan.Task task = brain.currentTaskOrNull();
        root.addProperty("current_task", task == null ? null : task.type.name());
        root.addProperty("task_failures", brain.consecutiveTaskFailures());

        JsonObject inventory = new JsonObject();
        for (var entry : self.getInventory().summary().entrySet()) {
            inventory.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("inventory", inventory);

        JsonObject nearby = new JsonObject();

        JsonArray storages = new JsonArray();
        for (StorageNode node : StorageManager.get(level).all()) {
            JsonObject s = new JsonObject();
            s.addProperty("id", node.storageId);
            s.addProperty("distance", round(self.distanceToSqr(
                    node.containerX + 0.5, node.containerY + 0.5, node.containerZ + 0.5)));
            s.addProperty("position", node.containerX + "," + node.containerY + "," + node.containerZ);
            storages.add(s);
        }
        nearby.add("storage", storages);

        JsonArray resources = new JsonArray();
        for (var entry : self.knownResources().entrySet()) {
            JsonObject r = new JsonObject();
            r.addProperty("type", entry.getKey());
            BlockPos p = entry.getValue();
            r.addProperty("distance", round(self.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));
            r.addProperty("position", p.getX() + "," + p.getY() + "," + p.getZ());
            resources.add(r);
        }
        nearby.add("resources", resources);

        // Hostile mobs the citizen could perceive (same radius as its reflex)
        JsonArray hostiles = new JsonArray();
        double threatRadius = Math.max(
                CombatPolicy.effectiveRadius(ModConfig.COMBAT_TRIGGER_RADIUS.get(),
                        self.getPersonality().riskTolerance),
                16.0D);
        AABB threatBox = self.getBoundingBox().inflate(threatRadius);
        // Enemy is a marker interface (not an Entity subtype): scan mobs, filter with instanceof
        for (Mob enemy : level.getEntitiesOfClass(Mob.class, threatBox)) {
            if (!(enemy instanceof Enemy) || !enemy.isAlive()) continue;
            JsonObject h = new JsonObject();
            h.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(enemy.getType()).toString());
            h.addProperty("distance", round(self.distanceToSqr(enemy)));
            BlockPos p = enemy.blockPosition();
            h.addProperty("position", p.getX() + "," + p.getY() + "," + p.getZ());
            hostiles.add(h);
        }
        nearby.add("hostiles", hostiles);
        root.add("nearby", nearby);

        JsonObject civ = new JsonObject();
        civ.addProperty("population", CitizenIndex.population());
        civ.addProperty("food_reserve", foodReserve(level));
        civ.addProperty("active_projects", activeProjects(level));
        civ.addProperty("day", (int) (level.getDayTime() / 24000L));
        civ.addProperty("known_storage", StorageManager.get(level).all().size());
        JsonArray bottlenecks = new JsonArray();
        for (String b : projectBottlenecks(level)) bottlenecks.add(b);
        civ.add("bottlenecks", bottlenecks);
        root.add("civilization", civ);

        JsonArray events = new JsonArray();
        for (var it = brain.recentEvents(); it.hasNext(); ) {
            events.add(it.next());
        }
        root.add("recent_events", events);

        // Milestone memory keys (e.g. "placed:minecraft:crafting_table") so the
        // brain knows what infrastructure it already set up. Per-item noise is
        // filtered out and the list is sorted + bounded for token efficiency.
        JsonArray memories = new JsonArray();
        for (String key : memoryKeysForBrain(self)) {
            memories.add(key);
        }
        root.add("known_memories", memories);

        // NOTE: no "reason" key here — the Python Observation schema is
        // extra="forbid"; the reason travels in the DecisionRequest instead.
        return root.toString();
    }

    private static java.util.List<String> memoryKeysForBrain(CitizenEntity self) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String key : self.getMemory().keySet()) {
            if (isBookkeepingMemory(key)) continue;
            keys.add(key);
        }
        java.util.Collections.sort(keys);
        return keys.size() > 24 ? keys.subList(0, 24) : keys;
    }

    private static boolean isBookkeepingMemory(String key) {
        return key.startsWith("found:") || key.startsWith("took:")
                || key.startsWith("deposited:") || key.startsWith("fail:")
                || key.startsWith("done:") || key.startsWith("disabled:")
                || key.startsWith("completedProject:") || key.startsWith("last")
                || "taskFailures".equals(key);
    }

    private static String goalLabel(CitizenPlan.Goal goal) {
        StringBuilder sb = new StringBuilder(goal.type.name());
        if (goal.resource != null) sb.append('|').append(goal.resource);
        if (goal.projectId != null) sb.append('|').append(goal.projectId);
        return sb.toString();
    }

    private static int foodReserve(ServerLevel level) {
        int total = 0;
        for (StorageNode node : StorageManager.get(level).all()) {
            var be = level.getBlockEntity(node.containerPos());
            if (!(be instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) continue;
                if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private static int activeProjects(ServerLevel level) {
        int count = 0;
        for (ConstructionProject p : ConstructionManager.get(level).all()) {
            if (p.status == ConstructionProject.Status.PROPOSED
                    || p.status == ConstructionProject.Status.PLANNED
                    || p.status == ConstructionProject.Status.WAITING_FOR_RESOURCES
                    || p.status == ConstructionProject.Status.BUILDING) {
                count++;
            }
        }
        return count;
    }

    private static java.util.List<String> projectBottlenecks(ServerLevel level) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (ConstructionProject p : ConstructionManager.get(level).all()) {
            if (p.status == ConstructionProject.Status.WAITING_FOR_RESOURCES) {
                result.add(p.name + " waiting for resources");
            }
        }
        return result;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
