package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.action.MiningAction;
import io.github.zoyluo.aibot.action.MovementAction;
import io.github.zoyluo.aibot.action.ToolSelector;
import io.github.zoyluo.aibot.coordination.Job;
import io.github.zoyluo.aibot.coordination.TaskBoard;
import io.github.zoyluo.aibot.craft.AcquisitionHints;
import io.github.zoyluo.aibot.craft.CraftingHelper;
import io.github.zoyluo.aibot.craft.InstantCrafter;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.gift.GiftCelebrator;
import io.github.zoyluo.aibot.gift.GiftDispatcher;
import io.github.zoyluo.aibot.goal.Goal;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemory;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.mining.OreProspector;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.pathfinding.Standability;
import io.github.zoyluo.aibot.task.BlueprintLoader;
import io.github.zoyluo.aibot.task.BreedTask;
import io.github.zoyluo.aibot.task.CreateObsidianTask;
import io.github.zoyluo.aibot.task.DescendToYTask;
import io.github.zoyluo.aibot.task.EmergencyShelterTask;
import io.github.zoyluo.aibot.task.EvadeTask;
import io.github.zoyluo.aibot.task.PickupDropsTask;
import io.github.zoyluo.aibot.task.PillarUpTask;
import io.github.zoyluo.aibot.task.PlantSaplingTask;
import io.github.zoyluo.aibot.task.ResupplyTask;
import io.github.zoyluo.aibot.task.RideTask;
import io.github.zoyluo.aibot.task.ShearSheepTask;
import io.github.zoyluo.aibot.task.BuildGolemTask;
import io.github.zoyluo.aibot.task.BuildWallTask;
import io.github.zoyluo.aibot.task.FlattenAreaTask;
import io.github.zoyluo.aibot.task.PatrolTask;
import io.github.zoyluo.aibot.task.PlaceBoatTask;
import io.github.zoyluo.aibot.task.ScaffoldWalkTask;
import io.github.zoyluo.aibot.task.ThrowAtTask;
import io.github.zoyluo.aibot.task.TameTask;
import io.github.zoyluo.aibot.task.Threat;
import io.github.zoyluo.aibot.task.BuildTask;
import io.github.zoyluo.aibot.task.ChaseAttackTask;
import io.github.zoyluo.aibot.task.CombatTask;
import io.github.zoyluo.aibot.task.ContainerTask;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.EatTask;
import io.github.zoyluo.aibot.task.EmoteTask;
import io.github.zoyluo.aibot.task.MilkCowTask;
import io.github.zoyluo.aibot.task.FishTask;
import io.github.zoyluo.aibot.task.FarmTask;
import io.github.zoyluo.aibot.task.GatherQuotaTask;
import io.github.zoyluo.aibot.task.FollowTask;
import io.github.zoyluo.aibot.task.GuardTask;
import io.github.zoyluo.aibot.task.HoldTask;
import io.github.zoyluo.aibot.task.LightAreaTask;
import io.github.zoyluo.aibot.task.LongRunningIntentManager;
import io.github.zoyluo.aibot.task.MineTask;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.SleepTask;
import io.github.zoyluo.aibot.task.SmeltTask;
import io.github.zoyluo.aibot.task.StockpileTask;
import io.github.zoyluo.aibot.task.OreDigTask;
import io.github.zoyluo.aibot.task.StripMineTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskStatus;
import io.github.zoyluo.aibot.task.TradeTask;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.SaplingBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ToolRegistry {
    /** run_command 永久黑名单:即使主人门禁放行也绝不执行的服务器级危险指令。 */
    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "stop", "ban", "ban-ip", "pardon", "pardon-ip", "op", "deop", "whitelist", "kick", "save-off", "reload");

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public ToolRegistry() {
        registerDefaults();
    }

    public Optional<ToolDefinition> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> allTools() {
        return List.copyOf(tools.values());
    }

    public List<ToolDefinition> tools(AIBotConfig.Brain config) {
        return tools(config, config.exposesLowLevelTools());
    }

    public List<ToolDefinition> tools(AIBotConfig.Brain config, boolean exposeLowLevelTools) {
        return tools(config, exposeLowLevelTools, config.memoryToolsEnabled(), config.coordinationToolsEnabled());
    }

    // 弱模型误选诱饵:strip_mine/mine_vein 是脆逻辑旧任务(硬失败多、与 C1 引导相悖),set_goal 让模型
    // 自由编步骤字符串=「自信地做错」高发口。默认不暴露给模型(exposeAdvancedTools=false),高层入口
    // gather/mine_ore/achieve_goal 全覆盖;/aibot 命令行与 verify 直连任务层,不受影响。
    private static final java.util.Set<String> ADVANCED_ONLY_TOOLS = java.util.Set.of(
            "strip_mine", "mine_vein", "set_goal", "assign_task",
            // These remain available to commands and expert mode, but the model gets the two
            // intent-level tools below. Keeping the legacy surface hidden stops it from
            // decomposing a single journey into conflicting movement and construction calls.
            "attack", "chase_attack", "come_here", "follow", "guard", "flee",
            "pillar_up", "go_surface", "descend_to_y", "explore", "wander",
            "scaffold_walk", "patrol", "unstuck", "use_held_item");

    public List<ToolDefinition> tools(AIBotConfig.Brain config,
                                      boolean exposeLowLevelTools,
                                      boolean memoryToolsEnabled,
                                      boolean coordinationToolsEnabled) {
        boolean advanced = config.advancedToolsExposed();
        return tools.values().stream()
                .filter(tool -> switch (tool.group()) {
                    case CORE -> true;
                    case MEMORY -> memoryToolsEnabled;
                    case COORDINATION -> coordinationToolsEnabled;
                    case LOW_LEVEL -> exposeLowLevelTools;
                })
                .filter(tool -> advanced || !ADVANCED_ONLY_TOOLS.contains(tool.name()))
                .toList();
    }

    private void registerDefaults() {
        register("say", "Reply to the human in Simplified Chinese. The reply is shown in the AIBot panel.", objectSchema()
                .property("message", stringSchema("the text to say"))
                .required("message")
                .build(), (bot, args) -> {
            String message = requiredString(args, "message");
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", message);
            return ok("said");
        });

        register("look_at", "Turn the bot's head toward a coordinate", xyzSchema(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            LookAction.lookAt(bot, new Vec3d(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z")));
            return ok("looked");
        });

        register("move_to", "智能移动到坐标:自动绕路、挖开普通遮挡、垫高和跨缺口铺路。", xyzSchema(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            BlockPos goal = blockPos(args);
            Task task = new MoveTask(bot, goal);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: smart_move -> " + goal.toShortString());
        });

        register("mine_block", "Low-level single-block break at given coords. Bot must already be within reach. For gathering materials or mining counts, prefer assign_task with task_type mine.", xyzSchema(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            BlockPos pos = blockPos(args);
            MiningAction.startMining(bot, pos, Direction.getFacing(bot.getEyePos().subtract(pos.toCenterPos())));
            return ok("started");
        });

        register("place_block", "Low-level manual placement of the currently held block at given coords. For crafting table placement during recipes, prefer craft because it can place a held crafting table automatically.", xyzSchema(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            return result(BuildAction.placeBlockAt(bot, blockPos(args)));
        });

        register("select_hotbar", "Select hotbar slot 0..8", objectSchema()
                .property("slot", integerSchema("hotbar slot", 0, 8))
                .required("slot")
                .build(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> result(InventoryAction.selectHotbar(bot, requiredInt(args, "slot"))));

        register("inventory", "Get the bot's current inventory", objectSchema().build(), (bot, args) ->
                ok(InventoryAction.summarize(bot).toString()));

        register("equip_best_tool", "Equip the best available tool for breaking a block type", objectSchema()
                .property("block", stringSchema("block id, for example minecraft:stone"))
                .required("block")
                .build(), (bot, args) -> {
            Block block = requiredBlock(args, "block");
            ToolSelector.Selection selection = ToolSelector.equipBestTool(bot, block.getDefaultState());
            return ok(selection.describe());
        });

        register("plan_craft", "Read-only preflight for crafting. Returns feasible, deterministic craft steps, missing materials, and each missing material's acquisition source.", objectSchema()
                .property("item", stringSchema("target item id, for example minecraft:stone_pickaxe"))
                .property("count", integerSchema("desired count"))
                .required("item")
                .build(), (bot, args) -> ok(craftPlanJson(CraftingHelper.plan(bot, requiredItem(args, "item"), optionalInt(args, "count", 1)))));

        register("craft", "INSTANT crafting: automatically checks materials and crafts immediately in this same call — no crafting table, no walking, no waiting. Resolves intermediates such as planks and sticks recursively, so craft the target item directly. If base materials are missing it returns need: <item> xN — obtain exactly those (mine_ore/gather/smelt or achieve_goal), then call craft again.", objectSchema()
                .property("item", stringSchema("item id, for example minecraft:stone_pickaxe"))
                .property("count", integerSchema("desired count"))
                .required("item")
                .build(), (bot, args) -> {
            // 瞬时合成:直播取舍——免工作台免走路,一次调用一轮出结果,省掉模型多轮空转。
            String result = InstantCrafter.craft(bot, requiredItem(args, "item"), optionalInt(args, "count", 1));
            return result.startsWith("need:") ? fail(result) : ok(result);
        });

        register("eat", "Eat available food from inventory", objectSchema().build(), (bot, args) -> {
            Task task = new EatTask();
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("smelt", "Smelt input items in a nearby or held furnace using available fuel. It does not craft a furnace; call craft first if needed.", objectSchema()
                .property("input_item", stringSchema("input item id, for example minecraft:raw_iron"))
                .property("output_item", stringSchema("expected output item id, for example minecraft:iron_ingot"))
                .property("count", integerSchema("output count"))
                .required("input_item")
                .required("output_item")
                .build(), (bot, args) -> {
            Task task = new SmeltTask(
                    requiredItem(args, "input_item"),
                    requiredItem(args, "output_item"),
                    optionalInt(args, "count", 1));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("gather", "Gather an item until the inventory contains the requested quota. USE FOR: logs/wood(木头), stone(石头), cobblestone(圆石), dirt, sand, seeds — e.g. collect 64 cobblestone. It loops survey, move, harvest, pickup automatically. NOT FOR real ores (iron/gold/diamond/coal ore) — use mine_ore for those.", objectSchema()
                .property("item", stringSchema("target item id, for example minecraft:cobblestone"))
                .property("count", integerSchema("desired inventory count"))
                .required("item")
                .build(), (bot, args) -> {
            Task task = new GatherQuotaTask(requiredItem(args, "item"), optionalInt(args, "count", 1));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("fish", "Fish at nearby water with a fishing rod. Casts, waits for bite, reels, collects loot, and loops until max_catches or max_ticks.", objectSchema()
                .property("max_catches", integerSchema("number of successful catches"))
                .property("max_ticks", integerSchema("maximum task duration in ticks"))
                .build(), (bot, args) -> {
            Task task = new FishTask(optionalInt(args, "max_catches", 1), optionalInt(args, "max_ticks", 6000));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("trade", "Trade directly with a nearby villager without opening a merchant screen. Supports simple one-input offers, optionally targeting a sell item.", objectSchema()
                .property("target_item", stringSchema("optional item id to buy, for example minecraft:bread"))
                .property("max_distance", integerSchema("search radius"))
                .build(), (bot, args) -> {
            Task task = new TradeTask(optionalItem(args, "target_item"), optionalInt(args, "max_distance", 16));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("set_base", "Remember the bot's current position as the base for stockpiling and resupply tasks.", objectSchema().build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            BotMemoryStore.INSTANCE.of(bot.getUuid()).markPlace("base", bot.getServerWorld(), bot.getBlockPos());
            return ok("marked_base: " + bot.getBlockPos().toShortString());
        });

        register("deposit_all", "Deposit carried items into containers near the remembered base. Same items prefer containers that already contain them; all_except_tools defaults true.", objectSchema()
                .property("all_except_tools", booleanSchema("deposit all non-damageable items and keep tools/equipment"))
                .build(), (bot, args) -> {
            Task task = new StockpileTask(optionalBoolean(args, "all_except_tools", true));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("strip_mine", "ADVANCED manual branch-tunnel mining (2-high tunnel, stair shaft, torches, depot returns). For obtaining ores DO NOT use this — use mine_ore instead (it auto-prepares the pickaxe and is safer). Only for explicit manual tunneling requests.", objectSchema()
                .property("direction", stringSchema("north, south, east, or west"))
                .property("length", integerSchema("main tunnel length"))
                .property("spacing", integerSchema("branch spacing and branch depth"))
                .property("depot_x", integerSchema("optional depot chest x"))
                .property("depot_y", integerSchema("optional depot chest y"))
                .property("depot_z", integerSchema("optional depot chest z"))
                .property("target_ores", stringSchema("optional comma separated ore block ids; default is common ores"))
                .build(), (bot, args) -> {
            Task task = new StripMineTask(
                    optionalDirection(args, "direction", Direction.NORTH),
                    optionalInt(args, "length", 16),
                    optionalInt(args, "spacing", 4),
                    optionalBlockPos(args, "depot_x", "depot_y", "depot_z"),
                    optionalBlocksCsv(args, "target_ores"));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("mine_vein", "ADVANCED: mine the nearest visible ore vein in range (radius only 6, fails fast). Prefer mine_ore for any ore request. Optional target_ores is a comma separated list of ore block ids.", objectSchema()
                .property("target_ores", stringSchema("optional comma separated ore block ids; default is common ores"))
                .build(), (bot, args) -> {
            Task task = StripMineTask.mineNearbyVein(optionalBlocksCsv(args, "target_ores"));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("mine_ore", "PREFERRED way to obtain ORES / RAW drops ONLY (e.g. minecraft:iron_ore -> raw_iron 生铁, coal, diamond). Auto-prepares the pickaxe then mines. NOT FOR finished items: 铁锭 iron_ingot / tools / armor need smelting+crafting — use achieve_goal for those. Do not manually break this into gather/craft/mine steps.", objectSchema()
                .property("ore", stringSchema("ore block id or raw item, e.g. minecraft:iron_ore or minecraft:raw_iron"))
                .property("count", integerSchema("how many ore blocks to mine"))
                .required("ore")
                .build(), (bot, args) -> {
            if (!AIBotConfig.get().goal().autoToolFillEnabled()) {
                Task task = new OreDigTask(oreTargetsFrom(requiredString(args, "ore")), optionalInt(args, "count", 1));
                TaskManager.INSTANCE.assign(bot, task);
                return ok("assigned: " + task.name());
            }
            boolean started = GoalExecutor.INSTANCE.submit(bot,
                    new Goal.MineOre(oreTargetsFrom(requiredString(args, "ore")), optionalInt(args, "count", 1)));
            return started ? ok("goal_assigned: mine_ore") : fail("goal_plan_failed");
        });

        register("achieve_goal", "Achieve ANY FINISHED item goal with one call — ingots(锭, auto-smelts), tools, weapons, armor, blocks. E.g. iron_pickaxe or 10 iron_ingot: it runs the whole chain (wood->pickaxe->mine->smelt->craft) automatically. NEVER manually decompose into move/mine/craft steps (that wastes all your turns); NEVER use mine_ore for 锭/成品 (raw_iron is NOT iron_ingot).", objectSchema()
                .property("item", stringSchema("target item/tool id, for example minecraft:iron_pickaxe or minecraft:iron_ingot"))
                .property("count", integerSchema("desired inventory count"))
                .required("item")
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submit(bot,
                    new Goal.HaveItem(requiredItem(args, "item"), optionalInt(args, "count", 1)));
            return started ? ok("goal_assigned: achieve_goal") : fail("goal_plan_failed");
        });

        register("harvest_crop", "Grow and harvest a crop with deterministic planning. Use for requests like 种小麦/收点小麦/get wheat. Crop is wheat, carrot, or potato. The system auto-prepares a hoe, tills, plants, waits for growth, and harvests; do not decompose manually.", objectSchema()
                .property("crop", stringSchema("crop: wheat, carrot, or potato"))
                .property("count", integerSchema("how many to harvest"))
                .required("crop")
                .build(), (bot, args) -> {
            FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop"));
            net.minecraft.item.Item produce = spec.crop() == net.minecraft.block.Blocks.WHEAT
                    ? net.minecraft.item.Items.WHEAT
                    : spec.seed(); // 胡萝卜/土豆:产出即种子物品
            boolean started = GoalExecutor.INSTANCE.submit(bot,
                    new Goal.HarvestCrop(spec.crop(), spec.seed(), produce, optionalInt(args, "count", 1)));
            return started ? ok("goal_assigned: harvest_crop") : fail("goal_plan_failed");
        });

        register("provision_food", "Stock food end-to-end; AUTO-PICKS hunting or farming by scanning what's actually around (perception-driven). "
                + "This is the DEFAULT for ANY general 'get food' request: 找吃的/找点吃的/找吃的去/找吃的去啊/去找吃的/找东西吃/找点东西吃/去搞点吃的/弄点吃的/弄点肉/打点肉吃/去打猎/我饿了/饿了/备点粮/搞点食物/补充食物/get some food/go find food/make food/go hunt. "
                + "Auto-plans (hunt->cook meat OR farm->bread) based on surroundings; do NOT decompose manually. count = how many food items (default 4).", objectSchema()
                .property("count", integerSchema("how many cooked food items to stock (default 4)"))
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submit(bot,
                    new Goal.Food(optionalInt(args, "count", 4)));
            return started ? ok("goal_assigned: provision_food") : fail("goal_plan_failed");
        });

        register("forage", "Forage SPECIFIC wild berries/melon nearby. ONLY when the user EXPLICITLY asks for berries/wild fruit, NOT for general food. "
                + "Use for 采点野果/采点浆果/摘浆果/采甜浆果/摘西瓜/想吃浆果; needs berry bushes or melons around. "
                + "For ANY general 找吃的/搞点吃的 request use provision_food instead (it auto-picks hunt or farm). count = how many (default 4).", objectSchema()
                .property("count", integerSchema("how many wild food to gather (default 4)"))
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submit(bot,
                    new Goal.HaveItem(net.minecraft.item.Items.SWEET_BERRIES, optionalInt(args, "count", 4)));
            return started ? ok("goal_assigned: forage") : fail("goal_plan_failed");
        });

        register("achieve_armor", "Make and equip a full set of iron armor plus an iron sword with deterministic planning. Use for 武装起来/做一身装备/给我穿上盔甲/gear up. Auto-plans mining, smelting and crafting; do not decompose manually.", objectSchema()
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Armor());
            return started ? ok("goal_assigned: achieve_armor") : fail("goal_plan_failed");
        });

        register("achieve_workstation", "Set up a base: craft and place a crafting table, furnace and chest nearby. Use for 建个家/搭个工作台/摆好工作台熔炉箱子/set up a base. Auto-plans gathering and crafting; do not decompose manually.", objectSchema()
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Workstation());
            return started ? ok("goal_assigned: achieve_workstation") : fail("goal_plan_failed");
        });

        register("build_house", "Build a house/shelter. Use for 盖房子/建个家/造房子/盖个小屋/build a house. The goal system auto-gathers ALL missing materials (wood/stone/glass) then builds — call once then STOP. Either pass blueprint (small_hut default, hut_5x5), OR pass width/depth/height/material for a custom house (e.g. 盖个7格宽的石头房 -> width=7, material=stone_like). material: planks (wood, default) / stone_like / glass.", objectSchema()
                .property("blueprint", stringSchema("preset blueprint name: small_hut (default) or hut_5x5; ignored when width/depth/height given"))
                .property("width", integerSchema("custom house outer width in blocks (3..16)", 3, 16))
                .property("depth", integerSchema("custom house outer depth in blocks (3..16)", 3, 16))
                .property("height", integerSchema("custom house wall height in blocks (2..8)", 2, 8))
                .property("material", stringSchema("wall material palette: planks (default) / stone_like / glass"))
                .build(), (bot, args) -> {
            // P3 参数化:给了任意尺寸参数就走 custom:WxDxH:material 规格(缺省边长 5/5/3),否则用预设蓝图。
            boolean custom = args != null && (args.has("width") || args.has("depth") || args.has("height") || args.has("material"));
            String bp;
            if (custom) {
                int w = optionalInt(args, "width", 5);
                int d = optionalInt(args, "depth", 5);
                int h = optionalInt(args, "height", 3);
                String material = optionalString(args, "material", "planks");
                bp = "custom:" + w + "x" + d + "x" + h + ":" + material;
            } else {
                bp = optionalString(args, "blueprint", "small_hut");
            }
            boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Build(bp));
            return started ? ok("goal_assigned: build " + bp) : fail("goal_plan_failed");
        });

        register("stockpile", "Obtain N of an item then store everything into a nearby chest. Use for 囤货/囤点/存起来/stockpile N cobblestone. Auto-plans obtaining and depositing; do not decompose manually.", objectSchema()
                .property("item", stringSchema("item id to stockpile, e.g. minecraft:cobblestone"))
                .property("count", integerSchema("how many to obtain"))
                .required("item")
                .build(), (bot, args) -> {
            boolean started = GoalExecutor.INSTANCE.submit(bot,
                    new Goal.Stockpile(requiredItem(args, "item"), optionalInt(args, "count", 1)));
            return started ? ok("goal_assigned: stockpile") : fail("goal_plan_failed");
        });

        register("find_container", "Find the nearest reachable inventory container such as a chest", objectSchema()
                .property("radius", integerSchema("search radius"))
                .build(), (bot, args) -> ContainerTask.nearestContainer(bot, optionalInt(args, "radius", 8))
                .map(pos -> ok("{\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}"))
                .orElseGet(() -> fail("no_container")));

        register("deposit", "Deposit items into a nearby or specified container. Use all_except_tools=true to store surplus materials while keeping damageable tools/equipment.", objectSchema()
                .property("item", stringSchema("optional item id to deposit, for example minecraft:cobblestone"))
                .property("count", integerSchema("optional item count; omit or <=0 means all matching items"))
                .property("all_except_tools", booleanSchema("deposit all non-damageable items"))
                .property("chest_x", integerSchema("optional container x"))
                .property("chest_y", integerSchema("optional container y"))
                .property("chest_z", integerSchema("optional container z"))
                .build(), (bot, args) -> {
            Task task = ContainerTask.deposit(
                    optionalBlockPos(args, "chest_x", "chest_y", "chest_z"),
                    optionalItem(args, "item"),
                    optionalInt(args, "count", 0),
                    optionalBoolean(args, "all_except_tools", false));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("withdraw", "Withdraw a specific item count from a nearby or specified container", objectSchema()
                .property("item", stringSchema("item id to withdraw, for example minecraft:cobblestone"))
                .property("count", integerSchema("count to withdraw"))
                .property("chest_x", integerSchema("optional container x"))
                .property("chest_y", integerSchema("optional container y"))
                .property("chest_z", integerSchema("optional container z"))
                .required("item")
                .build(), (bot, args) -> {
            Task task = ContainerTask.withdraw(
                    optionalBlockPos(args, "chest_x", "chest_y", "chest_z"),
                    requiredItem(args, "item"),
                    optionalInt(args, "count", 1));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("equip_armor", "Equip the best armor pieces from inventory and select the best weapon", objectSchema().build(), (bot, args) -> {
            int equipped = EquipAction.equipBestArmor(bot);
            EquipAction.equipBestWeapon(bot);
            return ok("equipped_armor_pieces: " + equipped);
        });

        register("drop_item", "Drop N of an item from the inventory onto the ground in front of the bot. Use for 丢掉/扔掉/丢地上/不要了. ALWAYS pass count when the user says a number (丢5个 -> count=5); omitting count drops ALL of that item. Note: N items land as ONE stacked pile, not N piles.", objectSchema()
                .property("item", stringSchema("item id, for example minecraft:cobblestone"))
                .property("count", integerSchema("how many to drop; omit or 999 = drop all of that item"))
                .required("item")
                .build(), (bot, args) -> {
            Item item = requiredItem(args, "item");
            int dropped = dropFromInventory(bot, item, optionalInt(args, "count", 999), null);
            return dropped > 0
                    ? ok("dropped: " + Registries.ITEM.getId(item) + " x" + dropped)
                    : fail("not_in_inventory: " + Registries.ITEM.getId(item));
        });

        register("give_item", "Toss N of an item to a nearby player: the bot turns to face them and throws the items over. Works within 8 blocks — if farther away, come_here or move_to first, then give. Use for 给我/递给我/把X给我. Omit player to use the owner. ALWAYS pass count when the user says a number (给我10个 -> count=10); 都/全部/所有 -> count=999.", objectSchema()
                .property("player", stringSchema("player name; omit to use the bot's owner or nearest player"))
                .property("item", stringSchema("item id, for example minecraft:iron_ingot"))
                .property("count", integerSchema("how many to give, default 1; use 999 for all"))
                .required("item")
                .build(), (bot, args) -> {
            ServerPlayerEntity target = resolveTossTarget(bot, optionalString(args, "player", ""));
            if (target == null) {
                return fail("player_not_found");
            }
            if (bot.distanceTo(target) > 8.0F) {
                return fail("too_far: " + (int) bot.distanceTo(target) + " blocks away, come_here first then give_item again");
            }
            Item item = requiredItem(args, "item");
            int given = dropFromInventory(bot, item, optionalInt(args, "count", 1), target);
            return given > 0
                    ? ok("tossed: " + Registries.ITEM.getId(item) + " x" + given + " to " + target.getGameProfile().getName())
                    : fail("not_in_inventory: " + Registries.ITEM.getId(item));
        });

        register("scan_surroundings", "God-view scan of surroundings: nearest lava/water/ores/trees/chests/furnaces/beds, hostile mobs and ground items with coordinates and distances (blocks radius 12, entities 24). ALWAYS call this FIRST before answering any question about what is nearby (附近有没有X/周围有什么/跳进旁边的X) — never claim something is not nearby without scanning.", objectSchema().build(), (bot, args) ->
                ok(io.github.zoyluo.aibot.perception.PerceptionCollector.scanReport(bot)));

        register("speak", "让观众听到你说的话(触发语音 TTS)。每次一句短话(≤30中文字),超长自动截断。需要说话给观众/主人听时用它:回复、吐槽、庆祝、挑衅、互动等。plain text 短句也会自动 TTS,但 speak 是显式控制,效果更好。", objectSchema()
                .property("message", stringSchema("要说什么,一句短话"))
                .required("message")
                .build(), (bot, args) -> {
            String message = requiredString(args, "message");
            if (message.length() > 100) {
                message = message.substring(0, 97) + "...";
            }
            if (message.isBlank()) {
                return fail("empty_message");
            }
            BrainCoordinator.INSTANCE.triggerSpeech(bot, message);
            return ok("said");
        });

        register("finish", "End your current turn. Call ONCE after all tool calls for this step are done, with a one-line summary spoken to the audience. Until finish() is called, your turn is not considered complete and the player cannot send a new instruction — so do not skip it. NEVER call finish as your first action — only after at least one other tool (speak/come_here/gather/etc).", objectSchema()
                .property("summary", stringSchema("one-line summary of what just happened, ≤30 Chinese chars, spoken to audience"))
                .required("summary")
                .build(), (bot, args) -> {
            String summary = requiredString(args, "summary");
            if (summary == null || summary.isBlank()) {
                summary = "Done";
            }
            if (summary.length() > 100) {
                summary = summary.substring(0, 97) + "...";
            }
            // 先触发 TTS 让观众听到总结
            BrainCoordinator.INSTANCE.triggerSpeech(bot, summary);
            // 标记 turn 完成 → BrainCoordinator 允许下一条用户消息
            BrainCoordinator.INSTANCE.markTurnFinished(bot);
            return ok("finished: " + summary);
        });

        register("run_command", "Execute ONE server command with OP permission (leading slash optional), e.g. command=\"tp <your_name> <owner_name>\". HARD-LOCKED: only works when the OWNER's current message explicitly demands it; rejected for gift/viewer instructions and self-initiative.", objectSchema()
                .property("command", stringSchema("the full command, for example: tp Bob ImMICx"))
                .required("command")
                .build(), (bot, args) -> {
            if (!BrainCoordinator.INSTANCE.currentRequestFromOwner(bot)) {
                return fail("forbidden: run_command only works when the owner directly asked for it in this request");
            }
            String command = requiredString(args, "command").trim();
            while (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (command.isBlank()) {
                return fail("empty_command");
            }
            String root = command.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
            if (BLOCKED_COMMANDS.contains(root)) {
                return fail("blocked_command: " + root + " is never allowed");
            }
            net.minecraft.server.MinecraftServer server = bot.getServer();
            if (server == null) {
                return fail("no_server");
            }
            server.getCommandManager().executeWithPrefix(bot.getCommandSource().withLevel(4), command);
            io.github.zoyluo.aibot.log.BotLog.comm(bot, "run_command_executed", "command", command);
            return ok("executed: /" + command);
        });

        register("smart_navigate", "统一智能移动。mode=go(去坐标/主人/标记), follow(持续跟随), explore(朝方向探索), wander(附近闲逛), route(明确修桥/铺路), pillar(原地垫高), surface(回地表), descend(安全下到 Y 层), patrol(巡逻), escape(脱离附近敌怪), unstuck(卡住自救)。所有移动模式均内置绕路、破普通遮挡、自动垫高、跨缺口铺最少支撑和失败反馈；不要再拼接 move/follow/scaffold/pillar 等多个工具。", objectSchema()
                .property("mode", stringSchema("go/follow/explore/wander/route/pillar/surface/descend/patrol/escape/unstuck"))
                .property("player_name", stringSchema("go/follow 时目标玩家；省略为主人"))
                .property("use_marker", booleanSchema("go/route 时使用主人 Shift+中键标记"))
                .property("x", integerSchema("go/route 时目标 x"))
                .property("y", integerSchema("go 时目标 y；descend 时目标 Y"))
                .property("z", integerSchema("go/route 时目标 z"))
                .property("direction", stringSchema("explore/route 时 north/south/east/west"))
                .property("distance", integerSchema("explore/wander 距离"))
                .property("length", integerSchema("route 铺路长度"))
                .property("max_blocks", integerSchema("route 最多消耗的方块数"))
                .property("height", integerSchema("pillar 垫高格数"))
                .property("radius", integerSchema("patrol 半径"))
                .property("laps", integerSchema("patrol 圈数"))
                .required("mode")
                .build(), ToolRegistry::assignSmartNavigate);

        register("smart_combat", "统一智能战斗。mode=attack(击杀指定生物), guard(护卫主人/玩家/坐标), chase_owner(持续追杀主人), escape(撤离附近敌怪)。会自动穿甲选武器、追击与智能导航；遇到普通遮挡会绕、挖、垫高或跨缺口，不要先调移动工具。", objectSchema()
                .property("mode", stringSchema("attack/guard/chase_owner/escape"))
                .property("entity_type", stringSchema("attack 时实体 id，例如 minecraft:zombie"))
                .property("count", integerSchema("attack 时击杀数，默认 1"))
                .property("player_name", stringSchema("guard 时护卫的玩家；省略为主人"))
                .property("x", integerSchema("guard 时固定地点 x"))
                .property("y", integerSchema("guard 时固定地点 y"))
                .property("z", integerSchema("guard 时固定地点 z"))
                .required("mode")
                .build(), ToolRegistry::assignSmartCombat);

        register("attack", "KILL N entities of a type — the ONLY correct tool for 杀/击杀/杀死/kill requests. Deterministic combat task: equips armor and weapon, fights until exactly N are killed, then completes. Example: 杀一只牛 -> entity_type=minecraft:cow, count=1.", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:zombie"))
                .property("count", integerSchema("number of kills"))
                .required("entity_type")
                .build(), (bot, args) -> {
            // 追击牵引:有主人时默认拴 16 格——追怪跑出这个半径就放弃、回主人身边。
            // 直播里 bot 追怪脱离画面是大忌(主人被围/观众看不到),这是"像傻子走来走去"的真凶。
            java.util.UUID leashOwner = io.github.zoyluo.aibot.manager.AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null);
            Task task = new CombatTask(
                    requiredEntityType(args, "entity_type"),
                    optionalInt(args, "count", 1),
                    io.github.zoyluo.aibot.AIBotConfig.get().combat().retreatHp(),
                    leashOwner, 16.0D);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("chase_attack", "持续追杀主人本人(直播反水/礼物追杀节目效果)。会自动绕路、挖开普通遮挡、垫高和跨缺口铺路,无需再调用移动或施工工具。用于主人说\"一直追杀我/从现在开始追着打我/反水干我\"。仅追主人自己,需服务端 pvp=true 才真掉血。", objectSchema().build(), (bot, args) -> {
            Optional<ChaseAttackTask> task = ChaseAttackTask.ownerTarget(bot);
            if (task.isEmpty()) {
                return fail("no_owner: 这个 bot 没有主人,无法追杀");
            }
            TaskManager.INSTANCE.assign(bot, task.get());
            return ok("assigned: chase_attack");
        });

        register("sleep", "Find or place a bed, sleep through night, and wake up in the morning", objectSchema().build(), (bot, args) -> {
            Task task = new SleepTask();
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("light_area", "Place torches around the bot where block light is below the configured threshold", objectSchema()
                .property("radius", integerSchema("scan radius"))
                .property("max_torches", integerSchema("maximum torches to place"))
                .build(), (bot, args) -> {
            Task task = new LightAreaTask(optionalInt(args, "radius", 8), optionalInt(args, "max_torches", 8));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("come_here", "Move once to a player, then stop when near them. Use this for 过来/来我身边/come here. Do not use follow unless the human explicitly asks to keep following.", objectSchema()
                .property("player_name", stringSchema("optional player name; defaults to owner"))
                .build(), (bot, args) -> {
            ServerPlayerEntity target = targetPlayer(bot, optionalString(args, "player_name", ""))
                    .orElseThrow(() -> new IllegalArgumentException("target_player_not_found"));
            Task task = new MoveTask(bot, target.getBlockPos());
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: come_here");
        });

        register("emote", "直播卖萌/表演/庆祝的肢体动作,给观众一点看头。style: wave(挥手打招呼) / nod(点头) / shake(摇头) / jump(蹦跳) / spin(转圈) / bow(鞠躬行礼) / look_around(东张西望) / dance(尬舞,默认) / celebrate(放烟花+转圈庆祝). 纯表演,1~3 秒做完,不影响也不需要材料。适合:主人夸你、完成一件事、观众刷礼物/弹幕活跃、开场收场时来一个。", objectSchema()
                .property("style", stringSchema("wave/nod/shake/jump/spin/bow/look_around/dance/celebrate,默认 dance"))
                .build(), (bot, args) -> {
            String style = optionalString(args, "style", "dance");
            String s = style.toLowerCase();
            if (s.contains("celebrat") || s.contains("firework") || style.contains("庆祝") || style.contains("烟花") || style.contains("礼花")) {
                GiftCelebrator.INSTANCE.celebrate(bot, 4,
                        new GiftDispatcher.GiftEvent("直播间", "emote", 1, bot.getGameProfile().getName()));
                return ok("emote: 🎆庆祝");
            }
            Task task = new EmoteTask(style);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: emote");
        });

        register("milk_cow", "挤牛奶:走到附近的牛旁边,用背包里的空桶(bucket)挤奶。喝牛奶能立刻解除中毒/虚弱等负面效果,也能当食物。没有空桶会失败——先 craft minecraft:bucket 或让主人给。count = 挤几桶(默认 1)。", objectSchema()
                .property("count", integerSchema("挤几桶牛奶,默认 1"))
                .build(), (bot, args) -> {
            Task task = new MilkCowTask(optionalInt(args, "count", 1));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        // ================= §17.16 真人工具链大扩容(24 个) =================

        register("pillar_up", "原地垫方块升高 N 格(上去/上树/上墙头/垫高)。竖直方向用它;水平过河用 scaffold_walk。需背包有可放方块。", objectSchema()
                .property("height", integerSchema("升高几格,默认 5", 2, 32))
                .build(), (bot, args) -> {
            Task task = new PillarUpTask(optionalInt(args, "height", 5));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("go_surface", "回到地面/地表(从矿洞、地下上来)。自动找最近的地表落点走/挖过去。", objectSchema().build(), (bot, args) -> {
            ServerWorld world = bot.getServerWorld();
            BlockPos feet = bot.getBlockPos();
            int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
            if (feet.getY() >= topY - 2) {
                return ok("already_on_surface");
            }
            BlockPos target = surfaceStandable(world, feet.getX(), feet.getZ(), 16);
            if (target == null) {
                return fail("no_surface_spot");
            }
            Task task = new MoveTask(bot, target);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: go_surface -> " + target.toShortString());
        });

        register("descend_to_y", "挖竖井下到指定 Y 层(挖钻石先到 y=-58)。遇岩浆自动停;到层后再 mine_ore。", objectSchema()
                .property("y", integerSchema("目标 Y 层,例如 -58", -59, 100))
                .required("y")
                .build(), (bot, args) -> {
            Task task = new DescendToYTask(Math.max(-59, Math.min(100, requiredInt(args, "y"))));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("explore", "朝一个方向出发探索一段距离(去北边看看/往那边走走)。", objectSchema()
                .property("direction", stringSchema("north/south/east/west;省略=当前朝向"))
                .property("distance", integerSchema("走多远,默认 32", 8, 64))
                .build(), (bot, args) -> {
            Direction dir = optionalDirection(args, "direction", bot.getHorizontalFacing());
            int dist = Math.max(8, Math.min(64, optionalInt(args, "distance", 32)));
            BlockPos target = surfaceStandable(bot.getServerWorld(),
                    bot.getBlockX() + dir.getOffsetX() * dist,
                    bot.getBlockZ() + dir.getOffsetZ() * dist, 8);
            if (target == null) {
                return fail("no_standable_target");
            }
            Task task = new MoveTask(bot, target);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: explore -> " + target.toShortString());
        });

        register("wander", "附近随便逛逛(没事干时的活人感,随机 8~N 格)。", objectSchema()
                .property("distance", integerSchema("最远几格,默认 16", 8, 32))
                .build(), (bot, args) -> {
            BlockPos target = randomWanderTarget(bot, optionalInt(args, "distance", 16));
            if (target == null) {
                return fail("wander_no_target");
            }
            Task task = new MoveTask(bot, target);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: wander");
        });

        register("pickup_items", "把附近地上的掉落物全捡起来(打完怪/砍完树的散落物,别浪费)。", objectSchema()
                .property("radius", integerSchema("搜多远,默认 16", 4, 32))
                .build(), (bot, args) -> {
            Task task = new PickupDropsTask(optionalInt(args, "radius", 16));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("flee", "立刻逃离最近的敌对怪(快跑/撤退),冲刺拉开 20 格再停。", objectSchema().build(), (bot, args) -> {
            HostileEntity hostile = bot.getServerWorld()
                    .getEntitiesByClass(HostileEntity.class, bot.getBoundingBox().expand(24.0D), Entity::isAlive)
                    .stream()
                    .min(Comparator.comparingDouble(bot::distanceTo))
                    .orElse(null);
            if (hostile == null) {
                return fail("no_hostile_nearby: 附近没怪,不用逃");
            }
            Task task = new EvadeTask(new Threat(Threat.Type.HOSTILE, Threat.Severity.HIGH,
                    hostile, hostile.getBlockPos()));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: flee");
        });

        register("shelter_now", "就地用方块把自己围起来应急自保(半夜被围/血少快死时)。", objectSchema().build(), (bot, args) -> {
            Task task = new EmergencyShelterTask();
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("resupply", "回基地箱子补给:不带参数=补吃的;给 item=取那件工具。需要之前 set_base 过。", objectSchema()
                .property("item", stringSchema("要取的工具 id,如 minecraft:iron_pickaxe;省略=补食物"))
                .build(), (bot, args) -> {
            Item item = optionalItem(args, "item");
            Task task = item != null ? ResupplyTask.tool(item) : ResupplyTask.food();
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("irrigate", "在身前挖 2x2 无限水源池(浇地/取水点)。需背包 ≥2 桶水。", objectSchema().build(), (bot, args) -> {
            Task task = new io.github.zoyluo.aibot.task.IrrigateTask(
                    bot.getBlockPos().offset(bot.getHorizontalFacing(), 2).down());
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("raid_crops", "收割附近已成熟的庄稼(村庄的也收=偷菜,节目效果)。", objectSchema()
                .property("count", integerSchema("收几个,默认 8"))
                .build(), (bot, args) -> {
            Task task = new io.github.zoyluo.aibot.task.RaidCropsTask(optionalInt(args, "count", 8));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("create_obsidian", "水浇岩浆造黑曜石并挖走(去下界/做附魔台必备)。需水桶+钻石镐。", objectSchema()
                .property("count", integerSchema("造几块,默认 4"))
                .build(), (bot, args) -> {
            Task task = new CreateObsidianTask(optionalInt(args, "count", 4));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("ride", "走过去坐上附近的船/矿车/已驯服的马(坐船划船/骑马)。下来用 dismount。", objectSchema()
                .property("target", stringSchema("boat/minecart/horse;省略=最近的可骑之物"))
                .build(), (bot, args) -> {
            Task task = new RideTask(optionalString(args, "target", ""));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("dismount", "从船/矿车/马上下来。", objectSchema().build(), (bot, args) -> {
            if (!bot.hasVehicle()) {
                return fail("not_riding");
            }
            bot.stopRiding();
            return ok("dismounted");
        });

        register("shear_sheep", "剪羊毛并捡走(做床/旗帜的羊毛来源)。需剪刀 shears(2 铁锭 craft)。", objectSchema()
                .property("count", integerSchema("剪几只,默认 1"))
                .build(), (bot, args) -> {
            Task task = new ShearSheepTask(optionalInt(args, "count", 1));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("tame", "驯服宠物归自己:wolf 狼(要骨头)/cat 猫(生鳕鱼)/parrot 鹦鹉(种子)/horse 马(反复骑到服)。缺料会告诉你缺什么。", objectSchema()
                .property("animal", stringSchema("wolf/cat/parrot/horse"))
                .required("animal")
                .build(), (bot, args) -> {
            Task task = new TameTask(requiredString(args, "animal"));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("plant_sapling", "把背包里的树苗种到附近草地上(砍树后补种,环保人设;树苗=打树叶掉落)。", objectSchema()
                .property("count", integerSchema("种几棵,默认 4"))
                .build(), (bot, args) -> {
            Task task = new PlantSaplingTask(optionalInt(args, "count", 4));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("bone_meal", "对身边 4 格内的庄稼/树苗撒骨粉催熟(骨头 craft 成 bone_meal)。", objectSchema()
                .property("count", integerSchema("撒几次,默认 4"))
                .build(), (bot, args) -> {
            int want = Math.max(1, Math.min(16, optionalInt(args, "count", 4)));
            ServerWorld world = bot.getServerWorld();
            BlockPos feet = bot.getBlockPos();
            int applied = 0;
            for (int i = 0; i < want; i++) {
                if (InventoryAction.countItem(bot, Items.BONE_MEAL) <= 0) {
                    break;
                }
                var slot = InventoryAction.findItem(bot, Items.BONE_MEAL);
                if (slot.isEmpty()) {
                    break;
                }
                InventoryAction.equipFromSlot(bot, slot.getAsInt());
                BlockPos fertilizable = null;
                for (BlockPos pos : BlockPos.iterate(feet.add(-4, -1, -4), feet.add(4, 2, 4))) {
                    BlockState stateHere = world.getBlockState(pos);
                    if (stateHere.getBlock() instanceof CropBlock crop && !crop.isMature(stateHere)) {
                        fertilizable = pos.toImmutable();
                        break;
                    }
                    if (stateHere.getBlock() instanceof SaplingBlock) {
                        fertilizable = pos.toImmutable();
                        break;
                    }
                }
                if (fertilizable == null) {
                    break;
                }
                if (!InteractAction.useItemOnBlock(bot, fertilizable, Direction.UP, Hand.MAIN_HAND).isSuccess()) {
                    break;
                }
                applied++;
            }
            if (applied > 0) {
                return ok("bone_meal_applied: " + applied);
            }
            return fail(InventoryAction.countItem(bot, Items.BONE_MEAL) <= 0
                    ? "need_bone_meal: 没骨粉,骨头可 craft"
                    : "no_growable_crop_or_sapling_nearby");
        });

        register("use_bucket", "用桶:action=fill 从身边水源舀一桶水;action=pour 把水倒在身前(灭火/浇地/造水路)。", objectSchema()
                .property("action", stringSchema("fill(舀水) 或 pour(倒水)"))
                .required("action")
                .build(), (bot, args) -> {
            String action = requiredString(args, "action").toLowerCase(java.util.Locale.ROOT);
            ServerWorld world = bot.getServerWorld();
            if (action.startsWith("fill") || action.contains("舀")) {
                BlockPos feet = bot.getBlockPos();
                for (BlockPos pos : BlockPos.iterate(feet.add(-3, -2, -3), feet.add(3, 1, 3))) {
                    if (FarmAction.isWaterSource(world, pos)) {
                        return result(FarmAction.fillBucket(bot, pos.toImmutable()));
                    }
                }
                return fail("no_water_source_nearby");
            }
            if (action.startsWith("pour") || action.contains("倒")) {
                return result(FarmAction.placeWater(bot, bot.getBlockPos().offset(bot.getHorizontalFacing())));
            }
            return fail("unknown_action: fill|pour");
        });

        register("toggle_door", "开/关最近的门·活板门·栅栏门,或拉拉杆·按按钮(给坐标则操作那格)。铁门除外(要红石)。", objectSchema()
                .property("x", integerSchema("可选 目标 x"))
                .property("y", integerSchema("可选 目标 y"))
                .property("z", integerSchema("可选 目标 z"))
                .build(), (bot, args) -> {
            ServerWorld world = bot.getServerWorld();
            BlockPos targetPos = optionalBlockPos(args, "x", "y", "z");
            if (targetPos == null) {
                BlockPos feet = bot.getBlockPos();
                double best = Double.MAX_VALUE;
                for (BlockPos pos : BlockPos.iterate(feet.add(-4, -1, -4), feet.add(4, 2, 4))) {
                    if (!isToggleable(world.getBlockState(pos))) {
                        continue;
                    }
                    double dist = pos.getSquaredDistance(feet);
                    if (dist < best) {
                        best = dist;
                        targetPos = pos.toImmutable();
                    }
                }
            }
            if (targetPos == null) {
                return fail("no_door_or_switch_nearby");
            }
            if (!isToggleable(world.getBlockState(targetPos))) {
                return fail("not_toggleable: " + Registries.BLOCK.getId(world.getBlockState(targetPos).getBlock()));
            }
            return result(InteractAction.useItemOnBlock(bot, targetPos, Direction.UP, Hand.MAIN_HAND));
        });

        register("hold_item", "把指定物品拿在手上展示/备用(offhand=true 放副手,如盾牌/火把/图腾)。", objectSchema()
                .property("item", stringSchema("item id, 例如 minecraft:shield"))
                .property("offhand", booleanSchema("true=放副手"))
                .required("item")
                .build(), (bot, args) -> {
            Item item = requiredItem(args, "item");
            var slot = InventoryAction.findItem(bot, item);
            if (slot.isEmpty()) {
                return fail("not_in_inventory: " + Registries.ITEM.getId(item));
            }
            if (optionalBoolean(args, "offhand", false)) {
                var inventory = bot.getInventory();
                ItemStack moving = inventory.main.get(slot.getAsInt());
                ItemStack old = inventory.offHand.get(0);
                inventory.offHand.set(0, moving);
                inventory.main.set(slot.getAsInt(), old);
                inventory.markDirty();
                return ok("held_offhand: " + Registries.ITEM.getId(item));
            }
            int hotbar = InventoryAction.equipFromSlot(bot, slot.getAsInt());
            return hotbar >= 0 ? ok("held: " + Registries.ITEM.getId(item)) : fail("equip_failed");
        });

        register("use_item", "一次完成从背包选取并右键使用指定物品。用于喝药水/牛奶、吃指定食物、扔雪球/鸡蛋/末影珍珠等连续动作；不要先 hold_item 再 use_held_item。弓箭请用 shoot_bow，纯展示或把盾牌/图腾放副手才用 hold_item。", objectSchema()
                .property("item", stringSchema("要使用的物品 id，例如 minecraft:snowball"))
                .property("offhand", booleanSchema("true 时从副手使用；物品不在副手会自动换入"))
                .required("item")
                .build(), (bot, args) -> {
            Item item = requiredItem(args, "item");
            boolean offhand = optionalBoolean(args, "offhand", false);
            var inventory = bot.getInventory();
            Hand hand = offhand ? Hand.OFF_HAND : Hand.MAIN_HAND;
            if (offhand && !inventory.offHand.get(0).isOf(item)) {
                var slot = InventoryAction.findItem(bot, item);
                if (slot.isEmpty()) {
                    return fail("not_in_inventory: " + Registries.ITEM.getId(item));
                }
                ItemStack moving = inventory.main.get(slot.getAsInt());
                ItemStack old = inventory.offHand.get(0);
                inventory.offHand.set(0, moving);
                inventory.main.set(slot.getAsInt(), old);
                inventory.markDirty();
            } else if (!offhand) {
                var slot = InventoryAction.findItem(bot, item);
                if (slot.isEmpty()) {
                    return fail("not_in_inventory: " + Registries.ITEM.getId(item));
                }
                if (InventoryAction.equipFromSlot(bot, slot.getAsInt()) < 0) {
                    return fail("equip_failed");
                }
            }
            return result(InteractAction.useItemInAir(bot, hand));
        });

        register("use_held_item", "使用(右键)主手物品一次:扔雪球/末影珍珠/鸡蛋、喝药水/奶桶等。先 hold_item 拿好再用。", objectSchema().build(), (bot, args) ->
                result(InteractAction.useItemInAir(bot, Hand.MAIN_HAND)));

        register("world_info", "查世界信息:坐标/维度/群系/时间/天气/是否在地下。回答几点了/在哪/什么群系/下雨吗之前先调它。", objectSchema().build(), (bot, args) -> {
            ServerWorld world = bot.getServerWorld();
            BlockPos feet = bot.getBlockPos();
            long timeOfDay = world.getTimeOfDay() % 24000L;
            String phase = timeOfDay < 1000 ? "清晨" : timeOfDay < 6000 ? "上午" : timeOfDay < 9000 ? "中午"
                    : timeOfDay < 12000 ? "下午" : timeOfDay < 13000 ? "黄昏" : "夜晚";
            String weather = world.isThundering() ? "雷暴" : world.isRaining() ? "下雨" : "晴";
            int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
            String biome = world.getBiome(feet).getKey().map(key -> key.getValue().toString()).orElse("unknown");
            return ok("{\"pos\":\"" + feet.toShortString()
                    + "\",\"dimension\":\"" + world.getRegistryKey().getValue()
                    + "\",\"biome\":\"" + biome
                    + "\",\"time\":\"" + phase + "(" + timeOfDay + ")\",\"weather\":\"" + weather
                    + "\",\"underground\":" + (feet.getY() < topY - 3) + "}");
        });

        register("shoot_bow", "朝最近的某类实体拉满弓射 N 箭(远程点名/表演/骚扰,不保证射死)。要弓+箭;真要杀怪用 attack(它自己会切弓)。", objectSchema()
                .property("entity_type", stringSchema("目标实体, 例如 minecraft:zombie"))
                .property("count", integerSchema("射几箭,默认 1", 1, 5))
                .required("entity_type")
                .build(), (bot, args) -> {
            Task task = new io.github.zoyluo.aibot.task.ShootBowTask(
                    requiredString(args, "entity_type"), optionalInt(args, "count", 1));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("roll_dice", "掷骰子/抽签(直播互动小游戏):公屏播报 1~sides 的随机数。", objectSchema()
                .property("sides", integerSchema("几面骰,默认 6", 2, 1000))
                .build(), (bot, args) -> {
            int sides = Math.max(2, Math.min(1000, optionalInt(args, "sides", 6)));
            int rolled = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, sides + 1);
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", "掷骰子(1~" + sides + "):" + rolled + " 点!");
            return ok("rolled: " + rolled + " / " + sides);
        });

        // ================= §17.17 第二批真人工具链(25 个) =================

        register("get_marked_target", "读取主人用 Shift+中键设置的唯一空间标记,返回命中方块、命中面和推荐脚位坐标。主人说那里/那边/对面/那个位置/标记处时先用它;没有标记就让主人先 Shift+中键标一下,禁止猜成主人当前位置。", objectSchema()
                .build(), (bot, args) -> {
            var marker = io.github.zoyluo.aibot.marker.TargetMarkerService.INSTANCE.forBot(bot)
                    .orElseThrow(() -> new IllegalArgumentException("no_active_marker: 请主人先 Shift+中键标记目标"));
            String botDimension = bot.getServerWorld().getRegistryKey().getValue().toString();
            if (!botDimension.equals(marker.dimensionId())) {
                return fail("marker_in_other_dimension: " + marker.dimensionId());
            }
            double distance = Math.sqrt(marker.standPos().getSquaredDistance(bot.getBlockPos()));
            return ok("marked_target: dimension=" + marker.dimensionId()
                    + ", clicked=" + marker.clickedBlock().toShortString()
                    + ", face=" + marker.face().asString()
                    + ", stand=" + marker.standPos().toShortString()
                    + ", distance=" + String.format(java.util.Locale.ROOT, "%.1f", distance));
        });

        register("scaffold_walk", "显式修路工具:主人明确要求搭桥/修一条永久道路时使用。普通移动、跟随和追击已会自动跨缺口施工,不要为了帮助其他移动任务二次调用本工具。主人说标记处时传 use_marker=true。", objectSchema()
                .property("use_marker", booleanSchema("true=走向主人 Shift+中键标记的地点"))
                .property("player_name", stringSchema("走向谁(默认主人)"))
                .property("x", integerSchema("或:目标 x"))
                .property("z", integerSchema("或:目标 z"))
                .property("direction", stringSchema("或:固定方向 north/south/east/west"))
                .property("length", integerSchema("方向模式铺多远,默认 16", 1, 256))
                .property("max_blocks", integerSchema("最多消耗几块,省略时按距离自动计算", 8, 512))
                .build(), (bot, args) -> {
            boolean useMarker = optionalBoolean(args, "use_marker", false);
            boolean hasX = args.has("x");
            boolean hasZ = args.has("z");
            String playerName = optionalString(args, "player_name", "").trim();
            String directionName = optionalString(args, "direction", "").trim();
            int selectedModes = (useMarker ? 1 : 0) + ((hasX || hasZ) ? 1 : 0)
                    + (!playerName.isEmpty() ? 1 : 0) + (!directionName.isEmpty() ? 1 : 0);
            if (selectedModes > 1) {
                return fail("conflicting_target: use_marker/x+z/direction/player_name 只能选一种");
            }
            if (hasX != hasZ) {
                return fail("bad_target: x 和 z 必须同时提供");
            }

            Task task;
            if (useMarker) {
                var marker = io.github.zoyluo.aibot.marker.TargetMarkerService.INSTANCE.forBot(bot)
                        .orElseThrow(() -> new IllegalArgumentException("no_active_marker: 请主人先 Shift+中键标记目标"));
                String botDimension = bot.getServerWorld().getRegistryKey().getValue().toString();
                if (!botDimension.equals(marker.dimensionId())) {
                    return fail("marker_in_other_dimension: " + marker.dimensionId());
                }
                int budget = scaffoldBudget(bot, marker.standPos(), args);
                task = new ScaffoldWalkTask(marker.standPos().getX(), marker.standPos().getZ(), budget);
            } else if (!directionName.isEmpty()) {
                Direction direction = optionalDirection(args, "direction", Direction.NORTH);
                int length = Math.max(1, Math.min(256, optionalInt(args, "length", 16)));
                BlockPos target = bot.getBlockPos().offset(direction, length);
                int budget = args.has("max_blocks") ? optionalInt(args, "max_blocks", length + 8) : length + 8;
                task = new ScaffoldWalkTask(target.getX(), target.getZ(), budget);
            } else if (hasX) {
                BlockPos target = new BlockPos(requiredInt(args, "x"), bot.getBlockY(), requiredInt(args, "z"));
                task = new ScaffoldWalkTask(target.getX(), target.getZ(), scaffoldBudget(bot, target, args));
            } else {
                Optional<ServerPlayerEntity> target = targetPlayer(bot, playerName);
                if (target.isEmpty()) {
                    return fail("player_not_found");
                }
                int budget = scaffoldBudget(bot, target.get().getBlockPos(), args);
                task = new ScaffoldWalkTask(target.get().getGameProfile().getName(), target.get().getBlockPos(), budget);
            }
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("throw_at", "扔雪球/鸡蛋砸人或砸怪(纯整活不疼):player_name 砸玩家(默认主人),或 entity_type 砸最近的那类怪。要背包有雪球/鸡蛋;扔珍珠须显式 item=minecraft:ender_pearl(会把自己传送过去)。", objectSchema()
                .property("player_name", stringSchema("砸谁(玩家名,默认主人)"))
                .property("entity_type", stringSchema("或:砸最近的某类实体, 例如 minecraft:zombie"))
                .property("item", stringSchema("投掷物 id(默认自动选雪球/鸡蛋)"))
                .property("count", integerSchema("扔几个,默认 3", 1, 16))
                .build(), (bot, args) -> {
            String entityType = optionalString(args, "entity_type", "").trim();
            String explicitPlayer = optionalString(args, "player_name", "").trim();
            // 语义:显式给了玩家名→砸他;只给实体→砸实体;啥都没给→砸主人。
            String playerName = !explicitPlayer.isEmpty() ? explicitPlayer : (entityType.isEmpty() ? "" : null);
            Task task = new ThrowAtTask(playerName, entityType, optionalItem(args, "item"), optionalInt(args, "count", 3));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("build_wall", "在面前沿指定方向砌一道直墙(挡怪/围地/圈羊):length 列 × height 高。需背包有方块(木板/圆石/泥土)。", objectSchema()
                .property("direction", stringSchema("north/south/east/west,默认面朝方向"))
                .property("length", integerSchema("长度(列数),默认 5", 1, 16))
                .property("height", integerSchema("高度,默认 2", 1, 3))
                .build(), (bot, args) -> {
            Direction dir = optionalDirection(args, "direction", bot.getHorizontalFacing());
            Task task = new BuildWallTask(dir, optionalInt(args, "length", 5), optionalInt(args, "height", 2));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("patrol", "绕当前位置巡逻几圈(守家/看场子/来回走位巡视)。", objectSchema()
                .property("radius", integerSchema("巡逻半径,默认 8", 4, 16))
                .property("laps", integerSchema("圈数,默认 3", 1, 10))
                .build(), (bot, args) -> {
            Task task = new PatrolTask(optionalInt(args, "radius", 8), optionalInt(args, "laps", 3));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("build_golem", "造傀儡守家:type=iron 铁傀儡(4 铁块+雕刻南瓜,会帮忙打怪)或 snow 雪傀儡(2 雪块+雕刻南瓜)。材料不够会报缺什么。", objectSchema()
                .property("type", stringSchema("iron 或 snow,默认 iron"))
                .build(), (bot, args) -> {
            boolean iron = !"snow".equalsIgnoreCase(optionalString(args, "type", "iron"));
            Task task = new BuildGolemTask(iron);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("flatten_area", "推平脚下场地:把周围半径 N 格、高出地面 3 格内的方块全挖掉(盖房前平地/拆土包),不往下挖坑。", objectSchema()
                .property("radius", integerSchema("半径,默认 2(=5x5)", 1, 4))
                .build(), (bot, args) -> {
            Task task = new FlattenAreaTask(optionalInt(args, "radius", 2));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("place_boat", "把背包里的船放到旁边水面并坐上去(过湖/划船)。没船先 craft minecraft:oak_boat(5 木板);下船用 dismount。", objectSchema().build(), (bot, args) -> {
            Task task = new PlaceBoatTask();
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("sneak", "潜行开/关(on=true 蹲下:防摔下边缘/卖萌打招呼;false 站起)。", objectSchema()
                .property("on", booleanSchema("true=蹲下, false=站起,默认 true"))
                .build(), (bot, args) ->
                result(MovementAction.setSneaking(bot, optionalBoolean(args, "on", true))));

        register("sprint", "疾跑开/关(赶路快 30%,费饱食度;走路移动时生效)。", objectSchema()
                .property("on", booleanSchema("true=开跑, false=停,默认 true"))
                .build(), (bot, args) -> {
            boolean on = optionalBoolean(args, "on", true);
            bot.getActionPack().setSprinting(on);
            return ok(on ? "sprinting" : "sprint_off");
        });

        register("face", "转头面向:player_name 看那个玩家(默认主人=看镜头),或 entity_type 看最近的那类实体。纯转头不移动。", objectSchema()
                .property("player_name", stringSchema("看谁(默认主人)"))
                .property("entity_type", stringSchema("或:看最近的某类实体, 例如 minecraft:cow"))
                .build(), (bot, args) -> {
            String entityType = optionalString(args, "entity_type", "").replace("minecraft:", "").trim();
            if (!entityType.isEmpty()) {
                Entity target = bot.getServerWorld()
                        .getOtherEntities(bot, bot.getBoundingBox().expand(24.0D),
                                e -> e.isAlive() && Registries.ENTITY_TYPE.getId(e.getType()).getPath().equals(entityType))
                        .stream().min(Comparator.comparingDouble(bot::distanceTo)).orElse(null);
                if (target == null) {
                    return fail("entity_not_found: " + entityType);
                }
                LookAction.lookAt(bot, target.getEyePos());
                return ok("facing: " + entityType);
            }
            Optional<ServerPlayerEntity> player = targetPlayer(bot, optionalString(args, "player_name", ""));
            if (player.isEmpty()) {
                return fail("player_not_found");
            }
            LookAction.lookAt(bot, player.get().getEyePos());
            return ok("facing: " + player.get().getGameProfile().getName());
        });

        register("compact_inventory", "整理背包腾格子:把铁/金/铜/钻石/绿宝石/红石/煤/青金石(锭·宝石·原矿)每 9 个压成 1 块,直接在背包完成。", objectSchema().build(), (bot, args) -> {
            Item[][] pairs = {
                    {Items.IRON_INGOT, Items.IRON_BLOCK}, {Items.GOLD_INGOT, Items.GOLD_BLOCK},
                    {Items.COPPER_INGOT, Items.COPPER_BLOCK}, {Items.DIAMOND, Items.DIAMOND_BLOCK},
                    {Items.EMERALD, Items.EMERALD_BLOCK}, {Items.REDSTONE, Items.REDSTONE_BLOCK},
                    {Items.COAL, Items.COAL_BLOCK}, {Items.LAPIS_LAZULI, Items.LAPIS_BLOCK},
                    {Items.RAW_IRON, Items.RAW_IRON_BLOCK}, {Items.RAW_GOLD, Items.RAW_GOLD_BLOCK},
                    {Items.RAW_COPPER, Items.RAW_COPPER_BLOCK}};
            StringBuilder done = new StringBuilder();
            for (Item[] pair : pairs) {
                int crafts = InventoryAction.countItem(bot, pair[0]) / 9;
                if (crafts <= 0 || !InventoryAction.removeItems(bot, pair[0], crafts * 9)) {
                    continue;
                }
                // 分堆给付(单堆 ≤maxCount,防超堆叠存档崩);背包满则落脚下,绝不凭空消失。
                int remaining = crafts;
                int max = Math.max(1, new ItemStack(pair[1]).getMaxCount());
                while (remaining > 0) {
                    int chunk = Math.min(remaining, max);
                    ItemStack out = new ItemStack(pair[1], chunk);
                    if (InventoryAction.giveItem(bot, out).isFailed() && !out.isEmpty()) {
                        bot.dropItem(out, false, true);
                    }
                    remaining -= chunk;
                }
                done.append(Registries.ITEM.getId(pair[1]).getPath()).append(" x").append(crafts).append("; ");
            }
            return done.isEmpty() ? ok("nothing_to_compact: 没有攒够 9 个的可压缩材料") : ok("compacted: " + done);
        });

        register("pet_command", "指挥自己驯的宠物(狼/猫/鹦鹉):action=sit 全部坐下原地待命 / stand 全部起来跟着走。", objectSchema()
                .property("action", stringSchema("sit 或 stand"))
                .required("action")
                .build(), (bot, args) -> {
            boolean sit = !"stand".equalsIgnoreCase(requiredString(args, "action"));
            var pets = bot.getServerWorld().getEntitiesByClass(TameableEntity.class,
                    bot.getBoundingBox().expand(16.0D), pet -> pet.isAlive() && pet.isTamed() && pet.isOwner(bot));
            if (pets.isEmpty()) {
                return fail("no_pets_nearby: 附近没有自己驯的宠物(先 tame)");
            }
            int changed = 0;
            for (TameableEntity pet : pets) {
                if (pet.isSitting() != sit) {
                    pet.setSitting(sit);
                    pet.setInSittingPose(sit);
                    changed++;
                }
            }
            bot.swingHand(Hand.MAIN_HAND);
            return ok((sit ? "pets_sitting: " : "pets_standing: ") + changed + "/" + pets.size());
        });

        register("feed_pet", "喂受伤的自家宠物回血:狼喂肉(生熟牛猪鸡羊/腐肉),猫喂生鳕鱼/生鲑鱼。喂最近一只受伤的。", objectSchema().build(), (bot, args) -> {
            var pets = bot.getServerWorld().getEntitiesByClass(TameableEntity.class,
                    bot.getBoundingBox().expand(16.0D),
                    pet -> pet.isAlive() && pet.isTamed() && pet.isOwner(bot) && pet.getHealth() < pet.getMaxHealth());
            if (pets.isEmpty()) {
                return fail("no_injured_pets: 附近没有受伤的自家宠物");
            }
            TameableEntity pet = pets.stream().min(Comparator.comparingDouble(bot::distanceTo)).orElseThrow();
            if (bot.distanceTo(pet) > 4.0F) {
                return fail("pet_too_far: 先 move_to " + pet.getBlockPos().toShortString());
            }
            String type = Registries.ENTITY_TYPE.getId(pet.getType()).getPath();
            Item[] menu = "cat".equals(type) || "ocelot".equals(type)
                    ? new Item[]{Items.COD, Items.SALMON}
                    : new Item[]{Items.COOKED_BEEF, Items.BEEF, Items.COOKED_PORKCHOP, Items.PORKCHOP,
                            Items.COOKED_CHICKEN, Items.CHICKEN, Items.COOKED_MUTTON, Items.MUTTON, Items.ROTTEN_FLESH};
            for (Item food : menu) {
                var slot = InventoryAction.findItem(bot, food);
                if (slot.isEmpty()) {
                    continue;
                }
                InventoryAction.equipFromSlot(bot, slot.getAsInt());
                LookAction.lookAt(bot, pet.getEyePos());
                if (InteractAction.useItemOnEntity(bot, pet, Hand.MAIN_HAND).isSuccess()) {
                    return ok(String.format(java.util.Locale.ROOT, "fed_%s: hp=%.0f/%.0f",
                            type, pet.getHealth(), pet.getMaxHealth()));
                }
            }
            return fail("no_pet_food: 狼要肉/猫要生鱼,背包里没有");
        });

        register("gear_check", "查装备耐久:主手/副手/盔甲各剩百分之几(回答\"镐还能用多久/装备什么情况\",快坏提前换)。", objectSchema().build(), (bot, args) -> {
            StringBuilder sb = new StringBuilder("{");
            appendGear(sb, "main_hand", bot.getMainHandStack());
            appendGear(sb, "off_hand", bot.getOffHandStack());
            String[] armorNames = {"boots", "leggings", "chestplate", "helmet"};
            var armor = bot.getInventory().armor;
            for (int i = 0; i < armor.size() && i < 4; i++) {
                appendGear(sb, armorNames[i], armor.get(i));
            }
            if (sb.length() > 1 && sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
            sb.append("}");
            return ok(sb.length() <= 2 ? "{\"empty\":\"手上盔甲全空\"}" : sb.toString());
        });

        register("find_block", "找最近的某种方块并报坐标(附近哪有钻石矿/箱子/岩浆):按 id 或关键词匹配,报最近一处的坐标和距离。想去就接 move_to。", objectSchema()
                .property("block", stringSchema("方块 id 或关键词, 例如 diamond_ore / chest / lava"))
                .property("radius", integerSchema("搜索半径,默认 16", 4, 24))
                .required("block")
                .build(), (bot, args) -> {
            String query = requiredString(args, "block").replace("minecraft:", "").trim().toLowerCase(java.util.Locale.ROOT);
            int radius = Math.max(4, Math.min(24, optionalInt(args, "radius", 16)));
            BlockPos feet = bot.getBlockPos();
            // OreProspector 有 section 级 hasAny 快速跳过,大半径不卡主线程;绝不手写 49^3 暴力遍历。
            BlockPos found = OreProspector.nearest(bot.getServerWorld(), feet, radius,
                    state -> !state.isAir() && Registries.BLOCK.getId(state.getBlock()).getPath().contains(query));
            if (found == null) {
                return fail("not_found: " + query + "(半径 " + radius + " 内没有)");
            }
            return ok("found " + query + ": " + found.toShortString()
                    + " 距" + (int) Math.sqrt(found.getSquaredDistance(feet)) + "格");
        });

        register("find_entity", "找最近的某种实体并报坐标(附近有没有村民/牛/僵尸):关键词匹配,报最近 3 个。", objectSchema()
                .property("entity", stringSchema("实体 id 或关键词, 例如 villager / cow"))
                .property("radius", integerSchema("搜索半径,默认 24", 8, 48))
                .required("entity")
                .build(), (bot, args) -> {
            String query = requiredString(args, "entity").replace("minecraft:", "").trim().toLowerCase(java.util.Locale.ROOT);
            int radius = Math.max(8, Math.min(48, optionalInt(args, "radius", 24)));
            var matches = bot.getServerWorld().getOtherEntities(bot, bot.getBoundingBox().expand(radius),
                    e -> e.isAlive() && Registries.ENTITY_TYPE.getId(e.getType()).getPath().contains(query));
            if (matches.isEmpty()) {
                return fail("not_found: " + query + "(半径 " + radius + " 内没有)");
            }
            matches.sort(Comparator.comparingDouble(bot::distanceTo));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, matches.size()); i++) {
                Entity e = matches.get(i);
                sb.append(Registries.ENTITY_TYPE.getId(e.getType()).getPath())
                        .append("@").append(e.getBlockPos().toShortString())
                        .append(" 距").append((int) bot.distanceTo(e)).append("格; ");
            }
            return ok("found: " + sb);
        });

        register("firework", "放烟花庆祝(想庆祝就放,不用等礼物):scale 1~4 越大越隆重(4=八连环绕+原地转圈)。", objectSchema()
                .property("scale", integerSchema("规模 1~4,默认 3", 1, 4))
                .build(), (bot, args) -> {
            int scale = Math.max(1, Math.min(4, optionalInt(args, "scale", 3)));
            // 第 4 档会 pauseFor+强制转圈 3s:濒危/着火/怪贴脸时降到 3,防转圈覆盖逃生任务的朝向。
            if (scale == 4 && (bot.getHealth() < 10.0F || bot.isOnFire()
                    || !bot.getServerWorld().getEntitiesByClass(HostileEntity.class,
                            bot.getBoundingBox().expand(8.0D), e -> e.isAlive()).isEmpty())) {
                scale = 3;
            }
            GiftCelebrator.INSTANCE.celebrate(bot, scale,
                    new GiftDispatcher.GiftEvent("自嗨", "烟花", 1, bot.getGameProfile().getName()));
            return ok("fireworks: scale=" + scale);
        });

        register("ring_bell", "敲响附近的钟(村庄集合/报时整活)。8 格内要有钟,太远会报坐标让你先过去。", objectSchema().build(), (bot, args) -> {
            ServerWorld world = bot.getServerWorld();
            BlockPos feet = bot.getBlockPos();
            BlockPos bell = null;
            double bestDist = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.iterate(feet.add(-8, -3, -8), feet.add(8, 3, 8))) {
                if (!Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).getPath().equals("bell")) {
                    continue;
                }
                double dist = pos.getSquaredDistance(feet);
                if (dist < bestDist) {
                    bestDist = dist;
                    bell = pos.toImmutable();
                }
            }
            if (bell == null) {
                return fail("no_bell_nearby: 8 格内没有钟");
            }
            if (bot.getEyePos().distanceTo(bell.toCenterPos()) > 4.5D) {
                return fail("bell_too_far: 先 move_to " + bell.toShortString());
            }
            Direction face = Direction.getFacing(bot.getX() - bell.getX(), 0.0D, bot.getZ() - bell.getZ());
            return result(InteractAction.useItemOnBlock(bot, bell, face, Hand.MAIN_HAND));
        });

        register("extinguish_fire", "灭火:扑灭自己身上的火 + 打掉周围 6 格内的火焰方块(着火了/房子着火/雷劈起火)。", objectSchema().build(), (bot, args) -> {
            bot.setFireTicks(0);
            ServerWorld world = bot.getServerWorld();
            BlockPos feet = bot.getBlockPos();
            int put = 0;
            for (BlockPos pos : BlockPos.iterate(feet.add(-6, -2, -6), feet.add(6, 3, 6))) {
                if (world.getBlockState(pos).isIn(BlockTags.FIRE)) {
                    world.breakBlock(pos.toImmutable(), false, bot);
                    put++;
                }
            }
            return ok("extinguished: 自己身上 + " + put + " 处火焰");
        });

        register("collect_lava", "用空桶舀岩浆(顶级燃料一桶炼 100 个/造黑曜石原料):4.5 格内要有岩浆源;只伸手舀,不会走进岩浆。", objectSchema().build(), (bot, args) -> {
            if (InventoryAction.countItem(bot, Items.BUCKET) < 1) {
                return fail("missing_bucket: 先 craft minecraft:bucket(3 铁锭)");
            }
            ServerWorld world = bot.getServerWorld();
            BlockPos feet = bot.getBlockPos();
            BlockPos lava = null;
            double bestDist = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.iterate(feet.add(-4, -2, -4), feet.add(4, 2, 4))) {
                var fluid = world.getFluidState(pos);
                if (!fluid.isIn(FluidTags.LAVA) || !fluid.isStill()) {
                    continue;
                }
                double dist = pos.getSquaredDistance(feet);
                if (dist < bestDist) {
                    bestDist = dist;
                    lava = pos.toImmutable();
                }
            }
            if (lava == null) {
                return fail("no_lava_source_nearby: 附近没有静止岩浆源");
            }
            if (bot.getEyePos().distanceTo(lava.toCenterPos()) > 4.5D) {
                return fail("lava_too_far: 岩浆在 " + lava.toShortString() + ",小心走近点(别掉进去)");
            }
            if (!InventoryAction.removeItems(bot, Items.BUCKET, 1)) {
                return fail("missing_bucket");
            }
            world.setBlockState(lava, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            ItemStack filled = new ItemStack(Items.LAVA_BUCKET, 1);
            if (InventoryAction.giveItem(bot, filled).isFailed() && !filled.isEmpty()) {
                bot.dropItem(filled, false, true); // 背包满:岩浆桶落脚下,绝不凭空消失
            }
            bot.swingHand(Hand.MAIN_HAND);
            return ok("lava_bucket_filled: " + lava.toShortString());
        });

        register("list_places", "列出记过的所有地点(mark_place 存的家/矿点/农场),含坐标和维度。", objectSchema().build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            var places = BotMemoryStore.INSTANCE.of(bot.getUuid()).placesView();
            if (places.isEmpty()) {
                return ok("no_places: 还没记过地点(mark_place 可以记)");
            }
            StringBuilder sb = new StringBuilder();
            places.forEach((name, place) -> sb.append(name).append(" = ").append(place.pos().toShortString())
                    .append("(").append(place.dimension().replace("minecraft:", "")).append("); "));
            return ok(sb.toString());
        });

        register("drop_junk", "清背包垃圾腾格子:把多余的圆石/泥土/沙/沙砾/花岗岩闪长岩安山岩/凝灰岩扔地上,每样最多留 keep_each 个。", objectSchema()
                .property("keep_each", integerSchema("每样保留几个,默认 32", 0, 320))
                .build(), (bot, args) -> {
            boolean dropped = InventoryAction.dropJunk(bot, Math.max(0, optionalInt(args, "keep_each", 32)));
            return ok(dropped ? "junk_dropped" : "no_junk: 没有超量的垃圾方块");
        });

        register("swap_hands", "主手副手物品互换(像按 F 键):把火把/食物换去副手备用,或把副手的东西拿回主手。", objectSchema().build(), (bot, args) -> {
            var inventory = bot.getInventory();
            ItemStack main = inventory.main.get(inventory.selectedSlot);
            ItemStack off = inventory.offHand.get(0);
            inventory.main.set(inventory.selectedSlot, off);
            inventory.offHand.set(0, main);
            inventory.markDirty();
            return ok("swapped: main=" + describeStack(off) + " off=" + describeStack(main));
        });

        register("unstuck", "卡住自救:停掉一切动作,跳一下并朝随机方向挪两三格(被卡在缝里/原地转圈打摆子时用)。", objectSchema().build(), (bot, args) -> {
            bot.getActionPack().stopAll();
            bot.getActionPack().jumpOnce();
            double angle = java.util.concurrent.ThreadLocalRandom.current().nextDouble(Math.PI * 2.0D);
            double dist = 2.0D + java.util.concurrent.ThreadLocalRandom.current().nextDouble(1.5D);
            bot.getActionPack().startWalkTo(bot.getPos().add(Math.cos(angle) * dist, 0.0D, Math.sin(angle) * dist));
            return ok("nudged: 已随机挪位");
        });

        register("make_path", "用铲子把脚下周围的草地/泥土铲成小路方块(修门前路/院子装饰)。要背包有任意铲子。", objectSchema()
                .property("radius", integerSchema("半径,默认 1(=3x3)", 1, 3))
                .build(), (bot, args) -> {
            int radius = Math.max(1, Math.min(3, optionalInt(args, "radius", 1)));
            int shovel = -1;
            var main = bot.getInventory().main;
            for (int i = 0; i < main.size(); i++) {
                ItemStack stack = main.get(i);
                if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).getPath().endsWith("_shovel")) {
                    shovel = i;
                    break;
                }
            }
            if (shovel < 0) {
                return fail("no_shovel: 先 craft minecraft:wooden_shovel");
            }
            InventoryAction.equipFromSlot(bot, shovel);
            ServerWorld world = bot.getServerWorld();
            BlockPos under = bot.getBlockPos().down();
            int converted = 0;
            for (BlockPos pos : BlockPos.iterate(under.add(-radius, 0, -radius), under.add(radius, 0, radius))) {
                Block block = world.getBlockState(pos).getBlock();
                boolean pathable = block == Blocks.GRASS_BLOCK || block == Blocks.DIRT
                        || block == Blocks.PODZOL || block == Blocks.COARSE_DIRT || block == Blocks.MYCELIUM;
                if (!pathable || !world.getBlockState(pos.up()).isReplaceable()) {
                    continue;
                }
                if (InteractAction.useItemOnBlock(bot, pos.toImmutable(), Direction.UP, Hand.MAIN_HAND).isSuccess()) {
                    converted++;
                }
            }
            return converted > 0 ? ok("path_made: " + converted + " 格") : fail("no_convertible_ground: 脚下周围没有草地/泥土");
        });

        register("follow", "持续跟随玩家并保持约 2-4 格距离。内置智能导航,会自动绕路、挖开普通遮挡、垫高和跨缺口铺路,无需二次调用移动/垫高/铺路工具。只用于一直跟着/持续跟随,一次性过来请用 come_here。", objectSchema()
                .property("player_name", stringSchema("optional player name; defaults to owner"))
                .build(), (bot, args) -> {
            String playerName = optionalString(args, "player_name", "");
            LongRunningIntentManager.INSTANCE.setFollow(bot, playerName);
            Task task = new FollowTask(playerName);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("long_intent_assigned: follow");
        });

        register("hold", "Hold the current position until another task is assigned. DangerWatcher can still interrupt for survival threats.", objectSchema().build(), (bot, args) -> {
            Task task = new HoldTask();
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("guard", "护卫/保护主人的唯一正确工具(用于 保护我/守着我/别让怪碰我/在我身边打怪). Guard a named player (pass player_name), a coordinate, or the current point. The bot STAYS next to the guard target and only fights hostiles that come close, then returns — it will NEVER chase a mob off across the map. For 保护我 pass player_name = the owner. Prefer this over attack whenever the human wants protection rather than hunting a specific mob far away.", objectSchema()
                .property("player_name", stringSchema("optional player name to guard"))
                .property("x", integerSchema("optional guard x"))
                .property("y", integerSchema("optional guard y"))
                .property("z", integerSchema("optional guard z"))
                .build(), (bot, args) -> {
            String playerName = optionalString(args, "player_name", "");
            BlockPos point = optionalBlockPos(args, "x", "y", "z");
            Task task = playerName.isBlank()
                    ? GuardTask.point(point == null ? bot.getBlockPos() : point)
                    : GuardTask.player(playerName);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("farm", "Till soil, plant crops, harvest mature crops, and optionally keep tending the area. Supported crops: wheat, carrot, potato.", objectSchema()
                .property("x", integerSchema("area center x"))
                .property("y", integerSchema("area center y"))
                .property("z", integerSchema("area center z"))
                .property("radius", integerSchema("area radius"))
                .property("crop", stringSchema("wheat, carrot, or potato"))
                .property("keep_tending", booleanSchema("keep surveying instead of completing after one pass"))
                .required("x")
                .required("y")
                .required("z")
                .required("crop")
                .build(), (bot, args) -> {
            FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop"));
            Task task = new FarmTask(blockPos(args), optionalInt(args, "radius", 3), spec.seed(), spec.crop(),
                    optionalBoolean(args, "keep_tending", false), false);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("harvest", "Harvest mature crops in an area without tilling or planting new empty soil. Supported crops: wheat, carrot, potato.", objectSchema()
                .property("x", integerSchema("area center x"))
                .property("y", integerSchema("area center y"))
                .property("z", integerSchema("area center z"))
                .property("radius", integerSchema("area radius"))
                .property("crop", stringSchema("wheat, carrot, or potato"))
                .required("x")
                .required("y")
                .required("z")
                .required("crop")
                .build(), (bot, args) -> {
            FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(args, "crop"));
            Task task = new FarmTask(blockPos(args), optionalInt(args, "radius", 3), spec.seed(), spec.crop(), false, true);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("breed", "Feed two nearby adult animals of the requested type to breed them. Supported examples: minecraft:cow, minecraft:sheep, minecraft:pig, minecraft:chicken.", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:cow"))
                .property("pairs", integerSchema("number of pairs to breed"))
                .required("entity_type")
                .build(), (bot, args) -> {
            Task task = new BreedTask(requiredEntityType(args, "entity_type"), optionalInt(args, "pairs", 1));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("attack_entity", "ONE manual sword swing at the nearest entity of a type (single hit, does NOT kill). Only for a one-off poke/provocation (拍一下/敲一下/挑衅). NEVER use for kill requests — for 杀/击杀 use the attack tool which fights to the kill and completes.", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:cow"))
                .required("entity_type")
                .build(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            String entityType = requiredString(args, "entity_type");
            Identifier id = Identifier.of(entityType);
            Optional<Entity> target = bot.getServerWorld()
                    .getOtherEntities(bot, bot.getBoundingBox().expand(4.5D), entity -> Registries.ENTITY_TYPE.getId(entity.getType()).equals(id))
                    .stream()
                    .min(Comparator.comparingDouble(bot::distanceTo));
            if (target.isEmpty()) {
                return fail("no_nearby_entity: " + entityType);
            }
            return result(InteractAction.attackEntity(bot, target.get()));
        });

        register("stop", "Stop all ongoing goals, tasks, and movement immediately, then return to idle", objectSchema().build(), (bot, args) -> {
            LongRunningIntentManager.INSTANCE.clear(bot);
            GoalExecutor.INSTANCE.clear(bot);
            TaskManager.INSTANCE.resetToIdle(bot);
            MovementAction.stopAll(bot);
            return ok("stopped");
        });

        register("post_job", "Post a shared job to the multi-bot task board. Idle bots whose role matches the job role can claim and execute it.", objectSchema()
                .property("kind", stringSchema("job kind, for example mine, build, craft, smelt, move, eat, or light_area"))
                .property("role", stringSchema("bot role that should claim it, for example miner or builder; blank means any role"))
                .property("params", objectSchema().build())
                .required("kind")
                .required("params")
                .build(), ToolDefinition.Group.COORDINATION, (bot, args) -> {
            UUID id = TaskBoard.INSTANCE.post(requiredString(args, "kind"), paramsObject(args, "params"), optionalString(args, "role", ""));
            return ok("job_posted: " + id);
        });

        register("list_jobs", "List shared jobs on the multi-bot task board", objectSchema().build(), ToolDefinition.Group.COORDINATION, (bot, args) -> {
            List<Job> jobs = TaskBoard.INSTANCE.snapshot();
            if (jobs.isEmpty()) {
                return ok("[]");
            }
            StringBuilder builder = new StringBuilder("[");
            for (int index = 0; index < jobs.size(); index++) {
                Job job = jobs.get(index);
                if (index > 0) {
                    builder.append(", ");
                }
                builder.append("{id=").append(job.id())
                        .append(", kind=").append(job.kind())
                        .append(", role=").append(job.role())
                        .append(", status=").append(job.status())
                        .append(", reason=").append(job.failureReason())
                        .append("}");
            }
            builder.append("]");
            return ok(builder.toString());
        });

        register("tell_bot", "Send a message from this bot to another bot's brain, reusing the normal @bot chat pathway.", objectSchema()
                .property("target", stringSchema("target bot name"))
                .property("message", stringSchema("message text"))
                .required("target")
                .required("message")
                .build(), ToolDefinition.Group.COORDINATION, (bot, args) -> {
            String targetName = requiredString(args, "target");
            var target = AIPlayerManager.INSTANCE.getByName(targetName);
            if (target.isEmpty()) {
                return fail("no_such_bot: " + targetName);
            }
            boolean queued = BrainCoordinator.INSTANCE.handleMessage(target.get(), bot.getGameProfile().getName(), requiredString(args, "message"));
            return queued ? ok("message_sent") : fail("target_busy");
        });

        register("remember", "Store a persistent per-bot fact by key. Use for user preferences, named facts, or long-lived notes.", objectSchema()
                .property("key", stringSchema("memory key"))
                .property("value", stringSchema("memory value"))
                .required("key")
                .required("value")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            BotMemoryStore.INSTANCE.of(bot.getUuid()).remember(requiredString(args, "key"), requiredString(args, "value"));
            return ok("remembered");
        });

        register("recall", "Recall a persistent fact by key", objectSchema()
                .property("key", stringSchema("memory key"))
                .required("key")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> BotMemoryStore.INSTANCE.of(bot.getUuid())
                .recall(requiredString(args, "key"))
                .map(ToolRegistry::ok)
                .orElseGet(() -> fail("missing_memory: " + requiredString(args, "key"))));

        register("forget", "Delete a persistent fact by key", objectSchema()
                .property("key", stringSchema("memory key"))
                .required("key")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            boolean removed = BotMemoryStore.INSTANCE.of(bot.getUuid()).forget(requiredString(args, "key"));
            return ok("forgotten: " + removed);
        });

        register("mark_place", "Remember the bot's current block position as a named place", objectSchema()
                .property("name", stringSchema("place name, for example home"))
                .required("name")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            BotMemoryStore.INSTANCE.of(bot.getUuid()).markPlace(requiredString(args, "name"), bot.getServerWorld(), bot.getBlockPos());
            return ok("marked_place: " + requiredString(args, "name") + " at " + bot.getBlockPos().toShortString());
        });

        register("goto_place", "Assign a move task to a remembered named place in the current dimension", objectSchema()
                .property("name", stringSchema("place name"))
                .required("name")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            Optional<BotMemory.Place> place = BotMemoryStore.INSTANCE.of(bot.getUuid()).place(requiredString(args, "name"));
            if (place.isEmpty()) {
                return fail("unknown_place: " + requiredString(args, "name"));
            }
            if (!bot.getServerWorld().getRegistryKey().getValue().toString().equals(place.get().dimension())) {
                return fail("place_in_other_dimension: " + place.get().dimension());
            }
            Task task = new MoveTask(bot, place.get().pos());
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("resume_mining", "Continue mining where the last mining session left off: walks back to the remembered mine face and mines the same ore kinds. Use when the player says things like '继续挖矿'/'接着挖'.", objectSchema()
                .property("count", integerSchema("how many more ore blocks to mine, default 8"))
                .build(), (bot, args) -> {
            var mem = BotMemoryStore.INSTANCE.of(bot.getUuid());
            var face = mem.place("mine_face");
            if (face.isEmpty()) {
                return fail("no_mine_face: 没有上次挖矿的作业面记录");
            }
            if (!bot.getServerWorld().getRegistryKey().getValue().toString().equals(face.get().dimension())) {
                return fail("mine_face_in_other_dimension");
            }
            java.util.Set<net.minecraft.block.Block> ores = new java.util.HashSet<>();
            mem.recall("mine_face_ores").ifPresent(csv -> {
                for (String id : csv.split(",")) {
                    var block = net.minecraft.registry.Registries.BLOCK
                            .get(net.minecraft.util.Identifier.of(id.trim()));
                    if (block != net.minecraft.block.Blocks.AIR) {
                        ores.add(block);
                    }
                }
            });
            // 队列接力:先走回作业面,再原矿种续挖(goal 队列自动衔接,中途打断也能再续)。
            Task back = new MoveTask(bot, face.get().pos());
            TaskManager.INSTANCE.assign(bot, back);
            GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(
                    ores.isEmpty() ? java.util.Set.of(net.minecraft.block.Blocks.IRON_ORE) : ores,
                    optionalInt(args, "count", 8)));
            return ok("resuming at " + face.get().pos().toShortString());
        });

        register("mine_and_stockpile", "Mine ores then deposit the yield into a chest near the remembered base. Use when the player wants mined goods stored, not carried.", objectSchema()
                .property("ore", stringSchema("ore block id or raw item, e.g. minecraft:iron_ore"))
                .property("count", integerSchema("how many ore blocks to mine"))
                .required("ore")
                .build(), (bot, args) -> {
            var ores = oreTargetsFrom(requiredString(args, "ore"));
            int count = optionalInt(args, "count", 1);
            boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(ores, count));
            if (!started) {
                return fail("goal_plan_failed");
            }
            // 归仓接力:goal 队列自动衔接(挖完即去基地箱入库;无 base 时 Stockpile 自己报 no_base)
            Item yield = io.github.zoyluo.aibot.action.HarvestCore.expectedDropsFor(ores)
                    .stream().findFirst().orElse(null);
            if (yield != null) {
                GoalExecutor.INSTANCE.submit(bot, new Goal.Stockpile(yield, count));
            }
            return ok("goal_assigned: mine_ore + stockpile queued");
        });

        register("recover_drops", "Run back to the most recent death location and pick up dropped items before they despawn (5 min)", objectSchema()
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            var deaths = io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE
                    .recentOfType(bot.getUuid(), io.github.zoyluo.aibot.memory.EpisodeLog.Type.DEATH, 1);
            if (deaths.isEmpty()) {
                return fail("no_recent_death");
            }
            var death = deaths.get(0);
            Task task = new io.github.zoyluo.aibot.task.RecoverDropsTask(death.pos(), death.gameTick());
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: recover_drops -> " + death.pos().toShortString());
        });

        register("set_goal", "Set a persistent long-term goal with ordered steps. Steps should be an array of short strings.", objectSchema()
                .property("title", stringSchema("goal title"))
                .property("steps", arrayOfStringsSchema("ordered goal steps"))
                .required("title")
                .required("steps")
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> {
            List<String> steps = stringArray(args, "steps");
            BotMemoryStore.INSTANCE.of(bot.getUuid()).setGoal(requiredString(args, "title"), steps);
            return ok(BotMemoryStore.INSTANCE.of(bot.getUuid()).goalStatus(""));
        });

        register("advance_goal", "Advance the current persistent long-term goal by one step", objectSchema()
                .property("result", stringSchema("short result of the completed step"))
                .build(), ToolDefinition.Group.MEMORY, (bot, args) -> ok(BotMemoryStore.INSTANCE.of(bot.getUuid()).advanceGoal(optionalString(args, "result", ""))));

        register("goal_status", "Get the current persistent long-term goal status", objectSchema().build(), ToolDefinition.Group.MEMORY, (bot, args) ->
                ok(BotMemoryStore.INSTANCE.of(bot.getUuid()).goalStatus("")));

        register("assign_task", "Start a high-level deterministic task for the bot. Prefer this for movement, gathering, foraging, mining, combat, building, sleep, lighting, farming, fishing, trading, breeding, and container work. Use dedicated craft, eat, and smelt tools for those actions. For exposed surface blocks use task_type=mine. To obtain ores (iron/coal/copper/gold/diamond, *_ore, or raw_*), use the dedicated mine_ore tool which auto-locates the nearest ore and mines it directly — do NOT use strip_mine for getting ore. Supersedes any current task. Build params: blueprint plus optional anchor_x/anchor_y/anchor_z, auto_site, and flatten. x/y/z aliases are accepted; omit anchor when auto_site=true.", objectSchema()
                .property("task_type", stringSchema("move, gather, forage, irrigate, milk_cow, raid_crops, attack, mine, strip_mine, mine_vein, build, sleep, light_area, farm, harvest, fish, trade, breed, follow, hold, guard, deposit, stockpile, or withdraw"))
                .property("params", objectSchema().build())
                .required("task_type")
                .required("params")
                .build(), (bot, args) -> {
            String taskType = requiredString(args, "task_type");
            JsonObject params = args.getAsJsonObject("params");
            if ("mine_ore".equals(taskType)) {
                if (!AIBotConfig.get().goal().autoToolFillEnabled()) {
                    Task task = new OreDigTask(oreTargetsFrom(requiredString(params, "ore")), optionalInt(params, "count", 1));
                    TaskManager.INSTANCE.assign(bot, task);
                    return ok("assigned: " + task.name());
                }
                boolean started = GoalExecutor.INSTANCE.submit(bot,
                        new Goal.MineOre(oreTargetsFrom(requiredString(params, "ore")), optionalInt(params, "count", 1)));
                return started ? ok("goal_assigned: mine_ore") : fail("goal_plan_failed");
            }
            if ("mine".equals(taskType)) {
                Block block = blockWithAlias(params, "block", "block_type");
                if (OreScan.isOreBlock(block)) {
                    int count = optionalInt(params, "count", 1);
                    if (!AIBotConfig.get().goal().autoToolFillEnabled()) {
                        Task task = new OreDigTask(OreScan.oreFamily(block), count);
                        TaskManager.INSTANCE.assign(bot, task);
                        return ok("assigned: " + task.name());
                    }
                    boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(OreScan.oreFamily(block), count));
                    return started ? ok("goal_assigned: mine_ore") : fail("goal_plan_failed");
                }
            }
            if ("follow".equals(taskType)) {
                String playerName = optionalString(params, "player_name", "");
                LongRunningIntentManager.INSTANCE.setFollow(bot, playerName);
                Task task = new FollowTask(playerName);
                TaskManager.INSTANCE.assign(bot, task);
                return ok("long_intent_assigned: follow");
            }
            Task task = createTask(bot, taskType, params);
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("get_task_status", "Get the current task status", objectSchema().build(), (bot, args) -> {
            // 优化3:有确定性目标在跑时不喂详细状态——断掉大脑反复轮询的正反馈(实测 get_task_status×19 耗尽轮次);
            // 目标完成/失败会主动唤醒大脑,期间无需查询。
            if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                return ok("{\"state\":\"goal_running\",\"note\":\"目标执行中,完成或失败时会通知你,期间不要重复查询\"}");
            }
            TaskStatus status = TaskManager.INSTANCE.status(bot);
            return ok("{\"name\":\"" + escape(status.name())
                    + "\",\"state\":\"" + status.state()
                    + "\",\"progress\":" + status.progress()
                    + ",\"description\":\"" + escape(status.description()) + "\"}");
        });

        register("abort_task", "Cancel the current task", objectSchema().build(), (bot, args) -> {
            TaskManager.INSTANCE.abort(bot);
            return ok("aborted");
        });
    }

    private static ToolDefinition.ToolResult assignSmartNavigate(AIPlayerEntity bot, JsonObject args) {
        String mode = requiredString(args, "mode").trim().toLowerCase(java.util.Locale.ROOT);
        Task task;
        switch (mode) {
            case "go" -> {
                boolean useMarker = optionalBoolean(args, "use_marker", false);
                boolean hasX = args.has("x");
                boolean hasY = args.has("y");
                boolean hasZ = args.has("z");
                String playerName = optionalString(args, "player_name", "").trim();
                int targets = (useMarker ? 1 : 0) + ((hasX || hasY || hasZ) ? 1 : 0) + (!playerName.isEmpty() ? 1 : 0);
                if (targets > 1) {
                    return fail("conflicting_target: use_marker/坐标/player_name 只能选一种");
                }
                BlockPos target;
                if (useMarker) {
                    var marker = io.github.zoyluo.aibot.marker.TargetMarkerService.INSTANCE.forBot(bot)
                            .orElseThrow(() -> new IllegalArgumentException("no_active_marker: 请主人先 Shift+中键标记目标"));
                    String botDimension = bot.getServerWorld().getRegistryKey().getValue().toString();
                    if (!botDimension.equals(marker.dimensionId())) {
                        return fail("marker_in_other_dimension: " + marker.dimensionId());
                    }
                    target = marker.standPos();
                } else if (hasX || hasY || hasZ) {
                    if (!(hasX && hasY && hasZ)) {
                        return fail("bad_target: go 需要同时提供 x、y、z");
                    }
                    target = new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
                } else {
                    ServerPlayerEntity player = targetPlayer(bot, playerName)
                            .orElseThrow(() -> new IllegalArgumentException("target_player_not_found"));
                    target = player.getBlockPos();
                }
                task = new MoveTask(bot, target);
            }
            case "follow" -> {
                String playerName = optionalString(args, "player_name", "");
                LongRunningIntentManager.INSTANCE.setFollow(bot, playerName);
                task = new FollowTask(playerName);
            }
            case "explore" -> {
                Direction direction = optionalDirection(args, "direction", bot.getHorizontalFacing());
                int distance = Math.max(8, Math.min(64, optionalInt(args, "distance", 32)));
                BlockPos target = surfaceStandable(bot.getServerWorld(),
                        bot.getBlockX() + direction.getOffsetX() * distance,
                        bot.getBlockZ() + direction.getOffsetZ() * distance, 8);
                if (target == null) {
                    return fail("no_standable_target");
                }
                task = new MoveTask(bot, target);
            }
            case "wander" -> {
                BlockPos target = randomWanderTarget(bot, optionalInt(args, "distance", 16));
                if (target == null) {
                    return fail("wander_no_target");
                }
                task = new MoveTask(bot, target);
            }
            case "route" -> {
                boolean useMarker = optionalBoolean(args, "use_marker", false);
                boolean hasX = args.has("x");
                boolean hasZ = args.has("z");
                String playerName = optionalString(args, "player_name", "").trim();
                String directionName = optionalString(args, "direction", "").trim();
                int selectedModes = (useMarker ? 1 : 0) + ((hasX || hasZ) ? 1 : 0)
                        + (!playerName.isEmpty() ? 1 : 0) + (!directionName.isEmpty() ? 1 : 0);
                if (selectedModes > 1) {
                    return fail("conflicting_target: use_marker/x+z/direction/player_name 只能选一种");
                }
                if (hasX != hasZ) {
                    return fail("bad_target: route 的 x 和 z 必须同时提供");
                }
                if (useMarker) {
                    var marker = io.github.zoyluo.aibot.marker.TargetMarkerService.INSTANCE.forBot(bot)
                            .orElseThrow(() -> new IllegalArgumentException("no_active_marker: 请主人先 Shift+中键标记目标"));
                    String botDimension = bot.getServerWorld().getRegistryKey().getValue().toString();
                    if (!botDimension.equals(marker.dimensionId())) {
                        return fail("marker_in_other_dimension: " + marker.dimensionId());
                    }
                    int budget = scaffoldBudget(bot, marker.standPos(), args);
                    task = new ScaffoldWalkTask(marker.standPos().getX(), marker.standPos().getZ(), budget);
                } else if (!directionName.isEmpty()) {
                    Direction direction = optionalDirection(args, "direction", Direction.NORTH);
                    int length = Math.max(1, Math.min(256, optionalInt(args, "length", 16)));
                    BlockPos target = bot.getBlockPos().offset(direction, length);
                    int budget = args.has("max_blocks") ? optionalInt(args, "max_blocks", length + 8) : length + 8;
                    task = new ScaffoldWalkTask(target.getX(), target.getZ(), budget);
                } else if (hasX) {
                    BlockPos target = new BlockPos(requiredInt(args, "x"), bot.getBlockY(), requiredInt(args, "z"));
                    task = new ScaffoldWalkTask(target.getX(), target.getZ(), scaffoldBudget(bot, target, args));
                } else {
                    ServerPlayerEntity player = targetPlayer(bot, playerName)
                            .orElseThrow(() -> new IllegalArgumentException("target_player_not_found"));
                    int budget = scaffoldBudget(bot, player.getBlockPos(), args);
                    task = new ScaffoldWalkTask(player.getGameProfile().getName(), player.getBlockPos(), budget);
                }
            }
            case "pillar" -> task = new PillarUpTask(optionalInt(args, "height", 5));
            case "surface" -> {
                ServerWorld world = bot.getServerWorld();
                BlockPos feet = bot.getBlockPos();
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
                if (feet.getY() >= topY - 2) {
                    return ok("already_on_surface");
                }
                BlockPos target = surfaceStandable(world, feet.getX(), feet.getZ(), 16);
                if (target == null) {
                    return fail("no_surface_spot");
                }
                task = new MoveTask(bot, target);
            }
            case "descend" -> task = new DescendToYTask(Math.max(-59, Math.min(100, requiredInt(args, "y"))));
            case "patrol" -> task = new PatrolTask(optionalInt(args, "radius", 8), optionalInt(args, "laps", 1));
            case "escape" -> {
                HostileEntity hostile = bot.getServerWorld()
                        .getEntitiesByClass(HostileEntity.class, bot.getBoundingBox().expand(24.0D), Entity::isAlive)
                        .stream()
                        .min(Comparator.comparingDouble(bot::distanceTo))
                        .orElse(null);
                if (hostile == null) {
                    return fail("no_hostile_nearby: 附近没怪,不用逃");
                }
                task = new EvadeTask(new Threat(Threat.Type.HOSTILE, Threat.Severity.HIGH, hostile, hostile.getBlockPos()));
            }
            case "unstuck" -> {
                bot.getActionPack().stopAll();
                bot.getActionPack().jumpOnce();
                double angle = java.util.concurrent.ThreadLocalRandom.current().nextDouble(Math.PI * 2.0D);
                double distance = 2.0D + java.util.concurrent.ThreadLocalRandom.current().nextDouble(1.5D);
                var result = bot.getActionPack().startPathTo(BlockPos.ofFloored(
                        bot.getPos().add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance)));
                return result.isFailed() ? fail("unstuck_failed: " + result.reason()) : ok("unstuck_nudge_started");
            }
            default -> {
                return fail("unknown_navigation_mode: " + mode);
            }
        }
        TaskManager.INSTANCE.assign(bot, task);
        return ok("assigned: " + task.name());
    }

    private static ToolDefinition.ToolResult assignSmartCombat(AIPlayerEntity bot, JsonObject args) {
        String mode = requiredString(args, "mode").trim().toLowerCase(java.util.Locale.ROOT);
        Task task;
        switch (mode) {
            case "attack" -> {
                java.util.UUID leashOwner = AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null);
                task = new CombatTask(requiredEntityType(args, "entity_type"), optionalInt(args, "count", 1),
                        AIBotConfig.get().combat().retreatHp(), leashOwner, 16.0D);
            }
            case "chase_owner" -> task = ChaseAttackTask.ownerTarget(bot)
                    .orElseThrow(() -> new IllegalArgumentException("no_owner: 这个 bot 没有主人,无法追杀"));
            case "guard" -> {
                BlockPos point = optionalBlockPos(args, "x", "y", "z");
                String playerName = optionalString(args, "player_name", "").trim();
                if (point != null && !playerName.isEmpty()) {
                    return fail("conflicting_target: guard 只能选 player_name 或坐标");
                }
                if (point != null) {
                    task = GuardTask.point(point);
                } else {
                    ServerPlayerEntity player = targetPlayer(bot, playerName)
                            .orElseThrow(() -> new IllegalArgumentException("target_player_not_found"));
                    task = GuardTask.player(player.getGameProfile().getName());
                }
            }
            case "escape" -> {
                HostileEntity hostile = bot.getServerWorld()
                        .getEntitiesByClass(HostileEntity.class, bot.getBoundingBox().expand(24.0D), Entity::isAlive)
                        .stream()
                        .min(Comparator.comparingDouble(bot::distanceTo))
                        .orElse(null);
                if (hostile == null) {
                    return fail("no_hostile_nearby: 附近没怪,不用逃");
                }
                task = new EvadeTask(new Threat(Threat.Type.HOSTILE, Threat.Severity.HIGH, hostile, hostile.getBlockPos()));
            }
            default -> {
                return fail("unknown_combat_mode: " + mode);
            }
        }
        TaskManager.INSTANCE.assign(bot, task);
        return ok("assigned: " + task.name());
    }

    private static Task createTask(io.github.zoyluo.aibot.entity.AIPlayerEntity bot, String taskType, JsonObject params) {
        if (params == null) {
            throw new IllegalArgumentException("missing_or_bad_arg: params");
        }
        return switch (taskType) {
            case "move" -> new MoveTask(bot, new BlockPos(requiredInt(params, "x"), requiredInt(params, "y"), requiredInt(params, "z")));
            case "forage" -> new GatherQuotaTask(net.minecraft.item.Items.SWEET_BERRIES, optionalInt(params, "count", 4));
            case "attack" -> new CombatTask(
                    requiredEntityType(params, "entity_type"),
                    optionalInt(params, "count", 1),
                    io.github.zoyluo.aibot.AIBotConfig.get().combat().retreatHp());
            case "mine" -> {
                Block block = blockWithAlias(params, "block", "block_type");
                int count = optionalInt(params, "count", 1);
                yield OreScan.isOreBlock(block) ? new OreDigTask(OreScan.oreFamily(block), count) : new MineTask(block, count);
            }
            case "mine_ore" -> new OreDigTask(oreTargetsFrom(requiredString(params, "ore")), optionalInt(params, "count", 1));
            case "gather" -> new GatherQuotaTask(requiredItem(params, "item"), optionalInt(params, "count", 1));
            case "irrigate" -> new io.github.zoyluo.aibot.task.IrrigateTask(
                    bot.getBlockPos().offset(bot.getHorizontalFacing(), 2).down()); // 身前 2 格 floor 层挖 2×2 无限水源
            case "milk_cow" -> new io.github.zoyluo.aibot.task.MilkCowTask(optionalInt(params, "count", 1)); // 挤 count 桶牛奶(需空桶)
            case "raid_crops" -> new io.github.zoyluo.aibot.task.RaidCropsTask(optionalInt(params, "count", 8)); // 收割附近(村庄/野外)成熟作物
            case "fish" -> new FishTask(optionalInt(params, "max_catches", 1), optionalInt(params, "max_ticks", 6000));
            case "trade" -> new TradeTask(optionalItem(params, "target_item"), optionalInt(params, "max_distance", 16));
            case "stockpile" -> new StockpileTask(optionalBoolean(params, "all_except_tools", true));
            case "sleep" -> new SleepTask();
            case "light_area" -> new LightAreaTask(optionalInt(params, "radius", 8), optionalInt(params, "max_torches", 8));
            case "follow" -> new FollowTask(optionalString(params, "player_name", ""));
            case "hold" -> new HoldTask();
            case "guard" -> {
                String playerName = optionalString(params, "player_name", "");
                BlockPos point = optionalBlockPos(params, "x", "y", "z");
                yield playerName.isBlank() ? GuardTask.point(point) : GuardTask.player(playerName);
            }
            case "farm" -> {
                FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(params, "crop"));
                yield new FarmTask(blockPos(params), optionalInt(params, "radius", 3), spec.seed(), spec.crop(),
                        optionalBoolean(params, "keep_tending", false), false);
            }
            case "harvest" -> {
                FarmAction.CropSpec spec = FarmAction.cropSpec(requiredString(params, "crop"));
                yield new FarmTask(blockPos(params), optionalInt(params, "radius", 3), spec.seed(), spec.crop(), false, true);
            }
            case "breed" -> new BreedTask(requiredEntityType(params, "entity_type"), optionalInt(params, "pairs", 1));
            case "strip_mine" -> new StripMineTask(
                    optionalDirection(params, "direction", Direction.NORTH),
                    optionalInt(params, "length", 16),
                    optionalInt(params, "spacing", 4),
                    optionalBlockPos(params, "depot_x", "depot_y", "depot_z"),
                    optionalBlocksCsv(params, "target_ores"));
            case "mine_vein" -> StripMineTask.mineNearbyVein(optionalBlocksCsv(params, "target_ores"));
            case "deposit" -> ContainerTask.deposit(
                    optionalBlockPos(params, "chest_x", "chest_y", "chest_z"),
                    optionalItem(params, "item"),
                    optionalInt(params, "count", 0),
                    optionalBoolean(params, "all_except_tools", false));
            case "withdraw" -> ContainerTask.withdraw(
                    optionalBlockPos(params, "chest_x", "chest_y", "chest_z"),
                    requiredItem(params, "item"),
                    optionalInt(params, "count", 1));
            case "build" -> {
                try {
                    boolean autoSite = optionalBoolean(params, "auto_site", false);
                    boolean flatten = optionalBoolean(params, "flatten", false);
                    BlockPos anchor = autoSite && !hasBlockPos(params, "anchor_x", "anchor_y", "anchor_z") && !hasBlockPos(params, "x", "y", "z")
                            ? null
                            : new BlockPos(
                                    intWithAlias(params, "anchor_x", "x"),
                                    intWithAlias(params, "anchor_y", "y"),
                                    intWithAlias(params, "anchor_z", "z"));
                    yield new BuildTask(
                            BlueprintLoader.load(requiredString(params, "blueprint")),
                            anchor,
                            autoSite,
                            flatten);
                } catch (java.io.IOException exception) {
                    throw new IllegalArgumentException(exception.getMessage(), exception);
                }
            }
            default -> throw new IllegalArgumentException("unknown_task_type: " + taskType);
        };
    }

    private void register(String name, String description, JsonObject schema, ToolDefinition.Handler handler) {
        tools.put(name, new ToolDefinition(name, description, schema, handler));
    }

    private void register(String name, String description, JsonObject schema, ToolDefinition.Group group, ToolDefinition.Handler handler) {
        tools.put(name, new ToolDefinition(name, description, schema, handler, group));
    }

    private static String craftPlanJson(CraftingHelper.CraftPlan plan) {
        JsonObject root = new JsonObject();
        root.addProperty("feasible", plan.success());
        root.addProperty("target", Registries.ITEM.getId(plan.target()).toString());
        root.addProperty("count", plan.targetCount());
        root.addProperty("needs_crafting_table", plan.needsCraftingTable());

        com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
        for (CraftingHelper.CraftStep step : plan.steps()) {
            JsonObject json = new JsonObject();
            json.addProperty("output", Registries.ITEM.getId(step.recipe().output()).toString());
            json.addProperty("crafts", step.crafts());
            json.addProperty("output_count", step.outputCount());
            json.addProperty("needs_crafting_table", step.recipe().needsCraftingTable());
            com.google.gson.JsonArray ingredients = new com.google.gson.JsonArray();
            for (io.github.zoyluo.aibot.craft.RecipeRegistry.Ingredient ingredient : step.recipe().ingredients()) {
                JsonObject ingredientJson = new JsonObject();
                ingredientJson.addProperty("count", ingredient.count() * step.crafts());
                com.google.gson.JsonArray anyOf = new com.google.gson.JsonArray();
                for (Item item : ingredient.anyOf()) {
                    anyOf.add(Registries.ITEM.getId(item).toString());
                }
                ingredientJson.add("any_of", anyOf);
                ingredients.add(ingredientJson);
            }
            json.add("ingredients", ingredients);
            steps.add(json);
        }
        root.add("steps", steps);

        com.google.gson.JsonArray missing = new com.google.gson.JsonArray();
        for (CraftingHelper.Missing item : plan.missing()) {
            JsonObject json = new JsonObject();
            json.addProperty("item", Registries.ITEM.getId(item.item()).toString());
            json.addProperty("count", item.count());
            json.addProperty("source", AcquisitionHints.source(item.item()));
            missing.add(json);
        }
        root.add("missing", missing);
        return root.toString();
    }

    private static ToolDefinition.ToolResult result(io.github.zoyluo.aibot.action.ActionResult actionResult) {
        if (actionResult.isSuccess() || actionResult.isInProgress()) {
            return ok(actionResult.status().name().toLowerCase());
        }
        return fail(actionResult.reason());
    }

    /**
     * 从背包(主+副手)扣走 item×count 并作为掉落物抛出;target 非空时先转身面向再抛(实物飞向玩家)。
     * 返回实际抛出数量(0=背包里没有)。
     */
    private static int dropFromInventory(AIPlayerEntity bot, Item item, int count, ServerPlayerEntity target) {
        int want = Math.max(1, count);
        var inventory = bot.getInventory();
        int removed = 0;
        for (int slot = 0; slot < inventory.main.size() && removed < want; slot++) {
            net.minecraft.item.ItemStack stack = inventory.main.get(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(stack.getCount(), want - removed);
                stack.decrement(take);
                removed += take;
            }
        }
        for (int slot = 0; slot < inventory.offHand.size() && removed < want; slot++) {
            net.minecraft.item.ItemStack stack = inventory.offHand.get(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(stack.getCount(), want - removed);
                stack.decrement(take);
                removed += take;
            }
        }
        if (removed == 0) {
            return 0;
        }
        if (target != null) {
            // dropItem 的抛出方向跟随 bot 视角:先面向目标(含俯仰),抛出物自然飞向玩家。
            double dx = target.getX() - bot.getX();
            double dz = target.getZ() - bot.getZ();
            double dy = target.getEyeY() - bot.getEyeY();
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            LookAction.setYawPitch(bot, yaw, pitch);
        }
        int remaining = removed;
        while (remaining > 0) {
            int amount = Math.min(remaining, Math.max(1, item.getMaxCount()));
            bot.dropItem(new net.minecraft.item.ItemStack(item, amount), false, false);
            remaining -= amount;
        }
        return removed;
    }

    /** give_item 目标:指名玩家 → owner → 16 格内最近的真人玩家。 */
    private static ServerPlayerEntity resolveTossTarget(AIPlayerEntity bot, String name) {
        net.minecraft.server.MinecraftServer server = bot.getServer();
        if (server == null) {
            return null;
        }
        if (name != null && !name.isBlank()) {
            return server.getPlayerManager().getPlayer(name.trim());
        }
        ServerPlayerEntity owner = AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(uuid -> server.getPlayerManager().getPlayer(uuid))
                .orElse(null);
        if (owner != null) {
            return owner;
        }
        net.minecraft.entity.player.PlayerEntity nearest =
                bot.getServerWorld().getClosestPlayer(bot, 16.0D);
        return nearest instanceof ServerPlayerEntity sp && !(nearest instanceof AIPlayerEntity) ? sp : null;
    }

    private static ToolDefinition.ToolResult ok(String message) {
        return new ToolDefinition.ToolResult(true, message);
    }

    private static ToolDefinition.ToolResult fail(String message) {
        return new ToolDefinition.ToolResult(false, message);
    }

    private static BlockPos blockPos(JsonObject args) {
        return new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
    }

    private static int requiredInt(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return args.get(name).getAsInt();
    }

    private static int intWithAlias(JsonObject args, String primary, String alias) {
        if (args.has(primary) && args.get(primary).isJsonPrimitive()) {
            return args.get(primary).getAsInt();
        }
        if (args.has(alias) && args.get(alias).isJsonPrimitive()) {
            return args.get(alias).getAsInt();
        }
        throw new IllegalArgumentException("missing_or_bad_arg: " + primary);
    }

    private static String requiredString(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return args.get(name).getAsString();
    }

    private static int optionalInt(JsonObject args, String name, int defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return args.get(name).getAsInt();
        } catch (NumberFormatException e) {
            return defaultValue; // 模型偶发传非数字字符串("全部"),回退默认而不是炸掉整轮工具调用
        }
    }

    private static boolean optionalBoolean(JsonObject args, String name, boolean defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            return defaultValue;
        }
        return args.get(name).getAsBoolean();
    }

    private static String optionalString(JsonObject args, String name, String defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            return defaultValue;
        }
        String value = args.get(name).getAsString();
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static List<String> stringArray(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonArray()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement element : args.getAsJsonArray(name)) {
            if (!element.isJsonPrimitive()) {
                throw new IllegalArgumentException("missing_or_bad_arg: " + name);
            }
            String value = element.getAsString();
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return values;
    }

    private static Map<String, String> paramsObject(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonObject()) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : args.getAsJsonObject(name).entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                params.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return params;
    }

    private static BlockPos optionalBlockPos(JsonObject args, String xName, String yName, String zName) {
        if (!args.has(xName) && !args.has(yName) && !args.has(zName)) {
            return null;
        }
        return new BlockPos(requiredInt(args, xName), requiredInt(args, yName), requiredInt(args, zName));
    }

    private static boolean hasBlockPos(JsonObject args, String xName, String yName, String zName) {
        return args.has(xName) && args.has(yName) && args.has(zName);
    }

    private static Block requiredBlock(JsonObject args, String name) {
        Identifier id = Identifier.of(requiredString(args, name));
        return Registries.BLOCK.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_block: " + id));
    }

    private static Block blockWithAlias(JsonObject args, String primary, String alias) {
        if (args.has(primary) && args.get(primary).isJsonPrimitive()) {
            return requiredBlock(args, primary);
        }
        if (args.has(alias) && args.get(alias).isJsonPrimitive()) {
            return requiredBlock(args, alias);
        }
        throw new IllegalArgumentException("missing_or_bad_arg: " + primary);
    }

    private static Item requiredItem(JsonObject args, String name) {
        Identifier id = Identifier.of(requiredString(args, name));
        return Registries.ITEM.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_item: " + id));
    }

    private static Item optionalItem(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive() || args.get(name).getAsString().isBlank()) {
            return null;
        }
        return requiredItem(args, name);
    }

    private static EntityType<?> requiredEntityType(JsonObject args, String name) {
        Identifier id = Identifier.of(requiredString(args, name));
        return Registries.ENTITY_TYPE.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_entity_type: " + id));
    }

    private static Direction optionalDirection(JsonObject args, String name, Direction defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive() || args.get(name).getAsString().isBlank()) {
            return defaultValue;
        }
        return switch (args.get(name).getAsString().toLowerCase(java.util.Locale.ROOT)) {
            case "north", "n" -> Direction.NORTH;
            case "south", "s" -> Direction.SOUTH;
            case "east", "e" -> Direction.EAST;
            case "west", "w" -> Direction.WEST;
            default -> throw new IllegalArgumentException("unknown_direction: " + args.get(name).getAsString());
        };
    }

    private static Set<Block> optionalBlocksCsv(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive() || args.get(name).getAsString().isBlank()) {
            return Set.of();
        }
        Set<Block> blocks = new HashSet<>();
        for (String token : args.get(name).getAsString().split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Identifier id = Identifier.of(trimmed);
            blocks.add(Registries.BLOCK.getOptionalValue(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown_block: " + id)));
        }
        return blocks;
    }

    // 把"矿石方块 id"或"原矿物品(raw_iron/iron_ore 等)"解析成目标矿石家族(含深板岩变体)。
    private static java.util.Set<Block> oreTargetsFrom(String oreOrItem) {
        Identifier id = Identifier.of(oreOrItem.trim());
        Block block = Registries.BLOCK.getOptionalValue(id).orElse(null);
        if (block != null && OreScan.isOreBlock(block)) {
            return OreScan.oreFamily(block);
        }
        String path = id.getPath().replace("raw_", "");
        for (String cand : new String[]{"minecraft:" + path + "_ore", "minecraft:" + path}) {
            Block b = Registries.BLOCK.getOptionalValue(Identifier.of(cand)).orElse(null);
            if (b != null && OreScan.isOreBlock(b)) {
                return OreScan.oreFamily(b);
            }
        }
        return OreScan.COMMON_ORES;
    }

    /** 目标水平坐标附近(本列优先,环形外扩到 ring)找第一个地表可站、脚下无水的落点。 */
    private static BlockPos surfaceStandable(ServerWorld world, int x, int z, int ring) {
        for (int r = 0; r <= ring; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // 只扫当前环周,近处优先
                    }
                    int cx = x + dx;
                    int cz = z + dz;
                    int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                    BlockPos candidate = new BlockPos(cx, y, cz);
                    if (Standability.isStandable(world, candidate) && world.getFluidState(candidate.down()).isEmpty()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** 随机方向 8~max 格的地表可站点(≤8 次尝试),与 GiftDispatcher.pickWanderTarget 同款。 */
    private static BlockPos randomWanderTarget(AIPlayerEntity bot, int max) {
        ServerWorld world = bot.getServerWorld();
        double span = Math.max(1, Math.min(32, max) - 8);
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = java.util.concurrent.ThreadLocalRandom.current().nextDouble(Math.PI * 2.0D);
            double dist = 8.0D + java.util.concurrent.ThreadLocalRandom.current().nextDouble(span);
            int x = (int) Math.floor(bot.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(bot.getZ() + Math.sin(angle) * dist);
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (Standability.isStandable(world, candidate) && world.getFluidState(candidate.down()).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    /** 手动可开关:木门/活板门/栅栏门/拉杆/按钮;铁门铁活板门要红石,排除。 */
    private static boolean isToggleable(BlockState state) {
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        if (path.startsWith("iron_")) {
            return false;
        }
        return path.endsWith("_door") || path.endsWith("_trapdoor") || path.endsWith("_fence_gate")
                || path.equals("lever") || path.endsWith("_button");
    }

    /** gear_check 用:非空物品追加 "槽位":"物品 剩余耐久%",(无耐久物品只报名字)。 */
    private static void appendGear(StringBuilder sb, String slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        sb.append("\"").append(slot).append("\":\"").append(Registries.ITEM.getId(stack.getItem()).getPath());
        if (stack.isDamageable()) {
            int left = (stack.getMaxDamage() - stack.getDamage()) * 100 / Math.max(1, stack.getMaxDamage());
            sb.append(" ").append(left).append("%");
        }
        sb.append("\",");
    }

    private static String describeStack(ItemStack stack) {
        return stack.isEmpty() ? "empty" : Registries.ITEM.getId(stack.getItem()).getPath() + " x" + stack.getCount();
    }

    private static Optional<ServerPlayerEntity> targetPlayer(AIPlayerEntity bot, String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            return Optional.ofNullable(bot.getServer().getPlayerManager().getPlayer(playerName.trim()));
        }
        return AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(uuid -> bot.getServer().getPlayerManager().getPlayer(uuid));
    }

    private static int scaffoldBudget(AIPlayerEntity bot, BlockPos target, JsonObject args) {
        if (args.has("max_blocks")) {
            return Math.max(8, Math.min(512, optionalInt(args, "max_blocks", 96)));
        }
        double distance = Math.hypot(target.getX() - bot.getX(), target.getZ() - bot.getZ());
        // A diagonal step may need the forward cell plus two corner supports.
        return Math.max(8, Math.min(512, (int) Math.ceil(distance * 3.0D) + 8));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static JsonObject xyzSchema() {
        return objectSchema()
                .property("x", integerSchema("block x"))
                .property("y", integerSchema("block y"))
                .property("z", integerSchema("block z"))
                .required("x")
                .required("y")
                .required("z")
                .build();
    }

    private static JsonObject stringSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject arrayOfStringsSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.addProperty("description", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        schema.add("items", items);
        return schema;
    }

    private static JsonObject integerSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject integerSchema(String description, int min, int max) {
        JsonObject schema = integerSchema(description);
        schema.addProperty("minimum", min);
        schema.addProperty("maximum", max);
        return schema;
    }

    private static JsonObject booleanSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "boolean");
        schema.addProperty("description", description);
        return schema;
    }

    private static ObjectSchemaBuilder objectSchema() {
        return new ObjectSchemaBuilder();
    }

    private static final class ObjectSchemaBuilder {
        private final JsonObject root = new JsonObject();
        private final JsonObject properties = new JsonObject();
        private final com.google.gson.JsonArray required = new com.google.gson.JsonArray();

        private ObjectSchemaBuilder() {
            root.addProperty("type", "object");
            root.add("properties", properties);
            root.add("required", required);
        }

        private ObjectSchemaBuilder property(String name, JsonObject schema) {
            properties.add(name, schema);
            return this;
        }

        private ObjectSchemaBuilder required(String name) {
            required.add(name);
            return this;
        }

        private JsonObject build() {
            return root;
        }
    }
}
