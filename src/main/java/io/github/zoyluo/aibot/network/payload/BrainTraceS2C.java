package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 大脑过程实时跟踪行(左下角 HUD 用):API 请求/响应/工具调用/结果/失败/打断。 */
public record BrainTraceS2C(String botName, String line) implements CustomPayload {
    public static final Id<BrainTraceS2C> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "brain_trace"));
    public static final PacketCodec<RegistryByteBuf, BrainTraceS2C> CODEC = PacketCodec.of(BrainTraceS2C::write, BrainTraceS2C::new);

    private BrainTraceS2C(RegistryByteBuf buf) {
        this(buf.readString(), buf.readString());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeString(botName);
        buf.writeString(line);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
