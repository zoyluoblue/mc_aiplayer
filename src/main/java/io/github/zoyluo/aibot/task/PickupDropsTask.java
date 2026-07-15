package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;

/**
 * 捡地上的掉落物:磁吸身边的、走向远处的,直到 radius 内一件不剩。
 * 真人高频动作("把地上的东西捡起来/别浪费快捡")——之前只有死亡回收 recover_drops,
 * 平时打怪/炸树留下的散落物没有对应工具。复用 HarvestCore.chaseDropAnyOf(null=任意物品)。
 */
public final class PickupDropsTask extends AbstractTask {
    private static final int MAX_ELAPSED = 1200; // ~60s

    private final double radius;
    private int baseline;

    public PickupDropsTask(int radius) {
        this.radius = Math.max(4, Math.min(32, radius));
    }

    @Override
    public String name() {
        return "pickup_items";
    }

    @Override
    public String describe() {
        return "Picking up drops r=" + (int) radius;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.9D, elapsed / (double) MAX_ELAPSED);
    }

    @Override
    public boolean isWaiting() {
        return true; // 追物途中可能短暂站桩(等磁吸),卡死由超时自兜
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        baseline = HarvestCore.totalInventoryCount(bot);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (HarvestCore.nearestDropAnyOf(bot, null, radius).isEmpty()) {
            bot.getActionPack().stopMovement();
            complete(); // 捡干净了(或本来就没有)
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            bot.getActionPack().stopMovement();
            int picked = HarvestCore.totalInventoryCount(bot) - baseline;
            if (picked > 0) {
                complete(); // 超时但有收获=够不着的剩件不算失败
            } else {
                fail("pickup_timeout");
            }
            return;
        }
        HarvestCore.chaseDropAnyOf(bot, null, radius);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }
}
