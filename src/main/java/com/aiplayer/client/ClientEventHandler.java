package com.aiplayer.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;

public class ClientEventHandler {
    private static boolean narratorDisabled = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientEventHandler::onClientTick);
    }

    private static void onClientTick(Minecraft client) {
        if (!narratorDisabled && client.options != null) {
            client.options.narrator().set(NarratorStatus.OFF);
            client.options.save();
            narratorDisabled = true;
        }
        if (client.options != null && client.options.pauseOnLostFocus) {
            client.options.pauseOnLostFocus = false;
            client.options.save();
        }

        if (KeyBindings.consumeToggle(client)) {
            AiPlayerGUI.toggle();
        }

        AiPlayerGUI.tick();
    }
}
