package io.github.zoyluo.aibot.network.payload;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 客户端请求在玩家与 AI 之间移动物品。
 * direction:0=TAKE(从 AI 背包 slot 拿到玩家背包)、1=PUT(把玩家背包 slot 放进 AI 背包)。
 * slot:源容器里的槽位下标(TAKE=AI main 槽,PUT=玩家 inventory main 槽)。
 * amount:期望移动数量(<=0 视为整堆;服务端按实际可移动量裁剪)。
 */
public record BotItemMoveC2S(String botName, int direction, int slot, int amount) implements CustomPayload {
    public static final int TAKE = 0;
    public static final int PUT = 1;

    public static final Id<BotItemMoveC2S> ID = new Id<>(Identifier.of(AIBotMod.MOD_ID, "item_move"));
    public static final PacketCodec<RegistryByteBuf, BotItemMoveC2S> CODEC =
            PacketCodec.of(BotItemMoveC2S::write, BotItemMoveC2S::new);

    private BotItemMoveC2S(RegistryByteBuf buf) {
        this(buf.readString(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeString(botName);
        buf.writeVarInt(direction);
        buf.writeVarInt(slot);
        buf.writeVarInt(amount);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
