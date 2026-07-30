package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reproduces the unsupported first-air furnace placement seen after DigDown returns to the surface. */
public final class SmeltFurnacePlacementGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "smeltFurnacePlacementLive", tickLimit = 800)
    public void unsupportedAirRetryCannotReplaceActiveClearingPickaxe(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 5, -48));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlockState(start.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        // seed 3000 的精确失败形态：NORTH 是没有任何可点击支撑的悬空 air，
        // 放炉会先在这里合法失败；EAST 是需要石镐清掉的铁矿。旧状态机在开始
        // 挖 EAST 后，下一 tick 会先重试 NORTH 并把镐换回 furnace。SOUTH/WEST
        // 用不可破坏块锁定唯一清障格，不让方向迭代顺序弱化用例。
        world.setBlockState(start.north(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.north().down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.north().down(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.east(), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.south(), Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.west(), Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);

        String name = "SmeltUnsupportedAirGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + AIBotConfig.get().profile());
        // 填满 main，并把 furnace 放在 offhand。首次找炉会无损提升它；清障开始后再
        // 把它放回 offhand，精确复现 active pick 被下一 tick findItem 交换掉的边界。
        for (var filler : new net.minecraft.item.Item[]{
                Items.DIRT, Items.SAND, Items.GRAVEL, Items.COBBLESTONE,
                Items.ANDESITE, Items.DIORITE, Items.GRANITE,
                Items.OAK_LOG, Items.BIRCH_LOG}) {
            InventoryAction.giveItem(bot, new ItemStack(filler, 64));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            if (bot.getInventory().main.get(slot).isEmpty()) {
                bot.getInventory().main.set(slot, new ItemStack(Items.NETHERRACK));
            }
        }
        bot.getInventory().offHand.set(0, new ItemStack(Items.FURNACE));
        bot.getInventory().markDirty();
        require(context, bot.getInventory().main.stream().noneMatch(stack -> stack.isOf(Items.FURNACE))
                        && bot.getOffHandStack().isOf(Items.FURNACE)
                        && InventoryAction.findItem(bot, Items.STONE_PICKAXE).orElse(-1) > 8
                        && bot.getInventory().main.stream().noneMatch(ItemStack::isEmpty),
                "fixture did not start full-main with offhand furnace and carried pickaxe");

        SmeltTask task = new SmeltTask(Items.RAW_IRON, Items.IRON_INGOT, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_smelt_enclosed_furnace"));
        boolean[] furnaceRehomedDuringClear = {false};
        context.runAtEveryTick(() -> {
            if (world.getBlockState(start.east()).isOf(Blocks.IRON_ORE)
                    && !bot.getActionPack().isMiningIdle()) {
                if (!furnaceRehomedDuringClear[0]) {
                    int furnaceMainSlot = -1;
                    for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
                        if (bot.getInventory().main.get(slot).isOf(Items.FURNACE)) {
                            furnaceMainSlot = slot;
                            break;
                        }
                    }
                    require(context, furnaceMainSlot >= 0,
                            "portable furnace was unavailable when active clearing began");
                    ItemStack furnace = bot.getInventory().main.get(furnaceMainSlot);
                    ItemStack displaced = bot.getOffHandStack();
                    bot.getInventory().main.set(furnaceMainSlot, displaced);
                    bot.getInventory().offHand.set(0, furnace);
                    bot.getInventory().markDirty();
                    furnaceRehomedDuringClear[0] = true;
                }
                require(context, bot.getMainHandStack().isOf(Items.STONE_PICKAXE),
                        "unsupported-air retry replaced the active clearing pick with "
                                + bot.getMainHandStack().getItem());
                require(context, InventoryAction.countItem(bot, Items.FURNACE) == 1,
                        "active-clear offhand handoff lost or duplicated the furnace");
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("enclosed-furnace smelt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, world.getBlockState(start.north()).isAir(),
                    "unsupported NORTH air shaft was unexpectedly filled");
            require(context, world.getBlockState(start.east()).isOf(Blocks.FURNACE),
                    "smelt did not clear the EAST iron ore and place the local furnace");
            require(context, furnaceRehomedDuringClear[0],
                    "fixture never reached the active-clear offhand furnace boundary");
            int pickaxeDamage = java.util.stream.Stream.concat(
                            bot.getInventory().main.stream(), bot.getInventory().offHand.stream())
                    .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                    .mapToInt(ItemStack::getDamage)
                    .sum();
            require(context, pickaxeDamage > 0,
                    "furnace cell was not physically cleared with the carried stone pickaxe");
            require(context, InventoryAction.countItem(bot, Items.IRON_INGOT) == 1,
                    "enclosed furnace did not physically produce one iron ingot");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 64,
                    "full-main offhand exchange lost the displaced selected stack");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "smeltFurnacePlacementLive", tickLimit = 800)
    public void skipsUnsupportedStairMouthAndCooksOnSupportedSide(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 5, -48));

        // Keep the bot's own floor and the EAST furnace floor. NORTH is a two-block-deep air shaft,
        // matching the returned DigDown stair mouth that used to be selected and failed first.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    world.setBlockState(start.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos supportedFurnace = start.east();
        world.setBlockState(supportedFurnace.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        String name = "SmeltFurnacePlacementGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + AIBotConfig.get().profile());
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE));
        InventoryAction.giveItem(bot, new ItemStack(Items.MUTTON));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));

        SmeltTask task = new SmeltTask(1, true);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_smelt_furnace_placement"));
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("stair-mouth smelt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, world.getBlockState(start.north()).isAir(),
                    "unsupported NORTH stair mouth was unexpectedly filled");
            require(context, world.getBlockState(supportedFurnace).isOf(Blocks.FURNACE),
                    "furnace was not placed on the supported EAST side");
            require(context, InventoryAction.countItem(bot, Items.COOKED_MUTTON) == 1,
                    "smelt completed without one physically cooked mutton");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "smeltFurnacePlacementLive", tickLimit = 800)
    public void craftsLocalFurnaceInsteadOfChasingFarRememberedSurfaceFurnace(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 4, 8));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlockState(start.add(dx, -1, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.add(dx, 0, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.add(dx, 1, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.add(dx, 2, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        BlockPos farSurfaceFurnace = start.add(0, 40, 0);
        world.setBlockState(farSurfaceFurnace, Blocks.FURNACE.getDefaultState(), Block.NOTIFY_ALL);

        String name = "SmeltLocalFurnaceGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.of(bot.getUuid())
                .markPlace("furnace", world, farSurfaceFurnace);
        // Exact seed-3000 regression: the bot first reached the remote furnace with five
        // cobblestone, mined four more while trying to get there, then could plan a furnace but
        // could not insert its output.  Consuming eight from a stack of nine leaves the source
        // slot occupied, and every other main slot was full.  The local recovery must physically
        // drop a disposable whole stack, retry the craft, and never chase the remembered furnace.
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 9));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            if (bot.getInventory().main.get(slot).isEmpty()) {
                bot.getInventory().main.set(slot, new ItemStack(Items.NETHERRACK, 64));
            }
        }
        bot.getInventory().markDirty();
        require(context, bot.getInventory().main.stream().noneMatch(ItemStack::isEmpty),
                "fixture did not start with a full main inventory");

        SmeltTask task = new SmeltTask(Items.RAW_IRON, Items.IRON_INGOT, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_smelt_local_furnace"));
        AtomicBoolean observedPhysicalDisposal = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            if (!world.getEntitiesByClass(ItemEntity.class, new Box(start).expand(8.0D),
                    entity -> entity.getStack().isOf(Items.DIRT)
                            && entity.getStack().getCount() == 8).isEmpty()) {
                observedPhysicalDisposal.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("local-furnace smelt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            boolean localFurnace = BlockPos.stream(start.add(-2, -1, -2), start.add(2, 2, 2))
                    .anyMatch(pos -> world.getBlockState(pos).isOf(Blocks.FURNACE));
            require(context, localFurnace, "a local furnace was not crafted and placed");
            require(context, world.getBlockState(farSurfaceFurnace).isOf(Blocks.FURNACE),
                    "far remembered furnace was unexpectedly removed");
            require(context, bot.getBlockPos().getSquaredDistance(start) <= 4.0D,
                    "bot chased the far furnace instead of smelting locally: " + bot.getBlockPos());
            require(context, InventoryAction.countItem(bot, Items.IRON_INGOT) == 1,
                    "local furnace did not physically produce one iron ingot");
            require(context, observedPhysicalDisposal.get(),
                    "capacity recovery did not create an ordinary dirt ItemEntity");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 1,
                    "local furnace craft did not consume exactly eight of nine cobblestone");
            io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.remove(bot.getUuid());
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "smeltFurnacePlacementLive", tickLimit = 800)
    public void craftsLocalFurnaceWhenRememberedSurfaceFurnaceIsBeyondLookupRadius(
            TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 4, 8));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlockState(start.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        // SmeltTask intentionally ignores remembered furnaces beyond 96 blocks. This reproduces
        // seed3000's surface furnace at distance 100.7 while keeping the exact block loaded.
        BlockPos farSurfaceFurnace = start.up(97);
        world.setBlockState(farSurfaceFurnace,
                Blocks.FURNACE.getDefaultState(), Block.NOTIFY_ALL);

        String name = "SmeltBeyondMemoryGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + AIBotConfig.get().profile());
        io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.of(bot.getUuid())
                .markPlace("furnace", world, farSurfaceFurnace);

        // Preserve non-zero baselines so the test catches absolute-vs-incremental accounting bugs.
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 12));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        require(context, surfaceCraftMaterialCount(bot) == 0,
                "fixture unexpectedly started with a surface craft material");

        SmeltTask task = new SmeltTask(Items.RAW_IRON, Items.IRON_INGOT, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_smelt_beyond_memory_furnace"));
        context.runAtEveryTick(() -> {
            require(context, surfaceCraftMaterialCount(bot) == 0,
                    "local furnace repair acquired a log/plank underground");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("beyond-memory smelt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            boolean localFurnace = BlockPos.stream(start.add(-2, -1, -2), start.add(2, 2, 2))
                    .anyMatch(pos -> world.getBlockState(pos).isOf(Blocks.FURNACE));
            require(context, localFurnace, "missing locally crafted furnace beyond memory radius");
            require(context, world.getBlockState(farSurfaceFurnace).isOf(Blocks.FURNACE),
                    "far remembered furnace was changed or removed");
            require(context, bot.getBlockPos().getSquaredDistance(start) <= 4.0D,
                    "bot chased the out-of-radius furnace: " + bot.getBlockPos());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 4,
                    "local furnace did not consume exactly eight cobblestone");
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == 1,
                    "incremental smelt consumed the wrong raw-iron baseline");
            require(context, InventoryAction.countItem(bot, Items.IRON_INGOT) == 3,
                    "incremental smelt did not preserve the two-ingot baseline");
            require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                    "local furnace craft consumed the carried crafting table");
            require(context, InventoryAction.countItem(bot, Items.FURNACE) == 0,
                    "local furnace remained duplicated in inventory after placement");
            io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.remove(bot.getUuid());
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    private static int surfaceCraftMaterialCount(AIPlayerEntity bot) {
        return java.util.stream.Stream.concat(RecipeRegistry.LOGS.stream(), RecipeRegistry.PLANKS.stream())
                .mapToInt(item -> InventoryAction.countItem(bot, item))
                .sum();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
