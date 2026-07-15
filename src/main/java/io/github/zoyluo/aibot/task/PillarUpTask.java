package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.OptionalInt;

/**
 * 垫块上高(俗称搭柱子/nerd-pole):原地起跳,在腾空瞬间往脚下补一块,循环直到升高 height 格。
 * 真人玩家最高频的动作之一("上来/上树/上墙/垫上去看看")——之前 bot 完全做不了竖直方向,
 * bridge 只管水平。头顶有实心方块会提前收工(到顶),不挖不拆。
 */
public final class PillarUpTask extends AbstractTask {
    private static final int MAX_ELAPSED = 1200;      // ~60s 硬超时
    private static final int NO_PROGRESS_LIMIT = 100; // 5s 没升高一格 → 卡死认输
    private static final int PLACE_FAIL_LIMIT = 12;

    private final int height;
    private int baseY;
    private int lastRisen;
    private int lastProgressElapsed;
    private int placeRetry;

    public PillarUpTask(int height) {
        this.height = Math.max(1, Math.min(32, height));
    }

    @Override
    public String name() {
        return "pillar_up";
    }

    @Override
    public String describe() {
        return "PillarUp " + lastRisen + "/" + height;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, lastRisen / (double) height);
    }

    @Override
    public boolean isWaiting() {
        // 原地跳+放块,水平位置不变属正常;卡死由 NO_PROGRESS_LIMIT 自兜,别让 StuckWatcher 误救。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        baseY = bot.getBlockY();
        lastRisen = 0;
        lastProgressElapsed = 0;
        placeRetry = 0;
        bot.getActionPack().stopMovement();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            finishOrFail("pillar_timeout");
            return;
        }
        // 只认"落地站稳"的高度:跳跃峰值 blockY 会瞬时够到目标层但脚下块还没放,
        // 若在腾空时判完成会少垫最后一格还假报成功(实为差一格)。
        int risen = bot.getBlockY() - baseY;
        if (bot.isOnGround() && risen > lastRisen) {
            lastRisen = risen;
            lastProgressElapsed = elapsed;
        }
        if (bot.isOnGround() && risen >= height) {
            reset(bot);
            complete();
            return;
        }
        if (elapsed - lastProgressElapsed > NO_PROGRESS_LIMIT) {
            finishOrFail("pillar_stuck");
            return;
        }

        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        // 头顶两格内有实心 → 到顶了,best-effort 收工(不挖天花板)。
        if (!world.getBlockState(feet.up(2)).isReplaceable()) {
            finishOrFail("ceiling");
            return;
        }

        // 视线朝下(垫块的标准姿势),防其他残留视角干扰放块朝向。
        LookAction.setYawPitch(bot, bot.getYaw(), 90.0F);

        if (bot.isOnGround()) {
            OptionalInt slot = BridgeTask.fillerSlot(bot);
            if (slot.isEmpty()) {
                finishOrFail("pillar_no_blocks");
                return;
            }
            InventoryAction.equipFromSlot(bot, slot.getAsInt());
            bot.getActionPack().jumpOnce();
            return;
        }
        // 腾空:脚下那格(刚离开的格)是空的就补一块,落下正好踩上。
        BlockPos below = feet.down();
        if (world.getBlockState(below).isReplaceable()) {
            ActionResult result = BuildAction.placeBlockAt(bot, below);
            if (result.isSuccess()) {
                placeRetry = 0;
            } else {
                placeRetry++;
                if (placeRetry > PLACE_FAIL_LIMIT) {
                    finishOrFail("pillar_place_failed: " + result.reason());
                }
            }
        }
    }

    private void finishOrFail(String reason) {
        if (lastRisen > 0) {
            complete(); // 已经升上去一截就算数,别把半成果报成失败
        } else {
            fail(reason);
        }
    }

    private void reset(AIPlayerEntity bot) {
        LookAction.setYawPitch(bot, bot.getYaw(), 0.0F);
        bot.getActionPack().stopMovement();
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        reset(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        reset(bot);
        bot.getActionPack().stopAll();
    }
}
