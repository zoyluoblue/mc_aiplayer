package io.github.zoyluo.aibot.pathfinding;

import io.github.zoyluo.aibot.AIBotConfig;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class CostModel {
    private CostModel() {
    }

    public static double stepCost(MoveType type, int fallHeight) {
        return switch (type) {
            case WALK -> 1.0D;
            // 对角 ≈ √2:比走两步直角(2.0)便宜,让 A* 优先抄近路,减少蛇形、更快到达。
            case DIAGONAL -> 1.41D;
            case JUMP_UP -> 1.5D;
            case DROP_DOWN -> {
                if (fallHeight > AIBotConfig.get().nav().maxSafeFall()) {
                    yield 1000.0D;
                }
                yield 0.5D + 0.3D * fallHeight;
            }
            case DIG_THROUGH -> 8.0D;
            // 垫方块上升:代价高(消耗方块 + 慢),仅在地形无法翻越时 A* 才会选它。
            case PILLAR_UP -> 6.0D;
            // 缺口铺路:消耗方块,但比绕很远或挖隧道便宜。
            case SCAFFOLD -> 4.0D;
            // 平跳越沟:比绕路(每格 1.0)贵、比挖穿便宜——2 格沟直跳 2.5 vs 绕 5+ 格,A* 自然选跳。
            case PARKOUR -> 2.5D;
        };
    }

    public static double stepCost(Node current, NeighborCandidate neighbor, ServerWorld world) {
        double cost = stepCost(neighbor.moveType(), neighbor.fallHeight());
        cost += turnPenalty(current, neighbor.pos());
        // WATER-1 配套:深水节点已被 Standability 整个拒掉,能进图的只剩浅水(可涉)。
        // 涉水减速且有被推离路线的风险,×4 让 A* 只在没有干路时才蹚水,而不是抄水路近道。
        if (world.getFluidState(neighbor.pos()).isIn(FluidTags.WATER)) {
            cost *= 4.0D;
        }
        return cost;
    }

    public static double heuristic(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.getX() - to.getX());
        int dy = Math.abs(from.getY() - to.getY());
        int dz = Math.abs(from.getZ() - to.getZ());
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        return straight + 1.41D * diagonal + 1.5D * dy;
    }

    private static double turnPenalty(Node current, BlockPos next) {
        Node parent = current.parent();
        if (parent == null) {
            return 0.0D;
        }
        Direction previous = horizontalDirection(parent.pos(), current.pos());
        Direction incoming = horizontalDirection(current.pos(), next);
        if (previous == null || incoming == null || previous == incoming) {
            return 0.0D;
        }
        return previous.getOpposite() == incoming ? 0.4D : 0.15D;
    }

    private static Direction horizontalDirection(BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX() - from.getX(), 0);
        int dz = Integer.compare(to.getZ() - from.getZ(), 0);
        if (dx != 0 && dz == 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0 && dx == 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }
}
