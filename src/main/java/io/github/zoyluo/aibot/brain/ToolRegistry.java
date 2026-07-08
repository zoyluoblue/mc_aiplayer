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
import io.github.zoyluo.aibot.goal.Goal;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemory;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.task.BlueprintLoader;
import io.github.zoyluo.aibot.task.BreedTask;
import io.github.zoyluo.aibot.task.BuildTask;
import io.github.zoyluo.aibot.task.CombatTask;
import io.github.zoyluo.aibot.task.ContainerTask;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.EatTask;
import io.github.zoyluo.aibot.task.FishTask;
import io.github.zoyluo.aibot.task.FarmTask;
import io.github.zoyluo.aibot.task.GatherQuotaTask;
import io.github.zoyluo.aibot.task.FollowTask;
import io.github.zoyluo.aibot.task.PvPTask;
import io.github.zoyluo.aibot.task.GuardTask;
import io.github.zoyluo.aibot.task.HoldTask;
import io.github.zoyluo.aibot.task.LightAreaTask;
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
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ToolRegistry {
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

    public List<ToolDefinition> tools(AIBotConfig.Brain config,
                                      boolean exposeLowLevelTools,
                                      boolean memoryToolsEnabled,
                                      boolean coordinationToolsEnabled) {
        return tools.values().stream()
                .filter(tool -> switch (tool.group()) {
                    case CORE -> true;
                    case MEMORY -> memoryToolsEnabled;
                    case COORDINATION -> coordinationToolsEnabled;
                    case LOW_LEVEL -> exposeLowLevelTools;
                })
                .toList();
    }

    private void registerDefaults() {
        register("say", "Reply to the human in Russian. The reply is shown in the AIBot panel.", objectSchema()
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

        register("move_to", "Pathfind to a coordinate. Falls back to straight-line walking if pathfinding fails.", xyzSchema(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            BlockPos goal = blockPos(args);
            // BUGFIX: anti-spam — не двигаться если уже рядом
            if (bot.getBlockPos().getSquaredDistance(goal) <= 4.0D) {
                return ok("already_at_destination");
            }
            io.github.zoyluo.aibot.action.ActionResult pathResult = MovementAction.startPathTo(bot, goal);
            if (pathResult.isInProgress() || pathResult.isSuccess()) {
                return ok("pathfinding_started");
            }
            io.github.zoyluo.aibot.action.ActionResult fallback = MovementAction.startWalkTo(bot, Vec3d.ofCenter(goal));
            if (fallback.isInProgress() || fallback.isSuccess()) {
                return ok("fallback_walk_started: " + pathResult.reason());
            }
            return fail("path_and_walk_both_failed: " + pathResult.reason());
        });

        register("mine_block", "Low-level single-block break at given coords. Bot must already be within reach. For gathering materials or mining counts, prefer assign_task with task_type mine.", xyzSchema(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> {
            BlockPos pos = blockPos(args);
            MiningAction.startMining(bot, pos, Direction.getFacing(bot.getEyePos().subtract(pos.toCenterPos())));
            return ok("started");
        });

        register("place_block", "Place a block at given coordinates. If the held item is already a usable BlockItem (e.g. set via select_item), that block is placed. Otherwise auto-selects the first BlockItem from inventory.", xyzSchema(), (bot, args) -> {
            return result(BuildAction.placeBlockAt(bot, blockPos(args)));
        });

        register("select_hotbar", "Select hotbar slot 0..8", objectSchema()
                .property("slot", integerSchema("hotbar slot", 0, 8))
                .required("slot")
                .build(), ToolDefinition.Group.LOW_LEVEL, (bot, args) -> result(InventoryAction.selectHotbar(bot, requiredInt(args, "slot"))));

        register("select_item", "Equip an item from inventory to the main hand by its item id. For example: minecraft:chest, minecraft:crafting_table, minecraft:dirt. Use this BEFORE place_block if you need a specific item placed.", objectSchema()
                .property("item", stringSchema("item id, for example minecraft:chest"))
                .required("item")
                .build(), (bot, args) -> {
            if (io.github.zoyluo.aibot.action.BuildAction.equipItem(bot, requiredString(args, "item"))) {
                return ok("equipped");
            }
            return fail("item_not_found_in_inventory");
        });


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

        register("craft", "Craft an item using known survival recipes. It resolves planks and sticks recursively, so prefer crafting the target tool/item directly instead of crafting planks or sticks as separate steps. It does not gather, smelt, or open GUIs. For 3x3 recipes, craft minecraft:crafting_table first; after that this task can use a nearby table or place a held table automatically. Do not use select_hotbar/place_block for the crafting table unless the human asks. Fails with need: <item> xN when base materials are missing.", objectSchema()
                .property("item", stringSchema("item id, for example minecraft:stone_pickaxe"))
                .property("count", integerSchema("desired count"))
                .required("item")
                .build(), (bot, args) -> {
            Task task = new CraftTask(requiredItem(args, "item"), optionalInt(args, "count", 1));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
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

        register("gather", "Gather an item until the inventory contains the requested quota. Use this for requests like collect 64 cobblestone; it loops survey, move, harvest, and pickup without assigning child tasks.", objectSchema()
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

        register("strip_mine", "Mine a 2-high branch tunnel in a direction. Use this for ore blocks such as minecraft:iron_ore, not assign_task mine. If started above the target ore layer and no exposed ore is nearby, it first digs a descending stair shaft to the ore layer, then branches, follows veins, places torches, and can return to a depot chest when nearly full.", objectSchema()
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

        register("mine_vein", "Mine the nearest visible ore vein in range using bounded BFS. Optional target_ores is a comma separated list of ore block ids.", objectSchema()
                .property("target_ores", stringSchema("optional comma separated ore block ids; default is common ores"))
                .build(), (bot, args) -> {
            Task task = StripMineTask.mineNearbyVein(optionalBlocksCsv(args, "target_ores"));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("mine_ore", "PREFERRED way to obtain ores (e.g. minecraft:iron_ore or raw item minecraft:raw_iron). Starts a deterministic goal plan: prepare the required pickaxe first, then mine the ore. Do not manually break this into gather/craft/mine steps.", objectSchema()
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

        register("achieve_goal", "Achieve an item/tool inventory goal with deterministic planning. Use this for requests like make an iron pickaxe or obtain 10 iron ingots; do not manually decompose the steps.", objectSchema()
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

        register("forage", "Forage wild sweet berries nearby. ONLY when the user EXPLICITLY asks for berries/wild fruit, NOT for general food. "
                + "Use for wild berries request; needs sweet berry bushes around. "
                + "For ANY general food request use provision_food instead (it auto-picks hunt or farm).",
                objectSchema()
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

        register("attack", "Start a deterministic combat task against nearby entities of a type. The bot equips armor and weapon, attacks on cooldown, and retreats at low health.", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:zombie"))
                .property("count", integerSchema("number of kills"))
                .required("entity_type")
                .build(), (bot, args) -> {
            Task task = new CombatTask(
                    requiredEntityType(args, "entity_type"),
                    optionalInt(args, "count", 1),
                    io.github.zoyluo.aibot.AIBotConfig.get().combat().retreatHp());
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
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

        register("follow", "Follow a player while keeping roughly 2-4 blocks of distance. Omit player_name to follow this bot's owner.", objectSchema()
                .property("player_name", stringSchema("optional player name; defaults to owner"))
                .build(), (bot, args) -> {
            Task task = new FollowTask(optionalString(args, "player_name", ""));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("hold", "Hold the current position until another task is assigned. DangerWatcher can still interrupt for survival threats.", objectSchema().build(), (bot, args) -> {
            Task task = new HoldTask();
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("guard", "Guard the current point, a coordinate, or a named player. Hostiles near the guard point are fought inline, then the bot returns.", objectSchema()
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

        register("kill_player", "Attack and kill a specific player by name. The bot will track, pursue, and fight the target player.", objectSchema()
                .property("player_name", stringSchema("the name of the player to kill"))
                .required("player_name")
                .build(), (bot, args) -> {
            TaskManager.INSTANCE.assign(bot, new PvPTask(requiredString(args, "player_name")));
            return ok("pvp_task_assigned: hunting " + requiredString(args, "player_name"));
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

        register("attack_entity", "Attack a nearby entity by type", objectSchema()
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

        register("stop", "Stop all ongoing actions", objectSchema().build(), (bot, args) -> {
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
            if (params == null) {
                throw new IllegalArgumentException("missing_params");
            }
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
        return args.get(name).getAsInt();
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


