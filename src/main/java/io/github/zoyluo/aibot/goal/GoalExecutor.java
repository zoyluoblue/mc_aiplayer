package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.AIBotConfig;
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
        // GOALFIX-GF3:幂等——同一 bot 已有相同目标的活跃计划时,忽略重复 submit
        //(防大脑连点 mine_ore/achieve_goal 覆盖计划、打断进行中的步骤)。
        ActivePlan existing = activePlans.get(bot.getUuid());
        if (existing != null && existing.goal.equals(goal)) {
            BotLog.task(bot, "goal_submit_ignored", "goal", goal, "reason", "duplicate_active_plan");
            return true;
        }
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
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        if (active.isPresent()) {
            if (plan.currentTask != null && active.get() != plan.currentTask) {
                // FREEZE fix:有外来活跃任务时,先看我们的 step 是否被暂存进 paused 池。
                // 生存任务(战斗/逃跑/进食)抢占会把当前 step pauseFor 进 paused 池——这是临时抢占,
                // 打完会 resume,绝不能放弃整个目标(实测:刷怪→combat→goal_abandoned×12→从零重规划空转)。
                if (TaskManager.INSTANCE.hasPaused(bot)) {
                    return true;
                }
                // step 既不活跃也不在暂停池 = 被玩家显式指令真正替换 → 放弃目标让位。
                BotLog.task(bot, "goal_abandoned", "goal", plan.goal, "reason", "foreign_task_assigned");
                activePlans.remove(bot.getUuid());
                return false;
            }
            return true;
        }
        // GOALFIX-GF1 P0-B:当前步被安全网暂停(生存任务抢占)→ 等待 resume,不要误判为步骤结束而跳步。
        if (TaskManager.INSTANCE.hasPaused(bot)) {
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
            plan.currentTask = null;
            assignNext(bot, plan);
            return true;
        }
        if (status.state() == TaskState.FAILED) {
            handleStepFailure(server, bot, plan, status.failureReason());
            return true;
        }
        // GOALFIX-GF1 P0-B:其它状态(如上一任务残留的 lastStatus)→ 防御性 no-op,
        // 步骤推进只由 COMPLETED 分支驱动,失败由 FAILED 分支驱动。
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
        plan.currentTask = task.get();
        int done = plan.totalSteps - plan.steps.size();
        BotLog.task(bot, "goal_step", "index", done, "total", plan.totalSteps, "step", step.describe());
        TaskManager.INSTANCE.assign(bot, task.get());
    }

    private void handleStepFailure(MinecraftServer server, AIPlayerEntity bot, ActivePlan plan, String reason) {
        if (plan.replanned || !AIBotConfig.get().goal().replanOnFailureEnabled()) {
            activePlans.remove(bot.getUuid());
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, bot, "goal_failed", "goal", plan.goal, "reason", reason);
            report(bot, humanGoalFailure(reason));
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
        plan.currentTask = null;
        report(bot, "遇到问题,我重新规划了一次。");
        assignNext(bot, plan);
    }

    private static Optional<Task> stepToTask(AIPlayerEntity bot, GoalStep step) {
        return switch (step.kind()) {
            case GATHER -> Optional.of(new GatherQuotaTask(step.item(), step.count()));
            // MINE-DIG:MINE 步走定向挖掘器(OreSeekTask 定向模式),能往下挖到埋藏的石层;
            // MineTask 只能挖裸露方块,地表 bot 对埋在草皮下的石头会 no_reachable_target_block_in_range。
            case MINE -> Optional.of(OreSeekTask.digBlocks(java.util.Set.of(step.block()), step.count()));
            case MINE_ORE -> Optional.of(new OreSeekTask(step.ores(), step.count()));
            case CRAFT -> Optional.of(new CraftTask(step.item(), step.count()));
            case SMELT -> Optional.of(new SmeltTask(step.input(), step.output(), step.count()));
            case MOVE -> Optional.of(new MoveTask(bot, step.pos()));
        };
    }

    private static void report(AIPlayerEntity bot, String text) {
        BotReporter.INSTANCE.onGoalMessage(bot, text);
    }

    // P1:目标失败时给出可执行的中文引导,避免大脑收到原始 reason 后用 move 乱走探索而遇险。
    private static String humanGoalFailure(String reason) {
        String r = reason == null ? "" : reason;
        if (r.contains("no_resource_nearby") || r.contains("no_reachable") || r.contains("no_ore_found")) {
            return "我在较大范围内都没找到可用的树木/石头/矿石,暂时无法继续。我会待在原地,不乱走也不空手挖。";
        }
        if (r.startsWith("need_better_tool") || r.startsWith("need_pickaxe")) {
            return "我还缺合适的镐,正在自动准备;若准备不出来我会停下,不会空手硬挖。";
        }
        return "目标失败:" + (r.isBlank() ? "步骤失败" : r);
    }

    private static final class ActivePlan {
        private final Goal goal;
        private final ArrayDeque<GoalStep> steps;
        private GoalStep current;
        private Task currentTask;
        private int totalSteps;
        private boolean replanned;

        private ActivePlan(Goal goal, ArrayDeque<GoalStep> steps, int totalSteps) {
            this.goal = goal;
            this.steps = steps;
            this.totalSteps = totalSteps;
        }
    }
}
