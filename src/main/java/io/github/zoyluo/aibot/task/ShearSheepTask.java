package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.Comparator;
import java.util.OptionalInt;

/**
 * 剪羊毛:拿剪刀走到最近的没剪过的成年羊旁边右键剪(原版 interactMob 掉羊毛),顺手捡走。
 * 要羊毛做床/旗帜的标准来源;需要背包里有剪刀(shears,2 铁锭 craft)。
 * best-effort:剪到 ≥1 只就算完成,一只没剪到才失败(MilkCowTask 同款语义)。
 */
public final class ShearSheepTask extends AbstractTask {
    private static final double SEARCH = 32.0D;
    private static final double SHEAR_RANGE = 3.0D;
    private static final int NO_PROGRESS_LIMIT = 600;

    private final int target;
    private int sheared;
    private int lastProgressTick;

    public ShearSheepTask(int target) {
        this.target = Math.max(1, target);
    }

    @Override
    public String name() {
        return "shear_sheep";
    }

    @Override
    public String describe() {
        return "shear_sheep " + sheared + "/" + target;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, (double) sheared / target);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        sheared = 0;
        lastProgressTick = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // 剪下来的羊毛在羊脚边,顺手磁吸
        HarvestCore.forcePickupNearbyAnyOf(bot, null, 4.0D, 2.0D);
        if (sheared >= target) {
            bot.getActionPack().stopMovement();
            complete();
            return;
        }
        if (InventoryAction.countItem(bot, Items.SHEARS) <= 0) {
            finishOrFail("need_shears: 没有剪刀,先 craft minecraft:shears(2 铁锭)");
            return;
        }
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            finishOrFail("shear_no_progress");
            return;
        }
        SheepEntity sheep = nearestShearable(bot);
        if (sheep == null) {
            finishOrFail("no_shearable_sheep");
            return;
        }
        if (bot.getEyePos().distanceTo(sheep.getEyePos()) <= SHEAR_RANGE) {
            bot.getActionPack().stopMovement();
            OptionalInt slot = InventoryAction.findItem(bot, Items.SHEARS);
            if (slot.isEmpty()) {
                finishOrFail("need_shears");
                return;
            }
            InventoryAction.equipFromSlot(bot, slot.getAsInt());
            LookAction.lookAt(bot, sheep.getEyePos());
            ActionResult result = InteractAction.useItemOnEntity(bot, sheep, Hand.MAIN_HAND);
            if (result.isSuccess() || sheep.isSheared()) {
                sheared++;
                lastProgressTick = elapsed;
                bot.swingHand(Hand.MAIN_HAND);
            }
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(sheep.getBlockPos());
        }
    }

    private static SheepEntity nearestShearable(AIPlayerEntity bot) {
        return bot.getServerWorld()
                .getEntitiesByClass(SheepEntity.class, bot.getBoundingBox().expand(SEARCH),
                        sheep -> sheep.isAlive() && !sheep.isBaby() && !sheep.isSheared())
                .stream()
                .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                .orElse(null);
    }

    private void finishOrFail(String reason) {
        if (sheared > 0) {
            complete();
        } else {
            fail(reason);
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }
}
