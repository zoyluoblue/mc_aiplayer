package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Authoritative marker state for the owning client. */
public record TargetMarkerS2C(boolean active,
                              String dimension,
                              BlockPos blockPos,
                              int faceId,
                              BlockPos standPos,
                              String message) implements CustomPayload {
    public static final Id<TargetMarkerS2C> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "target_marker_state"));
    public static final PacketCodec<RegistryByteBuf, TargetMarkerS2C> CODEC =
            PacketCodec.of(TargetMarkerS2C::write, TargetMarkerS2C::new);

    private TargetMarkerS2C(RegistryByteBuf buf) {
        this(buf.readBoolean(), buf.readString(), buf.readBlockPos(), buf.readVarInt(),
                buf.readBlockPos(), buf.readString());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeString(dimension);
        buf.writeBlockPos(blockPos);
        buf.writeVarInt(faceId);
        buf.writeBlockPos(standPos);
        buf.writeString(message);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
