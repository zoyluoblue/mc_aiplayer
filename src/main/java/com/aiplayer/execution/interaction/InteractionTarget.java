package com.aiplayer.execution.interaction;

import net.minecraft.core.BlockPos;

public record InteractionTarget(
    BlockPos targetBlock,
    BlockPos standPos,
    double reachRange,
    String toolType,
    InteractionActionType actionType,
    String reason
) {
    public InteractionTarget {
        if (targetBlock == null) {
            throw new IllegalArgumentException("targetBlock cannot be null");
        }
        if (reachRange <= 0.0D) {
            throw new IllegalArgumentException("reachRange must be positive");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("actionType cannot be null");
        }
        reason = reason == null || reason.isBlank() ? "unknown" : reason;
    }

    public boolean hasStandPos() {
        return standPos != null;
    }

    public String summary() {
        return "target=" + targetBlock.toShortString()
            + ", stand=" + (standPos == null ? "direct" : standPos.toShortString())
            + ", reach=" + String.format("%.1f", reachRange)
            + ", tool=" + (toolType == null || toolType.isBlank() ? "any" : toolType)
            + ", action=" + actionType
            + ", reason=" + reason;
    }
}
