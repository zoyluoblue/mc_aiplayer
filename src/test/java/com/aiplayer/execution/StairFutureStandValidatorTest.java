package com.aiplayer.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StairFutureStandValidatorTest {
    @Test
    void acceptsStepWhenHeadAndFeetCanBeClearedWithSafeSupport() {
        StairFutureStandValidator.Result result = StairFutureStandValidator.validate(new StairFutureStandValidator.Input(
            "minecraft:stone",
            false,
            true,
            "minecraft:dirt",
            false,
            true,
            "minecraft:stone",
            true,
            null,
            null
        ));

        assertTrue(result.valid());
    }

    @Test
    void rejectsFutureStandWithoutSupportBeforeDigging() {
        StairFutureStandValidator.Result result = StairFutureStandValidator.validate(new StairFutureStandValidator.Input(
            "minecraft:air",
            true,
            true,
            "minecraft:stone",
            false,
            true,
            "minecraft:air",
            false,
            null,
            null
        ));

        assertFalse(result.valid());
        assertTrue(result.reason().contains("support=minecraft:air"));
    }

    @Test
    void rejectsUnbreakableHeadOrFootBlocks() {
        StairFutureStandValidator.Result headBlocked = StairFutureStandValidator.validate(new StairFutureStandValidator.Input(
            "minecraft:bedrock",
            false,
            false,
            "minecraft:air",
            true,
            true,
            "minecraft:stone",
            true,
            null,
            null
        ));
        StairFutureStandValidator.Result feetBlocked = StairFutureStandValidator.validate(new StairFutureStandValidator.Input(
            "minecraft:air",
            true,
            true,
            "minecraft:bedrock",
            false,
            false,
            "minecraft:stone",
            true,
            null,
            null
        ));

        assertFalse(headBlocked.valid());
        assertTrue(headBlocked.reason().contains("head_blocked"));
        assertFalse(feetBlocked.valid());
        assertTrue(feetBlocked.reason().contains("feet_blocked"));
    }

    @Test
    void rejectsDangerousBlocksBeforeSelectingDirection() {
        StairFutureStandValidator.Result result = StairFutureStandValidator.validate(new StairFutureStandValidator.Input(
            "minecraft:stone",
            false,
            true,
            "minecraft:stone",
            false,
            true,
            "minecraft:stone",
            true,
            null,
            "lava"
        ));

        assertFalse(result.valid());
        assertTrue(result.reason().contains("vertical_danger=lava"));
    }
}
