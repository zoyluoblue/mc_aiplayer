package io.github.zoyluo.aibot.network;

import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.brain.BotRuntimeOptions;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.marker.TargetMarkerService;
import io.github.zoyluo.aibot.gift.AudienceControlService;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.memory.BotMemory;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.network.payload.BotChatS2C;
import io.github.zoyluo.aibot.network.payload.AudienceControlC2S;
import io.github.zoyluo.aibot.network.payload.AudienceSnapshotS2C;
import io.github.zoyluo.aibot.network.payload.BotCommandC2S;
import io.github.zoyluo.aibot.network.payload.BotItemMoveC2S;
import io.github.zoyluo.aibot.network.payload.BotTeleportC2S;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import io.github.zoyluo.aibot.network.payload.BrainTraceS2C;
import io.github.zoyluo.aibot.network.payload.SetOptionC2S;
import io.github.zoyluo.aibot.network.payload.SubscribeBotC2S;
import io.github.zoyluo.aibot.network.payload.TargetMarkerC2S;
import io.github.zoyluo.aibot.network.payload.TargetMarkerS2C;
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
import net.minecraft.util.math.Direction;

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
    private final Map<UUID, String> subscriptions = new ConcurrentHashMap<>();
    private int snapshotTick;

    private AIBotServerNetworking() {
    }

    public void register() {
        ServerPlayNetworking.registerGlobalReceiver(AudienceControlC2S.ID, (payload, context) ->
                context.server().execute(() -> handleAudienceControl(context.player(), payload)));
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
        ServerPlayNetworking.registerGlobalReceiver(TargetMarkerC2S.ID, (payload, context) ->
                context.server().execute(() -> handleTargetMarker(context.player(), payload)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            subscriptions.remove(handler.player.getUuid());
            TargetMarkerService.INSTANCE.clear(handler.player.getUuid());
        });
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
            sendAudienceSnapshot(viewer);
        }
    }

    public void clear() {
        subscriptions.clear();
        snapshotTick = 0;
    }

    public void clearTargetMarker(ServerPlayerEntity player, String message) {
        TargetMarkerService.INSTANCE.clear(player.getUuid());
        sendTargetMarkerState(player, null, message == null ? "标记已清除" : message);
    }

    public void sendBotChat(AIPlayerEntity bot, String role, String text) {
        String normalized = normalize(bot.getGameProfile().getName());
        java.util.Set<UUID> delivered = new java.util.HashSet<>();
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
            if (ServerPlayNetworking.canSend(viewer, BotChatS2C.ID)) {
                ServerPlayNetworking.send(viewer, new BotChatS2C(bot.getGameProfile().getName(), role, text));
                delivered.add(viewer.getUuid());
            }
        }
        // 面板 close() 即退订,订阅只在面板打开期间存活;而客户端 TTS 靠 BotChatS2C 驱动,
        // 正常游玩(面板关闭)时必须仍能收到 bot 发言,故对 owner 恒推,不依赖订阅。
        UUID ownerUuid = AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null);
        if (ownerUuid != null) {
            if (delivered.contains(ownerUuid)) {
                return;
            }
            ServerPlayerEntity owner = bot.getServer().getPlayerManager().getPlayer(ownerUuid);
            if (owner != null && ServerPlayNetworking.canSend(owner, BotChatS2C.ID)) {
                ServerPlayNetworking.send(owner, new BotChatS2C(bot.getGameProfile().getName(), role, text));
            }
            return;
        }
        // 无主 bot(控制台生成/归属丢失/owner 离线):推给在线 OP,不再静默
        for (ServerPlayerEntity op : bot.getServer().getPlayerManager().getPlayerList()) {
            if (op.hasPermissionLevel(2) && !delivered.contains(op.getUuid())
                    && ServerPlayNetworking.canSend(op, BotChatS2C.ID)) {
                ServerPlayNetworking.send(op, new BotChatS2C(bot.getGameProfile().getName(), role, text));
            }
        }
    }



    public void sendBrainTrace(AIPlayerEntity bot, String line) {
        if (bot.getServer() == null) {
            return;
        }
        UUID ownerUuid = AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null);
        if (ownerUuid != null) {
            ServerPlayerEntity owner = bot.getServer().getPlayerManager().getPlayer(ownerUuid);
            if (owner != null && ServerPlayNetworking.canSend(owner, BrainTraceS2C.ID)) {
                ServerPlayNetworking.send(owner, new BrainTraceS2C(bot.getGameProfile().getName(), line));
            }
            return;
        }
        for (ServerPlayerEntity op : bot.getServer().getPlayerManager().getPlayerList()) {
            if (op.hasPermissionLevel(2) && ServerPlayNetworking.canSend(op, BrainTraceS2C.ID)) {
                ServerPlayNetworking.send(op, new BrainTraceS2C(bot.getGameProfile().getName(), line));
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

    private void handleAudienceControl(ServerPlayerEntity player, AudienceControlC2S payload) {
        if (!player.hasPermissionLevel(2)) {
            sendSystem(player, "", "需要 OP 权限才能管理观众 AI。");
            return;
        }
        switch (payload.action()) {
            case AudienceControlC2S.REFRESH -> {
            }
            case AudienceControlC2S.BIND -> AudienceControlService.INSTANCE
                    .bind(player.getServer(), player, payload.viewerKey());
            case AudienceControlC2S.UNBIND -> AudienceControlService.INSTANCE
                    .unbind(player.getServer(), player);
            default -> throw new IllegalArgumentException("unknown_audience_action: " + payload.action());
        }
        sendAudienceSnapshot(player);
    }

    private static void sendAudienceSnapshot(ServerPlayerEntity player) {
        if (ServerPlayNetworking.canSend(player, AudienceSnapshotS2C.ID)) {
            ServerPlayNetworking.send(player, AudienceControlService.INSTANCE.snapshot());
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

    private void handleTargetMarker(ServerPlayerEntity player, TargetMarkerC2S payload) {
        if (!payload.active()) {
            clearTargetMarker(player, "标记已清除");
            return;
        }
        String currentDimension = player.getServerWorld().getRegistryKey().getValue().toString();
        if (!currentDimension.equals(payload.dimension())) {
            sendTargetMarkerState(player, TargetMarkerService.INSTANCE.get(player.getUuid()).orElse(null),
                    "标记失败：维度已经变化");
            return;
        }
        try {
            Direction face = Direction.byId(payload.faceId());
            TargetMarkerService.Marker marker = TargetMarkerService.INSTANCE.set(player, payload.blockPos(), face);
            sendTargetMarkerState(player, marker, "已标记 " + marker.clickedBlock().toShortString());
        } catch (IllegalArgumentException exception) {
            sendTargetMarkerState(player, TargetMarkerService.INSTANCE.get(player.getUuid()).orElse(null),
                    "标记失败：" + exception.getMessage());
        }
    }

    private static void sendTargetMarkerState(ServerPlayerEntity player,
                                              TargetMarkerService.Marker marker,
                                              String message) {
        if (!ServerPlayNetworking.canSend(player, TargetMarkerS2C.ID)) {
            return;
        }
        if (marker == null) {
            ServerPlayNetworking.send(player, new TargetMarkerS2C(
                    false, "", BlockPos.ORIGIN, Direction.UP.getId(), BlockPos.ORIGIN, message));
            return;
        }
        ServerPlayNetworking.send(player, new TargetMarkerS2C(
                true,
                marker.dimensionId(),
                marker.clickedBlock(),
                marker.face().getId(),
                marker.standPos(),
                message));
    }

    // 面板背包:在玩家与 AI 之间移动物品。直接操作 Inventory(G3,不开 ScreenHandler);已在 server 线程(G2)。
    // 任何人可拿放(按用户要求,不校验权限)。
    private void handleTeleport(ServerPlayerEntity player, BotTeleportC2S payload) {
        Optional<AIPlayerEntity> bot = resolveBot(player, payload.botName());
        if (bot.isEmpty()) {
            return;
        }
        AIPlayerEntity target = bot.get();
        if (payload.direction() == BotTeleportC2S.TO_AI) {
            // 玩家 → AI 附近 10 格内可站立方块。
            net.minecraft.server.world.ServerWorld world = target.getServerWorld();
            io.github.zoyluo.aibot.pathfinding.Standability.findNearestStandable(world, target.getBlockPos(), 10, 8, 8)
                    .ifPresent(p -> player.teleport(world, p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D,
                            java.util.Set.of(), player.getYaw(), player.getPitch(), true));
        } else {
            // AI → 玩家附近 10 格内可站立方块(先停手头动作再传)。
            net.minecraft.server.world.ServerWorld world = player.getServerWorld();
            io.github.zoyluo.aibot.pathfinding.Standability.findNearestStandable(world, player.getBlockPos(), 10, 8, 8)
                    .ifPresent(p -> {
                        target.getActionPack().stopAll();
                        target.teleport(world, p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D,
                                java.util.Set.of(), target.getYaw(), target.getPitch(), true);
                    });
        }
    }

    private void handleItemMove(ServerPlayerEntity player, BotItemMoveC2S payload) {
        Optional<AIPlayerEntity> bot = resolveBot(player, payload.botName());
        if (bot.isEmpty()) {
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
        } else {
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
                TaskManager.INSTANCE.abort(bot);
                bot.getActionPack().stopAll();
                sendSystem(player, bot.getGameProfile().getName(), "任务已停止。");
            }
            case "brain_abort" -> {
                // 快捷键"打断":停思考(在途 API 响应作废)+清目标计划+复位任务+停动作,一键全停。
                BrainCoordinator.INSTANCE.abort(bot);
                io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.clear(bot);
                TaskManager.INSTANCE.resetToIdle(bot);
                bot.getActionPack().stopAll();
                sendSystem(player, bot.getGameProfile().getName(), "已打断:思考与任务全部停止。");
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

    private Optional<AIPlayerEntity> resolveBot(ServerPlayerEntity player, String botName) {
        // 三级兜底:指名 → 自己的 bot → 世界里唯一的 bot。修"换名/换 bot 后语音与面板全部失联"
        // (实测:aibot_voice.json 写死 targetBot=Bob,despawn Bob 换 gpt 后语音转写全部石沉大海)。
        if (botName != null && !botName.isBlank()) {
            Optional<AIPlayerEntity> named = AIPlayerManager.INSTANCE.getByName(botName);
            if (named.isPresent()) {
                return named;
            }
        }
        Optional<AIPlayerEntity> owned = AIPlayerManager.INSTANCE.botOf(player.getUuid());
        if (owned.isPresent()) {
            return owned;
        }
        var all = AIPlayerManager.INSTANCE.all();
        return all.size() == 1 ? Optional.of(all.iterator().next()) : Optional.empty();
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
