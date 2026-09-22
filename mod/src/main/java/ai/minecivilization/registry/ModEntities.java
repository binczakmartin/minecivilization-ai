package ai.minecivilization.registry;

import ai.minecivilization.MineCivilization;
import ai.minecivilization.entity.CitizenEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * One entity type only: {@link CitizenEntity}. Professions are dynamic data,
 * never separate entity classes.
 */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MineCivilization.MOD_ID);

    public static final RegistryObject<EntityType<CitizenEntity>> CITIZEN =
            ENTITY_TYPES.register("citizen", () -> EntityType.Builder.of(CitizenEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(10)
                    .updateInterval(3)
                    .build("minecivilization:citizen"));

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }

    private ModEntities() {
    }
}
