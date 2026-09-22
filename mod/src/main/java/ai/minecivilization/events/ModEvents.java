package ai.minecivilization.events;

import ai.minecivilization.construction.Blueprint;
import ai.minecivilization.construction.CampLayout;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.construction.StarterHouse;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.network.AiBridge;
import ai.minecivilization.registry.ModEntities;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
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
    /** How often (ticks) the camp's starter-house plan is reviewed. */
    private static final int HOUSE_CHECK_INTERVAL = 100;
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
        if (++houseCheckTick % HOUSE_CHECK_INTERVAL != 0) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            ensureStarterHouses(server.getLevel(Level.OVERWORLD));
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
            boolean house = StarterHouse.ID.equals(p.blueprintId);
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

        Blueprint houseBlueprint = ConstructionManager.blueprint(StarterHouse.ID);
        int houseW = houseBlueprint == null ? 1 : houseBlueprint.sizeX;
        int houseD = houseBlueprint == null ? 1 : houseBlueprint.sizeZ;

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
        manager.createProject("Starter House " + (houses.size() + 1), StarterHouse.ID,
                x, surfaceY(level, x, z, houseW, houseD), z, level.getGameTime());
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
    }
}
