package com.aiplayer.execution;

public enum MiningRecoveryAction {
    SWITCH_STAIR_DIRECTION("switch_stair_direction"),
    RESET_DESCENT_START("reset_descent_start"),
    RESCAN_TARGET("rescan_target"),
    PREPARE_TOOL("prepare_tool"),
    ASK_PLAYER("ask_player");

    private final String key;

    MiningRecoveryAction(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
