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
    /**
     * Ceiling on auto-planned houses.
     *
     * <p>Four was a hard cap on the whole settlement, which meant a colony of
     * twenty could never have more than four houses however long it ran — one
     * of the concrete reasons the place stopped looking like it was growing.
     * The row's own geometry still bounds it; this is only a sanity limit.</p>
     */
    private static final int MAX_HOUSES = CampLayout.MAX_SLOTS;

    private static int houseCheckTick = 0;
    /** Blueprints are rebuilt once per world load, not once per tick. */
    private static boolean blueprintsReconciled;

    /**
     * District markers allowed to be under construction at once.
     *
     * <p>Two, because a marker is a signpost and a chest, not a building. The
     * colony has exactly one construction workforce and markers compete with
     * the town hall for it.</p>
     */
    private static final int MAX_PENDING_MARKERS = 2;

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

        // The colony's work board has to exist before anybody asks it for a
        // job; installing here is idempotent and covers every world load.
        ai.minecivilization.work.ColonyWork.install();
        reconcileBlueprints(overworld);

        // One pass over the town: find its containers (without this the storage
        // registry stays empty and no citizen ever delivers anything) and count
        // its beds (without which the colony cannot know whether it may grow).
        ColonyCensus.tick(overworld);
        ColonyChunkLoader.tick(overworld);
        // Citizens carry their own ticking area, so work done away from town
        // happens in a world that is actually running — which is what makes
        // felled canopies decay and planted crops grow.
        ai.minecivilization.colony.CitizenChunkLoader.tick(overworld);
        ColonyLife.tick(overworld);
        ai.minecivilization.work.ProductivityMonitor.tick(overworld);
        ai.minecivilization.telemetry.CitizenTracker.tick(overworld);

        if (++heartbeatTick % HEARTBEAT_INTERVAL == 0) {
            logHeartbeat(overworld);
        }

        if (++houseCheckTick % HOUSE_CHECK_INTERVAL != 0) {
            return;
        }
        ensureZoning(overworld);
        ensureTradesCovered(overworld);
        // Civic buildings before houses: a colony wants a centre before it
        // wants a fourth bedroom, and the old order never got past bedrooms.
        ai.minecivilization.colony.CivicPlanner.ensure(overworld, CitizenIndex.population());
        ensureStarterHouses(overworld);
        ai.minecivilization.colony.SignRegistry.get(overworld).prune(overworld);
    }

    /**
     * Rebuild the blueprints this world's projects refer to, once per world.
     *
     * <p>Procedural blueprints live in a static map that starts empty, so
     * after a reload every marker, house and town hall names a blueprint
     * nothing has generated. The builder treats that as fatal and unrecoverable
     * — sixteen hundred failures in one session, which was the whole of the
     * colony's construction output.</p>
     */
    private static void reconcileBlueprints(ServerLevel level) {
        if (level == null || blueprintsReconciled) return;
        blueprintsReconciled = true;
        int retired = ConstructionManager.get(level).reconcileBlueprints(level);
        if (retired > 0) {
            LOGGER.warn("[Colony] retired {} project(s) whose blueprint could not be rebuilt",
                    retired);
            ai.minecivilization.telemetry.ColonyEventLog.of(level).organisation(level,
                    retired + " unbuildable project(s) were abandoned");
        }
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

        // Every district eventually gets a post saying what it is and a double
        // chest to start its first building from — but only a couple at a time.
        //
        // Without the cap, a colony of sixteen laid out twenty-nine districts
        // and immediately planned twenty-nine markers, all at 0%. They are
        // cheap individually and ruinous collectively: they crowd out real
        // buildings in every queue that counts projects, and a settlement whose
        // entire construction backlog is signposts builds nothing at all.
        int pendingMarkers = 0;
        for (ConstructionProject project : ConstructionManager.get(level).all()) {
            if (project.status == ConstructionProject.Status.COMPLETED
                    || project.status == ConstructionProject.Status.FAILED) continue;
            if (ai.minecivilization.colony.DistrictMarker.isMarker(project.blueprintId)) {
                pendingMarkers++;
            }
        }
        if (pendingMarkers >= MAX_PENDING_MARKERS) return;

        // Nearest district first, so the town marks itself outward from its
        // centre instead of starting with an empty plot on the far rim.
        BlockPos centre = ZoneManager.get(level).townCenter(level);
        List<Zone> districts = ZoneManager.get(level).all();
        districts.sort(java.util.Comparator.comparingDouble(z -> z.distanceSqrTo(centre)));
        for (Zone zone : districts) {
            if (ai.minecivilization.colony.DistrictMarker.ensureFor(level, zone) != null) {
                return;
            }
        }
    }

    /**
     * A minute-by-minute picture of the whole colony.
     *
     * <p>Failures alone never told the story: a settlement quietly working
     * looked identical in the log to one stuck in a loop. This prints the
     * numbers that distinguish them — how many citizens are productive, what
     * is under construction and what it is waiting for, what the roads and
     * districts add up to, and a full line per citizen including anyone lost
     * or stuck.</p>
     */
    private static void logHeartbeat(ServerLevel level) {
        if (level == null) return;
        if (CitizenIndex.all().isEmpty()) return;

        var snapshot = ai.minecivilization.telemetry.ColonySnapshot.take(level, 0);
        var census = snapshot.productivity();

        LOGGER.info("[Colony] {}", snapshot.headline());
        LOGGER.info("[Colony] {}% busy — working {} building {} exploring {} "
                        + "returning {} idle {} stuck {} lost {}",
                census.busyPercent(),
                census.count(ai.minecivilization.work.ProductivityMonitor.State.WORKING),
                census.count(ai.minecivilization.work.ProductivityMonitor.State.BUILDING),
                census.count(ai.minecivilization.work.ProductivityMonitor.State.EXPLORING),
                census.count(ai.minecivilization.work.ProductivityMonitor.State.RETURNING),
                census.count(ai.minecivilization.work.ProductivityMonitor.State.IDLE),
                census.count(ai.minecivilization.work.ProductivityMonitor.State.STUCK),
                census.count(ai.minecivilization.work.ProductivityMonitor.State.LOST));
        // Where the time actually went. A snapshot says what everyone is doing
        // now; this says what they have been doing, which is the question.
        var breakdown = ai.minecivilization.telemetry.ActivityLedger.breakdown();
        if (!breakdown.isEmpty()) {
            StringBuilder split = new StringBuilder();
            for (var slice : breakdown) {
                if (slice.percent() < 1) continue;
                if (split.length() > 0) split.append("  ");
                split.append(slice.percent()).append("% ").append(slice.label());
            }
            LOGGER.info("[Colony] time spent ({}s observed, {}% wasted): {}",
                    ai.minecivilization.telemetry.ActivityLedger.observedSeconds(),
                    ai.minecivilization.telemetry.ActivityLedger.wastedPercent(), split);
        }
        LOGGER.info("[Colony] {} district(s), {} container(s), {} bed(s), {} sign(s), "
                        + "{} road(s), food {} — growth: {}",
                snapshot.districts().size(), snapshot.containers(), snapshot.beds(),
                snapshot.signs(), snapshot.roads().size(), snapshot.food(),
                snapshot.growthBlocker() == null ? "ready" : snapshot.growthBlocker());

        // What the colony is physically building. This is the line that says
        // whether resources are turning into a settlement or into a pile.
        for (var project : snapshot.activeProjects()) {
            LOGGER.info("[Colony]   build {} [{}] {}% at {},{},{}{}",
                    project.name(), project.status(), project.percent(),
                    project.origin().getX(), project.origin().getY(), project.origin().getZ(),
                    project.waitingFor().isBlank() ? "" : "  waiting for " + project.waitingFor());
        }

        for (var citizen : snapshot.citizens()) {
            LOGGER.info("[Colony]   {}", citizen.summaryLine());
        }
        // Anybody in trouble gets their full readout, because that is the case
        // somebody will want to debug.
        for (var citizen : snapshot.inTrouble()) {
            for (String line : citizen.lines()) LOGGER.info("[Colony] {}", line);
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

        // A dead citizen holds nothing. Leaving its claims to time out would
        // reserve that job against the living for a full minute.
        ai.minecivilization.work.GlobalTaskPool.release(citizen);
        // The cell claims are keyed by citizen id, not entity UUID — see
        // BuildBlueprintSkill, which is what takes them.
        ai.minecivilization.construction.WorkClaimStore.releaseOwner(
                String.valueOf(citizen.getIdentity().citizenId));

        ColonyData.get(level).recordDeath();
        String killer = event.getSource().getEntity() == null
                ? event.getSource().getMsgId()
                : event.getSource().getEntity().getName().getString();
        ColonyNotifier.death(level, citizen.getIdentity().name, killer);

        // A death is the colony's most expensive lesson, and it used to learn
        // nothing from it. Now the place goes on the map as dangerous, and any
        // road passing through it stops being improved and starts being
        // avoided — which is how a settlement routes around the ravine instead
        // of paving a path into it.
        BlockPos where = citizen.blockPosition();
        ai.minecivilization.roads.PathMemory.get(level).reportDanger(level, where);
        ai.minecivilization.telemetry.ColonyEventLog.of(level).danger(level,
                citizen.getIdentity().name,
                "died at " + where.getX() + "," + where.getY() + "," + where.getZ()
                        + " (" + killer + ") — the place is now marked dangerous");
        ai.minecivilization.colony.SignRegistry.get(level).record(level, where,
                ai.minecivilization.colony.SignKind.DANGER, "DANGER",
                killer.toUpperCase(java.util.Locale.ROOT), true);
    }

    /** A chest put down by anyone joins the settlement warehouse at once. */
    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            StorageDiscovery.onContainerPlaced(level, event.getPos());
            ai.minecivilization.colony.LandmarkRegistry.get(level)
                    .notice(level, event.getPos());
            // A sign a player puts up is an instruction to the colony: reading
            // it back is what makes signage a two-way interface rather than
            // decoration the citizens produce and ignore.
            ai.minecivilization.colony.SignRegistry.get(level)
                    .readFromWorld(level, event.getPos());
        }
    }

    /** A broken chest leaves the registry, so nobody walks to a hole. */
    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            StorageDiscovery.onContainerRemoved(level, event.getPos());
            ai.minecivilization.colony.LandmarkRegistry.get(level).forget(event.getPos());
            ai.minecivilization.colony.SignRegistry.get(level).forget(event.getPos());
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
        BlockPos anchor = housingAnchor(level, manager);

        List<ConstructionProject> houses = new ArrayList<>();
        List<ConstructionProject> unhomed = new ArrayList<>();  // houses owning no blocks yet
        List<CampLayout.Rect> occupied = new ArrayList<>();     // everything fixed in place
        for (ConstructionProject p : manager.all()) {
            HouseCatalog.ensureRegistered(level, p.blueprintId);
            boolean house = HouseCatalog.isHouse(p.blueprintId);
            if (house) houses.add(p);
            if (house && p.placed.isEmpty() && p.ownedCells.isEmpty()) {
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
        int houseW = houseBlueprint.footprintWidth();
        int houseD = houseBlueprint.footprintDepth();

        // 1) Leave unhomed houses alone when they already sit on a free camp
        //    slot — re-running the check must never shuffle the row.
        List<ConstructionProject> pending = new ArrayList<>();
        for (ConstructionProject p : unhomed) {
            CampLayout.Rect r = footprint(p);
            if (CampLayout.isSlot(anchor.getX(), anchor.getZ(), r.x, r.z)
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
            int originX = x - blueprintMinX(p);
            int originZ = z - blueprintMinZ(p);
            manager.relocate(p, originX, surfaceY(level, x, z, houseW, houseD), originZ);
            occupied.add(footprint(p));
        }

        // 3) Plan one more house while need outgrows the camp.
        // One house per two citizens, as before, but now actually allowed to
        // keep up with a growing population.
        int desired = Math.min(MAX_HOUSES, (population + 1) / 2);
        if (fulfilled >= desired || houses.size() >= MAX_HOUSES) return;
        int slot = CampLayout.freeSlot(anchor.getX(), anchor.getZ(), houseW, houseD, occupied);
        if (slot < 0) return;
        int x = CampLayout.slotX(anchor.getX(), slot);
        int z = CampLayout.slotZ(anchor.getZ());
        int originX = x - houseBlueprint.minX;
        int originZ = z - houseBlueprint.minZ;
        manager.createProject(houseBlueprint.name + " " + (houses.size() + 1),
                houseBlueprint.id, originX, surfaceY(level, x, z, houseW, houseD), originZ,
                level.getGameTime());
    }

    /**
     * Where the colony's houses go.
     *
     * <p>The residential district once the land registry has allotted one,
     * and the camp beside the player's bed until then. That single indirection
     * is most of what turns a row of huts by a bed into a town with a
     * neighbourhood: houses cluster where housing belongs, and the civic
     * centre, warehouses and workshops get their own ground.</p>
     */
    private static BlockPos housingAnchor(ServerLevel level, ConstructionManager manager) {
        Zone housing = ZoneManager.get(level).nearest(
                ai.minecivilization.colony.ZoneType.RESIDENTIAL,
                ZoneManager.get(level).townCenter(level));
        if (housing == null) return manager.resolveAnchor(level);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                housing.centerX(), housing.centerZ());
        return new BlockPos(housing.centerX(), y, housing.centerZ());
    }

    /** Footprint in world coordinates, including an architectural overhang. */
    private static CampLayout.Rect footprint(ConstructionProject p) {
        Blueprint bp = ConstructionManager.blueprint(p.blueprintId);
        if (bp == null) {
            return new CampLayout.Rect(p.originX, p.originZ, 1, 1);
        }
        return new CampLayout.Rect(p.originX + bp.minX, p.originZ + bp.minZ,
                bp.footprintWidth(), bp.footprintDepth());
    }

    private static int blueprintMinX(ConstructionProject project) {
        Blueprint bp = ConstructionManager.blueprint(project.blueprintId);
        return bp == null ? 0 : bp.minX;
    }

    private static int blueprintMinZ(ConstructionProject project) {
        Blueprint bp = ConstructionManager.blueprint(project.blueprintId);
        return bp == null ? 0 : bp.minZ;
    }

    /** First air above solid ground; blueprint origin is a build datum, not a buried block. */
    private static int surfaceY(ServerLevel level, int x, int z, int sizeX, int sizeZ) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                x + sizeX / 2, z + sizeZ / 2);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CitizenIndex.clear();
        blueprintsReconciled = false;
        ColonyCensus.reset();
        ColonyChunkLoader.reset();
        ai.minecivilization.colony.CitizenChunkLoader.reset();
        ColonyLife.reset();
        ai.minecivilization.construction.WorkClaimStore.clear();
        ai.minecivilization.work.GlobalTaskPool.clear();
        ai.minecivilization.work.ColonyWork.reset();
        ai.minecivilization.work.ProductivityMonitor.reset();
        ai.minecivilization.telemetry.ColonyEventLog.clear();
        ai.minecivilization.telemetry.CitizenTracker.reset();
        ai.minecivilization.colony.CitizenMarkers.reset();
        ai.minecivilization.network.ModNetwork.reset();
        ai.minecivilization.telemetry.ActivityLedger.reset();
    }
}
