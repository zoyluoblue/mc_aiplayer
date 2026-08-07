package io.github.zoyluo.aibot.mining;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningBudgetTest {
    @Test
    void diamondStackIsSplitIntoEightDurableBatches() {
        MiningBudget budget = MiningBudget.forQuota(64, true, ToolTier.IRON);

        assertEquals(8, budget.batchSize());
        assertEquals(8, budget.batchCount());
        assertEquals(64, IntStream.range(0, budget.batchCount()).map(budget::batchTarget).sum());
        assertEquals(3, budget.initialPickaxes());
        assertEquals(5, budget.tunnelingPickaxes());
        assertEquals(0, budget.ordinaryChannelPickaxes());
        assertEquals(0, budget.ordinaryChannelRepairPickaxes());
        assertEquals(0, budget.ordinaryChannelRepairSticks());
        assertEquals(0, budget.ordinaryChannelRepairStoneLike());
        assertEquals(6, budget.spareToolIngots());
        assertEquals(256, budget.spareToolSticks());
        assertEquals(4, MiningBudget.TUNNELING_SERVICE_TARGET);
        assertEquals(7, MiningBudget.RARE_TUNNELING_SERVICE_TARGET);
        assertEquals(40, MiningBudget.RARE_BATCH_TORCH_LIMIT);
        assertEquals(1, MiningBudget.MAX_RARE_RESOURCE_RETRIES);
        assertEquals(1, MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH);
        assertEquals(720, budget.torchTarget());
        assertEquals(80, budget.cookedFoodTarget());
        assertEquals(60, budget.emergencyBlocks());
    }

    @Test
    void missionEpochMarginPoolIsBoundedAndFundedUpFront() {
        assertEquals(0, MiningBudget.rareMissionEpochMargin(0));
        assertEquals(0, MiningBudget.rareMissionEpochMargin(1));
        assertEquals(1, MiningBudget.rareMissionEpochMargin(2));
        assertEquals(1, MiningBudget.rareMissionEpochMargin(3));
        assertEquals(2, MiningBudget.rareMissionEpochMargin(4));
        // The 36-slot bootstrap-carry wall caps the pool no matter how many batches exist.
        assertEquals(2, MiningBudget.rareMissionEpochMargin(8));
        assertEquals(2, MiningBudget.rareMissionEpochMargin(100));
        assertEquals(MiningBudget.DIAMOND_STACK_EPOCH_MARGIN,
                MiningBudget.rareMissionEpochMargin(8));
        assertEquals(2, MiningBudget.rareMissionResourceEpochCapacity(1));
        assertEquals(4, MiningBudget.rareMissionResourceEpochCapacity(8));
        assertEquals(1, MiningBudget.rareMissionBatchCount(1));
        assertEquals(1, MiningBudget.rareMissionBatchCount(8));
        assertEquals(8, MiningBudget.rareMissionBatchCount(63));
        assertEquals(8, MiningBudget.rareMissionBatchCount(64));

        // The pinned 64-target bootstrap pools fund every margin epoch's food/torches/sticks.
        assertEquals(80, MiningBudget.RARE_BOOTSTRAP_FOOD);
        assertEquals(720, MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES);
        assertEquals(252, MiningBudget.DIAMOND_STACK_CHANNEL_REPAIR_STICKS);
        assertEquals(256, MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS);
        MiningBudget stack = MiningBudget.forQuota(64, true, ToolTier.IRON);
        assertEquals(MiningBudget.RARE_BOOTSTRAP_FOOD, stack.cookedFoodTarget());
        assertEquals(MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES, stack.torchTarget());
        assertEquals(MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS, stack.spareToolSticks());
    }

    @Test
    void largeOrdinaryCoalOwnsFiniteIndependentChannelHorizon() {
        MiningBudget budget = MiningBudget.forQuota(96, false, ToolTier.WOOD);

        assertEquals(16, budget.batchSize());
        assertEquals(6, budget.batchCount());
        assertEquals(4, budget.ordinaryChannelPickaxes());
        // Five inter-batch four-pick rebuilds plus one one-pick resupply per batch.
        assertEquals(5 * 4 + 6, budget.ordinaryChannelRepairPickaxes());
        assertEquals(52, budget.ordinaryChannelRepairSticks());
        assertEquals(78, budget.ordinaryChannelRepairStoneLike());
        assertEquals(1, MiningBudget.MAX_ORDINARY_CHANNEL_RESUPPLIES_PER_BATCH);
        assertEquals(0, budget.tunnelingPickaxes());
    }

    @Test
    void eightItemOrdinaryProbeStaysBelowChannelExpeditionThreshold() {
        MiningBudget budget = MiningBudget.forQuota(8, false, ToolTier.STONE);

        assertEquals(0, budget.ordinaryChannelPickaxes());
        assertEquals(0, budget.ordinaryChannelRepairPickaxes());
    }

    @Test
    void rareOreFoodBootstrapCoversEveryFundedEpochOfEveryBatch() {
        // Eight batches: 16 regular epochs + 2 capped margin epochs; four batches: 8 + 2.
        assertEquals(80, MiningBudget.forQuota(63, true, ToolTier.IRON).cookedFoodTarget());
        assertEquals(48, MiningBudget.forQuota(32, true, ToolTier.IRON).cookedFoodTarget());
        assertEquals(12, MiningBudget.rareServiceFoodMinimum(0));
        assertEquals(8, MiningBudget.rareServiceFoodMinimum(1));
        // Margin epochs clamp to the last regular epoch's floor instead of going negative.
        assertEquals(8, MiningBudget.rareServiceFoodMinimum(2));
        assertEquals(8, MiningBudget.rareServiceFoodMinimum(5));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> MiningBudget.rareServiceFoodMinimum(-1));
    }

    @Test
    void partialFinalBatchNeverOverclaimsQuota() {
        MiningBudget budget = MiningBudget.forQuota(18, true, ToolTier.IRON);

        assertEquals(3, budget.batchCount());
        assertEquals(8, budget.batchTarget(0));
        assertEquals(8, budget.batchTarget(1));
        assertEquals(2, budget.batchTarget(2));
        assertEquals(0, budget.batchTarget(3));
        assertEquals(5, budget.tunnelingPickaxes());
        assertEquals(0, budget.ordinaryChannelPickaxes());
        assertEquals(3, budget.spareToolIngots());
        // Three batches own six regular epochs plus one funded mission-margin epoch.
        assertEquals(100, budget.spareToolSticks());
        assertEquals(280, budget.torchTarget());
        assertEquals(36, budget.cookedFoodTarget());
    }

    @Test
    void smallRequestsKeepTheFastPath() {
        MiningBudget budget = MiningBudget.forQuota(3, true, ToolTier.IRON);

        assertEquals(1, budget.batchCount());
        assertEquals(1, budget.initialPickaxes());
        assertEquals(0, budget.tunnelingPickaxes());
        assertEquals(0, budget.ordinaryChannelPickaxes());
        assertEquals(0, budget.ordinaryChannelRepairPickaxes());
        assertEquals(0, budget.spareToolSticks());
        assertEquals(8, budget.torchTarget());
        assertEquals(0, budget.cookedFoodTarget());
    }
}
