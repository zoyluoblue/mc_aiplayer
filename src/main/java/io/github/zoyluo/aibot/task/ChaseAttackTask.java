package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;
import java.util.UUID;

/**
 * 持续追杀指定玩家(直播反水/礼物追杀玩法)。
 *
 * 与两个既有任务的区别:
 *  - FollowTask 只追踪不攻击;CombatTask 只杀"某类怪物 N 只"就结束、且不锁定特定玩家。
 *  - 本任务 = FollowTask 的追踪骨架 + CombatCore 的近战攻击,锁定**一个具体玩家**,永不自然结束
 *    (除非 stop/被打断,或目标离线)。这正是"你从现在开始一直追杀我"想要的效果。
 *
 * 安全:目标只能是**主人本人**(节目效果,主人已同意)。攻击是否真的掉血由服务端 pvp 规则决定——
 * pvp=false 时挥砍不掉血(原版行为),不需要代码额外拦。目标离线/跨维度 → 原地等,不 fail。
 *
 * 这是长期意图任务(isWaiting 参与调度),但不像 follow 那样在短任务后自动 resume——
 * 追杀是主动指令,由 LongRunningIntentManager 之外的显式 stop 结束。
 */
public final class ChaseAttackTask extends AbstractTask {
    private static final double APPROACH_START = 3.2D;  // 超过这个距离就寻路逼近(略大于攻击距离,避免抖动)
    private static final int REPATH_TICKS = 20;         // 追人比 follow 更频繁重规划,目标在跑
    private static final double PROGRESS_EPS = 0.4D;     // 一个重规划周期内至少靠近这么多才算"在推进"
    private static final int UNREACHABLE_LIMIT = 80;     // 连续 4s 逼近但没靠近 → 判定够不到,转待命喊话
    private final UUID targetUuid;
    private final String targetLabel;
    private int nextRepathTick;
    private boolean announcedOffline;
    private double lastDistToTarget = Double.MAX_VALUE;
    private int noApproachTicks;   // 连续"想追却没靠近"的 tick 数
    private boolean waiting;       // true → StuckWatcher 豁免本任务(追不到时不被判 stuck 中止)

    public ChaseAttackTask(UUID targetUuid, String targetLabel) {
        this.targetUuid = targetUuid;
        this.targetLabel = targetLabel == null || targetLabel.isBlank() ? "目标" : targetLabel;
    }

    @Override
    public String name() {
        return "chase_attack";
    }

    @Override
    public String describe() {
        return "Chasing and attacking " + targetLabel + (waiting ? " (unreachable, taunting)" : "");
    }

    @Override
    public double progress() {
        return 0.5D; // 无终点的持续任务,恒定 0.5 表示"进行中"
    }

    @Override
    public boolean isWaiting() {
        // 追不到(目标离线 / 爬不上去)时返回 true:StuckWatcher 会跳过本任务,不再 200 tick 判 stuck 中止。
        return waiting;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        CombatCore.equipMelee(bot);
        EquipAction.equipShieldOffhand(bot);
        nextRepathTick = 0;
        announcedOffline = false;
        lastDistToTarget = Double.MAX_VALUE;
        noApproachTicks = 0;
        waiting = false;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        ServerPlayerEntity target = target(bot).orElse(null);
        if (target == null || target.getServerWorld() != bot.getServerWorld() || !target.isAlive()) {
            bot.getActionPack().stopAll();
            waiting = true; // 目标不在 → 待命(豁免 stuck),不 fail,等目标回来继续追
            if (!announcedOffline && elapsed % 100 == 1) {
                announcedOffline = true;
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot",
                        targetLabel + "，你给我出来！");
            }
            return;
        }
        announcedOffline = false;
        CombatCore.lookAt(bot, target);

        double distance = bot.distanceTo(target);
        if (CombatCore.inMeleeRange(bot, target)) {
            // 到手就砍:攻击冷却好了才挥,避免空挥掉伤害。砍完停下脚步这一拍,下拍重新判距。
            bot.getActionPack().stopMovement();
            CombatCore.strikeIfReady(bot, target);
            waiting = false;
            noApproachTicks = 0;
            lastDistToTarget = distance;
            return;
        }

        // 够不到就追。优先走 A* 寻路(startPathTo → 背包有方块时会 PILLAR_UP 垫方块爬上高处的你),
        // 只有寻路彻底失败(idle 且不在冷却)才短暂直线兜底。绝不像旧版那样一 idle 就直线——直线不搭方块。
        BlockPos targetPos = target.getBlockPos();
        BlockPos activeGoal = bot.getActionPack().activePathGoal();
        boolean targetMoved = activeGoal == null || activeGoal.getSquaredDistance(targetPos) > 4.0D;
        if (elapsed >= nextRepathTick && (bot.getActionPack().isPathExecutorIdle() || targetMoved)) {
            var result = bot.getActionPack().startPathTo(target.getBlockPos());
            nextRepathTick = elapsed + REPATH_TICKS;
            if (!result.isFailed()) {
                waiting = false;
                noApproachTicks = 0;
            }
        }

        // 追不上判定:每个重规划周期检查是否真的在靠近目标。连续 UNREACHABLE_LIMIT tick 没靠近
        // → 判定"够不到"(你站在它爬不上的地方),转待命 + 隔一会儿喊一句,不再原地卡死被 stuck 中止。
        if (elapsed % REPATH_TICKS == 0) {
            if (!bot.getActionPack().isPathExecutorIdle() || distance < lastDistToTarget - PROGRESS_EPS) {
                noApproachTicks = 0;
                waiting = false;
            } else {
                noApproachTicks += REPATH_TICKS;
            }
            lastDistToTarget = distance;
        }
        if (noApproachTicks >= UNREACHABLE_LIMIT) {
            if (!waiting) {
                waiting = true; // 首次转待命
            }
            if (elapsed % 60 == 0) {
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", "你给我下来！躲上去算什么本事！");
            }
        }
    }

    private Optional<ServerPlayerEntity> target(AIPlayerEntity bot) {
        return Optional.ofNullable(bot.getServer().getPlayerManager().getPlayer(targetUuid));
    }

    /** 便捷构造:锁定 bot 的主人。无主返回 empty(工具层据此拒绝)。 */
    public static Optional<ChaseAttackTask> ownerTarget(AIPlayerEntity bot) {
        return AIPlayerManager.INSTANCE.ownerOf(bot).map(uuid -> {
            ServerPlayerEntity owner = bot.getServer().getPlayerManager().getPlayer(uuid);
            String label = owner != null ? owner.getGameProfile().getName() : "主人";
            return new ChaseAttackTask(uuid, label);
        });
    }
}
