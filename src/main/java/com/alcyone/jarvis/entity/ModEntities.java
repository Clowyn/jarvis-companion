package com.alcyone.jarvis.entity;

import com.alcyone.jarvis.JarvisMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry holder for Jarvis Companion entities.
 */
public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, JarvisMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<JarvisCompanionEntity>> JARVIS_COMPANION =
        ENTITY_TYPES.register("companion", () ->
            EntityType.Builder.<JarvisCompanionEntity>of(JarvisCompanionEntity::new, MobCategory.MISC)
                .sized(0.6F, 1.8F)
                .eyeHeight(1.62F)
                .clientTrackingRange(10)
                .updateInterval(2)
                .build("jarvis:companion")
        );

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(ModEntities::registerAttributes);
        modEventBus.addListener(ModEntities::registerCapabilities);
    }

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(JARVIS_COMPANION.get(), JarvisCompanionEntity.createAttributes().build());
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerEntity(
            Capabilities.ItemHandler.ENTITY,
            JARVIS_COMPANION.get(),
            (entity, context) -> entity.getInventory()
        );
    }
}
