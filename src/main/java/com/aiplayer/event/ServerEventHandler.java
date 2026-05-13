package com.aiplayer.event;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.memory.StructureRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class ServerEventHandler {
    private static boolean staleEntitiesCleared = false;

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (staleEntitiesCleared) {
                return;
            }

            StructureRegistry.clear();
            AiPlayerMod.info("system", "Preserved AI player entities on first player join");
            staleEntitiesCleared = true;
        });
    }
}
