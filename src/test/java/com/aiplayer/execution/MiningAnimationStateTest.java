package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningAnimationStateTest {
    @Test
    void recordsReadableMiningActionTarget() {
        MiningAnimationState state = new MiningAnimationState();

        state.record("route_clearance:head", new BlockPos(3, 12, -4), "minecraft:deepslate", 42);

        assertTrue(state.hasTarget());
        assertTrue(state.statusText().contains("模式=route_clearance:head"));
        assertTrue(state.statusText().contains("目标=3, 12, -4"));
        assertTrue(state.statusText().contains("方块=minecraft:deepslate"));
        assertTrue(state.statusText().contains("tick=42"));
    }

    @Test
    void clearRemovesMiningActionTarget() {
        MiningAnimationState state = new MiningAnimationState();
        state.record("branch_tunnel", BlockPos.ZERO, "minecraft:stone", 1);

        state.clear();

        assertFalse(state.hasTarget());
        assertTrue(state.statusText().contains("无"));
    }
}
