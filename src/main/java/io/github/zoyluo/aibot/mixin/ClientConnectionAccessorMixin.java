package io.github.zoyluo.aibot.mixin;

import io.github.zoyluo.aibot.network.ClientConnectionAccessor;
import io.netty.channel.Channel;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionAccessorMixin implements ClientConnectionAccessor {
    @Override
    @Accessor("channel")
    public abstract void aibot$setChannel(Channel channel);
}
