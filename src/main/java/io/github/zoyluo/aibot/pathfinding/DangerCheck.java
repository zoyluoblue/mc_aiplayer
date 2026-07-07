package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class DangerCheck {
    private DangerCheck() {
    }

    public static String scan(ServerWorld world, BlockPos node) {
        if (node.getY() < world.getBottomY() + 1) {
            return "void";
        }
        BlockState at = world.getBlockState(node);
        if (at.getFluidState().isIn(FluidTags.LAVA)) {
            return "lava_at";
        }
        BlockState below = world.getBlockState(node.down());
        if (below.getFluidState().isIn(FluidTags.LAVA)) {
            return "lava_below";
        }
        // BUGFIX: лава может быть на 2 блока ниже (под тонким слоем камня/гравия)
        BlockState below2 = world.getBlockState(node.down(2));
        if (below2.getFluidState().isIn(FluidTags.LAVA)) {
            return "lava_below_2";
        }
        if (below.isOf(Blocks.FIRE) || below.isOf(Blocks.SOUL_FIRE)) {
            return "fire_below";
        }
        if (below.isOf(Blocks.MAGMA_BLOCK)) {
            return "magma_below";
        }
        if (below.isOf(Blocks.CACTUS)) {
            return "cactus_below";
        }
        if (Standability.isDangerous(at)) {
            return "danger_at";
        }
        if (Standability.isDangerous(below)) {
            return "danger_below";
        }
        return null;
    }
}
