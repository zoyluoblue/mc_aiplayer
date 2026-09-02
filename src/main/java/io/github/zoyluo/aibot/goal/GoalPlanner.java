package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.craft.AcquisitionHints;
import io.github.zoyluo.aibot.craft.SmeltChain;
import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.mining.MiningChain;
import io.github.zoyluo.aibot.mining.MiningBudget;
import io.github.zoyluo.aibot.mining.MiningFoodReserve;
import io.github.zoyluo.aibot.mining.OreProspector;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.task.BlueprintLoader;
import io.github.zoyluo.aibot.task.BlueprintSchema;
import io.github.zoyluo.aibot.task.EmergencyShelterTask;
import io.github.zoyluo.aibot.task.HuntTask;
import io.github.zoyluo.aibot.task.MiningServiceTask;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.world.Heightmap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class GoalPlanner {

    private GoalPlanner() {
    }

    // 第3层 装备前置用:铁甲四件 + 对应装备槽。
    private static final List<Item> IRON_ARMOR = List.of(
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
    // 规避加固:挖深矿前备的火把数(供 DangerWatcher 在地下黑暗处点亮防刷怪)。
    private static final int TORCH_TARGET = 8;
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    // 第4层 备粮:安全储备口径统一由 MiningFoodReserve 维护；生肉只是待加工中间品。
    private static final List<Item> RAW_MEAT_ITEMS = List.of(
            Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT);
    private static final int FOOD_TARGET = 4;
    /** Matches DescendToYTask's dark-shaft placement cadence. */
    private static final int DESCEND_TORCH_EVERY = 6;
    // 黑曜石远征补给是进入取水/钻石链前的硬门槛。食物按任务量缩放,公式与历史依据
    // (旧 24-item 门 5,284-tick 启动、8 单位地板)见 MiningBudget.obsidianExpeditionFoodTarget。
    private static final int OBSIDIAN_EXPEDITION_STONE_PICKS = 4;
    private static final int DESCEND_THRESHOLD = 8; // bot 高于矿层超过这么多格,先下竖井到矿层再挖
    private static final int SPARE_IRON_INGOTS = 3; // 深潜挖矿前多备 1 把铁镐的料(3 铁锭),镐磨穿时深处背包直接合新镐
    private static final int FOOD_GRASS_SCAN = 32;  // Goal.Food 择源:扫这个半径内有无草(种植面包链的种子来源)

    public record GoalPlan(Goal goal, List<GoalStep> steps, List<String> unresolved) {
        public boolean success() {
            return unresolved.isEmpty();
        }

        public String describeSteps() {
            List<String> parts = new ArrayList<>();
            for (GoalStep step : steps) {
                parts.add(step.describe());
            }
            return parts.toString();
        }
    }

    public static GoalPlan plan(AIPlayerEntity bot, Goal goal) {
        return plan(bot, goal, null, null);
    }

    public static GoalPlan plan(AIPlayerEntity bot, Goal goal, GoalSnapshotCollector.Context resumeContext) {
        return plan(bot, goal, resumeContext, null);
    }

    /**
     * Mission-aware live planning. The mission id is deliberately absent from the synthetic
     * planFromState entry points: an inventory-shaped unit fixture cannot attest ownership of a
     * physical mission depot and therefore must always retain the descent-kit service step.
     */
    static GoalPlan plan(AIPlayerEntity bot,
                         Goal goal,
                         GoalSnapshotCollector.Context resumeContext,
                         String missionId) {
        // Goal.Food 感知择源:规划时扫一眼周围实际有什么,据此选打猎/种植(见 ensureFoodTo),不再绑死打猎
        //(没动物的地形硬派打猎只会抓瞎)。其余目标不受这两个标志影响。
        boolean hasPrey = HuntTask.hasPreyNearby(bot);
        boolean hasGrass = OreProspector.nearest(bot,
                FOOD_GRASS_SCAN, GoalPlanner::isGrassForSeeds) != null;
        // 荒芜兜底源:针叶林等生物群系动物稀少(实测 hunt 漫游 10 次 1092t 仍 0 猎物)但常有甜浆果丛——
        // 无动物可猎时浆果是"能立刻吃上"的最后手段。
        boolean hasBerries = OreProspector.nearest(bot,
                FOOD_GRASS_SCAN, state -> state.isOf(Blocks.SWEET_BERRY_BUSH)) != null;
        // 附近矿感知:规划时扫一眼目标矿是否已在身边(48 格)。在 → 不下潜矿层直接挖
        //(站在铁矿旁还先挖 70 格竖井到 Y16 是蠢的;且竖井穿天然地形洞/水/沙砾极易 descend_blocked,
        // 实测场景地表化后 descend 类失败爆发,旧 y6 出生点 botY<mineY 恰好从不触发才一直没暴露)。
        java.util.function.Predicate<Set<Block>> oreNearby = ores -> {
            if (OreProspector.nearest(bot, 48,
                    state -> ores.contains(state.getBlock())) != null) {
                return true;
            }
            // 知识库第二意见(语义记忆消费口):实扫 48 格没有,但以前在 96 格内见过该矿 → 同样跳过下潜,
            // OreDigTask 的 prospect(64)+水平掘进能摸到——"记得哪里有"比"现在看得见"覆盖更广。
            for (Block ore : ores) {
                String id = Registries.BLOCK.getId(ore).toString();
                if (io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE
                        .nearestResource(bot.getUuid(), id, bot.getBlockPos(), 96).isPresent()) {
                    return true;
                }
            }
            return false;
        };
        return planFromState(bot, goal, inventoryCounts(bot), toolUsableDurability(bot),
                Math.max(1, AIBotConfig.get().goal().maxPlanDepth()), bot.getBlockPos().getY(),
                hasPrey, hasGrass, hasBerries, canAcquireSurfaceResources(bot),
                oreNearby, resumeContext, missionId);
    }

    /**
     * Surface-only work (trees, animals and crops) may be planned only while the bot is actually
     * near the terrain surface. Y alone is not sufficient: mountains and deep ravines make a fixed
     * threshold lie in both directions. The no-leaves heightmap gives a cheap, deterministic fact
     * and tolerates a small shelter/overhang without classifying a Y=16 mine as surface.
     */
    public static boolean canAcquireSurfaceResources(AIPlayerEntity bot) {
        net.minecraft.util.math.BlockPos origin = bot.getBlockPos();
        // Even an open ravine or isolated test canvas at deepslate height has sky visibility but
        // no trees, animals or crops at the work face. Keep the heightmap test, with a conservative
        // lower bound that only rules out unequivocal deep-mine positions.
        if (origin.getY() < 32) {
            return false;
        }
        for (int dx = -8; dx <= 8; dx += 4) {
            for (int dz = -8; dz <= 8; dz += 4) {
                int topY = bot.getServerWorld().getTopY(
                        Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                        origin.getX() + dx,
                        origin.getZ() + dz);
                if (Math.abs(topY - origin.getY()) <= 8) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Planning entry point once world perception has been reduced to facts. Keeping this boundary separate makes
     * non-build dependency-chain regressions testable without constructing a live server player.
     */
    static GoalPlan planFromState(AIPlayerEntity bot,
                                  Goal goal,
                                  Map<Item, Integer> inventory,
                                  int maxDepth,
                                  int botY,
                                  boolean hasPrey,
                                  boolean hasGrass,
                                  boolean hasBerries,
                                  java.util.function.Predicate<Set<Block>> oreNearby,
                                  GoalSnapshotCollector.Context resumeContext) {
        return planFromState(bot, goal, inventory, maxDepth, botY,
                hasPrey, hasGrass, hasBerries, botY >= 48, oreNearby, resumeContext);
    }

    static GoalPlan planFromState(AIPlayerEntity bot,
                                  Goal goal,
                                  Map<Item, Integer> inventory,
                                  int maxDepth,
                                  int botY,
                                  boolean hasPrey,
                                  boolean hasGrass,
                                  boolean hasBerries,
                                  boolean surfaceAcquisitionAllowed,
                                  java.util.function.Predicate<Set<Block>> oreNearby,
                                  GoalSnapshotCollector.Context resumeContext) {
        return planFromState(bot, goal, inventory,
                assumedFreshToolUsableDurability(inventory), maxDepth, botY,
                hasPrey, hasGrass, hasBerries, surfaceAcquisitionAllowed,
                oreNearby, resumeContext);
    }

    static GoalPlan planFromState(AIPlayerEntity bot,
                                  Goal goal,
                                  Map<Item, Integer> inventory,
                                  Map<Item, Integer> toolUsableDurability,
                                  int maxDepth,
                                  int botY,
                                  boolean hasPrey,
                                  boolean hasGrass,
                                  boolean hasBerries,
                                  boolean surfaceAcquisitionAllowed,
                                  java.util.function.Predicate<Set<Block>> oreNearby,
                                  GoalSnapshotCollector.Context resumeContext) {
        return planFromState(bot, goal, inventory, toolUsableDurability,
                maxDepth, botY, hasPrey, hasGrass, hasBerries,
                surfaceAcquisitionAllowed, oreNearby, resumeContext, null);
    }

    private static GoalPlan planFromState(AIPlayerEntity bot,
                                          Goal goal,
                                          Map<Item, Integer> inventory,
                                          Map<Item, Integer> toolUsableDurability,
                                          int maxDepth,
                                          int botY,
                                          boolean hasPrey,
                                          boolean hasGrass,
                                          boolean hasBerries,
                                          boolean surfaceAcquisitionAllowed,
                                          java.util.function.Predicate<Set<Block>> oreNearby,
                                          GoalSnapshotCollector.Context resumeContext,
                                          String missionId) {
        boolean restrictSurfaceAcquisition = !surfaceAcquisitionAllowed
                && (goal instanceof Goal.MineOre
                || goal instanceof Goal.HaveItem haveItem
                && (haveItem.item() == Items.DIAMOND || haveItem.item() == Items.OBSIDIAN));
        Planner planner = new Planner(bot, new HashMap<>(inventory), Math.max(1, maxDepth), botY,
                hasPrey, hasGrass, hasBerries, surfaceAcquisitionAllowed,
                restrictSurfaceAcquisition, oreNearby, resumeContext,
                toolUsableDurability, missionId);
        planner.ensureGoal(goal, 0, new HashSet<>());
        return new GoalPlan(goal, List.copyOf(mergeGathers(planner.steps)), List.copyOf(planner.unresolved));
    }

    // 割草取种子的草类(种植面包链的起点):附近有草,才把"种植"作为无动物时的食物源。
    private static boolean isGrassForSeeds(BlockState state) {
        return state.isOf(Blocks.SHORT_GRASS) || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN) || state.isOf(Blocks.LARGE_FERN);
    }

    // 第A层 集中采集(挖钻石失败根因修复):没有地表局部任务时,把所有 GATHER 同类需求合并并提到
    // 计划最前。HUNT 是例外:动物会移动/消失,从零链必须先采最小木料做剑并就地完成首个 bounded
    // 捕猎批次，再批量采后续原木；否则先砍几十根燃料会把 bot 带离出生点羊群(seed 3000 实测)。
    // 但不能等最后一个 HUNT 批次：第二轮猎食可能远征数百格，把 bot 带到无林草原后才要求 18 根
    // 燃料木。首轮 HUNT 后的 GATHER 立即集中，剩余 HUNT/COOK 保持原顺序。
    // 深层贵重矿的最佳挖掘高度(1.18+ 地形);非深层矿返回 MAX_VALUE(不触发"先下矿层")。
    private static int bestMiningY(Set<Block> ores) {
        return MiningChain.bestY(ores); // S2:推荐挖掘 Y 层收敛到 MiningChain 单一数据源(混合矿取最深层)
    }

    private static List<GoalStep> mergeGathers(List<GoalStep> steps) {
        int huntIndex = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).kind() == GoalStep.Kind.HUNT) {
                huntIndex = i;
                break;
            }
        }
        if (huntIndex < 0) {
            return mergeGatherSegment(steps, 0);
        }

        // Preserve the exact prerequisite prefix through the first bounded HUNT batch
        // (minimal logs → table/sticks/sword → one local hunt). Every later GATHER remains
        // dependency-free, so batch that suffix before any second hunt can leave the forest.
        List<GoalStep> result = new ArrayList<>(steps.subList(0, huntIndex + 1));
        result.addAll(mergeGatherSegment(steps.subList(huntIndex + 1, steps.size()), 0));
        return result;
    }

    private static List<GoalStep> mergeGatherSegment(List<GoalStep> steps, int insertAt) {
        // GATHER:无前置依赖 → 同段合并并前移。MINE 不跨步骤合并：它可能横跨
        // “木镐挖首批 3 石→合石镐→石镐挖大批石料”的工具升级边界。
        record GatherKey(Item item, boolean bestEffort) {}
        Map<GatherKey, Integer> gatherTotals = new LinkedHashMap<>();
        for (GoalStep step : steps) {
            if (step.kind() == GoalStep.Kind.GATHER) {
                gatherTotals.merge(new GatherKey(step.item(), step.bestEffort()), step.count(), Integer::sum);
            }
        }
        if (gatherTotals.isEmpty()) {
            return new ArrayList<>(steps);
        }
        List<GoalStep> result = new ArrayList<>();
        result.addAll(steps.subList(0, Math.min(insertAt, steps.size())));
        for (Map.Entry<GatherKey, Integer> entry : gatherTotals.entrySet()) {
            GoalStep gathered = GoalStep.gather(entry.getKey().item(), entry.getValue());
            result.add(entry.getKey().bestEffort() ? gathered.asBestEffort() : gathered);
        }
        for (int i = Math.min(insertAt, steps.size()); i < steps.size(); i++) {
            GoalStep step = steps.get(i);
            if (step.kind() == GoalStep.Kind.GATHER) {
                continue; // 已提到最前
            }
            result.add(step);
        }
        return result;
    }

    public static List<GoalStep> planSteps(AIPlayerEntity bot, Goal goal) {
        return plan(bot, goal).steps();
    }

    private static Map<Item, Integer> inventoryCounts(AIPlayerEntity bot) {
        Map<Item, Integer> counts = new HashMap<>();
        for (ItemStack stack : bot.getInventory().main) {
            add(counts, stack);
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            add(counts, stack);
        }
        // 第3层:计入已穿装备槽,避免"已穿铁甲"被 ensureArmor 当成缺失而重复制作。
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            add(counts, bot.getEquippedStack(slot));
        }
        return counts;
    }

    private static void add(Map<Item, Integer> counts, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        // 即将断的工具/装备(与 ToolSelector 同阈值 damage>=max-1)不计入库存:选镐保全根本不会用它,
        // planner 若把它当"有镐"就不补新的 → 挖矿 need_better_tool 与"已有镐"死锁(real_armor 实测:
        // 挖26铁把石镐磨到将断,被当"有石镐"不补 → mine_ore 反复 need_better_tool:stone_pickaxe 失败)。
        // 不计 → ensurePickaxeTier 用背包圆石补一把新石镐,选镐保全改用新的,链路续上。
        if (stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1) {
            return;
        }
        counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
    }

    private static Map<Item, Integer> toolUsableDurability(AIPlayerEntity bot) {
        Map<Item, Integer> durability = new HashMap<>();
        for (ItemStack stack : bot.getInventory().main) {
            addToolUsableDurability(durability, stack);
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            addToolUsableDurability(durability, stack);
        }
        return durability;
    }

    private static void addToolUsableDurability(Map<Item, Integer> durability, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageable()) {
            return;
        }
        durability.merge(stack.getItem(), MiningServiceTask.usableDurability(stack),
                GoalPlanner::saturatedAdd);
    }

    private static Map<Item, Integer> assumedFreshToolUsableDurability(
            Map<Item, Integer> inventory) {
        Map<Item, Integer> durability = new HashMap<>();
        inventory.forEach((item, count) -> {
            int fresh = freshUsableDurability(item);
            if (fresh > 0 && count > 0) {
                durability.put(item, saturatedMultiply(fresh, count));
            }
        });
        return durability;
    }

    private static int freshUsableDurability(Item item) {
        ItemStack stack = new ItemStack(item);
        return stack.isDamageable() ? Math.max(0, stack.getMaxDamage() - 1) : 0;
    }

    private static int saturatedAdd(int left, int right) {
        return (int) Math.min(Integer.MAX_VALUE, (long) left + Math.max(0, right));
    }

    private static int saturatedMultiply(int left, int right) {
        return (int) Math.min(Integer.MAX_VALUE,
                (long) Math.max(0, left) * Math.max(0, right));
    }

    private static final class Planner {
        private final AIPlayerEntity bot;
        private final Map<Item, Integer> counts;
        private final Map<Item, Integer> initialCounts;
        private final Map<Item, Integer> initialToolUsableDurability;
        private final int maxDepth;
        // Planning changes the bot's physical layer. In particular ACQUIRE_WATER returns a deep
        // worker to the mission origin before the next dependency is executed, so a fixed initial
        // Y can incorrectly schedule a Y16 iron mine directly after the surface return.
        private int plannedY;
        private final int waterReturnY;
        private boolean initialOrePerceptionValid = true;
        private final boolean hasPreyNearby;  // 周围有可猎动物(食物择源:有→打猎)
        private final boolean hasGrassNearby; // 周围有草(食物择源:无动物但有草→种植面包)
        private final boolean hasBerriesNearby; // 周围有甜浆果丛(食物择源:无动物无现成粮→采浆果兜底)
        private final boolean surfaceAcquisitionAllowed;
        private final boolean restrictSurfaceAcquisition;
        private final java.util.function.Predicate<Set<Block>> oreNearby; // 目标矿是否已在身边(48格)→跳过下潜
        private final GoalSnapshotCollector.Context resumeContext;
        private final String missionId;
        private final List<GoalStep> steps = new ArrayList<>();
        private final List<String> unresolved = new ArrayList<>();
        private int bestEffortDepth;
        private int suppressOrdinaryTorchProvisionDepth;
        /** -1 means not opened; otherwise the still-unprovisioned portion of the 14-log seal. */
        private int surfaceEmergencyShelterWoodPending = -1;

        private Planner(AIPlayerEntity bot, Map<Item, Integer> counts, int maxDepth, int botY,
                        boolean hasPreyNearby, boolean hasGrassNearby, boolean hasBerriesNearby,
                        boolean surfaceAcquisitionAllowed,
                        boolean restrictSurfaceAcquisition,
                        java.util.function.Predicate<Set<Block>> oreNearby,
                        GoalSnapshotCollector.Context resumeContext,
                        Map<Item, Integer> toolUsableDurability,
                        String missionId) {
            this.bot = bot;
            this.counts = counts;
            this.initialCounts = Map.copyOf(counts);
            this.initialToolUsableDurability = toolUsableDurability == null
                    ? Map.of() : Map.copyOf(toolUsableDurability);
            this.maxDepth = maxDepth;
            this.plannedY = botY;
            this.waterReturnY = resumeContext == null ? botY : resumeContext.origin().getY();
            this.hasPreyNearby = hasPreyNearby;
            this.hasGrassNearby = hasGrassNearby;
            this.hasBerriesNearby = hasBerriesNearby;
            this.surfaceAcquisitionAllowed = surfaceAcquisitionAllowed;
            this.restrictSurfaceAcquisition = restrictSurfaceAcquisition;
            this.oreNearby = oreNearby;
            this.resumeContext = resumeContext;
            this.missionId = missionId == null ? "" : missionId;
        }

        private boolean ensureGoal(Goal goal, int depth, Set<String> visiting) {
            if (depth > maxDepth) {
                unresolved.add("max_depth:" + goal);
                return false;
            }
            return switch (goal) {
                case Goal.HaveItem haveItem -> ensureItem(haveItem.item(), haveItem.count(), depth, visiting);
                case Goal.HavePickaxeTier havePickaxeTier -> ensurePickaxeTier(havePickaxeTier.tier(), depth, visiting);
                case Goal.MineOre mineOre -> ensureMineOre(mineOre.ores(), mineOre.count(), depth, visiting);
                case Goal.HarvestCrop harvestCrop -> ensureHarvestCrop(harvestCrop, depth, visiting);
                case Goal.Armor ignored -> ensureArmor(true, depth, visiting);
                case Goal.Workstation ignored -> ensureWorkstation(depth, visiting);
                case Goal.Stockpile stockpile -> ensureStockpile(stockpile, depth, visiting);
                case Goal.Food food -> ensureFoodTo(food.cookedCount(), depth, visiting);
                case Goal.Build build -> ensureBuild(build, depth, visiting);
            };
        }

        // P3:收获作物——已有足量产出则空;否则倒推锄头(任意木镐档锄,这里用 wooden_hoe)+ 种子,再下 FARM 步。
        private boolean ensureHarvestCrop(Goal.HarvestCrop g, int depth, Set<String> visiting) {
            int owned = counts.getOrDefault(g.produce(), 0);
            int remaining = Math.max(0, g.count() - owned);
            if (remaining <= 0) {
                return true;
            }
            // 锄头:背包没有任意锄就倒推一把木锄(FarmAction 用任意 HoeItem,木锄足矣)。
            if (!hasAnyHoe() && !ensureItem(Items.WOODEN_HOE, 1, depth + 1, visiting)) {
                return false;
            }
            ensureSeeds(g.seed(), g.produce(), remaining, depth, visiting); // 种田前先确保种子
            addStep(GoalStep.farm(g.crop(), g.seed(), g.produce(), remaining));
            counts.merge(g.produce(), remaining, Integer::sum);
            return true;
        }

        // farm 前确保种子:种子与产出不同(如小麦种子≠小麦)且不足 → 倒推获取(小麦种子走割草);
        // 种子=产出自身(carrot/potato)则不倒推(否则 produce→farm→seed→produce 死循环),交 FarmTask 运行期就地找。
        private void ensureSeeds(Item seed, Item produce, int count, int depth, Set<String> visiting) {
            if (seed == null || seed == produce) {
                return;
            }
            int have = counts.getOrDefault(seed, 0);
            if (have < count) {
                ensureItem(seed, count - have, depth + 1, visiting);
            }
        }

        private boolean hasAnyHoe() {
            return counts.getOrDefault(Items.WOODEN_HOE, 0) > 0
                    || counts.getOrDefault(Items.STONE_HOE, 0) > 0
                    || counts.getOrDefault(Items.IRON_HOE, 0) > 0
                    || counts.getOrDefault(Items.DIAMOND_HOE, 0) > 0
                    || counts.getOrDefault(Items.GOLDEN_HOE, 0) > 0
                    || counts.getOrDefault(Items.NETHERITE_HOE, 0) > 0;
        }

        // 打猎要有武器(空手攻击力仅 1,低效甚至追不上动物)。背包/计划里有任意剑即可。
        private boolean hasAnySword() {
            return counts.getOrDefault(Items.WOODEN_SWORD, 0) > 0
                    || counts.getOrDefault(Items.STONE_SWORD, 0) > 0
                    || counts.getOrDefault(Items.IRON_SWORD, 0) > 0
                    || counts.getOrDefault(Items.DIAMOND_SWORD, 0) > 0
                    || counts.getOrDefault(Items.GOLDEN_SWORD, 0) > 0
                    || counts.getOrDefault(Items.NETHERITE_SWORD, 0) > 0;
        }

        private boolean ensurePickaxeTier(int tier, int depth, Set<String> visiting) {
            if (bestPickaxeTier() >= tier) {
                return true;
            }
            Item pickaxe = pickaxeForTier(tier);
            if (pickaxe == Items.AIR) {
                return true;
            }
            return ensureItem(pickaxe, 1, depth + 1, visiting);
        }

        private boolean ensureMineOre(Set<Block> ores, int count, int depth, Set<String> visiting) {
            Set<Block> expanded = ores == null || ores.isEmpty() ? OreScan.COMMON_ORES : OreScan.expandOreFamilies(ores);
            Set<Item> drops = io.github.zoyluo.aibot.action.HarvestCore.expectedDropsFor(expanded);
            int owned = countAny(drops);
            int remaining = Math.max(0, count - owned);
            if (remaining <= 0) {
                return true;
            }
            int tier = ToolTier.requiredPickaxeTier(expanded);
            boolean rareOre = expanded.contains(Blocks.DIAMOND_ORE)
                    || expanded.contains(Blocks.DEEPSLATE_DIAMOND_ORE)
                    || expanded.contains(Blocks.EMERALD_ORE)
                    || expanded.contains(Blocks.DEEPSLATE_EMERALD_ORE);
            boolean coalOre = expanded.contains(Blocks.COAL_ORE)
                    || expanded.contains(Blocks.DEEPSLATE_COAL_ORE);
            MiningBudget budget = MiningBudget.forQuota(remaining, rareOre, tier);
            // Mission identity is the requested total, not the current deficit. A 64-diamond
            // expedition with 63 already collected must retain its service/tool contract instead
            // of silently degrading into a one-off local mine after resume.
            MiningBudget missionBudget = MiningBudget.forQuota(count, rareOre, tier);
            boolean expedition = count >= MiningBudget.EXPEDITION_THRESHOLD;
            boolean longRareExpedition = expedition && rareOre;
            int mineY = bestMiningY(expanded);
            // A long rare-ore expedition owns a stable optimal layer. One exposed ore must not keep
            // a 64-item mission branch-mining at the surface; small requests retain the local shortcut.
            // oreNearby is a fact about the position at which this plan was created. Once an
            // earlier planned step has changed layers, reusing it can suppress a required descent.
            boolean knownOreNearby = initialOrePerceptionValid && oreNearby.test(expanded);
            boolean willDescend = plannedY - mineY > DESCEND_THRESHOLD
                    && (longRareExpedition || !knownOreNearby);
            boolean ordinaryChannelMission = !rareOre
                    && budget.ordinaryChannelPickaxes() > 0;
            // An ordinary expedition owns the same four-pick service horizon after a mine-layer
            // replan. Tying this flag to willDescend made a resumed coal/iron batch silently lose
            // channel maintenance as soon as it was already standing at the target Y.
            boolean maintainTunnelingTools = longRareExpedition || ordinaryChannelMission;
            // A live descent-kit attestation is valid only if no dependency is appended after this
            // snapshot. Coal/iron/torch provisioning can consume slots or reserved materials even
            // when the inventory happened to satisfy the kit at the beginning of this plan.
            int rareBootstrapStart = longRareExpedition ? steps.size() : -1;

            if (longRareExpedition) {
                // Surface readiness is a hard gate before descent. Once the sealed descent kit
                // hands off to the mine, its protected stone ledger supersedes this phase-scoped
                // wood reserve; an underground resume must never emit a wood top-up.
                boolean reserveSurfaceShelter = count >= 64 && surfaceAcquisitionAllowed;
                if (reserveSurfaceShelter) {
                    beginSurfaceEmergencyShelterWoodReserve();
                }
                int unresolvedBefore = unresolved.size();
                if (!ensureMiningFoodReserveTo(
                        missionBudget.cookedFoodTarget(),
                        MiningBudget.RARE_SERVICE_FOOD_FLOOR,
                        depth + 1, visiting)
                        || unresolved.size() > unresolvedBefore) {
                    return false;
                }
                // Keep the first bounded local hunt ahead of bulk wood collection; after food has
                // established that ordering, seal the emergency reserve before tool/fuel/service
                // dependencies are allowed to borrow it.
                if (reserveSurfaceShelter
                        && !reserveSurfaceEmergencyShelterWood(depth + 1, visiting)) {
                    return false;
                }
            }
            if (longRareExpedition && tier == ToolTier.IRON) {
                // Acquire the complete iron contract once. The old sequential chain first made one
                // pick (3 ingots), then two more (6), then the six-ingot spare. Those three small
                // OreDig missions each fell below the ordinary-channel threshold and reused one
                // increasingly damaged stone pick. One >=15 from-zero acquisition owns one finite
                // ordinary pool/service horizon and one smelt transaction.
                int missingTargetPicks = Math.max(0,
                        missionBudget.initialPickaxes()
                                - counts.getOrDefault(Items.IRON_PICKAXE, 0));
                int aggregatedIronTarget = saturatedAdd(
                        saturatedMultiply(missingTargetPicks, 3),
                        missionBudget.spareToolIngots());
                boolean ironReady;
                suppressOrdinaryTorchProvisionDepth++;
                try {
                    ironReady = ensureItem(Items.IRON_INGOT,
                            aggregatedIronTarget, depth + 1, visiting);
                } finally {
                    suppressOrdinaryTorchProvisionDepth--;
                }
                if (!ironReady) {
                    return false;
                }
            }
            if (!ensurePickaxeTier(tier, depth + 1, visiting)) {
                return false;
            }
            if (expedition) {
                // 首趟按配额准备多把镐；批次间 service 会回仓/就地补给，避免一次性把整趟远征
                // 的所有消耗都塞进 bootstrap。钻石目标所需的是铁镐，不会消耗目标钻石。
                Item expeditionPickaxe = pickaxeForTier(tier);
                if (expeditionPickaxe != Items.AIR
                        && !ensureItem(expeditionPickaxe, missionBudget.initialPickaxes(), depth + 1, visiting)) {
                    return false;
                }
                if (missionBudget.spareToolIngots() > 0
                        && !ensureItem(Items.IRON_INGOT, missionBudget.spareToolIngots(), depth + 1, visiting)) {
                    return false;
                }
                // 火把和封堵块只在地表 bootstrap 补齐。地下恢复时重放这两个目标会把
                // 已消耗的储备翻译成 DigDown/砍树前置，反而阻塞下一批；地下不足由 service
                // checkpoint 从仓点补给或明确 fail-closed。
                if (surfaceAcquisitionAllowed || willDescend) {
                    if (longRareExpedition) {
                        int descentTorchReserve = willDescend
                                ? descendTorchBudget(plannedY, mineY) : 0;
                        int requiredTorches = roundUpToTorchRecipe(
                                saturatedAdd(missionBudget.torchTarget(), descentTorchReserve));
                        if (missionBudget.targetCount() >= 64) {
                            requiredTorches = Math.max(requiredTorches,
                                    MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES);
                        }
                        if (!ensureTorchesTo(requiredTorches, depth + 1, visiting)) {
                            return false;
                        }
                    } else {
                        // Coal is the torch ingredient. Asking a coal expedition to provision its
                        // own normal torch target recursively emitted coal8 -> coal12 -> coal96,
                        // each with another independent unstackable channel pool. Mine the bounded
                        // target coal once; the parent torch craft immediately follows it.
                        if (!coalOre && suppressOrdinaryTorchProvisionDepth == 0) {
                            bestEffortProvision(() -> ensureTorchesTo(
                                    missionBudget.torchTarget(), depth + 1, visiting));
                        }
                        bestEffortProvision(() -> ensureItem(
                                Items.COBBLESTONE, missionBudget.emergencyBlocks(), depth + 1, visiting));
                    }
                }
            }
            // 大批量挖矿备足镐(治本·real_armor 26铁挖到9超时):石镐~131耐久,挖24+铁含掘进要磨断多把。
            // 途中磨穿→resupply 就地合会打断大配额单 mine_ore、丢挖矿进度→ore_dig_timeout。按量预备(含掘进≈1把/12块)
            // 一把磨穿换备用、不中断,一趟挖完。仅 STONE 档(挖铁/铜,圆石无限廉价)预备;IRON 档(钻石)已由备铁锭兜。
            if (tier <= ToolTier.STONE && remaining >= 12) {
                // 扣除已有的更高档镐(治 real_iron_bulk:预装 5 铁镐≈9 石镐当量,原样按 1+100/12=9 无脑备石镐
                // → 倒推砍木 → 无树地形 need_oak_planks 速死)。只备缺口;从零(无高档镐)照旧,不破 real_armor 26 铁场景。
                int needPicks = 1 + remaining / 12;
                int nonStoneEquiv = counts.getOrDefault(Items.IRON_PICKAXE, 0) * 250 / 131
                        + counts.getOrDefault(Items.DIAMOND_PICKAXE, 0) * 1561 / 131
                        + counts.getOrDefault(Items.NETHERITE_PICKAXE, 0) * 2031 / 131;
                int stoneNeeded = needPicks - nonStoneEquiv;
                if (stoneNeeded > 0) {
                    ensureItem(pickaxeForTier(tier), stoneNeeded, depth + 1, visiting);
                }
            }
            // 小配额深矿沿用轻量快速链，只补火把；长期稀有矿远征已在上方走硬食物 readiness，
            // 但仍不强制铁甲/盾牌，避免把矿前 bootstrap 扩成另一条重型装备任务。
            if (tier >= ToolTier.IRON && remaining < MiningBudget.EXPEDITION_THRESHOLD) {
                ensureTorches(depth + 1, visiting); // 小配额沿用轻量快速链
            }
            // 挖深层矿重构 P1:bot 远高于矿层 → 先下竖井到矿层,再挖。否则在错误高度(实测 Y=48)
            // 反复"锁定斜下方够不到的矿→水平掘隧道→dist 卡死→no_progress",卡死 11 分钟。
            // 深潜耐久兜底(治本·real_diamond 手测死因):深处铁镐磨穿后无法就地补给——深层没树做熔炉/燃料,
            // resupply 倒推"采橡木→合熔炉→熔炼铁锭"在 Y<0 必败(96 格无树)→ 反复 replan 卡死被怪杀。
            // 解法:深潜前多备 3 铁锭备料(地表一次性多挖/熔炼好)。镐磨穿时深处直接用备料+背包工作台+棍合新镐
            //(只需 craft,无需树/熔炉/熔炼),不被困死深处。仅深潜才备(就近挖在地表附近,坏了能正常补)。
            if (tier >= ToolTier.IRON && willDescend
                    && remaining < MiningBudget.EXPEDITION_THRESHOLD) {
                ensureItem(Items.IRON_INGOT, SPARE_IRON_INGOTS, depth + 1, visiting);
                // 【实验回退】带铁套加成回攻钻石实测净拖累(real_diamond 0/6 vs 精简基线3/6):深潜前备头胸甲(13铁)
                // 把链拉太长——bot 在36000t内多挖13铁+熔炼+合甲、还没下潜挖钻就超时(5/6 timeout),且铁甲一次没穿上
                // (前段就败、根本没走到深潜)。survival收益=0、代价=链翻倍。故撤回备甲,深潜survival改靠反应式
                // (入浆自救/濒死入土/点火把,遇险才花代价)。下潜穿甲(DescendToYTask.onStart equipBestArmor)保留:零成本,有甲就穿。
            }
            if (ordinaryChannelMission
                    && !ensureFreshOrdinaryChannelKit(budget, depth + 1, visiting)) {
                return false;
            }
            // Dependency planning above can itself move the simulated worker. Coal is the concrete
            // case: provisioning torches for a larger coal batch recursively mines an initial coal
            // batch and already descends to Y=48. Reusing the pre-dependency willDescend decision
            // then emitted a second Y=48 hand-off; a vein ending at Y=47 made that redundant task
            // fail as an overshoot even though the worker was already in the correct layer band.
            // Keep DescendToYTask fail-closed for real pose drift and only suppress this stale step.
            if (willDescend && plannedY - mineY > DESCEND_THRESHOLD) {
                if (longRareExpedition) {
                    boolean exactLiveKit = count == 64
                            && bot != null
                            && !missionId.isBlank()
                            && steps.size() == rareBootstrapStart
                            && MiningServiceTask.rareDescentKitReady(bot)
                            && MiningServiceTask.ownedMissionDepot(bot, missionId);
                    if (!exactLiveKit) {
                        boolean kitReady = count == 64
                                ? ensureRareDescentKit(expanded, count,
                                missionBudget, depth + 1, visiting)
                                : ensureDirectRareDescentKit(missionBudget,
                                depth + 1, visiting);
                        if (!kitReady) {
                            return false;
                        }
                    }
                }
                // DescendToY may place one torch at the dark starting face and then every six
                // vertical levels. Debit that worst-case use now so a later dependency cannot treat
                // already-promised torches as its inventory baseline.
                int descentTorches = descendTorchBudget(plannedY, mineY);
                addStep(GoalStep.descendToY(mineY));
                consumeItem(Items.TORCH, descentTorches);
                plannedY = mineY;
                initialOrePerceptionValid = false;
            }
            int rareBatchOffset = longRareExpedition
                    ? Math.floorMod(owned, budget.batchSize()) : 0;
            if (longRareExpedition && rareBatchOffset == 0) {
                // The first rare batch always owns a boundary service after the final descent.
                // From-zero missions use boundary 0 and a synthetic cursor supplied by Executor;
                // completed-batch resumes use their exact eight-item boundary. A partial open
                // batch must resume its existing cursor/resource ledgers before the next boundary;
                // servicing at owned=4 would silently split one logical batch into two.
                steps.add(GoalStep.rareOreService(
                        expanded, owned, count));
            }
            if (longRareExpedition) {
                int cumulative = 0;
                int firstBatchTarget = rareBatchOffset == 0
                        ? budget.batchSize() : budget.batchSize() - rareBatchOffset;
                while (cumulative < remaining) {
                    int batchTarget = Math.min(
                            cumulative == 0 ? firstBatchTarget : budget.batchSize(),
                            remaining - cumulative);
                    // Direct append is intentional: addStep would merge adjacent ore steps and
                    // erase the durable eight-item service boundaries.
                    steps.add(GoalStep.mineOre(expanded, batchTarget));
                    cumulative += batchTarget;
                    if (cumulative < remaining) {
                        steps.add(GoalStep.rareOreService(
                                expanded, owned + cumulative, count));
                    }
                }
            } else if (remaining >= MiningBudget.EXPEDITION_THRESHOLD) {
                int cumulative = 0;
                for (int batchIndex = 0; batchIndex < budget.batchCount(); batchIndex++) {
                    int batchTarget = budget.batchTarget(batchIndex);
                    if (batchTarget <= 0) {
                        continue;
                    }
                    // 直接 append，不能走 addStep：相邻同矿种步骤会被合并回一个 64 配额巨型 Task。
                    steps.add(GoalStep.mineOre(expanded, batchTarget));
                    cumulative += batchTarget;
                    if (batchIndex + 1 < budget.batchCount()) {
                        steps.add(GoalStep.miningService(
                                expanded, cumulative, maintainTunnelingTools));
                    }
                }
            } else {
                addStep(GoalStep.mineOre(expanded, remaining));
            }
            for (Item drop : drops) {
                counts.merge(drop, remaining, Integer::sum);
                break;
            }
            if (ordinaryChannelMission) {
                // These items remain physically carried for this ordinary mission, but they are no
                // longer available to later coal/iron/rare dependencies in the symbolic plan. This
                // is the ownership boundary that prevents the final rare kit from borrowing an
                // earlier ordinary service horizon.
                consumeItem(Items.STONE_PICKAXE, budget.ordinaryChannelPickaxes());
                consumeItem(Items.STICK, budget.ordinaryChannelRepairSticks());
                consumeItem(Items.COBBLESTONE, budget.ordinaryChannelRepairStoneLike());
                // Inter-batch service protects the next OreDig batch, but the final ordinary
                // batch hands control back to a parent craft/smelt/descent. Its unpredictable
                // spoil mix can occupy every main slot even when the target drop itself stacks.
                // Seal that runtime boundary explicitly without charging another four-pick
                // rebuild. The stone reserve is the symbolic inventory after this ordinary
                // mission's exact debit; preserving it keeps the downstream obsidian/rare plan
                // truthful while surplus mining spoil remains disposable.
                steps.add(GoalStep.miningHandoffService(
                        expanded, owned + remaining, plannedStoneLikeCount()));
            }
            return true;
        }

        private int plannedStoneLikeCount() {
            return saturatedAdd(counts.getOrDefault(Items.COBBLESTONE, 0),
                    saturatedAdd(counts.getOrDefault(Items.COBBLED_DEEPSLATE, 0),
                            counts.getOrDefault(Items.BLACKSTONE, 0)));
        }

        /**
         * Provisions one finite ordinary channel mission. Four fresh picks cover the initial
         * descent/working face. The remaining raw materials fund the exact bounded contract from
         * {@link MiningBudget}: one one-pick physical resupply per open batch and one four-pick
         * rebuild at every inter-batch service. The incremental craft is deliberate; item count
         * alone cannot prove that five carried picks have usable durability.
         */
        private boolean ensureFreshOrdinaryChannelKit(MiningBudget budget,
                                                      int depth,
                                                      Set<String> visiting) {
            int freshPickaxes = budget.ordinaryChannelPickaxes();
            if (freshPickaxes <= 0) {
                return true;
            }
            int requiredUsable = saturatedMultiply(
                    freshPickaxes, MiningBudget.STONE_PICKAXE_USABLE_DURABILITY);
            boolean currentPoolAttested = counts.getOrDefault(Items.STONE_PICKAXE, 0)
                    >= freshPickaxes
                    && initialToolUsableDurability.getOrDefault(Items.STONE_PICKAXE, 0)
                    >= requiredUsable;
            int craftCount = currentPoolAttested ? 0 : freshPickaxes;
            int craftSticks = saturatedMultiply(
                    craftCount, MiningBudget.STONE_PICKAXE_STICK_COST);
            int craftStone = saturatedMultiply(
                    craftCount, MiningBudget.STONE_PICKAXE_HEAD_COST);
            int requiredSticks = saturatedAdd(
                    budget.ordinaryChannelRepairSticks(), craftSticks);
            int requiredStone = saturatedAdd(
                    budget.ordinaryChannelRepairStoneLike(), craftStone);
            if (!ensureItem(Items.CRAFTING_TABLE, 1, depth + 1, visiting)
                    // Mine every stone-like dependency before sealing the handle reserve. Stone
                    // acquisition can itself open a bounded channel repair and spend sticks; doing
                    // it afterwards would let that nested mission borrow the final handle pool.
                    || !ensureItem(Items.COBBLESTONE, requiredStone, depth + 1, visiting)
                    || !ensureItem(Items.STICK, requiredSticks, depth + 1, visiting)) {
                return false;
            }
            if (craftCount > 0) {
                appendFreshStonePickaxeCraft(craftCount, craftSticks, craftStone);
            }
            return true;
        }

        /**
         * Final sealed hand-off for a target64 rare expedition. All coal, iron, torch and
         * target-tool dependencies have already emitted their work. Provisioning 238 sticks, 77
         * stone-like and a mission chest lets runtime atomically retire old tools, craft five fresh
         * picks, and leave the exact 228/60 reserve immediately before final descent.
         */
        private boolean ensureRareDescentKit(Set<Block> ores,
                                             int missionTarget,
                                             MiningBudget budget,
                                             int depth,
                                             Set<String> visiting) {
            if (missionTarget != 64) {
                unresolved.add("rare_descent_kit_requires_target64:" + missionTarget);
                return false;
            }
            int freshPickaxes = budget.tunnelingPickaxes();
            int craftSticks = saturatedMultiply(
                    freshPickaxes, MiningBudget.STONE_PICKAXE_STICK_COST);
            int craftStone = saturatedMultiply(
                    freshPickaxes, MiningBudget.STONE_PICKAXE_HEAD_COST);
            int requiredSticks = saturatedAdd(budget.spareToolSticks(), craftSticks);
            int requiredStone = saturatedAdd(
                    saturatedAdd(budget.emergencyBlocks(), craftStone), 2);
            boolean ownedDepotReady = bot != null && !missionId.isBlank()
                    && MiningServiceTask.ownedMissionDepot(bot, missionId);
            if (freshPickaxes <= 0
                    || (!ownedDepotReady
                    && !ensureItem(Items.CHEST, 1, depth + 1, visiting))
                    || !ensureItem(Items.CRAFTING_TABLE, 1, depth + 1, visiting)
                    // The rare handle pool is the final sealed resource. Any stone acquisition
                    // (including its own ordinary channel service) must finish before this top-up.
                    || !ensureItem(Items.COBBLESTONE, requiredStone, depth + 1, visiting)
                    || !ensureItem(Items.STICK, requiredSticks, depth + 1, visiting)) {
                return false;
            }
            steps.add(GoalStep.rareDescentKitService(ores, missionTarget));
            // Runtime service atomically consumes one chest, five pick heads/handles and at most
            // two blocks for the sealed retirement pocket. Mirror that promise so downstream
            // symbolic planning cannot borrow resources that no longer remain in inventory.
            if (!ownedDepotReady) {
                consumeItem(Items.CHEST, 1);
            }
            consumeItem(Items.COBBLESTONE, saturatedAdd(craftStone, 2));
            consumeItem(Items.STICK, craftSticks);
            counts.merge(Items.STONE_PICKAXE, freshPickaxes, Integer::sum);
            return true;
        }

        /**
         * Targets below one full stack retain the established direct hand-off: provision the
         * bounded reserve and craft five fresh channel picks immediately before descent. They do
         * not own the target64 mission-local depot/schema contract.
         */
        private boolean ensureDirectRareDescentKit(MiningBudget budget,
                                                   int depth,
                                                   Set<String> visiting) {
            int freshPickaxes = budget.tunnelingPickaxes();
            int craftSticks = saturatedMultiply(
                    freshPickaxes, MiningBudget.STONE_PICKAXE_STICK_COST);
            int craftStone = saturatedMultiply(
                    freshPickaxes, MiningBudget.STONE_PICKAXE_HEAD_COST);
            int requiredSticks = saturatedAdd(budget.spareToolSticks(), craftSticks);
            int requiredStone = saturatedAdd(budget.emergencyBlocks(), craftStone);
            if (freshPickaxes <= 0
                    || !ensureItem(Items.CRAFTING_TABLE, 1, depth + 1, visiting)
                    || !ensureItem(Items.COBBLESTONE, requiredStone, depth + 1, visiting)
                    || !ensureItem(Items.STICK, requiredSticks, depth + 1, visiting)) {
                return false;
            }
            appendFreshStonePickaxeCraft(freshPickaxes, craftSticks, craftStone);
            return true;
        }

        /** Adds a runtime-incremental craft instead of an absolute item-count ensure. */
        private void appendFreshStonePickaxeCraft(int pickaxes, int sticks, int stone) {
            steps.add(GoalStep.craft(Items.STONE_PICKAXE, pickaxes));
            consumeItem(Items.STICK, sticks);
            consumeItem(Items.COBBLESTONE, stone);
            counts.merge(Items.STONE_PICKAXE, pickaxes, Integer::sum);
        }

        // 装备前置:库存或已穿都算(inventoryCounts 已计入装备槽)。
        // full=true(主动 achieve_armor):整套四件 + 铁剑。
        // full=false(挖矿前置,用户选"折中"):只备头盔+胸甲——挡掉大部分伤害,又让计划短一半、少很多失败点。
        private boolean ensureArmor(boolean full, int depth, Set<String> visiting) {
            List<Item> pieces = full ? IRON_ARMOR : List.of(Items.IRON_HELMET, Items.IRON_CHESTPLATE);
            // 一趟挖够(治本·real_armor 实测 no_stand_position_for_furnace):先把所有缺甲片/剑所需铁锭【一次性】
            // 备齐,合并成一次挖矿 + 一次熔炼。否则逐件分批挖→每件挖完铁回炉熔——深处挖完铁找不回地表那个远炉的
            // 落脚点就卡死(实测做完头盔、给胸甲熔铁时 no_stand_position_for_furnace)。真人也是一趟挖满再统一熔。
            int totalIron = 0;
            for (Item piece : pieces) {
                if (counts.getOrDefault(piece, 0) <= 0) {
                    totalIron += ironIngotCost(piece);
                }
            }
            if (full && counts.getOrDefault(Items.IRON_SWORD, 0) <= 0) {
                totalIron += ironIngotCost(Items.IRON_SWORD);
            }
            if (totalIron > 0) {
                ensureItem(Items.IRON_INGOT, totalIron, depth + 1, visiting); // 一次挖+熔够,后续合甲/剑直接消耗库存,不再分批回炉
            }
            for (Item piece : pieces) {
                if (counts.getOrDefault(piece, 0) <= 0 && !ensureItem(piece, 1, depth + 1, visiting)) {
                    return false;
                }
            }
            if (full && counts.getOrDefault(Items.IRON_SWORD, 0) <= 0
                    && !ensureItem(Items.IRON_SWORD, 1, depth + 1, visiting)) {
                return false;
            }
            return true;
        }

        // 算一件成品(甲/剑)配方里需要多少铁锭(用 RecipeRegistry,不硬编码——armorOf=单一 iron_ingot 配料,
        // sword=iron_ingot×2+棍)。供 ensureArmor 合并预备总铁量,实现"一趟挖满 26 铁再统一熔"。
        private int ironIngotCost(Item item) {
            return RecipeRegistry.find(item)
                    .map(r -> r.ingredients().stream()
                            .filter(ing -> ing.anyOf().contains(Items.IRON_INGOT))
                            .mapToInt(RecipeRegistry.Ingredient::count)
                            .sum())
                    .orElse(0);
        }

        // 规避加固:挖深矿(凶险)前备一批火把,供 DangerWatcher 在地下黑暗处点亮防刷怪。
        // best-effort:能倒推出火把(挖煤+棍)就加进计划;不阻断挖矿目标(有铁镐即能挖煤,基本必成)。
        private void ensureTorches(int depth, Set<String> visiting) {
            ensureTorchesTo(TORCH_TARGET, depth, visiting);
        }

        private boolean ensureTorchesTo(int target, int depth, Set<String> visiting) {
            if (target <= 0 || counts.getOrDefault(Items.TORCH, 0) >= target) {
                return true;
            }
            return ensureItem(Items.TORCH, target, depth + 1, visiting);
        }

        /**
         * Roll back an optional provisioning branch if planning cannot resolve it. If planning
         * succeeds, mark every emitted step so a world-time miss cannot fail the parent mining Goal.
         */
        private void bestEffortProvision(Runnable provision) {
            int stepsBefore = steps.size();
            int unresolvedBefore = unresolved.size();
            Map<Item, Integer> countsBefore = new HashMap<>(counts);
            bestEffortDepth++;
            try {
                provision.run();
            } finally {
                bestEffortDepth--;
            }
            if (unresolved.size() <= unresolvedBefore) {
                for (int i = stepsBefore; i < steps.size(); i++) {
                    steps.set(i, steps.get(i).asBestEffort());
                }
                return;
            }
            rollbackSteps(stepsBefore);
            while (unresolved.size() > unresolvedBefore) {
                unresolved.remove(unresolved.size() - 1);
            }
            counts.clear();
            counts.putAll(countsBefore);
        }

        // Phase2:基建——备齐工作台/熔炉/箱子各一,再下放置步(PlaceStationsTask 摆到 bot 周围)。
        private boolean ensureWorkstation(int depth, Set<String> visiting) {
            if (counts.getOrDefault(Items.CRAFTING_TABLE, 0) <= 0
                    && !ensureItem(Items.CRAFTING_TABLE, 1, depth + 1, visiting)) {
                return false;
            }
            if (counts.getOrDefault(Items.FURNACE, 0) <= 0
                    && !ensureItem(Items.FURNACE, 1, depth + 1, visiting)) {
                return false;
            }
            if (counts.getOrDefault(Items.CHEST, 0) <= 0
                    && !ensureItem(Items.CHEST, 1, depth + 1, visiting)) {
                return false;
            }
            addStep(GoalStep.placeStations());
            return true;
        }

        // Phase3:囤货——先获取够 count 个 item,再下 STOCKPILE 步把资源存进附近箱子(best-effort)。
        private boolean ensureStockpile(Goal.Stockpile g, int depth, Set<String> visiting) {
            net.minecraft.util.math.BlockPos base = resumeContext == null
                    ? io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE
                            .of(bot.getUuid()).placeIn(bot.getServerWorld(), "base").orElse(bot.getBlockPos())
                    : resumeContext.origin();
            GoalSnapshotCollector.Context stockpileContext = resumeContext == null
                    ? GoalSnapshotCollector.Context.at(base)
                    : resumeContext;
            GoalSnapshot snapshot = GoalSnapshotCollector.collect(
                    bot, g, stockpileContext);
            int alreadyDelivered = new GoalPredicate.Stockpile(
                    Registries.ITEM.getId(g.item()).toString(), g.count()).evaluate(snapshot).matched();
            int missing = Math.max(0, g.count() - alreadyDelivered);
            if (missing == 0) {
                return true;
            }
            if (!ensureItem(g.item(), missing, depth + 1, visiting)) {
                return false;
            }
            addStep(GoalStep.stockpile(g.item()));
            return true;
        }

        // 盖房全链:"盖房子"一句话 = 备料(自动砍树/挖石/熔玻璃,复用 ensureItem 倒推) + 建造一条链。
        // 材料统计口径:BlueprintLoader.load 已把 ops 全部展开成逐格 placements——
        // hollow_box=外壳格数、layer/box/fill=区间内全格数,且同坐标去重(显式 placement 覆盖 op 格,
        // 如 small_hut 门洞的两格 air 覆盖墙体),所以逐 placement 计数与 BuildTask 实际放置完全一致。
        private boolean ensureBuild(Goal.Build g, int depth, Set<String> visiting) {
            BlueprintSchema schema;
            try {
                schema = BlueprintLoader.load(g.blueprint());
            } catch (IOException e) {
                unresolved.add("blueprint_missing:" + g.blueprint());
                return false;
            }
            // 防御:万一拿到未展开 schema(load 已保证展开,这里仅保险)按同一几何再展开一次。
            if (schema.ops() != null && !schema.ops().isEmpty()) {
                try {
                    schema = BlueprintLoader.expand(schema);
                } catch (IOException e) {
                    unresolved.add("blueprint_bad_ops:" + g.blueprint());
                    return false;
                }
            }
            // 备料按 palette 家族合计、exact 块精确分别统计。
            // 【治建到一半误判缺料乱逛】:palette 占位(如 small_hut 的 "planks")执行期 BuildTask/MaterialPalette
            // 接受家族任意成员(任意木种木板),但旧逻辑只把整组需求记到 preferredPlanks 单一木种上,重规划时
            // 见"oak 79 < 需 96"就插采橡木步、无视背包另有 896 块其它木板 → bot 丢下工地往黑暗里追原木耗尽
            // 预算(real_build 实测 54/116 超时根因)。修:palette 材料按家族 owned 合计判足,够则不插采料步。
            Map<String, Integer> paletteNeeds = new LinkedHashMap<>();
            Map<Item, Integer> exactNeeds = new LinkedHashMap<>();
            for (BlueprintSchema.BlockPlacement placement : schema.placements()) {
                if (resumeContext != null && resumeContext.buildAnchor() != null
                        && StructureVerifier.matches(bot.getServerWorld(), resumeContext.buildAnchor(), placement)) {
                    continue;
                }
                if ("minecraft:air".equals(placement.blockId())) {
                    continue;
                }
                if (placement.palette() != null && !placement.palette().isBlank()
                        && MaterialPalette.isKnown(placement.palette())) {
                    paletteNeeds.merge(placement.palette(), 1, Integer::sum);
                } else {
                    Item material = buildMaterialFor(placement);
                    if (material != null) {
                        exactNeeds.merge(material, 1, Integer::sum);
                    }
                }
            }
            // 备料 best-effort:单种材料倒推失败(unresolved 已记)不挡其它材料,建造执行期缺哪块再 fail 哪块;
            // 但所有材料都倒推失败则整体判失败(根本没法开工)。
            // 注意传 depth 而非 depth+1:蓝图材料是 Build 的"顶层交付物"——BuildTask 只会从背包拿成品,
            // 不像 CraftTask 那样运行期自动把原木展开成木板。craftItem 的 Fix C 会把 depth>0 的中间体
            // 木板 CRAFT 步抑制掉(交给下游 CraftTask 展开),对 BUILD 这是错的;Build 仅以 depth=0 进入
            // (ensureGoal 顶层),传 depth 让木板按顶层产物保留 CRAFT 步,否则只囤原木、开工即缺料。
            boolean anyResolved = paletteNeeds.isEmpty() && exactNeeds.isEmpty();
            for (Map.Entry<String, Integer> entry : paletteNeeds.entrySet()) {
                int need = entry.getValue();
                int ownedInFamily = 0;
                List<Item> family = MaterialPalette.GROUPS.get(entry.getKey());
                if (family != null) {
                    for (Item member : family) {
                        ownedInFamily += counts.getOrDefault(member, 0);
                    }
                }
                if (ownedInFamily >= need) {
                    anyResolved = true; // 家族合计已够,不插采料步
                    continue;
                }
                Item species = paletteDefaultItem(entry.getKey());
                if (species == null) {
                    continue;
                }
                // 只补家族缺口:desiredCount = 该木种现有 + 全家族缺口,ensureItem 内部扣减后只采缺口部分。
                int desired = counts.getOrDefault(species, 0) + (need - ownedInFamily);
                if (ensureItem(species, desired, depth, visiting)) {
                    anyResolved = true;
                }
            }
            for (Map.Entry<Item, Integer> entry : exactNeeds.entrySet()) {
                if (ensureItem(entry.getKey(), entry.getValue(), depth, visiting)) {
                    anyResolved = true;
                }
            }
            if (!anyResolved) {
                return false;
            }
            addStep(GoalStep.build(g.blueprint()));
            return true;
        }

        // 蓝图格 → 规划期备料物品:air 跳过;palette 占位按该家族默认材质备料(规划备默认料,
        // 执行期 BuildTask/MaterialPalette 接受家族任意成员);其余按 blockId → 方块 → 对应物品
        // (blockId 写死的placement,BuildTask 也按该方块精确放置,故按字面备料);
        // 无对应物品(asItem()==AIR,如技术性方块/未知 id)跳过并告警一条。
        private Item buildMaterialFor(BlueprintSchema.BlockPlacement placement) {
            if ("minecraft:air".equals(placement.blockId())) {
                return null;
            }
            if (placement.palette() != null && !placement.palette().isBlank()) {
                Item byPalette = paletteDefaultItem(placement.palette());
                if (byPalette != null) {
                    return byPalette;
                }
            }
            Identifier blockKey = placement.blockId() == null ? null : Identifier.tryParse(placement.blockId());
            Block block = blockKey == null ? null : Registries.BLOCK.getOptionalValue(blockKey).orElse(null);
            Item item = block == null ? Items.AIR : block.asItem();
            if (item == Items.AIR) {
                BotLog.warn(LogCategory.TASK, null, "blueprint_material_skipped",
                        "block", String.valueOf(placement.blockId()));
                return null;
            }
            return item;
        }

        // palette → 默认备料物品(与 BlueprintLoader.fallbackBlock 同口径);planks 走树种自适应。
        private Item paletteDefaultItem(String palette) {
            return switch (palette) {
                case "planks" -> preferredPlanks();
                case "logs" -> Items.OAK_LOG;
                case "stone_like" -> Items.COBBLESTONE;
                case "dirt_like" -> Items.DIRT;
                case "glass" -> Items.GLASS;
                default -> null;
            };
        }

        // 盖房备木板选树种(借鉴 preferredFuelLog):优先背包已有的木板种,其次已有原木对应的木板种
        // (RecipeRegistry.LOGS/PLANKS 同序对齐),都没有才默认橡木板。意义:GATHER 原木运行期接受任意
        // 树种,而木板配方树种专属(oak_planks←oak_log)——在桦木林采回桦木后 CRAFT oak_planks 会失败,
        // 失败触发的重规划读到背包里的桦木,自动改备桦木板;palette 建造接受任意木板,链路自愈。
        private Item preferredPlanks() {
            for (Item planks : RecipeRegistry.PLANKS) {
                if (counts.getOrDefault(planks, 0) > 0) {
                    return planks;
                }
            }
            for (int i = 0; i < RecipeRegistry.LOGS.size(); i++) {
                if (counts.getOrDefault(RecipeRegistry.LOGS.get(i), 0) > 0) {
                    return RecipeRegistry.PLANKS.get(i);
                }
            }
            return Items.OAK_PLANKS;
        }

        // 第4层 备粮(best-effort):挖矿前顺带备粮,凑够默认 FOOD_TARGET 个熟食。
        private boolean ensureFood(int depth, Set<String> visiting) {
            return ensureFoodTo(FOOD_TARGET, depth, visiting);
        }

        private boolean ensureMiningFoodReserveTo(int surfaceTarget,
                                                  int depth,
                                                  Set<String> visiting) {
            return ensureMiningFoodReserveTo(surfaceTarget,
                    MiningFoodReserve.MIN_DEEP_MINE_UNITS, depth, visiting, false);
        }

        private boolean ensureMiningFoodReserveTo(int surfaceTarget,
                                                  int depth,
                                                  Set<String> visiting,
                                                  boolean bootstrapStonePickBeforeFurnace) {
            return ensureMiningFoodReserveTo(surfaceTarget,
                    MiningFoodReserve.MIN_DEEP_MINE_UNITS, depth, visiting,
                    bootstrapStonePickBeforeFurnace);
        }

        private boolean ensureMiningFoodReserveTo(int surfaceTarget,
                                                  int deepMineFloor,
                                                  int depth,
                                                  Set<String> visiting) {
            return ensureMiningFoodReserveTo(surfaceTarget, deepMineFloor,
                    depth, visiting, false);
        }

        private boolean ensureMiningFoodReserveTo(int surfaceTarget,
                                                  int deepMineFloor,
                                                  int depth,
                                                  Set<String> visiting,
                                                  boolean bootstrapStonePickBeforeFurnace) {
            int have = MiningFoodReserve.units(counts);
            if (!surfaceAcquisitionAllowed) {
                int required = Math.max(MiningFoodReserve.MIN_DEEP_MINE_UNITS,
                        deepMineFloor);
                if (have < required) {
                    unresolved.add("deep_mining_food_reserve_depleted:have=" + have
                            + ":required=" + required);
                    return false;
                }
                return true;
            }
            return ensureFoodTo(surfaceTarget, depth, visiting,
                    bootstrapStonePickBeforeFurnace);
        }

        // 猎→烤闭环:凑够 target 个熟食/面包(高饱食、安全)。挖矿备粮用 FOOD_TARGET;
        // "去打猎/去搞点吃的"口语入口(Goal.Food)用指定量。
        // 没动物/没熔炉/没燃料时 GoalExecutor 跳过相应 best-effort 步(见 handleStepFailure),不阻断主目标。
        /** Cooked units this plan already provisioned via hunt+cook (species unknown until the kill). */
        private int provisionedFoodUnits;

        private int plannedFoodUnits() {
            return MiningFoodReserve.units(counts) + provisionedFoodUnits;
        }

        private boolean ensureFoodTo(int target, int depth, Set<String> visiting) {
            return ensureFoodTo(target, depth, visiting, false);
        }

        private boolean ensureFoodTo(int target,
                                     int depth,
                                     Set<String> visiting,
                                     boolean bootstrapStonePickBeforeFurnace) {
            int cooked = plannedFoodUnits();
            if (cooked >= target) {
                return true;
            }
            if (restrictSurfaceAcquisition) {
                unresolved.add("surface_food_acquisition_unavailable:have=" + cooked
                        + ":required=" + target);
                return false;
            }
            int needCooked = target - cooked;
            // 感知驱动择源:没动物但有草 → 种植面包,但**仅当已有快路径材料**(足量小麦只差合成 / 足量种子只差种收)。
            // 从零割草+等自然生长要 15-20 分钟,对"尽快吃上饭"的 Food 目标必超时(real_food 自然世界实测:
            // 割草采集失败 + 即便采到也等不熟,连环 FAIL)。没快路径就走打猎:HuntTask 自带 roam 远征,
            // 64 格只是规划感知半径、不是打猎能力上限,附近没动物会主动走远找。觅食浆果饱食低,暂不作备粮源。
            boolean breadFastPath = counts.getOrDefault(Items.WHEAT, 0) >= needCooked * 3
                    || counts.getOrDefault(Items.WHEAT_SEEDS, 0) >= needCooked * 3;
            if (!hasPreyNearby && hasGrassNearby && breadFastPath) {
                ensureItem(Items.BREAD, needCooked, depth + 1, visiting);
                return true;
            }
            // 荒芜兜底:无动物可猎、面包快路径也没有,但附近有甜浆果丛(针叶林常见)→ 采浆果直接吃。
            // 饱食低(2 点/颗)按 2:1 折算;不需要熔炉/燃料,是"能立刻吃上"的最后手段
            //(实测针叶林世界 hunt 漫游 10 次 1092t 仍 0 猎物,整条打猎+烤链白忙)。
            if (!hasPreyNearby && hasBerriesNearby) {
                ensureItem(Items.SWEET_BERRIES, needCooked * 2, depth + 1, visiting);
                return true;
            }
            int raw = 0;
            for (Item m : RAW_MEAT_ITEMS) {
                raw += counts.getOrDefault(m, 0);
            }
            int huntNeed = Math.max(0, needCooked - raw);
            // Raw food is a surface acquisition. Provision the cheap wooden weapon and hunt before
            // any furnace bootstrap can dig a stone staircase; otherwise the next HUNT starts at
            // the mine bottom and strict_survival correctly refuses the old teleport-to-surface
            // escape hatch.
            if (huntNeed > 0) {
                if (!hasAnySword() && !ensureItem(Items.WOODEN_SWORD, 1, depth + 1, visiting)) {
                    return false;
                }
                int remainingHunt = huntNeed;
                int batch = 1;
                while (remainingHunt > 0) {
                    int batchTarget = Math.min(4, remainingHunt);
                    addStep(GoalStep.huntBatch(batchTarget, batch++));
                    remainingHunt -= batchTarget;
                }
            }
            // A from-zero obsidian expedition used to open the furnace's eight-cobblestone shaft
            // with its only wooden pick, let background resupply consume another handle, then open
            // the four-pick readiness shaft with wood and consume a third.  Preserve the established
            // hunt-first surface ordering, but cross the normal three-cobblestone upgrade boundary
            // before any furnace or bulk-stone work.  The later four-pick target can count this
            // physical stone pick; all remaining stone acquisition then uses the renewable tier.
            if (bootstrapStonePickBeforeFurnace
                    && counts.getOrDefault(Items.FURNACE, 0) <= 0
                    && bestPickaxeTier() < ToolTier.STONE
                    && !ensurePickaxeTier(ToolTier.STONE, depth + 1, visiting)) {
                return false;
            }
            // 烤肉需熔炉:没炉则确定性倒推一座(8 圆石 → 挖石 → 需镐 → 木板/木棍 → 原木 → 砍树),
            // 让"砍树 + 做基本工具"作为底层能力按正确顺序自动展开;而非 best-effort 跳过后丢给大脑乱凑
            //(实测:大脑直接 gather 圆石、没先做镐,挖不动)。整条 Food best-effort 兜底,缺料环境降级不卡死。
            if (counts.getOrDefault(Items.FURNACE, 0) <= 0) {
                ensureItem(Items.FURNACE, 1, depth + 1, visiting);
            }
            // 烤 needCooked 个熟食需燃料——之前漏了这步,烤大量肉时 SmeltTask out_of_fuel、整条食物链白忙。
            // 与矿石熔炼 smeltItem 一致:已有煤/炭各≈烤 8 个;不够用原木补(1 原木≈烤 1.5 个),优先已有树种、
            // best-effort 砍树(无树则 unresolved,执行期 COOK_FOOD 缺燃料再降级,不卡死)。
            int coalLike = counts.getOrDefault(Items.COAL, 0) + counts.getOrDefault(Items.CHARCOAL, 0);
            // SmeltTask.remainingToQueue 严格封顶 targetCount，按实际熟食目标备燃料即可；旧的 2 倍
            // 预算会把 24 份 readiness 放大成 32 根燃料木，seed 3000 实测只是在开工前过度砍树。
            int fuelDeficit = needCooked - coalLike * 8;
            if (fuelDeficit > 0) {
                Item fuelLog = preferredFuelLog();
                int logsForFuel = Math.max(1, (int) Math.ceil(fuelDeficit / 1.5));
                int availableLogs = countItems(RecipeRegistry.LOGS);
                int missingLogs = Math.max(0, logsForFuel - availableLogs);
                if (missingLogs > 0 && !ensureItem(fuelLog,
                        counts.getOrDefault(fuelLog, 0) + missingLogs, depth + 1, visiting)) {
                    return false;
                }
                // Fuel is a real future consumption. Reserve it across every usable log species so
                // a later replan neither asks for 32 new oak logs beside a stack of birch nor spends
                // the same logs again on underground tool handles.
                consumeItems(RecipeRegistry.LOGS, logsForFuel);
            }
            addStep(GoalStep.cookFood(needCooked)); // 烤成熟肉(背包已有生肉也一并烤)
            provisionedFoodUnits += needCooked;
            return true;
        }

        /**
         * Seals one maximum-size surface shelter budget away from every later symbolic consumer.
         *
         * <p>Raw logs are the hard unit because every later physical recipe consumes them one for
         * one. Planks remain ordinary recipe stock: reserving them only symbolically would not stop
         * CraftTask from spending four carried planks while leaving one newly gathered log, which
         * loses three physical shelter blocks. The logs remain physically carried and are spent
         * only if DangerWatcher opens an emergency shelter transaction. This ownership ends at the
         * descent hand-off, where the mine's protected stone/service ledger becomes the emergency
         * enclosure budget; underground replans may therefore use carried logs as tool material.</p>
         */
        private void beginSurfaceEmergencyShelterWoodReserve() {
            if (surfaceEmergencyShelterWoodPending >= 0) {
                return;
            }
            int target = EmergencyShelterTask.MAX_PLACEMENT_BLOCKS;
            int logs = Math.min(target, countItems(RecipeRegistry.LOGS));
            consumeItems(RecipeRegistry.LOGS, logs);
            surfaceEmergencyShelterWoodPending = target - logs;
        }

        private boolean reserveSurfaceEmergencyShelterWood(int depth,
                                                           Set<String> visiting) {
            beginSurfaceEmergencyShelterWoodReserve();
            if (surfaceEmergencyShelterWoodPending == 0) {
                return true;
            }
            // Food planning may have produced a harmless raw-log remainder. Claim it before
            // gathering the outstanding reserve, but never return the logs sealed at begin() to
            // symbolic stock.
            int available = Math.min(
                    surfaceEmergencyShelterWoodPending,
                    countItems(RecipeRegistry.LOGS));
            consumeItems(RecipeRegistry.LOGS, available);
            surfaceEmergencyShelterWoodPending -= available;
            if (surfaceEmergencyShelterWoodPending > 0) {
                Item log = preferredFuelLog();
                int desired = saturatedAdd(
                        counts.getOrDefault(log, 0), surfaceEmergencyShelterWoodPending);
                if (!ensureItem(log, desired, depth + 1, visiting)) {
                    return false;
                }
                consumeItems(RecipeRegistry.LOGS, surfaceEmergencyShelterWoodPending);
                surfaceEmergencyShelterWoodPending = 0;
            }
            return true;
        }

        private boolean ensureItem(Item item, int desiredCount, int depth, Set<String> visiting) {
            if (depth > maxDepth) {
                unresolved.add("max_depth:" + id(item));
                return false;
            }
            int available = counts.getOrDefault(item, 0);
            if (available >= desiredCount) {
                return true;
            }
            String key = id(item) + ":" + desiredCount;
            if (!visiting.add(key)) {
                unresolved.add("cycle:" + id(item));
                return false;
            }
            int missing = desiredCount - available;
            Optional<RecipeRegistry.Recipe> recipe = RecipeRegistry.find(item);
            boolean resolved = recipe.isPresent()
                    ? craftItem(item, missing, recipe.get(), depth, visiting)
                    : acquireBaseItem(item, missing, depth, visiting);
            visiting.remove(key);
            return resolved;
        }

        // S7:回滚 steps 到指定大小——craftItem 某配方中途失败时清掉本配方已下发的中间步骤,避免半截残留污染计划。
        private void rollbackSteps(int to) {
            while (steps.size() > to) {
                steps.remove(steps.size() - 1);
            }
        }

        private boolean craftItem(Item item, int missing, RecipeRegistry.Recipe recipe, int depth, Set<String> visiting) {
            int crafts = divideRoundUp(missing, recipe.outputCount());
            int stepsBefore = steps.size(); // S7:本配方失败时回滚已下发的中间步骤
            Map<Item, Integer> countsBefore = new HashMap<>(counts);
            if (recipe.needsCraftingTable() && item != Items.CRAFTING_TABLE) {
                if (!ensureItem(Items.CRAFTING_TABLE, 1, depth + 1, visiting)) {
                    counts.clear();
                    counts.putAll(countsBefore);
                    rollbackSteps(stepsBefore);
                    return false;
                }
            }
            for (RecipeRegistry.Ingredient ingredient : recipe.ingredients()) {
                int need = ingredient.count() * crafts;
                if (!ensureIngredient(ingredient, need, depth + 1, visiting)) {
                    unresolved.add("missing:" + ingredient.anyOf() + " x" + need + " for " + id(item));
                    counts.clear();
                    counts.putAll(countsBefore);
                    rollbackSteps(stepsBefore);
                    return false;
                }
                consume(ingredient, need);
            }
            counts.merge(item, recipe.outputCount() * crafts, Integer::sum);
            // Fix C:中间体木板不下发独立 CRAFT 步——木板配方是树种专属(oak_planks←oak_log),
            // 但下游 stick/crafting_table/工具配方都接受任意 planks 家族,其 CraftTask 会按背包里实际
            // 采到的原木种类(可能是桦木/云杉…)自动展开木板。若仍下发 "CRAFT oak_planks" 步,在只有
            // 桦木的生物群系会失败。仅当木板本身是顶层目标(depth==0,如 achieve_goal planks)才保留,
            // 否则该目标会没有任何产出步骤。原木的 GATHER 步仍照常下发(在 acquireBaseItem)。
            if (!(depth > 0 && RecipeRegistry.PLANKS.contains(item))) {
                addStep(GoalStep.craft(item, recipe.outputCount() * crafts));
            }
            return true;
        }

        private boolean acquireBaseItem(Item item, int missing, int depth, Set<String> visiting) {
            if (restrictSurfaceAcquisition && isSurfaceOnlyResource(item)) {
                unresolved.add("underground_surface_resource_unavailable:" + id(item));
                return false;
            }
            if (RecipeRegistry.LOGS.contains(item)) {
                addStep(GoalStep.gather(item, missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            if (item == Items.WHEAT_SEEDS) {
                // 小麦种子 → 割草获取(GatherQuotaTask 把种子映射到短草/高草/蕨,破坏概率掉种子)。
                addStep(GoalStep.gather(item, missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            if (item == Items.SWEET_BERRIES || item == Items.MELON_SLICE) {
                // 野食 → 觅食(GatherQuotaTask 把野食映射到甜浆果丛/西瓜,采就近的)。
                addStep(GoalStep.gather(item, missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            if (item == Items.SUGAR_CANE) {
                // 甘蔗 → 割甘蔗(GatherQuotaTask 破坏 sugar_cane 块掉甘蔗;蛋糕链里糖的来源)。
                addStep(GoalStep.gather(item, missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            if (item == Items.MILK_BUCKET) {
                // 牛奶桶 → 先确保等量空桶(空桶可由 3 铁倒推/背包已有),再下挤奶步(周围要有牛,执行期 best-effort)。
                if (!ensureItem(Items.BUCKET, missing, depth + 1, visiting)) {
                    return false;
                }
                addStep(GoalStep.milkCow(missing));
                counts.merge(Items.MILK_BUCKET, missing, Integer::sum);
                return true;
            }
            if (item == Items.COBBLESTONE) {
                if (!ensurePickaxeTier(ToolTier.WOOD, depth + 1, visiting)) {
                    return false;
                }
                addStep(GoalStep.mine(Blocks.STONE, missing));
                counts.merge(Items.COBBLESTONE, missing, Integer::sum);
                return true;
            }
            if (item == Items.OBSIDIAN) {
                // 黑曜石远征顺序是硬契约:先在地表备好初始口粮、廉价掘进镐、封堵块和备用木棍;
                // 再为桶单独取得 3 铁并物理返回地表找可见水源;水桶到手后补齐完整食物配额
                // (分期见 MiningBudget.obsidianExpeditionInitialFoodTarget);最后才进入钻石镐深潜链。
                // bucket recipe 会消费自己的 3 铁,后续铁镐/备用铁因此会被独立倒推,不能挪用桶铁。
                int missionTarget = saturatedAdd(
                        counts.getOrDefault(Items.OBSIDIAN, 0), missing);
                ObsidianToolProvision toolProvision = obsidianToolProvision(missing);
                ObsidianTorchProvision torchProvision =
                        obsidianTorchProvision(toolProvision);
                if (!ensureObsidianExpeditionReadiness(
                        missing, missionTarget, toolProvision, torchProvision,
                        depth + 1, visiting)) {
                    return false;
                }
                if (counts.getOrDefault(Items.WATER_BUCKET, 0) <= 0) {
                    if (counts.getOrDefault(Items.BUCKET, 0) <= 0
                            && !ensureItem(Items.BUCKET, 1, depth + 1, visiting)) {
                        return false;
                    }
                    addStep(GoalStep.acquireWater());
                    plannedY = waterReturnY;
                    initialOrePerceptionValid = false;
                    consumeItem(Items.BUCKET, 1);
                    counts.merge(Items.WATER_BUCKET, 1, Integer::sum);
                }
                // 分期备粮的第二段:取水远征已把 bot 带到另一片地表畜群,此处补齐完整远征
                // 配额,然后才进入铁/钻石深潜链。preflight 的运行时食物门不变,仍要求足额。
                if (!ensureMiningFoodReserveTo(
                        MiningBudget.obsidianExpeditionFoodTarget(missionTarget),
                        depth + 1, visiting, true)) {
                    return false;
                }
                // Provision the ore-acquisition tool first. A raw-remaining=2 diamond/netherite
                // pick is a valid tier but cannot mine the three diamonds needed for its own
                // replacement. The immutable provision computed before readiness binds both this
                // dependency and the exact stick reserve to the same resource calculation.
                if (!ensureObsidianAcquisitionTool(toolProvision, depth + 1, visiting)
                        || !ensureObsidianTargetToolDurability(
                        toolProvision, depth + 1, visiting)) {
                    return false;
                }
                steps.add(GoalStep.obsidianPreflight(missing));
                addStep(GoalStep.makeObsidian(missing));
                counts.merge(Items.OBSIDIAN, missing, Integer::sum);
                return true;
            }
            // P2:矿物掉落物 → 对应矿石(统一映射表)。挖该矿所需镐档由 ToolTier 决定,
            // ensureMineOre 内部会先 ensurePickaxeTier 自动补齐镐链(如钻石需铁镐 → 先倒推铁镐)。
            Block oreOf = oreBlockFor(item);
            if (oreOf != null) {
                // ensureItem 已把 desiredCount 换算成 missing；ensureMineOre 内部还会再减一次
                // 当前掉落物库存，因此这里必须传“当前 + 缺口”的总目标。传 missing 会在 64
                // 钻石完成首批 8 后得到 count=8/owned=8，错误规划成空步骤并结束为 PARTIAL。
                int desiredTotal = counts.getOrDefault(item, 0) + missing;
                return ensureMineOre(Set.of(oreOf), desiredTotal, depth + 1, visiting);
            }
            SmeltRecipe smelt = smeltRecipeFor(item);
            if (smelt != null) {
                return smeltItem(smelt, missing, depth, visiting);
            }
            if ("smelt".equals(AcquisitionHints.source(item))) {
                unresolved.add("missing_smelt_recipe:" + id(item));
                return false;
            }
            if ("mine".equals(AcquisitionHints.source(item)) && item instanceof net.minecraft.item.BlockItem blockItem) {
                addStep(GoalStep.mine(blockItem.getBlock(), missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            // S4:生肉 → 打猎(best-effort 泛猎;HuntTask 猎 cow/pig/sheep/chicken/rabbit。乐观计入让食物链可倒推,
            // 运行期实际猎到哪种肉不定,模块 B 的食物消费按"泛食物"处理)。
            if (item == Items.BEEF || item == Items.PORKCHOP || item == Items.MUTTON
                    || item == Items.CHICKEN || item == Items.RABBIT) {
                addStep(GoalStep.hunt(missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            // S4:作物产出 → 就地种田(开垦/播种/等熟/收割)。
            FarmAction.CropSpec crop = cropSpecForProduce(item);
            if (crop != null) {
                // 种田要锄头(FarmAction.till 无锄 → missing_hoe)。此分支是 Goal.Food→面包→小麦 的入口,
                // 之前只 ensureSeeds、漏了倒推锄头(ensureHarvestCrop 有、这里没)→ FARM 步 till 白忙、面包链断。
                // best-effort 补一把木锄(与 ensureHarvestCrop 一致;无木料环境锄头 unresolved 不阻断整条 Food,
                // 仍下发 FARM 步,执行期 till 缺锄再降级,符合食物链"缺料降级不卡死"哲学)。
                if (!hasAnyHoe()) {
                    ensureItem(Items.WOODEN_HOE, 1, depth + 1, visiting);
                }
                ensureSeeds(crop.seed(), item, missing, depth, visiting); // 种田前先确保种子(小麦种子割草取)
                addStep(GoalStep.farm(crop.crop(), crop.seed(), item, missing));
                counts.merge(item, missing, Integer::sum);
                return true;
            }
            unresolved.add("unresolved:" + id(item) + " source=" + AcquisitionHints.source(item));
            return false;
        }

        private boolean isSurfaceOnlyResource(Item item) {
            return RecipeRegistry.LOGS.contains(item)
                    || RAW_MEAT_ITEMS.contains(item)
                    || item == Items.WHEAT_SEEDS
                    || item == Items.SWEET_BERRIES
                    || item == Items.MELON_SLICE
                    || item == Items.SUGAR_CANE
                    || item == Items.MILK_BUCKET
                    || cropSpecForProduce(item) != null;
        }

        private boolean ensureObsidianExpeditionReadiness(int targetCount,
                                                          int missionTarget,
                                                          ObsidianToolProvision toolProvision,
                                                          ObsidianTorchProvision torchProvision,
                                                          int depth,
                                                          Set<String> visiting) {
            boolean reserveSurfaceShelter = missionTarget >= 32
                    && surfaceAcquisitionAllowed;
            if (reserveSurfaceShelter) {
                beginSurfaceEmergencyShelterWoodReserve();
            }
            int unresolvedBefore = unresolved.size();
            // 8 个熟食只够 prepared 短程;64 块黑曜石要跨 8 个 service 段,深层既不能猎食也
            // 没有预置 depot 可取,断粮即 mining_service_food_reserve_depleted 无解。完整配额
            // (公式与单测在 MiningBudget)分期供给:此处只备 floor+buffer 的初始口粮,取水远征
            // 把 bot 带到第二片地表畜群后再补齐——把 9 连猎全部压在出生点畜群上,5 个公开
            // seed 全部耗尽死于 replan_same_step:hunt_no_progress。小目标全额本就 ≤ 初始量,
            // 保持单门形态。
            if (!ensureMiningFoodReserveTo(
                    MiningBudget.obsidianExpeditionInitialFoodTarget(missionTarget),
                    depth + 1, visiting, true)
                    || unresolved.size() > unresolvedBefore) {
                return false;
            }
            if (reserveSurfaceShelter
                    && !reserveSurfaceEmergencyShelterWood(depth + 1, visiting)) {
                return false;
            }
            // Keep the tool-upgrade boundary explicit: four stone picks are cheap tunnel tools;
            // diamond/iron durability remains reserved for target blocks and later replacement.
            int stoneLikeTarget = MiningServiceTask.ServicePolicy
                    .bootstrapStoneLikeTarget(targetCount)
                    + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;
            int serviceStickTarget = MiningServiceTask.ServicePolicy
                    .bootstrapStickTarget(targetCount)
                    + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STICKS;
            boolean needsStoneSword = counts.getOrDefault(Items.STONE_SWORD, 0) <= 0;
            int postReadinessStickTarget = serviceStickTarget
                    + toolProvision.postReadinessSticks()
                    + torchProvision.recipeSticks();
            if (!ensureItem(Items.CRAFTING_TABLE, 1, depth + 1, visiting)
                    || !ensureItem(Items.STONE_PICKAXE, OBSIDIAN_EXPEDITION_STONE_PICKS,
                    depth + 1, visiting)
                    // The weapon is a safety prerequisite for the long stone reserve shaft, not a
                    // reward after it. Craft its independent +2 stone/+1 stick margin first, then
                    // replenish the untouched service ledgers below.
                    || (needsStoneSword && !ensureItem(Items.STONE_SWORD, 1,
                    depth + 1, visiting))
                    || !ensureItem(Items.COBBLESTONE,
                    stoneLikeTarget,
                    depth + 1, visiting)
                    // Readiness runs before the acquisition + target-pick chain. Keep its exact
                    // handle/torch cost separate from all preflight + 8/16/24 repair windows.
                    || !ensureItem(Items.STICK,
                    postReadinessStickTarget,
                    depth + 1, visiting)
                    // Torch recipes consume their coal before the bucket/tool/spare-iron smelts.
                    // Provision both ledgers together so an underground replan cannot arrive at
                    // the first furnace with every carried fuel item already converted to light.
                    || !ensureItem(Items.COAL, torchProvision.recipeSticks()
                            + torchProvision.futureSmeltFuelItems(),
                    depth + 1, visiting)
                    || !ensureItem(Items.TORCH, torchProvision.targetCount(),
                    depth + 1, visiting)) {
                return false;
            }
            return unresolved.size() == unresolvedBefore;
        }

        /**
         * Computes one immutable post-readiness tool contract. Existing iron durability is spent
         * first because the mining-channel selector chooses the lowest sufficient tier. Target-tier
         * durability may safely acquire replacement diamonds only when doing so still leaves the
         * final obsidian break budget intact; otherwise a renewable iron pick is provisioned.
         */
        private ObsidianToolProvision obsidianToolProvision(int targetCount) {
            int targetUsable = plannedObsidianToolUsableDurability();
            int missingTargetDurability = Math.max(0, targetCount - targetUsable);
            int freshDiamondDurability = freshUsableDurability(Items.DIAMOND_PICKAXE);
            int replacementPicks = missingTargetDurability == 0 ? 0
                    : divideRoundUpSafe(missingTargetDurability, freshDiamondDurability);
            int replacementDiamonds = saturatedMultiply(replacementPicks, 3);
            int diamondsToMine = Math.max(0,
                    replacementDiamonds - counts.getOrDefault(Items.DIAMOND, 0));

            int ironUsable = plannedToolUsableDurability(Items.IRON_PICKAXE);
            int targetToolSpend = Math.max(0, diamondsToMine - ironUsable);
            boolean acquisitionCapacitySufficient = saturatedAdd(ironUsable, targetUsable)
                    >= diamondsToMine;
            long finalTargetUsableWithoutIron = (long) targetUsable
                    - Math.min(targetUsable, targetToolSpend)
                    + (long) replacementPicks * freshDiamondDurability;
            boolean acquisitionIronRequired = diamondsToMine > 0
                    && (!acquisitionCapacitySufficient
                    || finalTargetUsableWithoutIron < targetCount);
            int missingIronUsable = acquisitionIronRequired
                    ? Math.max(0, diamondsToMine - ironUsable) : 0;
            int freshIronDurability = freshUsableDurability(Items.IRON_PICKAXE);
            int acquisitionIronPicks = missingIronUsable == 0 ? 0
                    : divideRoundUpSafe(missingIronUsable, freshIronDurability);

            int required = saturatedMultiply(replacementPicks, 2);
            required = saturatedAdd(required, saturatedMultiply(acquisitionIronPicks, 2));
            return new ObsidianToolProvision(
                    counts.getOrDefault(Items.DIAMOND_PICKAXE, 0),
                    counts.getOrDefault(Items.IRON_PICKAXE, 0),
                    replacementPicks, acquisitionIronPicks,
                    diamondsToMine, required);
        }

        /**
         * Forecasts every dependency descent before the first obsidian pool. Runtime planning later
         * debits the same per-descent budget from symbolic torch inventory; the additional eight are
         * therefore still present when branch search starts.
         */
        private ObsidianTorchProvision obsidianTorchProvision(
                ObsidianToolProvision toolProvision) {
            int simulatedY = plannedY;
            boolean perceptionValid = initialOrePerceptionValid;
            int expectedUse = 0;
            int futureSmeltFuelItems = 0;
            int ironIngots = counts.getOrDefault(Items.IRON_INGOT, 0);
            int rawIron = counts.getOrDefault(Items.RAW_IRON, 0);

            boolean needsWater = counts.getOrDefault(Items.WATER_BUCKET, 0) <= 0;
            if (needsWater && counts.getOrDefault(Items.BUCKET, 0) <= 0) {
                if (ironIngots < 3) {
                    futureSmeltFuelItems++;
                }
                IronMaterialForecast bucketIron = consumeForecastIron(
                        ironIngots, rawIron, 3, simulatedY, perceptionValid);
                ironIngots = bucketIron.ironIngots();
                rawIron = bucketIron.rawIron();
                simulatedY = bucketIron.plannedY();
                perceptionValid = bucketIron.perceptionValid();
                expectedUse = saturatedAdd(expectedUse, bucketIron.torches());
            }
            if (needsWater) {
                simulatedY = waterReturnY;
                perceptionValid = false;
            }

            int acquisitionIron = saturatedMultiply(
                    toolProvision.acquisitionIronPickaxes(), 3);
            if (acquisitionIron > 0) {
                if (ironIngots < acquisitionIron) {
                    futureSmeltFuelItems++;
                }
                IronMaterialForecast toolIron = consumeForecastIron(
                        ironIngots, rawIron, acquisitionIron,
                        simulatedY, perceptionValid);
                simulatedY = toolIron.plannedY();
                perceptionValid = toolIron.perceptionValid();
                expectedUse = saturatedAdd(expectedUse, toolIron.torches());
            }

            if (toolProvision.diamondsToMine() > 0) {
                DescentForecast diamondDescent = forecastOreDescent(
                        simulatedY, perceptionValid,
                        OreScan.expandOreFamilies(Set.of(Blocks.DIAMOND_ORE)),
                        toolProvision.diamondsToMine(), true);
                // A small deep-diamond dependency reserves three spare iron ingots before its
                // descent. Forecast that nested acquisition in the same order as ensureMineOre:
                // it may add an iron descent and always opens one physical smelt when ingots are
                // missing, even if carried raw iron already covers the ore requirement.
                if (diamondDescent.descends()
                        && toolProvision.diamondsToMine()
                        < MiningBudget.EXPEDITION_THRESHOLD) {
                    int spareMissing = Math.max(0, SPARE_IRON_INGOTS - ironIngots);
                    if (spareMissing > 0) {
                        futureSmeltFuelItems++;
                        int rawUsed = Math.min(rawIron, spareMissing);
                        rawIron -= rawUsed;
                        int spareToMine = spareMissing - rawUsed;
                        if (spareToMine > 0) {
                            DescentForecast spareIron = forecastOreDescent(
                                    simulatedY, perceptionValid,
                                    OreScan.expandOreFamilies(Set.of(Blocks.IRON_ORE)),
                                    spareToMine, false);
                            simulatedY = spareIron.plannedY();
                            perceptionValid = spareIron.perceptionValid();
                            expectedUse = saturatedAdd(expectedUse, spareIron.torches());
                        }
                        diamondDescent = forecastOreDescent(
                                simulatedY, perceptionValid,
                                OreScan.expandOreFamilies(Set.of(Blocks.DIAMOND_ORE)),
                                toolProvision.diamondsToMine(), true);
                    }
                }
                expectedUse = saturatedAdd(expectedUse, diamondDescent.torches());
            }

            int target = saturatedAdd(expectedUse, TORCH_TARGET);
            int missing = Math.max(0,
                    target - counts.getOrDefault(Items.TORCH, 0));
            int recipeSticks = divideRoundUpSafe(missing, 4);
            return new ObsidianTorchProvision(
                    target, recipeSticks, futureSmeltFuelItems);
        }

        private IronMaterialForecast consumeForecastIron(int ironIngots,
                                                         int rawIron,
                                                         int required,
                                                         int fromY,
                                                         boolean perceptionValid) {
            int remaining = Math.max(0, required);
            int usedIngots = Math.min(Math.max(0, ironIngots), remaining);
            ironIngots -= usedIngots;
            remaining -= usedIngots;
            int usedRaw = Math.min(Math.max(0, rawIron), remaining);
            rawIron -= usedRaw;
            remaining -= usedRaw;
            if (remaining == 0) {
                return new IronMaterialForecast(
                        ironIngots, rawIron, fromY, perceptionValid, 0);
            }
            DescentForecast descent = forecastOreDescent(
                    fromY, perceptionValid,
                    OreScan.expandOreFamilies(Set.of(Blocks.IRON_ORE)),
                    remaining, false);
            return new IronMaterialForecast(
                    ironIngots, rawIron,
                    descent.plannedY(), descent.perceptionValid(), descent.torches());
        }

        private DescentForecast forecastOreDescent(int fromY,
                                                   boolean perceptionValid,
                                                   Set<Block> ores,
                                                   int targetCount,
                                                   boolean rareOre) {
            int mineY = bestMiningY(ores);
            boolean longRareExpedition = rareOre
                    && targetCount >= MiningBudget.EXPEDITION_THRESHOLD;
            boolean knownOreNearby = perceptionValid && oreNearby.test(ores);
            boolean descends = fromY - mineY > DESCEND_THRESHOLD
                    && (longRareExpedition || !knownOreNearby);
            if (!descends) {
                return new DescentForecast(fromY, perceptionValid, 0, false);
            }
            return new DescentForecast(
                    mineY, false, descendTorchBudget(fromY, mineY), true);
        }

        private static int descendTorchBudget(int fromY, int targetY) {
            int depth = Math.max(0, fromY - targetY);
            return depth == 0 ? 0 : divideRoundUpSafe(depth, DESCEND_TORCH_EVERY);
        }

        private static int roundUpToTorchRecipe(int target) {
            int batches = divideRoundUpSafe(Math.max(0, target), 4);
            return saturatedMultiply(batches, 4);
        }

        private boolean ensureObsidianAcquisitionTool(ObsidianToolProvision provision,
                                                      int depth,
                                                      Set<String> visiting) {
            if (provision.acquisitionIronPickaxes() == 0) {
                return true;
            }
            int desiredIronPicks = saturatedAdd(provision.baselineIronPickaxes(),
                    provision.acquisitionIronPickaxes());
            return ensureItem(Items.IRON_PICKAXE, desiredIronPicks,
                    depth + 1, visiting);
        }

        private boolean ensureObsidianTargetToolDurability(ObsidianToolProvision provision,
                                                           int depth,
                                                           Set<String> visiting) {
            if (provision.replacementDiamondPickaxes() == 0) {
                return true;
            }
            int desiredDiamondPicks = saturatedAdd(provision.baselineDiamondPickaxes(),
                    provision.replacementDiamondPickaxes());
            return ensureItem(Items.DIAMOND_PICKAXE, desiredDiamondPicks,
                    depth + 1, visiting);
        }

        private record ObsidianToolProvision(int baselineDiamondPickaxes,
                                             int baselineIronPickaxes,
                                             int replacementDiamondPickaxes,
                                             int acquisitionIronPickaxes,
                                             int diamondsToMine,
                                             int postReadinessSticks) {
        }

        private record ObsidianTorchProvision(int targetCount,
                                              int recipeSticks,
                                              int futureSmeltFuelItems) {
        }

        private record DescentForecast(int plannedY,
                                       boolean perceptionValid,
                                       int torches,
                                       boolean descends) {
        }

        private record IronMaterialForecast(int ironIngots,
                                            int rawIron,
                                            int plannedY,
                                            boolean perceptionValid,
                                            int torches) {
        }

        private int plannedObsidianToolUsableDurability() {
            return saturatedAdd(plannedToolUsableDurability(Items.DIAMOND_PICKAXE),
                    plannedToolUsableDurability(Items.NETHERITE_PICKAXE));
        }

        private int plannedToolUsableDurability(Item item) {
            int initialCount = initialCounts.getOrDefault(item, 0);
            int plannedCount = counts.getOrDefault(item, 0);
            int newTools = Math.max(0, plannedCount - initialCount);
            int plannedFresh = saturatedMultiply(freshUsableDurability(item), newTools);
            return saturatedAdd(
                    initialToolUsableDurability.getOrDefault(item, 0), plannedFresh);
        }

        private boolean smeltItem(SmeltRecipe recipe, int missing, int depth, Set<String> visiting) {
            if (!ensureItem(Items.FURNACE, 1, depth + 1, visiting)) {
                return false;
            }
            // GOALFIX-GF2:需要 missing 个 input 来熔炼 missing 个产物;优先用已有库存,只补缺口
            // (ensureItem 内部 missing = desired - available),不要在已有量之上再多挖一份。
            if (!ensureItem(recipe.input(), missing, depth + 1, visiting)) {
                return false;
            }
            // 燃料:优先用背包已有的煤/木炭(1 个烧 8 个),只在不足时才砍原木补缺口(1 原木烧 1.5 个)。
            // (原来无脑砍原木、背包有煤也不用 → 给了煤仍去砍树、无树则 no_resource;实测铁/金锭挂在此。)
            int coalLike = counts.getOrDefault(Items.COAL, 0) + counts.getOrDefault(Items.CHARCOAL, 0);
            int fuelDeficit = missing - coalLike * 8;
            int fuelLogs = 0;
            if (fuelDeficit > 0) {
                // +1 冗余:执行层与账本天然漂移——craft 换板按整原木消耗、smelt chooseFuel 全额
                // 单品种装填,两头贪心合计常差 1-2 板,链尾'合成木棍'就 need planks 重采;此时场景
                // 树若已砍光直接 no_resource(iron_pickaxe 套跑实测)。多砍一根木头吸收漂移。
                // The extra surface log absorbs recipe/accounting drift during initial bootstrap.
                // Underground replans must consume the already-carried reserve exactly: adding one
                // per separated smelt stage made a viable eight-log kit request trees at Y=16.
                fuelLogs = Math.max(1, (int) Math.ceil(fuelDeficit / 1.5))
                        + (surfaceAcquisitionAllowed ? 1 : 0);
                int availableLogs = countItems(RecipeRegistry.LOGS);
                int missingLogs = Math.max(0, fuelLogs - availableLogs);
                if (missingLogs > 0) {
                    if (restrictSurfaceAcquisition) {
                        unresolved.add("underground_fuel_reserve_depleted:have="
                                + availableLogs + ":required=" + fuelLogs);
                        return false;
                    }
                    Item fuel = preferredFuelLog();
                    int desiredFuel = counts.getOrDefault(fuel, 0) + missingLogs;
                    if (!ensureItem(fuel, desiredFuel, depth + 1, visiting)) {
                        return false;
                    }
                }
            }
            consumeItem(recipe.input(), missing);
            if (fuelLogs > 0) {
                // SmeltTask can reload different vanilla log fuels. Reserve the same family here
                // instead of binding three separated underground smelts to whichever tree species
                // happened to appear first in RecipeRegistry.LOGS.
                consumeItems(RecipeRegistry.LOGS, fuelLogs);
            }
            counts.merge(recipe.output(), missing, Integer::sum);
            addStep(GoalStep.smelt(recipe.input(), recipe.output(), missing));
            return true;
        }

        /**
         * Treats an any-of ingredient as one inventory family. Each candidate first contributes
         * only what its currently carried, recipe-compatible inputs can produce (oak logs to oak
         * planks, birch logs to birch planks, and so on). If the aggregate is still short, exactly
         * one candidate plans the remaining family deficit instead of independently requesting the
         * full requirement again.
         */
        private boolean ensureIngredient(RecipeRegistry.Ingredient ingredient,
                                         int need,
                                         int depth,
                                         Set<String> visiting) {
            if (countItems(ingredient.anyOf()) >= need) {
                return true;
            }
            for (Item candidate : ingredient.anyOf()) {
                int remaining = need - countItems(ingredient.anyOf());
                if (remaining <= 0) {
                    return true;
                }
                int existing = Math.max(0, counts.getOrDefault(candidate, 0));
                int carriedCapacity = directCraftCapacity(candidate);
                int producible = Math.max(0, carriedCapacity - existing);
                if (producible <= 0) {
                    continue;
                }
                int contribution = Math.min(remaining, producible);
                if (!ensureItem(candidate, saturatedAdd(existing, contribution), depth,
                        visiting)) {
                    return false;
                }
            }
            int remaining = need - countItems(ingredient.anyOf());
            if (remaining <= 0) {
                return true;
            }
            Item candidate = chooseIngredient(ingredient, remaining);
            return candidate != null
                    && ensureItem(candidate, saturatedAdd(
                    counts.getOrDefault(candidate, 0), remaining), depth, visiting)
                    && countItems(ingredient.anyOf()) >= need;
        }

        private Item chooseIngredient(RecipeRegistry.Ingredient ingredient, int need) {
            Item bestFinished = null;
            int bestFinishedCount = -1;
            Item bestSufficient = null;
            int bestSufficientCapacity = -1;
            Item bestAvailable = null;
            int bestAvailableCapacity = -1;
            for (Item item : ingredient.anyOf()) {
                int finished = counts.getOrDefault(item, 0);
                int capacity = directCraftCapacity(item);
                if (finished >= need && finished > bestFinishedCount) {
                    bestFinished = item;
                    bestFinishedCount = finished;
                }
                if (capacity >= need && capacity > bestSufficientCapacity) {
                    bestSufficient = item;
                    bestSufficientCapacity = capacity;
                }
                if (capacity > bestAvailableCapacity) {
                    bestAvailable = item;
                    bestAvailableCapacity = capacity;
                }
            }
            // Spend a fully-carried alternative first. Otherwise choose the family whose matching
            // direct ingredients can actually satisfy the whole multi-craft requirement. This keeps
            // one stray oak plank from binding an underground stick reserve to an unavailable oak
            // log when the carried birch logs can make all required birch planks.
            if (bestFinished != null) {
                return bestFinished;
            }
            if (bestSufficient != null) {
                return bestSufficient;
            }
            if (bestAvailable != null && bestAvailableCapacity > 0) {
                return bestAvailable;
            }
            for (Item item : ingredient.anyOf()) {
                if (RecipeRegistry.find(item).isPresent()) {
                    return item;
                }
            }
            return ingredient.anyOf().isEmpty() ? null : ingredient.anyOf().get(0);
        }

        /** Existing output plus the amount craftable from the recipe's currently carried inputs. */
        private int directCraftCapacity(Item item) {
            int existing = Math.max(0, counts.getOrDefault(item, 0));
            Optional<RecipeRegistry.Recipe> candidateRecipe = RecipeRegistry.find(item);
            if (candidateRecipe.isEmpty() || candidateRecipe.get().ingredients().isEmpty()) {
                return existing;
            }
            int crafts = Integer.MAX_VALUE;
            for (RecipeRegistry.Ingredient input : candidateRecipe.get().ingredients()) {
                int perCraft = Math.max(1, input.count());
                crafts = Math.min(crafts, countItems(input.anyOf()) / perCraft);
            }
            if (crafts <= 0 || crafts == Integer.MAX_VALUE) {
                return existing;
            }
            long capacity = (long) existing + (long) crafts * candidateRecipe.get().outputCount();
            return capacity >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) capacity;
        }

        private void consume(RecipeRegistry.Ingredient ingredient, int count) {
            int remaining = count;
            for (Item item : ingredient.anyOf()) {
                if (remaining <= 0) {
                    return;
                }
                int available = counts.getOrDefault(item, 0);
                int take = Math.min(available, remaining);
                if (take > 0) {
                    counts.put(item, available - take);
                    remaining -= take;
                }
            }
        }

        private void consumeItem(Item item, int count) {
            counts.put(item, Math.max(0, counts.getOrDefault(item, 0) - count));
        }

        private int countItems(Iterable<Item> items) {
            int total = 0;
            for (Item item : items) {
                total += counts.getOrDefault(item, 0);
            }
            return total;
        }

        private void consumeItems(Iterable<Item> items, int count) {
            int remaining = Math.max(0, count);
            for (Item item : items) {
                if (remaining <= 0) {
                    return;
                }
                int available = counts.getOrDefault(item, 0);
                int take = Math.min(available, remaining);
                if (take > 0) {
                    counts.put(item, available - take);
                    remaining -= take;
                }
            }
        }

        // GOALFIX-GF3:选熔炼燃料——优先背包已有的任意原木种类(spruce/birch…),都没有则默认橡木。
        private Item preferredFuelLog() {
            for (Item log : RecipeRegistry.LOGS) {
                if (counts.getOrDefault(log, 0) > 0) {
                    return log;
                }
            }
            return Items.OAK_LOG;
        }

        private int countAny(Set<Item> items) {
            int count = 0;
            for (Item item : items) {
                count += counts.getOrDefault(item, 0);
            }
            return count;
        }

        private int bestPickaxeTier() {
            int best = ToolTier.NONE;
            best = Math.max(best, tierIfPresent(Items.WOODEN_PICKAXE, ToolTier.WOOD));
            best = Math.max(best, tierIfPresent(Items.GOLDEN_PICKAXE, ToolTier.WOOD));
            best = Math.max(best, tierIfPresent(Items.STONE_PICKAXE, ToolTier.STONE));
            best = Math.max(best, tierIfPresent(Items.IRON_PICKAXE, ToolTier.IRON));
            best = Math.max(best, tierIfPresent(Items.DIAMOND_PICKAXE, ToolTier.DIAMOND));
            best = Math.max(best, tierIfPresent(Items.NETHERITE_PICKAXE, ToolTier.NETHERITE));
            return best;
        }

        private int tierIfPresent(Item item, int tier) {
            return counts.getOrDefault(item, 0) > 0 ? tier : ToolTier.NONE;
        }

        private void addStep(GoalStep step) {
            if (bestEffortDepth > 0) {
                step = step.asBestEffort();
            }
            if (!steps.isEmpty()) {
                GoalStep previous = steps.get(steps.size() - 1);
                if (previous.sameTarget(step)) {
                    steps.set(steps.size() - 1, previous.withCount(previous.count() + step.count()));
                    return;
                }
            }
            steps.add(step);
        }

        private static Item pickaxeForTier(int tier) {
            if (tier >= ToolTier.NETHERITE) {
                return Items.NETHERITE_PICKAXE;
            }
            if (tier >= ToolTier.DIAMOND) {
                return Items.DIAMOND_PICKAXE;
            }
            if (tier >= ToolTier.IRON) {
                return Items.IRON_PICKAXE;
            }
            if (tier >= ToolTier.STONE) {
                return Items.STONE_PICKAXE;
            }
            if (tier >= ToolTier.WOOD) {
                return Items.WOODEN_PICKAXE;
            }
            return Items.AIR;
        }

        // P2:矿物掉落物 → 对应矿石方块。覆盖全部常见矿(深板岩变种由 OreDigTask/expandOreFamilies 处理)。
        private static Block oreBlockFor(Item item) {
            if (item == Items.RAW_IRON) {
                return Blocks.IRON_ORE;
            }
            if (item == Items.RAW_COPPER) {
                return Blocks.COPPER_ORE;
            }
            if (item == Items.RAW_GOLD) {
                return Blocks.GOLD_ORE;
            }
            if (item == Items.COAL) {
                return Blocks.COAL_ORE;
            }
            if (item == Items.REDSTONE) {
                return Blocks.REDSTONE_ORE;
            }
            if (item == Items.LAPIS_LAZULI) {
                return Blocks.LAPIS_ORE;
            }
            if (item == Items.DIAMOND) {
                return Blocks.DIAMOND_ORE;
            }
            if (item == Items.EMERALD) {
                return Blocks.EMERALD_ORE;
            }
            return null;
        }

        private static SmeltRecipe smeltRecipeFor(Item output) {
            // S5:冶炼映射收敛到 SmeltChain 单一源(矿锭/石/木炭 + 熟肉/玻璃/烤土豆)。
            Item raw = SmeltChain.rawFor(output);
            return raw == null ? null : new SmeltRecipe(raw, output);
        }

        // S4:作物产出 → 作物规格(供 FARM 路由),非支持作物返回 null。
        private static FarmAction.CropSpec cropSpecForProduce(Item produce) {
            if (produce == Items.WHEAT) {
                return FarmAction.cropSpec("wheat");
            }
            if (produce == Items.CARROT) {
                return FarmAction.cropSpec("carrot");
            }
            if (produce == Items.POTATO) {
                return FarmAction.cropSpec("potato");
            }
            return null;
        }

        private static int divideRoundUp(int value, int divisor) {
            return (value + divisor - 1) / divisor;
        }

        private static int divideRoundUpSafe(int value, int divisor) {
            if (value <= 0) {
                return 0;
            }
            if (divisor <= 0) {
                throw new IllegalArgumentException("invalid_divisor:" + divisor);
            }
            return (int) Math.min(Integer.MAX_VALUE,
                    ((long) value + divisor - 1L) / divisor);
        }

        private static String id(Item item) {
            return Registries.ITEM.getId(item).toString();
        }
    }

    private record SmeltRecipe(Item input, Item output) {
    }
}
