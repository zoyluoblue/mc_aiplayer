package com.aiplayer.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

public class BlockPlacement {
    public final BlockPos pos;
    public final Block block;

    public BlockPlacement(BlockPos pos, Block block) {
        this.pos = pos;
        this.block = block;
    }
}
