package com.aiplayer.execution.interaction;

import net.minecraft.core.BlockPos;

public record InteractionFailureKey(
    BlockPos targetBlock,
    BlockPos standPos,
    InteractionActionType actionType,
    String reason
) {
    public InteractionFailureKey {
        if (targetBlock == null) {
            throw new IllegalArgumentException("targetBlock cannot be null");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("actionType cannot be null");
        }
        reason = reason == null || reason.isBlank() ? "unknown" : reason;
    }

    public static InteractionFailureKey from(InteractionTarget target, String reason) {
        return new InteractionFailureKey(target.targetBlock(), target.standPos(), target.actionType(), reason);
    }

    public boolean matches(InteractionTarget target) {
        if (target == null || !targetBlock.equals(target.targetBlock()) || actionType != target.actionType()) {
            return false;
        }
        if (standPos == null) {
            return target.standPos() == null;
        }
        return standPos.equals(target.standPos());
    }

    public String summary() {
        return "target=" + targetBlock.toShortString()
            + ", stand=" + (standPos == null ? "direct" : standPos.toShortString())
            + ", action=" + actionType
            + ", reason=" + reason;
    }
}
