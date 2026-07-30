package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.ResupplyTask;
import io.github.zoyluo.aibot.task.TaskState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.List;
import java.util.Set;

/** Strict-survival proofs that executable inventory paths honor offhand resources. */
public final class OffhandExecutionGameTests implements FabricGameTest {
    private static final String BATCH = "offhandExecutionStrict";

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 260)
    public void raw33OffhandDiamondPickIsSelectedAndBreaksObsidian(TestContext context) {
        Fixture fixture = spawn(context, "OffhandObsidianPickGT", new BlockPos(4, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        BlockPos target = fixture.feet().north();
        context.getWorld().setBlockState(
                target, Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);

        // A completely full main inventory forces the offhand promotion to exchange with the
        // selected hotbar slot. The displaced dirt must remain in offhand, never disappear.
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            bot.getInventory().main.set(slot, new ItemStack(Items.DIRT));
        }
        bot.getInventory().selectedSlot = 0;
        ItemStack diamond = new ItemStack(Items.DIAMOND_PICKAXE);
        diamond.setDamage(diamond.getMaxDamage() - 33);
        bot.getInventory().offHand.set(0, diamond);
        bot.getInventory().markDirty();

        ToolSelector.Selection channel = ToolSelector.equipMiningChannelTool(
                bot, context.getWorld().getBlockState(target));
        require(context, channel.changed()
                        && bot.getMainHandStack().isOf(Items.DIAMOND_PICKAXE),
                "mining-channel selector did not promote the offhand raw33 pick");
        require(context, bot.getOffHandStack().isOf(Items.DIRT)
                        && InventoryAction.countItem(bot, Items.DIRT) == 36,
                "full-inventory channel promotion lost the selected main stack");

        // Restore the exact full-inventory boundary so the physical break exercises the ordinary
        // equipBestTool path used by CreateObsidianTask, not the already-promoted main candidate.
        ItemStack promoted = bot.getMainHandStack();
        ItemStack displaced = bot.getOffHandStack();
        bot.getInventory().main.set(bot.getInventory().selectedSlot, displaced);
        bot.getInventory().offHand.set(0, promoted);
        bot.getInventory().markDirty();

        BlockMiner miner = new BlockMiner();
        miner.begin(bot, target);
        context.runAtEveryTick(() -> {
            BlockMiner.Status status = miner.tick(bot);
            if (status == BlockMiner.Status.FAILED) {
                context.throwGameTestException(
                        "offhand raw33 pick failed physical obsidian break: "
                                + miner.failureReason());
                return;
            }
            if (status != BlockMiner.Status.DONE) {
                return;
            }
            require(context, context.getWorld().getBlockState(target).isAir(),
                    "BlockMiner reported DONE without physically breaking obsidian");
            require(context, bot.getMainHandStack().isOf(Items.DIAMOND_PICKAXE)
                            && bot.getMainHandStack().getMaxDamage()
                            - bot.getMainHandStack().getDamage() == 32,
                    "physical break did not consume exactly one raw durability");
            require(context, bot.getOffHandStack().isOf(Items.DIRT)
                            && InventoryAction.countItem(bot, Items.DIRT) == 36,
                    "ordinary tool selection lost the main stack displaced into offhand");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 80)
    public void miningChannelBreaksSoftObstructionWithEmptyHand(TestContext context) {
        Fixture fixture = spawn(context, "EmptyHandSoftBlockGT", new BlockPos(7, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        BlockPos target = fixture.feet().north();
        context.getWorld().setBlockState(
                target, Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_ALL);

        // Reproduce the strict water-return boundary: the selected hand is empty and every
        // remaining hotbar candidate is an unusable raw-1 stone pick. Dirt is still legally and
        // physically mineable by hand, so the mining-channel policy must not report a missing tool.
        bot.getInventory().selectedSlot = 0;
        for (int slot = 1; slot < 9; slot++) {
            ItemStack exhausted = new ItemStack(Items.STONE_PICKAXE);
            exhausted.setDamage(exhausted.getMaxDamage() - 1);
            bot.getInventory().main.set(slot, exhausted);
        }
        bot.getInventory().markDirty();

        BlockMiner miner = new BlockMiner();
        miner.begin(bot, target, true);
        context.runAtEveryTick(() -> {
            BlockMiner.Status status = miner.tick(bot);
            if (status == BlockMiner.Status.FAILED) {
                context.throwGameTestException(
                        "empty-hand soft obstruction failed: " + miner.failureReason());
                return;
            }
            if (status != BlockMiner.Status.DONE) {
                return;
            }
            require(context, context.getWorld().getBlockState(target).isAir(),
                    "BlockMiner reported DONE without physically breaking grass");
            require(context, bot.getMainHandStack().isEmpty(),
                    "soft obstruction consumed or equipped an exhausted mining tool");
            for (int slot = 1; slot < 9; slot++) {
                ItemStack stack = bot.getInventory().main.get(slot);
                require(context, stack.isOf(Items.STONE_PICKAXE)
                                && stack.getMaxDamage() - stack.getDamage() == 1,
                        "soft obstruction changed raw-1 stone pick in slot " + slot);
            }
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 80)
    public void softBlockSpeedTiePreservesWoodenSwordDurability(TestContext context) {
        Fixture fixture = spawn(context, "SoftBlockSwordPreserveGT", new BlockPos(7, 4, 7));
        AIPlayerEntity bot = fixture.bot();
        BlockPos target = fixture.feet().north();
        context.getWorld().setBlockState(
                target, Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);

        ItemStack sword = new ItemStack(Items.WOODEN_SWORD);
        sword.setDamage(9);
        bot.getInventory().selectedSlot = 0;
        bot.getInventory().main.set(0, sword);
        bot.getInventory().markDirty();

        BlockMiner miner = new BlockMiner();
        miner.begin(bot, target);
        context.runAtEveryTick(() -> {
            BlockMiner.Status status = miner.tick(bot);
            if (status == BlockMiner.Status.FAILED) {
                context.throwGameTestException(
                        "soft-block sword preservation failed: " + miner.failureReason());
                return;
            }
            if (status != BlockMiner.Status.DONE) {
                return;
            }
            require(context, context.getWorld().getBlockState(target).isAir(),
                    "soft-block preservation reported DONE without breaking dirt");
            require(context, bot.getMainHandStack().isEmpty(),
                    "soft-block speed tie retained a durability-bearing melee weapon");
            require(context, bot.getInventory().main.get(0).isOf(Items.WOODEN_SWORD)
                            && bot.getInventory().main.get(0).getDamage() == 9,
                    "soft-block mining consumed wooden-sword durability");
            cleanup(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void offhandOnlyCraftingTableCompletesThreeByThreeRecipe(TestContext context) {
        Fixture fixture = spawn(context, "OffhandCraftingTableGT", new BlockPos(9, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        bot.getInventory().offHand.set(0, new ItemStack(Items.CRAFTING_TABLE));
        bot.getInventory().markDirty();

        CraftTask task = new CraftTask(Items.IRON_PICKAXE, 1);
        task.start(bot);
        for (int tick = 0; tick < 20 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }

        require(context, task.state() == TaskState.COMPLETED,
                "offhand-only table could not complete 3x3 craft: " + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 1,
                "3x3 craft did not produce the iron pickaxe");
        require(context, bot.getInventory().main.stream()
                        .anyMatch(stack -> stack.isOf(Items.CRAFTING_TABLE)),
                "CraftTask did not promote the offhand-only table into executable inventory");
        require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1
                        && InventoryAction.countItem(bot, Items.IRON_INGOT) == 0
                        && InventoryAction.countItem(bot, Items.STICK) == 0,
                "3x3 craft duplicated or lost table/ingredients");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void mixedOakAndBirchLogsCompleteOneAtomicStickPlan(TestContext context) {
        Fixture fixture = spawn(context, "MixedFamilyCraftGT", new BlockPos(12, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.BIRCH_LOG, 6));
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));

        CraftTask task = new CraftTask(Items.STICK, 58);
        task.start(bot);
        for (int tick = 0; tick < 12 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }

        require(context, task.state() == TaskState.COMPLETED,
                "mixed-family craft failed: " + task.failureReason());
        int remainingPlanks = InventoryAction.countItem(bot, Items.OAK_PLANKS)
                + InventoryAction.countItem(bot, Items.BIRCH_PLANKS);
        int remainingLogs = InventoryAction.countItem(bot, Items.OAK_LOG)
                + InventoryAction.countItem(bot, Items.BIRCH_LOG);
        require(context, InventoryAction.countItem(bot, Items.STICK) == 58
                        && remainingLogs * 4 + remainingPlanks == 6,
                "mixed-family craft duplicated or stranded inputs/intermediates");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void insufficientMixedFamilyCapacityLeavesInventoryBitExact(TestContext context) {
        Fixture fixture = spawn(context, "MixedFamilyRollbackGT", new BlockPos(15, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG));
        InventorySnapshot before = inventorySnapshot(bot);

        CraftTask task = new CraftTask(Items.STICK, 16);
        task.start(bot);
        for (int tick = 0; tick < 4 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }

        require(context, task.state() == TaskState.FAILED,
                "insufficient mixed-family craft did not fail");
        require(context, "need: minecraft:oak_planks x4".equals(task.failureReason()),
                "remaining family deficit was not reported once: " + task.failureReason());
        requireInventoryEquals(context, before, bot);
        require(context, InventoryAction.countItem(bot, Items.OAK_LOG) == 1
                        && InventoryAction.countItem(bot, Items.STICK) == 0
                        && InventoryAction.countItem(bot, Items.OAK_PLANKS) == 0,
                "failed family aggregation changed live inventory");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 40)
    public void insufficientCraftOutputCapacityLeavesInventoryBitExact(TestContext context) {
        Fixture fixture = spawn(context, "AtomicCraftCapacityGT", new BlockPos(14, 4, 4));
        AIPlayerEntity bot = fixture.bot();

        // The recipe consumes its only main-inventory material stack and therefore creates room
        // for just one of five unstackable outputs. Sticks live in offhand to prove that freeing
        // offhand does not count as output capacity. Before the guard this lost all ingredients
        // and inserted one pick before insertStack reported failure for the remaining four.
        ItemStack existingPick = new ItemStack(Items.STONE_PICKAXE);
        existingPick.setDamage(17);
        bot.getInventory().main.set(0, existingPick);
        bot.getInventory().main.set(1, new ItemStack(Items.COBBLESTONE, 15));
        bot.getInventory().main.set(2, new ItemStack(Items.CRAFTING_TABLE));
        for (int slot = 3; slot < bot.getInventory().main.size(); slot++) {
            bot.getInventory().main.set(slot, new ItemStack(Items.DIRT, slot + 1));
        }
        bot.getInventory().offHand.set(0, new ItemStack(Items.STICK, 10));
        bot.getInventory().markDirty();

        InventorySnapshot before = inventorySnapshot(bot);
        CraftTask task = new CraftTask(Items.STONE_PICKAXE, 6);
        task.start(bot);
        for (int tick = 0; tick < 4 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }

        require(context, task.state() == TaskState.FAILED,
                "capacity-constrained craft did not fail");
        require(context, task.failureReason().equals(
                        "craft_output_capacity:item=minecraft:stone_pickaxe:count=5:available=1"),
                "unexpected capacity failure reason: " + task.failureReason());
        requireInventoryEquals(context, before, bot);
        require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 1
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == 15
                        && InventoryAction.countItem(bot, Items.STICK) == 10,
                "capacity failure changed ingredients or existing output counts");
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 80)
    public void fullInventoryToolResupplyDropsJunkAndCraftsUsablePickaxe(TestContext context) {
        Fixture fixture = spawn(context, "ResupplyCapacityGT", new BlockPos(17, 4, 4));
        AIPlayerEntity bot = fixture.bot();

        for (int slot = 0; slot < 5; slot++) {
            ItemStack exhausted = new ItemStack(Items.STONE_PICKAXE);
            exhausted.setDamage(exhausted.getMaxDamage() - 1);
            bot.getInventory().main.set(slot, exhausted);
        }
        bot.getInventory().main.set(5, new ItemStack(Items.COBBLESTONE, 11));
        bot.getInventory().main.set(6, new ItemStack(Items.CRAFTING_TABLE));
        bot.getInventory().main.set(7, new ItemStack(Items.STICK, 42));
        bot.getInventory().main.set(8, new ItemStack(Items.DIRT, 8));
        for (int slot = 9; slot < bot.getInventory().main.size(); slot++) {
            bot.getInventory().main.set(slot, new ItemStack(Items.NETHERRACK, 64));
        }
        bot.getInventory().markDirty();
        require(context, bot.getInventory().main.stream().noneMatch(ItemStack::isEmpty),
                "fixture did not start with a full main inventory");

        ResupplyTask task = ResupplyTask.tool(Items.STONE_PICKAXE);
        task.start(bot);
        for (int tick = 0; tick < 30 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
        }

        require(context, task.state() == TaskState.COMPLETED,
                "full-inventory tool resupply failed: " + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 6
                        && bot.getMainHandStack().isOf(Items.STONE_PICKAXE)
                        && bot.getMainHandStack().getDamage() == 0,
                "resupply did not craft and equip one usable stone pickaxe");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 8
                        && InventoryAction.countItem(bot, Items.STICK) == 40
                        && InventoryAction.countItem(bot, Items.DIRT) == 0,
                "capacity recovery spent or retained the wrong inventory stacks");
        int droppedDirt = context.getWorld().getEntitiesByClass(
                        ItemEntity.class,
                        new Box(fixture.feet()).expand(4.0D),
                        entity -> entity.getStack().isOf(Items.DIRT))
                .stream()
                .mapToInt(entity -> entity.getStack().getCount())
                .sum();
        require(context, droppedDirt == 8,
                "capacity recovery did not create the exact ordinary dirt ItemEntity: "
                        + droppedDirt);
        cleanup(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = BATCH, tickLimit = 20)
    public void offhandFoodPromotionPreservesAFullSelectedSlot(TestContext context) {
        Fixture fixture = spawn(context, "OffhandFoodGT", new BlockPos(14, 4, 4));
        AIPlayerEntity bot = fixture.bot();
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            bot.getInventory().main.set(slot, new ItemStack(Items.DIRT));
        }
        bot.getInventory().selectedSlot = 0;
        bot.getInventory().offHand.set(0, new ItemStack(Items.BREAD));
        bot.getInventory().markDirty();

        int foodSlot = InventoryAction.findFoodSlot(bot);
        require(context, foodSlot == bot.getInventory().selectedSlot
                        && bot.getInventory().main.get(foodSlot).isOf(Items.BREAD),
                "offhand food was not promoted into a selectable main slot");
        require(context, bot.getOffHandStack().isOf(Items.DIRT)
                        && InventoryAction.countItem(bot, Items.DIRT) == 36
                        && InventoryAction.countItem(bot, Items.BREAD) == 1,
                "offhand food promotion lost or duplicated a full-inventory stack");
        cleanup(context, fixture);
    }

    private static Fixture spawn(TestContext context, String name, BlockPos relativeFeet) {
        var world = context.getWorld();
        world.setTimeOfDay(1000L);
        BlockPos feet = context.getAbsolutePos(relativeFeet);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(feet),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        return new Fixture(name, bot, feet);
    }

    private static void cleanup(TestContext context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    private static InventorySnapshot inventorySnapshot(AIPlayerEntity bot) {
        return new InventorySnapshot(
                bot.getInventory().main.stream().map(ItemStack::copy).toList(),
                bot.getInventory().offHand.stream().map(ItemStack::copy).toList());
    }

    private static void requireInventoryEquals(
            TestContext context, InventorySnapshot expected, AIPlayerEntity bot) {
        require(context, expected.main().size() == bot.getInventory().main.size()
                        && expected.offHand().size() == bot.getInventory().offHand.size(),
                "inventory region size changed during failed craft");
        for (int slot = 0; slot < expected.main().size(); slot++) {
            require(context, ItemStack.areEqual(
                            expected.main().get(slot), bot.getInventory().main.get(slot)),
                    "main inventory changed at slot " + slot);
        }
        for (int slot = 0; slot < expected.offHand().size(); slot++) {
            require(context, ItemStack.areEqual(
                            expected.offHand().get(slot), bot.getInventory().offHand.get(slot)),
                    "offhand inventory changed at slot " + slot);
        }
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private record Fixture(String name, AIPlayerEntity bot, BlockPos feet) {
    }

    private record InventorySnapshot(List<ItemStack> main, List<ItemStack> offHand) {
    }
}
