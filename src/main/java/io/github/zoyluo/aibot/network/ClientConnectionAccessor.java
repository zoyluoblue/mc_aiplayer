package io.github.zoyluo.aibot.network;

import io.netty.channel.Channel;

public interface ClientConnectionAccessor {
    void aibot$setChannel(Channel channel);
}
