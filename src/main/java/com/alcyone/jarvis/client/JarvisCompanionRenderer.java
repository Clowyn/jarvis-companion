package com.alcyone.jarvis.client;

import com.alcyone.jarvis.entity.JarvisCompanionEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side renderer for the Jarvis Companion humanoid NPC.
 * Renders with a standard player model and texture so Iris/Sodium and the vanilla renderer do not crash.
 */
public class JarvisCompanionRenderer extends HumanoidMobRenderer<JarvisCompanionEntity, HumanoidModel<JarvisCompanionEntity>> {
    private static final ResourceLocation TEXTURE = ResourceLocation.parse("minecraft:textures/entity/player/wide/steve.png");

    public JarvisCompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(JarvisCompanionEntity entity) {
        return TEXTURE;
    }
}
