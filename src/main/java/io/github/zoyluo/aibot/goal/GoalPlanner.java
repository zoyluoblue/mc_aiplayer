package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.craft.AcquisitionHints;
import io.github.zoyluo.aibot.craft.SmeltChain;
import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mining.MiningChain;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import net.minecraft.block.Block;
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
    // 第4层 备粮用:常见食物(生/熟肉 + 面包)。
    private static final List<Item> FOOD_ITEMS = List.of(
            Items.BEEF, Items.COOKED_BEEF, Items.PORKCHOP, Items.COOKED_PORKCHOP,
            Items.MUTTON, Items.COOKED_MUTTON, Items.CHICKEN, Items.COOKED_CHICKEN,
            Items.RABBIT, Items.COOKED_RABBIT, Items.BREAD);
    private static final int FOOD_TARGET = 4;
    private static final int DESCEND_THRESHOLD = 8; // bot 高于矿层超过这么多格,先下竖井到矿层再挖

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
        Planner planner = new Planner(inventoryCounts(bot), Math.max(1, AIBotConfig.get().goal().maxPlanDepth()), bot.getBlockPos().getY());
        planner.ensureGoal(goal, 0, new HashSet<>());
        return new GoalPlan(goal, List.copyOf(mergeGathers(planner.steps)), List.copyOf(planner.unresolved));
    }

    // 第A层 集中采集(挖钻石失败根因修复):把所有 GATHER 同类需求合并并**提到计划最前**,
    // 让 bot 先在地表一次砍够全部木头(GATHER 无前置依赖,后续 CRAFT 都在其后,依赖不破)。
    // 否则计划会交错"地下挖矿(把 bot 带到 y≈59)"与"地表砍树(够不到地表树)"→ no_resource_nearby → goal_failed。
    // 深层贵重矿的最佳挖掘高度(1.18+ 地形);非深层矿返回 MAX_VALUE(不触发"先下矿层")。
    private static int bestMiningY(Set<Block> ores) {
        return MiningChain.bestY(ores); // S2:推荐挖掘 Y 层收敛到 MiningChain 单一数据源(混合矿取最深层)
    }

    private static List<GoalStep> mergeGathers(List<GoalStep> steps) {
        Map<Item, Integer> gatherTotals = new LinkedHashMap<>();
        for (GoalStep step : steps) {
            if (step.kind() == GoalStep.Kind.GATHER) {
                gatherTotals.merge(step.item(), step.count(), Integer::sum);
            }
        }
        if (gatherTotals.isEmpty()) {
            return steps;
        }
        List<GoalStep> result = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : gatherTotals.entrySet()) {
            result.add(GoalStep.gather(entry.getKey(), entry.getValue()));
        }
        for (GoalStep step : steps) {
            if (step.kind() != GoalStep.Kind.GATHER) {
                result.add(step);
            }
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
        private final List<GoalStep> steps = new ArrayList<>();
        private final List<String> unresolved = new ArrayList<>();

        private Planner(Map<Item, Integer> counts, int maxDepth, int botY) {
            this.counts = counts;
            this.maxDepth = maxDepth;
            this.botY = botY;
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
            // 种子不在倒推里求:FarmTask 运行期会就地找已有作物/草获取并从收割中续种;
            // 若确实没种子,FarmTask 自身会 fail 并由 GoalExecutor 中文汇报,不在纯函数里假设世界有草。
            addStep(GoalStep.farm(g.crop(), g.seed(), g.produce(), remaining));
            counts.merge(g.produce(), remaining, Integer::sum);
            return true;
        }

        private boolean hasAnyHoe() {
            return counts.getOrDefault(Items.WOODEN_HOE, 0) > 0
                    || counts.getOrDefault(Items.STONE_HOE, 0) > 0
                    || counts.getOrDefault(Items.IRON_HOE, 0) > 0
                    || counts.getOrDefault(Items.DIAMOND_HOE, 0) > 0
                    || counts.getOrDefault(Items.GOLDEN_HOE, 0) > 0
                    || counts.getOrDefault(Items.NETHERITE_HOE, 0) > 0;
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

        // 第4层 备粮(best-effort):食物不足则下一个 HUNT 步,运行期去猎肉续航;没动物时 GoalExecutor
        // 跳过此步(见 handleStepFailure),不阻断挖矿目标。总返回 true,不影响规划成败。
        private boolean ensureFood(int depth, Set<String> visiting) {
            int food = 0;
            for (Item f : FOOD_ITEMS) {
                food += counts.getOrDefault(f, 0);
            }
            if (food >= FOOD_TARGET) {
                return true;
            }
            addStep(GoalStep.hunt(FOOD_TARGET - food));
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

        private boolean craftItem(Item item, int missing, RecipeRegistry.Recipe recipe, int depth, Set<String> visiting) {
            int crafts = divideRoundUp(missing, recipe.outputCount());
            if (recipe.needsCraftingTable() && item != Items.CRAFTING_TABLE) {
                if (!ensureItem(Items.CRAFTING_TABLE, 1, depth + 1, visiting)) {
                    return false;
                }
            }
            for (RecipeRegistry.Ingredient ingredient : recipe.ingredients()) {
                int need = ingredient.count() * crafts;
                Item candidate = chooseIngredient(ingredient);
                if (candidate == null || !ensureItem(candidate, counts.getOrDefault(candidate, 0) + need, depth + 1, visiting)) {
                    unresolved.add("missing:" + ingredient.anyOf() + " x" + need + " for " + id(item));
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
            if (item == Items.COBBLESTONE) {
                if (!ensurePickaxeTier(ToolTier.WOOD, depth + 1, visiting)) {
                    return false;
                }
                addStep(GoalStep.mine(Blocks.STONE, missing));
                counts.merge(Items.COBBLESTONE, missing, Integer::sum);
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
            // GOALFIX-GF2:1 个原木在熔炉可烧 1.5 个物品,燃料按 ceil(missing/1.5) 估算,避免高估 ~33%。
            // GOALFIX-GF3:燃料优先用背包已有的任意原木种类(与 chooseIngredient 一致),无则默认橡木。
            Item fuel = preferredFuelLog();
            int fuelLogs = Math.max(1, (int) Math.ceil(missing / 1.5));
            if (!ensureItem(fuel, fuelLogs, depth + 1, visiting)) {
                return false;
            }
            consumeItem(recipe.input(), missing);
            consumeItem(fuel, fuelLogs);
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
