package io.github.zoyluo.aibot.mixin;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class ServerTickProbeMixin {
    @Unique
    private long aibot$tickCount;

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void aibot$onTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (++aibot$tickCount % 1200L == 0L) {
            AIBotMod.LOGGER.info("[AIBot] mixin alive, server tick={}", aibot$tickCount);
        }
    }
}
