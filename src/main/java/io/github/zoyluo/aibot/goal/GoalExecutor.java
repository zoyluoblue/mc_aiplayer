package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.brain.BotReporter;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.GatherQuotaTask;
import io.github.zoyluo.aibot.task.MineTask;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.OreSeekTask;
import io.github.zoyluo.aibot.task.SmeltTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskState;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GoalExecutor {
    public static final GoalExecutor INSTANCE = new GoalExecutor();

    private final Map<UUID, ActivePlan> activePlans = new ConcurrentHashMap<>();

    private GoalExecutor() {
    }

    public boolean submit(AIPlayerEntity bot, Goal goal) {
        GoalPlanner.GoalPlan plan = GoalPlanner.plan(bot, goal);
        if (!plan.success()) {
            report(bot, "目标暂时无法规划:" + String.join(", ", plan.unresolved()));
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, bot, "goal_plan_failed",
                    "goal", goal,
                    "unresolved", plan.unresolved());
            return false;
        }
        if (plan.steps().isEmpty()) {
            activePlans.remove(bot.getUuid());
            report(bot, "目标已经满足。");
            return true;
        }
        ActivePlan active = new ActivePlan(goal, new ArrayDeque<>(plan.steps()), plan.steps().size());
        activePlans.put(bot.getUuid(), active);
        BotLog.task(bot, "goal_plan", "goal", goal, "steps", plan.describeSteps());
        report(bot, "我会按 " + plan.steps().size() + " 步完成目标。");
        assignNext(bot, active);
        return true;
    }

    public boolean tickBot(MinecraftServer server, AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        if (plan == null) {
            return false;
        }
        if (TaskManager.INSTANCE.getActive(bot).isPresent()) {
            return true;
        }
        if (plan.current == null) {
            assignNext(bot, plan);
            return true;
        }
        TaskStatus status = TaskManager.INSTANCE.status(bot);
        if (status.state() == TaskState.COMPLETED) {
            BotLog.task(bot, "goal_step_completed", "step", plan.current.describe());
            plan.current = null;
            assignNext(bot, plan);
            return true;
        }
        if (status.state() == TaskState.FAILED) {
            handleStepFailure(server, bot, plan, status.failureReason());
            return true;
        }
        assignNext(bot, plan);
        return true;
    }

    public boolean hasActivePlan(AIPlayerEntity bot) {
        return activePlans.containsKey(bot.getUuid());
    }

    public void clear(AIPlayerEntity bot) {
        activePlans.remove(bot.getUuid());
    }

    private void assignNext(AIPlayerEntity bot, ActivePlan plan) {
        GoalStep step = plan.steps.pollFirst();
        if (step == null) {
            activePlans.remove(bot.getUuid());
            BotLog.task(bot, "goal_completed", "goal", plan.goal);
            report(bot, "目标完成。");
            return;
        }
        Optional<Task> task = stepToTask(bot, step);
        if (task.isEmpty()) {
            activePlans.remove(bot.getUuid());
            report(bot, "目标步骤无法执行:" + step.describe());
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, bot, "goal_step_unmapped", "step", step.describe());
            return;
        }
        plan.current = step;
        int done = plan.totalSteps - plan.steps.size();
        BotLog.task(bot, "goal_step", "index", done, "total", plan.totalSteps, "step", step.describe());
        TaskManager.INSTANCE.assign(bot, task.get());
    }

    private void handleStepFailure(MinecraftServer server, AIPlayerEntity bot, ActivePlan plan, String reason) {
        if (plan.replanned) {
            activePlans.remove(bot.getUuid());
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, bot, "goal_failed", "goal", plan.goal, "reason", reason);
            report(bot, "目标失败:" + (reason == null || reason.isBlank() ? "步骤失败" : reason));
            return;
        }
        plan.replanned = true;
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(bot, plan.goal);
        BotLog.task(bot, "goal_replan", "goal", plan.goal, "reason", reason, "steps", fresh.describeSteps(), "unresolved", fresh.unresolved());
        if (!fresh.success() || fresh.steps().isEmpty()) {
            activePlans.remove(bot.getUuid());
            report(bot, fresh.success() ? "目标已停止:没有可继续执行的步骤。" : "目标重规划失败:" + String.join(", ", fresh.unresolved()));
            return;
        }
        plan.steps.clear();
        plan.steps.addAll(fresh.steps());
        plan.totalSteps = fresh.steps().size();
        plan.current = null;
        report(bot, "遇到问题,我重新规划了一次。");
        assignNext(bot, plan);
    }

    private static Optional<Task> stepToTask(AIPlayerEntity bot, GoalStep step) {
        return switch (step.kind()) {
            case GATHER -> Optional.of(new GatherQuotaTask(step.item(), step.count()));
            case MINE -> Optional.of(new MineTask(step.block(), step.count()));
            case MINE_ORE -> Optional.of(new OreSeekTask(step.ores(), step.count()));
            case CRAFT -> Optional.of(new CraftTask(step.item(), step.count()));
            case SMELT -> Optional.of(new SmeltTask(step.input(), step.output(), step.count()));
            case MOVE -> Optional.of(new MoveTask(bot, step.pos()));
        };
    }

    private static void report(AIPlayerEntity bot, String text) {
        BotReporter.INSTANCE.onGoalMessage(bot, text);
    }

    private static final class ActivePlan {
        private final Goal goal;
        private final ArrayDeque<GoalStep> steps;
        private GoalStep current;
        private int totalSteps;
        private boolean replanned;

        private ActivePlan(Goal goal, ArrayDeque<GoalStep> steps, int totalSteps) {
            this.goal = goal;
            this.steps = steps;
            this.totalSteps = totalSteps;
        }
    }
}
