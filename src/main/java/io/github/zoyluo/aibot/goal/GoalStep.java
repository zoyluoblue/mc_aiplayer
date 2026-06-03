package io.github.zoyluo.aibot.goal;

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
                       BlockPos pos) {
    public enum Kind {
        GATHER,
        MINE,
        MINE_ORE,
        CRAFT,
        SMELT,
        MOVE,
        FARM,
        HUNT,
        PLACE_STATIONS,
        STOCKPILE,
        DESCEND_TO_Y
    }

    public GoalStep {
        count = Math.max(1, count);
        ores = ores == null ? Set.of() : Set.copyOf(ores);
        pos = pos == null ? null : pos.toImmutable();
    }

    public static GoalStep gather(Item item, int count) {
        return new GoalStep(Kind.GATHER, item, count, null, Set.of(), null, null, null);
    }

    public static GoalStep mine(Block block, int count) {
        return new GoalStep(Kind.MINE, null, count, block, Set.of(), null, null, null);
    }

    public static GoalStep mineOre(Set<Block> ores, int count) {
        return new GoalStep(Kind.MINE_ORE, null, count, null, ores, null, null, null);
    }

    public static GoalStep craft(Item item, int count) {
        return new GoalStep(Kind.CRAFT, item, count, null, Set.of(), null, null, null);
    }

    public static GoalStep smelt(Item input, Item output, int count) {
        return new GoalStep(Kind.SMELT, null, count, null, Set.of(), input, output, null);
    }

    public static GoalStep move(BlockPos pos) {
        return new GoalStep(Kind.MOVE, null, 1, null, Set.of(), null, null, pos);
    }

    /** P3:FARM 步——block=作物方块,input=种子,item=产出物,count=要收的数量。 */
    public static GoalStep farm(Block crop, Item seed, Item produce, int count) {
        return new GoalStep(Kind.FARM, produce, count, crop, Set.of(), seed, null, null);
    }

    /** 第4层:HUNT 步——猎杀动物获取 count 个生肉(best-effort:周围没动物时跳过,不阻断挖矿目标)。 */
    public static GoalStep hunt(int count) {
        return new GoalStep(Kind.HUNT, null, count, null, Set.of(), null, null, null);
    }

    /** Phase2:放置工作台/熔炉/箱子三件套(方块固定,无参数)。 */
    public static GoalStep placeStations() {
        return new GoalStep(Kind.PLACE_STATIONS, null, 1, null, Set.of(), null, null, null);
    }

    /** Phase3:把背包资源存进附近箱子(best-effort;item 仅作语义标记)。 */
    public static GoalStep stockpile(Item item) {
        return new GoalStep(Kind.STOCKPILE, item, 1, null, Set.of(), null, null, null);
    }

    /** 挖深层矿:DESCEND_TO_Y 步——下挖到指定 Y(用 pos.y 携带,允许负数;x/z 忽略)。 */
    public static GoalStep descendToY(int y) {
        return new GoalStep(Kind.DESCEND_TO_Y, null, 1, null, Set.of(), null, null, new BlockPos(0, y, 0));
    }

    public GoalStep withCount(int newCount) {
        return new GoalStep(kind, item, newCount, block, ores, input, output, pos);
    }

    public boolean sameTarget(GoalStep other) {
        return other != null
                && kind == other.kind
                && item == other.item
                && block == other.block
                && input == other.input
                && output == other.output
                && ores.equals(other.ores)
                && java.util.Objects.equals(pos, other.pos);
    }

    public String describe() {
        return switch (kind) {
            case GATHER -> "GATHER " + Registries.ITEM.getId(item) + " x" + count;
            case MINE -> "MINE " + Registries.BLOCK.getId(block) + " x" + count;
            case MINE_ORE -> "MINE_ORE " + ores.stream()
                    .map(block -> Registries.BLOCK.getId(block).toString())
                    .sorted()
                    .collect(Collectors.joining(",")) + " x" + count;
            case CRAFT -> "CRAFT " + Registries.ITEM.getId(item) + " x" + count;
            case SMELT -> "SMELT " + Registries.ITEM.getId(input) + " -> " + Registries.ITEM.getId(output) + " x" + count;
            case MOVE -> "MOVE " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
            case FARM -> "FARM " + Registries.BLOCK.getId(block) + " x" + count;
            case HUNT -> "HUNT meat x" + count;
            case PLACE_STATIONS -> "PLACE_STATIONS table+furnace+chest";
            case STOCKPILE -> "STOCKPILE " + Registries.ITEM.getId(item);
            case DESCEND_TO_Y -> "DESCEND_TO_Y " + pos.getY();
        };
    }
}
