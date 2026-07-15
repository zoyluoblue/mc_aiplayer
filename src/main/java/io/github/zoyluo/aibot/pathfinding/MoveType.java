package io.github.zoyluo.aibot.pathfinding;

public enum MoveType {
    WALK,
    DIAGONAL,
    JUMP_UP,
    DROP_DOWN,
    DIG_THROUGH,
    PILLAR_UP,
    SCAFFOLD,
    PARKOUR // 助跑平跳越沟(1~2 格真沟),nav.parkour 可关
}
