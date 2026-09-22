package ai.minecivilization.events;

import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.network.AiBridge;
import ai.minecivilization.registry.ModEntities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Global Forge (game-bus) events plus the mod-bus attribute registration.
 *
 * <p>The AI bridge only ever mutates world state here: HTTP threads enqueue
 * results, and they are applied on the server thread by {@link AiBridge#drain()}.
 * Minecraft never blocks on the AI service.</p>
 */
public final class ModEvents {
    private ModEvents() {
    }

    /** Mod bus: entity attributes must be registered before any citizen spawns. */
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.CITIZEN.get(), CitizenEntity.createAttributes().build());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        AiBridge.drain();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CitizenIndex.clear();
    }
}
