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
        MOVE
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
        };
    }
}
