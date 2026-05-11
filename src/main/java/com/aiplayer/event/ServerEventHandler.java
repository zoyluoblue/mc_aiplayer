package com.aiplayer.event;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.memory.StructureRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class ServerEventHandler {
    private static boolean staleEntitiesCleared = false;

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (staleEntitiesCleared) {
                return;
            }

            var level = handler.player.serverLevel();
            AiPlayerMod.getAiPlayerManager().clearAllAiPlayers();
            StructureRegistry.clear();

            int removedCount = 0;
            for (var entity : level.getAllEntities()) {
                if (entity instanceof AiPlayerEntity) {
                    entity.discard();
                    removedCount++;
                }
            }

            AiPlayerMod.info("system", "Removed {} stale AI player entities on first player join", removedCount);
            staleEntitiesCleared = true;
        });
    }
}
