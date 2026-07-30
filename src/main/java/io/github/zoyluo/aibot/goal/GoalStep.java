package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.craft.ItemNames;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.Set;
import java.util.stream.Collectors;

public record GoalStep(Kind kind,
                       Item item,
                       int count,
                       Block block,
                       Set<Block> ores,
                       Item input,
                       Item output,
                       BlockPos pos,
                       String tag,
                       boolean bestEffort) {
    public enum Kind {
        GATHER,
        MINE,
        MINE_ORE,
        MINING_SERVICE,
        CRAFT,
        SMELT,
        MOVE,
        FARM,
        HUNT,
        COOK_FOOD,
        MILK_COW,
        PLACE_STATIONS,
        STOCKPILE,
        DESCEND_TO_Y,
        ACQUIRE_WATER,
        MAKE_OBSIDIAN,
        BUILD
    }

    public GoalStep {
        count = kind == Kind.MINING_SERVICE && tag != null
                && tag.startsWith("rare_ore_batch:")
                ? Math.max(0, count) : Math.max(1, count);
        ores = ores == null ? Set.of() : Set.copyOf(ores);
        pos = pos == null ? null : pos.toImmutable();
    }

    public static GoalStep gather(Item item, int count) {
        return new GoalStep(Kind.GATHER, item, count, null, Set.of(), null, null, null, null, false);
    }

    public static GoalStep mine(Block block, int count) {
        return new GoalStep(Kind.MINE, null, count, block, Set.of(), null, null, null, null, false);
    }

    public static GoalStep mineOre(Set<Block> ores, int count) {
        return new GoalStep(Kind.MINE_ORE, null, count, null, ores, null, null, null, null, false);
    }

    /** Long-running mining checkpoint between bounded ore batches. */
    public static GoalStep miningService(Set<Block> ores, int completedTarget) {
        return miningService(ores, completedTarget, false);
    }

    public static GoalStep miningService(Set<Block> ores,
                                         int completedTarget,
                                         boolean maintainTunnelingTools) {
        return new GoalStep(Kind.MINING_SERVICE, null, Math.max(1, completedTarget),
                null, ores, null, null, null,
                maintainTunnelingTools ? "after_batch:channel_tools" : "after_batch", false);
    }

    /** Capacity-only mining hand-off. Unlike an inter-batch boundary it does not rebuild the full
     * tunnelling pool; it physically retires junk and proves four delivery/crafting slots before
     * the same batch retries or its parent expedition starts the next underground dependency. */
    public static GoalStep miningHandoffService(Set<Block> ores, int completedTarget) {
        return miningHandoffService(ores, completedTarget,
                io.github.zoyluo.aibot.mining.MiningBudget.EMERGENCY_STONE_LIKE);
    }

    public static GoalStep miningHandoffService(Set<Block> ores,
                                                int completedTarget,
                                                int protectedStoneLike) {
        return new GoalStep(Kind.MINING_SERVICE, null, Math.max(1, completedTarget),
                null, ores, null, null, null,
                "after_final_handoff:stone=" + Math.max(
                        io.github.zoyluo.aibot.mining.MiningBudget.EMERGENCY_STONE_LIKE,
                        protectedStoneLike), false);
    }

    /** Exact service boundary inside one original-target long rare-ore mission. */
    public static GoalStep rareOreService(Set<Block> ores,
                                          int completedTarget,
                                          int missionTarget) {
        if (missionTarget < 8 || completedTarget < 0 || completedTarget >= missionTarget) {
            throw new IllegalArgumentException("invalid_rare_ore_service_step:target="
                    + missionTarget + ":boundary=" + completedTarget);
        }
        return new GoalStep(Kind.MINING_SERVICE, null, completedTarget,
                null, ores, null, null, null,
                "rare_ore_batch:channel_tools:target=" + missionTarget, false);
    }

    /** One-time mission-owned inventory seal immediately before a 64-diamond final descent. */
    public static GoalStep rareDescentKitService(Set<Block> ores, int missionTarget) {
        if (missionTarget != 64) {
            throw new IllegalArgumentException(
                    "invalid_rare_descent_kit_target:" + missionTarget);
        }
        return new GoalStep(Kind.MINING_SERVICE, null, 1,
                null, ores, null, null, null,
                "rare_descent_kit:channel_tools:target=" + missionTarget, false);
    }

    /** Eight-block service boundary inside one original-target obsidian transaction. */
    public static GoalStep obsidianService(int completedTarget) {
        return obsidianService(completedTarget, 32);
    }

    public static GoalStep obsidianService(int completedTarget, int transactionTarget) {
        if (completedTarget <= 0 || completedTarget % 8 != 0
                || transactionTarget <= completedTarget) {
            throw new IllegalArgumentException("invalid_obsidian_service_step:target="
                    + transactionTarget + ":boundary=" + completedTarget);
        }
        return new GoalStep(Kind.MINING_SERVICE, null, completedTarget,
                null, Set.of(Blocks.OBSIDIAN), null, null, null,
                "obsidian_8:channel_tools:target="
                        + transactionTarget, false);
    }

    /** One-time readiness gate before the first obsidian search transaction opens. */
    public static GoalStep obsidianPreflight() {
        return obsidianPreflight(32);
    }

    public static GoalStep obsidianPreflight(int transactionTarget) {
        if (transactionTarget <= 0) {
            throw new IllegalArgumentException(
                    "invalid_obsidian_preflight_step:" + transactionTarget);
        }
        return new GoalStep(Kind.MINING_SERVICE, null, 1,
                null, Set.of(Blocks.OBSIDIAN), null, null, null,
                "obsidian_preflight:channel_tools:target="
                        + transactionTarget, false);
    }

    public static GoalStep craft(Item item, int count) {
        return new GoalStep(Kind.CRAFT, item, count, null, Set.of(), null, null, null, null, false);
    }

    public static GoalStep smelt(Item input, Item output, int count) {
        return new GoalStep(Kind.SMELT, null, count, null, Set.of(), input, output, null, null, false);
    }

    public static GoalStep move(BlockPos pos) {
        return new GoalStep(Kind.MOVE, null, 1, null, Set.of(), null, null, pos, null, false);
    }

    /** P3:FARM 步——block=作物方块,input=种子,item=产出物,count=要收的数量。 */
    public static GoalStep farm(Block crop, Item seed, Item produce, int count) {
        return new GoalStep(Kind.FARM, produce, count, crop, Set.of(), seed, null, null, null, false);
    }

    /** 第4层:HUNT 步——猎杀动物获取 count 个生肉(best-effort:周围没动物时跳过,不阻断挖矿目标)。 */
    public static GoalStep hunt(int count) {
        return new GoalStep(Kind.HUNT, null, count, null, Set.of(), null, null, null, null, false);
    }

    /** Expedition hunt batch. The tag prevents adjacent bounded batches from being merged. */
    public static GoalStep huntBatch(int count, int batchIndex) {
        return new GoalStep(Kind.HUNT, null, count, null, Set.of(), null, null, null,
                "food_batch:" + Math.max(1, batchIndex), false);
    }

    /** P0 食物闭环:COOK_FOOD 步——把背包里所有生食烤成 count 个熟食(best-effort,无熔炉/燃料则跳过)。 */
    public static GoalStep cookFood(int count) {
        return new GoalStep(Kind.COOK_FOOD, null, count, null, Set.of(), null, null, null, null, false);
    }

    /** 蛋糕链:MILK_COW 步——用空桶挤 count 桶牛奶(需背包有空桶 + 周围有牛;缺则 best-effort 失败)。 */
    public static GoalStep milkCow(int count) {
        return new GoalStep(Kind.MILK_COW, null, count, null, Set.of(), null, null, null, null, false);
    }

    /** Phase2:放置工作台/熔炉/箱子三件套(方块固定,无参数)。 */
    public static GoalStep placeStations() {
        return new GoalStep(Kind.PLACE_STATIONS, null, 1, null, Set.of(), null, null, null, null, false);
    }

    /** Phase3:把背包资源存进附近箱子(best-effort;item 仅作语义标记)。 */
    public static GoalStep stockpile(Item item) {
        return new GoalStep(Kind.STOCKPILE, item, 1, null, Set.of(), null, null, null, null, false);
    }

    /** 挖深层矿:DESCEND_TO_Y 步——下挖到指定 Y(用 pos.y 携带,允许负数;x/z 忽略)。 */
    public static GoalStep descendToY(int y) {
        return new GoalStep(Kind.DESCEND_TO_Y, null, 1, null, Set.of(), null, null,
                new BlockPos(0, y, 0), null, false);
    }

    /** 黑曜石远征取水：携空桶返回任务地表锚点，物理搜索可见水源并用原版交互装水。 */
    public static GoalStep acquireWater() {
        return new GoalStep(Kind.ACQUIRE_WATER, null, 1, null, Set.of(), null, null,
                null, "strict_survival", false);
    }

    /** 造黑曜石:MAKE_OBSIDIAN 步——水浇岩浆现造 count 块(需背包桶+钻石镐)。 */
    public static GoalStep makeObsidian(int count) {
        return new GoalStep(Kind.MAKE_OBSIDIAN, null, count, null, Set.of(), null, null, null, null, false);
    }

    /** 盖房:BUILD 步——tag=蓝图名(如 small_hut/hut_5x5),材料已由规划期倒推备齐。 */
    public static GoalStep build(String blueprintName) {
        return new GoalStep(Kind.BUILD, null, 1, null, Set.of(), null, null, null, blueprintName, false);
    }

    public GoalStep withCount(int newCount) {
        return new GoalStep(kind, item, newCount, block, ores, input, output, pos, tag, bestEffort);
    }

    /** Marks a provisioning step as optional without changing its task payload. */
    public GoalStep asBestEffort() {
        return bestEffort ? this
                : new GoalStep(kind, item, count, block, ores, input, output, pos, tag, true);
    }

    public boolean maintainsTunnelingTools() {
        return kind == Kind.MINING_SERVICE && tag != null && tag.contains("channel_tools");
    }

    public boolean isMiningHandoffService() {
        return kind == Kind.MINING_SERVICE && tag != null
                && tag.startsWith("after_final_handoff:stone=");
    }

    public int miningHandoffStoneLikeReserve() {
        if (!isMiningHandoffService()) {
            return io.github.zoyluo.aibot.mining.MiningBudget.EMERGENCY_STONE_LIKE;
        }
        for (String component : tag.split(":")) {
            if (component.startsWith("stone=")) {
                int reserve = Integer.parseInt(component.substring("stone=".length()));
                if (reserve < io.github.zoyluo.aibot.mining.MiningBudget.EMERGENCY_STONE_LIKE) {
                    throw new IllegalStateException("invalid_mining_handoff_stone_reserve");
                }
                return reserve;
            }
        }
        throw new IllegalStateException("missing_mining_handoff_stone_reserve");
    }

    public boolean isObsidianService() {
        return kind == Kind.MINING_SERVICE && tag != null
                && tag.startsWith("obsidian_8:");
    }

    public boolean isObsidianPreflight() {
        return kind == Kind.MINING_SERVICE
                && tag != null && tag.startsWith("obsidian_preflight:");
    }

    public boolean isRareOreService() {
        return kind == Kind.MINING_SERVICE && tag != null
                && tag.startsWith("rare_ore_batch:");
    }

    public boolean isRareDescentKitService() {
        return kind == Kind.MINING_SERVICE && tag != null
                && tag.startsWith("rare_descent_kit:");
    }

    public int rareOreMissionTarget() {
        if (!isRareOreService()) {
            return 0;
        }
        return taggedServiceTarget("rare_ore");
    }

    public int rareDescentKitMissionTarget() {
        if (!isRareDescentKitService()) {
            return 0;
        }
        return taggedServiceTarget("rare_descent_kit");
    }

    public int obsidianTransactionTarget() {
        if (!isObsidianService() && !isObsidianPreflight() || tag == null) {
            return 0;
        }
        return taggedServiceTarget("obsidian_transaction");
    }

    private int taggedServiceTarget(String identity) {
        for (String component : tag.split(":")) {
            if (component.startsWith("target=")) {
                int target = Integer.parseInt(component.substring("target=".length()));
                if (target <= 0) {
                    throw new IllegalStateException("invalid_" + identity + "_target");
                }
                return target;
            }
        }
        throw new IllegalStateException("missing_" + identity + "_target");
    }

    public boolean sameTarget(GoalStep other) {
        return other != null
                && kind == other.kind
                && item == other.item
                && block == other.block
                && input == other.input
                && output == other.output
                && ores.equals(other.ores)
                && java.util.Objects.equals(pos, other.pos)
                && java.util.Objects.equals(tag, other.tag)
                && bestEffort == other.bestEffort;
    }

    // 中文动词 + 物品/方块中文名(ItemNames 对照表,服务端就翻译好,面板/日志直接中文)。
    public String describe() {
        return switch (kind) {
            case GATHER -> "采集 " + ItemNames.cn(item) + " ×" + count;
            case MINE -> "挖 " + ItemNames.cn(block) + " ×" + count;
            case MINE_ORE -> "采矿 " + ores.stream()
                    .map(ItemNames::cn)
                    .sorted()
                    .collect(Collectors.joining("/")) + " ×" + count;
            case MINING_SERVICE -> isObsidianPreflight()
                    ? "黑曜石首批补给检查"
                    : isObsidianService()
                    ? "黑曜石补给检查点（累计 ×" + count + "）"
                    : isRareDescentKitService()
                    ? "钻石远征下潜密封补给"
                    : isRareOreService()
                    ? "稀有矿补给检查点（累计 ×" + count + "）"
                    : isMiningHandoffService()
                    ? "采矿容量交接（累计 ×" + count + "）"
                    : "采矿补给检查点（累计 ×" + count + "）";
            case CRAFT -> "合成 " + ItemNames.cn(item) + " ×" + count;
            case SMELT -> "熔炼 " + ItemNames.cn(input) + " → " + ItemNames.cn(output) + " ×" + count;
            case MOVE -> "移动到 " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
            case FARM -> "种植 " + ItemNames.cn(block) + " ×" + count;
            case HUNT -> "打猎取肉 ×" + count;
            case COOK_FOOD -> "烤制食物 ×" + count;
            case MILK_COW -> "挤牛奶 ×" + count;
            case PLACE_STATIONS -> "摆放工作台/熔炉/箱子";
            case STOCKPILE -> "囤入箱子 " + ItemNames.cn(item);
            case DESCEND_TO_Y -> "下挖到 Y=" + pos.getY();
            case ACQUIRE_WATER -> "物理寻找水源并装满水桶";
            case MAKE_OBSIDIAN -> "造黑曜石 ×" + count;
            case BUILD -> "建造 " + tag;
        };
    }
}
