package ai.minecivilization.client;

import ai.minecivilization.MineCivilization;
import ai.minecivilization.registry.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only wiring (renderer registration). Dist.CLIENT keeps this class off
 * dedicated servers so client classes are never loaded there.
 */
@Mod.EventBusSubscriber(modid = MineCivilization.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.CITIZEN.get(), CitizenRenderer::new);
    }
}
