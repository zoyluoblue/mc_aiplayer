package io.github.zoyluo.aibot.mixin;

import io.github.zoyluo.aibot.client.TargetMarkerClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shift+middle-click marks a target; plain middle-click keeps vanilla pick-block behavior. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMarkerMixin {
    @Inject(method = "doItemPick", at = @At("HEAD"), cancellable = true)
    private void aibot$handleTargetMarker(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (TargetMarkerClient.handleShiftMiddleClick(client)) {
            ci.cancel();
        }
    }
}
