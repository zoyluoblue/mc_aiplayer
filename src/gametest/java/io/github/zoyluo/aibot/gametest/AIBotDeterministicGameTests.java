package io.github.zoyluo.aibot.gametest;

import io.github.zoyluo.aibot.goal.Goal;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import io.github.zoyluo.aibot.persist.MissionSpec;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

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
    public void blockedEndpointPrefersNearestUpperStandOverDeeperCave(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(1, 3, 1));
        BlockPos requested = start.add(3, 0, 0);
        var world = context.getWorld();

        for (int dx = 0; dx < 3; dx++) {
            world.setBlockState(start.add(dx, -1, 0), Blocks.STONE.getDefaultState());
            world.setBlockState(start.add(dx, 0, 0), Blocks.AIR.getDefaultState());
            world.setBlockState(start.add(dx, 1, 0), Blocks.AIR.getDefaultState());
        }
        // The requested feet cell is a one-block obstacle. Both requested.up() and a cell three
        // blocks below are standable; endpoint resolution must honor actual distance.
        world.setBlockState(requested, Blocks.STONE.getDefaultState());
        world.setBlockState(requested.up(), Blocks.AIR.getDefaultState());
        world.setBlockState(requested.up(2), Blocks.AIR.getDefaultState());
        world.setBlockState(requested.down(), Blocks.AIR.getDefaultState());
        world.setBlockState(requested.down(2), Blocks.AIR.getDefaultState());
        world.setBlockState(requested.down(3), Blocks.AIR.getDefaultState());
        world.setBlockState(requested.down(4), Blocks.STONE.getDefaultState());

        var result = new AStarPathfinder(world, start, requested, 1_000, 50L, false, false).findPath();
        if (!result.success() || !requested.up().equals(result.resolvedGoal())) {
            context.throwGameTestException("blocked endpoint resolved away from nearest upper stand: "
                    + result.resolvedGoal() + " reason=" + result.reason());
        }
        context.complete();
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
