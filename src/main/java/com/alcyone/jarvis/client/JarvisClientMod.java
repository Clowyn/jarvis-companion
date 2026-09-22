package com.alcyone.jarvis.client;

import com.alcyone.jarvis.JarvisMod;
import com.alcyone.jarvis.entity.ModEntities;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public class JarvisClientMod {

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.JARVIS_COMPANION.get(), JarvisCompanionRenderer::new);
        JarvisMod.LOGGER.info("[Jarvis] Registered client EntityRenderer for JarvisCompanionEntity");
    }
}
