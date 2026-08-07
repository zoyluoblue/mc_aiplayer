package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.action.ContainerAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.brain.BotReporter;
import io.github.zoyluo.aibot.brain.BotRuntimeOptions;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.mining.MiningBudget;
import io.github.zoyluo.aibot.mining.MiningCursor;
import io.github.zoyluo.aibot.mining.MiningMissionBudget;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.persist.MissionRecord;
import io.github.zoyluo.aibot.persist.MissionRuntimeRecord;
import io.github.zoyluo.aibot.persist.MissionSpec;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.AbstractTask;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.DescendToYTask;
import io.github.zoyluo.aibot.task.EmergencyShelterTask;
import io.github.zoyluo.aibot.task.HoldTask;
import io.github.zoyluo.aibot.task.MiningServiceTask;
import io.github.zoyluo.aibot.task.OreDigTask;
import io.github.zoyluo.aibot.task.ResupplyTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.inventory.Inventory;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Mission-level proof that a live ore batch restores its durable branch cursor. */
public final class MiningCheckpointMissionGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 140)
    public void trappedBlindBranchFailsMissionWithoutRecreatingOreDig(TestContext context) {
        String name = "OreBoundaryMissionGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.COAL_ORE), 1);
        long resultBaseline = GoalExecutor.INSTANCE.lastResult(bot)
                .map(GoalResult::sequence).orElse(0L);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "trapped-branch goal setup failed");

        AtomicBoolean restoredBoundary = new AtomicBoolean();
        AtomicReference<OreDigTask> trappedTask = new AtomicReference<>();
        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);

            if (!restoredBoundary.get()) {
                if (!(active instanceof OreDigTask)
                        || !"MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    if (context.getTick() > 55) {
                        context.throwGameTestException(
                                "fixture never reached OreDig: " + checkpointSummary(checkpoint));
                    }
                    return;
                }
                BlockPos face = bot.getBlockPos().toImmutable();
                var world = bot.getServerWorld();
                world.setBlockState(
                        face.north(), Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_LISTENERS);
                world.setBlockState(
                        face.north().up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                for (BlockPos lateral : new BlockPos[]{face.east(), face.west()}) {
                    world.setBlockState(
                            lateral, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                    world.setBlockState(
                            lateral.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }

                Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                String encodedFace = face.getX() + "," + face.getY() + "," + face.getZ();
                for (String prefix : Set.of("task.", "mining.")) {
                    forged.put(prefix + "face", encodedFace);
                    forged.put(prefix + "direction", "0");
                    forged.put(prefix + "leg", "0");
                    forged.put(prefix + "steps_left", "48");
                    forged.put(prefix + "leg_length", "48");
                    // This is an ordinary restored branch, not a successful zero-movement
                    // boundary turn. Its solid geometric rear must remain excluded.
                    forged.remove(prefix + "boundary_reroute_origin");
                    forged.remove(prefix + "pending_pickup_pos");
                    forged.remove(prefix + "pending_pickup_last_seen_pos");
                    forged.put(prefix + "pending_pickup_inventory", "-1");
                    forged.put(prefix + "pending_pickup_started_budget", "-1");
                    forged.put(prefix + "pickup_gain_budget", "-1");
                    forged.remove(prefix + "active_break_pos");
                    forged.put(prefix + "active_break_inventory", "-1");
                }

                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_restore_trapped_branch");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(runtime, forged));
                require(context, GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "valid trapped cursor was rejected during restore");
                Object restoredActive = TaskManager.INSTANCE.getActive(bot).orElse(null);
                require(context, restoredActive instanceof OreDigTask,
                        "restored trapped cursor did not assign its OreDigTask");
                trappedTask.set((OreDigTask) restoredActive);
                restoredBoundary.set(true);
                return;
            }

            if (active instanceof OreDigTask oreDig) {
                OreDigTask first = trappedTask.get();
                if (first == null) {
                    trappedTask.set(oreDig);
                } else {
                    require(context, first == oreDig,
                            "GoalExecutor recreated a second OreDigTask for the same trapped cursor");
                }
            }
            GoalResult result = GoalExecutor.INSTANCE.resultAfter(
                    bot, resultBaseline).orElse(null);
            if (result != null) {
                require(context, trappedTask.get() != null,
                        "trapped mission ended before the restored OreDigTask ran");
                require(context, result.goal().equals(goal)
                                && result.status() == GoalResult.Status.FAILED
                                && result.reason().startsWith(
                                "ore_dig_branch_boundary_trapped:"),
                        "trapped branch lost its typed terminal result: "
                                + result.status() + ":" + result.reason());
                require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "trapped branch retained an active mission after terminal failure");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (context.getTick() > 115) {
                context.throwGameTestException(
                        "trapped branch did not terminate on its first typed failure");
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 180)
    public void completedToolResupplyRetriesTheSameOreBatchWithoutParentReplan(
            TestContext context) {
        String name = "OreToolRetryGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.IRON_ORE), 1);
        long resultBaseline = GoalExecutor.INSTANCE.lastResult(bot)
                .map(GoalResult::sequence).orElse(0L);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "iron goal setup failed");

        AtomicBoolean toolsExhausted = new AtomicBoolean();
        AtomicBoolean sawResupply = new AtomicBoolean();
        AtomicBoolean resumed = new AtomicBoolean();
        AtomicReference<Object> originalTask = new AtomicReference<>();
        AtomicReference<String> originalMission = new AtomicReference<>();
        AtomicReference<String> originalOrigin = new AtomicReference<>();
        AtomicReference<Integer> originalBudget = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();

            if (!toolsExhausted.get()) {
                Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);
                if (active instanceof OreDigTask
                        && "MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    int stoneSlot = InventoryAction.findItem(bot, Items.STONE_PICKAXE)
                            .orElseThrow(() -> new IllegalStateException(
                                    "fixture has no stone pickaxe"));
                    require(context, InventoryAction.equipFromSlot(bot, stoneSlot) >= 0,
                            "could not equip the resupply target");
                    originalTask.set(active);
                    originalMission.set(runtime.active().missionId());
                    originalOrigin.set(checkpoint.get("task.origin"));
                    originalBudget.set(Integer.parseInt(checkpoint.get("task.budget_used")));
                    exhaustAllPickaxes(bot);
                    toolsExhausted.set(true);
                } else if (context.getTick() > 45) {
                    context.throwGameTestException(
                            "fixture never reached OreDig: " + checkpointSummary(checkpoint));
                }
                return;
            }

            Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof ResupplyTask) {
                sawResupply.set(true);
                return;
            }
            if (!resumed.get() && active instanceof OreDigTask && active != originalTask.get()) {
                MissionRuntimeRecord after = GoalExecutor.INSTANCE.captureRuntime(bot);
                require(context, after.active() != null
                                && originalMission.get().equals(after.active().missionId()),
                        "tool recovery changed mission identity");
                Map<String, String> restored = after.active().checkpoint();
                require(context, sawResupply.get(),
                        "OreDig was replaced before a physical ResupplyTask completed");
                require(context, "MINE_ORE".equals(restored.get("task_kind"))
                                && originalOrigin.get().equals(restored.get("task.origin")),
                        "tool recovery changed ore checkpoint identity: "
                                + checkpointSummary(restored));
                require(context, Integer.parseInt(restored.get("task.budget_used"))
                                >= originalBudget.get()
                                && "0".equals(restored.get("lifetime_replans")),
                        "tool recovery refreshed budget or replanned parent: "
                                + checkpointSummary(restored));
                require(context, TaskManager.INSTANCE.activeOrigin(bot)
                                .map(origin -> origin.kind()
                                        == io.github.zoyluo.aibot.runtime.TaskOrigin.Kind.MISSION
                                        && originalMission.get().equals(
                                        origin.missionId().toString()))
                                .orElse(false),
                        "retried OreDig is not owned by the original mission");
                require(context, GoalExecutor.INSTANCE.resultAfter(bot, resultBaseline).isEmpty(),
                        "tool recovery published a terminal goal result");
                resumed.set(true);
                InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON));
                return;
            }
            if (!resumed.get()) {
                if (GoalExecutor.INSTANCE.resultAfter(bot, resultBaseline).isPresent()
                        || context.getTick() > 125) {
                    context.throwGameTestException(
                            "post-resupply OreDig was not retried: active="
                                    + (active == null ? "idle" : active.getClass().getSimpleName())
                                    + " checkpoint=" + checkpointSummary(checkpoint));
                }
                return;
            }

            GoalResult result = GoalExecutor.INSTANCE.resultAfter(bot, resultBaseline).orElse(null);
            if (result != null) {
                require(context, result.goal().equals(goal)
                                && result.status() == GoalResult.Status.COMPLETED,
                        "retried ore mission ended as " + result.status() + ":" + result.reason());
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (context.getTick() > 165) {
                context.throwGameTestException("retried ore mission did not complete");
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 220)
    public void diamond64BootstrapCoalUsesOrdinaryCheckpointAndPhysicalResupply(
            TestContext context) {
        String name = "DiamondBootstrapCoalGT";
        var world = context.getWorld();
        BlockPos template = context.getAbsolutePos(new BlockPos(1, 2, 1));
        BlockPos start = new BlockPos(template.getX(), 48, template.getZ());
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlockState(start.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
        for (int offset = -5; offset <= 5; offset++) {
            for (int dy = -1; dy <= 1; dy++) {
                world.setBlockState(start.add(-5, dy, offset),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                world.setBlockState(start.add(5, dy, offset),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                world.setBlockState(start.add(offset, dy, -5),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                world.setBlockState(start.add(offset, dy, 5),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        giveItemToAtLeast(bot, Items.IRON_PICKAXE, 3);
        giveItemToAtLeast(bot, Items.STONE_PICKAXE, 5);
        // The large coal prerequisite is tier-WOOD and the planner budgets its own cheap target
        // picks even though the branch channel is protected by the stone-pick pool below.
        giveItemToAtLeast(bot, Items.WOODEN_PICKAXE, 5);
        giveItemToAtLeast(bot, Items.IRON_INGOT, 6);
        // Keep the enlarged margin-funded 720-torch contract in scope without asking this sealed
        // fixture to gather surface wood or mine detour stone before the coal OreDig it isolates.
        // The larger torch pool grows the coal chain to twelve batches whose channel-repair heads
        // need 56 picks x 3 = 168 stone-like, so the pre-margin 160 cobblestone is eight short;
        // 192 covers it inside the same three slots. Logs shrink to one stack so the carry stays
        // one slot above the pre-margin fixture (the margin sticks own that slot).
        giveItemToAtLeast(bot, Items.STICK,
                MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS + 6);
        giveItemToAtLeast(bot, Items.OAK_LOG,
                50 + EmergencyShelterTask.MAX_PLACEMENT_BLOCKS);
        giveItemToAtLeast(bot, Items.COOKED_BEEF, MiningBudget.RARE_BOOTSTRAP_FOOD);
        giveItemToAtLeast(bot, Items.COBBLESTONE, 192);
        giveItemToAtLeast(bot, Items.CRAFTING_TABLE, 1);
        // target64 now owns a physical mission-local depot before the final descent. This
        // fixture isolates the ordinary coal channel-resupply contract, so provide the chest
        // directly instead of letting a deep Y=48 test canvas emit an unrelated surface gather.
        giveItemToAtLeast(bot, Items.CHEST, 1);

        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "diamond64 bootstrap-coal goal setup failed");
        AtomicBoolean exhausted = new AtomicBoolean();
        AtomicBoolean sawResupply = new AtomicBoolean();
        AtomicReference<String> missionId = new AtomicReference<>();
        AtomicReference<String> fingerprint = new AtomicReference<>();
        AtomicReference<Integer> budget = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (!exhausted.get()) {
                if (active instanceof OreDigTask
                        && "MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    require(context, "0".equals(checkpoint.get("task.rare_mission_target"))
                                    && checkpoint.getOrDefault("task.ore_fingerprint", "")
                                    .contains("coal_ore"),
                            "diamond parent leaked rare identity into bootstrap coal: "
                                    + checkpointSummary(checkpoint));
                    missionId.set(runtime.active().missionId());
                    fingerprint.set(checkpoint.get("task.ore_fingerprint"));
                    budget.set(Integer.parseInt(checkpoint.get("task.budget_used")));
                    // Keep coal's target-harvest tools healthy and exhaust only the ordinary stone
                    // branch channel. This must enter GoalExecutor's bounded channel resupply, not
                    // the unrelated generic target-tool recovery path.
                    exhaustStonePickaxes(bot);
                    int ironSlot = InventoryAction.findItem(bot, Items.IRON_PICKAXE)
                            .orElseThrow(() -> new IllegalStateException(
                                    "fixture has no healthy target pickaxe"));
                    require(context, InventoryAction.equipFromSlot(bot, ironSlot) >= 0,
                            "could not keep a healthy target pickaxe in hand");
                    exhausted.set(true);
                } else if (context.getTick() > 80) {
                    context.throwGameTestException(
                            "diamond bootstrap never reached ordinary coal OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            if (active instanceof ResupplyTask) {
                sawResupply.set(true);
                return;
            }
            if (sawResupply.get() && active instanceof OreDigTask
                    && "MINE_ORE".equals(checkpoint.get("task_kind"))) {
                require(context, runtime.active() != null
                                && missionId.get().equals(runtime.active().missionId())
                                && "0".equals(checkpoint.get("task.rare_mission_target"))
                                && fingerprint.get().equals(checkpoint.get("task.ore_fingerprint"))
                                && Integer.parseInt(checkpoint.get("task.budget_used")) >= budget.get()
                                && checkpoint.get("task.face").equals(checkpoint.get("mining.face"))
                                && checkpoint.get("task.budget_used")
                                .equals(checkpoint.get("mining.budget_used"))
                                && "true".equals(checkpoint.get("task.inventory_service_used"))
                                && "0".equals(checkpoint.get("lifetime_replans"))
                                && hasUsableStonePickaxe(bot),
                        "ordinary bootstrap resupply changed mission/cursor/budget: "
                                + checkpointSummary(checkpoint));
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
                return;
            }
            if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 200) {
                context.throwGameTestException(
                        "bootstrap coal did not use one physical ordinary resupply: active="
                                + (active == null ? "idle" : active.getClass().getSimpleName())
                                + " checkpoint=" + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 320)
    public void diamond64RestoresMissionKitAndSealsInventoryBeforeFinalDescent(
            TestContext context) {
        String name = "DiamondFreshRareKitGT";
        var world = context.getWorld();
        BlockPos anchor = context.getAbsolutePos(new BlockPos(1, 2, 1));
        BlockPos start = new BlockPos(anchor.getX(), 64, anchor.getZ());
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlockState(start.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        for (int index = 0; index < 5; index++) {
            ItemStack nearlyBroken = new ItemStack(Items.STONE_PICKAXE);
            nearlyBroken.setDamage(nearlyBroken.getMaxDamage() - 2);
            require(context, !InventoryAction.giveItem(bot, nearlyBroken).isFailed(),
                    "could not give near-broken stone pick " + index);
        }
        giveItemToAtLeast(bot, Items.COBBLESTONE,
                MiningBudget.RARE_BOOTSTRAP_STONE_LIKE
                        + 5 * MiningBudget.STONE_PICKAXE_HEAD_COST + 2);
        giveItemToAtLeast(bot, Items.STICK,
                MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS
                        + 5 * MiningBudget.STONE_PICKAXE_STICK_COST);
        giveItemToAtLeast(bot, Items.IRON_PICKAXE, 3);
        giveItemToAtLeast(bot, Items.IRON_INGOT, 6);
        int descentTorchReserve = (64 - (-59) + 5) / 6;
        int sealedTorchTarget = ((MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES
                + descentTorchReserve + 3) / 4) * 4;
        giveItemToAtLeast(bot, Items.TORCH, sealedTorchTarget);
        giveItemToAtLeast(bot, Items.COOKED_BEEF, MiningBudget.RARE_BOOTSTRAP_FOOD);
        giveItemToAtLeast(bot, Items.CRAFTING_TABLE, 1);
        giveItemToAtLeast(bot, Items.CHEST, 1);
        // Carry the surface shelter reserve in the slot previously occupied by one generic loot
        // stack. The margin-funded torch/stick pools claim two more slots than the pre-margin
        // carry, so the crowded boundary is now exactly two working slots before the kit seals
        // loot into the mission depot.
        Map<Item, Integer> carriedSupplies = Map.of(
                Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS,
                Items.RAW_GOLD, 1);
        carriedSupplies.forEach((item, count) ->
                giveItemToAtLeast(bot, item, count));
        require(context, freeMainSlots(bot) == 2,
                "crowded final-kit fixture must expose exactly two working slots, got "
                        + freeMainSlots(bot));

        require(context, GoalExecutor.INSTANCE.submit(
                        bot, new Goal.HaveItem(Items.DIAMOND, 64)),
                "diamond64 fresh-kit goal setup failed");
        AtomicBoolean sawRareKit = new AtomicBoolean();
        AtomicBoolean restartedAtVerify = new AtomicBoolean();
        AtomicReference<String> originalMission = new AtomicReference<>();
        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof MiningServiceTask
                    && "RARE_DESCENT_KIT".equals(
                    checkpoint.get("task.service_profile"))) {
                sawRareKit.set(true);
            } else if (sawRareKit.get() && active != null
                    && !(active instanceof DescendToYTask)) {
                context.throwGameTestException(
                        "a task was inserted between RARE_DESCENT_KIT and final descent: "
                                + active.getClass().getSimpleName());
                return;
            }
            if (!restartedAtVerify.get()
                    && "RARE_DESCENT_KIT".equals(
                    checkpoint.get("task.service_profile"))
                    && "VERIFY_MISSION_DEPOT".equals(
                    checkpoint.get("task.phase"))) {
                String missionId = runtime.active().missionId();
                originalMission.set(missionId);
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_rare_kit_verify_restart");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, runtime);
                MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
                require(context, restored.active() != null
                                && missionId.equals(restored.active().missionId()),
                        "VERIFY restore changed target64 mission identity");
                restartedAtVerify.set(true);
                return;
            }
            if (active instanceof DescendToYTask
                    && "DESCEND_TO_Y".equals(checkpoint.get("task_kind"))
                    && "-59".equals(checkpoint.get("task.target_y"))) {
                require(context, sawRareKit.get(),
                        "target64 final descent skipped its rare descent kit");
                require(context, restartedAtVerify.get()
                                && runtime.active().missionId().equals(originalMission.get()),
                        "final descent did not preserve the VERIFY-restarted mission");
                int usableStone = totalUsableStonePickaxeDurability(bot);
                int stoneLike = InventoryAction.countItem(bot, Items.COBBLESTONE)
                        + InventoryAction.countItem(bot, Items.COBBLED_DEEPSLATE);
                require(context, usableStone >= 5 * MiningBudget.STONE_PICKAXE_USABLE_DURABILITY,
                        "final descent started without five fresh stone picks: usable="
                                + usableStone + " checkpoint=" + checkpointSummary(checkpoint));
                require(context, InventoryAction.countItem(bot, Items.STICK)
                                >= MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS
                                && stoneLike >= MiningBudget.RARE_BOOTSTRAP_STONE_LIKE,
                        "final descent started without sealed 284/60 reserve: sticks="
                                + InventoryAction.countItem(bot, Items.STICK)
                                + " stone_like=" + stoneLike);
                require(context, "0".equals(checkpoint.get("rare_resource_retries_used")),
                        "fresh final kit consumed rare resource epoch: "
                                + checkpointSummary(checkpoint));
                String missionId = runtime.active().missionId();
                require(context, MiningServiceTask.ownedMissionDepot(bot, missionId),
                        "final descent lacks its exact mission-owned observable depot");
                var memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
                require(context, memory.recall("mining_depot_owner")
                                .filter(missionId::equals).isPresent(),
                        "mission depot owner fact changed before descent");
                BlockPos depotPos = memory.placeIn(
                                bot.getServerWorld(), "mining_depot")
                        .orElseThrow(() -> new IllegalStateException(
                                "missing mission depot place"));
                Inventory depot = ContainerAction.resolve(bot, depotPos).orElseThrow(
                        () -> new IllegalStateException("mission depot is not a container"));
                carriedSupplies.forEach((item, expected) ->
                        require(context, InventoryAction.countItem(bot, item)
                                        + countItem(depot, item) == expected,
                                "final capacity boundary lost/duplicated " + item));
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 280) {
                context.throwGameTestException(
                        "fresh rare kit never handed off to final descent: active="
                                + (active == null ? "idle" : active.getClass().getSimpleName())
                                + " checkpoint=" + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 320)
    public void ordinarySecondBatchOwnsItsSecondBoundedPhysicalChannelRepair(
            TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context, "OrdinaryTwoRepairGT");
        AIPlayerEntity bot = fixture.bot();
        Goal goal = new Goal.MineOre(Set.of(Blocks.IRON_ORE), 32);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "ordinary two-repair goal setup failed");
        AtomicInteger stage = new AtomicInteger();
        AtomicBoolean sawSecondResupply = new AtomicBoolean();
        AtomicReference<String> missionId = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (runtime.active() != null && missionId.get() == null) {
                missionId.set(runtime.active().missionId());
            }
            if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 290) {
                context.throwGameTestException(
                        "ordinary second bounded repair did not complete: stage=" + stage.get()
                                + " active="
                                + (active == null ? "idle" : active.getClass().getSimpleName())
                                + " checkpoint=" + checkpointSummary(checkpoint));
                return;
            }
            if (stage.get() == 0) {
                if (active instanceof OreDigTask
                        && checkpoint.getOrDefault("task.ore_fingerprint", "")
                        .contains("iron_ore")
                        && Integer.parseInt(checkpoint.getOrDefault(
                        "task.budget_used", "0")) > 0) {
                    // Settle the first logical batch without consuming its optional physical
                    // repair. This reaches the real boundary-16 service deterministically; the
                    // assertion below is specifically that the successor batch owns a fresh,
                    // bounded inventory_service_used=false debit of its own.
                    InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON, 16));
                    stage.set(1);
                }
                return;
            }
            if (stage.get() == 1) {
                if ((active instanceof MiningServiceTask
                        && "16".equals(checkpoint.get("task.service_boundary")))
                        || (active instanceof OreDigTask
                        && "1".equals(checkpoint.get("task.batches"))
                        && "false".equals(checkpoint.get("task.inventory_service_used")))) {
                    // A prepared service can complete in six ticks and bridge to batch two between
                    // GameTest callbacks. The committed batch counter is the same durable proof of
                    // having crossed boundary 16, so accept either observable side of the hand-off.
                    stage.set(2);
                }
                return;
            }
            if (stage.get() == 2) {
                if (active instanceof OreDigTask
                        && InventoryAction.countItem(bot, Items.RAW_IRON) >= 16
                        && "false".equals(checkpoint.get("task.inventory_service_used"))) {
                    // Boundary service opened a new OreDig ledger. Exhausting this pool must spend
                    // the second batch's own debit, not refresh the first batch or enter an
                    // unbounded parent replan. Restore that live ledger at one controlled channel
                    // cell so the next break attempts its missing stone tool immediately; mutating
                    // durability in the middle of BlockMiner's already-open transaction would let
                    // the old break wander through the shared GameTest world before observing it.
                    BlockPos face = bot.getBlockPos().toImmutable();
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            BlockPos cell = face.add(dx, 0, dz);
                            bot.getServerWorld().setBlockState(cell.down(),
                                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);
                            bot.getServerWorld().setBlockState(cell,
                                    Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                            bot.getServerWorld().setBlockState(cell.up(),
                                    Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                        }
                    }
                    bot.getServerWorld().setBlockState(face.north(),
                            Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);
                    bot.getServerWorld().setBlockState(face.north().up(),
                            Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);
                    Map<String, String> controlled = new LinkedHashMap<>(checkpoint);
                    String encodedFace = face.getX() + "," + face.getY() + "," + face.getZ();
                    for (String prefix : Set.of("task.", "mining.")) {
                        controlled.put(prefix + "origin", encodedFace);
                        controlled.put(prefix + "face", encodedFace);
                        controlled.put(prefix + "direction", "0");
                        controlled.put(prefix + "leg", "0");
                        controlled.put(prefix + "steps_left", "1");
                        controlled.put(prefix + "leg_length", "48");
                        clearOrePhysicalLedger(controlled, prefix);
                    }
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_second_ordinary_channel_cell");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, controlled));
                    // Restore while the four-pick pool is still factually healthy so the planner can
                    // reconstruct this exact deep-mine step. Only then exhaust it, before the new
                    // OreDig receives its first tick, to exercise batch two's runtime debit rather
                    // than asking an underground replan to manufacture another bootstrap pool.
                    exhaustStonePickaxes(bot);
                    equipHealthyIronPickaxe(context, bot);
                    stage.set(3);
                }
                return;
            }
            if (stage.get() == 3) {
                if (active instanceof ResupplyTask) {
                    sawSecondResupply.set(true);
                    return;
                }
                if (sawSecondResupply.get() && active instanceof OreDigTask
                        && "true".equals(checkpoint.get("task.inventory_service_used"))) {
                    require(context, runtime.active() != null
                                    && missionId.get().equals(runtime.active().missionId())
                                    && "0".equals(checkpoint.get("lifetime_replans"))
                                    && hasUsableStonePickaxe(bot),
                            "second ordinary batch repair escaped its finite mission budget: "
                                    + checkpointSummary(checkpoint));
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                    context.complete();
                    return;
                }
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 300)
    public void completedChannelToolResupplyRetriesSmallDiamondBatchWithoutParentReplan(
            TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context, "OreChannelRetryGT");
        AIPlayerEntity bot = fixture.bot();
        // This test owns channel-tool service, not branch-boundary behavior. At the north edge of
        // the synthetic open chamber, provide one factual east wall so exhausted stone picks reach
        // the normal physical ResupplyTask instead of a no-fresh-territory terminal boundary.
        BlockPos northBoundary = fixture.face().north(8);
        context.getWorld().setBlockState(
                northBoundary.east(), Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(
                northBoundary.east().up(), Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 1);
        long resultBaseline = GoalExecutor.INSTANCE.lastResult(bot)
                .map(GoalResult::sequence).orElse(0L);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "small diamond goal setup failed");

        AtomicBoolean toolsExhausted = new AtomicBoolean();
        AtomicBoolean sawResupply = new AtomicBoolean();
        AtomicBoolean resumed = new AtomicBoolean();
        AtomicReference<Object> originalTask = new AtomicReference<>();
        AtomicReference<String> originalMission = new AtomicReference<>();
        AtomicReference<String> originalOrigin = new AtomicReference<>();
        AtomicReference<Integer> originalBudget = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);

            if (!toolsExhausted.get()) {
                if (active instanceof OreDigTask
                        && "MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    require(context, ToolTier.canHarvestWithInventory(
                                    bot, Blocks.DIAMOND_ORE.getDefaultState()),
                            "fixture has no healthy target-tier pickaxe");
                    originalTask.set(active);
                    originalMission.set(runtime.active().missionId());
                    originalOrigin.set(checkpoint.get("task.origin"));
                    originalBudget.set(Integer.parseInt(checkpoint.get("task.budget_used")));
                    exhaustStonePickaxes(bot);
                    toolsExhausted.set(true);
                } else if (context.getTick() > 80) {
                    context.throwGameTestException(
                            "small diamond fixture never reached OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            if (active instanceof ResupplyTask) {
                sawResupply.set(true);
                return;
            }
            if (!resumed.get() && active instanceof OreDigTask && active != originalTask.get()) {
                MissionRuntimeRecord after = GoalExecutor.INSTANCE.captureRuntime(bot);
                Map<String, String> restored = after.active().checkpoint();
                require(context, sawResupply.get(),
                        "small channel failure bypassed physical ResupplyTask");
                require(context, originalMission.get().equals(after.active().missionId())
                                && originalOrigin.get().equals(restored.get("task.origin")),
                        "small channel recovery changed mission/checkpoint identity: "
                                + checkpointSummary(restored));
                require(context, Integer.parseInt(restored.get("task.budget_used"))
                                >= originalBudget.get()
                                && "0".equals(restored.get("lifetime_replans")),
                        "small channel recovery refreshed budget or replanned parent: "
                                + checkpointSummary(restored));
                require(context, hasUsableStonePickaxe(bot),
                        "resumed channel has no healthy stone pickaxe");
                resumed.set(true);
                InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND));
                return;
            }

            GoalResult result = GoalExecutor.INSTANCE.resultAfter(bot, resultBaseline).orElse(null);
            if (result != null) {
                require(context, resumed.get()
                                && result.status() == GoalResult.Status.COMPLETED,
                        "small channel retry ended as " + result.status() + ":" + result.reason());
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                context.complete();
            } else if (context.getTick() > 205) {
                context.throwGameTestException(
                        "small channel recovery did not complete: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 140)
    public void activeOreBatchRestoresSameMissionAndBranchCursor(TestContext context) {
        AIPlayerEntity bot = spawnPreparedMiner(context);
        giveRareMissionReadiness(bot, 8);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal), "mining goal setup failed");

        context.runAtTick(35, () -> {
            MissionRuntimeRecord before = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, before.active() != null, "missing active mining mission");
            UUID missionId = UUID.fromString(before.active().missionId());
            Map<String, String> checkpoint = before.active().checkpoint();
            require(context, "MINE_ORE".equals(checkpoint.get("task_kind")),
                    "missing mining task kind: " + checkpointSummary(checkpoint));
            require(context, checkpoint.containsKey("task.origin")
                            && checkpoint.containsKey("task.face")
                            && checkpoint.containsKey("task.leg")
                            && checkpoint.containsKey("task.steps_left"),
                    "incomplete mining cursor: " + checkpointSummary(checkpoint));

            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_restart_boundary");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot, before);

            MissionRuntimeRecord after = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, after.active() != null, "mining mission was not restored");
            require(context, missionId.toString().equals(after.active().missionId()),
                    "mission id changed across restore");
            require(context, GoalExecutor.INSTANCE.isActiveGoal(bot, goal), "original mining goal not active");
            Map<String, String> restored = after.active().checkpoint();
            require(context, checkpoint.get("task.origin").equals(restored.get("task.origin")),
                    "cursor origin changed across restore");
            require(context, checkpoint.get("task.leg").equals(restored.get("task.leg")),
                    "branch leg changed across restore");
            require(context, checkpoint.get("task.steps_left").equals(restored.get("task.steps_left")),
                    "branch progress changed across restore");
        });

        context.runAtTick(55, () -> InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8)));
        context.runAtTick(90, () -> {
            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
            require(context, result.goal().equals(goal), "wrong goal completed");
            require(context, result.status() == GoalResult.Status.COMPLETED,
                    "restored mining mission ended as " + result.status() + ":" + result.reason());
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), bot.getGameProfile().getName());
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 110)
    public void satisfiedGoalRestoresFullyDeliveredOpenOreLedgerBeforeCommit(
            TestContext context) {
        String name = "FullyDeliveredOreLedgerGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        giveRareMissionReadiness(bot, 8);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "fully-delivered mining goal setup failed");
        AtomicBoolean restored = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!restored.get()) {
                if (!"MINE_ORE".equals(checkpoint.get("task_kind"))
                        || Integer.parseInt(checkpoint.getOrDefault(
                        "task.budget_used", "0")) <= 0) {
                    if (context.getTick() > 55) {
                        context.throwGameTestException(
                                "fully-delivered fixture never reached OreDig");
                    }
                    return;
                }
                Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                String face = forged.get("task.face");
                for (String prefix : Set.of("task.", "mining.")) {
                    clearOrePhysicalLedger(forged, prefix);
                    forged.put(prefix + "delivered", "8");
                    forged.put(prefix + "pending_pickup_pos", face);
                    forged.put(prefix + "pending_pickup_last_seen_pos", face);
                    forged.put(prefix + "pending_pickup_inventory", "0");
                    forged.put(prefix + "pending_pickup_started_budget",
                            forged.get(prefix + "budget_used"));
                    forged.put(prefix + "pickup_gain_budget", "-1");
                }
                InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_fully_delivered_ore_ledger");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(runtime, forged));

                MissionRuntimeRecord after = GoalExecutor.INSTANCE.captureRuntime(bot);
                require(context, after.active() != null
                                && "MINE_ORE".equals(
                                after.active().checkpoint().get("task_kind"))
                                && "8".equals(after.active().checkpoint().get(
                                "task.delivered")),
                        "satisfied fast path discarded the fully-delivered open ledger");
                require(context, GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                        "fully-delivered ledger was acknowledged before task commit");
                restored.set(true);
                return;
            }

            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
            if (result != null) {
                require(context, result.status() == GoalResult.Status.COMPLETED
                                && InventoryAction.countItem(bot, Items.DIAMOND) == 8,
                        "fully-delivered ledger did not commit exactly once: "
                                + result.status() + ":" + result.reason());
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (context.getTick() > 90) {
                context.throwGameTestException(
                        "fully-delivered open ledger never committed");
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 100)
    public void activeOreRestoreRejectsSameFamilyWithWrongLogicalBatchCount(
            TestContext context) {
        String name = "WrongOreBatchCountGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        giveRareMissionReadiness(bot, 8);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "wrong-count mining goal setup failed");

        context.runAtTick(35, () -> {
            MissionRuntimeRecord before = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, before.active() != null, "missing active mining mission");
            Map<String, String> forged = new LinkedHashMap<>(before.active().checkpoint());
            require(context, "MINE_ORE".equals(forged.get("task_kind")),
                    "fixture did not reach OreDig: " + checkpointSummary(forged));
            forged.put("task.target_count", "7");
            forged.put("mining.target_count", "7");

            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_wrong_ore_batch_count");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(before, forged));

            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
            require(context, "mission_restore_incompatible_ore_dig_successor"
                            .equals(result.reason()),
                    "same-family wrong-count checkpoint restored as live work: "
                            + result.reason());
            require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                    "wrong-count OreDig checkpoint retained an active mission");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 100)
    public void satisfiedGoalRejectsInvalidActiveOreCheckpoint(TestContext context) {
        String name = "InvalidOreTaskGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        giveRareMissionReadiness(bot, 8);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal), "mining goal setup failed");

        context.runAtTick(35, () -> {
            MissionRuntimeRecord before = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, before.active() != null, "missing active mining mission");
            Map<String, String> checkpoint = new LinkedHashMap<>(before.active().checkpoint());
            require(context, "MINE_ORE".equals(checkpoint.get("task_kind")),
                    "fixture did not reach OreDig: " + checkpointSummary(checkpoint));
            checkpoint.put("task.task_schema", "999");
            MissionRuntimeRecord corrupted = withCheckpoint(before, checkpoint);

            InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_invalid_oredig_restore");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot, corrupted);

            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
            require(context, "mission_restore_invalid_ore_dig_checkpoint".equals(result.reason()),
                    "satisfied restore bypassed invalid OreDig checkpoint: " + result.reason());
            require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                    "invalid OreDig checkpoint restored an active mission");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 100)
    public void satisfiedGoalRejectsInvalidMiningNamespace(TestContext context) {
        String name = "InvalidMiningGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        giveRareMissionReadiness(bot, 8);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal), "mining goal setup failed");

        context.runAtTick(35, () -> {
            MissionRuntimeRecord before = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, before.active() != null, "missing active mining mission");
            Map<String, String> checkpoint = new LinkedHashMap<>(before.active().checkpoint());
            require(context, checkpoint.containsKey("mining.task_schema"),
                    "fixture did not publish mining namespace: " + checkpointSummary(checkpoint));
            checkpoint.put("mining.unexpected", "corrupt");
            MissionRuntimeRecord corrupted = withCheckpoint(before, checkpoint);

            InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_invalid_mining_restore");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot, corrupted);

            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
            require(context, "mission_restore_invalid_mining_checkpoint".equals(result.reason()),
                    "satisfied restore bypassed invalid mining namespace: " + result.reason());
            require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                    "invalid mining namespace restored an active mission");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 180)
    public void bootstrapOrdinaryNamespaceIsDiscardedAtRareBoundaryZeroRestart(
            TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context, "RareBootstrapNamespaceGT");
        AIPlayerEntity bot = fixture.bot();
        giveItemToAtLeast(bot, Items.WOODEN_PICKAXE, 5);
        giveItemToAtLeast(bot, Items.STONE_PICKAXE, 5);
        giveItemToAtLeast(bot, Items.IRON_PICKAXE, 3);
        giveItemToAtLeast(bot, Items.IRON_INGOT, 6);
        // Keep the fixture's original 24-stick craft deficit and log headroom relative to the
        // margin-funded stick reserve so the plan still crafts locally instead of gathering.
        giveItemToAtLeast(bot, Items.STICK,
                MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS - 24);
        giveItemToAtLeast(bot, Items.OAK_LOG,
                56 + EmergencyShelterTask.MAX_PLACEMENT_BLOCKS);
        giveItemToAtLeast(bot, Items.COOKED_BEEF, MiningBudget.RARE_BOOTSTRAP_FOOD);
        giveItemToAtLeast(bot, Items.COBBLESTONE, 160);
        giveItemToAtLeast(bot, Items.FURNACE, 1);
        BlockPos bootstrap = new BlockPos(fixture.face().getX(), 48,
                fixture.face().getZ());
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                context.getWorld().setBlockState(bootstrap.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                for (int dy = 0; dy <= 2; dy++) {
                    context.getWorld().setBlockState(bootstrap.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
        bot.teleport(context.getWorld(), bootstrap.getX() + 0.5D,
                bootstrap.getY(), bootstrap.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "diamond64 bootstrap goal setup failed");
        AtomicInteger stage = new AtomicInteger();
        AtomicReference<Map<String, String>> committedOrdinary = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (stage.get() == 0) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))
                        && "0".equals(checkpoint.get("task.rare_mission_target"))) {
                    Map<String, String> restart = new LinkedHashMap<>(checkpoint);
                    clearOrePhysicalLedger(restart, "task.");
                    clearOrePhysicalLedger(restart, "mining.");
                    committedOrdinary.set(committedOreNamespace(
                            namespace(restart, "task.")));
                    clearCarriedInventory(bot);
                    giveDiamond64Readiness(bot);
                    bot.teleport(context.getWorld(), fixture.face().getX() + 0.5D,
                            fixture.face().getY(), fixture.face().getZ() + 0.5D,
                            Set.of(), 0.0F, 0.0F, true);
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_bootstrap_namespace_handoff");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(runtime, restart));
                    stage.set(1);
                } else if (context.getTick() > 80) {
                    context.throwGameTestException(
                            "bootstrap never reached ordinary OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }
            if (stage.get() == 1) {
                boolean rareBoundaryZero = "MINING_SERVICE".equals(
                        checkpoint.get("task_kind"))
                        && "RARE_ORE_BATCH".equals(
                        checkpoint.get("task.service_profile"))
                        && "0".equals(checkpoint.get("task.service_boundary"));
                if (rareBoundaryZero) {
                    require(context, checkpoint.keySet().stream()
                                    .noneMatch(key -> key.startsWith("mining.")),
                            "safe ordinary handoff rehydrated its discarded namespace: "
                                    + checkpointSummary(checkpoint));
                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    committedOrdinary.get().forEach((key, value) ->
                            forged.put("mining." + key, value));
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_rare_boundary_zero_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, forged));
                    stage.set(2);
                } else if (context.getTick() > 130) {
                    context.throwGameTestException(
                            "fresh plan never reached rare boundary zero: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }
            MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, restored.active() != null,
                    "rare boundary-zero mission rejected a closed ordinary predecessor");
            Map<String, String> after = restored.active().checkpoint();
            require(context, "MINING_SERVICE".equals(after.get("task_kind"))
                            && "RARE_ORE_BATCH".equals(
                            after.get("task.service_profile"))
                            && after.keySet().stream()
                            .noneMatch(key -> key.startsWith("mining.")),
                    "rare boundary-zero restore retained the ordinary namespace: "
                            + checkpointSummary(after));
            Map<String, String> withPhysicalDebt = new LinkedHashMap<>(after);
            Map<String, String> unsafeOrdinary = new LinkedHashMap<>(
                    committedOrdinary.get());
            setOpenBreakLedger(unsafeOrdinary, "");
            unsafeOrdinary.forEach((key, value) ->
                    withPhysicalDebt.put("mining." + key, value));
            TaskManager.INSTANCE.cancelIntentTasks(
                    bot, "gametest_rare_boundary_zero_physical_debt");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(
                    bot, withCheckpoint(restored, withPhysicalDebt));
            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
            require(context, "mission_restore_invalid_mining_checkpoint"
                            .equals(result.reason())
                            && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                    "boundary-zero restore discarded an ordinary active-break ledger: "
                            + result.reason());
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 160)
    public void ordinaryServiceRestoreRejectsWrongMission(TestContext context) {
        withRunningOrdinaryService(context, "OrdinaryWrongMissionGT",
                (fixture, goal, runtime, checkpoint) -> {
                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.put("task.service_mission_id", UUID.randomUUID().toString());
                    AIPlayerEntity bot = fixture.bot();
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_ordinary_wrong_mission");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, forged));
                    GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
                    require(context,
                            "mission_restore_incompatible_mining_service_checkpoint"
                                    .equals(result.reason()),
                            "wrong-mission ordinary service was not isolated: "
                                    + result.reason());
                    require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                            "wrong-mission ordinary service restored an active plan");
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 160)
    public void ordinaryFirstServiceWithoutMiningNamespaceRestoresFromOwnCursor(
            TestContext context) {
        withRunningOrdinaryService(context, "OrdinaryFirstServiceGT",
                (fixture, goal, runtime, checkpoint) -> {
                    require(context, !checkpoint.containsKey("task.pocket_ledger"),
                            "first-service fixture already owns a disposal ledger");
                    Map<String, String> legacyFirst = new LinkedHashMap<>(checkpoint);
                    legacyFirst.keySet().removeIf(key -> key.startsWith("mining."));
                    AIPlayerEntity bot = fixture.bot();
                    String missionId = runtime.active().missionId();
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_ordinary_first_service_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, legacyFirst));
                    MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restored.active() != null
                                    && missionId.equals(restored.active().missionId()),
                            "first ordinary service lost mission identity");
                    Map<String, String> after = restored.active().checkpoint();
                    require(context, "MINING_SERVICE".equals(after.get("task_kind"))
                                    && "ORE_BATCH".equals(
                                    after.get("task.service_profile"))
                                    && after.keySet().stream()
                                    .noneMatch(key -> key.startsWith("mining.")),
                            "first ordinary service without mining namespace did not restore: "
                                    + checkpointSummary(after));
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 180)
    public void interBatchServiceRestoreSurvivesFailedFreshPlan(TestContext context) {
        withRunningOrdinaryService(context, "OrdinaryFailedFreshPlanGT",
                (fixture, goal, runtime, checkpoint) -> {
                    Map<String, String> service = new LinkedHashMap<>(
                            namespace(checkpoint, "task."));
                    for (String key : Set.of(
                            "pocket_entry", "pocket_sink", "pocket_direction",
                            "pocket_entities", "pocket_lineage", "pocket_baseline",
                            "pocket_ledger", "pocket_drop_committed",
                            "pocket_ledger_verified", "pocket_phase_started",
                            "pocket_failure", "pocket_clear_index")) {
                        service.remove(key);
                    }
                    service.put("phase", "PREPARE");
                    require(context, MiningServiceTask.inspectCheckpoint(service).isPresent()
                                    && !GoalExecutor.hasActiveServicePocket(service),
                            "failed-fresh-plan fixture did not produce a resumable non-pocket service");

                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.keySet().removeIf(key -> key.startsWith("task."));
                    service.forEach((key, value) -> forged.put("task." + key, value));
                    AIPlayerEntity bot = fixture.bot();
                    clearCarriedInventory(bot);
                    GoalPlanner.GoalPlan fresh = GoalPlanner.plan(bot, goal);
                    require(context, !fresh.success(),
                            "failed-fresh-plan fixture unexpectedly remained plannable: "
                                    + fresh.steps());

                    String missionId = runtime.active().missionId();
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_ordinary_failed_fresh_plan_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, forged));

                    Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
                    MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
                    Map<String, String> after = restored.active() == null
                            ? Map.of() : restored.active().checkpoint();
                    require(context, active instanceof MiningServiceTask
                                    && restored.active() != null
                                    && missionId.equals(restored.active().missionId())
                                    && "MINING_SERVICE".equals(after.get("task_kind"))
                                    && "ORE_BATCH".equals(after.get("task.service_profile"))
                                    && namespace(checkpoint, "mining.").equals(
                                    namespace(after, "mining."))
                                    && GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                            "failed fresh plan discarded an exact interrupted service: "
                                    + checkpointSummary(after));
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 180)
    public void terminalOrdinaryHandoffRestoresWithoutARepeatedOreSuccessor(
            TestContext context) {
        withRunningOrdinaryService(context, "TerminalOrdinaryHandoffGT",
                (fixture, goal, runtime, checkpoint) -> {
                    require(context, "false".equals(checkpoint.get("mining.batch_open"))
                                    && checkpoint.containsKey("mining.face"),
                            "terminal handoff fixture lacks a closed exact mining namespace: "
                                    + checkpointSummary(checkpoint));
                    AIPlayerEntity bot = fixture.bot();
                    giveItemToAtLeast(bot, Items.RAW_IRON, 32);

                    Map<String, String> terminal = new LinkedHashMap<>(checkpoint);
                    terminal.put("task.channel_tools", "false");
                    terminal.put("task.channel_tool_usable", "0");
                    terminal.put("task.emergency_blocks_reserved", "28");
                    String missionId = runtime.active().missionId();
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_terminal_ordinary_handoff_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, terminal));

                    MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restored.active() != null
                                    && missionId.equals(restored.active().missionId()),
                            "terminal ordinary handoff was discarded after its ore became satisfied");
                    Map<String, String> after = restored.active().checkpoint();
                    require(context, "MINING_SERVICE".equals(after.get("task_kind"))
                                    && "ORE_BATCH".equals(after.get("task.service_profile"))
                                    && "false".equals(after.get("task.channel_tools"))
                                    && "28".equals(after.get(
                                    "task.emergency_blocks_reserved"))
                                    && "false".equals(after.get("mining.batch_open")),
                            "terminal handoff did not restore service-first with its closed cursor: "
                                    + checkpointSummary(after));
                    require(context, GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                            "terminal handoff restore published a premature result");
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "failedPrimaryServiceIsolation", tickLimit = 220)
    public void failedNonPocketPrimaryServiceReplansWithoutStaleReplay(
            TestContext context) {
        String name = "FailedPrimaryServiceGT";
        withRunningOrdinaryService(context, name,
                (fixture, ignoredGoal, runtime, checkpoint) -> {
                    Map<String, String> closedPrimary =
                            namespace(checkpoint, "mining.");
                    require(context,
                            OreDigTask.inspectCheckpoint(closedPrimary, 0)
                                    .filter(metadata -> !metadata.batchOpen())
                                    .isPresent(),
                            "failed-primary fixture lacks a closed ordinary parent");

                    Map<String, String> failedService = new LinkedHashMap<>(
                            namespace(checkpoint, "task."));
                    for (String key : Set.of(
                            "pocket_entry", "pocket_sink", "pocket_direction",
                            "pocket_entities", "pocket_lineage", "pocket_baseline",
                            "pocket_ledger", "pocket_drop_committed",
                            "pocket_ledger_verified", "pocket_phase_started",
                            "pocket_failure", "pocket_clear_index")) {
                        failedService.remove(key);
                    }
                    failedService.put("phase", "PREPARE");
                    failedService.put("budget_used", String.valueOf(
                            MiningMissionBudget.SERVICE_HARD_WINDOW_TICKS));
                    failedService.put("last_progress_budget", "0");
                    require(context,
                            MiningServiceTask.inspectCheckpoint(failedService).isPresent()
                                    && GoalExecutor.failedClosedAuxiliaryServiceMatches(
                                    failedService, closedPrimary),
                            "failed-primary fixture did not bind its exact closed parent");
                    Map<String, String> terminalFailedService = withServicePolicy(
                            failedService,
                            MiningServiceTask.ServicePolicy.defaultOre(false));
                    String settledFailure =
                            "mining_service_disposal_ore_preserved:minecraft:diamond_ore";
                    terminalFailedService = new LinkedHashMap<>(terminalFailedService);
                    terminalFailedService.put("terminal_failure", settledFailure);
                    require(context,
                            MiningServiceTask.inspectCheckpoint(terminalFailedService)
                            .filter(metadata -> settledFailure.equals(
                            metadata.terminalFailure())).isPresent()
                                    && GoalExecutor.failedClosedAuxiliaryServiceMatches(
                                    terminalFailedService, closedPrimary),
                            "failed-primary fixture could not bind its terminal service policy");

                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.keySet().removeIf(key -> key.startsWith("task.")
                            || key.startsWith("mining.")
                            || key.startsWith("aux_mining."));
                    forged.put("task_kind", "MINING_SERVICE");
                    terminalFailedService.forEach((key, value) ->
                            forged.put("task." + key, value));
                    closedPrimary.forEach((key, value) ->
                            forged.put("mining." + key, value));
                    forged.remove("capacity_parent");

                    AIPlayerEntity bot = fixture.bot();
                    clearCarriedInventory(bot);
                    giveDiamond64Readiness(bot);
                    InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 16));
                    Goal longRareGoal = new Goal.HaveItem(Items.DIAMOND, 64);
                    MissionRecord prior = runtime.active();
                    MissionRuntimeRecord restart = new MissionRuntimeRecord(
                            new MissionRecord(prior.missionId(),
                                    MissionSpec.fromGoal(longRareGoal),
                                    Map.copyOf(forged)),
                            runtime.queue(), runtime.userPaused());
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_failed_primary_service_restore");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, restart);

                    Task service = TaskManager.INSTANCE.getActive(bot).orElse(null);
                    require(context, service instanceof MiningServiceTask,
                            "failed primary service was not restored first");
                    require(context, service.state() == TaskState.FAILED
                                    && settledFailure.equals(service.failureReason()),
                            "non-pocket primary service did not restore its typed failure: "
                                    + service.state() + ":" + service.failureReason());

                    // Reproduce the exact persistence window: TaskManager has removed the failed
                    // mission task, a safety owner occupies the slot, and GoalExecutor therefore
                    // cannot settle the failure before the periodic runtime snapshot.
                    TaskManager.INSTANCE.abort(bot);
                    TaskManager.INSTANCE.assign(bot, new HoldTask(),
                            TaskOrigin.safety("gametest_terminal_failure_capture_window"));
                    GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);
                    MissionRuntimeRecord capturedBeforeHandler =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, capturedBeforeHandler.active() != null
                                    && TaskManager.INSTANCE.getActive(bot)
                                    .filter(HoldTask.class::isInstance).isPresent()
                                    && settledFailure.equals(
                                    capturedBeforeHandler.active().checkpoint().get(
                                    "task.terminal_failure"))
                                    && "PREPARE".equals(
                                    capturedBeforeHandler.active().checkpoint().get(
                                    "task.phase"))
                                    && capturedBeforeHandler.active().checkpoint().keySet()
                                    .stream().noneMatch(key -> key.startsWith("task.pocket_")),
                            "capture-before-handler lost or relabeled the terminal receipt: "
                                    + checkpointSummary(
                                    capturedBeforeHandler.active().checkpoint()));

                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_terminal_failure_process_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, capturedBeforeHandler);
                    Task replayedFailure = TaskManager.INSTANCE.getActive(bot).orElse(null);
                    require(context, replayedFailure instanceof MiningServiceTask
                                    && replayedFailure.state() == TaskState.FAILED
                                    && settledFailure.equals(
                                    replayedFailure.failureReason()),
                            "process restart replayed PREPARE instead of terminal service: "
                                    + (replayedFailure == null ? "missing"
                                    : replayedFailure.state() + ":"
                                    + replayedFailure.failureReason()));
                    MissionRuntimeRecord recaptured =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, recaptured.active() != null
                                    && settledFailure.equals(
                                    recaptured.active().checkpoint().get(
                                    "task.terminal_failure"))
                                    && capturedBeforeHandler.active().checkpoint().get(
                                    "task.budget_used").equals(
                                    recaptured.active().checkpoint().get(
                                    "task.budget_used"))
                                    && capturedBeforeHandler.active().checkpoint().get(
                                    "lifetime_replans").equals(
                                    recaptured.active().checkpoint().get(
                                    "lifetime_replans")),
                            "repeated capture changed terminal receipt budget or replan count");

                    TaskManager.INSTANCE.abort(bot);
                    GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);

                    MissionRuntimeRecord replanned =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, replanned.active() != null
                                    && GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                            "closed primary service failure escaped generic replan");
                    Map<String, String> after = replanned.active().checkpoint();
                    require(context,
                            !terminalFailedService.equals(namespace(after, "task."))
                                    && !closedPrimary.equals(namespace(after, "mining."))
                                    && after.keySet().stream().noneMatch(
                                    key -> key.startsWith("aux_mining.")),
                            "failed primary service or closed cursor survived cleanup: "
                                    + checkpointSummary(after));

                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_failed_primary_service_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, replanned);
                    MissionRuntimeRecord restored =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restored.active() != null
                                    && GoalExecutor.INSTANCE.lastResult(bot).isEmpty()
                                    && !terminalFailedService.equals(namespace(
                                    restored.active().checkpoint(), "task."))
                                    && !closedPrimary.equals(namespace(
                                    restored.active().checkpoint(), "mining.")),
                            "restart replayed failed primary service or its stale cursor");
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "terminalServiceGuardSlotRepair", tickLimit = 40)
    public void terminalServiceGuardAllowsSlotRepairAndRemainsDurable(
            TestContext context) {
        context.runAtTick(1, () -> {
            String failure =
                    "mining_service_disposal_ore_preserved:minecraft:diamond_ore";
            GuardFixture fixture = settleBoundaryZeroGuard(
                    context, "TerminalGuardSlotRepairGT", failure);
            AIPlayerEntity bot = fixture.bot();
            MissionRuntimeRecord guarded = fixture.guardedRuntime();
            Map<String, String> checkpoint = guarded.active().checkpoint();
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            require(context, active instanceof MiningServiceTask
                            && "1".equals(checkpoint.get("settled_service.count"))
                            && failure.equals(checkpoint.get(
                            "settled_service.0.failure")),
                    "slot-repaired service did not retain its guard: "
                            + checkpointSummary(checkpoint));

            active.tick(bot);
            require(context, !failure.equals(active.failureReason()),
                    "guard rejected the same geometry despite sufficient slots");
            MissionRuntimeRecord afterTick =
                    GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, afterTick.active() != null
                            && "1".equals(afterTick.active().checkpoint().get(
                            "settled_service.count"))
                            && failure.equals(afterTick.active().checkpoint().get(
                            "settled_service.0.failure")),
                    "successful slot attestation pruned the durable guard");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "terminalServiceGuardRepairRestart", tickLimit = 220)
    public void terminalServiceGuardSurvivesCraftRestartAndBlocksWithoutMutation(
            TestContext context) {
        String name = "TerminalGuardRepairRestartGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        giveRareMissionReadiness(bot, 8);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        String failure =
                "mining_service_disposal_ore_preserved:minecraft:diamond_ore";
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "repair guard setup failed");
        AtomicBoolean restartedOnRepair = new AtomicBoolean();

        context.runAtTick(1, () -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, runtime.active() != null
                            && "MINING_SERVICE".equals(
                            runtime.active().checkpoint().get("task_kind")),
                    "repair fixture did not start at boundary-zero service");
            require(context, InventoryAction.removeItems(bot, Items.IRON_PICKAXE, 1),
                    "repair fixture could not remove one target pickaxe");
            InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 3));
            GoalPlanner.GoalPlan fresh = GoalPlanner.plan(bot, goal);
            require(context, fresh.success() && !fresh.steps().isEmpty()
                            && fresh.steps().getFirst().kind() == GoalStep.Kind.CRAFT
                            && fresh.steps().getFirst().item() == Items.IRON_PICKAXE
                            && fresh.steps().stream().anyMatch(GoalStep::isRareOreService),
                    "fixture did not create a real CraftTask prefix: "
                            + fresh.describeSteps());

            Map<String, String> terminal = terminalServiceCheckpoint(
                    runtime.active().checkpoint(), failure);
            TaskManager.INSTANCE.cancelIntentTasks(
                    bot, "gametest_terminal_guard_repair_restore");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(runtime, terminal));
            Task receipt = TaskManager.INSTANCE.getActive(bot).orElse(null);
            require(context, receipt instanceof MiningServiceTask
                            && receipt.state() == TaskState.FAILED
                            && failure.equals(receipt.failureReason()),
                    "repair fixture did not restore the typed terminal receipt");

            TaskManager.INSTANCE.abort(bot);
            GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);
            MissionRuntimeRecord onRepair = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, onRepair.active() != null
                            && TaskManager.INSTANCE.getActive(bot)
                            .filter(CraftTask.class::isInstance).isPresent()
                            && "1".equals(onRepair.active().checkpoint().get(
                            "settled_service.count"))
                            && failure.equals(onRepair.active().checkpoint().get(
                            "settled_service.0.failure"))
                            && onRepair.active().checkpoint().keySet().stream()
                            .noneMatch(key -> key.equals("task.terminal_failure")),
                    "receipt did not become a repair-spanning guard: "
                            + (onRepair.active() == null ? "missing"
                            : checkpointSummary(onRepair.active().checkpoint())));

            TaskManager.INSTANCE.cancelIntentTasks(
                    bot, "gametest_terminal_guard_repair_process_restart");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot, onRepair);
            MissionRuntimeRecord restarted = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, restarted.active() != null
                            && TaskManager.INSTANCE.getActive(bot)
                            .filter(CraftTask.class::isInstance).isPresent()
                            && failure.equals(restarted.active().checkpoint().get(
                            "settled_service.0.failure")),
                    "process restart lost the guard or skipped the real repair");
            restartedOnRepair.set(true);
        });

        context.runAtEveryTick(() -> {
            if (!restartedOnRepair.get()) {
                return;
            }
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof MiningServiceTask service) {
                fillWithGlassUntilFreeSlots(bot, 0);
                require(context, freeMainSlots(bot) == 0,
                        "repair fixture did not preserve a real slot deficit");
                MissionRuntimeRecord before =
                        GoalExecutor.INSTANCE.captureRuntime(bot);
                require(context, before.active() != null,
                        "guarded service lost its mission before PREPARE");
                Map<BlockPos, net.minecraft.block.BlockState> worldBefore =
                        snapshotGuardGeometry(bot, before.active().checkpoint());
                Vec3d positionBefore = bot.getPos();
                InventorySnapshot inventoryBefore = snapshotInventory(bot);

                service.tick(bot);
                require(context, service.state() == TaskState.FAILED
                                && failure.equals(service.failureReason())
                                && bot.getPos().squaredDistanceTo(positionBefore) < 0.000001D
                                && inventoryBefore.matches(bot)
                                && worldBefore.equals(snapshotGuardGeometry(
                                bot, before.active().checkpoint())),
                        "guard hit moved the bot or mutated its pocket geometry");
                TaskManager.INSTANCE.abort(bot);
                GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);
                GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
                require(context, result != null
                                && failure.equals(result.reason())
                                && InventoryAction.countItem(
                                bot, Items.IRON_PICKAXE) >= 2
                                && !GoalExecutor.INSTANCE.hasActivePlan(bot)
                                && TaskManager.INSTANCE.getActive(bot).isEmpty(),
                        "guard hit entered generic replan or lost the typed reason: "
                                + (result == null ? "missing" : result.reason()));
                restartedOnRepair.set(false);
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
                return;
            }
            if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 190) {
                context.throwGameTestException(
                        "CraftTask never reached the guarded service");
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "terminalServiceGuardGeometry", tickLimit = 30)
    public void foreignServiceIsBlockedButRotatedAxisIsAllowed(
            TestContext context) {
        context.runAtTick(1, () -> {
            String name = "TerminalGuardGeometryGT";
            AIPlayerEntity bot = spawnPreparedMiner(context, name);
            BlockPos face = bot.getBlockPos().toImmutable();
            MiningCursor original = MiningCursor.initial(face, 48)
                    .withStrip(0, 0, 12, 48);
            MiningCursor rotated = original.withStrip(1, 0, 12, 48);
            MiningServiceTask.DisposalGeometry geometry =
                    MiningServiceTask.DisposalGeometry.fromCursor(original)
                            .orElseThrow();
            String failure =
                    "mining_service_disposal_ore_preserved:minecraft:diamond_ore";
            MiningServiceTask.DisposalReplayGuard guard =
                    new MiningServiceTask.DisposalReplayGuard(
                            bot.getServerWorld().getRegistryKey().getValue().toString(),
                            geometry, failure);
            fillWithGlassUntilFreeSlots(bot, 0);
            Map<BlockPos, net.minecraft.block.BlockState> worldBefore =
                    snapshotGeometry(bot, geometry);
            Vec3d positionBefore = bot.getPos();
            InventorySnapshot inventoryBefore = snapshotInventory(bot);

            MiningServiceTask foreign = new MiningServiceTask(
                    Set.of(Blocks.COAL_ORE), Map.of(),
                    MiningServiceTask.ServicePolicy.defaultOre(false),
                    0, "foreign-service-geometry", 0, original,
                    java.util.List.of(guard));
            foreign.start(bot);
            foreign.tick(bot);
            require(context, foreign.state() == TaskState.FAILED
                            && failure.equals(foreign.failureReason())
                            && bot.getPos().squaredDistanceTo(positionBefore) < 0.000001D
                            && inventoryBefore.matches(bot)
                            && worldBefore.equals(snapshotGeometry(bot, geometry)),
                    "foreign service escaped or mutated the shared physical guard");

            MiningServiceTask rotatedService = new MiningServiceTask(
                    Set.of(Blocks.COAL_ORE), Map.of(),
                    MiningServiceTask.ServicePolicy.defaultOre(false),
                    0, "rotated-service-geometry", 0, rotated,
                    java.util.List.of(guard));
            rotatedService.start(bot);
            rotatedService.tick(bot);
            require(context, !failure.equals(rotatedService.failureReason())
                            && GoalExecutor.hasActiveServicePocket(
                            rotatedService.checkpoint()),
                    "90-degree cursor rotation was incorrectly blocked by the old axis");
            rotatedService.abort(bot);
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "terminalServiceGuardCodec", tickLimit = 80)
    public void settledServiceGuardNamespaceRestoresFailClosed(
            TestContext context) {
        context.runAtTick(1, () -> {
            String failure =
                    "mining_service_disposal_ore_preserved:minecraft:diamond_ore";
            for (String variant : java.util.List.of(
                    "partial", "unknown", "root", "naked_dot", "duplicate",
                    "count_overflow", "noncanonical_count",
                    "noncanonical_coordinate", "noncanonical_descriptor",
                    "mission_mismatch", "active_dimension_mismatch")) {
                GuardFixture fixture = settleBoundaryZeroGuard(
                        context, "GuardCodec" + variant, failure);
                AIPlayerEntity bot = fixture.bot();
                Map<String, String> forged = new LinkedHashMap<>(
                        fixture.guardedRuntime().active().checkpoint());
                switch (variant) {
                    case "partial" -> forged.remove(
                            "settled_service.0.failure");
                    case "unknown" -> forged.put(
                            "settled_service.0.future", "1");
                    case "root" -> {
                        forged.keySet().removeIf(
                                key -> key.startsWith("settled_service."));
                        forged.put("settled_service", "1");
                    }
                    case "naked_dot" -> {
                        forged.keySet().removeIf(
                                key -> key.startsWith("settled_service."));
                        forged.put("settled_service.", "1");
                    }
                    case "duplicate" -> duplicateGuardEntry(forged);
                    case "count_overflow" ->
                            forged.put("settled_service.count", "17");
                    case "noncanonical_count" ->
                            forged.put("settled_service.count", "01");
                    case "noncanonical_coordinate" -> forged.put(
                            "settled_service.0.work_face",
                            nonCanonicalPos(forged.get(
                                    "settled_service.0.work_face")));
                    case "noncanonical_descriptor" -> {
                        String descriptor = forged.get(
                                "settled_service.0.descriptor");
                        String[] parts = descriptor.split("\\|", -1);
                        parts[12] = "TRUE";
                        forged.put("settled_service.0.descriptor",
                                String.join("|", parts));
                    }
                    case "mission_mismatch" -> {
                        String descriptor = forged.get(
                                "settled_service.0.descriptor");
                        String[] parts = descriptor.split("\\|", -1);
                        parts[2] = UUID.randomUUID().toString();
                        forged.put("settled_service.0.descriptor",
                                String.join("|", parts));
                    }
                    case "active_dimension_mismatch" -> forged.put(
                            "task.service_dimension",
                            "minecraft:the_nether");
                    default -> throw new IllegalStateException(
                            "unknown variant " + variant);
                }

                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_guard_codec_" + variant);
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(
                        bot, withCheckpoint(
                                fixture.guardedRuntime(), Map.copyOf(forged)));
                GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
                String expectedReason = "active_dimension_mismatch".equals(variant)
                        ? "mission_restore_invalid_mining_service_checkpoint"
                        : "mission_restore_invalid_settled_service_tombstone";
                require(context, result != null
                                && expectedReason.equals(result.reason())
                                && !GoalExecutor.INSTANCE.hasActivePlan(bot)
                                && TaskManager.INSTANCE.getActive(bot).isEmpty(),
                        "guard decoder accepted " + variant + ": "
                                + (result == null ? "missing" : result.reason()));
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
            }

            GuardFixture cursorless = settleBoundaryZeroGuard(
                    context, "GuardCodecCursorlessReceipt", failure);
            AIPlayerEntity cursorlessBot = cursorless.bot();
            Map<String, String> cursorlessReceipt = new LinkedHashMap<>(
                    terminalServiceCheckpoint(
                            cursorless.originalRuntime().active().checkpoint(), failure));
            cursorlessReceipt.keySet().removeIf(
                    key -> key.startsWith("task.cursor_"));
            cursorlessReceipt.keySet().removeIf(
                    key -> key.startsWith("settled_service."));
            TaskManager.INSTANCE.cancelIntentTasks(
                    cursorlessBot, "gametest_guard_cursorless_receipt");
            GoalExecutor.INSTANCE.unload(cursorlessBot);
            GoalExecutor.INSTANCE.restoreRuntime(cursorlessBot,
                    withCheckpoint(cursorless.originalRuntime(),
                            Map.copyOf(cursorlessReceipt)));
            GoalResult cursorlessResult =
                    GoalExecutor.INSTANCE.lastResult(cursorlessBot).orElse(null);
            require(context, cursorlessResult != null
                            && "mission_restore_invalid_mining_service_checkpoint"
                            .equals(cursorlessResult.reason()),
                    "cursor-less terminal receipt passed strict service decode");
            AIPlayerManager.INSTANCE.despawn(
                    cursorlessBot.getServer(), cursorless.name());
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "terminalServiceGuardRestoreCompatibility", tickLimit = 80)
    public void settledServiceGuardRestoreCompatibilityIsStrict(
            TestContext context) {
        context.runAtTick(1, () -> {
            String failure =
                    "mining_service_disposal_ore_preserved:minecraft:diamond_ore";

            GuardFixture unrelated = settleBoundaryZeroGuard(
                    context, "GuardRestoreUnrelatedGT", failure);
            Map<String, String> rotated = new LinkedHashMap<>(
                    unrelated.guardedRuntime().active().checkpoint());
            BlockPos oldFace = decodePos(rotated.get(
                    "settled_service.0.work_face"));
            BlockPos unrelatedFace = oldFace.east();
            unrelated.bot().getServerWorld().setBlockState(unrelatedFace,
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            unrelated.bot().getServerWorld().setBlockState(unrelatedFace.up(),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            unrelated.bot().getServerWorld().setBlockState(unrelatedFace.down(),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);
            unrelated.bot().teleport(unrelated.bot().getServerWorld(),
                    unrelatedFace.getX() + 0.5D, unrelatedFace.getY(),
                    unrelatedFace.getZ() + 0.5D, Set.of(),
                    0.0F, 0.0F, true);
            rotated.put("task.work_face", encodePos(unrelatedFace));
            rotated.put("task.cursor_origin", encodePos(unrelatedFace));
            rotated.put("task.cursor_face", encodePos(unrelatedFace));
            TaskManager.INSTANCE.cancelIntentTasks(
                    unrelated.bot(), "gametest_guard_unrelated_active");
            GoalExecutor.INSTANCE.unload(unrelated.bot());
            GoalExecutor.INSTANCE.restoreRuntime(unrelated.bot(),
                    withCheckpoint(unrelated.guardedRuntime(), Map.copyOf(rotated)));
            MissionRuntimeRecord rotatedRuntime =
                    GoalExecutor.INSTANCE.captureRuntime(unrelated.bot());
            require(context, rotatedRuntime.active() != null
                            && TaskManager.INSTANCE.getActive(unrelated.bot())
                            .filter(task -> task instanceof MiningServiceTask
                                    && task.state() == TaskState.RUNNING).isPresent()
                            && "1".equals(rotatedRuntime.active().checkpoint().get(
                            "settled_service.count")),
                    "unrelated non-pocket active service was rejected or cleared guard");
            AIPlayerManager.INSTANCE.despawn(
                    unrelated.bot().getServer(), unrelated.name());

            GuardFixture pocket = settleBoundaryZeroGuard(
                    context, "GuardRestorePocketGT", failure);
            Map<String, String> activePocket = withOpenGuardedPocket(
                    pocket.guardedRuntime().active().checkpoint());
            require(context, MiningServiceTask.inspectCheckpoint(
                            namespace(activePocket, "task.")).isPresent()
                            && GoalExecutor.hasActiveServicePocket(
                            namespace(activePocket, "task.")),
                    "active-pocket fixture is not a valid service checkpoint");
            TaskManager.INSTANCE.cancelIntentTasks(
                    pocket.bot(), "gametest_guard_active_pocket");
            GoalExecutor.INSTANCE.unload(pocket.bot());
            GoalExecutor.INSTANCE.restoreRuntime(pocket.bot(),
                    withCheckpoint(pocket.guardedRuntime(), activePocket));
            MissionRuntimeRecord pocketQuarantine =
                    GoalExecutor.INSTANCE.captureRuntime(pocket.bot());
            require(context, pocketQuarantine.active() != null
                            && GoalExecutor.INSTANCE.hasActivePlan(pocket.bot())
                            && TaskManager.INSTANCE.getActive(pocket.bot()).isEmpty()
                            && GoalExecutor.INSTANCE.lastResult(pocket.bot()).isEmpty()
                            && namespace(activePocket, "task.").equals(namespace(
                            pocketQuarantine.active().checkpoint(), "task.")),
                    "guarded active pocket was not quarantined byte-exact");
            GoalExecutor.INSTANCE.cancelAll(pocket.bot());
            AIPlayerManager.INSTANCE.despawn(
                    pocket.bot().getServer(), pocket.name());

            GuardFixture duplicate = settleBoundaryZeroGuard(
                    context, "GuardRestoreCrashWindowGT", failure);
            Map<String, String> duplicateReceipt = replaceTaskCheckpoint(
                    duplicate.guardedRuntime().active().checkpoint(),
                    namespace(terminalServiceCheckpoint(
                            duplicate.originalRuntime().active().checkpoint(),
                            failure), "task."),
                    GoalStep.Kind.MINING_SERVICE);
            TaskManager.INSTANCE.cancelIntentTasks(
                    duplicate.bot(), "gametest_guard_duplicate_receipt");
            GoalExecutor.INSTANCE.unload(duplicate.bot());
            GoalExecutor.INSTANCE.restoreRuntime(duplicate.bot(),
                    withCheckpoint(duplicate.guardedRuntime(), duplicateReceipt));
            MissionRuntimeRecord merged =
                    GoalExecutor.INSTANCE.captureRuntime(duplicate.bot());
            require(context, merged.active() != null
                            && merged.active().checkpoint().keySet().stream()
                            .noneMatch(key -> key.equals("task.terminal_failure"))
                            && failure.equals(merged.active().checkpoint().get(
                            "settled_service.0.failure"))
                            && GoalExecutor.INSTANCE.lastResult(
                            duplicate.bot()).isEmpty(),
                    "exact crash-window receipt was not merged idempotently");
            AIPlayerManager.INSTANCE.despawn(
                    duplicate.bot().getServer(), duplicate.name());

            GuardFixture driftedDuplicate = settleBoundaryZeroGuard(
                    context, "GuardRestoreDriftedCrashWindowGT", failure);
            Map<String, String> driftedReceipt = replaceTaskCheckpoint(
                    driftedDuplicate.guardedRuntime().active().checkpoint(),
                    namespace(terminalServiceCheckpoint(
                            driftedDuplicate.originalRuntime().active().checkpoint(),
                            failure), "task."),
                    GoalStep.Kind.MINING_SERVICE);
            BlockPos driftedFace = decodePos(driftedReceipt.get("task.work_face"));
            var driftedNether = driftedDuplicate.bot().getServer().getWorld(
                    net.minecraft.world.World.NETHER);
            require(context, driftedNether != null, "fixture has no Nether world");
            TaskManager.INSTANCE.cancelIntentTasks(
                    driftedDuplicate.bot(), "gametest_guard_drifted_duplicate");
            GoalExecutor.INSTANCE.unload(driftedDuplicate.bot());
            driftedDuplicate.bot().teleport(driftedNether,
                    driftedFace.getX() + 0.5D, driftedFace.getY(),
                    driftedFace.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
            GoalExecutor.INSTANCE.restoreRuntime(driftedDuplicate.bot(),
                    withCheckpoint(
                            driftedDuplicate.guardedRuntime(), driftedReceipt));
            GoalResult driftedResult = GoalExecutor.INSTANCE.lastResult(
                    driftedDuplicate.bot()).orElse(null);
            require(context, driftedResult != null
                            && failure.equals(driftedResult.reason())
                            && !GoalExecutor.INSTANCE.hasActivePlan(
                            driftedDuplicate.bot()),
                    "cross-dimension exact merge continued its parent plan");
            AIPlayerManager.INSTANCE.despawn(
                    driftedDuplicate.bot().getServer(), driftedDuplicate.name());

            GuardFixture conflict = settleBoundaryZeroGuard(
                    context, "GuardRestoreConflictGT", failure);
            String conflictingFailure =
                    "mining_service_disposal_ore_preserved:minecraft:coal_ore";
            Map<String, String> conflictingReceipt = replaceTaskCheckpoint(
                    conflict.guardedRuntime().active().checkpoint(),
                    namespace(terminalServiceCheckpoint(
                            conflict.originalRuntime().active().checkpoint(),
                            conflictingFailure), "task."),
                    GoalStep.Kind.MINING_SERVICE);
            TaskManager.INSTANCE.cancelIntentTasks(
                    conflict.bot(), "gametest_guard_conflicting_receipt");
            GoalExecutor.INSTANCE.unload(conflict.bot());
            GoalExecutor.INSTANCE.restoreRuntime(conflict.bot(),
                    withCheckpoint(conflict.guardedRuntime(), conflictingReceipt));
            GoalResult conflictResult =
                    GoalExecutor.INSTANCE.lastResult(conflict.bot()).orElse(null);
            require(context, conflictResult != null
                            && "mission_restore_invalid_settled_service_tombstone"
                            .equals(conflictResult.reason()),
                    "conflicting same-geometry authority was accepted");
            AIPlayerManager.INSTANCE.despawn(
                    conflict.bot().getServer(), conflict.name());
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "terminalServiceReceiptCrossDimension", tickLimit = 40)
    public void terminalReceiptKeepsOriginalReasonAcrossDimensionDrift(
            TestContext context) {
        context.runAtTick(1, () -> {
            String name = "GuardReceiptDimensionGT";
            AIPlayerEntity bot = spawnPreparedMiner(context, name);
            giveRareMissionReadiness(bot, 8);
            Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
            require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                    "cross-dimension receipt fixture submit failed");
            MissionRuntimeRecord original = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, original.active() != null
                            && "MINING_SERVICE".equals(
                            original.active().checkpoint().get("task_kind")),
                    "cross-dimension fixture lacks service");
            String failure =
                    "mining_service_disposal_ore_preserved:minecraft:diamond_ore";
            Map<String, String> terminal = terminalServiceCheckpoint(
                    original.active().checkpoint(), failure);
            BlockPos face = decodePos(terminal.get("task.work_face"));
            var nether = bot.getServer().getWorld(net.minecraft.world.World.NETHER);
            require(context, nether != null, "fixture has no Nether world");
            TaskManager.INSTANCE.cancelIntentTasks(
                    bot, "gametest_cross_dimension_receipt");
            GoalExecutor.INSTANCE.unload(bot);
            bot.teleport(nether, face.getX() + 0.5D, face.getY(),
                    face.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
            GoalExecutor.INSTANCE.restoreRuntime(
                    bot, withCheckpoint(original, terminal));
            Task receipt = TaskManager.INSTANCE.getActive(bot).orElse(null);
            require(context, receipt instanceof MiningServiceTask
                            && receipt.state() == TaskState.FAILED
                            && failure.equals(receipt.failureReason()),
                    "dimension drift replaced the terminal receipt reason");
            TaskManager.INSTANCE.abort(bot);
            GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);
            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
            require(context, result != null && failure.equals(result.reason())
                            && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                    "cross-dimension receipt did not terminate with original reason");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "terminalServicePocketDimensionSuspend", tickLimit = 60)
    public void activePocketWaitsForItsPersistedDimensionBeforeRestore(
            TestContext context) {
        context.runAtTick(1, () -> {
            GuardFixture fixture = settleBoundaryZeroGuard(
                    context, "GuardDimensionSuspendGT",
                    "mining_service_disposal_ore_preserved:minecraft:diamond_ore");
            AIPlayerEntity bot = fixture.bot();
            Map<String, String> wrongDimensionPocket = new LinkedHashMap<>(
                    withOpenGuardedPocket(
                            fixture.guardedRuntime().active().checkpoint()));
            wrongDimensionPocket.keySet().removeIf(
                    key -> key.startsWith("settled_service."));
            Map<String, String> pocketTask = namespace(
                    wrongDimensionPocket, "task.");
            require(context, MiningServiceTask.inspectCheckpoint(pocketTask).isPresent()
                            && GoalExecutor.hasActiveServicePocket(pocketTask),
                    "wrong-dimension fixture lost its complete pocket ledger");

            TaskManager.INSTANCE.cancelIntentTasks(
                    bot, "gametest_dimension_suspend_restore");
            GoalExecutor.INSTANCE.unload(bot);
            BlockPos face = decodePos(pocketTask.get("work_face"));
            var nether = bot.getServer().getWorld(net.minecraft.world.World.NETHER);
            require(context, nether != null, "fixture has no Nether world");
            bot.teleport(nether, face.getX() + 0.5D, face.getY(),
                    face.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
            GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(
                    fixture.guardedRuntime(), Map.copyOf(wrongDimensionPocket)));
            MissionRuntimeRecord suspended = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, suspended.active() != null
                            && GoalExecutor.INSTANCE.hasActivePlan(bot)
                            && TaskManager.INSTANCE.getActive(bot).isEmpty()
                            && GoalExecutor.INSTANCE.lastResult(bot).isEmpty()
                            && pocketTask.equals(namespace(
                            suspended.active().checkpoint(), "task.")),
                    "wrong-dimension pocket was terminated or lost while suspended");
            Goal queuedGoal = new Goal.HaveItem(Items.APPLE, 1);
            require(context, GoalExecutor.INSTANCE.submit(bot, fixture.goal())
                            && GoalExecutor.INSTANCE.submit(bot, queuedGoal),
                    "suspended submit did not remain idempotent/queue-only");
            MissionRuntimeRecord queuedSuspension =
                    GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, TaskManager.INSTANCE.getActive(bot).isEmpty()
                            && queuedSuspension.active() != null
                            && pocketTask.equals(namespace(
                            queuedSuspension.active().checkpoint(), "task."))
                            && queuedSuspension.queue().size() == 1
                            && queuedSuspension.queue().getFirst().toGoal()
                            .filter(queuedGoal::equals).isPresent()
                            && GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                    "suspended submit started a second task or changed the pocket ledger");

            var overworld = bot.getServer().getWorld(net.minecraft.world.World.OVERWORLD);
            require(context, overworld != null, "fixture has no Overworld");
            bot.teleport(overworld, face.getX() + 0.5D, face.getY(),
                    face.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
            GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);
            MissionRuntimeRecord resumed = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, resumed.active() != null
                            && TaskManager.INSTANCE.getActive(bot)
                            .filter(task -> task instanceof MiningServiceTask
                                    && task.state() == TaskState.RUNNING).isPresent()
                            && GoalExecutor.hasActiveServicePocket(namespace(
                            resumed.active().checkpoint(), "task."))
                            && resumed.queue().size() == 1
                            && resumed.queue().getFirst().toGoal()
                            .filter(queuedGoal::equals).isPresent(),
                    "pocket did not resume with its ledger after returning dimension");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServicePocketKindAuthority", tickLimit = 80)
    public void activePocketKindIsInferredAndSemanticFailureIsQuarantined(
            TestContext context) {
        context.runAtTick(1, () -> {
            String failure =
                    "mining_service_disposal_ore_preserved:minecraft:diamond_ore";
            for (String variant : java.util.List.of("missing", "wrong")) {
                GuardFixture fixture = settleBoundaryZeroGuard(
                        context, "PocketKind" + variant + "GT", failure);
                Map<String, String> checkpoint = new LinkedHashMap<>(
                        withOpenGuardedPocket(
                                fixture.guardedRuntime().active().checkpoint()));
                checkpoint.keySet().removeIf(
                        key -> key.startsWith("settled_service."));
                if ("missing".equals(variant)) {
                    checkpoint.remove("task_kind");
                } else {
                    checkpoint.put("task_kind", "CRAFT");
                }
                Map<String, String> rawTask = Map.copyOf(
                        namespace(checkpoint, "task."));
                require(context, MiningServiceTask.inspectCheckpoint(rawTask).isPresent()
                                && GoalExecutor.hasActiveServicePocket(rawTask),
                        variant + " task-kind fixture lost its valid pocket");
                TaskManager.INSTANCE.cancelIntentTasks(
                        fixture.bot(), "gametest_pocket_kind_" + variant);
                GoalExecutor.INSTANCE.unload(fixture.bot());
                BotRuntimeOptions.INSTANCE.setVerboseReportsEnabled(
                        fixture.bot(), true);
                long taskReportBaseline = BotReporter.INSTANCE
                        .taskReportSequence(fixture.bot());
                GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(),
                        withCheckpoint(fixture.originalRuntime(),
                                Map.copyOf(checkpoint)));
                long taskReportAfter = BotReporter.INSTANCE
                        .taskReportSequence(fixture.bot());
                require(context, taskReportAfter == taskReportBaseline + 1,
                        variant + " committed restore did not publish exactly one assignment: "
                                + taskReportBaseline + "->" + taskReportAfter);
                MissionRuntimeRecord restored =
                        GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
                require(context, restored.active() != null
                                && "MINING_SERVICE".equals(
                                restored.active().checkpoint().get("task_kind"))
                                && TaskManager.INSTANCE.getActive(fixture.bot())
                                .filter(task -> task instanceof MiningServiceTask
                                        && task.state() == TaskState.RUNNING).isPresent()
                                && rawTask.equals(namespace(
                                restored.active().checkpoint(), "task."))
                                && GoalExecutor.INSTANCE.lastResult(
                                fixture.bot()).isEmpty(),
                        variant + " task_kind bypassed or changed pocket authority");
                AIPlayerManager.INSTANCE.despawn(
                        fixture.bot().getServer(), fixture.name());
            }

            GuardFixture conflict = settleBoundaryZeroGuard(
                    context, "PocketSemanticQuarantineGT", failure);
            Map<String, String> guardedPocket = withOpenGuardedPocket(
                    conflict.guardedRuntime().active().checkpoint());
            Map<String, String> rawTask = Map.copyOf(
                    namespace(guardedPocket, "task."));
            require(context, MiningServiceTask.inspectCheckpoint(rawTask).isPresent()
                            && GoalExecutor.hasActiveServicePocket(rawTask),
                    "semantic quarantine fixture is not a valid physical pocket");
            TaskManager.INSTANCE.cancelIntentTasks(
                    conflict.bot(), "gametest_pocket_semantic_conflict");
            GoalExecutor.INSTANCE.unload(conflict.bot());
            BotRuntimeOptions.INSTANCE.setVerboseReportsEnabled(
                    conflict.bot(), true);
            long taskReportBaseline = BotReporter.INSTANCE
                    .taskReportSequence(conflict.bot());
            GoalExecutor.INSTANCE.restoreRuntime(conflict.bot(),
                    withCheckpoint(conflict.guardedRuntime(), guardedPocket));
            long taskReportAfter = BotReporter.INSTANCE
                    .taskReportSequence(conflict.bot());
            require(context, taskReportAfter == taskReportBaseline,
                    "quarantined restore leaked task chat: "
                            + taskReportBaseline + "->" + taskReportAfter);
            MissionRuntimeRecord quarantined =
                    GoalExecutor.INSTANCE.captureRuntime(conflict.bot());
            Goal foreignGoal = new Goal.HaveItem(Items.APPLE, 1);
            require(context, quarantined.active() != null
                            && GoalExecutor.INSTANCE.hasActivePlan(conflict.bot())
                            && TaskManager.INSTANCE.getActive(conflict.bot()).isEmpty()
                            && GoalExecutor.INSTANCE.lastResult(conflict.bot()).isEmpty()
                            && rawTask.equals(namespace(
                            quarantined.active().checkpoint(), "task."))
                            && !GoalExecutor.INSTANCE.submit(
                            conflict.bot(), foreignGoal)
                            && GoalExecutor.INSTANCE.captureRuntime(conflict.bot())
                            .queue().equals(quarantined.queue()),
                    "semantic pocket conflict was terminated, mutated, or escaped quarantine");
            GoalExecutor.INSTANCE.cancelAll(conflict.bot());
            AIPlayerManager.INSTANCE.despawn(
                    conflict.bot().getServer(), conflict.name());
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "settledServiceGuardCausalReason", tickLimit = 60)
    public void guardOnlyRestoreUsesTypedReasonOnlyWhenCausalityIsUnique(
            TestContext context) {
        context.runAtTick(1, () -> {
            String firstFailure =
                    "mining_service_disposal_ore_preserved:minecraft:diamond_ore";
            String secondFailure =
                    "mining_service_disposal_ore_preserved:minecraft:emerald_ore";
            for (boolean multiple : java.util.List.of(false, true)) {
                GuardFixture fixture = settleBoundaryZeroGuard(
                        context, multiple ? "GuardMultiReasonGT" : "GuardSingleReasonGT",
                        firstFailure);
                AIPlayerEntity bot = fixture.bot();
                Map<String, String> guardOnly = new LinkedHashMap<>(
                        fixture.guardedRuntime().active().checkpoint());
                guardOnly.remove("task_kind");
                guardOnly.keySet().removeIf(key -> key.startsWith("task.")
                        || key.startsWith("mining.")
                        || key.startsWith("aux_mining.")
                        || key.startsWith("obsidian."));
                guardOnly.remove("capacity_parent");
                guardOnly.remove("capacity_parent_delivered");
                guardOnly.remove("capacity_parent_face");
                guardOnly.remove("capacity_parent_services_used");
                guardOnly.remove("aux_mining_continuation");
                if (multiple) {
                    addCanonicalDistinctGuard(guardOnly, secondFailure);
                }
                clearCarriedInventory(bot);
                require(context, !GoalPlanner.plan(bot, fixture.goal()).success(),
                        "guard-only fixture did not force a failed fresh plan");
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_guard_only_causal_reason");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot,
                        withCheckpoint(fixture.guardedRuntime(),
                                Map.copyOf(guardOnly)));
                GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
                String expected = multiple
                        ? "mission_restore_settled_service_guard_without_continuation"
                        : firstFailure;
                require(context, result != null && expected.equals(result.reason())
                                && !GoalExecutor.INSTANCE.hasActivePlan(bot)
                                && (multiple
                                ? !firstFailure.equals(result.reason())
                                && !secondFailure.equals(result.reason()) : true),
                        "guard-only restore guessed a non-causal reason: "
                                + (result == null ? "none" : result.reason()));
                AIPlayerManager.INSTANCE.despawn(
                        bot.getServer(), fixture.name());
            }
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "terminalCapacityGuardOneShot", tickLimit = 240)
    public void terminalCapacityGuardSurvivesRepairRestartAndStopsGenericReplan(
            TestContext context) {
        String name = "TerminalCapacityGuardGT";
        String failure =
                "mining_service_disposal_ore_preserved:minecraft:diamond_ore";
        AtomicBoolean awaitingRepairedParent = new AtomicBoolean();
        AtomicReference<Map<String, String>> originalService =
                new AtomicReference<>();
        AtomicInteger restartReplans = new AtomicInteger(-1);

        withRunningSmallRareCapacityService(context, name,
                (bot, goal, runtime, checkpoint) -> {
                    int ironPickaxes = InventoryAction.countItem(
                            bot, Items.IRON_PICKAXE);
                    require(context, ironPickaxes > 0 && InventoryAction.removeItems(
                                    bot, Items.IRON_PICKAXE, ironPickaxes),
                            "capacity repair fixture could not remove target pickaxes");
                    GoalPlanner.GoalPlan repairPlan = GoalPlanner.plan(bot, goal);
                    require(context, repairPlan.success()
                                    && !repairPlan.steps().isEmpty()
                                    && repairPlan.steps().getFirst().kind()
                                    == GoalStep.Kind.CRAFT
                                    && repairPlan.steps().getFirst().item()
                                    == Items.IRON_PICKAXE,
                            "capacity fixture did not create a real CraftTask prefix: "
                                    + repairPlan.describeSteps());

                    Map<String, String> terminal = terminalServiceCheckpoint(
                            checkpoint, failure);
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_terminal_capacity_guard_restore");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, terminal));
                    Task receipt = TaskManager.INSTANCE.getActive(bot).orElse(null);
                    require(context, receipt instanceof MiningServiceTask
                                    && receipt.state() == TaskState.FAILED
                                    && failure.equals(receipt.failureReason()),
                            "capacity receipt did not restore its typed result");
                    TaskManager.INSTANCE.abort(bot);
                    GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);

                    MissionRuntimeRecord onRepair =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, onRepair.active() != null
                                    && TaskManager.INSTANCE.getActive(bot)
                                    .filter(task -> task instanceof CraftTask
                                            && task.state() == TaskState.RUNNING)
                                    .isPresent()
                                    && "mining".equals(
                                    onRepair.active().checkpoint().get(
                                    "capacity_parent"))
                                    && failure.equals(
                                    onRepair.active().checkpoint().get(
                                    "settled_service.0.failure")),
                            "capacity receipt skipped repair or lost its guard");
                    int replans = Integer.parseInt(
                            onRepair.active().checkpoint().getOrDefault(
                                    "lifetime_replans", "-1"));
                    require(context, replans >= 0,
                            "capacity fixture lacks lifetime replan watermark");

                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_terminal_capacity_guard_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, onRepair);
                    MissionRuntimeRecord restarted =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restarted.active() != null
                                    && TaskManager.INSTANCE.getActive(bot)
                                    .filter(task -> task instanceof CraftTask
                                            && task.state() == TaskState.RUNNING)
                                    .isPresent()
                                    && replans == Integer.parseInt(
                                    restarted.active().checkpoint().getOrDefault(
                                    "lifetime_replans", "-2")),
                            "capacity restart reordered MineOre before Craft or spent replan");
                    originalService.set(Map.copyOf(namespace(checkpoint, "task.")));
                    restartReplans.set(replans);
                    awaitingRepairedParent.set(true);
                });

        context.runAtEveryTick(() -> {
            if (!awaitingRepairedParent.get()) {
                return;
            }
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.getByName(name).orElseThrow();
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof CraftTask) {
                return;
            }
            if (active instanceof OreDigTask) {
                MissionRuntimeRecord parent =
                        GoalExecutor.INSTANCE.captureRuntime(bot);
                require(context, parent.active() != null
                                && restartReplans.get() == Integer.parseInt(
                                parent.active().checkpoint().getOrDefault(
                                "lifetime_replans", "-2"))
                                && failure.equals(
                                parent.active().checkpoint().get(
                                "settled_service.0.failure")),
                        "repair completion spent replan or pruned capacity guard");
                fillWithGlassUntilFreeSlots(bot, 0);
                Map<String, String> guardedService = replaceTaskCheckpoint(
                        parent.active().checkpoint(), originalService.get(),
                        GoalStep.Kind.MINING_SERVICE);
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_terminal_capacity_guard_replay");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(
                        bot, withCheckpoint(parent, guardedService));
                MiningServiceTask service = TaskManager.INSTANCE.getActive(bot)
                        .filter(MiningServiceTask.class::isInstance)
                        .map(MiningServiceTask.class::cast).orElse(null);
                require(context, service != null && service.state() == TaskState.RUNNING,
                        "capacity service did not restore at its guarded face");
                Map<BlockPos, net.minecraft.block.BlockState> worldBefore =
                        snapshotGuardGeometry(bot, guardedService);
                Vec3d positionBefore = bot.getPos();
                InventorySnapshot inventoryBefore = snapshotInventory(bot);
                service.tick(bot);
                require(context, service.state() == TaskState.FAILED
                                && failure.equals(service.failureReason())
                                && worldBefore.equals(snapshotGuardGeometry(
                                bot, guardedService))
                                && inventoryBefore.matches(bot)
                                && bot.getPos().squaredDistanceTo(
                                positionBefore) < 0.000001D,
                        "capacity guard mutated geometry or lost its reason");
                TaskManager.INSTANCE.abort(bot);
                GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);
                GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
                require(context, result != null
                                && failure.equals(result.reason())
                                && !GoalExecutor.INSTANCE.hasActivePlan(bot)
                                && TaskManager.INSTANCE.getActive(bot).isEmpty(),
                        "capacity guard fell through to generic replan");
                awaitingRepairedParent.set(false);
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
                return;
            }
            if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 225) {
                context.throwGameTestException(
                        "capacity repair never resumed its exact parent OreDig");
            }
        });
    }
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void ordinaryServicePreservesProtectedRareMiningNamespace(TestContext context) {
        BlockPos rareFace = context.getAbsolutePos(new BlockPos(1, 2, 1));
        MiningCursor rareCursor = MiningCursor.initial(rareFace, 48);
        OreDigTask.RestoreMetadata protectedRare = new OreDigTask.RestoreMetadata(
                Set.of(Blocks.DIAMOND_ORE), 8, 0, 64, true, rareCursor,
                17, MiningBudget.RARE_BATCH_TORCH_LIMIT, 0, 0, false);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        require(context, GoalExecutor.ordinaryServiceMiningNamespaceMatches(
                        goal, Set.of(Blocks.COAL_ORE), rareFace.add(8, 0, 0),
                        java.util.Optional.of(protectedRare)),
                "ordinary prerequisite service rejected the protected rare cursor");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void capacityParentIdentityRequiresExactDebitedFamilyFaceAndCursor(
            TestContext context) {
        String name = "CapacityParentIdentityGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        MiningCursor cursor = MiningCursor.initial(bot.getBlockPos(), 48);
        Map<String, String> parent = OreDigTask.debitCapacityHandoff(
                openOrdinaryCheckpoint(cursor, Set.of(Blocks.COAL_ORE)))
                .orElseThrow();
        MiningServiceTask serviceTask = new MiningServiceTask(
                Set.of(Blocks.COAL_ORE), Map.of(),
                MiningServiceTask.ServicePolicy.capacityHandoff(
                        MiningBudget.EMERGENCY_STONE_LIKE),
                0, "capacity-parent-gametest", 0, cursor);
        serviceTask.start(bot);
        Map<String, String> service = serviceTask.checkpoint();

        require(context, GoalExecutor.capacityParentMatchesService(service, parent),
                "exact debited capacity parent was rejected");

        Map<String, String> undebited = new LinkedHashMap<>(parent);
        undebited.put("inventory_service_used", "false");
        require(context, !GoalExecutor.capacityParentMatchesService(service, undebited),
                "capacity service accepted inventory_service_used=false");

        Map<String, String> wrongFamily = new LinkedHashMap<>(parent);
        wrongFamily.put("ore_fingerprint",
                OreDigTask.oreFingerprint(Set.of(Blocks.IRON_ORE)));
        require(context, !GoalExecutor.capacityParentMatchesService(service, wrongFamily),
                "capacity service accepted a different ore family");

        for (Map.Entry<String, String> mismatch : Map.of(
                "origin", "1,0,0",
                "face", "1,0,0",
                "direction", "1",
                "leg", "1",
                "steps_left", "1",
                "leg_length", "96",
                "batches", "1").entrySet()) {
            require(context, !java.util.Objects.equals(
                            parent.get(mismatch.getKey()), mismatch.getValue()),
                    "identity fixture did not change " + mismatch.getKey());
            Map<String, String> changed = new LinkedHashMap<>(parent);
            changed.put(mismatch.getKey(), mismatch.getValue());
            require(context, !GoalExecutor.capacityParentMatchesService(service, changed),
                    "capacity service accepted mismatched " + mismatch.getKey());
        }

        Map<String, String> physicalDebt = new LinkedHashMap<>(parent);
        physicalDebt.put("active_break_pos", "0,0,0");
        physicalDebt.put("active_break_inventory", "0");
        require(context, !GoalExecutor.capacityParentMatchesService(service, physicalDebt),
                "capacity service accepted a parent with physical break debt");

        Map<String, String> closedParent = committedOreNamespace(parent);
        require(context, GoalExecutor.failedClosedAuxiliaryServiceMatches(
                        service, closedParent),
                "exact failed non-pocket service did not match its closed auxiliary parent");
        Map<String, String> activePocket = new LinkedHashMap<>(service);
        activePocket.put("pocket_entry", "0,0,0");
        require(context, !GoalExecutor.failedClosedAuxiliaryServiceMatches(
                        activePocket, closedParent),
                "active pocket was admitted to stale auxiliary cleanup");
        Map<String, String> wrongClosedCursor = new LinkedHashMap<>(closedParent);
        wrongClosedCursor.put("face", "1,0,0");
        require(context, !GoalExecutor.failedClosedAuxiliaryServiceMatches(
                        service, wrongClosedCursor),
                "failed service cleanup accepted a different closed cursor");
        serviceTask.abort(bot);
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 180)
    public void protectedRareRestoreKeepsByteExactRareAndAuxiliaryCapacityLedgers(
            TestContext context) {
        String name = "ProtectedRareAuxCapacityGT";
        withRunningOrdinaryService(context, name,
                (fixture, ignoredGoal, runtime, checkpoint) -> {
                    Map<String, String> parent = new LinkedHashMap<>(
                            namespace(checkpoint, "mining."));
                    parent.put("batch_open", "true");
                    parent.put("inventory_service_used", "true");
                    clearOrePhysicalLedger(parent, "");
                    require(context, OreDigTask.inspectCheckpoint(parent, 0).isPresent(),
                            "forged auxiliary parent is not a valid ordinary OreDig ledger");

                    Map<String, String> protectedRare = new LinkedHashMap<>(parent);
                    protectedRare.put("target_count", "8");
                    protectedRare.put("rare_mission_target", "64");
                    protectedRare.put("inventory_service_used", "false");
                    protectedRare.put("ore_fingerprint",
                            OreDigTask.oreFingerprint(Set.of(Blocks.DIAMOND_ORE)));
                    require(context,
                            OreDigTask.inspectCheckpoint(protectedRare, 64).isPresent(),
                            "forged protected rare namespace is invalid");

                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.put("task.channel_tools", "false");
                    forged.put("task.channel_tool_usable", "0");
                    forged.keySet().removeIf(key -> key.startsWith("mining."));
                    parent.forEach((key, value) ->
                            forged.put("aux_mining." + key, value));
                    protectedRare.forEach((key, value) ->
                            forged.put("mining." + key, value));
                    forged.put("capacity_parent", "auxiliary");
                    require(context, MiningServiceTask.inspectCheckpoint(
                                    namespace(forged, "task.")).isPresent(),
                            "forged capacity service checkpoint is invalid");

                    MissionRecord prior = runtime.active();
                    Goal longRareGoal = new Goal.HaveItem(Items.DIAMOND, 64);
                    MissionRuntimeRecord restart = new MissionRuntimeRecord(
                            new MissionRecord(prior.missionId(),
                                    MissionSpec.fromGoal(longRareGoal),
                                    Map.copyOf(forged)),
                            runtime.queue(), runtime.userPaused());
                    AIPlayerEntity bot = fixture.bot();
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_protected_rare_aux_capacity_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, restart);

                    MissionRuntimeRecord restored =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restored.active() != null
                                    && prior.missionId().equals(
                                    restored.active().missionId()),
                            "protected rare auxiliary capacity restore lost mission identity");
                    Map<String, String> after = restored.active().checkpoint();
                    require(context, "MINING_SERVICE".equals(after.get("task_kind"))
                                    && "auxiliary".equals(after.get("capacity_parent"))
                                    && protectedRare.equals(namespace(after, "mining."))
                                    && parent.equals(namespace(after, "aux_mining.")),
                            "restore changed protected rare or auxiliary capacity bytes: "
                                    + checkpointSummary(after));
                    require(context, GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                            "protected rare auxiliary restore published a terminal result");
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 180)
    public void protectedRareRestoreSettlesClosedAuxiliaryCapacityCommit(
            TestContext context) {
        String name = "ProtectedRareClosedAuxGT";
        withRunningOrdinaryService(context, name,
                (fixture, ignoredGoal, runtime, checkpoint) -> {
                    Map<String, String> openOrdinary = new LinkedHashMap<>(
                            namespace(checkpoint, "mining."));
                    openOrdinary.put("batch_open", "true");
                    openOrdinary.put("inventory_service_used", "true");
                    clearOrePhysicalLedger(openOrdinary, "");
                    require(context,
                            OreDigTask.inspectCheckpoint(openOrdinary, 0).isPresent(),
                            "closed-aux fixture could not create an open ordinary parent");
                    Map<String, String> committedAux =
                            committedOreNamespace(openOrdinary);

                    Map<String, String> protectedRare =
                            new LinkedHashMap<>(openOrdinary);
                    protectedRare.put("target_count", "8");
                    protectedRare.put("rare_mission_target", "64");
                    protectedRare.put("inventory_service_used", "false");
                    protectedRare.put("ore_fingerprint",
                            OreDigTask.oreFingerprint(Set.of(Blocks.DIAMOND_ORE)));
                    require(context,
                            OreDigTask.inspectCheckpoint(protectedRare, 64).isPresent(),
                            "closed-aux fixture forged an invalid protected rare cursor");

                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.keySet().removeIf(key -> key.startsWith("task.")
                            || key.startsWith("mining.")
                            || key.startsWith("aux_mining."));
                    forged.put("task_kind", "MINE_ORE");
                    committedAux.forEach((key, value) -> {
                        forged.put("task." + key, value);
                        forged.put("aux_mining." + key, value);
                    });
                    protectedRare.forEach((key, value) ->
                            forged.put("mining." + key, value));
                    forged.put("capacity_parent", "auxiliary");

                    AIPlayerEntity bot = fixture.bot();
                    // The ordinary-service fixture deliberately leaves only one working slot.
                    // This restart proof replaces that inventory with the complete physical
                    // long-rare readiness set; its subject is checkpoint settlement, not the
                    // preceding ordinary batch's crowded inventory.
                    clearCarriedInventory(bot);
                    giveDiamond64Readiness(bot);
                    MissionRecord prior = runtime.active();
                    Goal longRareGoal = new Goal.HaveItem(Items.DIAMOND, 64);
                    MissionRuntimeRecord restart = new MissionRuntimeRecord(
                            new MissionRecord(prior.missionId(),
                                    MissionSpec.fromGoal(longRareGoal),
                                    Map.copyOf(forged)),
                            runtime.queue(), runtime.userPaused());
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_closed_aux_capacity_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, restart);

                    MissionRuntimeRecord restored =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restored.active() != null
                                    && prior.missionId().equals(
                                    restored.active().missionId()),
                            "closed auxiliary commit lost protected rare mission identity");
                    Map<String, String> after = restored.active().checkpoint();
                    require(context,
                            !after.containsKey("capacity_parent")
                                    && after.keySet().stream().noneMatch(
                                    key -> key.startsWith("aux_mining."))
                                    && protectedRare.equals(
                                    namespace(after, "mining."))
                                    && !committedAux.equals(
                                    namespace(after, "task.")),
                            "closed auxiliary commit replayed or changed protected rare bytes: "
                                    + checkpointSummary(after));
                    require(context, GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                            "closed auxiliary commit published a premature result");
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "failedAuxServiceIsolation", tickLimit = 180)
    public void failedNonPocketAuxiliaryServiceReplansWithoutStaleReplay(
            TestContext context) {
        String name = "ProtectedRareFailedAuxGT";
        withRunningOrdinaryService(context, name,
                (fixture, ignoredGoal, runtime, checkpoint) -> {
                    Map<String, String> closedAux =
                            namespace(checkpoint, "mining.");
                    require(context,
                            OreDigTask.inspectCheckpoint(closedAux, 0)
                                    .filter(metadata -> !metadata.batchOpen())
                                    .isPresent(),
                            "failed-aux fixture lacks a closed ordinary parent");

                    Map<String, String> failedService = new LinkedHashMap<>(
                            namespace(checkpoint, "task."));
                    for (String key : Set.of(
                            "pocket_entry", "pocket_sink", "pocket_direction",
                            "pocket_entities", "pocket_lineage", "pocket_baseline",
                            "pocket_ledger", "pocket_drop_committed",
                            "pocket_ledger_verified", "pocket_phase_started",
                            "pocket_failure", "pocket_clear_index")) {
                        failedService.remove(key);
                    }
                    failedService.put("phase", "PREPARE");
                    failedService.put("budget_used", String.valueOf(
                            MiningMissionBudget.SERVICE_HARD_WINDOW_TICKS));
                    failedService.put("last_progress_budget", "0");
                    require(context,
                            MiningServiceTask.inspectCheckpoint(failedService).isPresent()
                                    && GoalExecutor.failedClosedAuxiliaryServiceMatches(
                                    failedService, closedAux),
                            "failed-aux fixture did not produce an exact non-pocket service");
                    Map<String, String> terminalFailedService = withServicePolicy(
                            failedService,
                            MiningServiceTask.ServicePolicy.defaultOre(false));
                    require(context,
                            MiningServiceTask.inspectCheckpoint(terminalFailedService).isPresent()
                                    && GoalExecutor.failedClosedAuxiliaryServiceMatches(
                                    terminalFailedService, closedAux),
                            "failed-aux fixture could not bind its terminal service policy");

                    Map<String, String> protectedRare =
                            new LinkedHashMap<>(closedAux);
                    protectedRare.put("batch_open", "true");
                    protectedRare.put("target_count", "8");
                    protectedRare.put("rare_mission_target", "64");
                    protectedRare.put("inventory_service_used", "false");
                    protectedRare.put("ore_fingerprint",
                            OreDigTask.oreFingerprint(Set.of(Blocks.DIAMOND_ORE)));
                    require(context,
                            OreDigTask.inspectCheckpoint(protectedRare, 64).isPresent(),
                            "failed-aux fixture forged an invalid protected rare cursor");

                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.keySet().removeIf(key -> key.startsWith("task.")
                            || key.startsWith("mining.")
                            || key.startsWith("aux_mining."));
                    forged.put("task_kind", "MINING_SERVICE");
                    failedService.forEach((key, value) ->
                            forged.put("task." + key, value));
                    protectedRare.forEach((key, value) ->
                            forged.put("mining." + key, value));
                    closedAux.forEach((key, value) ->
                            forged.put("aux_mining." + key, value));
                    forged.remove("capacity_parent");

                    AIPlayerEntity bot = fixture.bot();
                    // Retire the one-slot ordinary-service fixture before installing the complete
                    // long-rare restart inventory. Otherwise the readiness helper truthfully
                    // rejects the partial fixture before the failed-service boundary is exercised.
                    clearCarriedInventory(bot);
                    giveDiamond64Readiness(bot);
                    InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 16));
                    Goal longRareGoal = new Goal.HaveItem(Items.DIAMOND, 64);
                    MissionRecord prior = runtime.active();
                    MissionRuntimeRecord staleRestart = new MissionRuntimeRecord(
                            new MissionRecord(prior.missionId(),
                                    MissionSpec.fromGoal(longRareGoal),
                                    Map.copyOf(forged)),
                            runtime.queue(), runtime.userPaused());
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_failed_aux_service_restore");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, staleRestart);

                    MissionRuntimeRecord staleRetired =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, staleRetired.active() != null
                                    && staleRetired.active().checkpoint().keySet().stream()
                                    .noneMatch(key -> key.startsWith("aux_mining."))
                                    && protectedRare.equals(namespace(
                                    staleRetired.active().checkpoint(), "mining."))
                                    && !failedService.equals(namespace(
                                    staleRetired.active().checkpoint(), "task.")),
                            "stale non-pocket service retained its closed auxiliary cursor: "
                                    + checkpointSummary(staleRetired.active() == null
                                    ? Map.of() : staleRetired.active().checkpoint()));

                    Map<String, String> terminalForged = new LinkedHashMap<>(forged);
                    terminalForged.keySet().removeIf(key -> key.startsWith("task."));
                    terminalFailedService.forEach((key, value) ->
                            terminalForged.put("task." + key, value));
                    MissionRuntimeRecord restart = new MissionRuntimeRecord(
                            new MissionRecord(prior.missionId(),
                                    MissionSpec.fromGoal(longRareGoal),
                                    Map.copyOf(terminalForged)),
                            runtime.queue(), runtime.userPaused());
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_terminal_failed_aux_service_restore");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, restart);

                    Task service = TaskManager.INSTANCE.getActive(bot).orElse(null);
                    require(context, service instanceof MiningServiceTask,
                            "failed auxiliary service was not restored first");
                    service.tick(bot);
                    require(context, service.state() == TaskState.FAILED
                                    && service.failureReason().startsWith(
                                    "mining_service_timeout:"),
                            "non-pocket service did not publish its typed failure: "
                                    + service.state() + ":" + service.failureReason());
                    TaskManager.INSTANCE.abort(bot);
                    GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);

                    MissionRuntimeRecord replanned =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, replanned.active() != null
                                    && GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                            "non-pocket service failure escaped generic replan");
                    Map<String, String> after = replanned.active().checkpoint();
                    require(context,
                            after.keySet().stream().noneMatch(
                                    key -> key.startsWith("aux_mining."))
                                    && protectedRare.equals(
                                    namespace(after, "mining."))
                                    && !terminalFailedService.equals(
                                    namespace(after, "task.")),
                            "failed auxiliary service survived cleanup or changed rare bytes: "
                                    + checkpointSummary(after));

                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_failed_aux_service_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, replanned);
                    MissionRuntimeRecord restored =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restored.active() != null
                                    && restored.active().checkpoint().keySet().stream()
                                    .noneMatch(key -> key.startsWith("aux_mining."))
                                    && protectedRare.equals(namespace(
                                    restored.active().checkpoint(), "mining.")),
                            "restart replayed failed auxiliary service or lost rare cursor");
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "failedAuxServiceContinuation", tickLimit = 220)
    public void failedInterBatchAuxiliaryServicePreservesLaterSameFamilyCursor(
            TestContext context) {
        String name = "ProtectedRareAuxContinueGT";
        withRunningOrdinaryService(context, name,
                (fixture, ignoredGoal, runtime, checkpoint) -> {
                    Map<String, String> closedAux = namespace(checkpoint, "mining.");
                    require(context, OreDigTask.inspectCheckpoint(closedAux, 0)
                                    .filter(metadata -> !metadata.batchOpen()).isPresent(),
                            "continuation fixture lacks a closed ordinary cursor");

                    Map<String, String> failedService = new LinkedHashMap<>(
                            namespace(checkpoint, "task."));
                    for (String key : Set.of(
                            "pocket_entry", "pocket_sink", "pocket_direction",
                            "pocket_entities", "pocket_lineage", "pocket_baseline",
                            "pocket_ledger", "pocket_drop_committed",
                            "pocket_ledger_verified", "pocket_phase_started",
                            "pocket_failure", "pocket_clear_index")) {
                        failedService.remove(key);
                    }
                    failedService.put("phase", "PREPARE");
                    failedService.put("budget_used", String.valueOf(
                            MiningMissionBudget.SERVICE_HARD_WINDOW_TICKS));
                    failedService.put("last_progress_budget", "0");
                    failedService = withServicePolicy(failedService,
                            MiningServiceTask.ServicePolicy.defaultOre(true));
                    require(context, GoalExecutor.failedClosedAuxiliaryServiceMatches(
                                    failedService, closedAux),
                            "continuation fixture service does not bind its closed cursor");

                    Map<String, String> protectedRare = new LinkedHashMap<>(closedAux);
                    protectedRare.put("batch_open", "true");
                    protectedRare.put("target_count", "8");
                    protectedRare.put("rare_mission_target", "64");
                    protectedRare.put("inventory_service_used", "false");
                    protectedRare.put("ore_fingerprint",
                            OreDigTask.oreFingerprint(Set.of(Blocks.DIAMOND_ORE)));
                    require(context, OreDigTask.inspectCheckpoint(protectedRare, 64).isPresent(),
                            "continuation fixture forged an invalid protected rare cursor");

                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.keySet().removeIf(key -> key.startsWith("task.")
                            || key.startsWith("mining.")
                            || key.startsWith("aux_mining."));
                    forged.put("task_kind", "MINING_SERVICE");
                    failedService.forEach((key, value) ->
                            forged.put("task." + key, value));
                    protectedRare.forEach((key, value) ->
                            forged.put("mining." + key, value));
                    closedAux.forEach((key, value) ->
                            forged.put("aux_mining." + key, value));
                    forged.remove("capacity_parent");

                    AIPlayerEntity bot = fixture.bot();
                    clearCarriedInventory(bot);
                    giveDiamond64Readiness(bot);
                    int ironPicks = InventoryAction.countItem(bot, Items.IRON_PICKAXE);
                    int ironIngots = InventoryAction.countItem(bot, Items.IRON_INGOT);
                    require(context, ironPicks > 0 && ironIngots > 0
                                    && InventoryAction.removeItems(
                                    bot, Items.IRON_PICKAXE, ironPicks)
                                    && InventoryAction.removeItems(
                                    bot, Items.IRON_INGOT, ironIngots),
                            "continuation fixture could not remove carried iron readiness");
                    giveItemToAtLeast(bot, Items.COAL, 10);
                    Goal longRareGoal = new Goal.HaveItem(Items.DIAMOND, 64);
                    GoalPlanner.GoalPlan fresh = GoalPlanner.plan(bot, longRareGoal);
                    String ironFingerprint = OreDigTask.oreFingerprint(
                            Set.of(Blocks.IRON_ORE));
                    require(context, fresh.success() && fresh.steps().stream().anyMatch(
                                    step -> step.kind() == GoalStep.Kind.MINE_ORE
                                            && ironFingerprint.equals(
                                            OreDigTask.oreFingerprint(step.ores()))),
                            "continuation fixture did not retain a fresh iron successor: "
                                    + fresh.steps() + " unresolved=" + fresh.unresolved());

                    MissionRecord prior = runtime.active();
                    MissionRuntimeRecord restart = new MissionRuntimeRecord(
                            new MissionRecord(prior.missionId(),
                                    MissionSpec.fromGoal(longRareGoal), Map.copyOf(forged)),
                            runtime.queue(), runtime.userPaused());
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_failed_aux_continuation_restore");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, restart);

                    Task service = TaskManager.INSTANCE.getActive(bot).orElse(null);
                    require(context, service instanceof MiningServiceTask,
                            "continuation fixture did not restore its service first");
                    service.tick(bot);
                    require(context, service.state() == TaskState.FAILED
                                    && service.failureReason().startsWith(
                                    "mining_service_timeout:"),
                            "continuation service did not publish its typed timeout: "
                                    + service.state() + ":" + service.failureReason());
                    TaskManager.INSTANCE.abort(bot);
                    GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);

                    MissionRuntimeRecord continued = GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, continued.active() != null
                                    && GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                            "same-family auxiliary continuation became terminal");
                    assertAuxiliaryContinuationOrPromotion(
                            context, continued.active().checkpoint(), protectedRare,
                            closedAux, ironFingerprint);

                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_failed_aux_continuation_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, continued);
                    MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restored.active() != null
                                    && GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                            "same-family auxiliary continuation failed after restart");
                    assertAuxiliaryContinuationOrPromotion(
                            context, restored.active().checkpoint(), protectedRare,
                            closedAux, ironFingerprint);
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "completedAuxServiceContinuation", tickLimit = 220)
    public void completedInterBatchServicePromotesAuxCursorToNextBatch(
            TestContext context) {
        String name = "ProtectedRareAuxCompleteGT";
        withRunningOrdinaryService(context, name,
                (fixture, ignoredGoal, runtime, checkpoint) -> {
                    String ironFingerprint = OreDigTask.oreFingerprint(
                            Set.of(Blocks.IRON_ORE));
                    Map<String, String> closedAux = new LinkedHashMap<>(
                            namespace(checkpoint, "mining."));
                    require(context, OreDigTask.inspectCheckpoint(closedAux, 0)
                                    .filter(metadata -> !metadata.batchOpen())
                                    .filter(metadata -> ironFingerprint.equals(
                                    OreDigTask.oreFingerprint(metadata.ores())))
                                    .isPresent(),
                            "completed-continuation fixture lacks a closed iron cursor");

                    Map<String, String> serviceCheckpoint = new LinkedHashMap<>(
                            namespace(checkpoint, "task."));
                    for (String key : Set.of(
                            "pocket_entry", "pocket_sink", "pocket_direction",
                            "pocket_entities", "pocket_lineage", "pocket_baseline",
                            "pocket_ledger", "pocket_drop_committed",
                            "pocket_ledger_verified", "pocket_phase_started",
                            "pocket_failure", "pocket_clear_index")) {
                        serviceCheckpoint.remove(key);
                    }
                    serviceCheckpoint.put("phase", "PREPARE");
                    serviceCheckpoint.put("budget_used", "0");
                    serviceCheckpoint.put("last_progress_budget", "0");
                    serviceCheckpoint = withServicePolicy(serviceCheckpoint,
                            MiningServiceTask.ServicePolicy.defaultOre(true));
                    require(context, GoalExecutor.failedClosedAuxiliaryServiceMatches(
                                    serviceCheckpoint, closedAux),
                            "completed service does not bind its closed iron cursor");

                    Map<String, String> protectedRare = new LinkedHashMap<>(closedAux);
                    protectedRare.put("batch_open", "true");
                    protectedRare.put("target_count", "8");
                    protectedRare.put("rare_mission_target", "64");
                    protectedRare.put("inventory_service_used", "false");
                    protectedRare.put("ore_fingerprint",
                            OreDigTask.oreFingerprint(Set.of(Blocks.DIAMOND_ORE)));
                    require(context, OreDigTask.inspectCheckpoint(protectedRare, 64).isPresent(),
                            "completed-continuation fixture forged an invalid rare cursor");

                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.keySet().removeIf(key -> key.startsWith("task.")
                            || key.startsWith("mining.")
                            || key.startsWith("aux_mining."));
                    forged.put("task_kind", "MINING_SERVICE");
                    serviceCheckpoint.forEach((key, value) ->
                            forged.put("task." + key, value));
                    protectedRare.forEach((key, value) ->
                            forged.put("mining." + key, value));
                    closedAux.forEach((key, value) ->
                            forged.put("aux_mining." + key, value));
                    forged.remove("capacity_parent");

                    AIPlayerEntity bot = fixture.bot();
                    clearCarriedInventory(bot);
                    giveDiamond64Readiness(bot);
                    int ironPicks = InventoryAction.countItem(bot, Items.IRON_PICKAXE);
                    int ironIngots = InventoryAction.countItem(bot, Items.IRON_INGOT);
                    require(context, ironPicks > 0 && ironIngots > 0
                                    && InventoryAction.removeItems(
                                    bot, Items.IRON_PICKAXE, ironPicks)
                                    && InventoryAction.removeItems(
                                    bot, Items.IRON_INGOT, ironIngots),
                            "completed-continuation fixture could not remove carried iron readiness");
                    giveItemToAtLeast(bot, Items.COAL, 10);
                    Goal longRareGoal = new Goal.HaveItem(Items.DIAMOND, 64);
                    GoalPlanner.GoalPlan fresh = GoalPlanner.plan(bot, longRareGoal);
                    require(context, fresh.success() && fresh.steps().stream().anyMatch(
                                    step -> step.kind() == GoalStep.Kind.MINE_ORE
                                            && ironFingerprint.equals(
                                            OreDigTask.oreFingerprint(step.ores()))),
                            "completed service fixture lacks a fresh iron successor: "
                                    + fresh.unresolved());

                    MissionRecord prior = runtime.active();
                    MissionRuntimeRecord restart = new MissionRuntimeRecord(
                            new MissionRecord(prior.missionId(),
                                    MissionSpec.fromGoal(longRareGoal), Map.copyOf(forged)),
                            runtime.queue(), runtime.userPaused());
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_completed_aux_continuation_restore");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, restart);

                    Task service = TaskManager.INSTANCE.getActive(bot).orElse(null);
                    require(context, service instanceof MiningServiceTask,
                            "completed-continuation fixture did not restore its service first");
                    for (int tick = 0; tick < 80
                            && service.state() == TaskState.RUNNING; tick++) {
                        service.tick(bot);
                    }
                    require(context, service.state() == TaskState.COMPLETED,
                            "inter-batch service did not complete from ready inventory: "
                                    + service.state() + ":" + service.failureReason());
                    TaskManager.INSTANCE.abort(bot);
                    GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);

                    MissionRuntimeRecord continued = GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, continued.active() != null
                                    && GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                            "completed service lost its same-family continuation");
                    assertAuxiliaryContinuationOrPromotion(
                            context, continued.active().checkpoint(), protectedRare,
                            closedAux, ironFingerprint);

                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_completed_aux_continuation_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, continued);
                    MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restored.active() != null
                                    && GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                            "completed service continuation failed after restart");
                    assertAuxiliaryContinuationOrPromotion(
                            context, restored.active().checkpoint(), protectedRare,
                            closedAux, ironFingerprint);
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 260)
    public void protectedRareOrdinaryFullInventorySchedulesAuxiliaryCapacityParent(
            TestContext context) {
        String name = "ProtectedRareAuxScheduleGT";
        AIPlayerEntity bot = spawnDiamond64CoalBootstrapMiner(context, name);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "protected rare auxiliary scheduling goal setup failed");
        AtomicBoolean injectedAndFilled = new AtomicBoolean();
        AtomicReference<Map<String, String>> protectedRare = new AtomicReference<>();
        AtomicReference<Map<String, String>> ordinaryBeforeFailure =
                new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!injectedAndFilled.get()) {
                boolean ordinaryCoal = "MINE_ORE".equals(
                        checkpoint.get("task_kind"))
                        && "0".equals(checkpoint.get(
                        "task.rare_mission_target"))
                        && checkpoint.getOrDefault(
                        "task.ore_fingerprint", "").contains("coal_ore")
                        && !checkpoint.containsKey("task.pending_pickup_pos")
                        && !checkpoint.containsKey("task.active_break_pos");
                if (!ordinaryCoal) {
                    if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                            || context.getTick() > 100) {
                        context.throwGameTestException(
                                "diamond64 bootstrap never reached a debt-free coal OreDig: "
                                        + checkpointSummary(checkpoint));
                    }
                    return;
                }
                Map<String, String> ordinary = new LinkedHashMap<>(
                        namespace(checkpoint, "task."));
                Map<String, String> rare = new LinkedHashMap<>(ordinary);
                rare.put("target_count", "8");
                rare.put("delivered", "0");
                rare.put("rare_mission_target", "64");
                rare.put("inventory_service_used", "false");
                rare.put("torch_placements", "0");
                rare.put("resource_epoch", "0");
                rare.put("ore_fingerprint",
                        OreDigTask.oreFingerprint(Set.of(Blocks.DIAMOND_ORE)));
                clearOrePhysicalLedger(rare, "");
                require(context, OreDigTask.inspectCheckpoint(rare, 64).isPresent(),
                        "protected rare scheduling fixture forged an invalid rare cursor");

                Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                forged.keySet().removeIf(key -> key.startsWith("mining."));
                rare.forEach((key, value) -> forged.put("mining." + key, value));
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_inject_protected_rare_cursor");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(
                        bot, withCheckpoint(runtime, forged));
                MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
                require(context, restored.active() != null,
                        "ordinary prerequisite above protected rare cursor did not restore");
                Map<String, String> before = restored.active().checkpoint();
                require(context, "MINE_ORE".equals(before.get("task_kind"))
                                && ordinary.equals(namespace(before, "task."))
                                && rare.equals(namespace(before, "mining."))
                                && before.keySet().stream().noneMatch(
                                key -> key.startsWith("aux_mining.")),
                        "restore changed the ordinary/rare split before capacity failure: "
                                + checkpointSummary(before));

                BlockPos face = decodePos(before.get("task.face"));
                prepareDisposalPocket(bot, face, Direction.EAST);
                prepareDisposalPocket(bot, face, Direction.WEST);
                require(context, !InventoryAction.giveItem(
                                bot, new ItemStack(Items.TUFF, 64)).isFailed(),
                        "protected rare fixture could not add disposable tuff");
                fillWithGlassUntilFreeSlots(bot, 0);
                require(context, freeMainSlots(bot) == 0,
                        "protected rare fixture did not fill inventory");
                protectedRare.set(Map.copyOf(rare));
                ordinaryBeforeFailure.set(Map.copyOf(ordinary));
                injectedAndFilled.set(true);
                return;
            }

            if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))) {
                Map<String, String> rare = protectedRare.get();
                Map<String, String> before = ordinaryBeforeFailure.get();
                Map<String, String> auxiliary = namespace(
                        checkpoint, "aux_mining.");
                require(context, "ORE_BATCH".equals(
                                checkpoint.get("task.service_profile"))
                                && String.valueOf(
                                MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE).equals(
                                checkpoint.get("task.emergency_blocks_reserved"))
                                && "auxiliary".equals(
                                checkpoint.get("capacity_parent"))
                                && rare.equals(namespace(checkpoint, "mining."))
                                && "false".equals(
                                checkpoint.get("mining.inventory_service_used"))
                                && "true".equals(
                                checkpoint.get("aux_mining.inventory_service_used")),
                        "ordinary capacity scheduling overwrote protected rare mining: "
                                + checkpointSummary(checkpoint));
                require(context,
                        Integer.parseInt(auxiliary.get("budget_used"))
                                == Integer.parseInt(before.get("budget_used")) + 1,
                        "auxiliary retry refreshed or overcharged the ordinary budget: "
                                + checkpointSummary(checkpoint));
                for (String suffix : Set.of(
                        "target_count", "delivered", "origin", "face", "direction",
                        "leg", "steps_left", "leg_length", "batches",
                        "last_progress_budget", "ore_fingerprint",
                        "pending_pickup_inventory", "pending_pickup_started_budget",
                        "pickup_gain_budget", "active_break_inventory")) {
                    require(context, java.util.Objects.equals(
                                    before.get(suffix), auxiliary.get(suffix)),
                            "auxiliary retry changed ordinary " + suffix + ": "
                                    + checkpointSummary(checkpoint));
                }
                for (String suffix : Set.of(
                        "origin", "face", "direction", "leg", "steps_left",
                        "leg_length", "batches")) {
                    require(context, java.util.Objects.equals(
                                    auxiliary.get(suffix),
                                    checkpoint.get("task.cursor_" + suffix)),
                            "capacity service changed auxiliary cursor " + suffix + ": "
                                    + checkpointSummary(checkpoint));
                }
                require(context, auxiliary.get("face").equals(
                                checkpoint.get("task.work_face")),
                        "capacity service work face is not auxiliary-parent exact");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 230) {
                context.throwGameTestException(
                        "ordinary full inventory did not schedule auxiliary capacity service: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 160)
    public void ordinaryServiceRestoreRejectsWrongFamilyOpenMiningNamespace(
            TestContext context) {
        withRunningOrdinaryService(context, "OrdinaryWrongFamilyGT",
                (fixture, goal, runtime, checkpoint) -> {
                    require(context, checkpoint.containsKey("mining.ore_fingerprint"),
                            "ordinary service fixture lacks its mining namespace: "
                                    + checkpointSummary(checkpoint));
                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.put("mining.ore_fingerprint",
                            OreDigTask.oreFingerprint(Set.of(Blocks.COAL_ORE)));
                    forged.put("mining.batch_open", "true");
                    AIPlayerEntity bot = fixture.bot();
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_ordinary_wrong_family");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, forged));
                    GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
                    require(context,
                            "mission_restore_incompatible_mining_service_checkpoint"
                                    .equals(result.reason()),
                            "wrong-family ordinary namespace was not isolated: "
                                    + result.reason());
                    require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                            "wrong-family ordinary namespace restored an active plan");
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 110)
    public void satisfiedGoalRejectsOrphanedOrdinaryBreakLedger(TestContext context) {
        String name = "SatisfiedOreLedgerGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.IRON_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "ordinary mining goal setup failed");

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!"MINE_ORE".equals(checkpoint.get("task_kind"))
                    || !checkpoint.containsKey("mining.task_schema")
                    || Integer.parseInt(checkpoint.getOrDefault(
                    "task.budget_used", "0")) <= 0) {
                if (context.getTick() > 55) {
                    context.throwGameTestException(
                            "ordinary ledger fixture never reached OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }
            Map<String, String> forged = new LinkedHashMap<>(checkpoint);
            setOpenBreakLedger(forged, "task.");
            setOpenBreakLedger(forged, "mining.");
            InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON, 8));
            TaskManager.INSTANCE.cancelIntentTasks(
                    bot, "gametest_satisfied_ordinary_ledger");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(runtime, forged));

            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
            require(context,
                    "mission_restore_orphaned_ordinary_mining_ledger"
                            .equals(result.reason()),
                    "satisfied fast path bypassed the physical ore ledger: "
                            + result.reason());
            require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                    "satisfied goal retained an orphaned physical ledger");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 100)
    public void ordinaryTaskAndMiningLedgerMustBeByteExact(TestContext context) {
        String name = "MismatchedOreLedgerGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.IRON_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "ordinary mining goal setup failed");
        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!"MINE_ORE".equals(checkpoint.get("task_kind"))
                    || !checkpoint.containsKey("mining.task_schema")
                    || Integer.parseInt(checkpoint.getOrDefault(
                    "task.budget_used", "0")) <= 0) {
                if (context.getTick() > 55) {
                    context.throwGameTestException(
                            "ledger mismatch fixture never reached OreDig");
                }
                return;
            }
            Map<String, String> forged = new LinkedHashMap<>(checkpoint);
            setOpenBreakLedger(forged, "task.");
            forged.put("task.delivered", "1");
            TaskManager.INSTANCE.cancelIntentTasks(
                    bot, "gametest_mismatched_ordinary_ledger");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(runtime, forged));
            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
            require(context,
                    "mission_restore_incompatible_mining_checkpoint"
                            .equals(result.reason()),
                    "task/mining ledger mismatch bypassed exact identity: "
                            + result.reason());
            require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                    "mismatched ordinary ledgers restored an active plan");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 100)
    public void standaloneOrdinaryMiningLedgerCannotBeDiscarded(TestContext context) {
        String name = "StandaloneOreLedgerGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.IRON_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "standalone mining-ledger goal setup failed");
        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!"MINE_ORE".equals(checkpoint.get("task_kind"))
                    || !checkpoint.containsKey("mining.task_schema")
                    || Integer.parseInt(checkpoint.getOrDefault(
                    "task.budget_used", "0")) <= 0) {
                if (context.getTick() > 55) {
                    context.throwGameTestException(
                            "standalone mining-ledger fixture never reached OreDig");
                }
                return;
            }
            Map<String, String> standalone = new LinkedHashMap<>(checkpoint);
            standalone.remove("task_kind");
            standalone.keySet().removeIf(key -> key.startsWith("task."));
            setOpenBreakLedger(standalone, "mining.");
            InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON, 8));
            TaskManager.INSTANCE.cancelIntentTasks(
                    bot, "gametest_standalone_ordinary_ledger");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot,
                    withCheckpoint(runtime, standalone));
            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
            require(context,
                    "mission_restore_orphaned_ordinary_mining_ledger"
                            .equals(result.reason()),
                    "standalone mining ledger was silently discarded: "
                            + result.reason());
            require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                    "standalone mining ledger restored an active plan");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 100)
    public void rareServiceRestoreRejectsSelfConsistentWrongMissionTarget(TestContext context) {
        String name = "RareServiceWrongTargetGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        giveRareMissionReadiness(bot, 8);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal), "mining goal setup failed");

        context.runAtTick(1, () -> {
            MissionRuntimeRecord before = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, before.active() != null, "missing active mining mission");
            Map<String, String> checkpoint = new LinkedHashMap<>(before.active().checkpoint());
            require(context, "MINING_SERVICE".equals(checkpoint.get("task_kind"))
                            && "RARE_ORE_BATCH".equals(checkpoint.get("task.service_profile")),
                    "fixture did not retain the rare service checkpoint: "
                            + checkpointSummary(checkpoint));

            // Forge a checkpoint that is internally valid for target 72. Restore must still reject
            // it because the top-level mission target is 8; service self-consistency is insufficient.
            MiningServiceTask.ServicePolicy wrongPolicy =
                    MiningServiceTask.ServicePolicy.rareOreBatch(72, 0, 0);
            checkpoint.put("task.service_target_count", "72");
            checkpoint.put("task.target_tool_usable", String.valueOf(
                    wrongPolicy.targetToolUsableDurability()));
            checkpoint.put("task.channel_tool_usable", String.valueOf(
                    wrongPolicy.channelToolUsableDurability()));
            checkpoint.put("task.torch_min_count", String.valueOf(
                    wrongPolicy.torchMinCount()));
            checkpoint.put("task.food_min_units", String.valueOf(
                    wrongPolicy.foodMinUnits()));
            checkpoint.put("task.emergency_blocks_reserved", String.valueOf(
                    wrongPolicy.emergencyBlocksReserved()));
            checkpoint.put("task.future_stick_reserve", String.valueOf(
                    wrongPolicy.futureStickReserve()));
            MissionRuntimeRecord forged = withCheckpoint(before, checkpoint);

            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_wrong_rare_service_target");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot, forged);

            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
            require(context, "mission_restore_incompatible_rare_ore_service_checkpoint"
                            .equals(result.reason()),
                    "wrong rare service target bypassed mission identity validation: "
                            + result.reason());
            require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                    "wrong rare service target restored an active mission");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 160)
    public void boundaryRareServiceRestoreRejectsMissingMiningNamespace(TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context, "RareServiceMissingMiningGT");
        AIPlayerEntity bot = fixture.bot();
        giveRareMissionReadiness(bot, 16);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 16);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "two-batch mining goal setup failed");
        AtomicBoolean firstBatchFed = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!firstBatchFed.get()) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
                    fillWithGlassUntilFreeSlots(bot, 3);
                    firstBatchFed.set(true);
                } else if (context.getTick() > 80) {
                    context.throwGameTestException(
                            "boundary-zero service never handed off to OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            boolean boundaryService = "MINING_SERVICE".equals(checkpoint.get("task_kind"))
                    && "RARE_ORE_BATCH".equals(checkpoint.get("task.service_profile"))
                    && "8".equals(checkpoint.get("task.service_boundary"));
            if (boundaryService) {
                require(context, checkpoint.containsKey("mining.task_schema")
                                && checkpoint.containsKey("mining.face"),
                        "boundary-eight fixture lacked its original mining namespace: "
                                + checkpointSummary(checkpoint));
                Map<String, String> missingMining = new LinkedHashMap<>(checkpoint);
                missingMining.keySet().removeIf(key -> key.startsWith("mining."));
                MissionRuntimeRecord corrupted = withCheckpoint(runtime, missingMining);

                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_boundary_service_missing_mining");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, corrupted);

                GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
                require(context,
                        "mission_restore_incompatible_rare_ore_service_checkpoint"
                                .equals(result.reason()),
                        "boundary-eight rare service accepted a missing mining namespace: "
                                + result.reason());
                require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "orphaned boundary-eight service restored an active mission");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 135) {
                context.throwGameTestException(
                        "fixture never reached boundary-eight rare service: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 140)
    public void firstRareTorchEpochFailureSchedulesOneServiceAndPreservesCursor(TestContext context) {
        String name = "RareTorchRetryGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        giveRareRetrySupplies(bot);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal), "rare retry goal setup failed");
        AtomicBoolean forged = new AtomicBoolean();
        AtomicReference<Integer> expectedBudget = new AtomicReference<>();
        AtomicReference<String> expectedFace = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!forged.get()) {
                if (!"MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    if (context.getTick() > 70) {
                        context.throwGameTestException(
                                "rare retry fixture never reached OreDig: "
                                        + checkpointSummary(checkpoint));
                    }
                    return;
                }
                MissionRuntimeRecord exhausted = withOreResourceState(
                        runtime, 40, 0, 0);
                expectedBudget.set(Integer.parseInt(
                        exhausted.active().checkpoint().get("mining.budget_used")));
                expectedFace.set(exhausted.active().checkpoint().get("mining.face"));
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_rare_torch_retry");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, exhausted);
                forged.set(true);
                return;
            }

            if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))) {
                require(context, "1".equals(checkpoint.get("rare_resource_retries_used"))
                                && "1".equals(checkpoint.get("mining.resource_epoch"))
                                && "0".equals(checkpoint.get("mining.torch_placements")),
                        "first torch failure did not atomically debit/advance one epoch: "
                                + checkpointSummary(checkpoint));
                require(context, Integer.parseInt(checkpoint.get("mining.budget_used"))
                                == expectedBudget.get() + 1
                                && expectedFace.get().equals(checkpoint.get("mining.face")),
                        "resource service changed more than the one detection tick: "
                                + checkpointSummary(checkpoint));
                require(context, "RARE_ORE_BATCH".equals(
                                checkpoint.get("task.service_profile"))
                                && "0".equals(checkpoint.get("task.service_boundary")),
                        "retry did not insert the fresh boundary-zero rare service: "
                                + checkpointSummary(checkpoint));
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 110) {
                context.throwGameTestException(
                        "first torch epoch failure did not hand off to service: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 600)
    public void firstRareChannelToolFailureSchedulesOneServiceAndPreservesCursor(
            TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context, "RareChannelRetryGT");
        AIPlayerEntity bot = fixture.bot();
        giveDiamond64Readiness(bot);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "rare channel retry goal setup failed");
        AtomicBoolean exhausted = new AtomicBoolean();
        AtomicBoolean sawService = new AtomicBoolean();
        AtomicReference<String> missionId = new AtomicReference<>();
        AtomicReference<String> expectedFace = new AtomicReference<>();
        AtomicReference<Integer> expectedBudget = new AtomicReference<>();
        AtomicReference<String> serviceFace = new AtomicReference<>();
        AtomicReference<Integer> serviceBudget = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (!exhausted.get()) {
                if (active instanceof OreDigTask
                        && "MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    require(context, "64".equals(checkpoint.get("task.rare_mission_target")),
                            "channel retry lost long rare mission identity: "
                                    + checkpointSummary(checkpoint));
                    // Pin the live cursor to one adjacent, factual channel cell before damaging
                    // the tool pool. Strip-leg direction/length depend on the absolute GameTest
                    // placement; allowing that exploration to choose the failure face made this
                    // service-ledger test nondeterministically spend hundreds of unrelated ticks.
                    BlockPos face = bot.getBlockPos().toImmutable();
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            BlockPos cell = face.add(dx, 0, dz);
                            bot.getServerWorld().setBlockState(cell.down(),
                                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);
                            bot.getServerWorld().setBlockState(cell,
                                    Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                            bot.getServerWorld().setBlockState(cell.up(),
                                    Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                        }
                    }
                    bot.getServerWorld().setBlockState(face.north(),
                            Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);
                    bot.getServerWorld().setBlockState(face.north().up(),
                            Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);
                    Map<String, String> controlled = new LinkedHashMap<>(checkpoint);
                    String encodedFace = face.getX() + "," + face.getY() + "," + face.getZ();
                    for (String prefix : Set.of("task.", "mining.")) {
                        controlled.put(prefix + "origin", encodedFace);
                        controlled.put(prefix + "face", encodedFace);
                        controlled.put(prefix + "direction", "0");
                        controlled.put(prefix + "leg", "0");
                        controlled.put(prefix + "steps_left", "1");
                        controlled.put(prefix + "leg_length", "48");
                        clearOrePhysicalLedger(controlled, prefix);
                    }
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_first_rare_channel_cell");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, controlled));
                    MissionRuntimeRecord restoredRuntime = GoalExecutor.INSTANCE.captureRuntime(bot);
                    Map<String, String> restoredCheckpoint = restoredRuntime.active() == null
                            ? Map.of() : restoredRuntime.active().checkpoint();
                    require(context, TaskManager.INSTANCE.getActive(bot)
                                    .filter(OreDigTask.class::isInstance).isPresent()
                                    && "MINE_ORE".equals(restoredCheckpoint.get("task_kind")),
                            "controlled rare channel cursor did not restore: "
                                    + checkpointSummary(restoredCheckpoint));
                    missionId.set(restoredRuntime.active().missionId());
                    expectedFace.set(restoredCheckpoint.get("task.face"));
                    expectedBudget.set(Integer.parseInt(
                            restoredCheckpoint.get("task.budget_used")));
                    int stoneLike = InventoryAction.countItem(bot, Items.COBBLESTONE);
                    int retryPool = MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE;
                    int excessStone = stoneLike - retryPool;
                    require(context, excessStone >= 0
                                    && (excessStone == 0 || InventoryAction.removeItems(
                                    bot, Items.COBBLESTONE, excessStone))
                                    && InventoryAction.countItem(bot, Items.COBBLESTONE)
                                    == retryPool,
                            "retry fixture did not retain the exact emergency+epoch1 stone pool: "
                                    + InventoryAction.countItem(bot, Items.COBBLESTONE));
                    // The service contract needs one fully owned channel break followed by an
                    // exhausted pool; it does not need to traverse a coordinate-dependent strip
                    // leg with every bootstrap pick. Retain exactly one raw-one pick so adding
                    // unrelated GameTests cannot multiply the bounded assertion window before
                    // publishing the same typed failure.
                    int channelPicks = InventoryAction.countItem(bot, Items.STONE_PICKAXE);
                    require(context, channelPicks >= 1
                                    && (channelPicks == 1 || InventoryAction.removeItems(
                                    bot, Items.STONE_PICKAXE, channelPicks - 1))
                                    && InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 1,
                            "retry fixture did not isolate one final channel pick");
                    exhaustStonePickaxes(bot);
                    equipHealthyIronPickaxe(context, bot);
                    exhausted.set(true);
                } else if (context.getTick() > 100) {
                    context.throwGameTestException(
                            "rare channel fixture never reached OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            require(context, !(active instanceof ResupplyTask),
                    "long rare channel failure used generic ResupplyTask");
            if (active instanceof OreDigTask
                    && "MINE_ORE".equals(checkpoint.get("task_kind"))) {
                if (sawService.get()) {
                    int resumedBudget = Integer.parseInt(
                            checkpoint.get("task.budget_used"));
                    require(context, missionId.get().equals(runtime.active().missionId())
                                    && "1".equals(checkpoint.get("rare_resource_retries_used"))
                                    && "1".equals(checkpoint.get("task.resource_epoch"))
                                    && serviceFace.get().equals(checkpoint.get("task.face"))
                                    && resumedBudget >= serviceBudget.get()
                                    && resumedBudget <= serviceBudget.get() + 1
                                    && hasUsableStonePickaxe(bot),
                            "rare channel service did not resume the exact OreDig batch: "
                                    + checkpointSummary(checkpoint));
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                    context.complete();
                    return;
                }
                expectedFace.set(checkpoint.get("task.face"));
                expectedBudget.set(Integer.parseInt(checkpoint.get("task.budget_used")));
                return;
            }
            if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))) {
                require(context, missionId.get().equals(runtime.active().missionId())
                                && "1".equals(checkpoint.get("rare_resource_retries_used"))
                                && "1".equals(checkpoint.get("mining.resource_epoch"))
                                && "0".equals(checkpoint.get("mining.torch_placements")),
                        "rare channel failure did not atomically advance one epoch: "
                                + checkpointSummary(checkpoint));
                require(context, expectedFace.get().equals(checkpoint.get("mining.face"))
                                && Integer.parseInt(checkpoint.get("mining.budget_used"))
                                >= expectedBudget.get()
                                && Integer.parseInt(checkpoint.get("mining.budget_used"))
                                <= expectedBudget.get() + 1,
                        "rare channel service refreshed cursor or skipped hard budget: "
                                + checkpointSummary(checkpoint));
                require(context, "RARE_ORE_BATCH".equals(
                                checkpoint.get("task.service_profile"))
                                && "0".equals(checkpoint.get("task.service_boundary")),
                        "rare channel failure did not insert boundary-zero service: "
                                + checkpointSummary(checkpoint));
                require(context, String.valueOf(MiningBudget.EMERGENCY_STONE_LIKE)
                                .equals(checkpoint.get("task.emergency_blocks_reserved")),
                        "resource retry did not release only its channel-stone pool: "
                                + checkpointSummary(checkpoint));
                sawService.set(true);
                serviceFace.set(checkpoint.get("mining.face"));
                serviceBudget.set(Integer.parseInt(checkpoint.get("mining.budget_used")));
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 560) {
                context.throwGameTestException(
                        "rare channel failure did not complete service and resume: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 600)
    public void sameBatchEpochOneChannelToolFailureIsTerminalWithoutAnotherService(
            TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context, "RareChannelRetryExhaustedGT");
        AIPlayerEntity bot = fixture.bot();
        giveDiamond64Readiness(bot);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "exhausted rare channel goal setup failed");
        AtomicBoolean forged = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (!forged.get()) {
                if (active instanceof OreDigTask
                        && "MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    BlockPos face = bot.getBlockPos().toImmutable();
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            BlockPos cell = face.add(dx, 0, dz);
                            bot.getServerWorld().setBlockState(cell.down(),
                                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);
                            bot.getServerWorld().setBlockState(cell,
                                    Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                            bot.getServerWorld().setBlockState(cell.up(),
                                    Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                        }
                    }
                    bot.getServerWorld().setBlockState(face.north(),
                            Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);
                    bot.getServerWorld().setBlockState(face.north().up(),
                            Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);

                    MissionRuntimeRecord epochOne = withOreResourceState(runtime, 0, 1, 1);
                    Map<String, String> controlled = new LinkedHashMap<>(
                            epochOne.active().checkpoint());
                    // The 64-target mission owns a four-epoch margin pool; exhaust it so this
                    // same-batch epoch-one failure exercises the terminal branch.
                    controlled.put("rare_epoch_margin_used", String.valueOf(
                            MiningBudget.rareMissionEpochMargin(
                                    MiningBudget.rareMissionBatchCount(64))));
                    String encodedFace = face.getX() + "," + face.getY() + "," + face.getZ();
                    for (String prefix : Set.of("task.", "mining.")) {
                        controlled.put(prefix + "origin", encodedFace);
                        controlled.put(prefix + "face", encodedFace);
                        controlled.put(prefix + "direction", "0");
                        controlled.put(prefix + "leg", "0");
                        controlled.put(prefix + "steps_left", "1");
                        controlled.put(prefix + "leg_length", "48");
                        clearOrePhysicalLedger(controlled, prefix);
                    }
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_second_rare_channel_retry");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(epochOne, controlled));
                    int channelPicks = InventoryAction.countItem(bot, Items.STONE_PICKAXE);
                    require(context, channelPicks >= 1
                                    && (channelPicks == 1 || InventoryAction.removeItems(
                                    bot, Items.STONE_PICKAXE, channelPicks - 1))
                                    && InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 1,
                            "same-batch channel fixture did not isolate one final stone pick");
                    exhaustStonePickaxes(bot);
                    equipHealthyIronPickaxe(context, bot);
                    forged.set(true);
                } else if (context.getTick() > 100) {
                    context.throwGameTestException(
                            "same-batch epoch-one channel fixture never reached OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            require(context, !(active instanceof ResupplyTask),
                    "exhausted long rare channel failure used generic ResupplyTask");
            require(context, !"MINING_SERVICE".equals(checkpoint.get("task_kind")),
                    "exhausted rare channel failure scheduled a second service");
            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
            if (result != null) {
                require(context,
                        "need_mining_channel_tool:minecraft:stone_pickaxe"
                                .equals(result.reason()),
                            "same-batch epoch-one channel failure reported the wrong terminal reason: "
                                + result.reason());
                require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "same-batch epoch-one channel failure retained an active mission");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                context.complete();
            } else if (context.getTick() > 560) {
                context.throwGameTestException(
                        "same-batch epoch-one channel failure did not terminate: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 140)
    public void sameBatchEpochOneTorchExhaustionIsRejectedWithoutAnotherService(
            TestContext context) {
        String name = "RareTorchRetryExhaustedGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        giveDiamond64Readiness(bot);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal), "rare retry goal setup failed");
        AtomicBoolean forged = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!forged.get()) {
                if (!"MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    if (context.getTick() > 70) {
                        context.throwGameTestException(
                                "same-batch epoch-one torch fixture never reached OreDig: "
                                        + checkpointSummary(checkpoint));
                    }
                    return;
                }
                MissionRuntimeRecord exhausted = withOreResourceState(
                        runtime, 40, 1, 1);
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_second_rare_torch_retry");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, exhausted);
                forged.set(true);
                return;
            }

            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
            if (result != null) {
                require(context, "ore_dig_torch_epoch_exhausted:placed=40:epoch=1"
                                .equals(result.reason()),
                        "same-batch epoch-one torch failure reported the wrong terminal reason: "
                                + result.reason());
                require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "same-batch epoch-one torch failure scheduled another service");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (context.getTick() > 110) {
                context.throwGameTestException(
                        "same-batch epoch-one torch failure did not terminate: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 600)
    public void epochOneTimeoutWithMissionMarginSurvivesAndDrawsOneEpoch(
            TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context, "RareMarginTimeoutGT");
        AIPlayerEntity bot = fixture.bot();
        giveDiamond64Readiness(bot);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "margin timeout goal setup failed");
        AtomicBoolean forged = new AtomicBoolean();
        int epochOneWindow = MiningMissionBudget.rareOreDigCumulativeHardWindowTicks(1);

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (!forged.get()) {
                if (active instanceof OreDigTask
                        && "MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    // Forge the F2 shape: epoch one (the per-batch retry) has consumed its exact
                    // cumulative 48,000-tick window while the mission margin pool is untouched.
                    MissionRuntimeRecord epochOne = withOreResourceState(runtime, 0, 1, 1);
                    Map<String, String> exhausted = new LinkedHashMap<>(
                            epochOne.active().checkpoint());
                    for (String prefix : Set.of("task.", "mining.")) {
                        exhausted.put(prefix + "budget_used",
                                String.valueOf(epochOneWindow));
                        exhausted.put(prefix + "last_progress_budget",
                                String.valueOf(epochOneWindow));
                    }
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_rare_margin_timeout");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(epochOne, exhausted));
                    forged.set(true);
                } else if (context.getTick() > 200) {
                    context.throwGameTestException(
                            "margin timeout fixture never reached OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))) {
                require(context, "2".equals(checkpoint.get("rare_resource_retries_used"))
                                && "2".equals(checkpoint.get("mining.resource_epoch"))
                                && "0".equals(checkpoint.get("mining.torch_placements"))
                                && "1".equals(checkpoint.get("rare_epoch_margin_used")),
                        "epoch-one timeout did not atomically draw one margin epoch: "
                                + checkpointSummary(checkpoint));
                require(context, String.valueOf(epochOneWindow).equals(
                                checkpoint.get("mining.budget_used")),
                        "margin draw refreshed the monotonic OreDig hard budget: "
                                + checkpointSummary(checkpoint));
                require(context, "RARE_ORE_BATCH".equals(
                                checkpoint.get("task.service_profile"))
                                && "0".equals(checkpoint.get("task.service_boundary")),
                        "margin draw did not insert the fresh boundary-zero rare service: "
                                + checkpointSummary(checkpoint));
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 560) {
                context.throwGameTestException(
                        "epoch-one timeout with margin available terminated the mission: "
                                + GoalExecutor.INSTANCE.lastResult(bot)
                                .map(GoalResult::reason).orElse("no_result")
                                + " " + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 600)
    public void epochTimeoutWithExhaustedMarginPoolStaysTerminal(TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context, "RareMarginExhaustedGT");
        AIPlayerEntity bot = fixture.bot();
        giveDiamond64Readiness(bot);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "exhausted margin goal setup failed");
        AtomicBoolean forged = new AtomicBoolean();
        int epochOneWindow = MiningMissionBudget.rareOreDigCumulativeHardWindowTicks(1);
        int marginPool = MiningBudget.rareMissionEpochMargin(
                MiningBudget.rareMissionBatchCount(64));

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (!forged.get()) {
                if (active instanceof OreDigTask
                        && "MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    MissionRuntimeRecord epochOne = withOreResourceState(runtime, 0, 1, 1);
                    Map<String, String> exhausted = new LinkedHashMap<>(
                            epochOne.active().checkpoint());
                    // Same timeout shape as the margin-draw test, but the durable mission ledger
                    // proves every margin epoch has already been spent by earlier batches.
                    exhausted.put("rare_epoch_margin_used", String.valueOf(marginPool));
                    for (String prefix : Set.of("task.", "mining.")) {
                        exhausted.put(prefix + "budget_used",
                                String.valueOf(epochOneWindow));
                        exhausted.put(prefix + "last_progress_budget",
                                String.valueOf(epochOneWindow));
                    }
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_rare_margin_exhausted");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(epochOne, exhausted));
                    forged.set(true);
                } else if (context.getTick() > 200) {
                    context.throwGameTestException(
                            "exhausted margin fixture never reached OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            require(context, !"MINING_SERVICE".equals(checkpoint.get("task_kind")),
                    "exhausted margin pool still scheduled another rare service");
            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
            if (result != null) {
                require(context, result.reason() != null
                                && result.reason().startsWith("ore_dig_timeout collected="),
                        "exhausted margin timeout reported the wrong terminal reason: "
                                + result.reason());
                require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "exhausted margin timeout retained an active mission");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                context.complete();
            } else if (context.getTick() > 560) {
                context.throwGameTestException(
                        "exhausted margin timeout did not terminate: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 800)
    public void committedRareBatchResetsEpochAndNextBatchCanRetry(TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context, "RareEpochPerBatchGT");
        AIPlayerEntity bot = fixture.bot();
        giveDiamond64Readiness(bot);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 16);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "two-batch rare epoch goal setup failed");
        AtomicInteger phase = new AtomicInteger();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();

            if (phase.get() == 0) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    // Start from a factual open epoch-one batch. The focused same-batch tests above
                    // prove that another failure here is terminal; this test proves that a successful
                    // closed commit releases only this batch's debit.
                    MissionRuntimeRecord epochOne = withOreResourceState(runtime, 0, 1, 1);
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_first_batch_epoch_one");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, epochOne);
                    phase.set(1);
                } else if (context.getTick() > 180) {
                    context.throwGameTestException(
                            "two-batch fixture never reached first OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            if (phase.get() == 1) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    require(context, "1".equals(checkpoint.get(
                                    "rare_resource_retries_used"))
                                    && "1".equals(checkpoint.get("task.resource_epoch"))
                                    && "true".equals(checkpoint.get("task.batch_open")),
                            "first batch did not restore its open epoch-one identity: "
                                    + checkpointSummary(checkpoint));
                    InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
                    require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 8,
                            "first batch fixture did not deliver exactly eight diamonds");
                    phase.set(2);
                } else if (context.getTick() > 220) {
                    context.throwGameTestException(
                            "first epoch-one batch did not resume: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            if (phase.get() == 2) {
                boolean boundaryEight = "MINING_SERVICE".equals(checkpoint.get("task_kind"))
                        && "RARE_ORE_BATCH".equals(checkpoint.get("task.service_profile"))
                        && "8".equals(checkpoint.get("task.service_boundary"));
                if (boundaryEight) {
                    require(context, "0".equals(checkpoint.get(
                                    "rare_resource_retries_used"))
                                    && "false".equals(checkpoint.get("mining.batch_open"))
                                    && "0".equals(checkpoint.get("mining.resource_epoch"))
                                    && "0".equals(checkpoint.get("mining.torch_placements")),
                            "closed first batch did not atomically settle its resource epoch: "
                                    + checkpointSummary(checkpoint));
                    // Remove the first batch's durable-face lighting while the boundary service
                    // still owns the mission. Its remaining ticks give the light engine time to
                    // publish darkness before the successor OreDig reaches its placement boundary.
                    BlockPos center = bot.getBlockPos().toImmutable();
                    for (BlockPos pos : BlockPos.iterate(
                            center.add(-8, -3, -8), center.add(8, 3, 8))) {
                        if (bot.getServerWorld().getBlockState(pos).isOf(Blocks.TORCH)
                                || bot.getServerWorld().getBlockState(pos)
                                .isOf(Blocks.WALL_TORCH)) {
                            bot.getServerWorld().setBlockState(
                                    pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                        }
                    }
                    phase.set(3);
                } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                        || context.getTick() > 360) {
                    context.throwGameTestException(
                            "first batch never committed at boundary eight: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            if (phase.get() == 3) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    require(context, "0".equals(checkpoint.get(
                                    "rare_resource_retries_used"))
                                    && "0".equals(checkpoint.get("task.resource_epoch"))
                                    && "true".equals(checkpoint.get("task.batch_open")),
                            "second batch did not start with a fresh epoch-zero debit: "
                                    + checkpointSummary(checkpoint));
                    BlockPos center = bot.getBlockPos().toImmutable();
                    require(context, bot.getServerWorld().getLightLevel(
                                    net.minecraft.world.LightType.BLOCK, center) < 8,
                            "boundary service did not settle block light before the second batch");
                    MissionRuntimeRecord exhausted = withOreResourceState(
                            runtime, MiningBudget.RARE_BATCH_TORCH_LIMIT, 0, 0);
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_second_batch_epoch_zero_exhausted");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, exhausted);
                    phase.set(4);
                } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                        || context.getTick() > 680) {
                    context.throwGameTestException(
                            "boundary service never handed off to the second batch: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))) {
                require(context, "RARE_ORE_BATCH".equals(
                                checkpoint.get("task.service_profile"))
                                && "8".equals(checkpoint.get("task.service_boundary"))
                                && "1".equals(checkpoint.get(
                                "rare_resource_retries_used"))
                                && "true".equals(checkpoint.get("mining.batch_open"))
                                && "1".equals(checkpoint.get("mining.resource_epoch"))
                                && "0".equals(checkpoint.get("mining.torch_placements")),
                        "second batch could not independently advance from epoch zero to one: "
                                + checkpointSummary(checkpoint));
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 760) {
                context.throwGameTestException(
                        "second batch retry did not schedule its own service: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 140)
    public void firstRareInventoryFailureSchedulesOneCursorBoundService(TestContext context) {
        String name = "RareInventoryServiceGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        giveRareMissionReadiness(bot, 8);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "rare inventory-service goal setup failed");
        AtomicBoolean filled = new AtomicBoolean();
        AtomicReference<Map<String, String>> beforeFailure = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!filled.get()) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    beforeFailure.set(Map.copyOf(checkpoint));
                    fillWithGlassUntilFreeSlots(bot, 0);
                    require(context, freeMainSlots(bot) == 0,
                            "fixture did not produce a factual full inventory");
                    filled.set(true);
                } else if (context.getTick() > 80) {
                    context.throwGameTestException(
                            "rare inventory fixture never reached OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))) {
                Map<String, String> before = beforeFailure.get();
                require(context, "RARE_ORE_BATCH".equals(
                                checkpoint.get("task.service_profile"))
                                && "true".equals(
                                checkpoint.get("mining.inventory_service_used")),
                        "first full inventory did not debit one sealed rare service: "
                                + checkpointSummary(checkpoint));
                require(context, "0".equals(checkpoint.get("rare_resource_retries_used")),
                        "inventory service consumed the independent torch retry");
                require(context, Integer.parseInt(checkpoint.get("mining.budget_used"))
                                == Integer.parseInt(before.get("task.budget_used")) + 1,
                        "inventory service refreshed or overcharged the OreDig hard budget: "
                                + checkpointSummary(checkpoint));
                for (String suffix : Set.of("origin", "face", "direction", "leg",
                        "steps_left", "leg_length", "batches", "last_progress_budget",
                        "pending_pickup_pos", "pending_pickup_inventory",
                        "pending_pickup_started_budget", "pickup_gain_budget",
                        "active_break_pos", "active_break_inventory")) {
                    require(context, java.util.Objects.equals(
                                    before.get("task." + suffix),
                                    checkpoint.get("mining." + suffix)),
                            "inventory service changed OreDig " + suffix + ": "
                                    + checkpointSummary(checkpoint));
                }
                require(context, checkpoint.get("mining.face").equals(
                                checkpoint.get("task.work_face")),
                        "inventory service was not bound to the failed OreDig cursor");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 115) {
                context.throwGameTestException(
                        "first rare inventory failure did not hand off to service: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 140)
    public void secondRareInventoryFailureIsTerminalWithoutAnotherService(TestContext context) {
        String name = "RareInventoryServiceExhaustedGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        giveRareMissionReadiness(bot, 8);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "rare inventory-service exhaustion goal setup failed");
        AtomicBoolean forged = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!forged.get()) {
                if (!"MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    if (context.getTick() > 80) {
                        context.throwGameTestException(
                                "second inventory fixture never reached OreDig: "
                                        + checkpointSummary(checkpoint));
                    }
                    return;
                }
                Map<String, String> alreadyServiced = new LinkedHashMap<>(checkpoint);
                alreadyServiced.put("task.inventory_service_used", "true");
                alreadyServiced.put("mining.inventory_service_used", "true");
                fillWithGlassUntilFreeSlots(bot, 0);
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_second_rare_inventory_service");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(
                        bot, withCheckpoint(runtime, alreadyServiced));
                forged.set(true);
                return;
            }

            require(context, !"MINING_SERVICE".equals(checkpoint.get("task_kind")),
                    "second inventory failure scheduled another service");
            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
            if (result != null) {
                require(context, "ore_dig_inventory_service_required".equals(result.reason()),
                        "second inventory failure reported the wrong terminal reason: "
                                + result.reason());
                require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "second inventory failure retained an active mission");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (context.getTick() > 115) {
                context.throwGameTestException(
                        "second inventory failure did not terminate: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 120)
    public void smallRareFullInventorySchedulesOneCursorBoundCapacityService(
            TestContext context) {
        String name = "SmallRareInventoryGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 7);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "small rare full-inventory goal setup failed");
        AtomicBoolean filled = new AtomicBoolean();
        AtomicReference<Map<String, String>> beforeFailure = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!filled.get()) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    require(context, "0".equals(checkpoint.get("task.rare_mission_target")),
                            "small rare goal received a long mission identity");
                    beforeFailure.set(Map.copyOf(checkpoint));
                    fillWithGlassUntilFreeSlots(bot, 0);
                    filled.set(true);
                } else if (context.getTick() > 65) {
                    context.throwGameTestException(
                            "small rare fixture never reached OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))) {
                Map<String, String> before = beforeFailure.get();
                require(context, "ORE_BATCH".equals(checkpoint.get("task.service_profile"))
                                && !"RARE_ORE_BATCH".equals(
                                checkpoint.get("task.service_profile"))
                                && "mining".equals(checkpoint.get("capacity_parent"))
                                && "true".equals(
                                checkpoint.get("mining.inventory_service_used")),
                        "small rare full inventory did not schedule one ORE_BATCH capacity service: "
                                + checkpointSummary(checkpoint));
                require(context, java.util.Objects.equals(
                                        before.get("task.ore_fingerprint"),
                                        checkpoint.get("mining.ore_fingerprint"))
                                && java.util.Objects.equals(
                                before.get("task.face"), checkpoint.get("mining.face"))
                                && java.util.Objects.equals(
                                before.get("task.face"), checkpoint.get("task.work_face")),
                        "small rare capacity service changed its ore family or work cursor: "
                                + checkpointSummary(checkpoint));
                for (String suffix : Set.of("origin", "direction", "leg", "steps_left",
                        "leg_length", "batches")) {
                    require(context, java.util.Objects.equals(
                                    before.get("task." + suffix),
                                    checkpoint.get("mining." + suffix)),
                            "small rare capacity service changed OreDig " + suffix + ": "
                                    + checkpointSummary(checkpoint));
                }
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 110) {
                context.throwGameTestException(
                        "small rare full inventory did not enter capacity service: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 150)
    public void capacityServiceRestoreRejectsUndebitedDeclaredParent(
            TestContext context) {
        String name = "CapacityUndebitedParentGT";
        withRunningSmallRareCapacityService(context, name,
                (bot, goal, runtime, checkpoint) -> {
                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.put("mining.inventory_service_used", "false");
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_capacity_parent_false_bit");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, forged));
                    GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
                    require(context,
                            "mission_restore_incompatible_capacity_parent_checkpoint"
                                    .equals(result.reason())
                                    && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                            "undebited capacity parent restored or reported the wrong reason: "
                                    + result.reason());
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 150)
    public void capacityServiceRestoreRejectsMissingDeclaredParent(
            TestContext context) {
        String name = "CapacityMissingParentGT";
        withRunningSmallRareCapacityService(context, name,
                (bot, goal, runtime, checkpoint) -> {
                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.keySet().removeIf(key -> key.startsWith("mining."));
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_capacity_parent_missing");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, forged));
                    GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
                    require(context,
                            "mission_restore_incompatible_capacity_parent_checkpoint"
                                    .equals(result.reason())
                                    && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                            "missing capacity parent restored or reported the wrong reason: "
                                    + result.reason());
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 150)
    public void capacityServiceRestoreRejectsWatermarkAheadOfParent(
            TestContext context) {
        String name = "CapacityWatermarkAheadGT";
        withRunningSmallRareCapacityService(context, name,
                (bot, goal, runtime, checkpoint) -> {
                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    int delivered = Integer.parseInt(
                            forged.getOrDefault("mining.delivered", "0"));
                    forged.put("capacity_parent_delivered",
                            String.valueOf(delivered + 1));
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_capacity_watermark_ahead");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, forged));
                    GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
                    require(context,
                            "mission_restore_incompatible_capacity_parent_checkpoint"
                                    .equals(result.reason())
                                    && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                            "capacity watermark ahead of delivered restored or reported wrong: "
                                    + result.reason());
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 150)
    public void capacityServiceRestoreRejectsWatermarkWithoutParent(
            TestContext context) {
        String name = "CapacityWatermarkOrphanGT";
        withRunningSmallRareCapacityService(context, name,
                (bot, goal, runtime, checkpoint) -> {
                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.remove("capacity_parent");
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_capacity_watermark_orphan");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, forged));
                    GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
                    require(context,
                            "mission_restore_incompatible_capacity_parent_checkpoint"
                                    .equals(result.reason())
                                    && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                            "orphaned capacity watermark restored or reported wrong: "
                                    + result.reason());
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 150)
    public void capacityServiceRestoreRequiresWatermarkAtServiceBoundary(
            TestContext context) {
        String name = "CapacityWatermarkBoundaryGT";
        withRunningSmallRareCapacityService(context, name,
                (bot, goal, runtime, checkpoint) -> {
                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.put("mining.delivered", "1");
                    forged.put("capacity_parent_delivered", "0");
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_capacity_watermark_boundary");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, forged));
                    GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
                    require(context,
                            "mission_restore_incompatible_capacity_parent_checkpoint"
                                    .equals(result.reason())
                                    && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                            "capacity service restored with a stale delivered watermark: "
                                    + result.reason());
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 150)
    public void capacityServiceRestoreRejectsStaleFaceAtServiceBoundary(
            TestContext context) {
        String name = "CapacityFaceBoundaryGT";
        withRunningSmallRareCapacityService(context, name,
                (bot, goal, runtime, checkpoint) -> {
                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    BlockPos face = decodePos(forged.get("mining.face"));
                    forged.put("capacity_parent_face", face.east().getX() + ","
                            + face.east().getY() + "," + face.east().getZ());
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_capacity_face_boundary");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, forged));
                    GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
                    require(context,
                            "mission_restore_incompatible_capacity_parent_checkpoint"
                                    .equals(result.reason())
                                    && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                            "capacity service restored with a stale face watermark: "
                                    + result.reason());
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 150)
    public void capacityServiceRestoreRejectsServiceCountAboveTarget(
            TestContext context) {
        String name = "CapacityServiceCountAheadGT";
        withRunningSmallRareCapacityService(context, name,
                (bot, goal, runtime, checkpoint) -> {
                    Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                    forged.put("capacity_parent_services_used", "8");
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_capacity_service_count_ahead");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, forged));
                    GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElseThrow();
                    require(context,
                            "mission_restore_incompatible_capacity_parent_checkpoint"
                                    .equals(result.reason())
                                    && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                            "capacity parent restored with service count above target: "
                                    + result.reason());
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 150)
    public void capacityServiceRestoreMigratesLegacyMissingWatermarkConservatively(
            TestContext context) {
        String name = "CapacityLegacyWatermarkGT";
        withRunningSmallRareCapacityService(context, name,
                (bot, goal, runtime, checkpoint) -> {
                    Map<String, String> legacy = new LinkedHashMap<>(checkpoint);
                    legacy.remove("capacity_parent_delivered");
                    legacy.remove("capacity_parent_face");
                    legacy.remove("capacity_parent_services_used");
                    String delivered = legacy.get("mining.delivered");
                    String face = legacy.get("mining.face");
                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_capacity_legacy_watermark");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, legacy));
                    MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restored.active() != null,
                            "legacy capacity parent was not restored");
                    Map<String, String> after = restored.active().checkpoint();
                    require(context, "MINING_SERVICE".equals(after.get("task_kind"))
                                    && "mining".equals(after.get("capacity_parent"))
                                    && java.util.Objects.equals(delivered,
                                    after.get("capacity_parent_delivered"))
                                    && java.util.Objects.equals(face,
                                    after.get("capacity_parent_face"))
                                    && "1".equals(after.get(
                                    "capacity_parent_services_used")),
                            "legacy capacity parent did not bind conservative watermarks: "
                                    + checkpointSummary(after));
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "capacityCompletionSafetyIsolation", tickLimit = 180)
    public void completedCapacityRetryDefersSafetyAndRestoresClosedCommit(
            TestContext context) {
        String name = "CapacityCompletionSafetyGT";
        withRunningSmallRareCapacityService(context, name,
                (bot, goal, runtime, checkpoint) -> {
                    Map<String, String> parent = namespace(checkpoint, "mining.");
                    Map<String, String> retry = new LinkedHashMap<>(checkpoint);
                    retry.keySet().removeIf(key -> key.startsWith("task."));
                    retry.put("task_kind", "MINE_ORE");
                    parent.forEach((key, value) -> retry.put("task." + key, value));

                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_capacity_retry_restore");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(
                            bot, withCheckpoint(runtime, retry));
                    MissionRuntimeRecord restoredRetry =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restoredRetry.active() != null,
                            "capacity retry restore lost its mission");
                    Map<String, String> running =
                            restoredRetry.active().checkpoint();
                    Task missionTask = TaskManager.INSTANCE.getActive(bot).orElse(null);
                    require(context, missionTask instanceof OreDigTask
                                    && "MINE_ORE".equals(running.get("task_kind"))
                                    && "mining".equals(running.get("capacity_parent"))
                                    && "true".equals(
                                    running.get("task.inventory_service_used")),
                            "marked capacity parent did not replay its exact OreDig retry: "
                                    + checkpointSummary(running));

                    for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
                        if (bot.getInventory().main.get(slot).isOf(Items.GLASS)) {
                            bot.getInventory().main.set(slot, ItemStack.EMPTY);
                            break;
                        }
                    }
                    bot.getInventory().markDirty();
                    giveItemToAtLeast(bot, Items.DIAMOND, 7);
                    for (int attempt = 0;
                         attempt < 4 && missionTask.state() == TaskState.RUNNING;
                         attempt++) {
                        missionTask.tick(bot);
                    }
                    require(context, missionTask.state() == TaskState.COMPLETED,
                            "capacity retry did not reach its factual terminal state: "
                                    + missionTask.state());
                    TaskManager.INSTANCE.tickAll(bot.getServer());
                    require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                            "TaskManager retained the completed capacity retry");

                    HoldingSafetyTask safety = new HoldingSafetyTask();
                    TaskManager.INSTANCE.assign(bot, safety,
                            TaskOrigin.safety("gametest_capacity_completion_safety"));
                    GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);
                    require(context, GoalExecutor.INSTANCE.hasActivePlan(bot)
                                    && GoalExecutor.INSTANCE.lastResult(bot).isEmpty()
                                    && TaskManager.INSTANCE.getActive(bot).orElse(null) == safety,
                            "terminal mission was abandoned or overwrote active safety");

                    MissionRuntimeRecord interrupted =
                            GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, interrupted.active() != null,
                            "safety-interrupted completion lost its mission snapshot");
                    Map<String, String> closed = interrupted.active().checkpoint();
                    Map<String, String> closedTask = namespace(closed, "task.");
                    Map<String, String> closedParent = namespace(closed, "mining.");
                    require(context, "MINE_ORE".equals(closed.get("task_kind"))
                                    && "mining".equals(closed.get("capacity_parent"))
                                    && "false".equals(closed.get("task.batch_open"))
                                    && closedTask.equals(closedParent)
                                    && GoalExecutor.validCommittedCapacityParent(
                                    OreDigTask.inspectCheckpoint(closedParent, 0),
                                    GoalStep.Kind.MINE_ORE,
                                    closedTask, closedParent),
                            "capture did not publish the exact closed capacity commit: "
                                    + checkpointSummary(closed));

                    TaskManager.INSTANCE.cancelIntentTasks(
                            bot, "gametest_capacity_completion_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, interrupted);
                    GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
                    require(context, result != null
                                    && result.goal().equals(goal)
                                    && result.status() == GoalResult.Status.COMPLETED
                                    && "already_satisfied".equals(result.reason())
                                    && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                            "closed capacity commit was rejected or replayed after restart: "
                                    + (result == null ? "missing" : result.status()
                                    + ":" + result.reason()));
                    AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                    context.complete();
                });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "goalExecutorPocketFailureStrict", tickLimit = 650)
    public void satisfiedGoalRestoresPocketFirstThenFailsWithOriginalTypedReason(
            TestContext context) {
        String name = "GoalPocketTypedFailureGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 7);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "active-pocket failure goal setup failed");
        AtomicBoolean filled = new AtomicBoolean();
        AtomicBoolean restartedAndTrapped = new AtomicBoolean();
        AtomicReference<String> missionId = new AtomicReference<>();
        AtomicReference<String> pocketLedger = new AtomicReference<>();
        AtomicReference<MiningServiceTask> restoredService = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!filled.get()) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    BlockPos face = decodePos(checkpoint.get("task.face"));
                    prepareDisposalPocket(bot, face, Direction.EAST);
                    prepareDisposalPocket(bot, face, Direction.WEST);
                    require(context, !InventoryAction.giveItem(
                                    bot, new ItemStack(Items.TUFF, 64)).isFailed(),
                            "active-pocket fixture could not add disposable tuff");
                    fillWithGlassUntilFreeSlots(bot, 0);
                    require(context, freeMainSlots(bot) == 0,
                            "active-pocket fixture did not fill inventory");
                    filled.set(true);
                } else if (context.getTick() > 80) {
                    context.throwGameTestException(
                            "active-pocket fixture never reached OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            if (!restartedAndTrapped.get()) {
                boolean committedPocket = "MINING_SERVICE".equals(
                        checkpoint.get("task_kind"))
                        && "SETTLE_DISPOSABLE".equals(
                        checkpoint.get("task.phase"))
                        && !checkpoint.getOrDefault(
                        "task.pocket_ledger", "").isBlank();
                if (!committedPocket) {
                    if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                            || context.getTick() > 300) {
                        context.throwGameTestException(
                                "capacity service never committed a disposal pocket: "
                                        + checkpointSummary(checkpoint));
                    }
                    return;
                }
                require(context, runtime.active() != null,
                        "committed pocket lost its mission record");
                missionId.set(runtime.active().missionId());
                pocketLedger.set(checkpoint.get("task.pocket_ledger"));
                giveItemToAtLeast(bot, Items.DIAMOND, 7);
                require(context, InventoryAction.countItem(bot, Items.DIAMOND) >= 7,
                        "fixture could not satisfy the goal before pocket restore");

                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_goal_pocket_satisfied_restart");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, runtime);
                MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
                require(context, restored.active() != null
                                && missionId.get().equals(restored.active().missionId()),
                        "satisfied restore discarded the active physical pocket");
                Map<String, String> after = restored.active().checkpoint();
                require(context, "MINING_SERVICE".equals(after.get("task_kind"))
                                && pocketLedger.get().equals(
                                after.get("task.pocket_ledger"))
                                && GoalExecutor.INSTANCE.lastResult(bot).isEmpty(),
                        "satisfied restore bypassed or changed the pocket service: "
                                + checkpointSummary(after));
                Object active = TaskManager.INSTANCE.getActive(bot).orElse(null);
                require(context, active instanceof MiningServiceTask,
                        "restored physical debt is not owned by MiningServiceTask");
                restoredService.set((MiningServiceTask) active);

                BlockPos face = decodePos(after.get("task.work_face"));
                BlockPos cage = face.north(4);
                bot.teleport(bot.getServerWorld(), cage.getX() + 0.5D,
                        cage.getY(), cage.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                buildBedrockCage(bot, cage);
                restartedAndTrapped.set(true);
                return;
            }

            if (runtime.active() != null
                    && !"MINING_SERVICE".equals(checkpoint.get("task_kind"))) {
                context.throwGameTestException(
                        "active pocket escaped into generic replan: "
                                + checkpointSummary(checkpoint));
                return;
            }
            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
            if (result == null) {
                if (context.getTick() > 610) {
                    context.throwGameTestException(
                            "unreachable restored pocket did not terminate");
                }
                return;
            }
            MiningServiceTask service = restoredService.get();
            require(context, service != null
                            && result.goal().equals(goal)
                            && result.status() == GoalResult.Status.FAILED
                            && result.reason().equals(service.failureReason())
                            && result.reason().startsWith(
                            "mining_service_disposal_unsealed_return_failed:")
                            && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                    "active pocket lost its original fail-closed result: "
                            + result.status() + ":" + result.reason());
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 140)
    public void usedSmallRareCapacityDebitMakesTheSecondFullInventoryTerminal(
            TestContext context) {
        String name = "SmallRareCapacityExhaustedGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 7);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "small rare capacity exhaustion goal setup failed");
        AtomicBoolean forged = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!forged.get()) {
                if (!"MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    if (context.getTick() > 80) {
                        context.throwGameTestException(
                                "small rare exhaustion fixture never reached OreDig: "
                                        + checkpointSummary(checkpoint));
                    }
                    return;
                }
                Map<String, String> alreadyServiced = new LinkedHashMap<>(checkpoint);
                alreadyServiced.put("task.inventory_service_used", "true");
                alreadyServiced.put("mining.inventory_service_used", "true");
                alreadyServiced.put("capacity_parent", "mining");
                alreadyServiced.put("capacity_parent_delivered", "0");
                fillWithGlassUntilFreeSlots(bot, 0);
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_second_small_rare_capacity_service");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(
                        bot, withCheckpoint(runtime, alreadyServiced));
                forged.set(true);
                return;
            }

            require(context, !"MINING_SERVICE".equals(checkpoint.get("task_kind")),
                    "used small-rare capacity debit scheduled another service");
            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
            if (result != null) {
                require(context, "ore_dig_inventory_service_required".equals(result.reason()),
                        "used small-rare capacity debit reported the wrong terminal reason: "
                                + result.reason());
                require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "used small-rare capacity debit retained an active mission");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (context.getTick() > 115) {
                context.throwGameTestException(
                        "used small-rare capacity debit did not terminate: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 150)
    public void advancedCapacityWorkFaceSchedulesSecondServiceAcrossRestart(
            TestContext context) {
        String name = "SmallRareCapacityFaceProgressGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 7);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "work-face capacity progress goal setup failed");
        AtomicBoolean restored = new AtomicBoolean();
        AtomicReference<Map<String, String>> progressed = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!restored.get()) {
                if (!"MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    if (context.getTick() > 80) {
                        context.throwGameTestException(
                                "work-face progress fixture never reached OreDig: "
                                        + checkpointSummary(checkpoint));
                    }
                    return;
                }
                BlockPos currentFace = decodePos(checkpoint.get("task.face"));
                BlockPos previousServiceFace = currentFace.west();
                Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                for (String prefix : Set.of("task.", "mining.")) {
                    forged.put(prefix + "inventory_service_used", "true");
                    clearOrePhysicalLedger(forged, prefix);
                }
                forged.put("capacity_parent", "mining");
                forged.put("capacity_parent_delivered", "0");
                forged.put("capacity_parent_face", previousServiceFace.getX() + ","
                        + previousServiceFace.getY() + "," + previousServiceFace.getZ());
                forged.put("capacity_parent_services_used", "1");
                fillWithGlassUntilFreeSlots(bot, 0);
                progressed.set(Map.copyOf(forged));
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_capacity_face_progress_restart");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(runtime, forged));
                require(context, GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "work-face progressed capacity parent was rejected during restart");
                restored.set(true);
                return;
            }

            if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))) {
                Map<String, String> before = progressed.get();
                require(context, "ORE_BATCH".equals(checkpoint.get("task.service_profile"))
                                && "mining".equals(checkpoint.get("capacity_parent"))
                                && "0".equals(checkpoint.get("capacity_parent_delivered"))
                                && "2".equals(checkpoint.get(
                                "capacity_parent_services_used"))
                                && checkpoint.get("mining.face").equals(
                                checkpoint.get("capacity_parent_face"))
                                && "true".equals(
                                checkpoint.get("mining.inventory_service_used")),
                        "strict work-face progress did not schedule the second capacity service: "
                                + checkpointSummary(checkpoint));
                require(context, Integer.parseInt(checkpoint.get("mining.budget_used"))
                                == Integer.parseInt(before.get("task.budget_used")) + 1,
                        "work-face capacity service refreshed or overcharged the OreDig hard "
                                + "budget: " + checkpointSummary(checkpoint));
                for (String suffix : Set.of("origin", "face", "direction", "leg",
                        "steps_left", "leg_length", "batches",
                        "last_progress_budget")) {
                    require(context, java.util.Objects.equals(
                                    before.get("task." + suffix),
                                    checkpoint.get("mining." + suffix)),
                            "work-face capacity service changed OreDig " + suffix + ": "
                                    + checkpointSummary(checkpoint));
                }
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 130) {
                context.throwGameTestException(
                        "work-face capacity retry did not enter its second service: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 140)
    public void capacityServiceCountCapRejectsAnotherProgressedHandoff(
            TestContext context) {
        String name = "SmallRareCapacityCountCapGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 7);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "capacity service-count cap goal setup failed");
        AtomicBoolean restored = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!restored.get()) {
                if (!"MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    if (context.getTick() > 80) {
                        context.throwGameTestException(
                                "capacity cap fixture never reached OreDig: "
                                        + checkpointSummary(checkpoint));
                    }
                    return;
                }
                BlockPos currentFace = decodePos(checkpoint.get("task.face"));
                BlockPos previousServiceFace = currentFace.west();
                Map<String, String> exhausted = new LinkedHashMap<>(checkpoint);
                for (String prefix : Set.of("task.", "mining.")) {
                    exhausted.put(prefix + "inventory_service_used", "true");
                    clearOrePhysicalLedger(exhausted, prefix);
                }
                exhausted.put("capacity_parent", "mining");
                exhausted.put("capacity_parent_delivered", "0");
                exhausted.put("capacity_parent_face", previousServiceFace.getX() + ","
                        + previousServiceFace.getY() + "," + previousServiceFace.getZ());
                exhausted.put("capacity_parent_services_used", "7");
                fillWithGlassUntilFreeSlots(bot, 0);
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_capacity_service_count_cap");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(runtime, exhausted));
                require(context, GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "bounded capacity parent was rejected before its retry ran");
                restored.set(true);
                return;
            }

            require(context, !"MINING_SERVICE".equals(checkpoint.get("task_kind")),
                    "exhausted capacity service count scheduled another handoff");
            GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
            if (result != null) {
                require(context, "ore_dig_inventory_service_required".equals(result.reason())
                                && !GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "capacity service-count cap reported the wrong terminal result: "
                                + result.reason());
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (context.getTick() > 120) {
                context.throwGameTestException(
                        "capacity service-count cap did not terminate the retry: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 170)
    public void progressedCapacityRetrySchedulesSecondServiceAcrossRestart(
            TestContext context) {
        String name = "SmallRareCapacityProgressGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 7);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "progressed capacity retry goal setup failed");
        AtomicBoolean restored = new AtomicBoolean();
        AtomicReference<Map<String, String>> progressed = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!restored.get()) {
                if (!"MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    if (context.getTick() > 80) {
                        context.throwGameTestException(
                                "progressed capacity fixture never reached OreDig: "
                                        + checkpointSummary(checkpoint));
                    }
                    return;
                }
                giveItemToAtLeast(bot, Items.DIAMOND, 1);
                fillWithGlassUntilFreeSlots(bot, 0);
                require(context, InventoryAction.countItem(bot, Items.DIAMOND) >= 1
                                && freeMainSlots(bot) == 0,
                        "progressed capacity fixture did not preserve its factual item/full state");
                Map<String, String> forged = new LinkedHashMap<>(checkpoint);
                for (String prefix : Set.of("task.", "mining.")) {
                    forged.put(prefix + "delivered", "1");
                    forged.put(prefix + "inventory_service_used", "true");
                    clearOrePhysicalLedger(forged, prefix);
                }
                forged.put("capacity_parent", "mining");
                forged.put("capacity_parent_delivered", "0");
                progressed.set(Map.copyOf(forged));
                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_progressed_capacity_restart");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(runtime, forged));
                require(context, GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "progressed capacity parent was rejected during restart");
                restored.set(true);
                return;
            }

            if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))) {
                Map<String, String> before = progressed.get();
                require(context, "ORE_BATCH".equals(checkpoint.get("task.service_profile"))
                                && "mining".equals(checkpoint.get("capacity_parent"))
                                && "1".equals(checkpoint.get("capacity_parent_delivered"))
                                && "1".equals(checkpoint.get("mining.delivered"))
                                && "true".equals(
                                checkpoint.get("mining.inventory_service_used")),
                        "strict delivered progress did not schedule the second capacity service: "
                                + checkpointSummary(checkpoint));
                for (String suffix : Set.of("origin", "face", "direction", "leg",
                        "steps_left", "leg_length", "batches")) {
                    require(context, java.util.Objects.equals(
                                    before.get("task." + suffix),
                                    checkpoint.get("mining." + suffix)),
                            "repeat capacity service changed OreDig " + suffix + ": "
                                    + checkpointSummary(checkpoint));
                }
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 145) {
                context.throwGameTestException(
                        "progressed capacity retry did not enter its second service: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 160)
    public void diamond64TailOfOneRestartsAndAdvancesItsRareResourceEpoch(TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context, "Diamond64TailEpochGT");
        AIPlayerEntity bot = fixture.bot();
        giveDiamond64Readiness(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 63));
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "diamond64 tail goal setup failed");
        AtomicBoolean forged = new AtomicBoolean();
        AtomicReference<Map<String, String>> exhaustedState = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!forged.get()) {
                if (!"MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    if (context.getTick() > 90) {
                        context.throwGameTestException(
                                "diamond64 tail never reached OreDig: "
                                        + checkpointSummary(checkpoint));
                    }
                    return;
                }
                require(context, "1".equals(checkpoint.get("task.target_count"))
                                && "64".equals(
                                checkpoint.get("task.rare_mission_target")),
                        "diamond64 tail lost its original mission identity: "
                                + checkpointSummary(checkpoint));

                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_tail_ordinary_restart");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, runtime);
                MissionRuntimeRecord restarted = GoalExecutor.INSTANCE.captureRuntime(bot);
                require(context, restarted.active() != null,
                        "diamond64 tail did not survive ordinary restart");
                Map<String, String> afterRestart = restarted.active().checkpoint();
                require(context, "1".equals(afterRestart.get("task.target_count"))
                                && "64".equals(
                                afterRestart.get("task.rare_mission_target")),
                        "ordinary restart reclassified the diamond64 tail: "
                                + checkpointSummary(afterRestart));

                MissionRuntimeRecord exhausted = withOreResourceState(
                        restarted, 40, 0, 0);
                exhaustedState.set(Map.copyOf(exhausted.active().checkpoint()));
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_tail_epoch_retry");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, exhausted);
                forged.set(true);
                return;
            }

            if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))) {
                Map<String, String> before = exhaustedState.get();
                require(context, "1".equals(checkpoint.get("mining.target_count"))
                                && "64".equals(
                                checkpoint.get("mining.rare_mission_target"))
                                && "1".equals(checkpoint.get("mining.resource_epoch"))
                                && "0".equals(checkpoint.get("mining.torch_placements"))
                                && "1".equals(
                                checkpoint.get("rare_resource_retries_used")),
                        "diamond64 tail did not advance its durable rare epoch: "
                                + checkpointSummary(checkpoint));
                require(context, "63".equals(checkpoint.get("task.service_boundary")),
                        "tail retry lost its factual collected boundary: "
                                + checkpointSummary(checkpoint));
                require(context, Integer.parseInt(checkpoint.get("mining.budget_used"))
                                == Integer.parseInt(before.get("mining.budget_used")) + 1,
                        "tail retry refreshed or overcharged its hard budget: "
                                + checkpointSummary(checkpoint));
                for (String suffix : Set.of("origin", "face", "direction", "leg",
                        "steps_left", "leg_length", "batches", "last_progress_budget",
                        "pending_pickup_inventory", "pending_pickup_started_budget",
                        "pickup_gain_budget", "active_break_inventory")) {
                    require(context, java.util.Objects.equals(
                                    before.get("mining." + suffix),
                                    checkpoint.get("mining." + suffix)),
                            "tail retry changed OreDig " + suffix + ": "
                                    + checkpointSummary(checkpoint));
                }
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 135) {
                context.throwGameTestException(
                        "diamond64 tail epoch did not hand off to service: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 320)
    public void diamond64RestoreReplaysOnlyFourItemsBeforeBoundaryEight(
            TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context, "Diamond64PartialBatchRestoreGT");
        AIPlayerEntity bot = fixture.bot();
        giveDiamond64Readiness(bot);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "diamond64 partial restore goal setup failed");
        AtomicInteger phase = new AtomicInteger();
        AtomicReference<String> savedFace = new AtomicReference<>();
        AtomicReference<String> savedBudget = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (phase.get() == 0) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 4));
                    phase.set(1);
                } else if (context.getTick() > 160) {
                    context.throwGameTestException(
                            "diamond64 partial fixture never reached first OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }
            if (phase.get() == 1) {
                if (!"4".equals(checkpoint.get("task.delivered"))) {
                    if (context.getTick() > 190) {
                        context.throwGameTestException(
                                "first OreDig never published delivered=4: "
                                        + checkpointSummary(checkpoint));
                    }
                    return;
                }
                require(context, "8".equals(checkpoint.get("task.target_count"))
                                && "64".equals(checkpoint.get("task.rare_mission_target")),
                        "partial checkpoint lost logical batch or mission identity: "
                                + checkpointSummary(checkpoint));
                savedFace.set(checkpoint.get("task.face"));
                savedBudget.set(checkpoint.get("task.budget_used"));

                TaskManager.INSTANCE.cancelIntentTasks(
                        bot, "gametest_diamond64_partial_restart");
                GoalExecutor.INSTANCE.unload(bot);
                GoalExecutor.INSTANCE.restoreRuntime(bot, runtime);
                MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
                require(context, restored.active() != null,
                        "delivered=4 mission was not restored");
                Map<String, String> after = restored.active().checkpoint();
                require(context, "MINE_ORE".equals(after.get("task_kind"))
                                && "4".equals(after.get("task.delivered"))
                                && "8".equals(after.get("task.target_count"))
                                && savedFace.get().equals(after.get("task.face"))
                                && savedBudget.get().equals(after.get("task.budget_used")),
                        "restore refreshed or replaced the open logical batch: "
                                + checkpointSummary(after));
                InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 4));
                phase.set(2);
                return;
            }

            if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))
                    && "8".equals(checkpoint.get("task.service_boundary"))) {
                require(context, InventoryAction.countItem(bot, Items.DIAMOND) == 8,
                        "restored first batch collected more than its four-item remainder");
                require(context, "false".equals(checkpoint.get("mining.batch_open"))
                                && "0".equals(checkpoint.get("mining.delivered"))
                                && "1".equals(checkpoint.get("mining.batches")),
                        "boundary8 did not follow one committed logical batch: "
                                + checkpointSummary(checkpoint));
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
                GoalExecutor.INSTANCE.unload(bot);
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                context.complete();
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 285) {
                context.throwGameTestException(
                        "restored partial batch never handed off at boundary8: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "miningServiceMissionRestoreStrict", tickLimit = 520)
    public void serviceRestartReturnsToSavedFaceBeforeSecondDiamondBatch(TestContext context) {
        ServiceFixture fixture = spawnServiceMiner(context);
        AIPlayerEntity bot = fixture.bot();
        giveRareMissionReadiness(bot, 16);
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 16);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal), "two-batch mining goal setup failed");
        MissionRuntimeRecord initial = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, initial.active() != null, "missing initial two-batch mission");
        UUID missionId = UUID.fromString(initial.active().missionId());
        long resultBaseline = GoalExecutor.INSTANCE.lastResult(bot).map(GoalResult::sequence).orElse(0L);
        String diamondFingerprint = OreDigTask.oreFingerprint(Set.of(Blocks.DIAMOND_ORE));
        AtomicBoolean restarted = new AtomicBoolean();
        AtomicBoolean firstBatchFed = new AtomicBoolean();
        AtomicBoolean sawLocalService = new AtomicBoolean();
        AtomicBoolean secondBatchFed = new AtomicBoolean();
        AtomicReference<BlockPos> savedFace = new AtomicReference<>();
        AtomicReference<BlockPos> savedSink = new AtomicReference<>();
        AtomicInteger protectedCobblestone = new AtomicInteger();

        context.runAtEveryTick(() -> {
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();

            if (!firstBatchFed.get()) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
                    // Leave exactly three real slots. The boundary-eight rare service must recover
                    // the fourth slot in a sealed pocket at the cursor instead of commuting to the
                    // remembered chest eight blocks away. Cobblestone is inserted before this
                    // full tuff stack, so the service must skip the earlier partial protected-stone
                    // excess and choose a transaction that actually releases a slot. Leave the
                    // protected pool plus a 17-block excess. The partial excess is deliberately
                    // earlier than the full tuff stack but cannot release its occupied slot, so
                    // the capacity transaction must leave it untouched and choose all 64 tuff.
                    MiningServiceTask.ServicePolicy boundaryPolicy =
                            MiningServiceTask.ServicePolicy.rareOreBatch(16, 8, 0);
                    int cobblestone = InventoryAction.countItem(bot, Items.COBBLESTONE);
                    int protectedStone = boundaryPolicy.emergencyBlocksReserved();
                    int cobblestoneBaseline = protectedStone + 17;
                    require(context, cobblestone >= cobblestoneBaseline
                                    && (cobblestone == cobblestoneBaseline
                                    || InventoryAction.removeItems(bot, Items.COBBLESTONE,
                                    cobblestone - cobblestoneBaseline)),
                            "fixture could not isolate the protected cobblestone excess");
                    require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                                    == cobblestoneBaseline,
                            "fixture did not retain the exact 17-block cobblestone excess");
                    require(context, bot.getInventory().offHand.getFirst().isEmpty(),
                            "fixture offhand was not empty before seal setup");
                    bot.getInventory().offHand.set(0, new ItemStack(Items.DIRT, 2));
                    bot.getInventory().markDirty();
                    protectedCobblestone.set(cobblestoneBaseline);
                    InventoryAction.giveItem(bot, new ItemStack(Items.TUFF, 64));
                    fillWithGlassUntilFreeSlots(bot, 3);
                    require(context, freeMainSlots(bot) == 3,
                            "fixture did not reach the three-slot service boundary: free="
                                    + freeMainSlots(bot));
                    require(context, InventoryAction.countItem(bot, Items.TUFF) == 64,
                            "fixture lost its full disposable capacity stack");
                    prepareDisposalPocket(bot, fixture.face(), Direction.EAST);
                    prepareDisposalPocket(bot, fixture.face(), Direction.WEST);
                    firstBatchFed.set(true);
                } else if (context.getTick() > 150) {
                    context.throwGameTestException(
                            "boundary-zero service never handed off to first OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }

            if (!restarted.get()) {
                boolean boundaryService = "MINING_SERVICE".equals(
                        checkpoint.get("task_kind"))
                        && "RARE_ORE_BATCH".equals(
                        checkpoint.get("task.service_profile"))
                        && "8".equals(checkpoint.get("task.service_boundary"));
                if (boundaryService) {
                    require(context, bot.getBlockPos().getSquaredDistance(fixture.face()) <= 4.0D,
                            "local-first rare service left its cursor face: at="
                                    + bot.getBlockPos().toShortString());
                }
                boolean durableLocalPocket = boundaryService
                        && "CAPTURE_DISPOSAL_BASELINE".equals(
                        checkpoint.get("task.phase"));
                if (durableLocalPocket) {
                    require(context, checkpoint.containsKey("task.work_face")
                                    && checkpoint.containsKey("task.ores")
                                    && checkpoint.containsKey("task.pocket_entry")
                                    && checkpoint.containsKey("mining.face"),
                            "service checkpoint lost face/cursor: "
                                    + checkpointSummary(checkpoint));
                    MiningServiceTask.ServicePolicy expected =
                            MiningServiceTask.ServicePolicy.rareOreBatch(16, 8, 0);
                    require(context, "true".equals(checkpoint.get("task.channel_tools"))
                                    && "RARE_ORE_BATCH".equals(checkpoint.get("task.service_profile"))
                                    && "16".equals(checkpoint.get("task.service_target_count"))
                                    && "8".equals(checkpoint.get("task.service_boundary"))
                                    && String.valueOf(expected.torchMinCount()).equals(
                                    checkpoint.get("task.torch_min_count"))
                                    && String.valueOf(expected.foodMinUnits()).equals(
                                    checkpoint.get("task.food_min_units"))
                                    && String.valueOf(expected.futureStickReserve()).equals(
                                    checkpoint.get("task.future_stick_reserve"))
                                    && String.valueOf(expected.emergencyBlocksReserved()).equals(
                                    checkpoint.get("task.emergency_blocks_reserved"))
                                    && "8".equals(checkpoint.get("task.schema"))
                                    && checkpoint.get("mining.face").equals(
                                    checkpoint.get("task.cursor_face")),
                            "service checkpoint lost its schema8 channel/cursor policy: "
                                    + checkpointSummary(checkpoint));
                    require(context, diamondFingerprint.equals(checkpoint.get("mining.ore_fingerprint")),
                            "service retained the wrong ore-family cursor: "
                                    + checkpointSummary(checkpoint));
                    BlockPos face = decodePos(checkpoint.get("task.work_face"));
                    savedFace.set(face);
                    savedSink.set(decodePos(checkpoint.get("task.pocket_sink")));

                    MissionRuntimeRecord before = runtime;
                    TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_service_restart");
                    GoalExecutor.INSTANCE.unload(bot);
                    GoalExecutor.INSTANCE.restoreRuntime(bot, before);

                    MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
                    require(context, restored.active() != null, "service mission was not restored");
                    require(context, missionId.toString().equals(restored.active().missionId()),
                            "service restart changed mission id");
                    Map<String, String> after = restored.active().checkpoint();
                    require(context, "MINING_SERVICE".equals(after.get("task_kind")),
                            "restore skipped the interrupted service step: "
                                    + checkpointSummary(after));
                    require(context, checkpoint.get("task.work_face").equals(after.get("task.work_face")),
                            "service restore changed mining face");
                    require(context, checkpoint.get("task.pocket_entry").equals(
                                    after.get("task.pocket_entry")),
                            "service restore discarded its open local pocket");
                    require(context, "true".equals(after.get("task.channel_tools"))
                                    && checkpoint.get("task.channel_tool_usable")
                                    .equals(after.get("task.channel_tool_usable")),
                            "service restore downgraded its channel policy: "
                                    + checkpointSummary(after));
                    require(context, diamondFingerprint.equals(after.get("mining.ore_fingerprint")),
                            "service restore changed ore cursor identity");
                    sawLocalService.set(true);
                    restarted.set(true);
                } else if (context.getTick() > 300) {
                    context.throwGameTestException("boundary-eight service never opened a local pocket: "
                            + checkpointSummary(checkpoint));
                }
                return;
            }

            if (!secondBatchFed.get()) {
                require(context, runtime.active() != null, "mission ended before second batch");
                if ("MINING_SERVICE".equals(checkpoint.get("task_kind"))
                        && savedFace.get() != null
                        && bot.getBlockPos().equals(savedFace.get())) {
                    sawLocalService.set(true);
                }
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    require(context, sawLocalService.get(),
                            "OreDig, rather than MiningService, returned to the saved face");
                    require(context, bot.getBlockPos().equals(savedFace.get()),
                            "second batch started away from saved face");
                    InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
                    secondBatchFed.set(true);
                } else if (context.getTick() > 450) {
                    context.throwGameTestException("restored service never handed off: "
                            + checkpointSummary(checkpoint));
                }
                return;
            }

            GoalResult result = GoalExecutor.INSTANCE.resultAfter(bot, resultBaseline).orElse(null);
            if (result != null) {
                require(context, result.goal().equals(goal), "wrong two-batch goal completed");
                require(context, result.status() == GoalResult.Status.COMPLETED,
                        "restored two-batch mission ended as " + result.status() + ":" + result.reason());
                require(context, result.missionId().equals(missionId), "completion changed mission id");
                require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                                == protectedCobblestone.get(),
                        "capacity service discarded the non-slot-releasing cobblestone excess");
                require(context, InventoryAction.countItem(bot, Items.TUFF) == 0,
                        "capacity service did not commit the complete tuff stack");
                require(context, savedSink.get() != null
                                && countContainedPocketItem(bot, savedSink.get(), Items.TUFF) == 64,
                        "sealed pocket did not contain the exact 64-tuff capacity transaction");
                require(context, countContainedPocketItem(
                                bot, savedSink.get(), Items.COBBLESTONE) == 0,
                        "sealed pocket contains the rejected partial cobblestone candidate");
                Inventory remoteDepot = ContainerAction.resolve(
                                bot, fixture.face().add(8, 0, 0))
                        .orElseThrow(() -> new IllegalStateException(
                                "missing remembered remote depot"));
                for (int slot = 0; slot < remoteDepot.size(); slot++) {
                    require(context, remoteDepot.getStack(slot).isEmpty(),
                            "local-first rare service mutated remote depot slot " + slot);
                }
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                context.complete();
            } else if (context.getTick() > 500) {
                context.throwGameTestException("restored two-batch mission did not complete");
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 100)
    public void replanBudgetAndProgressSnapshotRoundTrip(TestContext context) {
        String name = "ReplanCheckpointGT";
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        // This is a checkpoint test, not a bootstrap test. Give the underground fixture the full
        // physical diamond64 readiness set so GoalPlanner cannot reject it for a missing surface
        // crafting/log dependency before the checkpoint assertions run.
        giveDiamond64Readiness(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 16));
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 64);
        require(context, GoalExecutor.lifetimeReplanLimit(goal) == 24,
                "diamond64 MineOre did not receive a 24-replan lifetime budget");
        require(context, GoalExecutor.lifetimeReplanLimit(
                        new Goal.HaveItem(Items.DIAMOND, 64)) == 24,
                "diamond64 HaveItem did not retain its original batch identity");
        require(context, GoalExecutor.withinReplanBudget(goal, 2, 23)
                        && !GoalExecutor.withinReplanBudget(goal, 3, 0)
                        && !GoalExecutor.withinReplanBudget(goal, 0, 24),
                "diamond64 replan boundary or consecutive gate changed");
        require(context, GoalExecutor.INSTANCE.submit(bot, goal), "diamond64 goal setup failed");

        context.runAtTick(10, () -> {
            MissionRuntimeRecord before = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, before.active() != null, "missing active diamond64 mission");
            Map<String, String> injected = new LinkedHashMap<>(before.active().checkpoint());
            injected.put("lifetime_replans", "13");
            // Legacy builds treated this as a mission-global debit. With no matching open
            // epoch-one rare batch it is stale history and must normalize to this batch's epoch 0.
            injected.put("rare_resource_retries_used", "1");
            // The margin ledger is mission-scoped: unlike the batch epoch it must round-trip
            // unchanged, otherwise a restart would refill the bounded F2 margin pool.
            injected.put("rare_epoch_margin_used", "2");
            injected.put("replan_count", "2");
            injected.put("snap_steps", "7");
            injected.put("snap_target", "11");
            injected.put("snap_x", "-17");
            injected.put("snap_y", "-59");
            injected.put("snap_z", "23");

            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_replan_checkpoint_roundtrip");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(before, injected));

            MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, restored.active() != null, "replan checkpoint did not restore mission");
            Map<String, String> actual = restored.active().checkpoint();
            for (String key : Set.of("lifetime_replans", "replan_count", "snap_steps",
                    "snap_target", "snap_x", "snap_y", "snap_z")) {
                require(context, injected.get(key).equals(actual.get(key)),
                        "replan checkpoint changed " + key + ": "
                                + checkpointSummary(actual));
            }
            require(context, "0".equals(actual.get("rare_resource_retries_used")),
                    "stale mission-global rare retry was not normalized to batch epoch zero: "
                            + checkpointSummary(actual));
            require(context, "2".equals(actual.get("rare_epoch_margin_used")),
                    "mission-scoped margin ledger did not round-trip unchanged: "
                            + checkpointSummary(actual));

            Map<String, String> legacy = new LinkedHashMap<>(actual);
            legacy.remove("lifetime_replans");
            legacy.remove("rare_resource_retries_used");
            legacy.remove("rare_epoch_margin_used");
            legacy.remove("replan_count");
            // A real legacy build wrote none of the snapshot namespace. Leaving any single
            // snap_ key behind (snap_dimension included) is a hybrid checkpoint that restore
            // validation correctly fails closed on.
            legacy.remove("snap_steps");
            legacy.remove("snap_target");
            legacy.remove("snap_x");
            legacy.remove("snap_y");
            legacy.remove("snap_z");
            legacy.remove("snap_dimension");
            legacy.remove("snap_hunt_raw_meat");
            legacy.remove("snap_hunt_visited_sectors");
            BlockPos legacyBaseline = bot.getBlockPos().toImmutable();

            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_legacy_replan_checkpoint");
            GoalExecutor.INSTANCE.unload(bot);
            GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(restored, legacy));

            MissionRuntimeRecord migrated = GoalExecutor.INSTANCE.captureRuntime(bot);
            require(context, migrated.active() != null, "legacy checkpoint did not restore mission");
            Map<String, String> migratedCheckpoint = migrated.active().checkpoint();
            require(context, "0".equals(migratedCheckpoint.get("lifetime_replans"))
                            && "0".equals(migratedCheckpoint.get(
                            "rare_resource_retries_used"))
                            && "0".equals(migratedCheckpoint.get("rare_epoch_margin_used"))
                            && "0".equals(migratedCheckpoint.get("replan_count")),
                    "legacy checkpoint refreshed non-zero retry counters: "
                            + checkpointSummary(migratedCheckpoint));
            require(context, migratedCheckpoint.get("revision")
                            .equals(migratedCheckpoint.get("snap_steps")),
                    "legacy checkpoint did not baseline completed steps: "
                            + checkpointSummary(migratedCheckpoint));
            require(context, "0".equals(migratedCheckpoint.get("snap_target"))
                            && String.valueOf(legacyBaseline.getX()).equals(migratedCheckpoint.get("snap_x"))
                            && String.valueOf(legacyBaseline.getY()).equals(migratedCheckpoint.get("snap_y"))
                            && String.valueOf(legacyBaseline.getZ()).equals(migratedCheckpoint.get("snap_z")),
                    "legacy checkpoint did not baseline live progress facts: "
                            + checkpointSummary(migratedCheckpoint));
            require(context, "0".equals(migratedCheckpoint.get("snap_hunt_raw_meat"))
                            && "0".equals(migratedCheckpoint.get(
                            "snap_hunt_visited_sectors")),
                    "legacy checkpoint did not rewrite hunt progress watermarks: "
                            + checkpointSummary(migratedCheckpoint));

            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    private static AIPlayerEntity spawnPreparedMiner(TestContext context) {
        return spawnPreparedMiner(context, "MiningCursorGT");
    }

    private static AIPlayerEntity spawnPreparedMiner(TestContext context, String name) {
        var world = context.getWorld();
        BlockPos cell = context.getAbsolutePos(new BlockPos(1, 2, 1));
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    world.setBlockState(cell.add(dx, dy, dz), Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
        world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(cell),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 2));
        for (int i = 0; i < 4; i++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 40));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 28));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        return bot;
    }

    private static AIPlayerEntity spawnDiamond64CoalBootstrapMiner(
            TestContext context,
            String name) {
        var world = context.getWorld();
        BlockPos template = context.getAbsolutePos(new BlockPos(1, 2, 1));
        BlockPos start = new BlockPos(template.getX(), 48, template.getZ());
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlockState(start.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
        for (int offset = -5; offset <= 5; offset++) {
            for (int dy = -1; dy <= 1; dy++) {
                world.setBlockState(start.add(-5, dy, offset),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                world.setBlockState(start.add(5, dy, offset),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                world.setBlockState(start.add(offset, dy, -5),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                world.setBlockState(start.add(offset, dy, 5),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException(
                        "failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        giveItemToAtLeast(bot, Items.IRON_PICKAXE, 3);
        giveItemToAtLeast(bot, Items.STONE_PICKAXE, 5);
        giveItemToAtLeast(bot, Items.WOODEN_PICKAXE, 5);
        giveItemToAtLeast(bot, Items.IRON_INGOT, 6);
        // Six sticks above the sealed mission reserve, mirroring the pre-margin 234 = 228 + 6.
        // The margin-funded 720-torch pool grows the coal chain to twelve batches whose
        // channel-repair heads need 56 picks x 3 = 168 stone-like: give 192 cobblestone (same
        // three slots as the pre-margin 160) so no mine-stone detour precedes the coal OreDig,
        // and one stack of logs still covers the ~62-log craft-plus-shelter wood demand.
        giveItemToAtLeast(bot, Items.STICK,
                MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS + 6);
        giveItemToAtLeast(bot, Items.OAK_LOG,
                50 + EmergencyShelterTask.MAX_PLACEMENT_BLOCKS);
        giveItemToAtLeast(bot, Items.COOKED_BEEF,
                MiningBudget.RARE_BOOTSTRAP_FOOD);
        giveItemToAtLeast(bot, Items.COBBLESTONE, 192);
        giveItemToAtLeast(bot, Items.CRAFTING_TABLE, 1);
        giveItemToAtLeast(bot, Items.CHEST, 1);
        return bot;
    }

    private static MissionRuntimeRecord withCheckpoint(MissionRuntimeRecord runtime,
                                                       Map<String, String> checkpoint) {
        MissionRecord active = runtime.active();
        if (active == null) {
            throw new IllegalArgumentException("missing active mission");
        }
        return new MissionRuntimeRecord(
                new MissionRecord(active.missionId(), active.spec(), checkpoint),
                runtime.queue(),
                runtime.userPaused());
    }

    private static Map<String, String> terminalServiceCheckpoint(
            Map<String, String> checkpoint, String failure) {
        Map<String, String> terminal = new LinkedHashMap<>(checkpoint);
        terminal.keySet().removeIf(key -> key.startsWith("task.pocket_"));
        terminal.put("task.phase", "PREPARE");
        terminal.put("task.terminal_failure", failure);
        return Map.copyOf(terminal);
    }

    private static GuardFixture settleBoundaryZeroGuard(
            TestContext context,
            String name,
            String failure) {
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        giveRareMissionReadiness(bot, 8);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "guard fixture submit failed");
        MissionRuntimeRecord original = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, original.active() != null
                        && "MINING_SERVICE".equals(
                        original.active().checkpoint().get("task_kind"))
                        && MiningServiceTask.inspectCheckpoint(namespace(
                        original.active().checkpoint(), "task.")).isPresent(),
                "guard fixture lacks a valid boundary-zero service");

        Map<String, String> terminal = terminalServiceCheckpoint(
                original.active().checkpoint(), failure);
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_guard_fixture_receipt");
        GoalExecutor.INSTANCE.unload(bot);
        GoalExecutor.INSTANCE.restoreRuntime(bot, withCheckpoint(original, terminal));
        Task receipt = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, receipt instanceof MiningServiceTask
                        && receipt.state() == TaskState.FAILED
                        && failure.equals(receipt.failureReason()),
                "guard fixture did not restore terminal receipt");
        TaskManager.INSTANCE.abort(bot);
        GoalExecutor.INSTANCE.tickBot(bot.getServer(), bot);

        MissionRuntimeRecord guarded = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, guarded.active() != null
                        && "1".equals(guarded.active().checkpoint().get(
                        "settled_service.count"))
                        && failure.equals(guarded.active().checkpoint().get(
                        "settled_service.0.failure")),
                "terminal receipt did not publish exactly one guard");
        return new GuardFixture(name, bot, goal, original, guarded, failure);
    }

    private static Map<String, String> replaceTaskCheckpoint(
            Map<String, String> checkpoint,
            Map<String, String> task,
            GoalStep.Kind kind) {
        Map<String, String> values = new LinkedHashMap<>(checkpoint);
        values.keySet().removeIf(key -> key.startsWith("task."));
        if (kind == null || task == null || task.isEmpty()) {
            values.remove("task_kind");
        } else {
            values.put("task_kind", kind.name());
            task.forEach((key, value) -> values.put("task." + key, value));
        }
        return Map.copyOf(values);
    }

    private static void duplicateGuardEntry(Map<String, String> checkpoint) {
        checkpoint.put("settled_service.count", "2");
        for (String key : Set.of(
                "schema", "descriptor", "dimension", "work_face",
                "pocket_axis", "pocket_a", "pocket_b", "failure")) {
            checkpoint.put("settled_service.1." + key,
                    checkpoint.get("settled_service.0." + key));
        }
    }

    private static void addCanonicalDistinctGuard(
            Map<String, String> checkpoint, String failure) {
        Map<String, String> first = new LinkedHashMap<>();
        for (String key : Set.of(
                "schema", "descriptor", "dimension", "work_face",
                "pocket_axis", "pocket_a", "pocket_b", "failure")) {
            first.put(key, checkpoint.get("settled_service.0." + key));
        }
        BlockPos face = decodePos(first.get("work_face")).east(17);
        Direction.Axis axis = Direction.Axis.valueOf(first.get("pocket_axis"));
        Map<String, String> second = new LinkedHashMap<>(first);
        second.put("work_face", encodePos(face));
        second.put("pocket_a", encodePos(axis == Direction.Axis.X
                ? face.west() : face.north()));
        second.put("pocket_b", encodePos(axis == Direction.Axis.X
                ? face.east() : face.south()));
        second.put("failure", failure);
        java.util.List<Map<String, String>> entries = new java.util.ArrayList<>(
                java.util.List.of(Map.copyOf(first), Map.copyOf(second)));
        entries.sort(java.util.Comparator.comparing(entry ->
                entry.get("descriptor") + "@" + entry.get("dimension") + "@"
                        + entry.get("work_face") + "@" + entry.get("pocket_axis")));
        checkpoint.keySet().removeIf(key -> key.startsWith("settled_service."));
        checkpoint.put("settled_service.count", "2");
        for (int index = 0; index < entries.size(); index++) {
            Map<String, String> entry = entries.get(index);
            for (Map.Entry<String, String> field : entry.entrySet()) {
                checkpoint.put("settled_service." + index + "."
                        + field.getKey(), field.getValue());
            }
        }
    }

    private static MiningServiceTask.DisposalGeometry guardGeometry(
            Map<String, String> checkpoint) {
        return new MiningServiceTask.DisposalGeometry(
                decodePos(checkpoint.get("settled_service.0.work_face")),
                Direction.Axis.valueOf(checkpoint.get(
                        "settled_service.0.pocket_axis")),
                decodePos(checkpoint.get("settled_service.0.pocket_a")),
                decodePos(checkpoint.get("settled_service.0.pocket_b")));
    }

    private static Map<BlockPos, net.minecraft.block.BlockState> snapshotGuardGeometry(
            AIPlayerEntity bot,
            Map<String, String> checkpoint) {
        return snapshotGeometry(bot, guardGeometry(checkpoint));
    }

    private static Map<BlockPos, net.minecraft.block.BlockState> snapshotGeometry(
            AIPlayerEntity bot,
            MiningServiceTask.DisposalGeometry geometry) {
        Map<BlockPos, net.minecraft.block.BlockState> snapshot = new LinkedHashMap<>();
        snapshot.put(geometry.workFace(),
                bot.getServerWorld().getBlockState(geometry.workFace()));
        for (BlockPos entry : java.util.List.of(
                geometry.firstEntry(), geometry.secondEntry())) {
            int dx = entry.getX() - geometry.workFace().getX();
            int dz = entry.getZ() - geometry.workFace().getZ();
            BlockPos sink = entry.add(dx, 0, dz);
            for (BlockPos pos : java.util.List.of(
                    entry, entry.up(), sink, sink.up())) {
                snapshot.put(pos, bot.getServerWorld().getBlockState(pos));
            }
        }
        return Map.copyOf(snapshot);
    }

    private static InventorySnapshot snapshotInventory(AIPlayerEntity bot) {
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        bot.getInventory().main.forEach(stack -> stacks.add(stack.copy()));
        bot.getInventory().offHand.forEach(stack -> stacks.add(stack.copy()));
        return new InventorySnapshot(java.util.List.copyOf(stacks),
                bot.getInventory().selectedSlot);
    }

    private static Map<String, String> withOpenGuardedPocket(
            Map<String, String> checkpoint) {
        MiningServiceTask.DisposalGeometry geometry = guardGeometry(checkpoint);
        Direction direction = geometry.pocketAxis() == Direction.Axis.X
                ? Direction.WEST : Direction.NORTH;
        BlockPos entry = geometry.workFace().offset(direction);
        BlockPos sink = geometry.workFace().offset(direction, 2);
        Map<String, String> task = new LinkedHashMap<>(namespace(checkpoint, "task."));
        task.remove("terminal_failure");
        task.put("phase", "OPEN_DISPOSAL_POCKET");
        task.put("pocket_entry", encodePos(entry));
        task.put("pocket_sink", encodePos(sink));
        task.put("pocket_direction", direction.name());
        task.put("pocket_entities", "");
        task.remove("pocket_lineage");
        task.put("pocket_baseline", "");
        task.put("pocket_ledger", "");
        task.put("pocket_drop_committed", "false");
        task.put("pocket_ledger_verified", "false");
        task.put("pocket_phase_started", "0");
        task.put("pocket_failure", "");
        task.put("pocket_clear_index", "0");
        return replaceTaskCheckpoint(
                checkpoint, Map.copyOf(task), GoalStep.Kind.MINING_SERVICE);
    }

    private static String encodePos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String nonCanonicalPos(String encoded) {
        String[] parts = encoded.split(",", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid position " + encoded);
        }
        parts[0] = parts[0].startsWith("-")
                ? "-0" + parts[0].substring(1) : "+" + parts[0];
        return String.join(",", parts);
    }

    private static void withRunningOrdinaryService(TestContext context,
                                                   String name,
                                                   OrdinaryServiceProbe probe) {
        ServiceFixture fixture = spawnServiceMiner(context, name);
        AIPlayerEntity bot = fixture.bot();
        Goal goal = new Goal.MineOre(Set.of(Blocks.IRON_ORE), 32);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "ordinary service goal setup failed");
        AtomicBoolean firstBatchFed = new AtomicBoolean();
        AtomicBoolean probed = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            if (probed.get()) {
                return;
            }
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!firstBatchFed.get()) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))
                        && checkpoint.getOrDefault("task.ore_fingerprint", "")
                        .contains("iron_ore")
                        && Integer.parseInt(checkpoint.getOrDefault(
                        "task.budget_used", "0")) > 0) {
                    InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON, 16));
                    fillWithGlassUntilFreeSlots(bot, 1);
                    firstBatchFed.set(true);
                } else if (context.getTick() > 65) {
                    context.throwGameTestException(
                            "ordinary fixture never reached first OreDig batch: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }
            boolean runningOrdinary = "MINING_SERVICE".equals(
                    checkpoint.get("task_kind"))
                    && "ORE_BATCH".equals(checkpoint.get("task.service_profile"))
                    && !"DONE".equals(checkpoint.get("task.phase"));
            if (runningOrdinary) {
                probed.set(true);
                probe.accept(fixture, goal, runtime, checkpoint);
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 130) {
                context.throwGameTestException(
                        "ordinary fixture never reached running service: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    private static void withRunningSmallRareCapacityService(
            TestContext context,
            String name,
            CapacityServiceProbe probe) {
        AIPlayerEntity bot = spawnPreparedMiner(context, name);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 7);
        require(context, GoalExecutor.INSTANCE.submit(bot, goal),
                "small-rare capacity service setup failed");
        AtomicBoolean filled = new AtomicBoolean();
        AtomicBoolean probed = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            if (probed.get()) {
                return;
            }
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Map<String, String> checkpoint = runtime.active() == null
                    ? Map.of() : runtime.active().checkpoint();
            if (!filled.get()) {
                if ("MINE_ORE".equals(checkpoint.get("task_kind"))) {
                    fillWithGlassUntilFreeSlots(bot, 0);
                    require(context, freeMainSlots(bot) == 0,
                            "capacity fixture did not produce a full inventory");
                    filled.set(true);
                } else if (context.getTick() > 70) {
                    context.throwGameTestException(
                            "capacity fixture never reached OreDig: "
                                    + checkpointSummary(checkpoint));
                }
                return;
            }
            boolean runningCapacity = "MINING_SERVICE".equals(
                    checkpoint.get("task_kind"))
                    && "ORE_BATCH".equals(checkpoint.get("task.service_profile"))
                    && "mining".equals(checkpoint.get("capacity_parent"))
                    && "true".equals(checkpoint.get(
                    "mining.inventory_service_used"));
            if (runningCapacity) {
                probed.set(true);
                probe.accept(bot, goal, runtime, checkpoint);
            } else if (GoalExecutor.INSTANCE.lastResult(bot).isPresent()
                    || context.getTick() > 120) {
                context.throwGameTestException(
                        "capacity fixture never reached its marked service: "
                                + checkpointSummary(checkpoint));
            }
        });
    }

    private static Map<String, String> namespace(Map<String, String> checkpoint,
                                                  String prefix) {
        Map<String, String> values = new LinkedHashMap<>();
        checkpoint.forEach((key, value) -> {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                values.put(key.substring(prefix.length()), value);
            }
        });
        return values;
    }

    private static void assertAuxiliaryContinuationOrPromotion(
            TestContext context,
            Map<String, String> checkpoint,
            Map<String, String> protectedRare,
            Map<String, String> closedAux,
            String expectedFingerprint) {
        require(context, protectedRare.equals(namespace(checkpoint, "mining."))
                        && !checkpoint.containsKey("capacity_parent"),
                "auxiliary continuation changed the protected rare namespace: "
                        + checkpointSummary(checkpoint));
        boolean suspended = expectedFingerprint.equals(
                checkpoint.get("aux_mining_continuation"))
                && closedAux.equals(namespace(checkpoint, "aux_mining."));
        boolean promoted = "MINE_ORE".equals(checkpoint.get("task_kind"))
                && expectedFingerprint.equals(checkpoint.get("task.ore_fingerprint"))
                && !checkpoint.containsKey("aux_mining_continuation")
                && checkpoint.keySet().stream().noneMatch(
                key -> key.startsWith("aux_mining."));
        if (promoted) {
            for (String field : Set.of(
                    "origin", "face", "direction", "leg",
                    "steps_left", "leg_length", "batches")) {
                require(context, java.util.Objects.equals(
                                closedAux.get(field), checkpoint.get("task." + field)),
                        "promoted auxiliary cursor changed " + field + ": "
                                + checkpointSummary(checkpoint));
            }
        }
        require(context, suspended || promoted,
                "closed auxiliary cursor was neither suspended nor promoted: "
                        + checkpointSummary(checkpoint));
        require(context, !"ORE_BATCH".equals(checkpoint.get("task.service_profile")),
                "failed service checkpoint replayed instead of yielding to its continuation");
    }

    private static Map<String, String> committedOreNamespace(
            Map<String, String> namespace) {
        Map<String, String> committed = new LinkedHashMap<>(namespace);
        committed.put("batch_open", "false");
        committed.put("delivered", "0");
        committed.put("budget_used", "0");
        committed.put("last_progress_budget", "0");
        committed.put("inventory_service_used", "false");
        committed.put("torch_placements", "0");
        committed.put("resource_epoch", "0");
        clearOrePhysicalLedger(committed, "");
        return Map.copyOf(committed);
    }

    private static void clearOrePhysicalLedger(Map<String, String> checkpoint,
                                               String prefix) {
        checkpoint.remove(prefix + "pending_pickup_pos");
        checkpoint.remove(prefix + "pending_pickup_last_seen_pos");
        checkpoint.put(prefix + "pending_pickup_inventory", "-1");
        checkpoint.put(prefix + "pending_pickup_started_budget", "-1");
        checkpoint.put(prefix + "pickup_gain_budget", "-1");
        checkpoint.remove(prefix + "active_break_pos");
        checkpoint.put(prefix + "active_break_inventory", "-1");
    }

    private static void setOpenBreakLedger(Map<String, String> checkpoint,
                                           String prefix) {
        clearOrePhysicalLedger(checkpoint, prefix);
        String face = checkpoint.get(prefix + "face");
        if (face == null) {
            throw new IllegalArgumentException("missing ore face for " + prefix);
        }
        checkpoint.put(prefix + "active_break_pos", face);
        checkpoint.put(prefix + "active_break_inventory", "0");
    }

    private static Map<String, String> openOrdinaryCheckpoint(
            MiningCursor cursor,
            Set<Block> ores) {
        Map<String, String> checkpoint = new LinkedHashMap<>(cursor.encode());
        checkpoint.put("task_schema", "4");
        checkpoint.put("target_count", "16");
        checkpoint.put("batch_open", "true");
        checkpoint.put("delivered", "0");
        checkpoint.put("rare_mission_target", "0");
        checkpoint.put("inventory_service_used", "false");
        checkpoint.put("torch_limit",
                String.valueOf(MiningBudget.RARE_BATCH_TORCH_LIMIT));
        checkpoint.put("torch_placements", "0");
        checkpoint.put("resource_epoch", "0");
        checkpoint.put("budget_used", "7");
        checkpoint.put("last_progress_budget", "7");
        checkpoint.put("ore_fingerprint", OreDigTask.oreFingerprint(ores));
        checkpoint.put("pending_pickup_inventory", "-1");
        checkpoint.put("pending_pickup_started_budget", "-1");
        checkpoint.put("pickup_gain_budget", "-1");
        checkpoint.put("active_break_inventory", "-1");
        return Map.copyOf(checkpoint);
    }

    private static MissionRuntimeRecord withOreResourceState(MissionRuntimeRecord runtime,
                                                              int placements,
                                                              int epoch,
                                                              int retriesUsed) {
        if (runtime.active() == null) {
            throw new IllegalArgumentException("missing active mission");
        }
        Map<String, String> checkpoint = new LinkedHashMap<>(runtime.active().checkpoint());
        for (String prefix : Set.of("task.", "mining.")) {
            checkpoint.put(prefix + "task_schema", "4");
            checkpoint.put(prefix + "torch_limit", "40");
            checkpoint.put(prefix + "torch_placements", String.valueOf(placements));
            checkpoint.put(prefix + "resource_epoch", String.valueOf(epoch));
            checkpoint.put(prefix + "direction", "0");
            checkpoint.put(prefix + "steps_left", "10");
        }
        checkpoint.put("rare_resource_retries_used", String.valueOf(retriesUsed));
        return withCheckpoint(runtime, checkpoint);
    }

    private static void giveRareRetrySupplies(AIPlayerEntity bot) {
        giveRareMissionReadiness(bot, 8);
    }

    private static void giveDiamond64Readiness(AIPlayerEntity bot) {
        giveRareMissionReadiness(bot, 64);
    }

    private static Map<String, String> withServicePolicy(
            Map<String, String> checkpoint,
            MiningServiceTask.ServicePolicy policy) {
        Map<String, String> rewritten = new LinkedHashMap<>(checkpoint);
        rewritten.put("channel_tools", String.valueOf(policy.maintainsTunnelingTools()));
        rewritten.put("service_profile", policy.profile().name());
        rewritten.put("target_tool_usable", String.valueOf(
                policy.targetToolUsableDurability()));
        rewritten.put("channel_tool_usable", String.valueOf(
                policy.channelToolUsableDurability()));
        rewritten.put("food_min_units", String.valueOf(policy.foodMinUnits()));
        rewritten.put("torch_min_count", String.valueOf(policy.torchMinCount()));
        rewritten.put("free_slots_min", String.valueOf(policy.freeSlotsMin()));
        rewritten.put("emergency_blocks_reserved", String.valueOf(
                policy.emergencyBlocksReserved()));
        rewritten.put("future_stick_reserve", String.valueOf(
                policy.futureStickReserve()));
        rewritten.put("crafting_table_required", String.valueOf(
                policy.craftingTableRequired()));
        return rewritten;
    }

    private static void giveRareMissionReadiness(AIPlayerEntity bot, int targetCount) {
        MiningBudget budget = MiningBudget.forQuota(targetCount, true, ToolTier.IRON);
        giveItemToAtLeast(bot, Items.IRON_PICKAXE, budget.initialPickaxes());
        giveItemToAtLeast(bot, Items.STONE_PICKAXE, budget.tunnelingPickaxes());
        giveItemToAtLeast(bot, Items.IRON_INGOT, budget.spareToolIngots());
        giveItemToAtLeast(bot, Items.STICK, budget.spareToolSticks());
        giveItemToAtLeast(bot, Items.TORCH, budget.torchTarget());
        giveItemToAtLeast(bot, Items.COOKED_BEEF, budget.cookedFoodTarget());
        giveItemToAtLeast(bot, Items.COBBLESTONE, budget.emergencyBlocks());
        giveItemToAtLeast(bot, Items.CRAFTING_TABLE, 1);
    }

    private static ServiceFixture spawnServiceMiner(TestContext context) {
        return spawnServiceMiner(context, "MiningServiceGT");
    }

    private static ServiceFixture spawnServiceMiner(TestContext context, String name) {
        var world = context.getWorld();
        BlockPos anchor = context.getAbsolutePos(new BlockPos(1, 2, 1));
        BlockPos face = new BlockPos(anchor.getX(), -58, anchor.getZ());
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                world.setBlockState(face.add(dx, -1, dz), Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_LISTENERS);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlockState(face.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
        BlockPos depot = face.add(8, 0, 0);
        world.setBlockState(depot, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(face),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, face.getX() + 0.5D, face.getY(), face.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 2));
        for (int i = 0; i < 4; i++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 12));
        giveItemToAtLeast(bot, Items.TORCH, 80);
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 28));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        BotMemoryStore.INSTANCE.of(bot.getUuid()).markPlace("depot", world, depot);
        return new ServiceFixture(name, bot, face.toImmutable());
    }

    private static void clearCarriedInventory(AIPlayerEntity bot) {
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            bot.getInventory().main.set(slot, ItemStack.EMPTY);
        }
        for (int slot = 0; slot < bot.getInventory().offHand.size(); slot++) {
            bot.getInventory().offHand.set(slot, ItemStack.EMPTY);
        }
        bot.getInventory().markDirty();
        bot.getActionPack().stopAll();
    }

    private static void prepareDisposalPocket(AIPlayerEntity bot,
                                              BlockPos face,
                                              Direction direction) {
        var world = bot.getServerWorld();
        BlockPos entry = face.offset(direction);
        BlockPos sink = face.offset(direction, 2);
        BlockPos back = face.offset(direction, 3);
        // Glass is physically mined but drops no opening spoil without Silk Touch. This keeps the
        // test focused on the one promised capacity slot instead of consuming another slot with
        // fixture-only dirt drops.
        world.setBlockState(entry, Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(entry.up(), Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(sink, Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(sink.up(), Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(back, Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(back.up(), Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        Direction left = direction.rotateYCounterclockwise();
        Direction right = direction.rotateYClockwise();
        for (BlockPos cell : new BlockPos[]{entry, sink}) {
            world.setBlockState(cell.offset(left),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.offset(left).up(),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.offset(right),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.offset(right).up(),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        }
    }

    private static void buildBedrockCage(AIPlayerEntity bot, BlockPos center) {
        var world = bot.getServerWorld();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(center.add(dx, -1, dz),
                        Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(center.add(dx, 2, dz),
                        Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                if (dx != 0 || dz != 0) {
                    world.setBlockState(center.add(dx, 0, dz),
                            Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                    world.setBlockState(center.add(dx, 1, dz),
                            Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(center, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(center.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static void giveItemToAtLeast(AIPlayerEntity bot, Item item, int target) {
        int remaining = Math.max(0, target - InventoryAction.countItem(bot, item));
        int stackLimit = Math.max(1, new ItemStack(item).getMaxCount());
        while (remaining > 0) {
            int batch = Math.min(stackLimit, remaining);
            if (InventoryAction.giveItem(bot, new ItemStack(item, batch)).isFailed()) {
                throw new IllegalStateException("fixture_inventory_full:item=" + item
                        + ":remaining=" + remaining);
            }
            remaining -= batch;
        }
    }

    private static void exhaustAllPickaxes(AIPlayerEntity bot) {
        java.util.stream.Stream.concat(
                        bot.getInventory().main.stream(),
                        bot.getInventory().offHand.stream())
                .filter(stack -> !stack.isEmpty()
                        && stack.getItem() instanceof net.minecraft.item.PickaxeItem)
                .forEach(stack -> stack.setDamage(stack.getMaxDamage() - 1));
        bot.getInventory().markDirty();
    }

    private static void exhaustStonePickaxes(AIPlayerEntity bot) {
        InventoryAction.findItem(bot, Items.STONE_PICKAXE)
                .ifPresent(slot -> InventoryAction.equipFromSlot(bot, slot));
        java.util.stream.Stream.concat(
                        bot.getInventory().main.stream(),
                        bot.getInventory().offHand.stream())
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .forEach(stack -> stack.setDamage(stack.getMaxDamage() - 1));
        bot.getInventory().markDirty();
        bot.getActionPack().stopAll();
    }

    private static void equipHealthyIronPickaxe(TestContext context, AIPlayerEntity bot) {
        int ironSlot = InventoryAction.findItem(bot, Items.IRON_PICKAXE)
                .orElseThrow(() -> new IllegalStateException(
                        "fixture has no healthy target pickaxe"));
        require(context, InventoryAction.equipFromSlot(bot, ironSlot) >= 0,
                "could not keep a healthy target pickaxe in hand");
    }

    private static boolean hasUsableStonePickaxe(AIPlayerEntity bot) {
        return java.util.stream.Stream.concat(
                        bot.getInventory().main.stream(),
                        bot.getInventory().offHand.stream())
                .anyMatch(stack -> stack.isOf(Items.STONE_PICKAXE)
                        && io.github.zoyluo.aibot.task.MiningServiceTask
                        .usableDurability(stack) > 0);
    }

    private static int totalUsableStonePickaxeDurability(AIPlayerEntity bot) {
        return java.util.stream.Stream.concat(
                        bot.getInventory().main.stream(),
                        bot.getInventory().offHand.stream())
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .mapToInt(MiningServiceTask::usableDurability)
                .sum();
    }

    private static void fillWithGlassUntilFreeSlots(AIPlayerEntity bot, int targetFreeSlots) {
        int target = Math.max(0, targetFreeSlots);
        while (freeMainSlots(bot) > target) {
            int before = freeMainSlots(bot);
            if (InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 64)).isFailed()
                    || freeMainSlots(bot) >= before) {
                break;
            }
        }
    }

    private static int freeMainSlots(AIPlayerEntity bot) {
        return (int) bot.getInventory().main.stream().filter(ItemStack::isEmpty).count();
    }

    private static int countItem(Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countContainedPocketItem(AIPlayerEntity bot,
                                                BlockPos sink,
                                                Item item) {
        if (sink == null) {
            return 0;
        }
        Box bounds = new Box(
                sink.getX(), sink.getY(), sink.getZ(),
                sink.getX() + 1.0D, sink.getY() + 2.0D, sink.getZ() + 1.0D);
        return bot.getServerWorld().getEntitiesByClass(
                        ItemEntity.class, bounds.expand(0.01D),
                        entity -> entity.isAlive() && entity.getStack().isOf(item)
                                && entity.getBoundingBox().minX >= bounds.minX
                                && entity.getBoundingBox().minY >= bounds.minY
                                && entity.getBoundingBox().minZ >= bounds.minZ
                                && entity.getBoundingBox().maxX <= bounds.maxX
                                && entity.getBoundingBox().maxY <= bounds.maxY
                                && entity.getBoundingBox().maxZ <= bounds.maxZ)
                .stream()
                .mapToInt(entity -> entity.getStack().getCount())
                .sum();
    }

    /** Compact failure evidence; GameTest exception packets reject messages above 1024 bytes. */
    private static String checkpointSummary(Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return "{empty}";
        }
        return "{kind=" + checkpoint.getOrDefault("task_kind", "-")
                + ",profile=" + checkpoint.getOrDefault("task.service_profile", "-")
                + ",phase=" + checkpoint.getOrDefault("task.phase", "-")
                + ",target=" + checkpoint.getOrDefault("task.service_target_count", "-")
                + ",boundary=" + checkpoint.getOrDefault("task.service_boundary", "-")
                + ",taskBudget=" + checkpoint.getOrDefault("task.budget_used", "-")
                + ",miningBudget=" + checkpoint.getOrDefault("mining.budget_used", "-")
                + ",epoch=" + checkpoint.getOrDefault("mining.resource_epoch", "-")
                + ",retries=" + checkpoint.getOrDefault("rare_resource_retries_used", "-")
                + ",face=" + checkpoint.getOrDefault("mining.face", "-") + "}";
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private static BlockPos decodePos(String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing checkpoint position");
        }
        String[] parts = value.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid checkpoint position: " + value);
        }
        return new BlockPos(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    private static final class HoldingSafetyTask extends AbstractTask {
        @Override
        public String name() {
            return "holding_safety";
        }

        @Override
        public String describe() {
            return "Holding the safety slot across a terminal mission boundary";
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

    private record ServiceFixture(String name, AIPlayerEntity bot, BlockPos face) {
    }

    private record GuardFixture(String name,
                                AIPlayerEntity bot,
                                Goal goal,
                                MissionRuntimeRecord originalRuntime,
                                MissionRuntimeRecord guardedRuntime,
                                String failure) {
    }

    private record InventorySnapshot(java.util.List<ItemStack> stacks,
                                     int selectedSlot) {
        private boolean matches(AIPlayerEntity bot) {
            java.util.List<ItemStack> current = new java.util.ArrayList<>();
            current.addAll(bot.getInventory().main);
            current.addAll(bot.getInventory().offHand);
            if (selectedSlot != bot.getInventory().selectedSlot
                    || current.size() != stacks.size()) {
                return false;
            }
            for (int index = 0; index < stacks.size(); index++) {
                if (!ItemStack.areEqual(stacks.get(index), current.get(index))) {
                    return false;
                }
            }
            return true;
        }
    }

    @FunctionalInterface
    private interface OrdinaryServiceProbe {
        void accept(ServiceFixture fixture,
                    Goal goal,
                    MissionRuntimeRecord runtime,
                    Map<String, String> checkpoint);
    }

    @FunctionalInterface
    private interface CapacityServiceProbe {
        void accept(AIPlayerEntity bot,
                    Goal goal,
                    MissionRuntimeRecord runtime,
                    Map<String, String> checkpoint);
    }
}
