package io.github.zoyluo.aibot.network;

import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.brain.BotRuntimeOptions;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemory;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.network.payload.BotChatS2C;
import io.github.zoyluo.aibot.network.payload.BotCommandC2S;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import io.github.zoyluo.aibot.network.payload.SetOptionC2S;
import io.github.zoyluo.aibot.network.payload.SubscribeBotC2S;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.EatTask;
import io.github.zoyluo.aibot.task.MineTask;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.SmeltTask;
import io.github.zoyluo.aibot.task.SleepTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AIBotServerNetworking {
    public static final AIBotServerNetworking INSTANCE = new AIBotServerNetworking();

    private static final int SNAPSHOT_INTERVAL_TICKS = 10;
    private final Map<UUID, String> subscriptions = new ConcurrentHashMap<>();
    private int snapshotTick;

    private AIBotServerNetworking() {
    }

    public void register() {
        ServerPlayNetworking.registerGlobalReceiver(SubscribeBotC2S.ID, (payload, context) ->
                context.server().execute(() -> handleSubscribe(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(BotCommandC2S.ID, (payload, context) ->
                context.server().execute(() -> handleCommand(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(SetOptionC2S.ID, (payload, context) ->
                context.server().execute(() -> handleSetOption(context.player(), payload)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                subscriptions.remove(handler.player.getUuid()));
    }

    public void tick(MinecraftServer server) {
        snapshotTick++;
        if (snapshotTick % SNAPSHOT_INTERVAL_TICKS != 0 || subscriptions.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, String> entry : subscriptions.entrySet()) {
            ServerPlayerEntity viewer = server.getPlayerManager().getPlayer(entry.getKey());
            if (viewer == null) {
                subscriptions.remove(entry.getKey());
                continue;
            }
            if (!ServerPlayNetworking.canSend(viewer, BotSnapshotS2C.ID)) {
                continue;
            }
            resolveBot(viewer, entry.getValue())
                    .map(this::snapshot)
                    .ifPresent(snapshot -> ServerPlayNetworking.send(viewer, snapshot));
        }
    }

    public void clear() {
        subscriptions.clear();
        snapshotTick = 0;
    }

    public void sendBotChat(AIPlayerEntity bot, String role, String text) {
        if (subscriptions.isEmpty()) {
            return;
        }
        String normalized = normalize(bot.getGameProfile().getName());
        for (Map.Entry<UUID, String> entry : subscriptions.entrySet()) {
            ServerPlayerEntity viewer = bot.getServer().getPlayerManager().getPlayer(entry.getKey());
            if (viewer == null) {
                continue;
            }
            String subscribedName = entry.getValue();
            if (subscribedName == null || subscribedName.isBlank()) {
                subscribedName = AIPlayerManager.INSTANCE.botOf(viewer.getUuid())
                        .map(owned -> owned.getGameProfile().getName())
                        .orElse("");
            }
            if (!normalize(subscribedName).equals(normalized)) {
                continue;
            }
            if (viewer != null && ServerPlayNetworking.canSend(viewer, BotChatS2C.ID)) {
                ServerPlayNetworking.send(viewer, new BotChatS2C(bot.getGameProfile().getName(), role, text));
            }
        }
    }

    private void handleSubscribe(ServerPlayerEntity player, SubscribeBotC2S payload) {
        if (!player.hasPermissionLevel(2)) {
            sendSystem(player, payload.botName(), "需要 OP 权限才能使用 AIBot 面板。");
            return;
        }
        if (payload.subscribe()) {
            String botName = resolveBot(player, payload.botName())
                    .map(bot -> bot.getGameProfile().getName())
                    .orElse(payload.botName());
            subscriptions.put(player.getUuid(), botName);
            resolveBot(player, botName)
                    .map(this::snapshot)
                    .filter(snapshot -> ServerPlayNetworking.canSend(player, BotSnapshotS2C.ID))
                    .ifPresent(snapshot -> ServerPlayNetworking.send(player, snapshot));
            sendSystem(player, botName, botName == null || botName.isBlank() ? "未找到你的 AI 助手。" : "已订阅 " + botName);
        } else {
            subscriptions.remove(player.getUuid());
        }
    }

    private void handleCommand(ServerPlayerEntity player, BotCommandC2S payload) {
        if (!player.hasPermissionLevel(2)) {
            sendSystem(player, payload.botName(), "需要 OP 权限才能控制 AIBot。");
            return;
        }
        Optional<AIPlayerEntity> bot = resolveBot(player, payload.botName());
        if (bot.isEmpty()) {
            sendSystem(player, payload.botName(), "找不到 AI 助手: " + (payload.botName().isBlank() ? "我的助手" : payload.botName()));
            return;
        }
        try {
            dispatch(player, bot.get(), payload);
        } catch (RuntimeException exception) {
            BotLog.error(bot.get(), "panel_command_exception", exception, "action", payload.action());
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            sendSystem(player, payload.botName(), "命令执行失败: " + reason);
        }
    }

    private void handleSetOption(ServerPlayerEntity player, SetOptionC2S payload) {
        if (!player.hasPermissionLevel(2)) {
            sendSystem(player, payload.botName(), "需要 OP 权限才能修改 AIBot 设置。");
            return;
        }
        Optional<AIPlayerEntity> bot = resolveBot(player, payload.botName());
        if (bot.isEmpty()) {
            sendSystem(player, payload.botName(), "找不到 AI 助手: " + (payload.botName().isBlank() ? "我的助手" : payload.botName()));
            return;
        }
        AIPlayerEntity target = bot.get();
        switch (payload.key()) {
            case "manual" -> BrainCoordinator.INSTANCE.setManualMode(target, payload.value());
            case "memory" -> BotRuntimeOptions.INSTANCE.setMemoryToolsEnabled(target, payload.value());
            case "reports" -> BotRuntimeOptions.INSTANCE.setVerboseReportsEnabled(target, payload.value());
            default -> throw new IllegalArgumentException("unknown_option: " + payload.key());
        }
        sendSystem(player, target.getGameProfile().getName(), "设置已更新: " + payload.key() + "=" + payload.value());
    }

    private void dispatch(ServerPlayerEntity player, AIPlayerEntity bot, BotCommandC2S payload) {
        String action = payload.action().toLowerCase(Locale.ROOT);
        switch (action) {
            case "move" -> assign(bot, new MoveTask(bot, parseBlockPos(payload.arg1())));
            case "mine" -> assign(bot, new MineTask(requiredBlock(payload.arg1()), count(payload)));
            case "craft" -> assign(bot, new CraftTask(requiredItem(payload.arg1()), count(payload)));
            case "smelt" -> assign(bot, new SmeltTask(requiredItem(payload.arg1()), requiredItem(payload.arg2()), count(payload)));
            case "eat" -> assign(bot, new EatTask());
            case "sleep" -> assign(bot, new SleepTask());
            case "abort" -> {
                TaskManager.INSTANCE.abort(bot);
                bot.getActionPack().stopAll();
                sendSystem(player, bot.getGameProfile().getName(), "任务已停止。");
            }
            case "chat" -> {
                sendBotChat(bot, "user", payload.arg1());
                BrainCoordinator.INSTANCE.handleMessage(bot, player.getGameProfile().getName(), payload.arg1());
            }
            case "reset" -> {
                BrainCoordinator.INSTANCE.reset(bot);
                sendSystem(player, bot.getGameProfile().getName(), "大脑已重置。");
            }
            default -> throw new IllegalArgumentException("unknown_action: " + payload.action());
        }
    }

    private static void assign(AIPlayerEntity bot, Task task) {
        TaskManager.INSTANCE.assign(bot, task);
    }

    private BotSnapshotS2C snapshot(AIPlayerEntity bot) {
        TaskStatus task = TaskManager.INSTANCE.status(bot);
        BrainCoordinator.BrainStatus brain = BrainCoordinator.INSTANCE.status(bot);
        BotMemory memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
        ArrayList<BotSnapshotS2C.ItemEntry> inventory = new ArrayList<>();
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            ItemStack stack = bot.getInventory().main.get(slot);
            if (!stack.isEmpty()) {
                inventory.add(new BotSnapshotS2C.ItemEntry(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), slot));
            }
        }
        return new BotSnapshotS2C(
                bot.getGameProfile().getName(),
                bot.getHealth(),
                bot.getMaxHealth(),
                bot.getHungerManager().getFoodLevel(),
                task.name(),
                task.state().name(),
                (float) task.progress(),
                brain.busy(),
                brain.promptTokens(),
                brain.completionTokens(),
                memory.goalTitle(),
                memory.currentGoalStep().orElse(""),
                memory.goalCurrentStepIndex(),
                memory.goalTotalSteps(),
                memory.goalSteps(),
                BrainCoordinator.INSTANCE.manualMode(bot),
                BotRuntimeOptions.INSTANCE.memoryToolsEnabled(bot),
                BotRuntimeOptions.INSTANCE.verboseReportsEnabled(bot),
                inventory);
    }

    private void sendSystem(ServerPlayerEntity player, String botName, String text) {
        if (ServerPlayNetworking.canSend(player, BotChatS2C.ID)) {
            ServerPlayNetworking.send(player, new BotChatS2C(botName, "system", text));
        }
    }

    private Optional<AIPlayerEntity> resolveBot(ServerPlayerEntity player, String botName) {
        if (botName == null || botName.isBlank()) {
            return AIPlayerManager.INSTANCE.botOf(player.getUuid());
        }
        return AIPlayerManager.INSTANCE.getByName(botName);
    }

    private static int count(BotCommandC2S payload) {
        return Math.max(1, payload.count());
    }

    private static BlockPos parseBlockPos(String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 3) {
            throw new IllegalArgumentException("move expects arg1='x y z'");
        }
        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static Block requiredBlock(String idText) {
        Identifier id = Identifier.of(idText);
        return Registries.BLOCK.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_block: " + id));
    }

    private static Item requiredItem(String idText) {
        Identifier id = Identifier.of(idText);
        return Registries.ITEM.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_item: " + id));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
