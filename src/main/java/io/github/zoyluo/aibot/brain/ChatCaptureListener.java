package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.log.BotLog;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ChatCaptureListener {
    private static final Pattern MENTION = Pattern.compile("@(\\w+)\\s+(.+)");

    private ChatCaptureListener() {
    }

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getContent().getString();
            var matcher = MENTION.matcher(text);
            String targetName;
            String body;
            if (matcher.find()) {
                targetName = matcher.group(1);
                body = matcher.group(2);
            } else {
                // BUGFIX: без @ — все сообщения идут боту (если один бот)
                Collection<AIPlayerEntity> bots = AIPlayerManager.INSTANCE.all();
                if (bots.isEmpty()) return;
                if (bots.size() == 1) {
                    targetName = bots.iterator().next().getGameProfile().getName();
                    body = text;
                } else {
                    // Несколько ботов — ищем имя в тексте
                    Optional<AIPlayerEntity> found = bots.stream()
                            .filter(b -> text.toLowerCase().contains(b.getGameProfile().getName().toLowerCase()))
                            .findFirst();
                    if (found.isEmpty()) return;
                    targetName = found.get().getGameProfile().getName();
                    body = text;
                }
            }
            String finalBody = body;
            AIPlayerManager.INSTANCE.getByName(targetName).ifPresent(bot -> {
                BotLog.comm(bot, "chat_in", "sender", sender.getGameProfile().getName(), "text", finalBody);
                BrainCoordinator.INSTANCE.handleMessage(bot, sender.getGameProfile().getName(), finalBody);
            });
        });
    }
}
