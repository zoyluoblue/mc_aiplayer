package io.github.zoyluo.aibot.client;

import io.github.zoyluo.aibot.network.payload.BotCommandC2S;
import io.github.zoyluo.aibot.network.payload.BotItemMoveC2S;
import io.github.zoyluo.aibot.network.payload.BotTeleportC2S;
import io.github.zoyluo.aibot.network.payload.SetOptionC2S;
import io.github.zoyluo.aibot.network.payload.SubscribeBotC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

public final class BotCommandBridge {
    private BotCommandBridge() {
    }

    public static boolean hasPermission() {
        MinecraftClient client = MinecraftClient.getInstance();
        // Owner authorization is server-side and depends on the selected Bot; the client cannot
        // infer it from OP level. This probe only means that a player connection exists.
        return client.player != null;
    }

    public static void subscribe(String botName, boolean subscribe) {
        if (ClientPlayNetworking.canSend(SubscribeBotC2S.ID)) {
            ClientPlayNetworking.send(new SubscribeBotC2S(botName, subscribe));
        }
    }

    public static void chat(String botName, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        BotClientState.INSTANCE.addTranscript("user", text.trim());
        if (ClientPlayNetworking.canSend(BotCommandC2S.ID)) {
            ClientPlayNetworking.send(new BotCommandC2S(botName, "chat", text.trim(), "", 1));
        } else {
            sendChatMessage("@" + botName + " " + text.trim());
        }
    }

    public static void command(String botName, String action, String arg1, String arg2, int count) {
        if (ClientPlayNetworking.canSend(BotCommandC2S.ID)) {
            ClientPlayNetworking.send(new BotCommandC2S(clean(botName), action, clean(arg1), clean(arg2), count));
            return;
        }
        sendCommand(fallbackCommand(clean(botName), action, clean(arg1), clean(arg2), Math.max(1, count)));
    }

    private static String fallbackCommand(String botName, String action, String arg1, String arg2, int count) {
        return switch (action) {
            case "move" -> "aibot task assign " + botName + " move " + arg1;
            case "mine" -> "aibot task assign " + botName + " mine " + arg1 + " " + count;
            case "craft" -> "aibot task assign " + botName + " craft " + arg1 + " " + count;
            case "smelt" -> "aibot task assign " + botName + " smelt " + arg1 + " " + arg2 + " " + count;
            case "eat" -> "aibot task assign " + botName + " eat";
            case "sleep" -> "aibot task assign " + botName + " sleep";
            case "abort" -> "aibot task abort " + botName;
            case "pause" -> "aibot task pause " + botName;
            case "resume" -> "aibot task resume " + botName;
            case "reset" -> "aibot brain reset " + botName;
            default -> "aibot status";
        };
    }

    private static void sendCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
    }

    /** 在 owner 与自己的 AI 之间移动物品；服务端统一复核 owner/OP。 */
    public static void moveItem(String botName, int direction, int slot, int amount) {
        if (ClientPlayNetworking.canSend(BotItemMoveC2S.ID)) {
            ClientPlayNetworking.send(new BotItemMoveC2S(clean(botName), direction, slot, amount));
        }
    }

    /** 传送。direction:BotTeleportC2S.TO_AI(玩家→AI 附近)/ RECALL_AI(AI→玩家附近)。 */
    public static void teleport(String botName, int direction) {
        if (ClientPlayNetworking.canSend(BotTeleportC2S.ID)) {
            ClientPlayNetworking.send(new BotTeleportC2S(clean(botName), direction));
        }
    }

    public static void setOption(String botName, String key, boolean value) {
        if (ClientPlayNetworking.canSend(SetOptionC2S.ID)) {
            ClientPlayNetworking.send(new SetOptionC2S(botName == null ? "" : botName, key, value));
        }
    }

    private static void sendChatMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatMessage(message);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
