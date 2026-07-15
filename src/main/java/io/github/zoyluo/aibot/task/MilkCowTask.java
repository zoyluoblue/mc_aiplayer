package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MilkCowAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.Items;

/**
 * 挤奶任务:找最近的成年牛、靠近、用空桶挤出 target 桶牛奶(MILK_BUCKET)。
 * best-effort:没空桶/周围没牛/久无进展时,已挤到 ≥1 桶就完成、一桶没挤到才失败(不阻断上层目标)。
 */
public final class MilkCowTask extends AbstractTask {
    private static final double SEARCH = 32.0D;
    private static final double MILK_RANGE = 3.5D;
    private static final int NO_PROGRESS_LIMIT = 600;

    private final int target;
    private int milked;
    private int lastProgressTick;
    private String note = "";

    public MilkCowTask(int target) {
        this.target = Math.max(1, target);
    }

    @Override
    public String name() {
        return "milk_cow";
    }

    @Override
    public String describe() {
        return "milk_cow " + milked + "/" + target + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, (double) milked / target);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        milked = 0;
        lastProgressTick = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (milked >= target) {
            complete();
            return;
        }
        if (InventoryAction.countItem(bot, Items.BUCKET) <= 0) {
            finishOrFail("missing_bucket");
            return;
        }
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            finishOrFail("milk_no_progress");
            return;
        }
        CowEntity cow = MilkCowAction.nearestCow(bot, SEARCH);
        if (cow == null) {
            finishOrFail("no_cow");
            return;
        }
        if (bot.getEyePos().distanceTo(cow.getEyePos()) <= MILK_RANGE) {
            bot.getActionPack().stopMovement();
            ActionResult result = MilkCowAction.milk(bot);
            if (result.isSuccess()) {
                milked++;
                lastProgressTick = elapsed;
            } else {
                note = result.reason();
            }
            return;
        }
        // 走向牛(牛会移动,持续重定向;A* 失败则退化为直线 walk)。
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(cow.getBlockPos());
        }
    }

    private void finishOrFail(String reason) {
        if (milked > 0) {
            complete();
        } else {
            fail(reason);
        }
    }
}
