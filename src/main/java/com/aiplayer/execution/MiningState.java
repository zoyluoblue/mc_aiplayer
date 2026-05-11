package com.aiplayer.execution;

public enum MiningState {
    PREPARE_TOOLS("准备工具"),
    TRAVEL_TO_ORE("前往矿区"),
    DESCEND("下探"),
    CAVE_SCAN("洞穴扫描"),
    BRANCH_TUNNEL("分支矿道"),
    MINING("采矿"),
    SUPPLY("补给"),
    RETURNING("返回"),
    WAITING_FOR_PLAYER("等待玩家"),
    COMPLETED("完成");

    private final String label;

    MiningState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
