package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetOptionC2S(String botName, String key, boolean value) implements CustomPayload {
    public static final Id<SetOptionC2S> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "set_option"));
    public static final PacketCodec<RegistryByteBuf, SetOptionC2S> CODEC = PacketCodec.of(SetOptionC2S::write, SetOptionC2S::new);

    private SetOptionC2S(RegistryByteBuf buf) {
        this(buf.readString(), buf.readString(), buf.readBoolean());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeString(botName);
        buf.writeString(key);
        buf.writeBoolean(value);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
