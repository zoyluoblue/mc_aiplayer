package io.github.zoyluo.aibot.gametest;

import io.github.zoyluo.aibot.goal.Goal;
import io.github.zoyluo.aibot.persist.MissionSpec;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.List;
import java.util.Set;

/**
 * Minimal world-backed smoke tests for the isolated GameTest source set.
 *
 * <p>These tests deliberately avoid random state, external services and AIBot persistence so a
 * failure always reflects the compiled mod/runtime rather than a reused world.</p>
 */
public final class AIBotDeterministicGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void blockMutationIsVisible(TestContext context) {
        context.setBlockState(1, 1, 1, Blocks.STONE);
        context.expectBlock(Blocks.STONE, 1, 1, 1);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void scheduledAssertionRunsAtExpectedTick(TestContext context) {
        context.setBlockState(2, 1, 2, Blocks.OAK_PLANKS);
        context.runAtTick(2, () -> {
            context.expectBlock(Blocks.OAK_PLANKS, 2, 1, 2);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void missionSpecsRoundTripWithBootstrappedRegistries(TestContext context) {
        List<Goal> goals = List.of(
                new Goal.HaveItem(Items.IRON_INGOT, 3),
                new Goal.HavePickaxeTier(3),
                new Goal.MineOre(Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), 4),
                new Goal.HarvestCrop(Blocks.WHEAT, Items.WHEAT_SEEDS, Items.WHEAT, 8),
                new Goal.Armor(),
                new Goal.Workstation(),
                new Goal.Stockpile(Items.COBBLESTONE, 64),
                new Goal.Food(5),
                new Goal.Build("small_hut"));

        for (Goal goal : goals) {
            Goal restored = MissionSpec.fromGoal(goal).toGoal().orElseThrow();
            if (!goal.equals(restored)) {
                context.throwGameTestException("MissionSpec round-trip mismatch for " + goal);
            }
        }
        context.complete();
    }
}
