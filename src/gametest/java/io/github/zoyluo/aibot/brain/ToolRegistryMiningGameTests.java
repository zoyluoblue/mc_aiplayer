package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.runtime.IntentController;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.AbstractTask;
import io.github.zoyluo.aibot.task.StripMineTask;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;

/** Runtime registry coverage for the public mine_ore argument contract. */
public final class ToolRegistryMiningGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void aliasesResolveOnlyTheirRequestedOreFamily(TestContext context) {
        if (!OreScan.oreFamily(Blocks.DIAMOND_ORE)
                .equals(ToolRegistry.oreTargetsFrom("minecraft:diamond"))) {
            context.throwGameTestException("diamond alias resolved to the wrong ore family");
            return;
        }
        if (!OreScan.oreFamily(Blocks.IRON_ORE)
                .equals(ToolRegistry.oreTargetsFrom("minecraft:raw_iron"))) {
            context.throwGameTestException("raw_iron alias resolved to the wrong ore family");
            return;
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void obsidianAndNonOreTargetsFailClosed(TestContext context) {
        requireRejected(context, "minecraft:obsidian", true);
        requireRejected(context, "minecraft:stone", false);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void strictDirectHandlersRejectWithoutDisturbingActiveWork(TestContext context) {
        ActiveFixture fixture = activeFixture(context, "StripDirectGT");
        try {
            ToolRegistry registry = new ToolRegistry();
            requireToolRejected(context, fixture, registry, "strip_mine",
                    JsonParser.parseString("{\"direction\":\"north\",\"length\":8,\"spacing\":4}")
                            .getAsJsonObject());
            requireToolRejected(context, fixture, registry, "mine_vein",
                    JsonParser.parseString("{\"target_ores\":\"minecraft:diamond_ore\"}")
                            .getAsJsonObject());
        } finally {
            cleanupFixture(context, fixture);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void strictAssignTaskRejectsWithoutDisturbingActiveWork(TestContext context) {
        ActiveFixture fixture = activeFixture(context, "StripAssignGT");
        try {
            ToolRegistry registry = new ToolRegistry();
            requireToolRejected(context, fixture, registry, "assign_task",
                    JsonParser.parseString("""
                            {"task_type":"strip_mine","params":{
                              "direction":"north","length":8,"spacing":4
                            }}
                            """).getAsJsonObject());
            requireToolRejected(context, fixture, registry, "assign_task",
                    JsonParser.parseString("""
                            {"task_type":"mine_vein","params":{
                              "target_ores":"minecraft:diamond_ore"
                            }}
                            """).getAsJsonObject());
        } finally {
            cleanupFixture(context, fixture);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void strictPlayerCommandsRejectWithoutDisturbingActiveWork(TestContext context) {
        ActiveFixture fixture = activeFixture(context, "StripCmdGT");
        try {
            RecordingCommandOutput output = new RecordingCommandOutput();
            ServerCommandSource playerSource = fixture.bot().getCommandSource()
                    .withLevel(4)
                    .withOutput(output);

            runRejectedCommand(context, fixture, playerSource, output,
                    "aibot task assign StripCmdGT strip_mine north 8 4");
            runRejectedCommand(context, fixture, playerSource, output,
                    "aibot task assign StripCmdGT mine_vein minecraft:diamond_ore");
        } finally {
            cleanupFixture(context, fixture);
        }
        context.complete();
    }

    private static void requireRejected(TestContext context, String id, boolean requireCorrection) {
        try {
            ToolRegistry.oreTargetsFrom(id);
            context.throwGameTestException(id + " silently became a common-ore target");
        } catch (IllegalArgumentException expected) {
            String message = expected.getMessage();
            if (message == null || !message.contains("unsupported_mine_ore_target: " + id)) {
                context.throwGameTestException("unexpected rejection for " + id + ": " + message);
            }
            if (requireCorrection && !message.contains("use achieve_goal with item=" + id)) {
                context.throwGameTestException("missing achieve_goal correction for " + id);
            }
        }
    }

    private static void requireToolRejected(TestContext context,
                                            ActiveFixture fixture,
                                            ToolRegistry registry,
                                            String toolName,
                                            JsonObject args) {
        ToolDefinition definition = registry.get(toolName).orElse(null);
        require(context, definition != null, toolName + " was not registered");
        ToolDefinition.ToolResult result = definition.handler().invoke(fixture.bot(), args);
        require(context, result != null && !result.ok(), toolName + " did not reject strict mode");
        require(context, StripMineTask.STRICT_SURVIVAL_REJECTION.equals(result.message()),
                toolName + " returned the wrong typed reason: "
                        + (result == null ? "null" : result.message()));
        requireUndisturbed(context, fixture, toolName);
    }

    private static void runRejectedCommand(TestContext context,
                                           ActiveFixture fixture,
                                           ServerCommandSource playerSource,
                                           RecordingCommandOutput output,
                                           String command) {
        output.clear();
        fixture.bot().getServer().getCommandManager().executeWithPrefix(playerSource, command);
        require(context, output.messages().stream().anyMatch(message ->
                        message.contains(StripMineTask.STRICT_SURVIVAL_REJECTION)),
                command + " did not publish the typed rejection: " + output.messages());
        require(context, output.messages().stream().noneMatch(message ->
                        message.contains("task assigned")),
                command + " published a false assignment: " + output.messages());
        requireUndisturbed(context, fixture, command);
    }

    private static ActiveFixture activeFixture(TestContext context, String botName) {
        var world = context.getWorld();
        BlockPos spawn = context.getAbsolutePos(new BlockPos(1, 126, 1));
        prepareCell(world, spawn);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3d.ofBottomCenter(spawn),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));

        BotRuntimeOptions.INSTANCE.setVerboseReportsEnabled(bot, true);
        SentinelTask sentinel = new SentinelTask();
        TaskOrigin origin = TaskOrigin.of(TaskOrigin.Kind.VERIFY, "strip_mine_strict_gate_sentinel");
        TaskManager.INSTANCE.assign(bot, sentinel, origin);
        ActionResult action = bot.getActionPack().startWalkTo(bot.getPos().add(2.0D, 0.0D, 0.0D));
        require(context, action.isInProgress(), "sentinel walk action did not start");

        long reportSequence = BotReporter.INSTANCE.taskReportSequence(bot);
        require(context, reportSequence > 0L, "sentinel assignment did not activate report sequencing");
        ActionSnapshot actionSnapshot = ActionSnapshot.capture(bot);
        require(context, actionSnapshot.hasActiveActions() && !actionSnapshot.walkToIdle(),
                "sentinel walk action was not observable");
        return new ActiveFixture(
                botName,
                bot,
                sentinel,
                TaskManager.INSTANCE.status(bot),
                origin,
                reportSequence,
                actionSnapshot);
    }

    private static void requireUndisturbed(TestContext context,
                                           ActiveFixture fixture,
                                           String route) {
        require(context, TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null)
                        == fixture.sentinel(),
                route + " replaced the active sentinel task");
        require(context, fixture.status().equals(TaskManager.INSTANCE.status(fixture.bot())),
                route + " changed the published task status");
        require(context, TaskManager.INSTANCE.activeOrigin(fixture.bot())
                        .filter(fixture.origin()::equals).isPresent(),
                route + " changed active task authority");
        require(context, BotReporter.INSTANCE.taskReportSequence(fixture.bot())
                        == fixture.reportSequence(),
                route + " published an assignment/task event");
        require(context, fixture.actionSnapshot().equals(ActionSnapshot.capture(fixture.bot())),
                route + " stopped or replaced the active walk action");
    }

    private static void cleanupFixture(TestContext context, ActiveFixture fixture) {
        IntentController.INSTANCE.cancelAll(
                fixture.bot(), IntentController.ControlOrigin.SYSTEM,
                "strip_mine_strict_gate_gametest_cleanup");
        AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), fixture.botName());
    }

    private static void prepareCell(net.minecraft.server.world.ServerWorld world, BlockPos center) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlockState(center.add(dx, -1, dz), Blocks.STONE.getDefaultState(),
                        Block.NOTIFY_LISTENERS);
                world.setBlockState(center.add(dx, 0, dz), Blocks.AIR.getDefaultState(),
                        Block.NOTIFY_LISTENERS);
                world.setBlockState(center.add(dx, 1, dz), Blocks.AIR.getDefaultState(),
                        Block.NOTIFY_LISTENERS);
            }
        }
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private record ActiveFixture(String botName,
                                 AIPlayerEntity bot,
                                 SentinelTask sentinel,
                                 TaskStatus status,
                                 TaskOrigin origin,
                                 long reportSequence,
                                 ActionSnapshot actionSnapshot) {
    }

    private record ActionSnapshot(boolean hasActiveActions,
                                  boolean walkToIdle,
                                  boolean pathExecutorIdle,
                                  boolean miningIdle,
                                  BlockPos activePathGoal) {
        private static ActionSnapshot capture(AIPlayerEntity bot) {
            return new ActionSnapshot(
                    bot.getActionPack().hasActiveActions(),
                    bot.getActionPack().isWalkToIdle(),
                    bot.getActionPack().isPathExecutorIdle(),
                    bot.getActionPack().isMiningIdle(),
                    bot.getActionPack().activePathGoal());
        }
    }

    private static final class SentinelTask extends AbstractTask {
        @Override
        public String name() {
            return "strict_gate_sentinel";
        }

        @Override
        public String describe() {
            return "strict gate sentinel";
        }

        @Override
        public double progress() {
            return 0.25D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }
    }

    private static final class RecordingCommandOutput implements CommandOutput {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendMessage(Text message) {
            messages.add(message.getString());
        }

        @Override
        public boolean shouldReceiveFeedback() {
            return true;
        }

        @Override
        public boolean shouldTrackOutput() {
            return true;
        }

        @Override
        public boolean shouldBroadcastConsoleToOps() {
            return false;
        }

        private List<String> messages() {
            return List.copyOf(messages);
        }

        private void clear() {
            messages.clear();
        }
    }
}
