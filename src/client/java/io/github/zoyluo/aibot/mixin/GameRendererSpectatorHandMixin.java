package io.github.zoyluo.aibot.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 旁观者摄像机（OBS 导播账号）观战 AI 假人时，原版会因为
 * renderHand 里的 SPECTATOR 闸门跳过第一人称手臂渲染，画面上看不到手。
 * 这里在"正在旁观另一名玩家"时把闸门读到的模式伪装成 SURVIVAL，
 * 让第一人称手照常渲染——手里的物品由服务端 CameraMirror
 * 每 tick 从被观战 bot 镜像到本地玩家选中槽/副手槽。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererSpectatorHandMixin {

    @Redirect(
            method = "renderHand(Lnet/minecraft/client/render/Camera;FLorg/joml/Matrix4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;getCurrentGameMode()Lnet/minecraft/world/GameMode;"
            )
    )
    private GameMode aibot$showHandWhileSpectatingPlayer(ClientPlayerInteractionManager manager) {
        GameMode actual = manager.getCurrentGameMode();
        if (actual == GameMode.SPECTATOR) {
            MinecraftClient client = MinecraftClient.getInstance();
            Entity camera = client.getCameraEntity();
            if (camera instanceof PlayerEntity && camera != client.player) {
                return GameMode.SURVIVAL;
            }
        }
        return actual;
    }
}
