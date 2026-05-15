package com.aiplayer.mining;

import net.minecraft.core.BlockPos;

public final class ProspectMiningLayerPolicy {
    private ProspectMiningLayerPolicy() {
    }

    public static boolean canWorkOreFromCurrentLayer(int currentY, BlockPos orePos) {
        return orePos != null && Math.abs(currentY - orePos.getY()) <= 1;
    }
}
