package com.aiplayer.mining;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreProspectSelectionPolicyTest {
    @Test
    void rejectsEmbeddedIronAboveCurrentLayer() {
        OreProspectTarget target = ironTarget();

        OreProspectSelectionPolicy.Rejection rejection = OreProspectSelectionPolicy.rejectionFor(
            target,
            new BlockPos(0, 40, 0),
            new BlockPos(3, 48, 0),
            OreProspectClassification.EMBEDDED_HINT
        );

        assertTrue(rejection.rejected());
        assertTrue(rejection.reason().contains("above_current_layer"));
    }

    @Test
    void keepsExecutableIronAndLowerEmbeddedHints() {
        OreProspectTarget target = ironTarget();

        OreProspectSelectionPolicy.Rejection exposed = OreProspectSelectionPolicy.rejectionFor(
            target,
            new BlockPos(0, 40, 0),
            new BlockPos(3, 48, 0),
            OreProspectClassification.APPROACHABLE_EXPOSED
        );
        OreProspectSelectionPolicy.Rejection lowerHint = OreProspectSelectionPolicy.rejectionFor(
            target,
            new BlockPos(0, 40, 0),
            new BlockPos(3, 32, 0),
            OreProspectClassification.EMBEDDED_HINT
        );

        assertFalse(exposed.rejected());
        assertFalse(lowerHint.rejected());
    }

    @Test
    void rejectsEmbeddedGoldAboveCurrentLayerInGoldPhase() {
        OreProspectTarget target = OreProspectTarget.forBlocks(
            "raw_gold",
            "minecraft:raw_gold",
            "金矿",
            "minecraft:iron_pickaxe",
            "minecraft:overworld",
            List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore"),
            -64,
            256
        );

        OreProspectSelectionPolicy.Rejection rejection = OreProspectSelectionPolicy.rejectionFor(
            target,
            new BlockPos(0, 40, 0),
            new BlockPos(3, 48, 0),
            OreProspectClassification.EMBEDDED_HINT
        );

        assertTrue(rejection.rejected());
        assertTrue(rejection.reason().contains("above_current_layer"));
    }

    @Test
    void goldProfileContainsNormalAndDeepslateOreTargets() {
        OreProspectTarget target = OreProspectTarget.forBlocks(
            "raw_gold",
            "minecraft:raw_gold",
            "金矿",
            "minecraft:iron_pickaxe",
            "minecraft:overworld",
            List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore"),
            -64,
            256
        );

        assertTrue(target.blockIds().contains("minecraft:gold_ore"));
        assertTrue(target.blockIds().contains("minecraft:deepslate_gold_ore"));
    }

    @Test
    void prefersNearEmbeddedBasicOreOverFarExposedRoute() {
        OreProspectTarget target = ironTarget();
        BlockPos center = new BlockPos(16, 93, -9);

        OreTargetScore farExposed = OreTargetScore.calculate(
            target,
            center,
            new BlockPos(-60, 50, 7),
            OreProspectClassification.APPROACHABLE_EXPOSED
        );
        OreTargetScore nearEmbedded = OreTargetScore.calculate(
            target,
            center,
            new BlockPos(13, 93, -14),
            OreProspectClassification.EMBEDDED_HINT
        );

        assertTrue(nearEmbedded.betterThan(farExposed));
    }

    private static OreProspectTarget ironTarget() {
        return OreProspectTarget.forBlocks(
            "raw_iron",
            "minecraft:raw_iron",
            "铁矿",
            "minecraft:stone_pickaxe",
            "minecraft:overworld",
            List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
            -64,
            256
        );
    }
}
