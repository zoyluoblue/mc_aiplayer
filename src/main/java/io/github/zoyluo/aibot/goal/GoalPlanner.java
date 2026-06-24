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
import io.github.zoyluo.aibot.mining.OreProspector;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.task.BlueprintLoader;
import io.github.zoyluo.aibot.task.BlueprintSchema;
import io.github.zoyluo.aibot.task.HuntTask;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

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
    // 第4层 备粮:达标只认"熟食/面包"(高饱食、安全);生肉是中间品,需烤(见 ensureFood 闭环)。
    private static final List<Item> COOKED_FOOD_ITEMS = List.of(
            Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.COOKED_CHICKEN,
            Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.BAKED_POTATO, Items.BREAD);
    private static final List<Item> RAW_MEAT_ITEMS = List.of(
            Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT);
    private static final int FOOD_TARGET = 4;
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
        // Goal.Food 感知择源:规划时扫一眼周围实际有什么,据此选打猎/种植(见 ensureFoodTo),不再绑死打猎
        //(没动物的地形硬派打猎只会抓瞎)。其余目标不受这两个标志影响。
        boolean hasPrey = HuntTask.hasPreyNearby(bot);
        boolean hasGrass = OreProspector.nearest(bot.getServerWorld(), bot.getBlockPos(),
                FOOD_GRASS_SCAN, GoalPlanner::isGrassForSeeds) != null;
        // 荒芜兜底源:针叶林等生物群系动物稀少(实测 hunt 漫游 10 次 1092t 仍 0 猎物)但常有甜浆果丛——
        // 无动物可猎时浆果是"能立刻吃上"的最后手段。
        boolean hasBerries = OreProspector.nearest(bot.getServerWorld(), bot.getBlockPos(),
                FOOD_GRASS_SCAN, state -> state.isOf(Blocks.SWEET_BERRY_BUSH)) != null;
        // 附近矿感知:规划时扫一眼目标矿是否已在身边(48 格)。在 → 不下潜矿层直接挖
        //(站在铁矿旁还先挖 70 格竖井到 Y16 是蠢的;且竖井穿天然地形洞/水/沙砾极易 descend_blocked,
        // 实测场景地表化后 descend 类失败爆发,旧 y6 出生点 botY<mineY 恰好从不触发才一直没暴露)。
        java.util.function.Predicate<Set<Block>> oreNearby = ores -> {
            if (OreProspector.nearest(bot.getServerWorld(), bot.getBlockPos(), 48,
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
        Planner planner = new Planner(inventoryCounts(bot), Math.max(1, AIBotConfig.get().goal().maxPlanDepth()),
                bot.getBlockPos().getY(), hasPrey, hasGrass, hasBerries, oreNearby);
        planner.ensureGoal(goal, 0, new HashSet<>());
        return new GoalPlan(goal, List.copyOf(mergeGathers(planner.steps)), List.copyOf(planner.unresolved));
    }

    // 割草取种子的草类(种植面包链的起点):附近有草,才把"种植"作为无动物时的食物源。
    private static boolean isGrassForSeeds(BlockState state) {
        return state.isOf(Blocks.SHORT_GRASS) || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN) || state.isOf(Blocks.LARGE_FERN);
    }

    // 第A层 集中采集(挖钻石失败根因修复):把所有 GATHER 同类需求合并并**提到计划最前**,
    // 让 bot 先在地表一次砍够全部木头(GATHER 无前置依赖,后续 CRAFT 都在其后,依赖不破)。
    // 否则计划会交错"地下挖矿(把 bot 带到 y≈59)"与"地表砍树(够不到地表树)"→ no_resource_nearby → goal_failed。
    // 深层贵重矿的最佳挖掘高度(1.18+ 地形);非深层矿返回 MAX_VALUE(不触发"先下矿层")。
    private static int bestMiningY(Set<Block> ores) {
        return MiningChain.bestY(ores); // S2:推荐挖掘 Y 层收敛到 MiningChain 单一数据源(混合矿取最深层)
    }

    private static List<GoalStep> mergeGathers(List<GoalStep> steps) {
        // GATHER:无前置依赖 → 合并并提到计划最前(一次在地表砍够)。
        // S9:同物 MINE(如圆石)合并数量到**首次出现位置**——不提最前,因 MINE 依赖镐,提前会无镐空挖。
        Map<Item, Integer> gatherTotals = new LinkedHashMap<>();
        Map<Block, Integer> mineTotals = new LinkedHashMap<>();
        for (GoalStep step : steps) {
            if (step.kind() == GoalStep.Kind.GATHER) {
                gatherTotals.merge(step.item(), step.count(), Integer::sum);
            } else if (step.kind() == GoalStep.Kind.MINE) {
                mineTotals.merge(step.block(), step.count(), Integer::sum);
            }
        }
        if (gatherTotals.isEmpty() && mineTotals.isEmpty()) {
            return steps;
        }
        List<GoalStep> result = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : gatherTotals.entrySet()) {
            result.add(GoalStep.gather(entry.getKey(), entry.getValue()));
        }
        HashSet<Block> emittedMine = new HashSet<>();
        for (GoalStep step : steps) {
            if (step.kind() == GoalStep.Kind.GATHER) {
                continue; // 已提到最前
            }
            if (step.kind() == GoalStep.Kind.MINE) {
                if (emittedMine.add(step.block())) {
                    result.add(GoalStep.mine(step.block(), mineTotals.get(step.block()))); // 首次出现:并入总量
                }
                continue; // 后续同物 MINE 已合并,跳过
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

    private static final class Planner {
        private final Map<Item, Integer> counts;
        private final int maxDepth;
        private final int botY; // 规划时 bot 的 Y,用于判断深层矿是否需先下竖井到矿层
        private final boolean hasPreyNearby;  // 周围有可猎动物(食物择源:有→打猎)
        private final boolean hasGrassNearby; // 周围有草(食物择源:无动物但有草→种植面包)
        private final boolean hasBerriesNearby; // 周围有甜浆果丛(食物择源:无动物无现成粮→采浆果兜底)
        private final java.util.function.Predicate<Set<Block>> oreNearby; // 目标矿是否已在身边(48格)→跳过下潜
        private final List<GoalStep> steps = new ArrayList<>();
        private final List<String> unresolved = new ArrayList<>();

        private Planner(Map<Item, Integer> counts, int maxDepth, int botY,
                        boolean hasPreyNearby, boolean hasGrassNearby, boolean hasBerriesNearby,
                        java.util.function.Predicate<Set<Block>> oreNearby) {
            this.counts = counts;
            this.maxDepth = maxDepth;
            this.botY = botY;
            this.hasPreyNearby = hasPreyNearby;
            this.hasGrassNearby = hasGrassNearby;
            this.hasBerriesNearby = hasBerriesNearby;
            this.oreNearby = oreNearby;
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

        // 背包是否已有现成木料够做一把木剑(2 木板 + 1 木棍;1 原木→4 木板足够)。无料则不专程砍树(避免无树发呆)。
        private boolean hasSwordMaterial() {
            int logs = 0;
            for (Item l : RecipeRegistry.LOGS) {
                logs += counts.getOrDefault(l, 0);
            }
            if (logs >= 1) {
                return true;
            }
            int planks = 0;
            for (Item p : RecipeRegistry.PLANKS) {
                planks += counts.getOrDefault(p, 0);
            }
            return planks >= 2;
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
            if (!ensurePickaxeTier(tier, depth + 1, visiting)) {
                return false;
            }
            // 大批量挖矿备足镐(治本·real_armor 26铁挖到9超时):石镐~131耐久,挖24+铁含掘进要磨断多把。
            // 途中磨穿→resupply 就地合会打断大配额单 mine_ore、丢挖矿进度→ore_dig_timeout。按量预备(含掘进≈1把/12块)
            // 一把磨穿换备用、不中断,一趟挖完。仅 STONE 档(挖铁/铜,圆石无限廉价)预备;IRON 档(钻石)已由备铁锭兜。
            if (tier <= ToolTier.STONE && remaining >= 12) {
                ensureItem(pickaxeForTier(tier), 1 + remaining / 12, depth + 1, visiting);
            }
            // 精简速降(用户选·治本):深危矿(钻石/金/红石/绿宝石,Y<0 岩浆+怪多)**只备最小必需**——
            // 火把(廉价:煤+棍;照明=从源头少刷怪,性价比最高)。**砍掉铁甲/铁剑/盾/烤肉**——它们要先挖
            // 5+ 铁+熔炼+合成,把"挖钻"变成又长又险的地表远征(跨昼夜撞尽夜间怪海/淹死/崖壁,real_diamond
            // 实测全栽在这段而非挖矿本身)。深层危险改靠**反应式生存**兜底:濒死封墙(emergency_entomb)、
            // 岩浆封堵(ore_dig_fluid_seal)、黑暗撤离——遇险才花代价,不提前过度武装。暴露时间砍一个数量级。
            if (tier >= ToolTier.IRON) {
                ensureTorches(depth + 1, visiting); // 唯一保留的主动备货:火把(best-effort,缺也不阻断)
            }
            // 挖深层矿重构 P1:bot 远高于矿层 → 先下竖井到矿层,再挖。否则在错误高度(实测 Y=48)
            // 反复"锁定斜下方够不到的矿→水平掘隧道→dist 卡死→no_progress",卡死 11 分钟。
            int mineY = bestMiningY(expanded);
            // 附近已有目标矿 → 不下潜直接挖(站在矿旁先挖竖井到矿层是蠢的,且竖井穿天然地形
            // 极易 blocked;实操"带 bot 到矿边让它挖"也走这条捷径)。
            boolean willDescend = botY - mineY > DESCEND_THRESHOLD && !oreNearby.test(expanded);
            // 深潜耐久兜底(治本·real_diamond 手测死因):深处铁镐磨穿后无法就地补给——深层没树做熔炉/燃料,
            // resupply 倒推"采橡木→合熔炉→熔炼铁锭"在 Y<0 必败(96 格无树)→ 反复 replan 卡死被怪杀。
            // 解法:深潜前多备 3 铁锭备料(地表一次性多挖/熔炼好)。镐磨穿时深处直接用备料+背包工作台+棍合新镐
            //(只需 craft,无需树/熔炉/熔炼),不被困死深处。仅深潜才备(就近挖在地表附近,坏了能正常补)。
            if (tier >= ToolTier.IRON && willDescend) {
                ensureItem(Items.IRON_INGOT, SPARE_IRON_INGOTS, depth + 1, visiting);
                // 【实验回退】带铁套加成回攻钻石实测净拖累(real_diamond 0/6 vs 精简基线3/6):深潜前备头胸甲(13铁)
                // 把链拉太长——bot 在36000t内多挖13铁+熔炼+合甲、还没下潜挖钻就超时(5/6 timeout),且铁甲一次没穿上
                // (前段就败、根本没走到深潜)。survival收益=0、代价=链翻倍。故撤回备甲,深潜survival改靠反应式
                // (入浆自救/濒死入土/点火把,遇险才花代价)。下潜穿甲(DescendToYTask.onStart equipBestArmor)保留:零成本,有甲就穿。
            }
            if (willDescend) {
                addStep(GoalStep.descendToY(mineY));
            }
            addStep(GoalStep.mineOre(expanded, remaining));
            for (Item drop : drops) {
                counts.merge(drop, remaining, Integer::sum);
                break;
            }
            return true;
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
            if (counts.getOrDefault(Items.TORCH, 0) >= TORCH_TARGET) {
                return;
            }
            ensureItem(Items.TORCH, TORCH_TARGET, depth + 1, visiting);
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
            if (!ensureItem(g.item(), g.count(), depth + 1, visiting)) {
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

        // 猎→烤闭环:凑够 target 个熟食/面包(高饱食、安全)。挖矿备粮用 FOOD_TARGET;
        // "去打猎/去搞点吃的"口语入口(Goal.Food)用指定量。
        // 没动物/没熔炉/没燃料时 GoalExecutor 跳过相应 best-effort 步(见 handleStepFailure),不阻断主目标。
        private boolean ensureFoodTo(int target, int depth, Set<String> visiting) {
            int cooked = 0;
            for (Item f : COOKED_FOOD_ITEMS) {
                cooked += counts.getOrDefault(f, 0);
            }
            cooked += counts.getOrDefault(Items.SWEET_BERRIES, 0) / 2; // 浆果按 2:1 折算(饱食低,2 颗≈1 份)
            if (cooked >= target) {
                return true;
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
            // 燃料按 2 倍需求备:COOK_FOOD 是 cookAll 模式(背包所有生肉一起烤,猎获常超 needCooked),
            // 且烤途中断火重启有损耗——按精确需求备实测 out_of_fuel(地狱 seed R9)。宁多备一根木。
            int fuelDeficit = needCooked * 2 - coalLike * 8;
            if (fuelDeficit > 0) {
                Item fuelLog = preferredFuelLog();
                int logsForFuel = Math.max(1, (int) Math.ceil(fuelDeficit / 1.5));
                ensureItem(fuelLog, counts.getOrDefault(fuelLog, 0) + logsForFuel, depth + 1, visiting);
            }
            if (huntNeed > 0) {
                // 没剑且手头已有现成木料 → 顺手做把木剑;没料不专程砍树(避免无树发呆),空手/现有工具直接猎。
                if (!hasAnySword() && hasSwordMaterial()) {
                    ensureItem(Items.WOODEN_SWORD, 1, depth + 1, visiting);
                }
                addStep(GoalStep.hunt(huntNeed));
            }
            addStep(GoalStep.cookFood(needCooked)); // 烤成熟肉(背包已有生肉也一并烤)
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
            if (recipe.needsCraftingTable() && item != Items.CRAFTING_TABLE) {
                if (!ensureItem(Items.CRAFTING_TABLE, 1, depth + 1, visiting)) {
                    rollbackSteps(stepsBefore);
                    return false;
                }
            }
            for (RecipeRegistry.Ingredient ingredient : recipe.ingredients()) {
                int need = ingredient.count() * crafts;
                Item candidate = chooseIngredient(ingredient);
                // 只补缺口:ensureItem 内部按 desired-available 计算,传 need 即可。
                // (原写 counts+need = 已有量也再凑一份 → 背包已有材料还重复采/挖,实测有 8 石料做炉仍去挖石。)
                if (candidate == null || !ensureItem(candidate, need, depth + 1, visiting)) {
                    unresolved.add("missing:" + ingredient.anyOf() + " x" + need + " for " + id(item));
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
                // 黑曜石:需钻石镐(ToolTier 已映射 DIAMOND,否则破坏无掉落)。15 块远超自然矿脉——
                // 真实玩家靠"水浇岩浆源现造"。倒推:钻石镐 + 几个空桶(软放水,缺桶时任务直接确定性成型)
                // + MAKE_OBSIDIAN 步循环造够数。比原"假设世界已有 N 块黑曜石"(mine 步)对任意有岩浆环境
                // 都鲁棒;无岩浆时 CreateObsidianTask 干净失败(create_obsidian_no_lava)交大脑换策略。
                if (!ensurePickaxeTier(ToolTier.DIAMOND, depth + 1, visiting)) {
                    return false;
                }
                // 附近有现成黑曜石(自然矿脉/预放)→ 直接挖;扫不到才水浇岩浆现造(凑 15 块的真实手段)。
                // 二分支兼顾:既不丢"挖现成黑曜石"(achieve_obsidian 等),又补"无现成时现造"的新能力。
                if (oreNearby.test(java.util.Set.of(Blocks.OBSIDIAN))) {
                    addStep(GoalStep.mine(Blocks.OBSIDIAN, missing));
                } else {
                    // 备空桶(3 铁/个),保守封顶 4 个免把铁需求顶到天(15 桶=45 铁);软放水不强求,缺桶不阻断。
                    int buckets = Math.min(missing, 4);
                    if (!ensureItem(Items.BUCKET, buckets, depth + 1, visiting)) {
                        unresolved.add("obsidian_buckets_best_effort:" + buckets);
                    }
                    addStep(GoalStep.makeObsidian(missing));
                }
                counts.merge(Items.OBSIDIAN, missing, Integer::sum);
                return true;
            }
            // P2:矿物掉落物 → 对应矿石(统一映射表)。挖该矿所需镐档由 ToolTier 决定,
            // ensureMineOre 内部会先 ensurePickaxeTier 自动补齐镐链(如钻石需铁镐 → 先倒推铁镐)。
            Block oreOf = oreBlockFor(item);
            if (oreOf != null) {
                return ensureMineOre(Set.of(oreOf), missing, depth + 1, visiting);
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
            Item fuel = preferredFuelLog();
            int fuelLogs = 0;
            if (fuelDeficit > 0) {
                // +1 冗余:执行层与账本天然漂移——craft 换板按整原木消耗、smelt chooseFuel 全额
                // 单品种装填,两头贪心合计常差 1-2 板,链尾'合成木棍'就 need planks 重采;此时场景
                // 树若已砍光直接 no_resource(iron_pickaxe 套跑实测)。多砍一根木头吸收漂移。
                fuelLogs = Math.max(1, (int) Math.ceil(fuelDeficit / 1.5)) + 1;
                if (!ensureItem(fuel, fuelLogs, depth + 1, visiting)) {
                    return false;
                }
            }
            consumeItem(recipe.input(), missing);
            if (fuelLogs > 0) {
                consumeItem(fuel, fuelLogs);
            }
            counts.merge(recipe.output(), missing, Integer::sum);
            addStep(GoalStep.smelt(recipe.input(), recipe.output(), missing));
            return true;
        }

        private Item chooseIngredient(RecipeRegistry.Ingredient ingredient) {
            for (Item item : ingredient.anyOf()) {
                if (counts.getOrDefault(item, 0) >= ingredient.count()) {
                    return item;
                }
            }
            for (Item item : ingredient.anyOf()) {
                if (RecipeRegistry.find(item).isPresent()) {
                    return item;
                }
            }
            return ingredient.anyOf().isEmpty() ? null : ingredient.anyOf().get(0);
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

        private static String id(Item item) {
            return Registries.ITEM.getId(item).toString();
        }
    }

    private record SmeltRecipe(Item input, Item output) {
    }
}
