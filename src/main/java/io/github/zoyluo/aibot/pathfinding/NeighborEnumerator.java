package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

public final class NeighborEnumerator {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH,
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST
    };

    private final boolean canPillar;

    public NeighborEnumerator() {
        this(false);
    }

    // NAV-9:canPillar=true 时允许"垫方块上升"邻接(仅当 bot 背包有可放置方块时由 A* 传入)。
    public NeighborEnumerator(boolean canPillar) {
        this.canPillar = canPillar;
    }

    public List<NeighborCandidate> getNeighbors(BlockPos current, ServerWorld world) {
        List<NeighborCandidate> result = new ArrayList<>(HORIZONTAL.length);
        for (Direction direction : HORIZONTAL) {
            BlockPos target = current.offset(direction);
            if (Standability.isStandable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.WALK, 0));
                continue;
            }

            BlockPos jumpTarget = target.up();
            if (canJumpOnto(world, current, target) && Standability.isStandable(world, jumpTarget)) {
                result.add(new NeighborCandidate(jumpTarget, MoveType.JUMP_UP, 0));
                continue;
            }

            NeighborCandidate drop = findDrop(world, target);
            if (drop != null) {
                result.add(drop);
                continue;
            }

            if (isMineable(world, target) && hasHeadroom(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.DIG_THROUGH, 0));
            }
        }
        addDiagonals(current, world, result);
        addPillar(current, world, result);
        return result;
    }

    // NAV-3:同高对角移动。仅当目标格可站、且两个正交相邻格都"可穿过"(不切墙角)时才允许。
    private static void addDiagonals(BlockPos current, ServerWorld world, List<NeighborCandidate> result) {
        Direction[][] pairs = {
                {Direction.NORTH, Direction.EAST},
                {Direction.NORTH, Direction.WEST},
                {Direction.SOUTH, Direction.EAST},
                {Direction.SOUTH, Direction.WEST}
        };
        for (Direction[] pair : pairs) {
            BlockPos diag = current.offset(pair[0]).offset(pair[1]);
            if (!Standability.isStandable(world, diag)) {
                continue;
            }
            if (!passableColumn(world, diag)) {
                continue;
            }
            if (!passableColumn(world, current.offset(pair[0])) || !passableColumn(world, current.offset(pair[1]))) {
                continue;
            }
            result.add(new NeighborCandidate(diag, MoveType.DIAGONAL, 0));
        }
    }

    // NAV-9:垫方块上升一格(原地)。bot 会在脚下放方块并跳上去。需要头顶两格净空。
    private void addPillar(BlockPos current, ServerWorld world, List<NeighborCandidate> result) {
        if (!canPillar) {
            return;
        }
        BlockPos up1 = current.up();
        BlockPos up2 = current.up(2);
        // up1 = 新脚位(当前头位,应为空);up2 = 新头位,需净空
        if (collisionEmpty(world, up1) && collisionEmpty(world, up2) && !Standability.isDangerous(world.getBlockState(up1))) {
            result.add(new NeighborCandidate(up1, MoveType.PILLAR_UP, 0));
        }
    }

    private static boolean collisionEmpty(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean passableColumn(ServerWorld world, BlockPos feet) {
        return collisionEmpty(world, feet) && collisionEmpty(world, feet.up());
    }

    private static boolean canJumpFrom(ServerWorld world, BlockPos current) {
        return collisionEmpty(world, current.up()) && collisionEmpty(world, current.up(2));
    }

    private static boolean canJumpOnto(ServerWorld world, BlockPos current, BlockPos front) {
        if (!canJumpFrom(world, current)) {
            return false;
        }
        BlockState frontState = world.getBlockState(front);
        if (frontState.getCollisionShape(world, front).isEmpty()) {
            return false;
        }
        if (frontState.getCollisionShape(world, front).getMax(Direction.Axis.Y) > 1.0D) {
            return false;
        }
        return collisionEmpty(world, front.up()) && collisionEmpty(world, front.up(2));
    }

    private static NeighborCandidate findDrop(ServerWorld world, BlockPos target) {
        if (!collisionEmpty(world, target)) {
            return null;
        }
        if (!collisionEmpty(world, target.up())) {
            return null;
        }
        for (int fall = 1; fall <= 3; fall++) {
            BlockPos landing = target.down(fall);
            if (Standability.isStandable(world, landing)) {
                return new NeighborCandidate(landing, MoveType.DROP_DOWN, fall);
            }
            if (!collisionEmpty(world, landing)) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasHeadroom(ServerWorld world, BlockPos target) {
        return collisionEmpty(world, target.up()) && collisionEmpty(world, target.up(2));
    }

    private static boolean isMineable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getHardness(world, pos) < 0.0F || world.getBlockEntity(pos) != null) {
            return false;
        }
        if (!state.getFluidState().isEmpty() || Standability.isDangerous(state)) {
            return false;
        }
        return state.isIn(BlockTags.STONE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DIRT)
                || state.isOf(Blocks.STONE)
                || state.isOf(Blocks.COBBLESTONE)
                || state.isOf(Blocks.GRANITE)
                || state.isOf(Blocks.DIORITE)
                || state.isOf(Blocks.ANDESITE)
                || state.isOf(Blocks.SAND)
                || state.isOf(Blocks.RED_SAND)
                || state.isOf(Blocks.GRAVEL);
    }
}
