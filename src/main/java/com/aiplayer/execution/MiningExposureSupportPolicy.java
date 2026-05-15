package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

public final class MiningExposureSupportPolicy {
    private MiningExposureSupportPolicy() {
    }

    public static boolean supportWouldSealOreExposure(BlockPos supportPos, BlockPos orePos, boolean supportCurrentlyAir) {
        if (!supportCurrentlyAir || supportPos == null || orePos == null || supportPos.getY() != orePos.getY()) {
            return false;
        }
        int dx = Math.abs(supportPos.getX() - orePos.getX());
        int dz = Math.abs(supportPos.getZ() - orePos.getZ());
        return dx + dz == 1;
    }
}
