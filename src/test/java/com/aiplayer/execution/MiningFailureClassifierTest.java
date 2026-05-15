package com.aiplayer.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningFailureClassifierTest {
    @Test
    void classifiesToolFailures() {
        MiningFailureClassifier.Classification classification =
            MiningFailureClassifier.classify("wrong_tool_tier:minecraft:iron_pickaxe");

        assertEquals("tool_requirement", classification.code());
        assertEquals("prepare_tool", classification.recoveryAction());
    }

    @Test
    void classifiesTargetMissingFailures() {
        MiningFailureClassifier.Classification classification =
            MiningFailureClassifier.classify("target_removed_before_mine");

        assertEquals("target_missing", classification.code());
    }

    @Test
    void classifiesPathAndMovementFailuresSeparately() {
        assertEquals(
            "path_blocked",
            MiningFailureClassifier.classify("guided_tunnel_blocked:no_clearance_or_workable_ore").code()
        );
        assertEquals(
            "unreachable",
            MiningFailureClassifier.classify("adjacent_mining_step_stuck:prospect_guided_step").code()
        );
        assertEquals(
            "switch_stair_direction",
            MiningFailureClassifier.classify("blocked_line_of_sight").recoveryAction()
        );
    }

    @Test
    void classifiesInventoryCapacityFailures() {
        MiningFailureClassifier.Classification classification =
            MiningFailureClassifier.classify("inventory_full_for_drop:route_clearance:prospect_guided_step");

        assertEquals("inventory_full", classification.code());
    }

    @Test
    void classifiesActualNoProgressAndNavigationReasons() {
        assertEquals(
            "no_progress",
            MiningFailureClassifier.classify("no_item_progress").code()
        );
        assertEquals(
            "unreachable",
            MiningFailureClassifier.classify("navigation_stuck").code()
        );
    }

    @Test
    void classifiesNoTargetInteractionAndBreakFailures() {
        assertEquals(
            "no_target",
            MiningFailureClassifier.classify("prospect_not_found").code()
        );
        assertEquals(
            "interaction_blocked",
            MiningFailureClassifier.classify("blocked_line_of_sight").code()
        );
        assertEquals(
            "break_failed",
            MiningFailureClassifier.classify("break_failed:destroy_failed").code()
        );
    }

    @Test
    void classifiesEnvironmentRiskFailures() {
        MiningFailureClassifier.Classification classification =
            MiningFailureClassifier.classify("unsafe_route_clearance:lava");

        assertEquals("environment_blocked", classification.code());
        assertEquals("switch_stair_direction", classification.recoveryAction());
    }
}
