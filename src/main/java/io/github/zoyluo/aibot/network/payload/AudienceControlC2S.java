package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record AudienceControlC2S(int action, String viewerKey) implements CustomPayload {
    public static final int REFRESH = 0;
    public static final int BIND = 1;
    public static final int UNBIND = 2;

    public static final Id<AudienceControlC2S> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "audience_control"));
    public static final PacketCodec<RegistryByteBuf, AudienceControlC2S> CODEC =
            PacketCodec.of(AudienceControlC2S::write, AudienceControlC2S::new);

    private AudienceControlC2S(RegistryByteBuf buf) {
        this(buf.readVarInt(), buf.readString(160));
    }

    private void write(RegistryByteBuf buf) {
        buf.writeVarInt(action);
        buf.writeString(viewerKey == null ? "" : viewerKey, 160);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
