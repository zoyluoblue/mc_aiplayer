package com.aiplayer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.entity.AiPlayerManager;
import com.aiplayer.planning.PlanParser;
import com.aiplayer.planning.PlanSchema;
import com.aiplayer.planning.PlanValidator;
import com.aiplayer.recipe.AutoMiningTarget;
import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.SnapshotSerializer;
import com.aiplayer.snapshot.WorldSnapshot;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class AiPlayerCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ai")
            .then(Commands.literal("help")
                .executes(AiPlayerCommands::help))
            .then(Commands.literal("spawn")
                .executes(context -> spawnAi(context, null))
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(context -> spawnAi(context, StringArgumentType.getString(context, "name")))))
            .then(Commands.literal("remove")
                .executes(AiPlayerCommands::removeAi)
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(AiPlayerCommands::removeAiByName)))
            .then(Commands.literal("list")
                .executes(AiPlayerCommands::listAi))
            .then(Commands.literal("backpack")
                .executes(AiPlayerCommands::showBackpack)
                .then(Commands.literal("take")
                    .then(Commands.argument("item", StringArgumentType.word())
                        .executes(context -> takeBackpackItem(context, 64))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                            .executes(context -> takeBackpackItem(context, IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("put")
                    .then(Commands.argument("item", StringArgumentType.word())
                        .executes(context -> putBackpackItem(context, 64))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                            .executes(context -> putBackpackItem(context, IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("take_slot")
                    .then(Commands.argument("slot", IntegerArgumentType.integer(0, 35))
                        .executes(context -> takeBackpackSlot(context, 64))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                            .executes(context -> takeBackpackSlot(context, IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("put_hand")
                    .then(Commands.argument("slot", IntegerArgumentType.integer(0, 35))
                        .executes(context -> putHandIntoBackpackSlot(context, 64))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                            .executes(context -> putHandIntoBackpackSlot(context, IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("put_slot")
                    .then(Commands.argument("playerSlot", IntegerArgumentType.integer(0, 35))
                        .then(Commands.argument("aiSlot", IntegerArgumentType.integer(0, 35))
                            .executes(context -> putPlayerSlotIntoBackpackSlot(context, 64))
                            .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(context -> putPlayerSlotIntoBackpackSlot(context, IntegerArgumentType.getInteger(context, "count")))))))
                .then(Commands.literal("take_slot_to")
                    .then(Commands.argument("aiSlot", IntegerArgumentType.integer(0, 35))
                        .then(Commands.argument("playerSlot", IntegerArgumentType.integer(0, 35))
                            .executes(context -> takeBackpackSlotToPlayerSlot(context, 64))
                            .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(context -> takeBackpackSlotToPlayerSlot(context, IntegerArgumentType.getInteger(context, "count"))))))))
            .then(Commands.literal("snapshot")
                .executes(AiPlayerCommands::showSnapshot))
            .then(Commands.literal("status")
                .executes(AiPlayerCommands::showAiStatus))
            .then(Commands.literal("location")
                .executes(AiPlayerCommands::showAiLocation))
            .then(Commands.literal("recipe")
                .then(Commands.argument("item", StringArgumentType.word())
                    .executes(context -> showRecipe(context, 1))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(context -> showRecipe(context, IntegerArgumentType.getInteger(context, "count"))))))
            .then(Commands.literal("plan")
                .then(Commands.argument("item", StringArgumentType.word())
                    .executes(context -> showPlan(context, 1))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(context -> showPlan(context, IntegerArgumentType.getInteger(context, "count"))))))
            .then(Commands.literal("mining")
                .then(Commands.literal("status")
                    .executes(AiPlayerCommands::showMiningStatus))
                .then(Commands.literal("stop")
                    .executes(AiPlayerCommands::stopMining))
                .then(Commands.literal("return")
                    .executes(AiPlayerCommands::returnMining))
                .then(Commands.literal("start")
                    .then(Commands.argument("target", StringArgumentType.word())
                        .executes(context -> startMining(context, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                            .executes(context -> startMining(context, IntegerArgumentType.getInteger(context, "count")))))))
            .then(Commands.literal("stop")
                .executes(AiPlayerCommands::stopAi))
            .then(Commands.literal("recall")
                .executes(AiPlayerCommands::recallAi))
            .then(Commands.literal("say")
                .then(Commands.argument("command", StringArgumentType.greedyString())
                    .executes(AiPlayerCommands::tellAi)))
        );
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("""
            /ai spawn [name] - 召唤你的 AI 玩家，每名玩家最多 1 个
            /ai say <command> - 给你的 AI 玩家下达任务
            /ai stop - 停止当前任务并让 AI 玩家回到你身边
            /ai recall - 立即召回 AI 玩家并停止所有任务
            /ai remove - 移除你的 AI 玩家
            /ai remove <name> - 按名称清理你拥有的 AI 或无 owner 的旧占用记录
            /ai list - 查看你的 AI 玩家
            /ai backpack - 查看你的 AI 玩家背包
            /ai backpack take <item> [count] - 从 AI 背包取出物品
            /ai backpack put <item> [count] - 把你的物品放入 AI 背包
            /ai snapshot - 查看 AI 最近一次可观察状态 JSON
            /ai status - 查看 AI 当前状态和阶段
            /ai location - 查看 AI 当前坐标和距离
            /ai recipe <item> [count] - 查看目标物品的递归配方链
            /ai plan <item> [count] - 查看验证后的高层执行计划
            /ai mining start <target> [count] - 启动自动挖矿目标，例如 iron、diamond、gold、obsidian
            /ai mining status - 查看当前自动挖矿状态
            /ai mining stop - 停止当前自动挖矿任务
            /ai mining return - 停止挖矿并让 AI 正常走回你身边
            """), false);
        return 1;
    }

    private static int spawnAi(CommandContext<CommandSourceStack> context, String requestedName) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        ServerLevel serverLevel = source.getLevel();
        AiPlayerManager manager = AiPlayerMod.getAiPlayerManager();
        AiPlayerEntity existing = manager.getAiPlayerByOwner(player.getUUID());
        if (existing != null) {
            source.sendFailure(Component.literal("你已经召唤过 AI 玩家了：%s。每名玩家最多只能拥有 1 个 AI 玩家。"
                .formatted(existing.getAiPlayerName())));
            return 0;
        }

        Vec3 sourcePos = source.getPosition();
        Vec3 lookVec = player.getLookAngle();
        Vec3 spawnPos = sourcePos.add(lookVec.x * 3, 0, lookVec.z * 3);
        String name = sanitizeName(requestedName, player.getGameProfile().getName() + "的AI");

        AiPlayerEntity aiPlayer = manager.spawnAiPlayer(serverLevel, spawnPos, name, player.getUUID());
        if (aiPlayer != null) {
            source.sendSuccess(() -> Component.literal("已召唤 AI 玩家：%s。".formatted(name)), true);
            return 1;
        }

        source.sendFailure(Component.literal("召唤失败：名称可能已被占用，或服务器已达到 AI 玩家数量上限。"
            + "如果 /ai list 看不到自己的 AI，可以先执行 /ai remove %s 清理旧占用记录。".formatted(name)));
        return 0;
    }

    private static int removeAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        AiPlayerManager manager = AiPlayerMod.getAiPlayerManager();
        AiPlayerEntity aiPlayer = manager.getAiPlayerByOwner(player.getUUID());
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }

        String name = aiPlayer.getAiPlayerName();
        manager.removeAiPlayerByOwner(player.getUUID());
        source.sendSuccess(() -> Component.literal("已移除 AI 玩家：%s。".formatted(name)), true);
        return 1;
    }

    private static int removeAiByName(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");
        AiPlayerManager manager = AiPlayerMod.getAiPlayerManager();
        if (!manager.removeAiPlayerByNameForOwner(player.getUUID(), name)) {
            source.sendFailure(Component.literal("没有找到可由你移除的 AI 玩家或旧占用记录：%s。".formatted(name)));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已移除 AI 玩家或旧占用记录：%s。".formatted(name)), true);
        return 1;
    }

    private static int listAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        AiPlayerEntity aiPlayer = AiPlayerMod.getAiPlayerManager().getAiPlayerByOwner(player.getUUID());
        if (aiPlayer == null) {
            source.sendSuccess(() -> Component.literal("你当前没有 AI 玩家。使用 /ai spawn 创建一个。"), false);
        } else {
            source.sendSuccess(() -> Component.literal("你的 AI 玩家：%s。".formatted(aiPlayer.getAiPlayerName())), false);
        }
        return 1;
    }

    private static int showBackpack(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        AiPlayerEntity aiPlayer = AiPlayerMod.getAiPlayerManager().getAiPlayerByOwner(player.getUUID());
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }

        Map<String, Integer> inventory = new TreeMap<>(aiPlayer.getInventorySnapshot());
        if (inventory.isEmpty()) {
            source.sendSuccess(() -> Component.literal("%s 的背包为空。".formatted(aiPlayer.getAiPlayerName())), false);
            return 1;
        }

        StringBuilder message = new StringBuilder(aiPlayer.getAiPlayerName()).append(" 的背包：");
        inventory.forEach((item, count) -> message.append("\n- ").append(formatItemName(item)).append(" x").append(count));
        source.sendSuccess(() -> Component.literal(message.toString()), false);
        return 1;
    }

    private static int takeBackpackItem(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }
        AiPlayerEntity aiPlayer = getOwnedAiPlayer(player);
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }
        Item item = itemFromKey(StringArgumentType.getString(context, "item"));
        if (item == Items.AIR) {
            source.sendFailure(Component.literal("未知物品 ID。"));
            return 0;
        }
        List<ItemStack> removed = aiPlayer.removeItemStacks(item, count);
        int moved = moveStacksToPlayer(aiPlayer, player, removed);
        if (moved <= 0) {
            source.sendFailure(Component.literal("%s 的背包里没有可取出的 %s，或你的背包已满。"
                .formatted(aiPlayer.getAiPlayerName(), itemKey(item))));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已从 %s 背包取出 %s x%d。"
            .formatted(aiPlayer.getAiPlayerName(), formatItemName(itemKey(item)), moved)), true);
        return moved;
    }

    private static int putBackpackItem(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }
        AiPlayerEntity aiPlayer = getOwnedAiPlayer(player);
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }
        Item item = itemFromKey(StringArgumentType.getString(context, "item"));
        if (item == Items.AIR) {
            source.sendFailure(Component.literal("未知物品 ID。"));
            return 0;
        }
        List<ItemStack> removed = removeStacksFromPlayer(player, item, count);
        int moved = moveStacksToAi(aiPlayer, player, removed);
        if (moved <= 0) {
            source.sendFailure(Component.literal("你没有可放入的 %s，或 %s 的背包已满。"
                .formatted(formatItemName(itemKey(item)), aiPlayer.getAiPlayerName())));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已向 %s 背包放入 %s x%d。"
            .formatted(aiPlayer.getAiPlayerName(), formatItemName(itemKey(item)), moved)), true);
        return moved;
    }

    private static int takeBackpackSlot(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }
        AiPlayerEntity aiPlayer = getOwnedAiPlayer(player);
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }
        int slot = IntegerArgumentType.getInteger(context, "slot");
        ItemStack removed = aiPlayer.removeItemFromBackpackSlot(slot, count);
        int moved = moveStackToPlayer(aiPlayer, player, removed);
        if (moved <= 0) {
            source.sendFailure(Component.literal("该 AI 背包格为空，或你的背包已满。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已从 %s 背包第 %d 格取出 %s x%d。"
            .formatted(aiPlayer.getAiPlayerName(), slot, formatItemName(itemKey(removed.getItem())), moved)), true);
        return moved;
    }

    private static int putHandIntoBackpackSlot(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }
        AiPlayerEntity aiPlayer = getOwnedAiPlayer(player);
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }
        int slot = IntegerArgumentType.getInteger(context, "slot");
        ItemStack selected = player.getInventory().getSelected();
        if (selected.isEmpty()) {
            source.sendFailure(Component.literal("你的主手没有可放入的物品。"));
            return 0;
        }
        ItemStack moving = selected.copyWithCount(Math.min(count, selected.getCount()));
        int moved = aiPlayer.insertItemStackIntoSlot(slot, moving);
        if (moved <= 0) {
            source.sendFailure(Component.literal("%s 的背包第 %d 格无法放入该物品。"
                .formatted(aiPlayer.getAiPlayerName(), slot)));
            return 0;
        }
        selected.shrink(moved);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        source.sendSuccess(() -> Component.literal("已向 %s 背包第 %d 格放入 %s x%d。"
            .formatted(aiPlayer.getAiPlayerName(), slot, formatItemName(itemKey(moving.getItem())), moved)), true);
        return moved;
    }

    private static int putPlayerSlotIntoBackpackSlot(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }
        AiPlayerEntity aiPlayer = getOwnedAiPlayer(player);
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }
        int playerSlot = IntegerArgumentType.getInteger(context, "playerSlot");
        int aiSlot = IntegerArgumentType.getInteger(context, "aiSlot");
        ItemStack selected = player.getInventory().items.get(playerSlot);
        if (selected.isEmpty()) {
            source.sendFailure(Component.literal("玩家背包第 %d 格没有可放入的物品。".formatted(playerSlot)));
            return 0;
        }

        ItemStack moving = selected.copyWithCount(Math.min(count, selected.getCount()));
        int moved = aiPlayer.insertItemStackIntoSlot(aiSlot, moving);
        if (moved <= 0) {
            source.sendFailure(Component.literal("%s 的背包第 %d 格无法放入该物品。"
                .formatted(aiPlayer.getAiPlayerName(), aiSlot)));
            return 0;
        }

        selected.shrink(moved);
        if (selected.isEmpty()) {
            player.getInventory().items.set(playerSlot, ItemStack.EMPTY);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        source.sendSuccess(() -> Component.literal("已把玩家背包第 %d 格的 %s x%d 放入 %s 背包第 %d 格。"
            .formatted(playerSlot, formatItemName(itemKey(moving.getItem())), moved, aiPlayer.getAiPlayerName(), aiSlot)), true);
        return moved;
    }

    private static int takeBackpackSlotToPlayerSlot(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }
        AiPlayerEntity aiPlayer = getOwnedAiPlayer(player);
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }
        int aiSlot = IntegerArgumentType.getInteger(context, "aiSlot");
        int playerSlot = IntegerArgumentType.getInteger(context, "playerSlot");
        ItemStack removed = aiPlayer.removeItemFromBackpackSlot(aiSlot, count);
        if (removed.isEmpty()) {
            source.sendFailure(Component.literal("该 AI 背包格为空。"));
            return 0;
        }

        int moved = insertStackIntoPlayerSlot(player, playerSlot, removed);
        if (moved < removed.getCount()) {
            ItemStack leftover = removed.copyWithCount(removed.getCount() - moved);
            int returned = aiPlayer.insertItemStackIntoSlot(aiSlot, leftover);
            if (returned < leftover.getCount()) {
                aiPlayer.addItemStack(leftover.copyWithCount(leftover.getCount() - returned));
            }
        }
        if (moved <= 0) {
            source.sendFailure(Component.literal("玩家背包第 %d 格无法接收该物品。".formatted(playerSlot)));
            return 0;
        }

        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        source.sendSuccess(() -> Component.literal("已从 %s 背包第 %d 格取出 %s x%d 到玩家背包第 %d 格。"
            .formatted(aiPlayer.getAiPlayerName(), aiSlot, formatItemName(itemKey(removed.getItem())), moved, playerSlot)), true);
        return moved;
    }

    private static int showSnapshot(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        AiPlayerEntity aiPlayer = AiPlayerMod.getAiPlayerManager().getAiPlayerByOwner(player.getUUID());
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }

        WorldSnapshot snapshot = WorldSnapshot.capture(aiPlayer, "debug_snapshot");
        String json = SnapshotSerializer.toJson(snapshot);
        AiPlayerMod.info("snapshot", "Manual snapshot for AiPlayer '{}': {}", aiPlayer.getAiPlayerName(), SnapshotSerializer.toCompactJson(snapshot));
        sendLongMessage(source, json);
        return 1;
    }

    private static int showRecipe(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        AiPlayerEntity aiPlayer = AiPlayerMod.getAiPlayerManager().getAiPlayerByOwner(player.getUUID());
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }

        String item = StringArgumentType.getString(context, "item");
        WorldSnapshot snapshot = WorldSnapshot.capture(aiPlayer, "debug_recipe");
        RecipePlan recipePlan = new RecipeResolver().resolve(aiPlayer, snapshot, item, count);
        sendLongMessage(source, recipePlan.toUserText());
        return recipePlan.isSuccess() ? 1 : 0;
    }

    private static int showPlan(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        AiPlayerEntity aiPlayer = AiPlayerMod.getAiPlayerManager().getAiPlayerByOwner(player.getUUID());
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }

        String item = StringArgumentType.getString(context, "item");
        WorldSnapshot snapshot = WorldSnapshot.capture(aiPlayer, "debug_plan");
        RecipeResolver recipeResolver = new RecipeResolver();
        RecipePlan recipePlan = recipeResolver.resolve(aiPlayer, snapshot, item, count);
        if (!recipePlan.isSuccess()) {
            source.sendFailure(Component.literal(recipePlan.toUserText()));
            return 0;
        }
        PlanSchema plan = PlanSchema.fromRecipePlan("make_item", recipePlan);
        PlanValidator.ValidationResult validation = new PlanValidator(recipeResolver).validate(plan, aiPlayer, snapshot, recipePlan);
        if (!validation.valid()) {
            source.sendFailure(Component.literal(validation.toUserText()));
            return 0;
        }
        sendLongMessage(source, PlanParser.toJson(validation.plan()));
        return 1;
    }

    private static int startMining(CommandContext<CommandSourceStack> context, int count) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        AiPlayerEntity aiPlayer = AiPlayerMod.getAiPlayerManager().getAiPlayerByOwner(player.getUUID());
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }

        String target = StringArgumentType.getString(context, "target");
        AutoMiningTarget miningTarget = AutoMiningTarget.resolve(target, count);
        if (!miningTarget.supported()) {
            source.sendFailure(Component.literal(miningTarget.message()
                + " 可用目标包括 iron、coal、gold、diamond、redstone、lapis、emerald、copper、obsidian、ancient_debris。"));
            return 0;
        }

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("item", miningTarget.item());
        parameters.put("quantity", miningTarget.quantity());
        parameters.put("mining_target", miningTarget.profile().key());
        parameters.put("auto_mining", true);
        Task task = new Task("make_item", parameters);
        aiPlayer.getActionExecutor().startLocalTask(
            "自动挖矿：" + miningTarget.profile().displayName() + " x" + miningTarget.quantity(),
            task
        );

        source.sendSuccess(() -> Component.literal("已启动自动挖矿：%s -> %s x%d。维度：%s，最低工具：%s。"
            .formatted(
                miningTarget.profile().displayName(),
                formatItemName(miningTarget.item()),
                miningTarget.quantity(),
                miningTarget.dimension(),
                formatItemName(miningTarget.requiredTool())
            )), true);
        return 1;
    }

    private static int showMiningStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }
        AiPlayerEntity aiPlayer = getOwnedAiPlayer(player);
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }

        BlockPos pos = aiPlayer.blockPosition();
        String goal = aiPlayer.getActionExecutor().getCurrentGoal();
        String action = aiPlayer.getActionExecutor().getCurrentActionDescription();
        String taskId = aiPlayer.getActionExecutor().getActiveTaskId();
        String details = aiPlayer.getActionExecutor().getCurrentActionStatusDetails();
        String message = """
            AI 挖矿状态：
            - taskId: %s
            - 目标: %s
            - 动作: %s
            - 执行中: %s，规划中: %s
            - 位置: %s，Y=%d
            - 主手: %s
            - 背包: %s
            - 细节: %s
            """.formatted(
            taskId,
            goal == null || goal.isBlank() ? "无" : goal,
            action == null || action.isBlank() ? "无" : action,
            aiPlayer.getActionExecutor().isExecuting(),
            aiPlayer.getActionExecutor().isPlanning(),
            pos.toShortString(),
            pos.getY(),
            itemKey(aiPlayer.getMainHandItem().getItem()),
            new TreeMap<>(aiPlayer.getInventorySnapshot()),
            details == null || details.isBlank() ? "无" : "\n" + details
        );
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int showAiStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }
        AiPlayerEntity aiPlayer = getOwnedAiPlayer(player);
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }

        BlockPos pos = aiPlayer.blockPosition();
        String goal = aiPlayer.getActionExecutor().getCurrentGoal();
        String action = aiPlayer.getActionExecutor().getCurrentActionDescription();
        String agentState = aiPlayer.getActionExecutor().getStateMachine().getCurrentState().getDisplayName();
        String details = aiPlayer.getActionExecutor().getCurrentActionStatusDetails();
        String message = """
            AI 当前状态：
            - 名称: %s
            - 阶段: %s
            - 目标: %s
            - 当前动作: %s
            - 执行中: %s，规划中: %s
            - 位置: %s，Y=%d
            - 主手: %s
            - 背包: %s
            - 细节: %s
            """.formatted(
            aiPlayer.getAiPlayerName(),
            agentState,
            goal == null || goal.isBlank() ? "无" : goal,
            action == null || action.isBlank() ? "无" : action,
            aiPlayer.getActionExecutor().isExecuting(),
            aiPlayer.getActionExecutor().isPlanning(),
            pos.toShortString(),
            pos.getY(),
            itemKey(aiPlayer.getMainHandItem().getItem()),
            new TreeMap<>(aiPlayer.getInventorySnapshot()),
            details == null || details.isBlank() ? "无" : "\n" + details
        );
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int showAiLocation(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }
        AiPlayerEntity aiPlayer = getOwnedAiPlayer(player);
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }

        BlockPos aiPos = aiPlayer.blockPosition();
        BlockPos playerPos = player.blockPosition();
        double distance = Math.sqrt(aiPlayer.distanceToSqr(player));
        String dimension = aiPlayer.level().dimension().location().toString();
        String message = """
            AI 当前位置：
            - 名称: %s
            - 维度: %s
            - 坐标: X=%d, Y=%d, Z=%d
            - 与你距离: %.1f 格
            - 你的坐标: X=%d, Y=%d, Z=%d
            """.formatted(
            aiPlayer.getAiPlayerName(),
            dimension,
            aiPos.getX(),
            aiPos.getY(),
            aiPos.getZ(),
            distance,
            playerPos.getX(),
            playerPos.getY(),
            playerPos.getZ()
        );
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int stopMining(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }
        AiPlayerEntity aiPlayer = getOwnedAiPlayer(player);
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }
        aiPlayer.getActionExecutor().stopCurrentAction();
        aiPlayer.getMemory().clearTaskQueue();
        source.sendSuccess(() -> Component.literal("已停止自动挖矿，已获得物品仍保留在 %s 背包中。"
            .formatted(aiPlayer.getAiPlayerName())), true);
        return 1;
    }

    private static int returnMining(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }
        AiPlayerEntity aiPlayer = getOwnedAiPlayer(player);
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }
        aiPlayer.getActionExecutor().stopCurrentAction();
        aiPlayer.getMemory().clearTaskQueue();
        aiPlayer.setSprinting(true);
        aiPlayer.getNavigation().moveTo(player, SurvivalUtils.TASK_RUN_SPEED);
        source.sendSuccess(() -> Component.literal("已停止自动挖矿，%s 正在按正常移动回到你身边。"
            .formatted(aiPlayer.getAiPlayerName())), true);
        return 1;
    }

    private static void sendLongMessage(CommandSourceStack source, String message) {
        int chunkSize = 1800;
        for (int start = 0; start < message.length(); start += chunkSize) {
            int end = Math.min(message.length(), start + chunkSize);
            String chunk = message.substring(start, end);
            source.sendSuccess(() -> Component.literal(chunk), false);
        }
    }

    private static int stopAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        AiPlayerEntity aiPlayer = AiPlayerMod.getAiPlayerManager().getAiPlayerByOwner(player.getUUID());
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }

        aiPlayer.getActionExecutor().stopCurrentAction();
        aiPlayer.getMemory().clearTaskQueue();
        source.sendSuccess(() -> Component.literal("已停止 AI 玩家：%s。".formatted(aiPlayer.getAiPlayerName())), true);
        return 1;
    }

    private static int recallAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        AiPlayerEntity aiPlayer = AiPlayerMod.getAiPlayerManager().getAiPlayerByOwner(player.getUUID());
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。"));
            return 0;
        }

        aiPlayer.getActionExecutor().stopCurrentAction();
        aiPlayer.getMemory().clearTaskQueue();
        aiPlayer.getNavigation().stop();
        aiPlayer.setDeltaMovement(Vec3.ZERO);

        Vec3 recallPos = findRecallPosition(player);
        aiPlayer.moveTo(recallPos.x, recallPos.y, recallPos.z, player.getYRot(), 0.0F);
        source.sendSuccess(() -> Component.literal("已召回 AI 玩家：%s。".formatted(aiPlayer.getAiPlayerName())), true);
        return 1;
    }

    private static int tellAi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = getPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("该命令必须由玩家执行。"));
            return 0;
        }

        String command = StringArgumentType.getString(context, "command");
        AiPlayerEntity aiPlayer = AiPlayerMod.getAiPlayerManager().getAiPlayerByOwner(player.getUUID());
        if (aiPlayer == null) {
            source.sendFailure(Component.literal("你还没有召唤 AI 玩家。先使用 /ai spawn。"));
            return 0;
        }

        new Thread(() -> aiPlayer.getActionExecutor().processNaturalLanguageCommand(command), "ai-command-planner").start();
        source.sendSuccess(() -> Component.literal("已发送给 %s：%s".formatted(aiPlayer.getAiPlayerName(), command)), false);
        return 1;
    }

    private static ServerPlayer getPlayer(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player : null;
    }

    private static AiPlayerEntity getOwnedAiPlayer(ServerPlayer player) {
        return AiPlayerMod.getAiPlayerManager().getAiPlayerByOwner(player.getUUID());
    }

    private static String sanitizeName(String requestedName, String fallback) {
        if (requestedName == null || requestedName.isBlank()) {
            return fallback;
        }
        return requestedName.trim();
    }

    private static String formatItemName(String itemKey) {
        return itemKey.startsWith("minecraft:") ? itemKey.substring("minecraft:".length()) : itemKey;
    }

    private static Item itemFromKey(String key) {
        if (key == null || key.isBlank()) {
            return Items.AIR;
        }
        String normalized = key.contains(":") ? key : "minecraft:" + key;
        try {
            Item item = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(normalized));
            return item == null ? Items.AIR : item;
        } catch (RuntimeException e) {
            return Items.AIR;
        }
    }

    private static String itemKey(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "minecraft:air" : key.toString();
    }

    private static Vec3 findRecallPosition(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition();
        int[][] offsets = {
            {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {2, 1}, {2, -1}, {-2, 1}, {-2, -1},
            {1, 2}, {-1, 2}, {1, -2}, {-1, -2},
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
        };
        for (int[] offset : offsets) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos feet = base.offset(offset[0], dy, offset[1]);
                if (isRecallPositionSafe(level, feet)) {
                    return Vec3.atBottomCenterOf(feet);
                }
            }
        }
        return player.position().add(player.getLookAngle().normalize().scale(-2.0D));
    }

    private static boolean isRecallPositionSafe(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
            && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
    }

    private static int moveStacksToPlayer(AiPlayerEntity aiPlayer, ServerPlayer player, List<ItemStack> stacks) {
        int moved = 0;
        for (ItemStack stack : stacks) {
            moved += moveStackToPlayer(aiPlayer, player, stack);
        }
        return moved;
    }

    private static int moveStackToPlayer(AiPlayerEntity aiPlayer, ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        ItemStack remaining = stack.copy();
        int original = remaining.getCount();
        player.getInventory().add(remaining);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        int moved = original - remaining.getCount();
        if (!remaining.isEmpty()) {
            aiPlayer.addItemStack(remaining);
        }
        return moved;
    }

    private static int moveStacksToAi(AiPlayerEntity aiPlayer, ServerPlayer player, List<ItemStack> stacks) {
        int moved = 0;
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            int inserted = aiPlayer.addItemStack(stack);
            moved += inserted;
            if (inserted < stack.getCount()) {
                leftovers.add(stack.copyWithCount(stack.getCount() - inserted));
            }
        }
        for (ItemStack leftover : leftovers) {
            player.getInventory().add(leftover);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        return moved;
    }

    private static int insertStackIntoPlayerSlot(ServerPlayer player, int slot, ItemStack source) {
        if (source == null || source.isEmpty() || slot < 0 || slot >= player.getInventory().items.size()) {
            return 0;
        }
        ItemStack target = player.getInventory().items.get(slot);
        int moved = 0;
        if (target.isEmpty()) {
            moved = Math.min(source.getCount(), source.getMaxStackSize());
            player.getInventory().items.set(slot, source.copyWithCount(moved));
        } else if (ItemStack.isSameItemSameComponents(target, source)) {
            moved = Math.min(source.getCount(), target.getMaxStackSize() - target.getCount());
            if (moved > 0) {
                target.grow(moved);
            }
        }
        return moved;
    }

    private static List<ItemStack> removeStacksFromPlayer(ServerPlayer player, Item item, int count) {
        List<ItemStack> removed = new ArrayList<>();
        if (item == null || item == Items.AIR || count <= 0) {
            return removed;
        }
        int remaining = count;
        for (int slot = 0; slot < player.getInventory().items.size() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                int moved = Math.min(remaining, stack.getCount());
                removed.add(stack.split(moved));
                remaining -= moved;
                if (stack.isEmpty()) {
                    player.getInventory().items.set(slot, ItemStack.EMPTY);
                }
            }
        }
        if (!removed.isEmpty()) {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
        return removed;
    }
}
