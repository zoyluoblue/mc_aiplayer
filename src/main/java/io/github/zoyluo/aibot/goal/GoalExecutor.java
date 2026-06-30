package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.brain.BotReporter;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.task.BlueprintLoader;
import io.github.zoyluo.aibot.task.BuildTask;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.DescendToYTask;
import io.github.zoyluo.aibot.task.DigDownTask;
import io.github.zoyluo.aibot.task.FarmTask;
import io.github.zoyluo.aibot.task.GatherQuotaTask;
import io.github.zoyluo.aibot.task.HuntTask;
import io.github.zoyluo.aibot.task.MilkCowTask;
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

import java.io.IOException;
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
    // P0 目标队列(对话式助手根基):复合指令"先搞吃的再挖铁"需要连续目标。原单 plan 模型下
    // 第二个目标会被拒/覆盖(prompt 甚至要求"一次一个,调完 STOP")。现在:活跃目标存在时新目标入队,
    // 当前目标完成/失败后自动出队衔接(像真人:手头干完接着办下一件,办不成说一声跳过)。
    private final Map<UUID, java.util.Deque<Goal>> goalQueue = new ConcurrentHashMap<>();
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
        // P0 队列:已有进行中的目标 → 新目标入队(去重),手头干完自动接续。复合指令/连续吩咐的根基。
        // 注意放在"前置降级拦截"之后判定才安全?不——降级拦截在下面,先让它检查:子目标仍要拦。
        java.util.Deque<Goal> queued = goalQueue.computeIfAbsent(bot.getUuid(), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        if (existing != null) {
            Goal ugQ = userGoal.get(bot.getUuid());
            if (ugQ != null && !ugQ.equals(goal) && isPrerequisiteOf(bot, goal, ugQ)) {
                BotLog.task(bot, "goal_downgrade_blocked", "sub", goal, "user", ugQ);
                report(bot, "这是当前目标的前置步骤,系统会自动完成,无需单独做。");
                return false;
            }
            if (queued.stream().anyMatch(goal::equals)) {
                BotLog.task(bot, "goal_submit_ignored", "goal", goal, "reason", "duplicate_queued");
                return true;
            }
            queued.addLast(goal);
            BotLog.task(bot, "goal_queued", "goal", goal, "behind", String.valueOf(existing.goal), "queue_size", queued.size());
            report(bot, "记下了,等手头这件干完就去办:" + goalLabel(goal));
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
        ActivePlan active = new ActivePlan(goal, new ArrayDeque<>(plan.steps()), plan.steps().size(),
                plan.steps().stream().map(GoalStep::describe).toList());
        // Phase A:进度快照初始化(开局基准),供 handleStepFailure 的进度赦免对比。
        net.minecraft.util.math.BlockPos sp0 = bot.getBlockPos();
        active.snapX = sp0.getX();
        active.snapY = sp0.getY();
        active.snapZ = sp0.getZ();
        active.snapTargetCount = goalTargetCount(bot, goal);
        activePlans.put(bot.getUuid(), active);
        // 工作记忆 episode 边界:新目标=新 episode,上一件事的排除项/轨迹作废。
        // (replan 不走这里——handleStepFailure 原地改 plan.steps,工作记忆跨 replan 存活,这正是设计。)
        io.github.zoyluo.aibot.task.EpisodeMemory.INSTANCE.reset(bot.getUuid());
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
            plan.completedSteps++; // Phase A:完成一步=进展信号
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
        // user goal 记录一并清:clear=外部要求彻底复位(verify 场景切换/管理操作)。只清 plan 不清它,
        // 残留的旧 Food 目标会把后续"恰好是其前置"的新目标 downgrade_blocked 拒掉
        //(实测 verify forage 的 HaveItem(浆果) 被上一场景 Food 残留拦截 goal_submit_failed)。
        userGoal.remove(bot.getUuid());
        goalQueue.remove(bot.getUuid()); // 队列一并清:复位=全部待办作废
        io.github.zoyluo.aibot.task.EpisodeMemory.INSTANCE.reset(bot.getUuid()); // 工作记忆同清
    }

    /** 诊断埋点:当前激活的顶层目标(无则 "none")。日志用,保留英文便于排查。 */
    public String describeActiveGoal(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        return plan == null ? "none" : String.valueOf(plan.goal);
    }

    /** 面板任务链条:目标标题(中文)。物品 id 保留 minecraft:xxx,客户端再本地化成中文名。 */
    public String activeGoalTitle(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        return plan == null ? "无目标" : goalLabel(plan.goal);
    }

    private static String goalLabel(Goal goal) {
        return switch (goal) {
            case Goal.HaveItem g -> "获取 " + io.github.zoyluo.aibot.craft.ItemNames.cn(g.item()) + " ×" + g.count();
            case Goal.MineOre g -> "采矿 ×" + g.count();
            case Goal.HarvestCrop g -> "种收 " + io.github.zoyluo.aibot.craft.ItemNames.cn(g.produce()) + " ×" + g.count();
            case Goal.Food g -> "备食物(熟食) ×" + g.cookedCount();
            case Goal.Armor g -> "武装(整套护甲+剑)";
            case Goal.Workstation g -> "搭建工作站";
            case Goal.Stockpile g -> "囤货 " + io.github.zoyluo.aibot.craft.ItemNames.cn(g.item()) + " ×" + g.count();
            case Goal.HavePickaxeTier g -> "升级镐 (tier " + g.tier() + ")";
            case Goal.Build g -> "盖房子(" + g.blueprint() + ")";
        };
    }

    /** 诊断埋点:当前正在执行的步骤 + 进度 [第几步/总步数](无激活步则 "")。 */
    public String describeActiveStep(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        if (plan == null || plan.current == null) {
            return "";
        }
        int idx = plan.totalSteps - plan.steps.size(); // current 已从 steps 取出,正在做第 idx 步
        return plan.current.describe() + " [" + idx + "/" + plan.totalSteps + "]";
    }

    /** 面板任务链条:完整步骤描述列表(无激活计划则空)。 */
    public java.util.List<String> activeGoalSteps(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        return plan == null ? java.util.List.of() : plan.stepLabels;
    }

    /** 面板任务链条:当前所处步骤的 0 基下标。 */
    public int activeGoalCurrentIndex(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        if (plan == null || plan.current == null) {
            return 0;
        }
        return Math.max(0, plan.totalSteps - plan.steps.size() - 1);
    }

    /** 面板任务链条:总步数。 */
    public int activeGoalTotalSteps(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        return plan == null ? 0 : plan.totalSteps;
    }

    // P0 队列衔接:当前目标了结(完成/失败)后,自动开始队列里的下一个;规划失败的逐个跳过并说明。
    private void advanceQueue(AIPlayerEntity bot) {
        java.util.Deque<Goal> queued = goalQueue.get(bot.getUuid());
        if (queued == null) {
            return;
        }
        Goal next;
        while ((next = queued.pollFirst()) != null) {
            report(bot, "接着办下一件:" + goalLabel(next));
            if (submit(bot, next)) {
                return;
            }
            // submit 失败(规划不成/被拦)已在内部 report 过原因,继续试队列里再下一个
        }
    }

    private void assignNext(AIPlayerEntity bot, ActivePlan plan) {
        GoalStep step = plan.steps.pollFirst();
        if (step == null) {
            // Food 目标续足:步骤跑完 ≠ 真凑够熟食。打猎扑空(附近动物少)、cookAll 只烤了部分生肉时,
            // COOK_FOOD 会 collected>0 即 complete → 步骤耗尽 → 这里谎报"Food[4] 完成",实则只 2/4
            // (real_food 随机地形实测此假完成占食物失败 3/10)。重规划一次让 GoalPlanner 重新感知择源:
            // 它内部按 cooked>=target 判定——已够则返回空步骤(照常下面完成);不够且有源(浆果/种田/再打猎远征)
            // 则给出补足步骤继续。仅对【独立 Food 目标】生效:挖矿/铁套的备粮是其计划内 best-effort 步,
            // plan.goal 是 Diamond/Armor 而非 Food,不受影响(续航本就交饥饿链兜底,不该阻断挖矿)。
            // replanCount<3 兜底:地形真无足量食物源时按尽力收尾,绝不无限循环。
            if (plan.goal instanceof Goal.Food && bot.isAlive() && plan.replanCount < 3) {
                GoalPlanner.GoalPlan fresh = GoalPlanner.plan(bot, plan.goal);
                if (fresh.success() && !fresh.steps().isEmpty()) {
                    plan.replanCount++;
                    BotLog.task(bot, "goal_food_topup", "goal", plan.goal,
                            "steps", fresh.describeSteps(), "replan", String.valueOf(plan.replanCount));
                    plan.steps.clear();
                    plan.steps.addAll(fresh.steps());
                    plan.totalSteps = fresh.steps().size();
                    plan.current = null;
                    plan.currentTask = null;
                    assignNext(bot, plan);
                    return;
                }
            }
            activePlans.remove(bot.getUuid());
            BotLog.task(bot, "goal_completed", "goal", plan.goal);
            io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.zoyluo.aibot.memory.EpisodeLog.Type.GOAL_DONE, bot.getBlockPos(), goalLabel(plan.goal));
            report(bot, "目标完成。");
            userGoal.remove(bot.getUuid()); // 本目标已了结,让队列里下一个接管"用户原始目标"位
            advanceQueue(bot);
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

    // Phase A 进度信号:目标产物当前库存计数(HaveItem/Stockpile 用其物品,MineOre 用矿石掉落)。
    private static int goalTargetCount(AIPlayerEntity bot, Goal goal) {
        if (goal instanceof Goal.HaveItem hi) {
            return io.github.zoyluo.aibot.action.HarvestCore.countInventoryItems(bot, java.util.Set.of(hi.item()));
        }
        if (goal instanceof Goal.Stockpile sp) {
            return io.github.zoyluo.aibot.action.HarvestCore.countInventoryItems(bot, java.util.Set.of(sp.item()));
        }
        if (goal instanceof Goal.MineOre mo) {
            return io.github.zoyluo.aibot.action.HarvestCore.countInventoryItems(bot,
                    io.github.zoyluo.aibot.action.HarvestCore.expectedDropsFor(mo.ores()));
        }
        return 0;
    }

    private void handleStepFailure(MinecraftServer server, AIPlayerEntity bot, ActivePlan plan, String reason) {
        // 第4层:best-effort 步骤(如 HUNT 备粮)失败不阻断整体目标——跳过它直接继续下一步。
        // 这样"挖钻石前备点肉"在周围没动物时也不会让整条挖矿目标 goal_failed(续航仍由饥饿链兜底)。
        // 打猎(Goal.Food)整体 best-effort:任何前置(砍树/做剑)失败都降级继续(用现有工具/空手猎),绝不卡死/发呆。
        // 例外:Food 目标的 COOK_FOOD 是终局产出步——失败(no_raw_food)=整个目标必败,skip 等于无声放弃。
        // 放行到下面的 replan:重新感知择源(打猎扑空后动物多半已不在,replan 会落到浆果/面包等兜底源;
        // 实测打猎 1t 扑空 → 烤无肉 → 静默结束,从未给过兜底源机会)。
        boolean cookFinalOfFood = plan.goal instanceof Goal.Food
                && plan.current != null && plan.current.kind() == GoalStep.Kind.COOK_FOOD;
        boolean foodGoalBestEffort = plan.goal instanceof Goal.Food;
        if (!cookFinalOfFood && plan.current != null && (foodGoalBestEffort
                || plan.current.kind() == GoalStep.Kind.HUNT
                || plan.current.kind() == GoalStep.Kind.COOK_FOOD
                || plan.current.kind() == GoalStep.Kind.STOCKPILE)) {
            BotLog.task(bot, "goal_step_skipped_besteffort", "step", plan.current.describe(), "reason", reason);
            assignNext(bot, plan);
            return;
        }
        // Phase A 进度感知预算(断点恢复核心):有进展→清零"连续无进展"计数,产出区被瞬时打断
        // (骷髅/卡顿)不与原地空转同罪。进展=完成新步 || 挖到更多目标物 || 下潜更深 || 横向位移≥8格
        //(位移信号对 ore_dig strip-mining 前进尤其关键——它是 real_diamond 主导失败面)。只认单向增量防往返误判。
        net.minecraft.util.math.BlockPos bp = bot.getBlockPos();
        int curTarget = goalTargetCount(bot, plan.goal);
        long hMoved2 = (long) (bp.getX() - plan.snapX) * (bp.getX() - plan.snapX)
                     + (long) (bp.getZ() - plan.snapZ) * (bp.getZ() - plan.snapZ);
        boolean madeProgress = plan.completedSteps > plan.snapSteps
                || curTarget > plan.snapTargetCount
                || bp.getY() < plan.snapY
                || hMoved2 >= 64;
        if (madeProgress) {
            plan.replanCount = 0; // 进展赦免
        }
        plan.snapSteps = plan.completedSteps;
        plan.snapTargetCount = curTarget;
        plan.snapX = bp.getX();
        plan.snapY = bp.getY();
        plan.snapZ = bp.getZ();
        plan.lifetimeReplans++;
        // 死亡闸:连续 3 次无进展 replan,或终生 12 次(防"挖一点卡一点"无限磨),或 replan 关闭 → 判死。
        if (plan.replanCount >= 3 || plan.lifetimeReplans >= 12 || !AIBotConfig.get().goal().replanOnFailureEnabled()) {
            activePlans.remove(bot.getUuid());
            lastGoalFailTick.put(bot.getUuid(), server.getTicks());
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, bot, "goal_failed", "goal", plan.goal, "reason", reason);
            io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.zoyluo.aibot.memory.EpisodeLog.Type.GOAL_FAILED, bot.getBlockPos(), goalLabel(plan.goal));
            report(bot, humanGoalFailure(reason));
            userGoal.remove(bot.getUuid());
            advanceQueue(bot); // 像真人:这件办不成说一声,接着办队列里的下一件
            return;
        }
        plan.replanCount++;
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(bot, plan.goal);
        BotLog.task(bot, "goal_replan", "goal", plan.goal, "reason", reason, "steps", fresh.describeSteps(), "unresolved", fresh.unresolved());
        if (!fresh.success() || fresh.steps().isEmpty()) {
            activePlans.remove(bot.getUuid());
            report(bot, fresh.success() ? "目标已停止:没有可继续执行的步骤。" : "目标重规划失败:" + String.join(", ", fresh.unresolved()));
            return;
        }
        // 防呆:若重规划的第一步与刚失败的步骤完全相同,且失败是"硬卡死"类(挖不动/卡住/超时),
        // 重试只会原样再失败一次(实测#9 的 replan 风暴根因)。直接判失败,交大脑/玩家换思路。
        if (plan.current != null && plan.current.equals(fresh.steps().get(0)) && isHardFailure(reason) && !madeProgress) {
            activePlans.remove(bot.getUuid());
            lastGoalFailTick.put(bot.getUuid(), server.getTicks());
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, bot, "goal_failed",
                    "goal", plan.goal, "reason", "replan_same_step:" + reason);
            report(bot, humanGoalFailure(reason));
            userGoal.remove(bot.getUuid());
            advanceQueue(bot);
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
            // 蛋糕链:MILK_COW 步 → MilkCowTask 用空桶挤 count 桶牛奶。
            case MILK_COW -> Optional.of(new MilkCowTask(step.count()));
            // Phase2:PLACE_STATIONS 步 → 摆好工作台/熔炉/箱子。
            case PLACE_STATIONS -> Optional.of(new PlaceStationsTask());
            // Phase3:STOCKPILE 步 → 把背包资源存进附近箱子(存所有非工具)。
            case STOCKPILE -> Optional.of(new StockpileTask(true));
            // 挖深层矿:DESCEND_TO_Y 步 → 连续挖竖井下到矿层。
            case DESCEND_TO_Y -> Optional.of(new DescendToYTask(step.pos().getY()));
            case MAKE_OBSIDIAN -> Optional.of(new io.github.zoyluo.aibot.task.CreateObsidianTask(step.count()));
            // 盖房:BUILD 步 → BuildTask(自动选址 autoSite + 整地 flatten,真实起伏地形也能落成);材料已由规划期备齐;
            // 蓝图读取失败(被删/坏档)→ empty,assignNext 按"步骤无法执行"收尾。
            // flatten=true:真实地形罕有现成平地,lenient 选址选最平点 + FLATTEN 挖高填低整平(治 real_build no_flat_site)。
            case BUILD -> {
                try {
                    yield Optional.of(new BuildTask(BlueprintLoader.load(step.tag()), null, true, true));
                } catch (IOException e) {
                    yield Optional.empty();
                }
            }
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
        if (r.contains("no_resource_after_explore")) {
            // EXPLORE 已定向走出去几片区域都找过(非"原地没找到"),如实区分,免得大脑再让 move 乱走重试。
            return "我已经走出去好几片区域找过了,还是没找到需要的资源,暂时无法继续。我会待在原地,不乱走也不空手挖。";
        }
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
        private final java.util.List<String> stepLabels; // 完整步骤描述(steps 会随执行 poll 空,这里留全量供面板任务链条展示)
        private GoalStep current;
        private Task currentTask;
        private int totalSteps;
        private int replanCount;       // Phase A:语义=连续无进展 replan 数(有进展则清零)
        // Phase A 韧性·进度感知预算(断点恢复):
        private int completedSteps;    // 累计完成步数(单调增)
        private int lifetimeReplans;   // 终生 replan 数(永不重置,绝对兜底闸=12)
        private int snapSteps;         // 上次 replan 时 completedSteps 快照
        private int snapTargetCount;   // 上次 replan 时目标产物库存计数
        private int snapX, snapY, snapZ; // 上次 replan 时 bot 坐标(横向位移/下潜=进展判据)

        private ActivePlan(Goal goal, ArrayDeque<GoalStep> steps, int totalSteps, java.util.List<String> stepLabels) {
            this.goal = goal;
            this.steps = steps;
            this.totalSteps = totalSteps;
            this.stepLabels = stepLabels;
        }
    }
}
