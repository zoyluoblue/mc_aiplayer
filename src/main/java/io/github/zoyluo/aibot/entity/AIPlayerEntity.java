package io.github.zoyluo.aibot.entity;

import com.mojang.authlib.GameProfile;
import io.github.zoyluo.aibot.action.ActionPack;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

public class AIPlayerEntity extends ServerPlayerEntity {
    private final ActionPack actionPack = new ActionPack(this);

    public AIPlayerEntity(MinecraftServer server,
                          ServerWorld world,
                          GameProfile profile,
                          SyncedClientOptions clientOptions) {
        super(server, world, profile, clientOptions);
    }

    @Override
    public void tick() {
        if (this.server.getTicks() % 10 == 0 && this.networkHandler != null) {
            this.networkHandler.syncWithPlayerPosition();
            this.getServerWorld().getChunkManager().updatePosition(this);
        }

        try {
            super.tick();
            this.playerTick();
            this.actionPack.onUpdate();
        } catch (NullPointerException exception) {
            BotLog.error(this, "tick_npe_swallowed", exception);
            // Re-throw as runtime to prevent silent corruption
            throw new RuntimeException("NPE in bot tick - see stack trace above", exception);
        }
    }

    @Override
    public String getIp() {
        return "127.0.0.1";
    }

    public void reviveForAIBotSpawn() {
        this.unsetRemoved();
    }

    public ActionPack getActionPack() {
        return actionPack;
    }
}
