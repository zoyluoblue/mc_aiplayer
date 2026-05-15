package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

public final class MiningAnimationState {
    private String mode;
    private BlockPos target;
    private String blockId;
    private int tick;

    public void record(String mode, BlockPos target, String blockId, int tick) {
        this.mode = mode == null || mode.isBlank() ? "unknown" : mode;
        this.target = target == null ? null : target.immutable();
        this.blockId = blockId == null || blockId.isBlank() ? "unknown" : blockId;
        this.tick = Math.max(0, tick);
    }

    public void clear() {
        mode = null;
        target = null;
        blockId = null;
        tick = 0;
    }

    public boolean hasTarget() {
        return target != null;
    }

    public String statusText() {
        if (target == null) {
            return "无";
        }
        return "模式=" + mode
            + "，目标=" + target.toShortString()
            + "，方块=" + blockId
            + "，tick=" + tick;
    }
}
