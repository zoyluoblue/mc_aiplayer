package io.github.zoyluo.aibot.network;

import io.github.zoyluo.aibot.auth.BotAuthorizationGate;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.brain.BotRuntimeOptions;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.memory.BotMemory;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.network.payload.BotChatS2C;
import io.github.zoyluo.aibot.network.payload.BotCommandC2S;
import io.github.zoyluo.aibot.network.payload.BotItemMoveC2S;
import io.github.zoyluo.aibot.network.payload.BotTeleportC2S;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import io.github.zoyluo.aibot.network.payload.SetOptionC2S;
import io.github.zoyluo.aibot.network.payload.SubscribeBotC2S;
import io.github.zoyluo.aibot.runtime.IntentController;
import io.github.zoyluo.aibot.runtime.RuntimeLifecycleCoordinator;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AIBotServerNetworking {
    public static final AIBotServerNetworking INSTANCE = new AIBotServerNetworking();

    private static final int SNAPSHOT_INTERVAL_TICKS = 10;
    private final Map<UUID, UUID> subscriptions = new ConcurrentHashMap<>();
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
        ServerPlayNetworking.registerGlobalReceiver(BotItemMoveC2S.ID, (payload, context) ->
                context.server().execute(() -> handleItemMove(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(BotTeleportC2S.ID, (payload, context) ->
                context.server().execute(() -> handleTeleport(context.player(), payload)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                subscriptions.remove(handler.player.getUuid()));
    }

    public void tick(MinecraftServer server) {
        snapshotTick++;
        if (snapshotTick % SNAPSHOT_INTERVAL_TICKS != 0 || subscriptions.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : subscriptions.entrySet()) {
            ServerPlayerEntity viewer = server.getPlayerManager().getPlayer(entry.getKey());
            if (viewer == null) {
                subscriptions.remove(entry.getKey());
                continue;
            }
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByUuid(entry.getValue());
            if (bot.isEmpty() || !BotAuthorizationGate.INSTANCE.authorize(
                    viewer, bot.get(), BotAuthorizationPolicy.Operation.VIEW, "network:snapshot_push")) {
                subscriptions.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (ServerPlayNetworking.canSend(viewer, BotSnapshotS2C.ID)) {
                ServerPlayNetworking.send(viewer, snapshot(bot.get()));
            }
        }
    }

    public void clear() {
        subscriptions.clear();
        snapshotTick = 0;
    }

    public void clearBot(UUID botId) {
        subscriptions.entrySet().removeIf(entry -> botId.equals(entry.getValue()));
    }

    public void sendBotChat(AIPlayerEntity bot, String role, String text) {
        if (subscriptions.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : subscriptions.entrySet()) {
            if (!bot.getUuid().equals(entry.getValue())) {
                continue;
            }
            ServerPlayerEntity viewer = bot.getServer().getPlayerManager().getPlayer(entry.getKey());
            if (viewer == null) {
                subscriptions.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (!BotAuthorizationGate.INSTANCE.authorize(
                    viewer, bot, BotAuthorizationPolicy.Operation.VIEW, "network:chat_push")) {
                subscriptions.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (ServerPlayNetworking.canSend(viewer, BotChatS2C.ID)) {
                ServerPlayNetworking.send(viewer, new BotChatS2C(bot.getGameProfile().getName(), role, text));
            }
        }
    }

    private void handleSubscribe(ServerPlayerEntity player, SubscribeBotC2S payload) {
        if (!payload.subscribe()) {
            subscriptions.remove(player.getUuid());
            return;
        }
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.VIEW, "network:subscribe");
        if (bot.isEmpty()) {
            subscriptions.remove(player.getUuid());
            sendSystem(player, "", "找不到该 Bot 或无权限。");
            return;
        }
        AIPlayerEntity target = bot.get();
        subscriptions.put(player.getUuid(), target.getUuid());
        if (ServerPlayNetworking.canSend(player, BotSnapshotS2C.ID)) {
            ServerPlayNetworking.send(player, snapshot(target));
        }
        sendSystem(player, target.getGameProfile().getName(), "已订阅 " + target.getGameProfile().getName());
    }

    private void handleCommand(ServerPlayerEntity player, BotCommandC2S payload) {
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.COMMAND, "network:command");
        if (bot.isEmpty()) {
            sendSystem(player, "", "找不到该 Bot 或无权限。");
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
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.ADMIN, "network:set_option");
        if (bot.isEmpty()) {
            sendSystem(player, "", "找不到该 Bot 或无权限。");
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

    // 面板传送：server thread 内执行；授权在解析目标后、任何坐标修改前完成。
    private void handleTeleport(ServerPlayerEntity player, BotTeleportC2S payload) {
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.TELEPORT, "network:teleport");
        if (bot.isEmpty()) {
            sendSystem(player, "", "找不到该 Bot 或无权限。");
            return;
        }
        AIPlayerEntity target = bot.get();
        if (!io.github.zoyluo.aibot.mode.CapabilityRuntime.decide(
                target, io.github.zoyluo.aibot.mode.PrivilegedCapability.MANUAL_TELEPORT,
                "network_manual_teleport").allowed()) {
            sendSystem(player, target.getGameProfile().getName(),
                    "当前运行模式禁止面板传送；请显式启用 operator/manualTeleport。");
            return;
        }
        if (payload.direction() == BotTeleportC2S.TO_AI) {
            // 玩家 → AI 附近 10 格内可站立方块。
            net.minecraft.server.world.ServerWorld world = target.getServerWorld();
            io.github.zoyluo.aibot.pathfinding.Standability.findNearestStandable(world, target.getBlockPos(), 10, 8, 8)
                    .ifPresent(p -> player.teleport(world, p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D,
                            java.util.Set.of(), player.getYaw(), player.getPitch(), true));
        } else if (payload.direction() == BotTeleportC2S.RECALL_AI) {
            // AI → 玩家附近 10 格内可站立方块(先停手头动作再传)。
            net.minecraft.server.world.ServerWorld world = player.getServerWorld();
            io.github.zoyluo.aibot.pathfinding.Standability.findNearestStandable(world, player.getBlockPos(), 10, 8, 8)
                    .ifPresent(p -> {
                        target.getActionPack().stopAll();
                        target.teleport(world, p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D,
                                java.util.Set.of(), target.getYaw(), target.getPitch(), true);
                    });
        } else {
            sendSystem(player, target.getGameProfile().getName(), "无效的传送方向。");
        }
    }

    private void handleItemMove(ServerPlayerEntity player, BotItemMoveC2S payload) {
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.INVENTORY, "network:item_move");
        if (bot.isEmpty()) {
            sendSystem(player, "", "找不到该 Bot 或无权限。");
            return;
        }
        AIPlayerEntity target = bot.get();
        var botInv = target.getInventory();
        var playerInv = player.getInventory();
        if (payload.direction() == BotItemMoveC2S.TAKE) {
            // 从 AI main[slot] 拿到玩家背包
            int slot = payload.slot();
            if (slot < 0 || slot >= botInv.main.size()) {
                return;
            }
            ItemStack src = botInv.main.get(slot);
            if (src.isEmpty()) {
                return;
            }
            int move = payload.amount() <= 0 ? src.getCount() : Math.min(payload.amount(), src.getCount());
            ItemStack moving = src.copy();
            moving.setCount(move);
            boolean inserted = playerInv.insertStack(moving); // moving 被原地改为"未放入的剩余"
            int placed = move - moving.getCount();
            if (placed > 0) {
                src.decrement(placed);
                botInv.markDirty();
            }
        } else if (payload.direction() == BotItemMoveC2S.PUT) {
            // 把玩家 inventory.main[slot] 放进 AI 背包
            int slot = payload.slot();
            if (slot < 0 || slot >= playerInv.main.size()) {
                return;
            }
            ItemStack src = playerInv.main.get(slot);
            if (src.isEmpty()) {
                return;
            }
            int move = payload.amount() <= 0 ? src.getCount() : Math.min(payload.amount(), src.getCount());
            ItemStack moving = src.copy();
            moving.setCount(move);
            int placed = insertIntoBot(botInv, moving);
            if (placed > 0) {
                src.decrement(placed);
                playerInv.markDirty();
            }
        } else {
            sendSystem(player, target.getGameProfile().getName(), "无效的物品移动方向。");
            return;
        }
        // 立即回推一帧快照(含双方背包),UI 不必等 10-tick 周期刷新。
        if (ServerPlayNetworking.canSend(player, BotSnapshotS2C.ID)) {
            ServerPlayNetworking.send(player, snapshot(target));
        }
    }

    // 把 stack 尽量插入 AI 背包 main 区(先堆叠到同类,再填空槽),返回实际放入数量。
    private static int insertIntoBot(net.minecraft.entity.player.PlayerInventory botInv, ItemStack moving) {
        int want = moving.getCount();
        // 1) 堆叠到已有同类未满槽
        for (int i = 0; i < botInv.main.size() && !moving.isEmpty(); i++) {
            ItemStack dst = botInv.main.get(i);
            if (!dst.isEmpty() && ItemStack.areItemsAndComponentsEqual(dst, moving) && dst.getCount() < dst.getMaxCount()) {
                int room = dst.getMaxCount() - dst.getCount();
                int add = Math.min(room, moving.getCount());
                dst.increment(add);
                moving.decrement(add);
            }
        }
        // 2) 填空槽
        for (int i = 0; i < botInv.main.size() && !moving.isEmpty(); i++) {
            if (botInv.main.get(i).isEmpty()) {
                botInv.main.set(i, moving.copy());
                moving.setCount(0);
            }
        }
        if (want != moving.getCount()) {
            botInv.markDirty();
        }
        return want - moving.getCount();
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
                IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_abort");
            }
            case "chat" -> {
                sendBotChat(bot, "user", payload.arg1());
                if (!IntentController.INSTANCE.routePlayerControlPhrase(
                        bot, IntentController.ControlOrigin.PLAYER_PANEL, payload.arg1())) {
                    BrainCoordinator.INSTANCE.handleMessage(bot, player.getGameProfile().getName(), payload.arg1());
                }
            }
            case "pause" -> IntentController.INSTANCE.pause(
                    bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_pause");
            case "resume" -> IntentController.INSTANCE.resume(
                    bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_resume");
            case "reset" -> {
                RuntimeLifecycleCoordinator.INSTANCE.resetBot(
                        bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_brain_reset");
                sendSystem(player, bot.getGameProfile().getName(), "大脑已重置。");
            }
            default -> throw new IllegalArgumentException("unknown_action: " + payload.action());
        }
    }

    private static void assign(AIPlayerEntity bot, Task task) {
        IntentController.INSTANCE.replace(
                bot,
                IntentController.ControlOrigin.PLAYER_PANEL,
                "panel_assign:" + task.name(),
                () -> {
                    TaskManager.INSTANCE.assign(bot, task,
                            TaskOrigin.of(TaskOrigin.Kind.PLAYER_PANEL, "panel_assign"));
                    return true;
                });
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
        // UI:全身装备(头/胸/腿/脚/主手/副手),slot index 0..5,供背包面板的装备区展示。
        ArrayList<BotSnapshotS2C.ItemEntry> equipment = new ArrayList<>();
        net.minecraft.entity.EquipmentSlot[] equipSlots = {
                net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
                net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET,
                net.minecraft.entity.EquipmentSlot.MAINHAND, net.minecraft.entity.EquipmentSlot.OFFHAND};
        for (int slotIndex = 0; slotIndex < equipSlots.length; slotIndex++) {
            ItemStack equipped = bot.getEquippedStack(equipSlots[slotIndex]);
            if (!equipped.isEmpty()) {
                equipment.add(new BotSnapshotS2C.ItemEntry(
                        Registries.ITEM.getId(equipped.getItem()).toString(), equipped.getCount(), slotIndex));
            }
        }
        // 任务链条:优先展示 GoalExecutor 的实际确定性计划(provision_food→[砍树/做镐/挖石/造炉/打猎/烤]…),
        // 没有激活计划时才回退到大脑 set_goal 记的目标(memory)。这样面板链条与 bot 真正在执行的步骤一致。
        boolean hasPlan = GoalExecutor.INSTANCE.hasActivePlan(bot);
        String goalTitle = hasPlan ? GoalExecutor.INSTANCE.activeGoalTitle(bot) : memory.goalTitle();
        List<String> goalSteps = hasPlan ? GoalExecutor.INSTANCE.activeGoalSteps(bot) : memory.goalSteps();
        int goalIndex = hasPlan ? GoalExecutor.INSTANCE.activeGoalCurrentIndex(bot) : memory.goalCurrentStepIndex();
        int goalTotal = hasPlan ? GoalExecutor.INSTANCE.activeGoalTotalSteps(bot) : memory.goalTotalSteps();
        String goalCurrentStep = goalIndex >= 0 && goalIndex < goalSteps.size()
                ? goalSteps.get(goalIndex) : memory.currentGoalStep().orElse("");
        var goalResult = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
        var runtimeConfig = io.github.zoyluo.aibot.AIBotConfig.get();
        List<String> effectiveCapabilities = java.util.Arrays.stream(
                        io.github.zoyluo.aibot.mode.PrivilegedCapability.values())
                .filter(capability -> io.github.zoyluo.aibot.mode.CapabilityPolicy.decide(
                        runtimeConfig.profile(), runtimeConfig.operatorCapabilities(), capability).allowed())
                .map(Enum::name)
                .toList();
        return new BotSnapshotS2C(
                bot.getGameProfile().getName(),
                bot.getHealth(),
                bot.getMaxHealth(),
                bot.getHungerManager().getFoodLevel(),
                bot.getBlockX(),
                bot.getBlockY(),
                bot.getBlockZ(),
                task.name(),
                task.state().name(),
                (float) task.progress(),
                brain.busy(),
                brain.promptTokens(),
                brain.completionTokens(),
                goalTitle,
                goalCurrentStep,
                goalIndex,
                goalTotal,
                goalSteps,
                goalResult == null ? 0L : goalResult.sequence(),
                goalResult == null ? "" : goalResult.status().name(),
                goalResult == null ? "" : GoalExecutor.INSTANCE.resultSummary(goalResult),
                goalResult == null ? 0 : goalResult.evaluation().matched(),
                goalResult == null ? 0 : goalResult.evaluation().required(),
                TaskManager.INSTANCE.isUserPaused(bot),
                TaskManager.INSTANCE.pausedDepth(bot),
                runtimeConfig.profile().configValue(),
                effectiveCapabilities,
                BrainCoordinator.INSTANCE.manualMode(bot),
                BotRuntimeOptions.INSTANCE.memoryToolsEnabled(bot),
                BotRuntimeOptions.INSTANCE.verboseReportsEnabled(bot),
                inventory,
                equipment);
    }

    private void sendSystem(ServerPlayerEntity player, String botName, String text) {
        if (ServerPlayNetworking.canSend(player, BotChatS2C.ID)) {
            ServerPlayNetworking.send(player, new BotChatS2C(botName, "system", text));
        }
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

}
