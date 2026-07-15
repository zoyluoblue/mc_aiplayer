package io.github.zoyluo.aibot.client;

import io.github.zoyluo.aibot.client.voice.AIBotVoiceController;
import io.github.zoyluo.aibot.network.payload.BotChatS2C;
import io.github.zoyluo.aibot.network.payload.AudienceSnapshotS2C;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import io.github.zoyluo.aibot.network.payload.BrainTraceS2C;
import io.github.zoyluo.aibot.network.payload.TargetMarkerS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class AIBotClientNetworking {
    private AIBotClientNetworking() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(AudienceSnapshotS2C.ID, (payload, context) ->
                context.client().execute(() -> AudienceClientState.INSTANCE.setSnapshot(payload)));
        ClientPlayNetworking.registerGlobalReceiver(BotSnapshotS2C.ID, (payload, context) ->
                context.client().execute(() -> BotClientState.INSTANCE.setSnapshot(payload)));
        ClientPlayNetworking.registerGlobalReceiver(BotChatS2C.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (BotClientState.INSTANCE.matchesTarget(payload.botName())) {
                        BotClientState.INSTANCE.addTranscript(payload.role(), payload.text());
                        AIBotVoiceController.INSTANCE.onBotChat(payload.botName(), payload.role(), payload.text());
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(BrainTraceS2C.ID, (payload, context) ->
                context.client().execute(() -> BrainTraceHud.add(payload.botName(), payload.line())));
        ClientPlayNetworking.registerGlobalReceiver(TargetMarkerS2C.ID, (payload, context) ->
                context.client().execute(() -> TargetMarkerClient.apply(payload)));
    }
}
