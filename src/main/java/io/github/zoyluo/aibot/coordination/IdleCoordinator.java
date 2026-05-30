package io.github.zoyluo.aibot.coordination;

import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.task.BlueprintLoader;
import io.github.zoyluo.aibot.task.BuildTask;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.EatTask;
import io.github.zoyluo.aibot.task.LightAreaTask;
import io.github.zoyluo.aibot.task.MineTask;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.SmeltTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskState;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class IdleCoordinator {
    public static final IdleCoordinator INSTANCE = new IdleCoordinator();

    private final Map<UUID, UUID> claimedJobs = new ConcurrentHashMap<>();

    private IdleCoordinator() {
    }

    public void tick(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            tickBot(bot);
        }
    }

    public boolean tickBot(AIPlayerEntity bot) {
        if (TaskManager.INSTANCE.getActive(bot).isPresent()) {
            return false;
        }
        // GOALFIX-GF1 P0-A:bot 有活跃目标计划时,空闲分配让位给 GoalExecutor(防步骤间隙抢任务板作业)。
        if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return false;
        }
        UUID currentJob = claimedJobs.remove(bot.getUuid());
        if (currentJob != null) {
            finishClaimedJob(bot, currentJob);
        }
        if (BrainCoordinator.INSTANCE.status(bot).busy()) {
            return false;
        }
        Optional<Job> job = TaskBoard.INSTANCE.claimNext(bot, AIPlayerManager.INSTANCE.roles(bot));
        job.ifPresent(next -> assignJob(bot, next));
        return job.isPresent();
    }

    public void onBotRemoved(AIPlayerEntity bot) {
        UUID jobId = claimedJobs.remove(bot.getUuid());
        if (jobId != null) {
            TaskBoard.INSTANCE.markFailed(jobId, "bot_removed");
        }
    }

    private void finishClaimedJob(AIPlayerEntity bot, UUID jobId) {
        TaskStatus status = TaskManager.INSTANCE.status(bot);
        if (status.state() == TaskState.FAILED) {
            TaskBoard.INSTANCE.markFailed(jobId, status.failureReason().isBlank() ? "task_failed" : status.failureReason());
        } else {
            TaskBoard.INSTANCE.markDone(jobId);
        }
    }

    private void assignJob(AIPlayerEntity bot, Job job) {
        Optional<Task> task = jobToTask(bot, job);
        if (task.isEmpty()) {
            TaskBoard.INSTANCE.markFailed(job.id(), "unknown_or_bad_job: " + job.kind());
            return;
        }
        claimedJobs.put(bot.getUuid(), job.id());
        TaskManager.INSTANCE.assign(bot, task.get());
    }

    public static Optional<Task> jobToTask(AIPlayerEntity bot, Job job) {
        try {
            Map<String, String> params = job.params();
            return switch (job.kind()) {
                case "move" -> Optional.of(new MoveTask(bot, blockPos(params, "x", "y", "z")));
                case "mine" -> Optional.of(new MineTask(requiredBlock(params, "block"), intParam(params, "count", 1)));
                case "craft" -> Optional.of(new CraftTask(requiredItem(params, "item"), intParam(params, "count", 1)));
                case "smelt" -> Optional.of(new SmeltTask(requiredItem(params, "input_item"), requiredItem(params, "output_item"), intParam(params, "count", 1)));
                case "eat" -> Optional.of(new EatTask());
                case "light_area" -> Optional.of(new LightAreaTask(intParam(params, "radius", 8), intParam(params, "max_torches", 8)));
                case "build" -> Optional.of(new BuildTask(
                        BlueprintLoader.load(required(params, "blueprint")),
                        booleanParam(params, "auto_site", false) ? null : optionalBlockPos(params).orElseThrow(),
                        booleanParam(params, "auto_site", false),
                        booleanParam(params, "flatten", false)));
                default -> Optional.empty();
            };
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Block requiredBlock(Map<String, String> params, String name) {
        Identifier id = Identifier.of(required(params, name));
        return Registries.BLOCK.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_block: " + id));
    }

    private static Item requiredItem(Map<String, String> params, String name) {
        Identifier id = Identifier.of(required(params, name));
        return Registries.ITEM.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_item: " + id));
    }

    private static Optional<BlockPos> optionalBlockPos(Map<String, String> params) {
        if (!params.containsKey("x") || !params.containsKey("y") || !params.containsKey("z")) {
            return Optional.empty();
        }
        return Optional.of(blockPos(params, "x", "y", "z"));
    }

    private static BlockPos blockPos(Map<String, String> params, String x, String y, String z) {
        return new BlockPos(
                Integer.parseInt(required(params, x)),
                Integer.parseInt(required(params, y)),
                Integer.parseInt(required(params, z)));
    }

    private static int intParam(Map<String, String> params, String name, int fallback) {
        String value = params.get(name);
        return value == null || value.isBlank() ? fallback : Math.max(1, Integer.parseInt(value));
    }

    private static boolean booleanParam(Map<String, String> params, String name, boolean fallback) {
        String value = params.get(name);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private static String required(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing_param: " + name);
        }
        return value;
    }
}
