package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.mining.MiningBudget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningServicePolicyTest {
    @Test
    void defaultOrePoliciesPreserveTheLegacyChannelSwitch() {
        MiningServiceTask.ServicePolicy ordinary =
                MiningServiceTask.ServicePolicy.defaultOre(false);
        MiningServiceTask.ServicePolicy branch =
                MiningServiceTask.ServicePolicy.defaultOre(true);

        assertEquals(MiningServiceTask.ServiceProfile.ORE_BATCH, ordinary.profile());
        assertEquals(1, ordinary.targetToolUsableDurability());
        assertEquals(0, ordinary.channelToolUsableDurability());
        assertFalse(ordinary.maintainsTunnelingTools());
        assertEquals(520, branch.channelToolUsableDurability());
        assertTrue(branch.maintainsTunnelingTools());
    }

    @Test
    void capacityHandoffPreservesTheParentsPhysicalStoneHorizon() {
        MiningServiceTask.ServicePolicy handoff =
                MiningServiceTask.ServicePolicy.capacityHandoff(61);

        assertEquals(MiningServiceTask.ServiceProfile.ORE_BATCH, handoff.profile());
        assertFalse(handoff.maintainsTunnelingTools());
        assertEquals(61, handoff.emergencyBlocksReserved());
        assertEquals(4, handoff.freeSlotsMin());
        assertEquals(MiningServiceTask.ServicePolicy.defaultOre(false).foodMinUnits(),
                handoff.foodMinUnits());
    }

    @Test
    void obsidianThirtyTwoPoliciesPinEveryRemainingServiceHorizon() {
        MiningServiceTask.ServicePolicy preflight =
                MiningServiceTask.ServicePolicy.obsidianPreflight(32);
        assertEquals(MiningServiceTask.ServiceProfile.OBSIDIAN_PREFLIGHT,
                preflight.profile());
        assertEquals(32, preflight.targetToolUsableDurability());
        assertEquals(520, preflight.channelToolUsableDurability());
        assertEquals(24, preflight.futureStickReserve());
        assertEquals(52, preflight.emergencyBlocksReserved());
        assertTrue(preflight.craftingTableRequired());

        MiningServiceTask.ServicePolicy boundary8 =
                MiningServiceTask.ServicePolicy.obsidian8(32, 8);
        MiningServiceTask.ServicePolicy boundary16 =
                MiningServiceTask.ServicePolicy.obsidian8(32, 16);
        MiningServiceTask.ServicePolicy boundary24 =
                MiningServiceTask.ServicePolicy.obsidian8(32, 24);
        assertEquals(24, boundary8.targetToolUsableDurability());
        assertEquals(16, boundary8.futureStickReserve());
        assertEquals(40, boundary8.emergencyBlocksReserved());
        assertEquals(16, boundary16.targetToolUsableDurability());
        assertEquals(8, boundary16.futureStickReserve());
        assertEquals(28, boundary16.emergencyBlocksReserved());
        assertEquals(8, boundary24.targetToolUsableDurability());
        assertEquals(0, boundary24.futureStickReserve());
        assertEquals(16, boundary24.emergencyBlocksReserved());
        assertEquals(32, MiningServiceTask.ServicePolicy.bootstrapStickTarget(32));
        assertEquals(64, MiningServiceTask.ServicePolicy.bootstrapStoneLikeTarget(32));
    }

    @Test
    void namedProfilesCannotBeConstructedWithDowngradedThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new MiningServiceTask.ServicePolicy(
                MiningServiceTask.ServiceProfile.OBSIDIAN_8,
                24, 520, 2, 0, 4, 28, 8, true));
        assertThrows(IllegalArgumentException.class, () -> new MiningServiceTask.ServicePolicy(
                MiningServiceTask.ServiceProfile.OBSIDIAN_PREFLIGHT,
                32, 519, 2, 0, 4, 52, 24, true));
        assertThrows(IllegalArgumentException.class, () -> new MiningServiceTask.ServicePolicy(
                MiningServiceTask.ServiceProfile.ORE_BATCH,
                1, 519, 2, 0, 4, 16, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new MiningServiceTask.ServicePolicy(
                MiningServiceTask.ServiceProfile.OBSIDIAN_8,
                8, 520, 2, 0, 4, 16, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new MiningServiceTask.ServicePolicy(
                MiningServiceTask.ServiceProfile.RARE_ORE_BATCH,
                1, 520, 14, 83, 4, 16, 48, true));
    }

    @Test
    void diamond64RareOrePoliciesPinEveryRemainingBatchHorizon() {
        int[] boundaries = {8, 16, 24, 32, 40, 48, 56};
        int[] torches = {560, 480, 400, 320, 240, 160, 80};
        int[] futureSticks = {182, 154, 126, 98, 70, 42, 14};
        for (int index = 0; index < boundaries.length; index++) {
            MiningServiceTask.ServicePolicy policy =
                    MiningServiceTask.ServicePolicy.rareOreBatch(64, boundaries[index]);
            assertEquals(MiningServiceTask.ServiceProfile.RARE_ORE_BATCH, policy.profile());
            assertEquals(8, policy.targetToolUsableDurability());
            assertEquals(910, policy.channelToolUsableDurability());
            assertEquals(torches[index], policy.torchMinCount());
            assertEquals(12, policy.foodMinUnits());
            assertEquals(37, policy.emergencyBlocksReserved());
            assertEquals(futureSticks[index], policy.futureStickReserve());
            assertTrue(policy.craftingTableRequired());
        }

        MiningServiceTask.ServicePolicy boundary0 =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 0);
        MiningServiceTask.ServicePolicy boundary55 =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 55);
        MiningServiceTask.ServicePolicy boundary63 =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 63);
        assertEquals(8, boundary0.targetToolUsableDurability());
        assertEquals(640, boundary0.torchMinCount());
        assertEquals(210, boundary0.futureStickReserve());
        assertEquals(8, boundary55.targetToolUsableDurability());
        assertEquals(160, boundary55.torchMinCount());
        assertEquals(12, boundary55.foodMinUnits());
        assertEquals(42, boundary55.futureStickReserve());
        assertEquals(1, boundary63.targetToolUsableDurability());
        assertEquals(80, boundary63.torchMinCount());
        assertEquals(12, boundary63.foodMinUnits());
        assertEquals(14, boundary63.futureStickReserve());
    }

    @Test
    void rareRetryReleasesOnlyItsSealedChannelStonePool() {
        MiningServiceTask.ServicePolicy sealed =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 0, 0);
        MiningServiceTask.ServicePolicy released =
                MiningServiceTask.ServicePolicy.rareOreBatch(64, 0, 1);

        assertEquals(MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE,
                sealed.emergencyBlocksReserved());
        assertEquals(MiningBudget.EMERGENCY_STONE_LIKE,
                released.emergencyBlocksReserved());
        assertEquals(MiningBudget.RARE_RETRY_CHANNEL_STONE_LIKE,
                sealed.emergencyBlocksReserved() - released.emergencyBlocksReserved());
        assertEquals(sealed.channelToolUsableDurability(),
                released.channelToolUsableDurability());
        assertEquals(640, sealed.torchMinCount());
        assertEquals(600, released.torchMinCount());
        assertEquals(12, sealed.foodMinUnits());
        assertEquals(8, released.foodMinUnits());
        assertEquals(210, sealed.futureStickReserve());
        assertEquals(196, released.futureStickReserve());
        assertEquals(60, MiningBudget.RARE_BOOTSTRAP_STONE_LIKE);
        assertThrows(IllegalArgumentException.class,
                () -> MiningServiceTask.ServicePolicy.rareOreBatch(64, 0, 2));
    }

    @Test
    void rareChannelPoolCoversTheBoundedBranchGeometry() {
        int maximumBranchBreaks = 436 * 2;
        int sixPickPool = 6 * MiningBudget.STONE_PICKAXE_USABLE_DURABILITY;
        int sevenPickPool = MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                * MiningBudget.STONE_PICKAXE_USABLE_DURABILITY;

        assertTrue(maximumBranchBreaks > sixPickPool);
        assertTrue(maximumBranchBreaks < sevenPickPool);
        assertEquals(910, sevenPickPool);
    }

    @Test
    void diamondStackDescentKitPinsTheFullMissionReserve() {
        MiningServiceTask.ServicePolicy policy =
                MiningServiceTask.ServicePolicy.rareDescentKit(64);

        assertEquals(650, policy.channelToolUsableDurability());
        assertEquals(72, policy.foodMinUnits());
        assertEquals(640, policy.torchMinCount());
        assertEquals(60, policy.emergencyBlocksReserved());
        assertEquals(224, policy.futureStickReserve());
        assertEquals(228, MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS);
    }

    @Test
    void obsidianFactoriesRejectImpossibleTransactionIdentities() {
        assertThrows(IllegalArgumentException.class,
                () -> MiningServiceTask.ServicePolicy.obsidianPreflight(0));
        assertThrows(IllegalArgumentException.class,
                () -> MiningServiceTask.ServicePolicy.obsidian8(32, 0));
        assertThrows(IllegalArgumentException.class,
                () -> MiningServiceTask.ServicePolicy.obsidian8(32, 7));
        assertThrows(IllegalArgumentException.class,
                () -> MiningServiceTask.ServicePolicy.obsidian8(8, 8));
        assertThrows(IllegalArgumentException.class,
                () -> MiningServiceTask.ServicePolicy.obsidian8(8, 16));
    }

    @Test
    void nonThirtyTwoTargetsFundEveryStrictlyEarlierEightBlockBoundary() {
        int[] targets = {1, 8, 9, 16, 17, 31, 32, 33};
        int[] expectedBootstrapSticks = {8, 8, 16, 16, 24, 32, 32, 40};
        int[] expectedBootstrapStone = {28, 28, 40, 40, 52, 64, 64, 76};
        for (int index = 0; index < targets.length; index++) {
            assertEquals(expectedBootstrapSticks[index],
                    MiningServiceTask.ServicePolicy.bootstrapStickTarget(targets[index]),
                    "sticks target=" + targets[index]);
            assertEquals(expectedBootstrapStone[index],
                    MiningServiceTask.ServicePolicy.bootstrapStoneLikeTarget(targets[index]),
                    "stone target=" + targets[index]);
        }
    }

}
