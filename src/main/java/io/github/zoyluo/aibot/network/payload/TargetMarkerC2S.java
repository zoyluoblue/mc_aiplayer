package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Shift+middle-click target marker request. Inactive requests clear the current marker. */
public record TargetMarkerC2S(boolean active, String dimension, BlockPos blockPos, int faceId)
        implements CustomPayload {
    public static final Id<TargetMarkerC2S> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "target_marker"));
    public static final PacketCodec<RegistryByteBuf, TargetMarkerC2S> CODEC =
            PacketCodec.of(TargetMarkerC2S::write, TargetMarkerC2S::new);

    private TargetMarkerC2S(RegistryByteBuf buf) {
        this(buf.readBoolean(), buf.readString(), buf.readBlockPos(), buf.readVarInt());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeString(dimension);
        buf.writeBlockPos(blockPos);
        buf.writeVarInt(faceId);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
