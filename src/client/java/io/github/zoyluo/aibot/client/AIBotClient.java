package io.github.zoyluo.aibot.client;

import io.github.zoyluo.aibot.client.screen.BotPanelScreen;
import io.github.zoyluo.aibot.client.voice.AIBotVoiceController;
import io.github.zoyluo.aibot.network.payload.AIPayloads;
import io.github.zoyluo.aibot.network.payload.BotCommandC2S;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class AIBotClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AIPayloads.register();
        AIBotKeyBindings.register();
        AIBotClientNetworking.register();
        TargetMarkerClient.register();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        HudRenderCallback.EVENT.register((context, tickCounter) -> BrainTraceHud.render(context));
        registerVoiceCommand();
    }

    // 语音配置热加载走客户端命令(配置是客户端侧文件,不适合放服务端的 /aibot 树)
    private void registerVoiceCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("aibotvoice")
                        .then(ClientCommandManager.literal("reload").executes(context -> {
                            context.getSource().sendFeedback(
                                    Text.literal("[AIBot] " + AIBotVoiceController.INSTANCE.reload()));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("status").executes(context -> {
                            context.getSource().sendFeedback(
                                    Text.literal("[AIBot] 语音：" + AIBotVoiceController.INSTANCE.statusLine()));
                            return 1;
                        }))));
    }

    private void onClientTick(MinecraftClient client) {
        AIBotVoiceController.INSTANCE.tick(client,
                AIBotKeyBindings.pushToTalkDown(client),
                AIBotKeyBindings.audiencePushToTalkDown(client));
        while (AIBotKeyBindings.traceTogglePressed()) {
            BrainTraceHud.toggle();
        }
        while (AIBotKeyBindings.interruptPressed()) {
            if (client.player != null && ClientPlayNetworking.canSend(BotCommandC2S.ID)) {
                ClientPlayNetworking.send(new BotCommandC2S("", "brain_abort", "", "", 0));
                BrainTraceHud.add("", "== 打断指令已发送");
            }
        }
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
