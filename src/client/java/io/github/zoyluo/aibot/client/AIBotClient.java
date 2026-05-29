package io.github.zoyluo.aibot.client;

import io.github.zoyluo.aibot.client.screen.BotPanelScreen;
import io.github.zoyluo.aibot.network.payload.AIPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

public final class AIBotClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AIPayloads.register();
        AIBotKeyBindings.register();
        AIBotClientNetworking.register();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        BotPanelScreen.Mode mode = AIBotKeyBindings.pollToggle(client);
        if (mode == null) {
            return;
        }
        if (client.currentScreen instanceof BotPanelScreen panel && panel.mode() == mode) {
            client.setScreen(null);
        } else {
            client.setScreen(new BotPanelScreen(mode));
        }
    }
}
