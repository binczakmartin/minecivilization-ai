package ai.minecivilization.citizen;

import java.util.*;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.farming.Crops;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import static ai.minecivilization.citizen.CitizenPlan.*;

/** Bounded local jobs with failure cooldowns, independent of the AI service. */
public final class LocalWorkPlanner {
    private final Map<String, Long> blockedUntil = new HashMap<>();
    private int rotation;
    private long nextLightingCheck;
    private static String key(Task t) { return t.type + ":" + t.resource + ":" + t.block + ":" + t.target; }
    public void failed(Task t, long now) { blockedUntil.put(key(t), now + 1200); }
    private Task task(TaskType type, String resource, int quantity) {
        return new Task(type, resource, quantity, null, null, null, null);
    }
    private boolean isBlocked(Task task, long now) {
        return blockedUntil.getOrDefault(key(task), 0L) > now;
    }
    private CitizenPlan plan(Task task, String reasoning) {
        return new CitizenPlan(reasoning,
                new Goal(GoalType.INCREASE_RESOURCE, task.resource, task.quantity, null,
                        "Colony maintenance"), List.of(task));
    }
    public CitizenPlan lighting(ServerLevel level, CitizenEntity citizen) {
        if (level.getGameTime() < nextLightingCheck) return null;
        nextLightingCheck = level.getGameTime() + 40;
        var crop = ai.minecivilization.colony.ColonyLighting.find(level, citizen.blockPosition());
        if (crop == null) return null;
        var inv = citizen.getInventory();
        boolean pickaxe = false;
        for (int i = 0; i < inv.getContainerSize(); i++)
            if (inv.getItem(i).getItem() instanceof net.minecraft.world.item.PickaxeItem) pickaxe = true;
        var job = ai.minecivilization.farming.LightingPolicy.next(
            ai.minecivilization.crafting.VanillaRecipeSource.inventorySnapshot(inv),
            station(level, citizen, "minecraft:crafting_table"), station(level, citizen, "minecraft:furnace"), pickaxe);
        Task t = new Task(TaskType.valueOf(job.type()), job.item(), job.quantity(), null,
            job.type().equals("PLACE") ? job.item() : null, null,
            job.type().equals("DECORATE") ? new int[]{crop.getX(), crop.getY(), crop.getZ()} : null);
        if (isBlocked(t, level.getGameTime())) return null;
        return new CitizenPlan("Prepare lighting for colony buildings, fields and mines",
            new Goal(GoalType.INCREASE_RESOURCE, "minecraft:torch", 16, null, "Colony lighting"), List.of(t));
    }
    private boolean station(ServerLevel level, CitizenEntity citizen, String id) {
        var registry = ai.minecivilization.colony.LandmarkRegistry.get(level);
        var kind = ai.minecivilization.colony.LandmarkKind.of(id);
        BlockPos remembered = registry.nearest(kind, citizen.blockPosition());
        if (remembered != null && level.isLoaded(remembered)) {
            if (ai.minecivilization.colony.LandmarkKind.of(net.minecraftforge.registries.ForgeRegistries.BLOCKS
                    .getKey(level.getBlockState(remembered).getBlock()).toString()) == kind) return true;
            registry.forget(remembered);
        }
        BlockPos base = citizen.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-8, -4, -8), base.offset(8, 4, 8))) {
            if (!level.isLoaded(p)) continue;
            if (id.equals(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(level.getBlockState(p).getBlock()).toString())) {
                registry.notice(level, p); return true;
            }
        }
        return false;
    }
    private long nextHusbandryCheck;
    public CitizenPlan husbandry(ServerLevel level, CitizenEntity citizen) {
        // Husbandry belongs to the shepherd. Letting every idle citizen help
        // looked cooperative in code but made the whole colony independently
        // choose the same nearby wolf, bone stack or half-built pen on the same
        // tick. They then crowded one target, failed navigation together and
        // looked like a crowd running in circles.
        if (!"SHEPHERD".equals(citizen.getIdentity().profession)) return null;
        if (level.getGameTime() < nextHusbandryCheck) return null;
        nextHusbandryCheck = level.getGameTime() + 100;
        for (Task t : ai.minecivilization.livestock.LivestockWork.candidates(level, citizen)) {
            if (isBlocked(t, level.getGameTime())) continue;
            return new CitizenPlan("Maintain enclosed livestock and colony guard wolves",
                new Goal(GoalType.INCREASE_RESOURCE, t.resource, t.quantity, null, "Animal husbandry"), List.of(t));
        }
        return null;
    }
    public CitizenPlan next(ServerLevel level, CitizenEntity citizen) {
        // These world-backed jobs are already selective (a real wolf, a real
        // dark crop), so they may lead the generic maintenance rotation.
        CitizenPlan husbandryJob = husbandry(level, citizen);
        if (husbandryJob != null) return husbandryJob;
        CitizenPlan lightingJob = lighting(level, citizen);
        if (lightingJob != null) return lightingJob;

        Task ripe = knownRipeCrop(level, citizen);
        if (ripe != null && !isBlocked(ripe, level.getGameTime())) return plan(ripe,
                "Harvest a ripe crop already discovered nearby");

        List<Task> candidates = new ArrayList<>();
        var inv = citizen.getInventory();
        boolean storage = StorageManager.nearest(level, citizen.blockPosition()) != null;
        if (!storage) {
            if (inv.count("minecraft:chest") == 0) candidates.add(task(TaskType.CRAFT, "minecraft:chest", 1));
            else {
                BlockPos base = citizen.blockPosition();
                outer: for (int r = 1; r <= 4; r++) for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++) for (int dy = -2; dy <= 1; dy++) {
                        BlockPos p = base.offset(dx, dy, dz);
                        if (!level.isLoaded(p) || !level.getBlockState(p).isAir()
                                || !level.getBlockState(p.above()).isAir()
                                || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)
                                || !level.getEntities(citizen, new net.minecraft.world.phys.AABB(p)).isEmpty()) continue;
                        candidates.add(new Task(TaskType.PLACE, "minecraft:chest", 1, null,
                            "minecraft:chest", null, new int[]{p.getX(), p.getY(), p.getZ()}));
                        break outer;
                    }
            }
        }
        if (storage) for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            var stack = inv.getItem(slot);
            if (!stack.isEmpty() && !stack.isDamageableItem()
                    && DeliveryPolicy.depositable(CitizenInventory.idOf(stack), stack.getCount()) >= 8) {
                candidates.add(task(TaskType.DELIVER, null, -1)); break;
            }
        }
        boolean hoe = false;
        for (int slot = 0; slot < inv.getContainerSize(); slot++)
            if (inv.getItem(slot).getItem() instanceof net.minecraft.world.item.HoeItem) hoe = true;
        if (!hoe) candidates.add(task(TaskType.CRAFT, "minecraft:wooden_hoe", 1));
        List<String> seeds = new ArrayList<>(Crops.SEEDS.keySet());
        Collections.sort(seeds);
        Collections.rotate(seeds, rotation++ % seeds.size());
        for (String seed : seeds) if (inv.count(seed) > 0 && (!Crops.needsFarmland(seed) || hoe))
            candidates.add(task(TaskType.PLANT, seed, Math.min(8, inv.count(seed))));
        if (inv.count("minecraft:iron_ingot") >= 3 && inv.count("minecraft:bucket") == 0 && inv.count("minecraft:water_bucket") == 0)
            candidates.add(task(TaskType.CRAFT, "minecraft:bucket", 1));
        // Do not invent crop jobs. The old rotation blindly offered carrots,
        // potatoes, beetroot, berries, cane, nether wart, pumpkin and melon in
        // turn; a colony with none of those spent every few seconds proving it.
        // Crops are selected above only after one was actually found and found
        // ripe in this citizen's own loaded surroundings.
        String[] resources = {"minecraft:oak_log", "minecraft:cobblestone", "minecraft:dirt"};
        for (int i = 0; i < resources.length; i++) {
            String resource = resources[Math.floorMod(rotation + i + citizen.getId(), resources.length)];
            if (inv.count(resource) < 32) candidates.add(task(TaskType.GATHER, resource, 32));
        }
        for (Task t : candidates) {
            if (isBlocked(t, level.getGameTime())) continue;
            return plan(t, "Useful local work while awaiting cognition");
        }
        return null;
    }

    /**
     * A harvest job backed by a real ripe block this citizen has already seen.
     * No block means no task — an absent crop is not a reason to keep asking
     * the world for it.
     */
    private Task knownRipeCrop(ServerLevel level, CitizenEntity citizen) {
        BlockPos origin = citizen.blockPosition();
        for (Iterator<Map.Entry<String, BlockPos>> it =
                citizen.knownResources().entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            String produce = Crops.produceFor(entry.getKey());
            if (produce == null) continue;
            int have = citizen.getInventory().count(produce);
            if (have >= 8) continue;
            int wanted = 8 - have;

            BlockPos pos = entry.getValue();
            if (!level.isLoaded(pos) || pos.distSqr(origin) > 64 * 64) continue;
            var state = level.getBlockState(pos);
            String actual = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                    .getKey(state.getBlock()).toString();
            if (!entry.getKey().equals(actual) || !Crops.ripe(state, level, pos)) {
                it.remove();
                continue;
            }
            return new Task(TaskType.HARVEST, produce, wanted, null, entry.getKey(), null,
                    new int[]{pos.getX(), pos.getY(), pos.getZ()});
        }
        return null;
    }
}
