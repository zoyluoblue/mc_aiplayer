package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.craft.ItemNames;
import net.minecraft.block.Block;
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
                       String tag) {
    public enum Kind {
        GATHER,
        MINE,
        MINE_ORE,
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
        MAKE_OBSIDIAN,
        BUILD
    }

    public GoalStep {
        count = Math.max(1, count);
        ores = ores == null ? Set.of() : Set.copyOf(ores);
        pos = pos == null ? null : pos.toImmutable();
    }

    public static GoalStep gather(Item item, int count) {
        return new GoalStep(Kind.GATHER, item, count, null, Set.of(), null, null, null, null);
    }

    public static GoalStep mine(Block block, int count) {
        return new GoalStep(Kind.MINE, null, count, block, Set.of(), null, null, null, null);
    }

    public static GoalStep mineOre(Set<Block> ores, int count) {
        return new GoalStep(Kind.MINE_ORE, null, count, null, ores, null, null, null, null);
    }

    public static GoalStep craft(Item item, int count) {
        return new GoalStep(Kind.CRAFT, item, count, null, Set.of(), null, null, null, null);
    }

    public static GoalStep smelt(Item input, Item output, int count) {
        return new GoalStep(Kind.SMELT, null, count, null, Set.of(), input, output, null, null);
    }

    public static GoalStep move(BlockPos pos) {
        return new GoalStep(Kind.MOVE, null, 1, null, Set.of(), null, null, pos, null);
    }

    /** P3:FARM 步——block=作物方块,input=种子,item=产出物,count=要收的数量。 */
    public static GoalStep farm(Block crop, Item seed, Item produce, int count) {
        return new GoalStep(Kind.FARM, produce, count, crop, Set.of(), seed, null, null, null);
    }

    /** 第4层:HUNT 步——猎杀动物获取 count 个生肉(best-effort:周围没动物时跳过,不阻断挖矿目标)。 */
    public static GoalStep hunt(int count) {
        return new GoalStep(Kind.HUNT, null, count, null, Set.of(), null, null, null, null);
    }

    /** P0 食物闭环:COOK_FOOD 步——把背包里所有生食烤成 count 个熟食(best-effort,无熔炉/燃料则跳过)。 */
    public static GoalStep cookFood(int count) {
        return new GoalStep(Kind.COOK_FOOD, null, count, null, Set.of(), null, null, null, null);
    }

    /** 蛋糕链:MILK_COW 步——用空桶挤 count 桶牛奶(需背包有空桶 + 周围有牛;缺则 best-effort 失败)。 */
    public static GoalStep milkCow(int count) {
        return new GoalStep(Kind.MILK_COW, null, count, null, Set.of(), null, null, null, null);
    }

    /** Phase2:放置工作台/熔炉/箱子三件套(方块固定,无参数)。 */
    public static GoalStep placeStations() {
        return new GoalStep(Kind.PLACE_STATIONS, null, 1, null, Set.of(), null, null, null, null);
    }

    /** Phase3:把背包资源存进附近箱子(best-effort;item 仅作语义标记)。 */
    public static GoalStep stockpile(Item item) {
        return new GoalStep(Kind.STOCKPILE, item, 1, null, Set.of(), null, null, null, null);
    }

    /** 挖深层矿:DESCEND_TO_Y 步——下挖到指定 Y(用 pos.y 携带,允许负数;x/z 忽略)。 */
    public static GoalStep descendToY(int y) {
        return new GoalStep(Kind.DESCEND_TO_Y, null, 1, null, Set.of(), null, null, new BlockPos(0, y, 0), null);
    }

    /** 造黑曜石:MAKE_OBSIDIAN 步——水浇岩浆现造 count 块(需背包桶+钻石镐)。 */
    public static GoalStep makeObsidian(int count) {
        return new GoalStep(Kind.MAKE_OBSIDIAN, null, count, null, Set.of(), null, null, null, null);
    }

    /** 盖房:BUILD 步——tag=蓝图名(如 small_hut/hut_5x5),材料已由规划期倒推备齐。 */
    public static GoalStep build(String blueprintName) {
        return new GoalStep(Kind.BUILD, null, 1, null, Set.of(), null, null, null, blueprintName);
    }

    public GoalStep withCount(int newCount) {
        return new GoalStep(kind, item, newCount, block, ores, input, output, pos, tag);
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
                && java.util.Objects.equals(tag, other.tag);
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
            case MAKE_OBSIDIAN -> "造黑曜石 ×" + count;
            case BUILD -> "建造 " + tag;
        };
    }
}
