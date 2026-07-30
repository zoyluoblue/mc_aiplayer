package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;

/** Full-inventory proofs for restoring an active mining tool after offhand torch promotion. */
public final class MiningTorchToolRestoreGameTests implements FabricGameTest {
    private static final String BATCH = "miningTorchToolRestoreStrict";

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void descendTorchAttemptRestoresActiveStonePick(TestContext context) {
        Fixture fixture = spawn(context, "DescendTorchRestoreGT", new BlockPos(4, 4, 4));
        BlockMiner miner = beginActiveStoneClear(context, fixture, false);

        attemptOffhandTorchPlacement(context, fixture);
        DescendToYTask.restoreActiveMiningTool(fixture.bot(), context.getWorld(), miner);

        assertActivePickRestored(context, fixture, miner, "descend");
        cleanup(context, fixture, miner);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void oreDigTorchAttemptRestoresActiveChannelPick(TestContext context) {
        Fixture fixture = spawn(context, "OreDigTorchRestoreGT", new BlockPos(10, 4, 4));
        BlockMiner miner = beginActiveStoneClear(context, fixture, true);

        attemptOffhandTorchPlacement(context, fixture);
        OreDigTask.restoreActiveChannelTool(fixture.bot(), context.getWorld(), miner);

        assertActivePickRestored(context, fixture, miner, "ore_dig");
        cleanup(context, fixture, miner);
    }

    private static BlockMiner beginActiveStoneClear(TestContext context,
                                                     Fixture fixture,
                                                     boolean channelPolicy) {
        AIPlayerEntity bot = fixture.bot();
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            bot.getInventory().main.set(slot, new ItemStack(Items.DIRT));
        }
        bot.getInventory().selectedSlot = 0;
        bot.getInventory().main.set(0, new ItemStack(Items.STONE_PICKAXE));
        bot.getInventory().offHand.set(0, new ItemStack(Items.TORCH));
        bot.getInventory().markDirty();

        BlockMiner miner = new BlockMiner();
        miner.begin(bot, fixture.target(), channelPolicy);
        BlockMiner.Status status = miner.tick(bot);
        require(context, status == BlockMiner.Status.MINING
                        && fixture.target().equals(miner.target())
                        && bot.getMainHandStack().isOf(Items.STONE_PICKAXE),
                "fixture did not open an active stone-pick clear");
        return miner;
    }

    private static void attemptOffhandTorchPlacement(TestContext context, Fixture fixture) {
        AIPlayerEntity bot = fixture.bot();
        int torchSlot = InventoryAction.findItem(bot, Items.TORCH).orElse(-1);
        require(context, torchSlot == bot.getInventory().selectedSlot
                        && bot.getOffHandStack().isOf(Items.STONE_PICKAXE),
                "full-main torch promotion did not exchange the active pick into offhand");
        InventoryAction.equipFromSlot(bot, torchSlot);
        BuildAction.placeBlockAt(bot, fixture.torchPos());
    }

    private static void assertActivePickRestored(TestContext context,
                                                 Fixture fixture,
                                                 BlockMiner miner,
                                                 String owner) {
        AIPlayerEntity bot = fixture.bot();
        require(context, fixture.target().equals(miner.target()),
                owner + " torch handoff discarded the active BlockMiner target");
        require(context, bot.getMainHandStack().isOf(Items.STONE_PICKAXE),
                owner + " torch handoff left the active miner holding "
                        + bot.getMainHandStack().getItem());
        require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 1
                        && InventoryAction.countItem(bot, Items.DIRT) == 35,
                owner + " torch handoff lost or duplicated a full-inventory stack");
    }

    private static Fixture spawn(TestContext context, String name, BlockPos relativeFeet) {
        var world = context.getWorld();
        BlockPos feet = context.getAbsolutePos(relativeFeet);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        BlockPos target = feet.north();
        BlockPos torchPos = feet.south();
        world.setBlockState(target, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(feet),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        return new Fixture(name, bot, target, torchPos);
    }

    private static void cleanup(TestContext context, Fixture fixture, BlockMiner miner) {
        miner.cancel(fixture.bot());
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private record Fixture(String name,
                           AIPlayerEntity bot,
                           BlockPos target,
                           BlockPos torchPos) {
    }
}
