package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record AudienceSnapshotS2C(
        List<ViewerEntry> viewers,
        String boundViewerKey,
        String boundViewerName,
        String audienceBotName,
        String status
) implements CustomPayload {
    private static final int MAX_VIEWERS = 500;

    public static final Id<AudienceSnapshotS2C> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "audience_snapshot"));
    public static final PacketCodec<RegistryByteBuf, AudienceSnapshotS2C> CODEC =
            PacketCodec.of(AudienceSnapshotS2C::write, AudienceSnapshotS2C::new);

    private AudienceSnapshotS2C(RegistryByteBuf buf) {
        this(readViewers(buf), buf.readString(160), buf.readString(64), buf.readString(32), buf.readString(160));
    }

    private void write(RegistryByteBuf buf) {
        int size = Math.min(MAX_VIEWERS, viewers == null ? 0 : viewers.size());
        buf.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            ViewerEntry viewer = viewers.get(index);
            buf.writeString(viewer.key(), 160);
            buf.writeString(viewer.displayName(), 64);
            buf.writeBoolean(viewer.reliableIdentity());
            buf.writeString(viewer.lastKind(), 16);
            buf.writeLong(viewer.lastSeenMillis());
        }
        buf.writeString(boundViewerKey == null ? "" : boundViewerKey, 160);
        buf.writeString(boundViewerName == null ? "" : boundViewerName, 64);
        buf.writeString(audienceBotName == null ? "" : audienceBotName, 32);
        buf.writeString(status == null ? "" : status, 160);
    }

    private static List<ViewerEntry> readViewers(RegistryByteBuf buf) {
        int size = Math.min(MAX_VIEWERS, Math.max(0, buf.readVarInt()));
        List<ViewerEntry> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(new ViewerEntry(
                    buf.readString(160),
                    buf.readString(64),
                    buf.readBoolean(),
                    buf.readString(16),
                    buf.readLong()));
        }
        return List.copyOf(result);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record ViewerEntry(String key, String displayName, boolean reliableIdentity,
                              String lastKind, long lastSeenMillis) {
    }
}
