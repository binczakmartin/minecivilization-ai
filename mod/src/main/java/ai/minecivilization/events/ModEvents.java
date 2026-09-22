package ai.minecivilization.events;

import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.construction.StarterHouse;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.network.AiBridge;
import ai.minecivilization.registry.ModEntities;
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
    /** How often (ticks) the starter-house plan near spawn is reviewed. */
    private static final int HOUSE_CHECK_INTERVAL = 100;
    /** Hard cap on houses auto-planned near spawn (grows with population). */
    private static final int MAX_HOUSES = 4;
    /** Spacing between house origins along X, so the row never overlaps. */
    private static final int HOUSE_SPACING = 9;

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
     * Keep a small row of starter-house projects within sight of spawn:
     * one house per ~2 citizens, capped at {@link #MAX_HOUSES}. Completed
     * houses count toward the need, so growth — not churn — adds projects.
     */
    private static void ensureStarterHouses(ServerLevel level) {
        if (level == null) return;
        int population = CitizenIndex.population();
        if (population <= 0) return;

        ConstructionManager manager = ConstructionManager.get(level);
        int fulfilled = 0;   // houses being built or already standing
        int total = 0;       // every house project ever planned (slot allocator)
        for (ConstructionProject p : manager.all()) {
            if (!StarterHouse.ID.equals(p.blueprintId)) continue;
            total++;
            if (p.status != ConstructionProject.Status.FAILED) {
                fulfilled++;
            }
        }
        int desired = Math.min(MAX_HOUSES, (population + 1) / 2);
        if (fulfilled >= desired || total >= MAX_HOUSES) return;

        var spawn = level.getSharedSpawnPos();
        int originX = spawn.getX() + 6 + total * HOUSE_SPACING;
        int originZ = spawn.getZ() - 2;
        int originY = level.getHeight(Heightmap.Types.WORLD_SURFACE, originX + 2, originZ + 2) - 1;
        manager.createProject("Starter House " + (total + 1), StarterHouse.ID,
                originX, originY, originZ, level.getGameTime());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CitizenIndex.clear();
    }
}
