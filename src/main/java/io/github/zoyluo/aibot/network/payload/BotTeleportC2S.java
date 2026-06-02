package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 客户端请求传送。
 * direction:0=TO_AI(把玩家传送到 AI 附近可站立方块)、1=RECALL_AI(把 AI 传送到玩家附近可站立方块)。
 */
public record BotTeleportC2S(String botName, int direction) implements CustomPayload {
    public static final int TO_AI = 0;
    public static final int RECALL_AI = 1;

    public static final Id<BotTeleportC2S> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "teleport"));
    public static final PacketCodec<RegistryByteBuf, BotTeleportC2S> CODEC =
            PacketCodec.of(BotTeleportC2S::write, BotTeleportC2S::new);

    private BotTeleportC2S(RegistryByteBuf buf) {
        this(buf.readString(), buf.readVarInt());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeString(botName);
        buf.writeVarInt(direction);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
