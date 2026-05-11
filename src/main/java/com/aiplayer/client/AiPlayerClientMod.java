package com.aiplayer.client;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.entity.AiPlayerEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.ResourceLocation;

public class AiPlayerClientMod implements ClientModInitializer {
    private static final ResourceLocation AI_PLAYER_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("aiplayer", "textures/entity/aiplayer.png");

    @Override
    public void onInitializeClient() {
        KeyBindings.registerKeys();
        ClientEventHandler.register();

        EntityRendererRegistry.register(AiPlayerMod.AI_PLAYER_ENTITY, context ->
            new HumanoidMobRenderer<AiPlayerEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>>(
                context,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)),
                0.5F
            ) {
                @Override
                public HumanoidRenderState createRenderState() {
                    return new HumanoidRenderState();
                }

                @Override
                public ResourceLocation getTextureLocation(HumanoidRenderState state) {
                    return AI_PLAYER_TEXTURE;
                }
            }
        );
    }
}
