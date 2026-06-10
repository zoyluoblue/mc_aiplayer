package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.craft.AcquisitionHints;
import io.github.zoyluo.aibot.craft.SmeltChain;
import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mining.MiningChain;
import io.github.zoyluo.aibot.mining.OreProspector;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.task.HuntTask;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

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
        Planner planner = new Planner(inventoryCounts(bot), Math.max(1, AIBotConfig.get().goal().maxPlanDepth()),
                bot.getBlockPos().getY(), hasPrey, hasGrass, hasBerries);
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
        if (!stack.isEmpty()) {
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    private static final class Planner {
        private final Map<Item, Integer> counts;
        private final int maxDepth;
        private final int botY; // 规划时 bot 的 Y,用于判断深层矿是否需先下竖井到矿层
        private final boolean hasPreyNearby;  // 周围有可猎动物(食物择源:有→打猎)
        private final boolean hasGrassNearby; // 周围有草(食物择源:无动物但有草→种植面包)
        private final boolean hasBerriesNearby; // 周围有甜浆果丛(食物择源:无动物无现成粮→采浆果兜底)
        private final List<GoalStep> steps = new ArrayList<>();
        private final List<String> unresolved = new ArrayList<>();

        private Planner(Map<Item, Integer> counts, int maxDepth, int botY,
                        boolean hasPreyNearby, boolean hasGrassNearby, boolean hasBerriesNearby) {
            this.counts = counts;
            this.maxDepth = maxDepth;
            this.botY = botY;
            this.hasPreyNearby = hasPreyNearby;
            this.hasGrassNearby = hasGrassNearby;
            this.hasBerriesNearby = hasBerriesNearby;
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
            // 第3层:深层贵重矿(需铁镐及以上,如钻石/金/红石/绿宝石)下矿凶险 → 先备一身铁甲+铁剑再开挖。
            if (tier >= ToolTier.IRON) {
                if (!ensureArmor(false, depth + 1, visiting)) {
                    return false;
                }
                // 分级装备(用户选):钻石/金/红石/绿宝石等深危矿(都在 Y<0、岩浆+怪多)额外备铁剑+盾——
                // 主动清怪 + 挡爆炸/远程。best-effort:护甲维持头胸折中(不加腿靴),剑/盾做不出也不阻断挖矿。
                if (counts.getOrDefault(Items.IRON_SWORD, 0) <= 0) {
                    ensureItem(Items.IRON_SWORD, 1, depth + 1, visiting);
                }
                if (counts.getOrDefault(Items.SHIELD, 0) <= 0) {
                    ensureItem(Items.SHIELD, 1, depth + 1, visiting);
                }
                // 规避:备火把(best-effort),供地下黑暗处点亮防刷怪。
                ensureTorches(depth + 1, visiting);
                // 第4层:再备点粮(best-effort,周围没动物不阻断)。
                ensureFood(depth + 1, visiting);
            }
            // 挖深层矿重构 P1:bot 远高于矿层 → 先下竖井到矿层,再挖。否则在错误高度(实测 Y=48)
            // 反复"锁定斜下方够不到的矿→水平掘隧道→dist 卡死→no_progress",卡死 11 分钟。
            int mineY = bestMiningY(expanded);
            if (botY - mineY > DESCEND_THRESHOLD) {
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
                // 黑曜石:需钻石镐(ToolTier 已映射 DIAMOND);倒推钻石镐链 + 直接挖该块(非矿石,不走 oreBlockFor)。
                if (!ensurePickaxeTier(ToolTier.DIAMOND, depth + 1, visiting)) {
                    return false;
                }
                addStep(GoalStep.mine(Blocks.OBSIDIAN, missing));
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
                fuelLogs = Math.max(1, (int) Math.ceil(fuelDeficit / 1.5));
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
