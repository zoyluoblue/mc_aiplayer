package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import java.util.Comparator;

/**
 * 射箭:朝最近的指定类型实体拉满弓射 N 箭(表演/挑衅/远程点名,不保证射死)。
 * 复用 CombatTask 已验证的弓三件套:EquipAction.bestRangedSlot 装弓(要有箭)、
 * useItemInAir 开始蓄力、20t 后 stopUsingItem 释放;瞄准按距离做简易抛物线抬枪。
 * 要杀怪用 attack(它自己会在合适距离切弓)。
 */
public final class ShootBowTask extends AbstractTask {
    private static final int MAX_ELAPSED = 600;   // ~30s
    private static final int CHARGE_TICKS = 20;   // 满蓄力
    private static final double MAX_RANGE = 30.0D;
    private static final double SEARCH = 40.0D;

    private final Identifier targetType;
    private final int shotsWanted;
    private int shots;
    private int chargeTicks;

    public ShootBowTask(String entityType, int shots) {
        this.targetType = Identifier.of(entityType);
        this.shotsWanted = Math.max(1, Math.min(5, shots));
    }

    @Override
    public String name() {
        return "shoot_bow";
    }

    @Override
    public String describe() {
        return "shoot_bow " + targetType.getPath() + " " + shots + "/" + shotsWanted;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, (double) shots / shotsWanted);
    }

    @Override
    public boolean isWaiting() {
        return true; // 站定蓄力属正常
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        shots = 0;
        chargeTicks = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (shots >= shotsWanted) {
            bot.stopUsingItem();
            complete();
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            finishOrFail(bot, "shoot_timeout");
            return;
        }
        var slot = EquipAction.bestRangedSlot(bot);
        if (slot.isEmpty()) {
            finishOrFail(bot, "need_bow_and_arrow: 要弓(bow)和箭(arrow)才能射");
            return;
        }
        LivingEntity target = nearestTarget(bot);
        if (target == null) {
            finishOrFail(bot, "no_target_nearby: " + targetType);
            return;
        }
        // 太远先走近(弓有效射程有限,太远射也是空放)
        if (bot.distanceTo(target) > MAX_RANGE) {
            bot.stopUsingItem();
            chargeTicks = 0;
            if (bot.getActionPack().isPathExecutorIdle()) {
                bot.getActionPack().startPathTo(target.getBlockPos());
            }
            return;
        }
        bot.getActionPack().stopMovement();
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        // 简易弹道抬枪:满蓄力箭有重力,按距离抬高瞄点(30 格约抬 2.7 格);直线瞄远距必偏低
        double dist = bot.distanceTo(target);
        LookAction.lookAt(bot, target.getEyePos().add(0.0D, dist * 0.09D, 0.0D));
        if (!bot.isUsingItem()) {
            ActionResult result = InteractAction.useItemInAir(bot, Hand.MAIN_HAND);
            if (result.isFailed()) {
                finishOrFail(bot, "draw_bow_failed: " + result.reason());
                return;
            }
            chargeTicks = 0;
            return;
        }
        chargeTicks++;
        if (chargeTicks >= CHARGE_TICKS) {
            bot.stopUsingItem(); // 满蓄力释放,箭出膛
            chargeTicks = 0;
            shots++;
        }
    }

    private LivingEntity nearestTarget(AIPlayerEntity bot) {
        return bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(SEARCH),
                        entity -> entity.isAlive() && entity != bot
                                && Registries.ENTITY_TYPE.getId(entity.getType()).equals(targetType)
                                && bot.canSee(entity))
                .stream()
                .min(Comparator.comparingDouble(bot::distanceTo))
                .orElse(null);
    }

    /** 收口:任何退出路径都先松弓——complete()/fail() 不会触发 onAbort,漏松会永久卡拉弓姿势。 */
    private void finishOrFail(AIPlayerEntity bot, String reason) {
        bot.stopUsingItem();
        if (shots > 0) {
            complete();
        } else {
            fail(reason);
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        bot.stopUsingItem();
        bot.getActionPack().stopAll(); // stopMovement 清不掉 pathExecutor,被抢占时会被旧路径拖着走
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.stopUsingItem();
        bot.getActionPack().stopAll();
    }
}
