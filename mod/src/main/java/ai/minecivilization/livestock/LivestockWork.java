package ai.minecivilization.livestock;

import java.util.*;
import ai.minecivilization.citizen.CitizenPlan;
import ai.minecivilization.citizen.CitizenPlan.Task;
import ai.minecivilization.citizen.CitizenPlan.TaskType;
import ai.minecivilization.config.ModConfig;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.storage.SettlementStock;
import ai.minecivilization.construction.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;

/** World-backed husbandry choices; no blind loops waiting for an absent animal or feed. */
public final class LivestockWork {
    private static Task task(TaskType type, String item, int count, String target) { return new Task(type, item, count, target, null, null, null); }
    public static List<Task> candidates(ServerLevel level, CitizenEntity citizen) {
        List<Task> work = new ArrayList<>();
        Pens.census(level);
        var registry = HerdRegistry.get(level);
        var inventory = citizen.getInventory();
        var stored = SettlementStock.totals(level, citizen.blockPosition());
        var nearby = level.getEntitiesOfClass(Animal.class, citizen.getBoundingBox().inflate(48), a -> a.isAlive());
        // Bone drops can be collected after combat without inventing a crafting recipe for bones.
        boolean wildWolf = nearby.stream().anyMatch(a -> a instanceof Wolf w && !w.isTame() && !w.isAngry() && !w.isBaby() && !w.isLeashed() && !w.hasCustomName());
        if (wildWolf && registry.committed("minecraft:wolf", level.getGameTime()) < ModConfig.WOLF_LIMIT.get()) {
            if (inventory.count("minecraft:bone") > 0) work.add(task(TaskType.TAME_WOLF, "minecraft:wolf", 1, null));
            else if (stored.getOrDefault("minecraft:bone", 0) > 0) work.add(task(TaskType.WITHDRAW, "minecraft:bone", Math.min(8, stored.get("minecraft:bone")), null));
            else if (!level.getEntitiesOfClass(ItemEntity.class, citizen.getBoundingBox().inflate(14), e -> e.getItem().is(Items.BONE)).isEmpty())
                work.add(task(TaskType.COLLECT, "minecraft:bone", 8, null));
        }
        var pen = Pens.nearest(level, citizen.blockPosition());
        if (pen == null) {
            // A herd seen anywhere is reason enough to build the pen: the
            // animals are fetched once it stands.
            if (Pens.all(level).isEmpty() && nearby.stream().noneMatch(a -> AnimalHusbandry.isLivestock(HerdRegistry.species(a)))
                    && !AnimalSightings.any(level)) {
                if (!ai.minecivilization.citizen.NightPolicy.shelterTime(level.getDayTime(), level.isThundering()))
                    work.add(new Task(TaskType.EXPLORE, null, 1, null, null, null, null));
                return work;
            }
            var project = Pens.ensureProject(level, citizen.blockPosition(), penWood(level, citizen));
            if (project == null) return work;
            if (!Pens.prepared(level, project)) {
                if (inventory.count("minecraft:dirt") < 16) work.add(task(TaskType.CRAFT, "minecraft:dirt", 32, null));
                else work.add(new Task(TaskType.PREPARE_PEN, null, -1, null, null, project.id, null));
                return work;
            }
            // Ensure the 3x3 workstation before fence/gate recipes.
            var table = ai.minecivilization.colony.LandmarkRegistry.get(level).nearest(ai.minecivilization.colony.LandmarkKind.CRAFTING_TABLE, citizen.blockPosition());
            if (table == null || !level.isLoaded(table) || !level.getBlockState(table).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
                work.add(new Task(inventory.count("minecraft:crafting_table") > 0 ? TaskType.PLACE : TaskType.CRAFT,
                    "minecraft:crafting_table", 1, null, "minecraft:crafting_table", null, null));
                return work;
            }
            var blueprint = ConstructionManager.blueprint(project.blueprintId);
            // Repair destroyed fences even when the saved project says completed.
            for (var entry : blueprint.entries()) {
                var at = new net.minecraft.core.BlockPos(project.originX + entry.x, project.originY + entry.y, project.originZ + entry.z);
                var state = ConstructionManager.parseState(level, entry.blockState);
                if (state != null && !level.getBlockState(at).is(state.getBlock())) project.placed.remove(project.key(at.getX(), at.getY(), at.getZ()));
            }
            ConstructionManager.get(level).setDirty();
            for (var entry : blueprint.entries()) {
                String key = project.key(project.originX + entry.x, project.originY + entry.y, project.originZ + entry.z);
                if (project.placed.contains(key)) continue;
                String item = entry.blockState.split("\\[")[0];
                if (item.equals("minecraft:torch")) continue; // enclosure first, lighting is a separate colony duty
                if (inventory.count(item) == 0) { work.add(task(TaskType.CRAFT, item, item.endsWith("_fence") ? 32 : 1, null)); return work; }
            }
            work.add(new Task(TaskType.BUILD, null, -1, null, null, project.id, null)); return work;
        }
        var penned = Pens.animals(level, pen);
        // Too few animals in the pen and none about: go and fetch the herd the
        // colony has seen, with something to lure it with.
        long pennedLivestock = penned.stream().filter(a -> AnimalHusbandry.isLivestock(HerdRegistry.species(a))).count();
        boolean wildNearby = nearby.stream().anyMatch(a -> AnimalHusbandry.isLivestock(HerdRegistry.species(a))
                && !HerdRegistry.owned(a) && !Pens.inside(pen, a));
        if (pennedLivestock < 4 && !wildNearby) {
            var sighting = AnimalSightings.nearest(level, citizen.blockPosition());
            if (sighting != null && sighting.distSqr(citizen.blockPosition()) < 20 * 20) {
                // Arrived and they have wandered off.
                AnimalSightings.forgetNear(level, sighting);
                sighting = AnimalSightings.nearest(level, citizen.blockPosition());
            }
            boolean night = ai.minecivilization.citizen.NightPolicy.shelterTime(level.getDayTime(), level.isThundering());
            if (sighting != null && !night) {
                int lure = inventory.count("minecraft:wheat") + inventory.count("minecraft:wheat_seeds")
                        + inventory.count("minecraft:carrot");
                if (lure >= 2) {
                    work.add(new Task(TaskType.MOVE, null, -1, null, null, null,
                            new int[]{sighting.getX(), sighting.getY(), sighting.getZ()}));
                } else if (stored.getOrDefault("minecraft:wheat", 0) >= 2) {
                    work.add(task(TaskType.WITHDRAW, "minecraft:wheat", Math.min(8, stored.get("minecraft:wheat")), null));
                } else {
                    // Seeds lure chickens, wheat lures sheep and cows: grass gives seeds.
                    work.add(new Task(TaskType.GATHER, "minecraft:wheat_seeds", inventory.count("minecraft:wheat_seeds") + 6,
                            null, "minecraft:short_grass", null, null));
                }
                return work;
            }
            if (sighting == null && !night && pennedLivestock == 0) {
                work.add(new Task(TaskType.EXPLORE, null, 1, null, null, null, null));
                return work;
            }
        }
        boolean foodNeeded = stored.entrySet().stream().filter(e -> e.getKey().contains("cooked_") || e.getKey().equals("minecraft:bread"))
            .mapToInt(Map.Entry::getValue).sum() < 32;
        for (String species : AnimalHusbandry.species()) {
            int total = registry.count(species);
            int adults = (int) penned.stream().filter(a -> HerdRegistry.species(a).equals(species) && HerdRegistry.owned(a) && !a.isBaby()).count();
            if (HerdPolicy.canHarvestForFood(total, adults, false, ModConfig.LIVESTOCK_LIMIT.get(), foodNeeded && !species.equals("minecraft:goat")))
                work.add(task(TaskType.TEND_LIVESTOCK, species, 1, total > ModConfig.LIVESTOCK_LIMIT.get() ? "SURPLUS" : "FOOD"));
        }
        if (penned.stream().anyMatch(a -> a instanceof Sheep sheep && sheep.readyForShearing())) {
            int iron = inventory.count("minecraft:iron_ingot") + stored.getOrDefault("minecraft:iron_ingot", 0);
            if (inventory.count("minecraft:shears") > 0) work.add(task(TaskType.TEND_LIVESTOCK, "minecraft:sheep", 1, "SHEAR"));
            // Shears are iron: only once the mine has produced some.
            else if (iron >= 2) work.add(task(TaskType.CRAFT, "minecraft:shears", 1, null));
        }
        if (inventory.count("minecraft:milk_bucket") == 0 && stored.getOrDefault("minecraft:milk_bucket", 0) < 4
                && inventory.count("minecraft:bucket") > 0 && penned.stream().anyMatch(a -> !a.isBaby() && (a instanceof Cow || a instanceof Goat)))
            work.add(task(TaskType.TEND_LIVESTOCK, null, 1, "MILK"));
        if (!level.getEntitiesOfClass(ItemEntity.class, Pens.interior(pen), e -> e.getItem().is(Items.EGG)).isEmpty())
            work.add(task(TaskType.TEND_LIVESTOCK, "minecraft:chicken", 1, "EGGS"));
        for (String species : AnimalHusbandry.species()) {
            long adults = penned.stream().filter(a -> HerdRegistry.species(a).equals(species) && !a.isBaby() && a.getAge() == 0 && a.canFallInLove()).count();
            boolean breed = HerdPolicy.canBreed(registry.count(species), registry.pending(species, level.getGameTime()), (int) adults, ModConfig.LIVESTOCK_LIMIT.get());
            boolean herd = penned.stream().filter(a -> HerdRegistry.species(a).equals(species)).count() < 2
                && nearby.stream().anyMatch(a -> HerdRegistry.species(a).equals(species) && !a.isBaby() && !a.isLeashed() && !a.hasCustomName() && !Pens.inside(pen, a))
                && registry.committed(species, level.getGameTime()) < ModConfig.LIVESTOCK_LIMIT.get();
            if (!breed && !herd) continue;
            int needed = breed ? 2 : 1;
            String feed = AnimalHusbandry.feedFor(species).stream().filter(f -> inventory.count(f) >= needed).findFirst().orElse(null);
            if (feed != null) work.add(task(breed ? TaskType.BREED : TaskType.HERD, species, 1, null));
            else {
                String available = AnimalHusbandry.feedFor(species).stream().filter(f -> stored.getOrDefault(f, 0) >= needed).findFirst().orElse(null);
                if (available != null) work.add(task(TaskType.WITHDRAW, available, Math.min(8, stored.get(available)), null));
                else if (AnimalHusbandry.feedFor(species).contains("minecraft:wheat")) work.add(task(TaskType.HARVEST, "minecraft:wheat", 8, null));
            }
        }
        // Cook real meat after slaughter before handing it to the colony's food store.
        for (String meat : List.of("beef", "porkchop", "mutton", "chicken", "rabbit"))
            if (inventory.count("minecraft:" + meat) > 0) {
                var furnace = ai.minecivilization.colony.LandmarkRegistry.get(level).nearest(ai.minecivilization.colony.LandmarkKind.FURNACE, citizen.blockPosition());
                if (furnace != null && level.isLoaded(furnace) && level.getBlockState(furnace).is(net.minecraft.world.level.block.Blocks.FURNACE))
                    work.add(0, task(TaskType.CRAFT, "minecraft:cooked_" + meat, inventory.count("minecraft:cooked_" + meat) + inventory.count("minecraft:" + meat), null));
            }
        return work;
    }
    private static String penWood(ServerLevel level, CitizenEntity citizen) {
        List<String> woods = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove");
        String best = "oak"; int count = 0;
        for (String wood : woods) {
            int held = citizen.getInventory().count("minecraft:" + wood + "_log") * 4
                + citizen.getInventory().count("minecraft:" + wood + "_planks");
            if (held > count) { count = held; best = wood; }
        }
        if (count > 0) return best;
        var base = citizen.blockPosition();
        for (var pos : net.minecraft.core.BlockPos.betweenClosed(base.offset(-12, -4, -12), base.offset(12, 6, 12))) {
            if (!level.isLoaded(pos)) continue;
            String block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock()).toString();
            for (String wood : woods) if (block.equals("minecraft:" + wood + "_log")) return wood;
        }
        return best;
    }
    private LivestockWork() {}
}
