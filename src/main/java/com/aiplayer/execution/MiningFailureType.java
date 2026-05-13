package com.aiplayer.execution;

public enum MiningFailureType {
    STAND_INVALID("stand_invalid", MiningRecoveryAction.SWITCH_STAIR_DIRECTION, 3),
    NO_TARGET("no_target", MiningRecoveryAction.RESET_DESCENT_START, 3),
    MOVE_STUCK("move_stuck", MiningRecoveryAction.SWITCH_STAIR_DIRECTION, 3),
    DESCENT_LIMIT("descent_limit", MiningRecoveryAction.RESET_DESCENT_START, 2),
    TARGET_ABOVE_LAYER("target_above_layer", MiningRecoveryAction.RESCAN_TARGET, 3),
    UNSAFE_TARGET("unsafe_target", MiningRecoveryAction.SWITCH_STAIR_DIRECTION, 3),
    DANGER("danger", MiningRecoveryAction.ASK_PLAYER, 0),
    TOOL_MISSING("tool_missing", MiningRecoveryAction.PREPARE_TOOL, 2);

    private final String key;
    private final MiningRecoveryAction defaultAction;
    private final int recoveryLimit;

    MiningFailureType(String key, MiningRecoveryAction defaultAction, int recoveryLimit) {
        this.key = key;
        this.defaultAction = defaultAction;
        this.recoveryLimit = recoveryLimit;
    }

    public String key() {
        return key;
    }

    public MiningRecoveryAction defaultAction() {
        return defaultAction;
    }

    public int recoveryLimit() {
        return recoveryLimit;
    }
}
