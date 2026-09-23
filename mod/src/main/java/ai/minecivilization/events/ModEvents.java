package ai.minecivilization.events;

import ai.minecivilization.construction.Blueprint;
import ai.minecivilization.construction.CampLayout;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.architecture.HouseCatalog;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.network.AiBridge;
import ai.minecivilization.registry.ModEntities;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import ai.minecivilization.colony.ColonyCensus;
import ai.minecivilization.colony.ColonyChunkLoader;
import ai.minecivilization.colony.ColonyData;
import ai.minecivilization.colony.ColonyLife;
import ai.minecivilization.colony.ColonyNotifier;
import ai.minecivilization.colony.ColonyRoster;
import ai.minecivilization.colony.Zone;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.colony.ZonePlanner;
import ai.minecivilization.storage.StorageDiscovery;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Global Forge (game-bus) events plus the mod-bus attribute registration.
 *
 * <p>The AI bridge only ever mutates world state here: HTTP threads enqueue
 * results, and they are applied on the server thread by {@link AiBridge#drain()}.
 * Minecraft never blocks on the AI service.</p>
 */
public final class ModEvents {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** How often (ticks) the camp's starter-house plan is reviewed. */
    private static final int HOUSE_CHECK_INTERVAL = 100;
    /** How often the colony reports what everyone is doing (ticks). */
    private static final int HEARTBEAT_INTERVAL = 1200;

    private static int heartbeatTick;
    /** Hard cap on auto-planned camp houses (grows with population). */
    private static final int MAX_HOUSES = 4;

    private static int houseCheckTick = 0;

    private ModEvents() {
    }

    /** Mod bus: entity attributes must be registered before any citizen spawns. */
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.CITIZEN.get(), CitizenEntity.createAttributes().build());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        AiBridge.drain();
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);

        // One pass over the town: find its containers (without this the storage
        // registry stays empty and no citizen ever delivers anything) and count
        // its beds (without which the colony cannot know whether it may grow).
        ColonyCensus.tick(overworld);
        ColonyChunkLoader.tick(overworld);
        ColonyLife.tick(overworld);

        if (++heartbeatTick % HEARTBEAT_INTERVAL == 0) {
            logHeartbeat(overworld);
        }

        if (++houseCheckTick % HOUSE_CHECK_INTERVAL != 0) {
            return;
        }
        ensureZoning(overworld);
        ensureTradesCovered(overworld);
        ensureStarterHouses(overworld);
    }

    /**
     * Move one citizen into a trade nobody holds.
     *
     * <p>Trades are handed out in spawn order, which never revisits a gap: a
     * colony that loses its only farmer would otherwise stop growing food for
     * good. Conservative by design — see {@link ColonyRoster}.</p>
     */
    private static void ensureTradesCovered(ServerLevel level) {
        if (level == null) return;
        var citizens = CitizenIndex.all();
        if (citizens.isEmpty()) return;

        java.util.Map<String, Integer> census = ColonyRoster.emptyCensus();
        for (CitizenEntity citizen : citizens) {
            census.merge(citizen.getIdentity().profession, 1, Integer::sum);
        }

        ColonyRoster.Reassignment change = ColonyRoster.rebalance(census, citizens.size());
        if (change == null) return;

        // Take someone idle: interrupting a citizen mid-task wastes the trip.
        for (CitizenEntity citizen : citizens) {
            if (!change.from().equals(citizen.getIdentity().profession)) continue;
            if (citizen.getCitizenBrain().currentTaskOrNull() != null) continue;

            citizen.retrain(change.to());
            ColonyNotifier.milestone(level, citizen.getIdentity().name
                    + " takes up work as a " + change.to().toLowerCase(java.util.Locale.ROOT)
                    + " — the colony had none.");
            return;
        }
    }

    /**
     * Lay out one more district when the colony has outgrown its land. One at a
     * time, so a town grows plot by plot instead of appearing all at once.
     */
    private static void ensureZoning(ServerLevel level) {
        if (level == null) return;
        Zone created = ZonePlanner.ensure(level, CitizenIndex.population());
        if (created != null) {
            ColonyNotifier.zoneAllotted(level, created);
        }

        // Every district gets a post saying what it is and a double chest to
        // start its first building from. One per check, so marking the town
        // never competes with building it.
        for (Zone zone : ZoneManager.get(level).all()) {
            if (ai.minecivilization.colony.DistrictMarker.ensureFor(level, zone) != null) {
                return;
            }
        }
    }

    /**
     * A minute-by-minute line per citizen: who is doing what, and what is
     * holding the colony back. Failures alone never told the whole story — a
     * settlement quietly working looked the same as one stuck in a loop.
     */
    private static void logHeartbeat(ServerLevel level) {
        if (level == null) return;
        var citizens = CitizenIndex.all();
        if (citizens.isEmpty()) return;

        // How many are actually doing something. This is the number that says
        // whether the colony is working or waiting, and it was invisible: more
        // than half of all citizen time turned out to be spent with no goal.
        int busy = 0;
        for (CitizenEntity citizen : citizens) {
            if (citizen.getCitizenBrain().currentTaskOrNull() != null) busy++;
        }
        LOGGER.info("[Colony] {}/{} citizens working ({}% idle)",
                busy, citizens.size(), (citizens.size() - busy) * 100 / citizens.size());

        String blocker = ai.minecivilization.colony.ColonyLife.growthBlocker(level);
        LOGGER.info("[Colony] {} citizen(s), {} district(s), {} container(s), {} bed(s) — growth: {}",
                citizens.size(),
                ai.minecivilization.colony.ZoneManager.get(level).all().size(),
                ai.minecivilization.storage.StorageManager.get(level).all().size(),
                ai.minecivilization.colony.ColonyCensus.beds(),
                blocker == null ? "ready" : blocker);

        for (CitizenEntity citizen : citizens) {
            var goal = citizen.getCitizenBrain().currentGoal();
            var task = citizen.getCitizenBrain().currentTaskOrNull();
            LOGGER.info("[Colony]   {} ({}) at {},{},{} — {} / {} / {}",
                    citizen.getIdentity().name, citizen.getIdentity().profession,
                    (int) citizen.getX(), (int) citizen.getY(), (int) citizen.getZ(),
                    citizen.getStatusName(),
                    goal == null ? "no goal" : goal.type.name(),
                    task == null ? "idle" : task.type.name());
        }
    }

    /**
     * Teach hostile mobs that citizens are worth attacking.
     *
     * <p>Zombies and their kind only hunt what their own target goals name —
     * players, villagers, iron golems, turtles. A modded mob is invisible to
     * them, so citizens walked through hordes untouched and the whole combat
     * layer never fired in practice. The goal is added as each hostile spawns,
     * which is the only hook that reaches mobs the mod does not own.</p>
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        if (event.getEntity() instanceof net.minecraft.world.entity.animal.Animal animal
                && event.getLevel() instanceof ServerLevel level && ai.minecivilization.livestock.HerdRegistry.owned(animal)) {
            ai.minecivilization.livestock.HerdRegistry.get(level).register(animal);
            if (animal instanceof net.minecraft.world.entity.animal.Wolf wolf) ai.minecivilization.livestock.ColonyWolves.attach(wolf);
        }
        if (!(event.getEntity() instanceof Monster monster)) return;

        monster.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(
                monster, CitizenEntity.class, true));
    }

    @SubscribeEvent
    public static void onColonyAnimalBirth(net.minecraftforge.event.entity.living.BabyEntitySpawnEvent event) {
        if (!(event.getParentA() instanceof net.minecraft.world.entity.animal.Animal first)
                || !(event.getParentB() instanceof net.minecraft.world.entity.animal.Animal second)
                || !(first.level() instanceof ServerLevel level)
                || (!ai.minecivilization.livestock.HerdRegistry.owned(first)
                    && !ai.minecivilization.livestock.HerdRegistry.owned(second))) return;
        var registry = ai.minecivilization.livestock.HerdRegistry.get(level);
        String species = ai.minecivilization.livestock.HerdRegistry.species(first);
        registry.finishBirth(first, second);
        int cap = species.equals("minecraft:wolf") ? ai.minecivilization.config.ModConfig.WOLF_LIMIT.get()
            : ai.minecivilization.config.ModConfig.LIVESTOCK_LIMIT.get();
        if (registry.count(species) >= cap) { event.setCanceled(true); return; }
        if (event.getChild() instanceof net.minecraft.world.entity.animal.Animal baby) {
            baby.getPersistentData().putBoolean(ai.minecivilization.livestock.HerdRegistry.TAG, true);
            baby.setPersistenceRequired();
            // Registration happens on entity join, so another mod cancelling this
            // birth cannot leave a phantom animal consuming a population slot.
        }
    }

    /** A death is one of the few things worth interrupting a player for. */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.animal.Animal animal
                && animal.level() instanceof ServerLevel animalLevel && ai.minecivilization.livestock.HerdRegistry.owned(animal))
            ai.minecivilization.livestock.HerdRegistry.get(animalLevel).remove(animal.getUUID());
        if (!(event.getEntity() instanceof CitizenEntity citizen)) return;
        if (!(citizen.level() instanceof ServerLevel level)) return;

        ColonyData.get(level).recordDeath();
        String killer = event.getSource().getEntity() == null
                ? event.getSource().getMsgId()
                : event.getSource().getEntity().getName().getString();
        ColonyNotifier.death(level, citizen.getIdentity().name, killer);
    }

    /** A chest put down by anyone joins the settlement warehouse at once. */
    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            StorageDiscovery.onContainerPlaced(level, event.getPos());
            ai.minecivilization.colony.LandmarkRegistry.get(level)
                    .notice(level, event.getPos());
        }
    }

    /** A broken chest leaves the registry, so nobody walks to a hole. */
    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            StorageDiscovery.onContainerRemoved(level, event.getPos());
            ai.minecivilization.colony.LandmarkRegistry.get(level).forget(event.getPos());
        }
    }

    /**
     * Keep a small row of starter-house projects at the settlement camp —
     * beside the player's bed ({@link ConstructionManager#resolveAnchor}),
     * not at the world spawn: one house per ~2 citizens, capped at
     * {@link #MAX_HOUSES}. Completed houses count toward the need, so growth —
     * not churn — adds projects.
     *
     * <p>Houses that own no placed blocks yet follow the camp when the anchor
     * moves — worlds that planned their row near spawn before the camp moved
     * to the bed get re-homed for free. Anything already built stays where it
     * stands: moving it would strand its blocks.</p>
     */
    private static void ensureStarterHouses(ServerLevel level) {
        if (level == null) return;
        int population = CitizenIndex.population();
        if (population <= 0) return;

        ConstructionManager manager = ConstructionManager.get(level);
        BlockPos anchor = manager.resolveAnchor(level);

        List<ConstructionProject> houses = new ArrayList<>();
        List<ConstructionProject> unhomed = new ArrayList<>();  // houses owning no blocks yet
        List<CampLayout.Rect> occupied = new ArrayList<>();     // everything fixed in place
        for (ConstructionProject p : manager.all()) {
            boolean house = HouseCatalog.isHouse(p.blueprintId);
            if (house) houses.add(p);
            if (house && p.placed.isEmpty()) {
                unhomed.add(p);
            } else {
                occupied.add(footprint(p));
            }
        }

        int fulfilled = 0;   // houses being built or already standing
        for (ConstructionProject p : houses) {
            if (p.status != ConstructionProject.Status.FAILED) fulfilled++;
        }

        // The next house's design decides how much room to leave for it.
        Blueprint houseBlueprint = HouseCatalog.forColony(level, houses.size());
        int houseW = houseBlueprint.sizeX;
        int houseD = houseBlueprint.sizeZ;

        // 1) Leave unhomed houses alone when they already sit on a free camp
        //    slot — re-running the check must never shuffle the row.
        List<ConstructionProject> pending = new ArrayList<>();
        for (ConstructionProject p : unhomed) {
            CampLayout.Rect r = footprint(p);
            if (CampLayout.isSlot(anchor.getX(), anchor.getZ(), p.originX, p.originZ)
                    && CampLayout.free(r, occupied)) {
                occupied.add(r);
            } else {
                pending.add(p);
            }
        }

        // 2) Re-home the rest to the first free camp slot by the bed.
        for (ConstructionProject p : pending) {
            int slot = CampLayout.freeSlot(anchor.getX(), anchor.getZ(), houseW, houseD, occupied);
            if (slot < 0) continue;   // row full: better where it is than jammed in
            int x = CampLayout.slotX(anchor.getX(), slot);
            int z = CampLayout.slotZ(anchor.getZ());
            manager.relocate(p, x, surfaceY(level, x, z, houseW, houseD), z);
            occupied.add(new CampLayout.Rect(x, z, houseW, houseD));
        }

        // 3) Plan one more house while need outgrows the camp.
        int desired = Math.min(MAX_HOUSES, (population + 1) / 2);
        if (fulfilled >= desired || houses.size() >= MAX_HOUSES) return;
        int slot = CampLayout.freeSlot(anchor.getX(), anchor.getZ(), houseW, houseD, occupied);
        if (slot < 0) return;
        int x = CampLayout.slotX(anchor.getX(), slot);
        int z = CampLayout.slotZ(anchor.getZ());
        manager.createProject(houseBlueprint.name + " " + (houses.size() + 1),
                houseBlueprint.id, x, surfaceY(level, x, z, houseW, houseD), z,
                level.getGameTime());
    }

    /** Footprint of a project: its origin plus the blueprint's ground size. */
    private static CampLayout.Rect footprint(ConstructionProject p) {
        Blueprint bp = ConstructionManager.blueprint(p.blueprintId);
        return new CampLayout.Rect(p.originX, p.originZ,
                bp == null ? 1 : bp.sizeX, bp == null ? 1 : bp.sizeZ);
    }

    /** Ground level under the middle of a footprint starting at (x, z). */
    private static int surfaceY(ServerLevel level, int x, int z, int sizeX, int sizeZ) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE,
                x + sizeX / 2, z + sizeZ / 2) - 1;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CitizenIndex.clear();
        ColonyCensus.reset();
        ColonyChunkLoader.reset();
        ColonyLife.reset();
    }
}
