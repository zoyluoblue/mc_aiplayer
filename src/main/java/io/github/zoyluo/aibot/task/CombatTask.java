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
    private final java.util.UUID leashOwner;   // 非 null = 追击牵引:离主人超过 leashRadius 就放弃当前怪、回主人身边
    private final double leashRadius;
    private Phase phase = Phase.ACQUIRE;
    private LivingEntity target;
    private int kills;
    private int repositionTicks;
    private int bowChargeTicks;
    private int blockTicks;
    private int healTicks;
    private boolean eating;
    private int lostSightTicks; // 目标连续无视线(被墙挡)的 tick 数
    private int returningTicks;  // 牵引触发后往主人身边走的 tick 数

    public CombatTask(EntityType<?> targetType, int targetKills, float retreatHpThreshold) {
        this(targetType, targetKills, retreatHpThreshold, null, 0.0D);
    }

    /** leashOwner 非 null 时启用追击牵引:bot 追怪离主人超过 leashRadius 就放弃、走回主人身边再结束。 */
    public CombatTask(EntityType<?> targetType, int targetKills, float retreatHpThreshold,
                      java.util.UUID leashOwner, double leashRadius) {
        this.targetType = targetType;
        this.targetKills = Math.max(1, targetKills);
        this.retreatHpThreshold = retreatHpThreshold;
        this.leashOwner = leashOwner;
        this.leashRadius = leashRadius;
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
    protected void onStart(AIPlayerEntity bot) {
        CombatCore.equipMelee(bot);
        EquipAction.equipShieldOffhand(bot);
        phase = Phase.ACQUIRE;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) {
            fail("combat_timeout");
            return;
        }
        // 追击牵引:直播里 bot 追怪跑出画面是大忌(主人被围、观众看不到)。离主人超过 leashRadius →
        // 立刻放弃当前怪,走回主人身边,到位(或 5s 兜底)后 complete 干净收场,不 fail 惊动大脑。
        if (leashTick(bot)) {
            return;
        }
        // 失去视线不等于目标不可达。继续进入接近阶段，让统一导航绕行、挖开遮挡或自动施工。
        if (target != null && target.isAlive() && !CombatCore.hasLineOfSight(bot, target)) {
            lostSightTicks++;
            if (phase != Phase.APPROACH) {
                bot.stopUsingItem();
                phase = Phase.APPROACH;
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
        if (target != null && target.isAlive()) {
            Vec3d away = bot.getPos().subtract(target.getPos());
            if (away.lengthSquared() < 0.01D) {
                away = new Vec3d(1.0D, 0.0D, 0.0D);
            }
            Vec3d retreatTo = bot.getPos().add(away.normalize().multiply(8.0D));
            bot.getActionPack().startSmartWalkTo(retreatTo);
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
        if (!eating && InventoryAction.findFoodSlot(bot) >= 0) {
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

    /**
     * 追击牵引一拍:返回 true 表示本 tick 已被牵引逻辑接管(调用方直接 return,不走战斗相位)。
     * 逻辑:未启用牵引 / 主人不在线 → 放行(false)。一旦离主人超过 leashRadius,进入"回撤"模式:
     * 抛弃当前目标,走回主人身边;回到 leashRadius*0.6 内(或回撤超 100 tick 兜底)即 complete。
     */
    private boolean leashTick(AIPlayerEntity bot) {
        if (leashOwner == null) {
            return false;
        }
        net.minecraft.server.network.ServerPlayerEntity owner =
                bot.getServer() == null ? null : bot.getServer().getPlayerManager().getPlayer(leashOwner);
        if (owner == null || owner.getServerWorld() != bot.getServerWorld()) {
            return false; // 主人离线或不同世界:不牵引(避免站着不动),按普通战斗跑
        }
        double distToOwner = bot.distanceTo(owner);
        if (returningTicks == 0 && distToOwner <= leashRadius) {
            return false; // 还在绳长内:正常战斗
        }
        // 已触发回撤(returningTicks>0)或刚越界:走回主人身边
        returningTicks++;
        target = null;
        double comeBackWithin = leashRadius * 0.6D;
        if (distToOwner <= comeBackWithin || returningTicks > 100) {
            bot.getActionPack().stopMovement();
            complete(); // 已经回到主人身边(或兜底超时):干净结束,让 follow/guard 等背景任务 resume
            return true;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(owner.getBlockPos());
        }
        return true;
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
                && CombatCore.hasLineOfSight(bot, target)
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
