package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mining.MiningCursor;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.stat.Stats;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Live scheduling proofs for DangerWatcher's task-preemption boundaries. */
public final class DangerWatcherLowHealthGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void exactObsidianPickBudgetIsNotPreemptedByGenericResupply(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "ObsidianToolBudgetGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);

        // Give the damaged pick first so it is genuinely held. Raw 33 means exactly 32 usable
        // breaks: sufficient for this mission, but well inside DangerWatcher's generic 10% band.
        ItemStack diamond = new ItemStack(Items.DIAMOND_PICKAXE);
        diamond.setDamage(diamond.getMaxDamage() - 33);
        InventoryAction.giveItem(bot, diamond);
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 52));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 24));
        for (int index = 0; index < 4; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        require(context, bot.getMainHandStack().isOf(Items.DIAMOND_PICKAXE)
                        && MiningServiceTask.usableDurability(bot.getMainHandStack()) == 32,
                "fixture did not hold the exact raw-33 obsidian pick");

        MiningServiceTask service = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(32));
        TaskManager.INSTANCE.assign(bot, service,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_obsidian_service_tool_budget"));
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        requireUnpreempted(context, bot, service, "MiningServiceTask");

        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_service_probe_complete");
        CreateObsidianTask create = new CreateObsidianTask(32);
        TaskManager.INSTANCE.assign(bot, create,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_create_obsidian_tool_budget"));
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        requireUnpreempted(context, bot, create, "CreateObsidianTask");
        require(context, MiningServiceTask.usableDurability(bot.getMainHandStack()) == 32,
                "generic resupply damaged or replaced the exact-budget pick");

        TaskManager.INSTANCE.pauseFor(bot, "gametest_obsidian_safety_pause");
        require(context, TaskManager.INSTANCE.peekPaused(bot).orElse(null) == create,
                "fixture did not preserve the paused CreateObsidianTask");
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        requireUnpreempted(context, bot, create, "paused CreateObsidianTask");
        require(context, MiningServiceTask.usableDurability(bot.getMainHandStack()) == 32,
                "paused exact-budget owner triggered generic local tool crafting");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "dangerWatcherMiningToolOwnership", tickLimit = 800)
    public void createObsidianRawOneSettlementIsNotPreempted(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CreateRawOneOwnerGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        BlockPos target = bot.getBlockPos().east();
        bot.getServerWorld().setBlockState(
                target, Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);

        ItemStack damagedDiamond = new ItemStack(Items.DIAMOND_PICKAXE);
        damagedDiamond.setDamage(damagedDiamond.getMaxDamage() - 2);
        InventoryAction.giveItem(bot, damagedDiamond);
        ItemStack diamond = bot.getMainHandStack();
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 32));
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET));
        require(context, diamond.isOf(Items.DIAMOND_PICKAXE) && rawDurability(diamond) == 2,
                "fixture did not hold the raw-two diamond pick");

        CreateObsidianTask task = new CreateObsidianTask(
                1, createActiveBreakCheckpoint(bot.getBlockPos(), target, bot.getBlockPos()));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_create_raw_one_owner"));
        AtomicBoolean scannedRawOneSettlement = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("raw-two CreateObsidianTask ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint());
            }
            if (!scannedRawOneSettlement.get()
                    && bot.getServerWorld().getBlockState(target).isAir()
                    && rawDurability(diamond) == 1
                    && task.state() == TaskState.RUNNING) {
                Map<String, String> settlement = task.checkpoint();
                require(context, encode(target).equals(settlement.get("active_break_pos"))
                                || encode(target).equals(settlement.get("pending_pickup_pos")),
                        "raw-one Create state was not an active-break/pickup settlement: "
                                + settlement);
                require(context, bot.getMainHandStack().getItem() == Items.DIAMOND_PICKAXE,
                        "raw-one Create settlement was not holding its pickaxe");
                DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
                requireUnpreempted(context, bot, task, "raw-one CreateObsidianTask settlement");
                scannedRawOneSettlement.set(true);
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, scannedRawOneSettlement.get(),
                        "CreateObsidianTask completed without exposing the raw-one settlement window");
                require(context, InventoryAction.countItem(bot, Items.OBSIDIAN) == 1,
                        "CreateObsidianTask did not physically settle the final obsidian pickup");
                require(context, rawDurability(diamond) == 1,
                        "legal raw-two break did not leave the expected raw-one pick");
                despawnAndComplete(context, bot);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "dangerWatcherMiningToolOwnership", tickLimit = 40)
    public void oreDigRawOnePickupAndActiveBreakRemainOwned(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "OreRawOneOwnerGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        ItemStack damagedDiamond = new ItemStack(Items.DIAMOND_PICKAXE);
        damagedDiamond.setDamage(damagedDiamond.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, damagedDiamond);
        ItemStack diamond = bot.getMainHandStack();
        require(context, diamond.isOf(Items.DIAMOND_PICKAXE) && rawDurability(diamond) == 1,
                "fixture did not hold the raw-one diamond pick");

        BlockPos debt = bot.getBlockPos().east();
        OreDigTask pickupOwner = new OreDigTask(Set.of(Blocks.IRON_ORE), 1,
                oreDigCheckpoint(bot.getBlockPos(), debt, null));
        TaskManager.INSTANCE.assign(bot, pickupOwner,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_raw_one_pickup_owner"));
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        requireUnpreempted(context, bot, pickupOwner, "raw-one OreDigTask pickup");
        require(context, encode(debt).equals(pickupOwner.checkpoint().get("pending_pickup_pos")),
                "OreDig pickup debt was changed by DangerWatcher");

        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_pickup_owner_probe_complete");
        bot.getServerWorld().setBlockState(
                debt, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        OreDigTask activeBreakOwner = new OreDigTask(Set.of(Blocks.IRON_ORE), 1,
                oreDigCheckpoint(bot.getBlockPos(), null, debt));
        TaskManager.INSTANCE.assign(bot, activeBreakOwner,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_raw_one_active_owner"));
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        requireUnpreempted(context, bot, activeBreakOwner, "raw-one OreDigTask active break");
        require(context, encode(debt).equals(activeBreakOwner.checkpoint().get("active_break_pos")),
                "OreDig active-break debt was changed by DangerWatcher");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "dangerWatcherMiningToolOwnership", tickLimit = 40)
    public void rawOnePickOnNonOwnerStillTriggersGenericResupply(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "RawOneNonOwnerGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        ItemStack diamond = new ItemStack(Items.DIAMOND_PICKAXE);
        diamond.setDamage(diamond.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, diamond);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_raw_one_non_owner"));

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof ResupplyTask,
                "non-owner raw-one pick did not trigger generic resupply: "
                        + (active == null ? "idle" : active.name()));
        require(context, TaskManager.INSTANCE.hasPaused(bot)
                        && work.state() == TaskState.PAUSED,
                "generic resupply did not preserve the interrupted non-owner task");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "dangerWatcherMiningToolOwnership", tickLimit = 40)
    public void stonePickCraftIgnoresNearlyBrokenHeldPick(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CraftHeldPickOwnerGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        ItemStack nearlyBroken = new ItemStack(Items.STONE_PICKAXE);
        nearlyBroken.setDamage(nearlyBroken.getMaxDamage() - 2);
        InventoryAction.giveItem(bot, nearlyBroken);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 15));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 10));
        require(context, bot.getMainHandStack().isOf(Items.STONE_PICKAXE)
                        && rawDurability(bot.getMainHandStack()) == 2,
                "fixture did not hold the nearly-broken stone pick");

        CraftTask craft = new CraftTask(Items.STONE_PICKAXE, 5);
        TaskManager.INSTANCE.assign(bot, craft,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_craft_held_pick_owner"));
        int sticksBefore = InventoryAction.countItem(bot, Items.STICK);
        int stoneBefore = InventoryAction.countItem(bot, Items.COBBLESTONE);

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        requireUnpreempted(context, bot, craft, "stone-pick CraftTask");
        require(context, InventoryAction.countItem(bot, Items.STICK) == sticksBefore
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == stoneBefore,
                "generic resupply spent sealed craft inputs");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "dangerWatcherMiningToolOwnership", tickLimit = 80)
    public void pausedMiningOwnerResuppliesInPlaceWithoutBaseTravel(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "PausedMineLocalSupplyGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        BlockPos origin = bot.getBlockPos().toImmutable();

        ItemStack nearlyBroken = new ItemStack(Items.WOODEN_PICKAXE);
        nearlyBroken.setDamage(nearlyBroken.getMaxDamage() - 2);
        InventoryAction.giveItem(bot, nearlyBroken);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        require(context, bot.getMainHandStack().isOf(Items.WOODEN_PICKAXE)
                        && rawDurability(bot.getMainHandStack()) == 2,
                "fixture did not hold the nearly-broken wooden pick");

        DigDownTask owner = new DigDownTask(Blocks.STONE, 3);
        TaskManager.INSTANCE.assign(bot, owner,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_paused_mining_local_supply"));
        TaskManager.INSTANCE.pauseFor(bot, "gametest_safety_displacement_complete");
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty()
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == owner
                        && owner.state() == TaskState.PAUSED,
                "fixture did not preserve the paused DigDown owner");

        // A remembered remote base makes an ordinary ResupplyTask eligible to travel. The paused
        // owner branch must ignore it and use only the carried crafting inputs at this exact pose.
        io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.of(bot.getUuid())
                .markPlace("base", bot.getServerWorld(), origin.add(4, 0, 4));
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof ResupplyTask,
                "paused mining owner did not trigger tool service: "
                        + (active == null ? "idle" : active.name()));
        ResupplyTask resupply = (ResupplyTask) active;
        require(context, resupply.localOnly(),
                "paused mining owner received a travelling ResupplyTask");
        AtomicBoolean observedLocalOnly = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            if (resupply.describe().contains("note=local_only")) {
                observedLocalOnly.set(true);
            }
            require(context, bot.getBlockPos().equals(origin),
                    "paused-owner resupply moved toward the remembered base: "
                            + bot.getBlockPos().toShortString());
            require(context, bot.getActionPack().isPathExecutorIdle(),
                    "paused-owner resupply started a base path");
            if (resupply.state() == TaskState.FAILED
                    || resupply.state() == TaskState.CANCELLED) {
                context.throwGameTestException("local-only resupply ended as "
                        + resupply.state() + ":" + resupply.failureReason());
            }
            if (resupply.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, observedLocalOnly.get(),
                    "paused-owner resupply never entered its local-only boundary");
            require(context, bot.getMainHandStack().isOf(Items.WOODEN_PICKAXE)
                            && rawDurability(bot.getMainHandStack())
                            == bot.getMainHandStack().getMaxDamage(),
                    "local-only resupply did not craft and equip a fresh wooden pick");
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == owner
                            && !TaskManager.INSTANCE.hasPaused(bot)
                            && owner.state() == TaskState.RUNNING,
                    "local tool service did not resume the same DigDown instance");
            io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.remove(bot.getUuid());
            despawnAndComplete(context, bot);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "dangerWatcherMiningToolOwnership", tickLimit = 40)
    public void pausedMiningOwnerDoesNotTravelForDamagedCombatWeapon(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "PausedMineWeaponBoundaryGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        BlockPos origin = bot.getBlockPos().toImmutable();

        ItemStack damagedSword = new ItemStack(Items.WOODEN_SWORD);
        damagedSword.setDamage(damagedSword.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, damagedSword);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        require(context, bot.getMainHandStack().isOf(Items.WOODEN_SWORD)
                        && rawDurability(bot.getMainHandStack()) == 1,
                "fixture did not retain the damaged combat weapon in hand");

        DigDownTask owner = new DigDownTask(Blocks.STONE, 3);
        TaskManager.INSTANCE.assign(bot, owner,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_paused_mining_weapon_boundary"));
        TaskManager.INSTANCE.pauseFor(bot, "gametest_combat_displacement_complete");
        io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.of(bot.getUuid())
                .markPlace("base", bot.getServerWorld(), origin.add(4, 0, 4));

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, !(active instanceof ResupplyTask),
                "damaged combat weapon started base-travelling resupply over a paused miner");
        require(context, active == owner,
                "paused mining owner was not resumed after combat weapon service was suppressed: "
                        + (active == null ? "idle" : active.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                "paused mining owner remained stranded behind combat weapon service");
        require(context, bot.getBlockPos().equals(origin)
                        && bot.getActionPack().isPathExecutorIdle(),
                "combat weapon boundary moved toward the remembered base");

        // DangerWatcher scans continuously. Once the paused owner is active, the next scan must
        // still recognize its transaction ownership instead of treating the damaged sword as an
        // ordinary background resupply request.
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active == owner
                        && !TaskManager.INSTANCE.hasPaused(bot)
                        && bot.getActionPack().isPathExecutorIdle(),
                "second scan started combat-weapon resupply over the active mining owner");
        io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.remove(bot.getUuid());
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "dangerWatcherCombatEntomb", tickLimit = 40)
    public void healthyMeleeCombatIsNotPreemptedByUndergroundEntomb(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatEntombGT", 2);
        BlockPos origin = bot.getBlockPos().toImmutable();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                // Keep a conventional two-block-high chamber. The previous y+3 roof depended on
                // a heightmap update outside this empty template and intermittently read as open
                // sky when the large default batch prepared neighbouring fixtures in parallel.
                context.getWorld().setBlockState(origin.add(dx, 2, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 16));
        context.runAtTick(1, () -> {
            require(context, !context.getWorld().isSkyVisible(origin),
                    "combat-entomb fixture was not underground");
            ZombieEntity zombie = EntityType.ZOMBIE.create(context.getWorld(), SpawnReason.COMMAND);
            if (zombie == null) {
                despawnAndComplete(context, bot);
                context.throwGameTestException("failed to create close-combat zombie fixture");
                return;
            }
            BlockPos hostileFeet = origin.east();
            zombie.setPersistent();
            zombie.refreshPositionAndAngles(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                    hostileFeet.getZ() + 0.5D, 0.0F, 0.0F);
            context.getWorld().spawnEntity(zombie);

            CombatTask combat = CombatTask.defensive(zombie, 6.0F, origin);
            TaskManager.INSTANCE.assign(bot, combat,
                    TaskOrigin.safety("gametest_close_combat_entomb"));
            combat.tick(bot);
            bot.setHealth(17.5F);
            bot.hurtTime = 5;

            DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            require(context, active == combat,
                    "healthy defensive combat was replaced with "
                            + (active == null ? "idle" : active.name()));
            require(context, !TaskManager.INSTANCE.hasPaused(bot),
                    "healthy defensive combat was pushed behind an emergency shelter");
            require(context, combat.state() == TaskState.RUNNING,
                    "healthy defensive combat became terminal: "
                            + combat.state() + ":" + combat.failureReason());

            zombie.discard();
            despawnAndComplete(context, bot);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 20)
    public void equalDamageWeaponSelectionPrefersRemainingDurability(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatDurabilityGT", 2);
        ItemStack nearlyBroken = new ItemStack(Items.WOODEN_SWORD);
        nearlyBroken.setDamage(nearlyBroken.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, nearlyBroken);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        require(context, bot.getMainHandStack().isOf(Items.WOODEN_SWORD)
                        && rawDurability(bot.getMainHandStack()) == 1,
                "fixture did not initially hold the nearly-broken equal-damage weapon");

        CombatCore.equipMelee(bot);

        require(context, bot.getMainHandStack().isOf(Items.WOODEN_SWORD)
                        && rawDurability(bot.getMainHandStack())
                        == bot.getMainHandStack().getMaxDamage(),
                "equal-damage selection retained the lower-durability weapon");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 20)
    public void equalDamageWeaponSelectionPrefersSwordBeforeDurability(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatSwordPriorityGT", 2);
        ItemStack twoUseSword = new ItemStack(Items.STONE_SWORD);
        twoUseSword.setDamage(twoUseSword.getMaxDamage() - 2);
        ItemStack freshPickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        require(context,
                Double.compare(EquipAction.attackDamage(twoUseSword),
                        EquipAction.attackDamage(freshPickaxe)) == 0,
                "fixture weapons do not have equal attack damage");
        InventoryAction.giveItem(bot, twoUseSword);
        InventoryAction.giveItem(bot, freshPickaxe);

        CombatCore.equipMelee(bot);

        require(context, bot.getMainHandStack().isOf(Items.STONE_SWORD)
                        && rawDurability(bot.getMainHandStack()) == 2,
                "equal-damage fresh pickaxe displaced the admitted two-use sword");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 20)
    public void armorEquipRemainsIndependentFromMeleeWeaponFiltering(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "ArmorFilterIndependenceGT", 2);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));

        int equipped = EquipAction.equipBestArmor(bot);

        require(context, equipped == 1
                        && bot.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.IRON_CHESTPLATE),
                "melee weapon filtering suppressed ordinary armor equip");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 20)
    public void axeRemainsQualifiedWhilePickaxeCannotDisplaceIt(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "AxeWeaponQualificationGT", 2);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_AXE));

        EquipAction.equipBestWeapon(bot);

        require(context, bot.getMainHandStack().isOf(Items.STONE_AXE),
                "qualified axe was displaced by a non-weapon pickaxe");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 40)
    public void pickaxeOnlyInventoryCannotAuthorizeCombat(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "PickaxeOnlyNoCombatGT", 2);
        BlockPos origin = bot.getBlockPos().toImmutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        require(context, EquipAction.bestWeaponSlot(bot).isEmpty(),
                "stone pickaxe was classified as a qualified melee weapon");

        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_pickaxe_only_no_combat"));
        ZombieEntity zombie = EntityType.ZOMBIE.create(context.getWorld(), SpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create pickaxe-only zombie fixture");
            return;
        }
        BlockPos hostileFeet = origin.east(2);
        zombie.setPersistent();
        zombie.setAiDisabled(true);
        zombie.refreshPositionAndAngles(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(zombie);
        require(context, CombatCore.hasLineOfSight(bot, zombie),
                "pickaxe-only hostile fixture lacked factual line of sight");

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof EvadeTask,
                "pickaxe-only inventory entered "
                        + (active == null ? "idle" : active.name()) + " instead of Evade");
        require(context, work.state() == TaskState.PAUSED,
                "pickaxe-only Evade did not preserve interrupted work");
        zombie.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 40)
    public void finalUseSwordCannotAuthorizeCombat(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "FinalUseSwordNoCombatGT", 2);
        BlockPos origin = bot.getBlockPos().toImmutable();
        ItemStack finalUseSword = new ItemStack(Items.STONE_SWORD);
        finalUseSword.setDamage(finalUseSword.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, finalUseSword);
        require(context, EquipAction.bestWeaponSlot(bot).isEmpty(),
                "raw-1 sword was admitted as a defensive melee weapon");

        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_final_use_sword_no_combat"));
        ZombieEntity zombie = EntityType.ZOMBIE.create(context.getWorld(), SpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create final-use sword zombie fixture");
            return;
        }
        BlockPos hostileFeet = origin.east(2);
        zombie.setPersistent();
        zombie.setAiDisabled(true);
        zombie.refreshPositionAndAngles(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(zombie);
        require(context, CombatCore.hasLineOfSight(bot, zombie),
                "final-use sword hostile fixture lacked factual line of sight");

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof EvadeTask,
                "raw-1 sword entered "
                        + (active == null ? "idle" : active.name()) + " instead of Evade");
        require(context, work.state() == TaskState.PAUSED,
                "raw-1 sword Evade did not preserve interrupted work");
        zombie.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 20)
    public void finalUseAxeIsNotAQualifiedMeleeWeapon(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "FinalUseAxeNoCombatGT", 2);
        ItemStack finalUseAxe = new ItemStack(Items.STONE_AXE);
        finalUseAxe.setDamage(finalUseAxe.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, finalUseAxe);

        require(context, EquipAction.bestWeaponSlot(bot).isEmpty(),
                "raw-1 axe was admitted as a defensive melee weapon");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 30)
    public void rangedLineOfSightBlocksCombatHealBeyondMeleeBoundary(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatRangedHealGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.getBlockPos().toImmutable();
        bot.setHealth(8.0F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        // Stay inside the owned 8x8 footprint. This diagonal/elevated pose is 6.93 blocks away,
        // exercising the reported seven-block boundary without leaking into a neighbour fixture.
        BlockPos skeletonFeet = origin.add(4, 4, 4);
        context.getWorld().setBlockState(
                skeletonFeet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        SkeletonEntity skeleton = EntityType.SKELETON.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (skeleton == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create ranged-heal skeleton fixture");
            return;
        }
        skeleton.setPersistent();
        skeleton.setAiDisabled(true);
        skeleton.refreshPositionAndAngles(
                skeletonFeet.getX() + 0.5D, skeletonFeet.getY(),
                skeletonFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(skeleton);
        double skeletonDistance = bot.getPos().distanceTo(skeleton.getPos());
        require(context, skeletonDistance > 6.8D && skeletonDistance < 7.2D
                        && CombatCore.hasLineOfSight(bot, skeleton),
                "ranged-heal fixture was not a seven-block LOS threat: distance="
                        + skeletonDistance + " los=" + CombatCore.hasLineOfSight(bot, skeleton));

        CombatTask combat = CombatTask.defensive(skeleton, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_ranged_los_blocks_heal"));
        combat.tick(bot);
        combat.tick(bot);

        require(context, combat.state() == TaskState.RUNNING
                        && combat.describe().contains("phase=RETREAT"),
                "ranged LOS entered HEAL beyond the melee boundary: " + combat.describe());
        require(context, !bot.isUsingItem()
                        && InventoryAction.countItem(bot, Items.COOKED_BEEF) == 2,
                "ranged LOS allowed food use before reaching safety");
        require(context, deathCount(bot) == deathBaseline,
                "ranged-heal transition changed the bot death counter");

        BlockPos occluder = origin.add(2, 3, 2);
        context.getWorld().setBlockState(
                occluder, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        require(context, !CombatCore.hasLineOfSight(bot, skeleton),
                "ranged-heal occluder did not physically break LOS");
        combat.tick(bot);
        combat.tick(bot);

        require(context, combat.describe().contains("phase=HEAL") && bot.isUsingItem(),
                "breaking ranged LOS did not release the combat heal boundary: "
                        + combat.describe());
        skeleton.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 80)
    public void nightCreeperWithShelterMaterialsChoosesDedicatedDefense(TestContext context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "NightCreeperDefenseGT", 36);
        context.getWorld().setTimeOfDay(18000L);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 32));
        int blocksBefore = InventoryAction.countItem(bot, Items.COBBLESTONE);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_night_creeper_work"));
        CreeperEntity creeper = spawnDisabledCreeper(
                context, bot.getBlockPos().east(8), "night Creeper routing fixture");

        require(context, ObservableWorldQuery.canObserveEntity(bot, creeper)
                        && CombatCore.hasLineOfSight(bot, creeper),
                "night Creeper was not factually observable");
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "night Creeper with shelter material selected "
                        + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "night Creeper did not preserve exactly one mission frame");
        require(context, !bot.getActionPack().isPathExecutorIdle(),
                "night Creeper defense did not admit a real surface path");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksBefore,
                "night Creeper routing consumed shelter material");
        creeper.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 80)
    public void lowHealthCreeperCannotEnterEmergencyEntomb(TestContext context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "LowCreeperDefenseGT", 52);
        context.getWorld().setTimeOfDay(18000L);
        bot.setHealth(4.7F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 32));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));
        int blocksBefore = InventoryAction.countItem(bot, Items.COBBLESTONE);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_low_creeper_work"));
        CreeperEntity creeper = spawnDisabledCreeper(
                context, bot.getBlockPos().east(6), "low-health Creeper routing fixture");

        require(context, ObservableWorldQuery.canObserveEntity(bot, creeper)
                        && CombatCore.hasLineOfSight(bot, creeper),
                "low-health Creeper was not factually observable");
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "low-health Creeper entered " + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "low-health Creeper did not preserve exactly one mission frame");
        require(context, !bot.getActionPack().isPathExecutorIdle(),
                "low-health Creeper defense did not admit a real surface path");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksBefore,
                "low-health Creeper routing consumed shelter material");
        creeper.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 80)
    public void observableCreeperAtFifteenBlocksTriggersDedicatedDefense(
            TestContext context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(
                context, "CreeperFifteenDefenseGT", 60);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_creeper_fifteen_work"));
        CreeperEntity creeper = spawnDisabledCreeper(
                context, bot.getBlockPos().east(15), "fifteen-block Creeper fixture");

        require(context, ObservableWorldQuery.canObserveEntity(bot, creeper)
                        && CombatCore.hasLineOfSight(bot, creeper),
                "fifteen-block Creeper was not factually observable");
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "fifteen-block Creeper left mission active as "
                        + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "fifteen-block Creeper did not preserve one mission frame");
        require(context, !bot.getActionPack().isPathExecutorIdle(),
                "fifteen-block Creeper did not admit a real escape path");
        creeper.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 160)
    public void completedCreeperDefenseReacquiresWithoutMissionStackGap(
            TestContext context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "CreeperReacquireGT", 108);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_creeper_reacquire_work"));
        CreeperEntity first = spawnDisabledCreeper(
                context, bot.getBlockPos().east(8), "initial Creeper cooldown fixture");
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        Task firstSafety = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, firstSafety instanceof CreeperDefenseTask,
                "initial Creeper did not schedule dedicated defense");
        BlockPos firstGoal = bot.getActionPack().activePathGoal();
        require(context, firstGoal != null,
                "initial Creeper defense had no admitted goal");

        first.discard();
        bot.teleport(context.getWorld(),
                firstGoal.getX() + 0.5D, firstGoal.getY(), firstGoal.getZ() + 0.5D,
                Set.of(), bot.getYaw(), bot.getPitch(), true);
        bot.setVelocity(Vec3d.ZERO);
        for (int tick = 0; tick < 99; tick++) {
            firstSafety.tick(bot);
        }
        require(context, firstSafety.state() == TaskState.RUNNING
                        && work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "Creeper defense released its mission before the 100-tick LOS grace");
        firstSafety.tick(bot);
        require(context, firstSafety.state() == TaskState.COMPLETED,
                "settled Creeper defense did not complete after factual grace: "
                        + firstSafety.state() + ":" + firstSafety.failureReason());
        TaskManager.INSTANCE.tickAll(context.getWorld().getServer());
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty()
                        && work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "completed Creeper defense did not leave exactly one paused mission frame");

        CreeperEntity reappeared = spawnDisabledCreeper(
                context, bot.getBlockPos().east(15), "reappearing Creeper cooldown fixture");
        require(context, ObservableWorldQuery.canObserveEntity(bot, reappeared),
                "reappearing fifteen-block Creeper was not observable");
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot)
                        .orElse(null) instanceof CreeperDefenseTask,
                "completed defense retained a gap for the reappearing Creeper");
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "Creeper reacquisition resumed or duplicated the mission frame");
        reappeared.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 80)
    public void closerZombieCannotMaskObservableCreeper(TestContext context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "MixedCreeperDefenseGT", 76);
        context.getWorld().setTimeOfDay(18000L);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 32));
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_mixed_creeper_work"));
        HuskEntity husk = EntityType.HUSK.create(context.getWorld(), SpawnReason.COMMAND);
        if (husk == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create mixed-pressure Husk fixture");
            return;
        }
        husk.setPersistent();
        husk.setAiDisabled(true);
        BlockPos huskFeet = bot.getBlockPos().east(3);
        husk.refreshPositionAndAngles(
                huskFeet.getX() + 0.5D, huskFeet.getY(), huskFeet.getZ() + 0.5D,
                90.0F, 0.0F);
        context.getWorld().spawnEntity(husk);
        CreeperEntity creeper = spawnDisabledCreeper(
                context, bot.getBlockPos().east(15), "mixed-pressure Creeper fixture");

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "closer ordinary hostile masked Creeper with "
                        + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "mixed Creeper pressure did not preserve one mission frame");
        require(context, !bot.getActionPack().isPathExecutorIdle(),
                "mixed Creeper pressure did not admit a real escape path");
        husk.discard();
        creeper.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 80)
    public void evadeExaminesFifthDirectionWithinBoundedAdmission(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "EvadeFifthDirectionGT", 92);
        BlockPos origin = bot.getBlockPos().toImmutable();
        var world = context.getWorld();
        // Threat is east, so -90 degrees is south and is generated fifth. Keep every earlier
        // twelve-block endpoint unsupported while exposing one ordinary south corridor.
        for (int dz = 5; dz <= 16; dz++) {
            BlockPos cell = origin.south(dz);
            world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        CreeperEntity creeper = spawnDisabledCreeper(
                context, origin.east(8), "fifth-direction Creeper fixture");
        EvadeTask evade = new EvadeTask(new Threat(
                Threat.Type.HOSTILE, Threat.Severity.HIGH, creeper, creeper.getBlockPos()));
        evade.start(bot);

        BlockPos admitted = bot.getActionPack().activePathGoal();
        require(context, evade.state() == TaskState.RUNNING && admitted != null,
                "fifth escape direction was starved by earlier rejected endpoints: "
                        + evade.state() + ":" + evade.failureReason());
        require(context, admitted.getZ() > origin.getZ() + 4
                        && Math.abs(admitted.getX() - origin.getX()) <= 4,
                "Evade admitted the wrong directional endpoint: " + admitted.toShortString());
        creeper.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 500)
    public void observedCreeperDefenseExtendsBeyondFirstWaypoint(TestContext context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(
                context, "CreeperExtendDefenseGT", 68);
        BlockPos origin = bot.getBlockPos().toImmutable();
        int deathBaseline = deathCount(bot);
        CreeperEntity creeper = spawnDisabledCreeper(
                context, origin.east(8), "moving Creeper escape fixture");
        CreeperDefenseTask defense =
                new CreeperDefenseTask(creeper, creeper.getBlockPos());
        TaskManager.INSTANCE.assign(bot, defense,
                TaskOrigin.safety("gametest_creeper_defense_extension"));

        context.runAtEveryTick(() -> {
            BlockPos trailing = bot.getBlockPos().east(15);
            creeper.refreshPositionAndAngles(
                    trailing.getX() + 0.5D, trailing.getY(), trailing.getZ() + 0.5D,
                    90.0F, 0.0F);
            if (defense.state() == TaskState.FAILED
                    || defense.state() == TaskState.CANCELLED) {
                creeper.discard();
                despawnAndComplete(context, bot);
                context.throwGameTestException("extended Creeper defense ended as "
                        + defense.state() + ":" + defense.failureReason());
                return;
            }
            // The first projection is twelve blocks west and the task can settle within 2.5 blocks
            // of it. Reaching fifteen blocks proves a second path leg was admitted.
            if (bot.getX() <= origin.getX() - 15.0D) {
                require(context, defense.state() == TaskState.RUNNING,
                        "Creeper defense completed at its first moving-threat waypoint");
                require(context, ObservableWorldQuery.canObserveEntity(bot, creeper),
                        "moving Creeper left factual perception before extension proof");
                require(context, deathCount(bot) == deathBaseline,
                        "moving Creeper defense changed the bot death counter");
                creeper.discard();
                despawnAndComplete(context, bot);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 340)
    public void pointBlankLiveChargedCreeperDuringStalledEvadeSurvivesAndResumesMission(
            TestContext context) {
        AIPlayerEntity bot = spawnOnReactiveEscapeArena(
                context, "LiveCreeperStalledEvadeGT", 196);
        BlockPos origin = bot.getBlockPos().toImmutable();
        bot.setHealth(20.0F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 16));
        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);

        HoldingTask mission = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_live_creeper_mission"));
        Vec3d stallAnchor = Vec3d.ofBottomCenter(origin);
        Vec3d approachAnchor = Vec3d.ofBottomCenter(origin.east(8));
        CreeperEntity[] creeperRef = {null};
        AtomicBoolean pointBlankPressure = new AtomicBoolean();
        AtomicBoolean explosionObserved = new AtomicBoolean();
        int[] armedTicks = {0};
        int[] maxPausedDepth = {0};

        // Commit and physically stall the same initial westbound SAFETY path on both revisions
        // before applying point-blank pressure. Damage waits until join invulnerability expires.
        context.runAtTick(70, () -> {
            CreeperEntity creeper = spawnLiveTargetingCreeper(
                    context, origin.east(8), bot, "live Creeper fuse fixture");
            creeperRef[0] = creeper;
            require(context, ObservableWorldQuery.canObserveEntity(bot, creeper)
                            && CombatCore.hasLineOfSight(bot, creeper),
                    "live Creeper was not factually observable before safety routing");

            DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
            Task safety = TaskManager.INSTANCE.getActive(bot).orElse(null);
            BlockPos committedGoal = bot.getActionPack().activePathGoal();
            require(context, safety != null && safety != mission
                            && TaskManager.INSTANCE.activeOrigin(bot)
                            .map(TaskOrigin::safety).orElse(false),
                    "live Creeper did not assign a dedicated SAFETY owner");
            require(context, mission.state() == TaskState.PAUSED
                            && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                            && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                    "live Creeper did not preserve exactly one mission frame");
            require(context, committedGoal != null
                            && committedGoal.getX() < origin.getX() - 4,
                    "live Creeper safety did not commit its initial westbound path");
        });

        context.runAtTick(80, () -> {
            CreeperEntity creeper = creeperRef[0];
            require(context, creeper != null && creeper.isAlive(),
                    "live Creeper disappeared before point-blank pressure");
            // Stronger-than-evidence regression: keep vanilla fuse/explosion behavior, but use
            // vanilla charged state so an unshielded full-health baseline is strictly fatal.
            BlockPos fuseFeet = origin.east();
            creeper.refreshPositionAndAngles(
                    fuseFeet.getX() + 0.5D, fuseFeet.getY(), fuseFeet.getZ() + 0.5D,
                    90.0F, 0.0F);
            creeper.setTarget(bot);
            chargeCreeperWithoutLightningDamage(context, creeper);
            creeper.ignite();
            require(context, !creeper.isAiDisabled()
                            && creeper.getTarget() == bot
                            && creeper.isCharged()
                            && creeper.isIgnited()
                            && ObservableWorldQuery.canObserveEntity(bot, creeper)
                            && CombatCore.hasLineOfSight(bot, creeper),
                    "point-blank charged Creeper was not a live targeting pressure source");
            pointBlankPressure.set(true);
        });

        context.runAtEveryTick(() -> {
            CreeperEntity creeper = creeperRef[0];
            if (creeper == null) {
                if (bot.isAlive()) {
                    bot.teleport(context.getWorld(),
                            stallAnchor.x, stallAnchor.y, stallAnchor.z,
                            Set.of(), bot.getYaw(), bot.getPitch(), true);
                    bot.setVelocity(Vec3d.ZERO);
                }
                return;
            }
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (creeper.isAlive() && !pointBlankPressure.get()) {
                creeper.refreshPositionAndAngles(
                        approachAnchor.x, approachAnchor.y, approachAnchor.z,
                        90.0F, 0.0F);
                creeper.setVelocity(Vec3d.ZERO);
                creeper.setTarget(bot);
            }
            if (creeper.isAlive() && bot.isAlive()
                    && (!pointBlankPressure.get() || active instanceof EvadeTask)) {
                bot.teleport(context.getWorld(),
                        stallAnchor.x, stallAnchor.y, stallAnchor.z,
                        Set.of(), bot.getYaw(), bot.getPitch(), true);
                bot.setVelocity(Vec3d.ZERO);
            }
            int depth = TaskManager.INSTANCE.pausedDepth(bot);
            maxPausedDepth[0] = Math.max(maxPausedDepth[0], depth);
            require(context, bot.isAlive() && deathCount(bot) == deathBaseline,
                    "point-blank live charged Creeper killed the bot after its Evade path stalled"
                            + " at " + bot.getBlockPos().toShortString()
                            + " deaths=" + deathCount(bot));
            require(context, depth <= 1,
                    "live Creeper recovery grew the paused mission stack to " + depth);
            if (depth == 1) {
                require(context, TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                                && mission.state() == TaskState.PAUSED,
                        "live Creeper recovery replaced or resumed the mission too early");
            }

            if (active != null && active != mission) {
                require(context, TaskManager.INSTANCE.activeOrigin(bot)
                                .map(TaskOrigin::safety).orElse(false),
                        "live Creeper recovery transferred control to a non-safety task");
            }
            if (creeper.isAlive()) {
                creeper.setTarget(bot);
                if (creeper.isIgnited() || creeper.getFuseSpeed() > 0) {
                    armedTicks[0]++;
                }
            } else if (explosionObserved.compareAndSet(false, true)) {
                // The movement anchor ends with the factual blast, allowing the same paused
                // mission to resume after its SAFETY owner repays the pressure.
            }

            if (active == mission) {
                require(context, explosionObserved.get() && armedTicks[0] >= 20,
                        "mission resumed without observing a real Creeper fuse and explosion");
                require(context, mission.state() == TaskState.RUNNING
                                && depth == 0
                                && maxPausedDepth[0] == 1,
                        "same mission did not resume with one fully repaid safety frame");
                despawnAndComplete(context, bot);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 80)
    public void creeperIsNeverHitFromStrikeOrSecondaryRetreat(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatCreeperRetreatGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.getBlockPos().toImmutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        CreeperEntity creeper = EntityType.CREEPER.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (creeper == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create Creeper combat fixture");
            return;
        }
        creeper.setPersistent();
        creeper.setAiDisabled(true);
        BlockPos creeperFeet = origin.east();
        creeper.refreshPositionAndAngles(
                creeperFeet.getX() + 0.5D, creeperFeet.getY(),
                creeperFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(creeper);
        float creeperHealth = creeper.getHealth();

        // Exercise the normal STRIKE entry independently of DangerWatcher routing.
        CombatTask strikeProbe = CombatTask.defensive(creeper, 6.0F, origin);
        strikeProbe.start(bot);
        strikeProbe.tick(bot);
        strikeProbe.tick(bot);
        strikeProbe.tick(bot);
        require(context, creeper.getHealth() == creeperHealth
                        && strikeProbe.describe().contains("phase=RETREAT"),
                "STRIKE dealt melee damage to a Creeper: " + strikeProbe.describe());
        bot.getActionPack().stopAll();

        for (BlockPos wall : new BlockPos[]{origin.west(), origin.north(), origin.south()}) {
            context.getWorld().setBlockState(
                    wall, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(
                    wall.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                context.getWorld().setBlockState(
                        origin.add(dx, 2, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        bot.setHealth(8.0F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        BlockPos primaryFeet = origin.add(4, 4, 0);
        context.getWorld().setBlockState(
                primaryFeet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        // A regular Zombie can burn under the shared GameTest world's daytime and make this
        // no-melee assertion fail without any bot attack. Husk preserves the same close hostile
        // pressure contract while keeping health changes attributable to CombatTask alone.
        HuskEntity primary = EntityType.HUSK.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (primary == null) {
            creeper.discard();
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create Creeper secondary primary fixture");
            return;
        }
        primary.setPersistent();
        primary.setAiDisabled(true);
        primary.refreshPositionAndAngles(
                primaryFeet.getX() + 0.5D, primaryFeet.getY(),
                primaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(primary);
        float primaryHealth = primary.getHealth();

        CombatTask combat = CombatTask.defensive(primary, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_secondary_creeper_no_counterattack"));
        AtomicBoolean dedicatedOwnerObserved = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            require(context, creeper.isAlive() && creeper.getHealth() == creeperHealth,
                    "combat or dedicated defense attacked the secondary Creeper");
            require(context, primary.isAlive() && primary.getHealth() == primaryHealth,
                    "secondary Creeper pressure redirected damage to the primary");
            require(context, Double.compare(combat.progress(), 0.0D) == 0,
                    "secondary Creeper pressure advanced primary kill credit");
            require(context, !bot.isUsingItem(),
                    "combat healed before reaching the Creeper eight-block/LOS boundary");
            require(context, bot.isAlive() && deathCount(bot) == deathBaseline,
                    "secondary Creeper regression changed the bot death counter");
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof CreeperDefenseTask) {
                dedicatedOwnerObserved.set(true);
                require(context, combat.state() == TaskState.FAILED
                                && "aborted".equals(combat.failureReason())
                                && TaskManager.INSTANCE.activeOrigin(bot)
                                .map(TaskOrigin::safety).orElse(false)
                                && TaskManager.INSTANCE.pausedDepth(bot) == 0,
                        "SAFETY Combat was not replaced in place by dedicated defense");
            } else {
                require(context, active == combat && !dedicatedOwnerObserved.get(),
                        "secondary Creeper transferred to unexpected owner "
                                + (active == null ? "idle" : active.name()));
            }
            if (context.getTick() >= 40) {
                require(context, dedicatedOwnerObserved.get(),
                        "secondary Creeper never established dedicated defense");
                primary.discard();
                creeper.discard();
                despawnAndComplete(context, bot);
            } else if (!dedicatedOwnerObserved.get()
                    && (combat.state() == TaskState.FAILED
                    || combat.state() == TaskState.CANCELLED)) {
                context.throwGameTestException("secondary-Creeper combat ended as "
                        + combat.state() + ":" + combat.failureReason());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 20)
    public void primaryDeathDuringHealIsCreditedExactlyOnce(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatHealPrimaryDeathGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.getBlockPos().toImmutable();
        context.getWorld().setTimeOfDay(18000L);
        bot.setHealth(8.0F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        BlockPos primaryFeet = origin.add(4, 4, 0);
        context.getWorld().setBlockState(primaryFeet.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        ZombieEntity primary = EntityType.ZOMBIE.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (primary == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create heal-primary fixture");
            return;
        }
        primary.setPersistent();
        primary.setAiDisabled(true);
        primary.refreshPositionAndAngles(primaryFeet.getX() + 0.5D, primaryFeet.getY(),
                primaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(primary);

        CombatTask combat = CombatTask.defensive(primary, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_primary_death_during_heal"));
        combat.tick(bot);
        require(context, combat.describe().contains("phase=HEAL"),
                "safe-distance primary did not put low-health combat into HEAL: "
                        + combat.describe());

        primary.discard();
        combat.tick(bot);

        require(context, combat.state() == TaskState.COMPLETED,
                "primary death during HEAL ended as " + combat.state()
                        + ":" + combat.failureReason());
        require(context, Double.compare(combat.progress(), 1.0D) == 0,
                "primary death during HEAL was not credited exactly once: "
                        + combat.describe());
        require(context, deathCount(bot) == deathBaseline,
                "HEAL primary-death settlement changed the bot death counter");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 100)
    public void nearestSecondaryPressureBlocksFoodWithoutTakingPrimaryCredit(
            TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatSecondaryPressureGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.getBlockPos().toImmutable();
        context.getWorld().setTimeOfDay(18000L);
        for (BlockPos wall : new BlockPos[]{origin.west(), origin.north(), origin.south()}) {
            context.getWorld().setBlockState(
                    wall, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(
                    wall.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        bot.setHealth(8.0F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        BlockPos primaryFeet = origin.add(4, 4, 0);
        context.getWorld().setBlockState(primaryFeet.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        // GameTests share one world and other batches may change time after this test sets night.
        // Use non-burning Zombie variants so every health delta remains attributable to combat.
        HuskEntity primary = EntityType.HUSK.create(
                context.getWorld(), SpawnReason.COMMAND);
        HuskEntity secondary = EntityType.HUSK.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (primary == null || secondary == null) {
            if (primary != null) {
                primary.discard();
            }
            if (secondary != null) {
                secondary.discard();
            }
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create secondary-pressure fixtures");
            return;
        }
        primary.setPersistent();
        primary.setAiDisabled(true);
        primary.refreshPositionAndAngles(primaryFeet.getX() + 0.5D, primaryFeet.getY(),
                primaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        secondary.setPersistent();
        secondary.setAiDisabled(true);
        BlockPos secondaryFeet = origin.east();
        secondary.refreshPositionAndAngles(secondaryFeet.getX() + 0.5D, secondaryFeet.getY(),
                secondaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(primary);
        context.getWorld().spawnEntity(secondary);
        float primaryHealth = primary.getHealth();
        float secondaryHealth = secondary.getHealth();

        CombatTask combat = CombatTask.defensive(primary, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_secondary_pressure"));
        context.runAtEveryTick(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deathBaseline,
                    "secondary-pressure combat violated the zero-death boundary");
            if (secondary.isAlive() && bot.distanceTo(secondary) < 5.0D) {
                require(context, !bot.isUsingItem(),
                        "combat ate while the secondary hostile remained inside the heal boundary");
            }
            require(context, primary.getHealth() == primaryHealth,
                    "retreat pressure redirected primary kill ownership");
            require(context, Double.compare(combat.progress(), 0.0D) == 0,
                    "secondary damage advanced the primary kill quota");
            if (secondary.getHealth() < secondaryHealth) {
                primary.discard();
                secondary.discard();
                despawnAndComplete(context, bot);
            } else if (combat.state() == TaskState.FAILED
                    || combat.state() == TaskState.CANCELLED) {
                context.throwGameTestException("secondary-pressure combat ended as "
                        + combat.state() + ":" + combat.failureReason());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 40)
    public void rangedSecondaryAtFourteenBlocksBlocksPrimarySettlementUntilLosBreaks(
            TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatRangedSecondaryGT", 2);
        BlockPos origin = bot.getBlockPos().toImmutable();
        bot.setHealth(8.0F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));
        for (int dx = 1; dx <= 14; dx++) {
            BlockPos corridor = origin.east(dx);
            context.getWorld().setBlockState(
                    corridor.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(
                    corridor, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(
                    corridor.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (BlockPos wall : new BlockPos[]{origin.west(), origin.north(), origin.south()}) {
            context.getWorld().setBlockState(
                    wall, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(
                    wall.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        HuskEntity primary = EntityType.HUSK.create(
                context.getWorld(), SpawnReason.COMMAND);
        SkeletonEntity secondary = EntityType.SKELETON.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (primary == null || secondary == null) {
            if (primary != null) {
                primary.discard();
            }
            if (secondary != null) {
                secondary.discard();
            }
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create ranged-secondary fixtures");
            return;
        }
        BlockPos primaryFeet = origin.east();
        BlockPos secondaryFeet = origin.east(14);
        primary.setPersistent();
        primary.setAiDisabled(true);
        primary.refreshPositionAndAngles(primaryFeet.getX() + 0.5D, primaryFeet.getY(),
                primaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        secondary.setPersistent();
        secondary.setAiDisabled(true);
        secondary.refreshPositionAndAngles(secondaryFeet.getX() + 0.5D, secondaryFeet.getY(),
                secondaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(primary);
        context.getWorld().spawnEntity(secondary);
        require(context, CombatCore.hasLineOfSight(bot, secondary)
                        && bot.distanceTo(secondary) > 13.8D
                        && bot.distanceTo(secondary) < 14.2D,
                "ranged secondary was not an observable fourteen-block threat");

        CombatTask combat = CombatTask.defensive(primary, 10.0F, origin);
        combat.start(bot);
        combat.tick(bot);
        primary.discard();
        combat.tick(bot);

        require(context, combat.state() == TaskState.RUNNING
                        && combat.describe().contains("phase=RETREAT"),
                "ranged secondary did not retain retreat ownership: " + combat.describe());
        require(context, InventoryAction.countItem(bot, Items.COOKED_BEEF) == 2
                        && !bot.isUsingItem(),
                "combat ate while a fourteen-block ranged secondary retained LOS");

        for (int dz = -3; dz <= 3; dz++) {
            BlockPos occluder = origin.east(7).south(dz);
            context.getWorld().setBlockState(
                    occluder, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(
                    occluder.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        combat.tick(bot);
        require(context, combat.state() == TaskState.COMPLETED,
                "breaking ranged-secondary LOS did not release settlement: "
                        + combat.state() + ":" + combat.describe());

        secondary.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 100)
    public void combatReequipsBackupInTheSameAttackBoundary(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatBackupWeaponGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.getBlockPos().toImmutable();
        ItemStack twoUseStoneSword = new ItemStack(Items.STONE_SWORD);
        twoUseStoneSword.setDamage(twoUseStoneSword.getMaxDamage() - 2);
        InventoryAction.giveItem(bot, twoUseStoneSword);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        require(context, bot.getMainHandStack().isOf(Items.STONE_SWORD)
                        && rawDurability(bot.getMainHandStack()) == 2,
                "fixture did not hold the stronger two-use weapon");
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                context.getWorld().setBlockState(origin.add(dx, 2, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        ZombieEntity zombie = EntityType.ZOMBIE.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create backup-weapon zombie fixture");
            return;
        }
        zombie.setPersistent();
        zombie.setAiDisabled(true);
        BlockPos hostileFeet = origin.east();
        zombie.refreshPositionAndAngles(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(zombie);
        float initialHealth = zombie.getHealth();

        CombatTask combat = CombatTask.defensive(zombie, 6.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_combat_backup_weapon"));
        context.runAtEveryTick(() -> {
            require(context, bot.isAlive(), "bot died in the disabled-zombie weapon fixture");
            require(context, deathCount(bot) == deathBaseline,
                    "backup-weapon combat changed the bot death counter");
            ItemStack retiredStoneSword = bot.getInventory().main.stream()
                    .filter(stack -> stack.isOf(Items.STONE_SWORD))
                    .findFirst()
                    .orElse(ItemStack.EMPTY);
            if (!retiredStoneSword.isEmpty()
                    && rawDurability(retiredStoneSword) == 1) {
                require(context, zombie.getHealth() < initialHealth,
                        "two-use weapon lost durability before this combat damaged its target"
                                + " health=" + zombie.getHealth()
                                + " initial=" + initialHealth);
                ItemStack held = bot.getMainHandStack();
                require(context, held.isOf(Items.WOODEN_SWORD)
                                && rawDurability(held) > 1,
                        "newly ineligible weapon was not atomically replaced by its backup"
                                + " held=" + held.getItem()
                                + " raw=" + rawDurability(held)
                                + " selected=" + bot.getInventory().selectedSlot
                                + " wood_count="
                                + InventoryAction.countItem(bot, Items.WOODEN_SWORD));
                zombie.discard();
                despawnAndComplete(context, bot);
            } else if (combat.state() == TaskState.FAILED
                    || combat.state() == TaskState.CANCELLED) {
                context.throwGameTestException("backup-weapon combat ended as "
                        + combat.state() + ":" + combat.failureReason());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 100)
    public void contactHostileBlocksHealingAndForcesCounterattack(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatContactHealGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.getBlockPos().toImmutable();
        for (BlockPos wall : new BlockPos[]{origin.west(), origin.north(), origin.south()}) {
            context.getWorld().setBlockState(
                    wall, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(
                    wall.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                context.getWorld().setBlockState(origin.add(dx, 2, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        bot.setHealth(8.0F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        ZombieEntity zombie = EntityType.ZOMBIE.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create contact-heal zombie fixture");
            return;
        }
        zombie.setPersistent();
        zombie.setAiDisabled(true);
        BlockPos hostileFeet = origin.east();
        zombie.refreshPositionAndAngles(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(zombie);
        float initialHealth = zombie.getHealth();
        AtomicBoolean counterattacked = new AtomicBoolean();

        CombatTask combat = CombatTask.defensive(zombie, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_contact_hostile_blocks_heal"));
        combat.tick(bot);
        require(context, combat.describe().contains("phase=RETREAT"),
                "low-health acquire spent its first tick approaching the contact hostile: "
                        + combat.describe());
        BlockPos retreatGoal = bot.getActionPack().activePathGoal();
        require(context, retreatGoal != null
                        && retreatGoal.getSquaredDistance(hostileFeet)
                        > origin.getSquaredDistance(hostileFeet),
                "low-health acquire did not admit a goal away from the contact hostile: "
                        + (retreatGoal == null ? "no goal" : retreatGoal.toShortString()));
        context.runAtEveryTick(() -> {
            if (zombie.isAlive()) {
                require(context, !bot.isUsingItem(),
                        "combat began eating while a live hostile remained in contact range");
                require(context, bot.getMainHandStack().isOf(Items.WOODEN_SWORD),
                        "combat replaced its melee weapon with food at contact range");
            }
            if (zombie.getHealth() < initialHealth) {
                counterattacked.set(true);
            }
            require(context, bot.isAlive(), "bot died against the disabled contact hostile");
            require(context, deathCount(bot) == deathBaseline,
                    "contact-heal combat changed the bot death counter");
            if (!zombie.isAlive() || context.getTick() >= 60) {
                require(context, counterattacked.get(),
                        "blocked retreat never counterattacked the contact hostile");
                zombie.discard();
                despawnAndComplete(context, bot);
            } else if (combat.state() == TaskState.FAILED
                    || combat.state() == TaskState.CANCELLED) {
                context.throwGameTestException("contact-heal combat ended as "
                        + combat.state() + ":" + combat.failureReason());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "combatSurvivalRecovery", tickLimit = 30)
    public void leashExitCannotCompleteWhileAHostileRemainsInContact(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatLeashContactGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.getBlockPos().toImmutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        ZombieEntity zombie = EntityType.ZOMBIE.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create leash-contact zombie fixture");
            return;
        }
        zombie.setPersistent();
        zombie.setAiDisabled(true);
        BlockPos hostileFeet = origin.east();
        zombie.refreshPositionAndAngles(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(zombie);

        // The task's work-site anchor is deliberately outside its defensive leash while the live
        // hostile is still touching the bot. A leash check may end pursuit only after safety; it
        // must not complete here and expose the paused mining task to a free zombie hit.
        CombatTask combat = CombatTask.defensive(zombie, 6.0F, origin.west(9));
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_leash_contact_retreat"));
        combat.tick(bot);

        require(context, combat.state() == TaskState.RUNNING
                        && combat.describe().contains("phase=RETREAT"),
                "leash exit completed while a hostile remained in contact: "
                        + combat.state() + ":" + combat.describe());
        require(context, !bot.isUsingItem(),
                "leash-contact retreat began eating beside the hostile");
        require(context, deathCount(bot) == deathBaseline,
                "leash-contact retreat changed the bot death counter");
        zombie.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 160)
    public void lowHealthAloneDoesNotReplaceCurrentWorkWithEvade(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "LowHealthNoThreatGT", 2);
        bot.setHealth(4.7F);
        bot.getHungerManager().setFoodLevel(17);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_low_health_no_threat"));

        BlockPos origin = bot.getBlockPos().toImmutable();
        context.runAtEveryTick(() -> {
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            require(context, active == work,
                    "low HP without a reachable threat replaced work with "
                            + (active == null ? "idle" : active.name()));
            require(context, !TaskManager.INSTANCE.hasPaused(bot),
                    "low HP without a threat unnecessarily paused current work");
            require(context, bot.getBlockPos().getY() == origin.getY(),
                    "low HP without a threat changed vertical layer: "
                            + origin.toShortString() + " -> " + bot.getBlockPos().toShortString());
            if (context.getTick() >= 120) {
                despawnAndComplete(context, bot);
            }
        });
    }

    // This fixture opens a fourteen-block hostile corridor, wider than GameTest's default
    // structure spacing. Keep it in an isolated batch so neighbouring mobs/walls cannot change
    // the admission fact between the two synchronous scans.
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "nakedEatThreatCooldownIsolation", tickLimit = 40)
    public void observedHostileInsideThreatCooldownBlocksNewNakedHealingEat(
            TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "NakedEatAdmissionGT", 2);
        BlockPos origin = bot.getBlockPos().toImmutable();
        bot.setHealth(17.5F);
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        HoldingTask initialWork = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, initialWork,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_naked_eat_cooldown_seed"));

        SkeletonEntity skeleton = EntityType.SKELETON.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (skeleton == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create remote naked-eat skeleton fixture");
            return;
        }
        BlockPos hostileFeet = origin.east(7);
        BlockPos rangedFeet = origin.east(14);
        for (int dx = 1; dx <= 14; dx++) {
            BlockPos corridor = origin.east(dx);
            context.getWorld().setBlockState(corridor.down(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(corridor,
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(corridor.up(),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        skeleton.setPersistent();
        skeleton.setAiDisabled(true);
        skeleton.refreshPositionAndAngles(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(skeleton);
        require(context, CombatCore.hasLineOfSight(bot, skeleton),
                "remote naked-eat skeleton was not initially reachable");

        // Seed the ordinary threat retry cooldown with a real defensive assignment, then replace
        // the cancelled transaction with fresh resumable work while the same hostile remains.
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof CombatTask,
                "fixture did not seed the threat cooldown through defensive combat");
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_naked_eat_cooldown_seeded");
        skeleton.refreshPositionAndAngles(rangedFeet.getX() + 0.5D, rangedFeet.getY(),
                rangedFeet.getZ() + 0.5D, 90.0F, 0.0F);
        double rangedDistance = bot.getPos().distanceTo(skeleton.getPos());
        require(context, rangedDistance > 13.8D && rangedDistance < 14.2D
                        && CombatCore.hasLineOfSight(bot, skeleton),
                "naked-eat ranged fixture was not a fourteen-block LOS threat: distance="
                        + rangedDistance);

        bot.setHealth(4.7F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.MUTTON, 2));
        HoldingTask recoveryWork = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, recoveryWork,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_naked_eat_admission"));

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active == recoveryWork,
                "observed ranged hostile inside threat cooldown admitted naked "
                        + (active == null ? "idle" : active.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                "blocked naked EatTask grew a safety pause frame");
        require(context, InventoryAction.countItem(bot, Items.MUTTON) == 2,
                "blocked naked EatTask consumed food");
        skeleton.discard();
        despawnAndComplete(context, bot);
    }

    // The terminal-episode decision counts every observable hostile. An isolated batch proves
    // the intended close zombie without inheriting ranged mobs from adjacent empty structures.
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "terminalShelterEpisodeIsolation", tickLimit = 80)
    public void terminalShelterEpisodeUsesCloseDefensiveCombatUntilRelocation(
            TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "ShelterEpisodeFallbackGT", 2);
        BlockPos origin = bot.getBlockPos().toImmutable();
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
                context.getWorld().setBlockState(origin.add(dx, 2, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        bot.setHealth(4.7F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.MUTTON, 2));
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_episode_work"));

        ZombieEntity zombie = EntityType.ZOMBIE.create(context.getWorld(), SpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create shelter-episode zombie fixture");
            return;
        }
        BlockPos hostileFeet = origin.east(2);
        zombie.setPersistent();
        zombie.setAiDisabled(true);
        zombie.refreshPositionAndAngles(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(zombie);
        require(context, CombatCore.hasLineOfSight(bot, zombie),
                "shelter-episode fixture hostile was not reachable");

        DangerWatcher.INSTANCE.noteShelterTerminal(
                bot, origin, TaskState.FAILED, "gametest_shelter_terminal");
        require(context, DangerWatcher.INSTANCE.shelterEpisodeActive(bot),
                "terminal shelter did not latch its local hostile episode");

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CombatTask,
                "locked shelter site did not choose close defensive combat: "
                        + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "episode fallback did not preserve exactly one work frame");
        CombatTask combat = (CombatTask) active;
        combat.tick(bot);
        require(context, combat.describe().contains("phase=RETREAT"),
                "low-HP episode fallback did not start in RETREAT: " + combat.describe());
        require(context, DangerWatcher.INSTANCE.shelterEpisodeActive(bot),
                "continuing same-site hostile prematurely reset the shelter episode");

        BlockPos relocated = origin.south(5);
        context.getWorld().setBlockState(relocated.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(relocated, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(relocated.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(relocated.up(2),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        bot.teleport(context.getWorld(), relocated.getX() + 0.5D, relocated.getY(),
                relocated.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        require(context, !DangerWatcher.INSTANCE.shelterEpisodeActive(bot),
                "significant relocation did not reset the terminal shelter episode");

        zombie.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void lowHealthWithFoodPausesWorkToEatForHealing(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "LowHealthHealGT", 2);
        bot.setHealth(4.7F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.MUTTON, 2));
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_low_health_heal"));

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof EatTask,
                "low HP with usable food did not schedule healing eat: "
                        + (active == null ? "idle" : active.name()));
        require(context, TaskManager.INSTANCE.hasPaused(bot) && work.state() == TaskState.PAUSED,
                "healing eat did not preserve the interrupted work cursor");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 200)
    public void hostileLowHealthCannotInterruptAtomicHealingEat(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "LowHealthAtomicEatGT", 2);
        BlockPos origin = bot.getBlockPos().toImmutable();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                context.getWorld().setBlockState(origin.add(dx, 2, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        bot.setHealth(4.7F);
        bot.getHungerManager().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.MUTTON, 2));
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_low_health_atomic_eat"));

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        Task scheduled = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, scheduled instanceof EatTask,
                "fixture did not schedule the healing EatTask: "
                        + (scheduled == null ? "idle" : scheduled.name()));
        EatTask eat = (EatTask) scheduled;
        int pausedDepth = TaskManager.INSTANCE.pausedDepth(bot);
        require(context, pausedDepth == 1 && work.state() == TaskState.PAUSED,
                "fixture did not preserve exactly one interrupted work frame");

        SkeletonEntity skeleton = EntityType.SKELETON.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (skeleton == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create low-health skeleton fixture");
            return;
        }
        BlockPos hostileFeet = origin.east(2);
        skeleton.setPersistent();
        skeleton.setAiDisabled(true);
        skeleton.refreshPositionAndAngles(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(skeleton);
        // The physical two-block roof is the fact this regression needs. isSkyVisible() depends
        // on a lazily refreshed heightmap and can briefly report the pre-fixture value when the
        // default GameTest batch prepares many neighbouring structures in the same server tick.
        require(context, context.getWorld().getBlockState(origin.up(2)).isOf(Blocks.STONE),
                "atomic-eat fixture did not retain its physical cave roof");
        require(context, bot.canSee(skeleton) && CombatCore.hasLineOfSight(bot, skeleton),
                "atomic-eat skeleton was not an observable hostile");

        EvadeTask impossibleEscape = new EvadeTask(new Threat(
                Threat.Type.LOW_HP, Threat.Severity.HIGH, skeleton, hostileFeet));
        impossibleEscape.start(bot);
        impossibleEscape.tick(bot);
        require(context, impossibleEscape.state() == TaskState.FAILED
                        && "no_valid_escape_route".equals(impossibleEscape.failureReason()),
                "fixture unexpectedly exposed an escape route: " + impossibleEscape.describe());

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == eat,
                "LOW_HP skeleton replaced the active healing EatTask");
        require(context, TaskManager.INSTANCE.pausedDepth(bot) == pausedDepth,
                "LOW_HP skeleton grew the pause stack before eating began");

        context.runAtEveryTick(() -> {
            int remainingMutton = InventoryAction.countItem(bot, Items.MUTTON);
            if (remainingMutton < 2 && bot.getHungerManager().getFoodLevel() > 17) {
                require(context, TaskManager.INSTANCE.pausedDepth(bot) == pausedDepth,
                        "pause stack grew while the physical bite was settling");
                skeleton.discard();
                despawnAndComplete(context, bot);
                return;
            }
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == eat,
                    "healing EatTask lost ownership before consuming food");
            require(context, eat.state() == TaskState.RUNNING,
                    "healing EatTask became terminal before consuming food: "
                            + eat.state() + ":" + eat.failureReason());
            require(context, TaskManager.INSTANCE.pausedDepth(bot) == pausedDepth,
                    "hostile scan nested another safety frame above healing EatTask");
            DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void entitylessLowHealthThreatCannotInventDownwardEscape(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "LowHealthVectorGT", 25);
        BlockPos origin = bot.getBlockPos().toImmutable();
        // Provide the exact tempting old destination: a valid dry cave floor twenty blocks below.
        BlockPos cave = origin.down(20);
        context.getWorld().setBlockState(cave.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(cave, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(cave.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        EvadeTask task = new EvadeTask(new Threat(
                Threat.Type.LOW_HP, Threat.Severity.HIGH, null, origin));
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED
                        && "no_valid_escape_route".equals(task.failureReason()),
                "entity-less low HP invented an escape route: " + task.describe());
        require(context, bot.getBlockPos().getY() == origin.getY()
                        && bot.getActionPack().isPathExecutorIdle(),
                "entity-less low HP began moving toward the cave below");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "evadePathAdmissionCleanupStrict", tickLimit = 60)
    public void failedSurfacePathEvadeReleasesSprintAndAllowsPausedWorkResume(
            TestContext context) {
        // Keep the fixture well above neighbouring templates: Evade deliberately searches about
        // twenty horizontal blocks away, beyond the empty structure's eight-block footprint.
        AIPlayerEntity bot = spawnOnPlatform(context, "EvadeAdmissionCleanupGT", 80);
        BlockPos origin = bot.getBlockPos().toImmutable();
        var world = context.getWorld();

        // chooseGoal can prove a dry standable destination, but the sealed start makes the
        // surface-only path admission fail without granting excavation as an escape shortcut.
        for (BlockPos wall : new BlockPos[]{
                origin.north(), origin.south(), origin.east(), origin.west()}) {
            world.setBlockState(wall, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(wall.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(origin.up(2), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos candidate = origin.east(20);
        world.setBlockState(candidate.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(candidate, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(candidate.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_evade_admission_cleanup"));
        TaskManager.INSTANCE.pauseFor(bot, "gametest_hostile_interrupt");
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "fixture did not preserve one paused mission frame");

        EvadeTask evade = new EvadeTask(new Threat(
                Threat.Type.HOSTILE, Threat.Severity.HIGH, null, origin.west()));
        TaskManager.INSTANCE.assign(bot, evade, TaskOrigin.safety("gametest_failed_evade"));
        TaskManager.INSTANCE.tickAll(world.getServer());
        require(context, evade.state() == TaskState.FAILED
                        && "no_valid_escape_route".equals(evade.failureReason()),
                "sealed surface path did not fail admission: " + evade.describe());
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                "TaskManager retained the terminal EvadeTask");
        require(context, bot.getActionPack().isPathExecutorIdle()
                        && !bot.getActionPack().hasActiveActions(),
                "failed evade retained sprint or another synthetic action");
        require(context, DangerWatcher.canResumePausedWork(bot, java.util.Optional.empty()),
                "safe post-evade state did not pass the mission resume gate");

        TaskManager.INSTANCE.resumeFromPause(bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == work
                        && work.state() == TaskState.RUNNING
                        && !TaskManager.INSTANCE.hasPaused(bot),
                "failed evade left the original mission frame permanently paused");
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "digDownLavaReturnOwnershipStrict", tickLimit = 120)
    public void pausedDigDownClaimsObservedLavaAndPaysExactReturn(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "DigDownLavaReturnGT", 55);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        var world = context.getWorld();
        BlockPos start = bot.getBlockPos().toImmutable();
        BlockPos middle = start.east().down();
        BlockPos tail = middle.east().down();
        List<BlockPos> trail = List.of(start, middle, tail);
        for (BlockPos feet : trail) {
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        bot.teleport(world, tail.getX() + 0.5D, tail.getY(), tail.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 12));

        // One elevated source stays inside the watcher's +/-2 horizontal and +/-1 vertical window
        // from every factual waypoint. Three stone sides contain it; the visible south cell is reset
        // before each scan so fluid spread cannot turn this ownership proof into a contact-lava test.
        BlockPos lava = tail.north(2).up();
        world.setBlockState(lava.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava.north(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava.east(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava.west(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lava.south(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                4, "minecraft:stone", 36, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, tail.getY(), 0, 12, 1000, 900, 0,
                0, 0, false, null, 0, trail,
                -1, 0, -20, false, 0, -1, -1L, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 36, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_lava_return"));
        TaskManager.INSTANCE.pauseFor(bot, "gametest_failed_lava_evade_complete");
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, paused != null
                        && paused.phase() == DigDownTask.Phase.RETURN
                        && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED
                        && paused.returnTrailIndex() == trail.size() - 1,
                "fixture did not publish the paused factual return debt: " + task.checkpoint());
        int deathsBefore = deathCount(bot);
        float healthBefore = bot.getHealth();

        // Cross the old trap-repeat boundary without advancing the task. Every scan must be
        // idempotent: same instance, same cursor/budget, no generic Evade and no new pause frame.
        for (int scan = 0; scan < 6; scan++) {
            world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(lava.south(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            require(context, DangerWatcher.INSTANCE.scanBot(world.getServer(), bot),
                    "visible lava scan was not handled at iteration " + scan);
            DigDownTask.DigDownCheckpoint returning = DigDownTask.DigDownCheckpoint
                    .decode(task.checkpoint()).orElse(null);
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == task
                            && task.state() == TaskState.RUNNING
                            && TaskManager.INSTANCE.pausedDepth(bot) == 0,
                    "visible lava replaced or stranded the DigDown owner at scan " + scan);
            require(context, returning != null
                            && returning.phase() == DigDownTask.Phase.RETURN
                            && returning.returnOutcome() == DigDownTask.ReturnOutcome.WALLED
                            && returning.returnTrailIndex() == trail.size() - 1
                            && returning.returnBudgetUsed() == 0,
                    "repeated lava scan reset or changed the exact return debt: "
                            + task.checkpoint());
        }
        require(context, EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUuid(), start, world.getServer().getTicks()),
                "observed-lava entry was not excluded from same-episode replanning");

        int[] lastReturnIndex = {trail.size() - 1};
        int[] lastReturnBudget = {0};
        context.runAtEveryTick(() -> {
            if (task.state() != TaskState.RUNNING) {
                require(context, task.state() == TaskState.FAILED
                                && "dig_down_walled collected=12".equals(task.failureReason()),
                        "lava return lost its typed terminal outcome: "
                                + task.state() + ":" + task.failureReason());
                require(context, bot.getBlockPos().equals(start),
                        "lava return settled before the exact origin: "
                                + bot.getBlockPos().toShortString());
                require(context, bot.getHealth() == healthBefore
                                && deathCount(bot) == deathsBefore
                                && !bot.isInLava()
                                && !bot.isOnFire()
                                && TaskManager.INSTANCE.pausedDepth(bot) == 0,
                        "exact lava return ended with damage, contact or a paused frame");
                // Once DigDown has paid its return debt, the still-visible source may legitimately
                // start a fresh generic Evade. That post-terminal safety task is outside this
                // ownership proof and despawnAndComplete clears it with the fixture.
                despawnAndComplete(context, bot);
                return;
            }

            require(context, trail.contains(bot.getBlockPos()),
                    "lava return left the factual trail: " + bot.getBlockPos().toShortString());
            require(context, world.getBlockState(lava).isOf(Blocks.LAVA),
                    "DigDown mutated the factual lava source");
            require(context, bot.getHealth() == healthBefore
                            && deathCount(bot) == deathsBefore
                            && !bot.isInLava()
                            && !bot.isOnFire(),
                    "exact lava return caused damage, death or contact");
            require(context, TaskManager.INSTANCE.pausedDepth(bot) == 0,
                    "repeated lava scan recreated a paused frame");

            DigDownTask.DigDownCheckpoint live = DigDownTask.DigDownCheckpoint
                    .decode(task.checkpoint()).orElse(null);
            require(context, live != null
                            && live.returnTrailIndex() <= lastReturnIndex[0]
                            && live.returnBudgetUsed() >= lastReturnBudget[0],
                    "lava return cursor or budget regressed: " + task.checkpoint());
            lastReturnIndex[0] = live.returnTrailIndex();
            lastReturnBudget[0] = live.returnBudgetUsed();
            world.setBlockState(lava, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(lava.south(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            DangerWatcher.INSTANCE.scanBot(world.getServer(), bot);
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == task
                            && TaskManager.INSTANCE.pausedDepth(bot) == 0,
                    "continuous lava observation preempted the returning DigDown");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void unprovokedEndermanDoesNotInterruptCurrentWork(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "PassiveEndermanGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_passive_enderman"));

        EndermanEntity enderman = EntityType.ENDERMAN.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (enderman == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create passive Enderman fixture");
            return;
        }
        BlockPos endermanFeet = bot.getBlockPos().east(4);
        enderman.setPersistent();
        enderman.refreshPositionAndAngles(endermanFeet.getX() + 0.5D, endermanFeet.getY(),
                endermanFeet.getZ() + 0.5D, 0.0F, 0.0F);
        context.getWorld().spawnEntity(enderman);

        require(context, !enderman.isAngry() && enderman.getTarget() == null,
                "Enderman fixture spawned already provoked");
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active == work,
                "unprovoked Enderman replaced current work with "
                        + (active == null ? "idle" : active.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                "unprovoked Enderman paused current work");
        enderman.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "hostileSafetyRegression", tickLimit = 40)
    public void endermanAngryAtAnotherEntityDoesNotInterruptCurrentWork(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "OtherAngerEndermanGT", 116);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_enderman_other_anger"));

        var bystander = EntityType.COW.create(context.getWorld(), SpawnReason.COMMAND);
        if (bystander == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create Enderman anger bystander");
            return;
        }
        bystander.setPersistent();
        BlockPos bystanderFeet = bot.getBlockPos().east(4);
        bystander.refreshPositionAndAngles(
                bystanderFeet.getX() + 0.5D, bystanderFeet.getY(),
                bystanderFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(bystander);

        EndermanEntity enderman = spawnDisabledEnderman(
                context, bot.getBlockPos().east(2), "other-anger Enderman fixture");
        enderman.setAngerTime(600);
        enderman.setAngryAt(bystander.getUuid());
        enderman.setTarget(bystander);
        require(context, enderman.isAngry()
                        && enderman.getTarget() == bystander
                        && !enderman.shouldAngerAt(bot, bot.getServerWorld()),
                "Enderman fixture was not angry exclusively at the bystander");
        require(context, !DangerWatcher.isActiveHostileThreat(bot, enderman),
                "anger directed at another entity was attributed to this bot");

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == work
                        && !TaskManager.INSTANCE.hasPaused(bot),
                "other-directed Enderman anger interrupted current work");
        enderman.discard();
        bystander.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "hostileSafetyRegression", tickLimit = 80)
    public void provokedEndermanRoutesToEvade(TestContext context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "ProvokedEndermanGT", 132);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_provoked_enderman"));
        EndermanEntity enderman = spawnDisabledEnderman(
                context, bot.getBlockPos().east(8), "provoked Enderman fixture");
        enderman.setAngerTime(600);
        enderman.setAngryAt(bot.getUuid());
        enderman.setTarget(bot);
        float initialHealth = enderman.getHealth();

        require(context, DangerWatcher.isActiveHostileThreat(bot, enderman)
                        && ObservableWorldQuery.canObserveEntity(bot, enderman)
                        && CombatCore.hasLineOfSight(bot, enderman),
                "provoked Enderman was not a factual active threat");
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof EvadeTask,
                "provoked Enderman routed to "
                        + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "provoked Enderman did not preserve exactly one work frame");
        require(context, !bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().activePathGoal() != null,
                "provoked Enderman Evade did not admit a surface path");
        require(context, enderman.getHealth() == initialHealth,
                "provoked Enderman routing dealt combat damage");
        enderman.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "hostileSafetyRegression", tickLimit = 80)
    public void directCombatNeverAttacksEnderman(TestContext context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "CombatEndermanGuardGT", 164);
        BlockPos origin = bot.getBlockPos().toImmutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        EndermanEntity enderman = spawnDisabledEnderman(
                context, origin.east(3), "direct-combat Enderman fixture");
        enderman.setAngerTime(600);
        enderman.setAngryAt(bot.getUuid());
        enderman.setTarget(bot);
        float initialHealth = enderman.getHealth();

        // Exercise CombatTask directly so the assertion survives even if a caller bypasses the
        // normal DangerWatcher -> Evade routing boundary.
        CombatTask combat = CombatTask.defensive(enderman, 6.0F, origin);
        combat.start(bot);
        combat.tick(bot);
        combat.tick(bot);
        combat.tick(bot);

        require(context, combat.state() == TaskState.RUNNING
                        && combat.describe().contains("phase=RETREAT")
                        && enderman.getHealth() == initialHealth,
                "direct CombatTask attacked an Enderman: " + combat.describe());
        require(context, !bot.getActionPack().isPathExecutorIdle(),
                "direct Enderman CombatTask did not retain escape movement");
        enderman.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "hostileSafetyRegression", tickLimit = 80)
    public void combatRetreatAdmitsLateralSurfacePath(TestContext context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatLateralRetreatGT", 148);
        var world = context.getWorld();
        BlockPos origin = bot.getBlockPos().toImmutable();

        // Remove every projected endpoint, then expose only a connected north corridor. The
        // hostile stands east, so a direct retreat would be west and only the shared fan can find
        // this factual lateral route.
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                BlockPos cell = origin.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        for (int dz = 0; dz >= -12; dz--) {
            for (int dx = -1; dx <= 1; dx++) {
                BlockPos cell = origin.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        bot.setHealth(8.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        HuskEntity husk = EntityType.HUSK.create(world, SpawnReason.COMMAND);
        if (husk == null) {
            despawnAndComplete(context, bot);
            context.throwGameTestException("failed to create lateral-retreat Husk fixture");
            return;
        }
        husk.setPersistent();
        husk.setAiDisabled(true);
        BlockPos hostileFeet = origin.east();
        husk.refreshPositionAndAngles(
                hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        world.spawnEntity(husk);

        CombatTask combat = CombatTask.defensive(husk, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_lateral_combat_retreat"));
        combat.tick(bot);

        BlockPos goal = bot.getActionPack().activePathGoal();
        require(context, combat.state() == TaskState.RUNNING
                        && combat.describe().contains("phase=RETREAT"),
                "low-health combat did not retain RETREAT ownership: "
                        + combat.state() + ":" + combat.failureReason());
        require(context, goal != null
                        && Math.abs(goal.getX() - origin.getX()) <= 1
                        && goal.getZ() <= origin.getZ() - 5,
                "Combat retreat did not select the lateral corridor: "
                        + (goal == null ? "no goal" : goal.toShortString()));
        require(context, !bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle(),
                "Combat retreat bypassed surface-path admission");
        husk.discard();
        despawnAndComplete(context, bot);
    }

    private static Map<String, String> createActiveBreakCheckpoint(BlockPos origin,
                                                                    BlockPos obsidian,
                                                                    BlockPos stand) {
        Map<String, String> values = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(origin, 12).encode());
        values.put("task_schema", "2");
        values.put("target_count", "1");
        values.put("phase", CreateObsidianTask.Phase.MINE.name());
        values.put("inventory_baseline", "0");
        values.put("collected", "0");
        values.put("serviced_collected", "0");
        values.put("pending_service_boundary", "0");
        values.put("budget_used", "20");
        values.put("phase_started", "20");
        values.put("last_progress", "20");
        values.put("pickup_grace", "0");
        values.put("water_bucket_baseline", "-1");
        values.put("pending_pickup_inventory", "-1");
        values.put("pickup_gain_budget", "-1");
        values.put("active_break_inventory", "0");
        values.put("protection_prepared", "false");
        values.put("obsidian", encode(obsidian));
        values.put("stand", encode(stand));
        values.put("active_break_pos", encode(obsidian));
        Map<String, String> checkpoint = Map.copyOf(values);
        if (CreateObsidianTask.ObsidianCheckpoint.decode(checkpoint, 1, 24000).isEmpty()) {
            throw new IllegalStateException("invalid Create raw-one fixture: " + checkpoint);
        }
        return checkpoint;
    }

    private static Map<String, String> oreDigCheckpoint(BlockPos face,
                                                         BlockPos pendingPickup,
                                                         BlockPos activeBreak) {
        Set<Block> ores = Set.of(Blocks.IRON_ORE);
        Map<String, String> checkpoint = new OreDigTask.OreDigCheckpoint(
                4,
                1,
                true,
                0,
                0,
                false,
                40,
                0,
                0,
                MiningCursor.initial(face, 48),
                OreDigTask.oreFingerprint(ores),
                0,
                0,
                null,
                null,
                pendingPickup,
                pendingPickup,
                pendingPickup == null ? -1 : 0,
                pendingPickup == null ? -1 : 0,
                -1,
                activeBreak,
                activeBreak == null ? -1 : 0).encode();
        if (OreDigTask.OreDigCheckpoint.decode(checkpoint, ores).isEmpty()) {
            throw new IllegalStateException("invalid OreDig raw-one fixture: " + checkpoint);
        }
        return checkpoint;
    }

    private static int rawDurability(ItemStack stack) {
        return stack.isEmpty() || !stack.isDamageable()
                ? 0 : stack.getMaxDamage() - stack.getDamage();
    }

    private static int deathCount(AIPlayerEntity bot) {
        return bot.getStatHandler().getStat(
                Stats.CUSTOM.getOrCreateStat(Stats.DEATHS));
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static AIPlayerEntity spawnOnPlatform(TestContext context, String name, int relativeY) {
        var world = context.getWorld();
        world.setTimeOfDay(1000L);
        BlockPos feet = context.getAbsolutePos(new BlockPos(3, relativeY, 3));
        // Own the complete 8x8 template footprint. The old z=-76..-184 offsets escaped the
        // structure and let unrelated long GameTests overwrite raw-one drops and cave roofs.
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
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
        return bot;
    }

    private static AIPlayerEntity spawnOnEscapeCorridor(TestContext context,
                                                         String name,
                                                         int relativeY) {
        var world = context.getWorld();
        world.setTimeOfDay(1000L);
        BlockPos feet = context.getAbsolutePos(new BlockPos(3, relativeY, 3));
        // Keep long escape fixtures vertically isolated from the ordinary 8x8 GameTest footprint.
        // This proves real path admission without overwriting neighbouring structures.
        for (int dx = -64; dx <= 12; dx++) {
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
        return bot;
    }

    private static AIPlayerEntity spawnOnReactiveEscapeArena(TestContext context,
                                                              String name,
                                                              int relativeY) {
        var world = context.getWorld();
        world.setTimeOfDay(1000L);
        BlockPos feet = context.getAbsolutePos(new BlockPos(3, relativeY, 3));
        for (int dx = -18; dx <= 18; dx++) {
            for (int dz = -18; dz <= 18; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(
                        cell.down(), Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(feet),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }

    private static CreeperEntity spawnLiveTargetingCreeper(TestContext context,
                                                            BlockPos feet,
                                                            AIPlayerEntity target,
                                                            String fixture) {
        CreeperEntity creeper = EntityType.CREEPER.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (creeper == null) {
            context.throwGameTestException("failed to create " + fixture);
            throw new IllegalStateException("failed to create " + fixture);
        }
        creeper.setPersistent();
        creeper.setAiDisabled(false);
        creeper.refreshPositionAndAngles(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(creeper);
        creeper.setTarget(target);
        return creeper;
    }

    private static void chargeCreeperWithoutLightningDamage(TestContext context,
                                                             CreeperEntity creeper) {
        LightningEntity lightning = EntityType.LIGHTNING_BOLT.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (lightning == null) {
            context.throwGameTestException("failed to create charged Creeper fixture");
            throw new IllegalStateException("failed to create charged Creeper fixture");
        }
        creeper.onStruckByLightning(context.getWorld(), lightning);
        creeper.extinguish();
        creeper.setHealth(creeper.getMaxHealth());
    }

    private static CreeperEntity spawnDisabledCreeper(TestContext context,
                                                       BlockPos feet,
                                                       String fixture) {
        CreeperEntity creeper = EntityType.CREEPER.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (creeper == null) {
            context.throwGameTestException("failed to create " + fixture);
            throw new IllegalStateException("failed to create " + fixture);
        }
        creeper.setPersistent();
        creeper.setAiDisabled(true);
        creeper.refreshPositionAndAngles(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(creeper);
        return creeper;
    }

    private static EndermanEntity spawnDisabledEnderman(TestContext context,
                                                         BlockPos feet,
                                                         String fixture) {
        EndermanEntity enderman = EntityType.ENDERMAN.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (enderman == null) {
            context.throwGameTestException("failed to create " + fixture);
            throw new IllegalStateException("failed to create " + fixture);
        }
        enderman.setPersistent();
        enderman.setAiDisabled(true);
        enderman.refreshPositionAndAngles(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getWorld().spawnEntity(enderman);
        return enderman;
    }

    private static void despawnAndComplete(TestContext context, AIPlayerEntity bot) {
        String name = bot.getGameProfile().getName();
        DangerWatcher.INSTANCE.clear(bot);
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private static void requireUnpreempted(TestContext context,
                                           AIPlayerEntity bot,
                                           Task expected,
                                           String owner) {
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active == expected,
                owner + " was replaced with " + (active == null ? "idle" : active.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                owner + " was pushed behind generic resupply");
        require(context, expected.state() == TaskState.RUNNING,
                owner + " became terminal: " + expected.state());
    }

    private static void assertStrictCapabilities(TestContext context, AIPlayerEntity bot) {
        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + AIBotConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "danger_watcher_live_creeper_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }
    }

    private static final class HoldingTask extends AbstractTask {
        @Override
        public String name() {
            return "holding_work";
        }

        @Override
        public String describe() {
            return "Holding a resumable work cursor";
        }

        @Override
        public double progress() {
            return 0.5D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }
    }
}
