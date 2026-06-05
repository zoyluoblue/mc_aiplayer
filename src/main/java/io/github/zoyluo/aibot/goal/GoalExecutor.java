package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.brain.BotReporter;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.DescendToYTask;
import io.github.zoyluo.aibot.task.DigDownTask;
import io.github.zoyluo.aibot.task.FarmTask;
import io.github.zoyluo.aibot.task.GatherQuotaTask;
import io.github.zoyluo.aibot.task.HuntTask;
import io.github.zoyluo.aibot.task.MineTask;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.OreDigTask;
import io.github.zoyluo.aibot.task.PlaceStationsTask;
import io.github.zoyluo.aibot.task.SmeltTask;
import io.github.zoyluo.aibot.task.StockpileTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskState;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GoalExecutor {
    public static final GoalExecutor INSTANCE = new GoalExecutor();

    private final Map<UUID, ActivePlan> activePlans = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastGoalFailTick = new ConcurrentHashMap<>(); // 优化2:goal 整体失败时刻,拦大脑随后手动逐格挖矿
    private final Map<UUID, Goal> userGoal = new ConcurrentHashMap<>(); // B:用户原始高层目标,防大脑把它降级成其前置子目标(挖钻石→做铁镐)

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
        // B:保护用户原始目标——大脑不能把它降级成其前置子目标。实测:挖钻石失败后大脑 achieve_goal 做铁镐、
        // mine_ore 挖铁(都是挖钻石的前置)覆盖了目标,做完铁镐还误报"任务完成、最初要求是挖铁做镐"。
        Goal ug = userGoal.get(bot.getUuid());
        if (ug != null && !ug.equals(goal) && isPrerequisiteOf(bot, goal, ug)) {
            BotLog.task(bot, "goal_downgrade_blocked", "sub", goal, "user", ug);
            report(bot, "这是当前目标的前置步骤,系统会自动完成,无需单独做。要更换目标请直接告诉我。");
            return false;
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
        userGoal.putIfAbsent(bot.getUuid(), goal); // B:首个目标记为"用户原始目标";后续前置子目标被上面拦下,换目标由用户消息清空
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
        // 第4层:best-effort 步骤(如 HUNT 备粮)失败不阻断整体目标——跳过它直接继续下一步。
        // 这样"挖钻石前备点肉"在周围没动物时也不会让整条挖矿目标 goal_failed(续航仍由饥饿链兜底)。
        // 打猎(Goal.Food)整体 best-effort:任何前置(砍树/做剑)失败都降级继续(用现有工具/空手猎),绝不卡死/发呆。
        boolean foodGoalBestEffort = plan.goal instanceof Goal.Food;
        if (plan.current != null && (foodGoalBestEffort
                || plan.current.kind() == GoalStep.Kind.HUNT
                || plan.current.kind() == GoalStep.Kind.COOK_FOOD
                || plan.current.kind() == GoalStep.Kind.STOCKPILE)) {
            BotLog.task(bot, "goal_step_skipped_besteffort", "step", plan.current.describe(), "reason", reason);
            assignNext(bot, plan);
            return;
        }
        if (plan.replanned || !AIBotConfig.get().goal().replanOnFailureEnabled()) {
            activePlans.remove(bot.getUuid());
            lastGoalFailTick.put(bot.getUuid(), server.getTicks());
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
        // 防呆:若重规划的第一步与刚失败的步骤完全相同,且失败是"硬卡死"类(挖不动/卡住/超时),
        // 重试只会原样再失败一次(实测#9 的 replan 风暴根因)。直接判失败,交大脑/玩家换思路。
        if (plan.current != null && plan.current.equals(fresh.steps().get(0)) && isHardFailure(reason)) {
            activePlans.remove(bot.getUuid());
            lastGoalFailTick.put(bot.getUuid(), server.getTicks());
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, bot, "goal_failed",
                    "goal", plan.goal, "reason", "replan_same_step:" + reason);
            report(bot, humanGoalFailure(reason));
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

    // 优化2:目标最近(withinTicks 内)是否整体失败过——供 ActionDispatcher 拦截大脑失败后的手动逐格挖矿。
    public boolean recentlyFailed(AIPlayerEntity bot, int withinTicks) {
        Integer t = lastGoalFailTick.get(bot.getUuid());
        return t != null && bot.getServer().getTicks() - t < withinTicks;
    }

    // B:用户发来新消息时清空原始目标记忆(允许用户正常更换目标);由 BrainCoordinator 在收到用户消息时调用。
    public void clearUserGoal(AIPlayerEntity bot) {
        userGoal.remove(bot.getUuid());
    }

    // B:sub 是否是 parent(用户原始目标)的前置——sub 的产物落在 parent 计划某一步的产出里。
    // 覆盖主 case:做铁镐(HaveItem)/挖铁(MineOre)都是挖钻石计划里的前置步骤,会被拦下。
    private boolean isPrerequisiteOf(AIPlayerEntity bot, Goal sub, Goal parent) {
        GoalPlanner.GoalPlan parentPlan = GoalPlanner.plan(bot, parent);
        Set<Item> items = new HashSet<>();
        Set<Block> ores = new HashSet<>();
        for (GoalStep s : parentPlan.steps()) {
            if (s.item() != null) {
                items.add(s.item());
            }
            if (s.kind() == GoalStep.Kind.MINE_ORE) {
                ores.addAll(s.ores());
            }
        }
        if (sub instanceof Goal.HaveItem hi) {
            return items.contains(hi.item());
        }
        if (sub instanceof Goal.MineOre mo) {
            for (Block b : mo.ores()) {
                if (ores.contains(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Optional<Task> stepToTask(AIPlayerEntity bot, GoalStep step) {
        return switch (step.kind()) {
            case GATHER -> Optional.of(new GatherQuotaTask(step.item(), step.count()));
            // DIGDOWN(实测#8):MINE 步改用 DigDownTask——站着就近垂直下挖,不定位/不寻路,
            // 永不"够不到/走不过去"空转。取代旧的 OreSeekTask.digBlocks(它会锁定垂直够不到的石头 stuck)。
            case MINE -> Optional.of(new DigDownTask(step.block(), step.count()));
            // OREDIG(实测#10):MINE_ORE 步改用 OreDigTask(BlockMiner 控制式直挖隧道),
            // 取代 OreSeekTask——后者"A*接近被埋矿"在 #6/#8/#10 连续 stuck。
            case MINE_ORE -> Optional.of(new OreDigTask(step.ores(), step.count()));
            case CRAFT -> Optional.of(new CraftTask(step.item(), step.count()));
            case SMELT -> Optional.of(new SmeltTask(step.input(), step.output(), step.count()));
            case MOVE -> Optional.of(new MoveTask(bot, step.pos()));
            // P3:FARM 步 → 数量受限的 FarmTask(就地开垦/播种/等熟/收割,收够 count 个产出即完成)。
            case FARM -> Optional.of(new FarmTask(bot.getBlockPos(), 4, step.input(), step.block(),
                    true, false, step.item(), step.count()));
            // 第4层:HUNT 步 → HuntTask 猎杀动物取生肉(备粮)。
            case HUNT -> Optional.of(new HuntTask(step.count()));
            // P0 食物闭环:COOK_FOOD 步 → SmeltTask cookAll 模式,把背包生肉逐种烤成熟肉。
            case COOK_FOOD -> Optional.of(new SmeltTask(step.count()));
            // Phase2:PLACE_STATIONS 步 → 摆好工作台/熔炉/箱子。
            case PLACE_STATIONS -> Optional.of(new PlaceStationsTask());
            // Phase3:STOCKPILE 步 → 把背包资源存进附近箱子(存所有非工具)。
            case STOCKPILE -> Optional.of(new StockpileTask(true));
            // 挖深层矿:DESCEND_TO_Y 步 → 连续挖竖井下到矿层。
            case DESCEND_TO_Y -> Optional.of(new DescendToYTask(step.pos().getY()));
        };
    }

    private static void report(AIPlayerEntity bot, String text) {
        BotReporter.INSTANCE.onGoalMessage(bot, text);
    }

    // 硬卡死类失败:原样重试只会再失败(挖不动/卡住/超时/够不到)。区别于"缺料/缺镐"这类重规划能补的。
    private static boolean isHardFailure(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.contains("no_progress")
                || reason.contains("dig_down_blocked")
                || reason.contains("stuck:")
                || reason.contains("timeout")
                || reason.contains("no_reachable");
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
