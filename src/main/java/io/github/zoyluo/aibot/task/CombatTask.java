package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.EatAction;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

public final class CombatTask extends AbstractTask {
    private enum Phase {
        ACQUIRE,
        APPROACH,
        RANGED,
        STRIKE,
        BLOCK,
        REPOSITION,
        RETREAT,
        HEAL
    }

    private static final int SEARCH_RANGE = 20;
    private static final float RANGED_MIN_DISTANCE = 7.0F;
    private static final int BOW_CHARGE_TICKS = 20;
    private static final int BLOCK_TICKS = 12;
    private static final int HEAL_WAIT_TICKS = 200;
    private static final int LOST_SIGHT_LIMIT = 50; // 目标被墙挡住(无视线)持续 2.5s → 结束战斗,不傻打到 timeout

    private final EntityType<?> targetType;
    private final int targetKills;
    private final float retreatHpThreshold;
    private Phase phase = Phase.ACQUIRE;
    private LivingEntity target;
    private int kills;
    private int repositionTicks;
    private int bowChargeTicks;
    private int blockTicks;
    private int healTicks;
    private boolean eating;
    private int lostSightTicks; // 目标连续无视线(被墙挡)的 tick 数
    private int noFoodWaitTicks;
    private boolean noFoodSeen;
    private int noFoodCycleCount;

    public CombatTask(EntityType<?> targetType, int targetKills, float retreatHpThreshold) {
        this.targetType = targetType;
        this.targetKills = Math.max(1, targetKills);
        this.retreatHpThreshold = retreatHpThreshold;
    }

    @Override
    public String name() {
        return "combat";
    }

    @Override
    public String describe() {
        return "Attacking " + Registries.ENTITY_TYPE.getId(targetType) + " " + kills + "/" + targetKills + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) kills / targetKills);
    }

    @Override
    public boolean isWaiting() {
        return phase == Phase.RANGED || phase == Phase.BLOCK || (phase == Phase.HEAL && eating);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        CombatCore.equipMelee(bot);
        EquipAction.equipBestOffhand(bot);
        phase = Phase.ACQUIRE;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) {
            fail("combat_timeout");
            return;
        }
        // 目标被方块挡住够不到(隔墙/隔隧道)→ 结束战斗,别傻打/空追到 timeout(实测 bug:被阻隔的怪
        // 让 bot 一直"正在战斗"、中断正常挖矿)。瞬间遮挡不算,持续无视线 2.5s 才收手;够不到即安全,
        // 用 complete 干净结束让原任务 resume,不 fail 惊动大脑。
        if (target != null && target.isAlive() && !CombatCore.hasLineOfSight(bot, target)) {
            if (++lostSightTicks > LOST_SIGHT_LIMIT) {
                lostSightTicks = 0;
                complete();
                return;
            }
        } else {
            lostSightTicks = 0;
        }
        if (bot.getHealth() <= retreatHpThreshold && phase != Phase.RETREAT && phase != Phase.HEAL) {
            phase = Phase.RETREAT;
        }
        switch (phase) {
            case ACQUIRE -> acquire(bot);
            case APPROACH -> approach(bot);
            case RANGED -> ranged(bot);
            case STRIKE -> strike(bot);
            case BLOCK -> block(bot);
            case REPOSITION -> reposition(bot);
            case RETREAT -> retreat(bot);
            case HEAL -> heal(bot);
        }
    }

    private void acquire(AIPlayerEntity bot) {
        target = CombatCore.nearestTarget(bot, targetType, SEARCH_RANGE).orElse(null);
        if (target == null) {
            if (kills > 0) {
                complete();
            } else {
                fail("no_target_in_range");
            }
            return;
        }
        chooseEngagement(bot);
    }

    private void approach(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            kills++;
            finishOrAcquire();
            return;
        }
        CombatCore.lookAt(bot, target);
        if (shouldUseBow(bot)) {
            beginRanged(bot);
            return;
        }
        if (CombatCore.inMeleeRange(bot, target)) {
            bot.getActionPack().stopAll();
            phase = Phase.STRIKE;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            startApproach(bot);
        }
    }

    private void ranged(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            kills++;
            finishOrAcquire();
            return;
        }
        if (!shouldUseBow(bot)) {
            bot.stopUsingItem();
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        CombatCore.lookAt(bot, target);
        if (!bot.isUsingItem()) {
            ActionResult result = InteractAction.useItemInAir(bot, Hand.MAIN_HAND);
            if (result.isFailed()) {
                phase = Phase.APPROACH;
                startApproach(bot);
                return;
            }
        }
        bowChargeTicks++;
        if (bowChargeTicks >= BOW_CHARGE_TICKS) {
            bot.stopUsingItem();
            bowChargeTicks = 0;
            phase = Phase.APPROACH;
        }
    }

    private void strike(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            kills++;
            finishOrAcquire();
            return;
        }
        CombatCore.lookAt(bot, target);
        if (shouldBlock(bot)) {
            beginBlock(bot);
            return;
        }
        if (bot.distanceTo(target) > CombatCore.ATTACK_RANGE + 0.75F) {
            phase = Phase.APPROACH;
            startApproach(bot);
            return;
        }
        if (CombatCore.strikeIfReady(bot, target)) {
            repositionTicks = 8;
            phase = Phase.REPOSITION;
        }
    }

    private void block(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            bot.stopUsingItem();
            kills++;
            finishOrAcquire();
            return;
        }
        CombatCore.lookAt(bot, target);
        if (!bot.isUsingItem()) {
            InteractAction.useItemInAir(bot, Hand.OFF_HAND);
        }
        blockTicks--;
        if (blockTicks <= 0 || bot.distanceTo(target) > CombatCore.ATTACK_RANGE + 1.5F) {
            bot.stopUsingItem();
            phase = Phase.STRIKE;
        }
    }

    private void reposition(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            bot.getActionPack().stopMovement();
            kills++;
            finishOrAcquire();
            return;
        }
        CombatCore.lookAt(bot, target);
        bot.getActionPack().setStrafing(elapsed % 40 < 20 ? 0.45F : -0.45F);
        repositionTicks--;
        if (repositionTicks <= 0) {
            bot.getActionPack().stopMovement();
            phase = Phase.STRIKE;
        }
    }

    private void retreat(AIPlayerEntity bot) {
        if (noFoodSeen) {
            noFoodWaitTicks++;
            if (noFoodWaitTicks < 20) {
                bot.getActionPack().stopMovement();
                return;
            }
            noFoodWaitTicks = 0;
            noFoodCycleCount++;
            if (noFoodCycleCount >= 3) {
                fail("no_food_for_heal");
                return;
            }
            noFoodSeen = false;
        } else {
            noFoodCycleCount = 0;
        }
        if (target != null && target.isAlive()) {
            // BUGFIX: не переключаться в HEAL пока не отойдём на 6+ блоков
            double dist = bot.distanceTo(target);
            if (dist < 6.0D) {
                Vec3d away = bot.getPos().subtract(target.getPos());
                if (away.lengthSquared() < 0.01D) {
                    away = new Vec3d(1.0D, 0.0D, 0.0D);
                }
                Vec3d retreatTo = bot.getPos().add(away.normalize().multiply(12.0D));
                bot.getActionPack().startWalkTo(retreatTo);
                return; // остаёмся в RETREAT пока не отойдём
            }
        } else {
            bot.getActionPack().stopMovement();
        }
        healTicks = 0;
        eating = false;
        phase = Phase.HEAL;
    }

    private void heal(AIPlayerEntity bot) {
        healTicks++;
        if (bot.getHealth() > retreatHpThreshold + 4.0F) {
            bot.getActionPack().stopAll();
            eating = false;
            phase = Phase.ACQUIRE;
            return;
        }
        if (!eating && InventoryAction.findFoodSlot(bot) < 0) {
            noFoodSeen = true;
            bot.getActionPack().stopMovement();
            phase = Phase.RETREAT;
            return;
        }
        if (!eating) {
            bot.getActionPack().stopMovement();
            ActionResult result = EatAction.startEating(bot);
            eating = !result.isFailed();
            return;
        }
        if (eating && !bot.isUsingItem() && healTicks > 20) {
            eating = false;
        }
        if (healTicks > HEAL_WAIT_TICKS) {
            bot.getActionPack().stopAll();
            phase = bot.getHealth() > retreatHpThreshold ? Phase.ACQUIRE : Phase.RETREAT;
        }
    }

    private void startApproach(AIPlayerEntity bot) {
        CombatCore.startApproach(bot, target);
    }

    private void chooseEngagement(AIPlayerEntity bot) {
        if (shouldUseBow(bot)) {
            beginRanged(bot);
            return;
        }
        phase = Phase.APPROACH;
        startApproach(bot);
    }

    private boolean shouldUseBow(AIPlayerEntity bot) {
        return target != null
                && target.isAlive()
                && bot.distanceTo(target) > RANGED_MIN_DISTANCE
                && EquipAction.bestRangedSlot(bot).isPresent();
    }

    private void beginRanged(AIPlayerEntity bot) {
        EquipAction.bestRangedSlot(bot).ifPresent(slot -> InventoryAction.equipFromSlot(bot, slot));
        bot.getActionPack().stopMovement();
        bowChargeTicks = 0;
        phase = Phase.RANGED;
    }

    private boolean shouldBlock(AIPlayerEntity bot) {
        return bot.getOffHandStack().isOf(Items.SHIELD)
                && target != null
                && target.isAlive()
                && bot.distanceTo(target) <= CombatCore.ATTACK_RANGE + 1.0F
                && bot.getHealth() <= retreatHpThreshold + 6.0F;
    }

    private void beginBlock(AIPlayerEntity bot) {
        blockTicks = BLOCK_TICKS;
        bot.getActionPack().stopMovement();
        phase = Phase.BLOCK;
    }

    private void finishOrAcquire() {
        target = null;
        if (kills >= targetKills) {
            complete();
        } else {
            phase = Phase.ACQUIRE;
        }
    }
}
