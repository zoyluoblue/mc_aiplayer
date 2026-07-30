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
        assertEquals(228, budget.spareToolSticks());
        assertEquals(4, MiningBudget.TUNNELING_SERVICE_TARGET);
        assertEquals(7, MiningBudget.RARE_TUNNELING_SERVICE_TARGET);
        assertEquals(40, MiningBudget.RARE_BATCH_TORCH_LIMIT);
        assertEquals(1, MiningBudget.MAX_RARE_RESOURCE_RETRIES);
        assertEquals(1, MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH);
        assertEquals(640, budget.torchTarget());
        assertEquals(72, budget.cookedFoodTarget());
        assertEquals(60, budget.emergencyBlocks());
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
    void rareOreFoodBootstrapCoversBothEpochsOfEveryBatch() {
        assertEquals(72, MiningBudget.forQuota(63, true, ToolTier.IRON).cookedFoodTarget());
        assertEquals(40, MiningBudget.forQuota(32, true, ToolTier.IRON).cookedFoodTarget());
        assertEquals(12, MiningBudget.rareServiceFoodMinimum(0));
        assertEquals(8, MiningBudget.rareServiceFoodMinimum(1));
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
        assertEquals(86, budget.spareToolSticks());
        assertEquals(240, budget.torchTarget());
        assertEquals(32, budget.cookedFoodTarget());
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
