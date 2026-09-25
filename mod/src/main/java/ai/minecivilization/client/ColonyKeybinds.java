package ai.minecivilization.client;

import com.mojang.blaze3d.platform.InputConstants;

import ai.minecivilization.MineCivilization;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Opening the colony window.
 *
 * <p>Bound to {@code K} by default — free in vanilla, next to the other
 * information keys, and rebindable in Controls like anything else. A window you
 * have to type a command to open is a window nobody opens.</p>
 */
@Mod.EventBusSubscriber(modid = MineCivilization.MOD_ID, value = Dist.CLIENT)
public final class ColonyKeybinds {

    private static final String CATEGORY = "key.categories." + MineCivilization.MOD_ID;

    public static final KeyMapping OPEN_COLONY = new KeyMapping(
            "key." + MineCivilization.MOD_ID + ".colony",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_K,
            CATEGORY);

    private ColonyKeybinds() {
    }

    /** Mod bus. */
    @Mod.EventBusSubscriber(modid = MineCivilization.MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_COLONY);
        }
    }

    /**
     * Game bus: react to the key once per press.
     *
     * <p>{@code consumeClick} drains the queued presses, so holding the key
     * opens the window once rather than every frame.</p>
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        boolean pressed = false;
        while (OPEN_COLONY.consumeClick()) pressed = true;
        if (pressed) ColonyClientState.openScreen();
    }

    /** Leaving a world must not leave last session's colony on screen. */
    @SubscribeEvent
    public static void onLoggedOut(
            net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        ColonyClientState.clear();
    }
}
