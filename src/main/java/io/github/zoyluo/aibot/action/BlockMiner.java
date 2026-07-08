package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * BLOCKMINER:挖掘单个方块的正确共享原语,根治"每个任务各写一遍挖掘循环各错一遍"。
 *
 * 背景(实测#5/#8/#9 同类 bug):底层 {@code ActionPack.startMining(pos,face)} 每次都 new 一个
 * MiningController(语义是"重新开始挖",progress 归零)。多个任务各自手搓
 * {@code if(isMiningIdle()) startMining} + 进度判断,反复写错:有的每 tick/每 20 tick 重发
 * startMining 把进度清零导致永远破不了块;有的 face 算反;有的够不到也不放弃。
 *
 * 本类把"挖这一个块直到它破/超时/够不到"封装成一个**有状态、可轮询**的小机器:
 * - 只在挖掘空闲时发起一次 startMining,之后让 MiningController 自己累加进度,绝不中途重发清零;
 * - 自动算正确的 face(从 bot 眼睛指向方块);
 * - 目标已是空气 → DONE;块换了/够不到/超时 → FAILED;否则 MINING。
 *
 * 用法:每个挖矿任务持有一个 BlockMiner 字段,每 tick 调 {@link #tick}:
 * <pre>
 *   if (miner.target() == null || miner.isDone()) miner.begin(targetPos);
 *   switch (miner.tick(bot)) { case DONE -> ...; case FAILED -> ...; case MINING -> {} }
 * </pre>
 * 全程主线程(G2);不 assign(G1)。
 */
public final class BlockMiner {
    /** 单块挖掘超时(tick)。基岩级硬石头用钻石镐也远小于此;超时即判够不到/异常。 */
    private static final int MINE_TIMEOUT_TICKS = 200;

    public enum Status { IDLE, MINING, DONE, FAILED }

    private BlockPos target;
    private int sinceTick;
    private boolean started;
    private String failureReason = "";

    /** 开始挖一个新目标块(若与当前目标相同且在挖,则不打断、不清零进度)。 */
    public void begin(AIPlayerEntity bot, BlockPos pos) {
        if (pos == null) {
            io.github.zoyluo.aibot.log.BotLog.action(bot, "miner_begin_null",
                    "target", "null");
            return;
        }
        if (pos.equals(target) && started) {
            return; // 同一块继续挖,绝不重置(这正是 #9 卡死的根因)
        }
        // 切换目标:先停掉旧的挖掘,再锁定新目标。
        bot.getActionPack().stopMining();
        this.target = pos.toImmutable();
        this.sinceTick = 0;
        this.started = false;
        this.failureReason = "";
    }

    public BlockPos target() {
        return target;
    }

    public boolean isDone() {
        return target == null;
    }

    public String failureReason() {
        return failureReason;
    }

    /** 推进挖掘一 tick,返回状态。DONE/FAILED 后 target 置空,调用方应 begin 下一块。 */
    public Status tick(AIPlayerEntity bot) {
        if (target == null) {
            return Status.IDLE;
        }
        if (target.equals(BlockPos.ORIGIN)) {
            bot.getActionPack().stopMining();
            target = null;
            return Status.IDLE;
        }
        ServerWorld world = bot.getServerWorld();
        // 目标已破(空气/被替换为非目标由调用方判定;这里只认"已不可挖"=空气)。
        BlockState targetState = world.getBlockState(target);
        if (targetState.isAir()) {
            bot.getActionPack().stopMining();
            target = null;
            return Status.DONE;
        }
        // 流体不可"破坏"(挖击进度永不完成):调用方用 !isAir 判固体把水当成了可挖目标,
        // 每块干耗满 200t 超时再被拉黑(实测 miner_slow_dump block=water、13 连黑)。立即失败换块。
        if (!targetState.getFluidState().isEmpty()) {
            bot.getActionPack().stopMining();
            failureReason = "target_is_fluid";
            target = null;
            return Status.FAILED;
        }
        sinceTick++;
        if (sinceTick > MINE_TIMEOUT_TICKS) {
            bot.getActionPack().stopMining();
            failureReason = "mine_timeout";
            target = null;
            return Status.FAILED;
        }
        // 观测:挖了 5 秒还没破(石镐挖矿 ~2.5s 就该破)=挖击无效在循环。把关键状态打出来定位
        //(实测 ore_dig dist=3.9 锁定正挖却每块 200t 超时被拉黑 13 个,根因藏在这层之下)。
        if (sinceTick == 100) {
            double dist = Math.sqrt(bot.getEyePos().squaredDistanceTo(target.toCenterPos()));
            io.github.zoyluo.aibot.log.BotLog.action(bot, "miner_slow_dump",
                    "target", target.toShortString(),
                    "dist", String.format(java.util.Locale.ROOT, "%.1f", dist),
                    "mining_idle", bot.getActionPack().isMiningIdle(),
                    "started", started,
                    "block", world.getBlockState(target).getBlock().toString());
        }
        // 只在挖掘空闲(尚未对本块发起 / 上一块已结束)时发起一次;之后交给 MiningController 累加进度。
        // 绝不每 tick 重发 startMining —— 那会把 progress 清零,永远破不了块。
        if (bot.getActionPack().isMiningIdle()) {
            // 二次验证:刚结束的 MiningController 可能已设 idle 但世界方块尚未更新为空气,
            // 此时若不做 isAir 检查,会重新装备、创建 MiningController 挖空气浪费一次 tick。
            if (world.getBlockState(target).isAir()) {
                target = null;
                return Status.DONE;
            }
            BlockState equipTarget = world.getBlockState(target);
            ToolSelector.equipBestTool(bot, equipTarget);
            Direction face = faceToward(bot, target);
            MiningAction.startMining(bot, target, face);
            started = true;
        }
        return Status.MINING;
    }

    /** 放弃当前挖掘(任务暂停/中止时调用),不改变"该挖哪"的外部意图。 */
    public void cancel(AIPlayerEntity bot) {
        bot.getActionPack().stopMining();
        target = null;
        started = false;
        sinceTick = 0;
    }

    /** 从 bot 眼睛朝方块中心的方向,作为破坏面(取主轴)。正对方块即可,不必精确。 */
    private static Direction faceToward(AIPlayerEntity bot, BlockPos pos) {
        return Direction.getFacing(
                pos.getX() + 0.5 - bot.getEyePos().x,
                pos.getY() + 0.5 - bot.getEyePos().y,
                pos.getZ() + 0.5 - bot.getEyePos().z);
    }
}
