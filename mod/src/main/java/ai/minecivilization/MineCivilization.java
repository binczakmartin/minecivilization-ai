package ai.minecivilization;

import ai.minecivilization.commands.McivCommands;
import ai.minecivilization.config.ModConfig;
import ai.minecivilization.events.ModEvents;
import ai.minecivilization.registry.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * MineCivilization AI — a local AI civilization simulation inside Minecraft.
 *
 * <p>Architecture: the LLM (local, via the cognition service) makes high-level
 * decisions only; deterministic Minecraft code (skills) executes them every tick.
 * The LLM never directly controls the NPC per tick.</p>
 */
@Mod(MineCivilization.MOD_ID)
public final class MineCivilization {
    public static final String MOD_ID = "minecivilization";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MineCivilization(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();

        ModEntities.register(modBus);
        modBus.addListener(this::commonSetup);
        // Entity attributes (mod bus) — the client renderer is registered by
        // client.ClientSetup via @Mod.EventBusSubscriber(Dist.CLIENT).
        modBus.addListener(ModEvents::onEntityAttributeCreation);

        // Game (forge) bus: commands, ticks, entity death, inspection.
        MinecraftForge.EVENT_BUS.register(ModEvents.class);
        MinecraftForge.EVENT_BUS.addListener(McivCommands::onRegisterCommands);

        context.registerConfig(Type.COMMON, ModConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // The colony supervision window's channel. Registered here because a
        // channel must exist before any player connects, and optional in both
        // directions so neither side needs the other to have the mod.
        event.enqueueWork(ai.minecivilization.network.ModNetwork::register);
        LOGGER.info("MineCivilization AI initialized (local cognition, deterministic skills)");
    }
}
