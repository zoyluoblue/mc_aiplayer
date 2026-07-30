package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.EatAction;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.List;

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
    private static final double HEAL_SAFE_DISTANCE = CombatCore.ATTACK_RANGE + 2.0D;
    // Vanilla Creeper ignition only backs off beyond seven blocks (or after LOS is broken).
    private static final double CREEPER_HEAL_SAFE_DISTANCE = 8.0D;
    private static final int RETREAT_STEP_DISTANCE = 6;
    private static final int LOST_SIGHT_LIMIT = 50; // 目标被墙挡住(无视线)持续 2.5s → 结束战斗,不傻打到 timeout
    private static final int DEFENSIVE_MAX_VERTICAL_DROP = 2;
    private static final double DEFENSIVE_MAX_HORIZONTAL_DISTANCE = 8.0D;

    private final EntityType<?> targetType;
    private final int targetKills;
    private final float retreatHpThreshold;
    private final LivingEntity fixedDefensiveTarget;
    private final BlockPos defensiveAnchor;
    private Phase phase = Phase.ACQUIRE;
    private LivingEntity target;
    private int kills;
    private int repositionTicks;
    private int bowChargeTicks;
    private int blockTicks;
    private int healTicks;
    private boolean eating;
    /** Transient safety pressure; never owns primary kill credit. */
    private LivingEntity retreatThreat;
    private BlockPos retreatDestination;
    private int lostSightTicks; // 目标连续无视线(被墙挡)的 tick 数

    public CombatTask(EntityType<?> targetType, int targetKills, float retreatHpThreshold) {
        this(targetType, targetKills, retreatHpThreshold, null, null);
    }

    private CombatTask(EntityType<?> targetType,
                       int targetKills,
                       float retreatHpThreshold,
                       LivingEntity fixedDefensiveTarget,
                       BlockPos defensiveAnchor) {
        this.targetType = targetType;
        this.targetKills = Math.max(1, targetKills);
        this.retreatHpThreshold = retreatHpThreshold;
        this.fixedDefensiveTarget = fixedDefensiveTarget;
        this.defensiveAnchor = defensiveAnchor == null ? null : defensiveAnchor.toImmutable();
    }

    public static CombatTask defensive(LivingEntity threat,
                                       float retreatHpThreshold,
                                       BlockPos anchor) {
        if (threat == null || anchor == null) {
            throw new IllegalArgumentException("defensive_combat_requires_target_and_anchor");
        }
        return new CombatTask(threat.getType(), 1, retreatHpThreshold, threat, anchor);
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
        if (settleDeadPrimary(bot)) {
            if (state == TaskState.RUNNING && phase == Phase.RETREAT) {
                retreat(bot);
            }
            return;
        }
        if (!defensiveEngagementAllowed(bot)) {
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
        if (target != null && target.isAlive()
                && bot.getHealth() <= retreatHpThreshold
                && phase != Phase.RETREAT && phase != Phase.HEAL) {
            beginRetreat(bot);
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
        target = fixedDefensiveTarget != null
                ? fixedDefensiveTarget.isAlive() ? fixedDefensiveTarget : null
                : CombatCore.nearestTarget(bot, targetType, SEARCH_RANGE).orElse(null);
        if (target == null) {
            if (kills > 0) {
                complete();
            } else {
                fail("no_target_in_range");
            }
            return;
        }
        if (!defensiveEngagementAllowed(bot)) {
            return;
        }
        if (CombatCore.isMeleeForbiddenThreat(target)) {
            beginRetreat(bot);
            retreat(bot);
            return;
        }
        if (phase == Phase.RETREAT) {
            retreat(bot);
            return;
        }
        // A newly assigned defensive task has not populated target during the generic onTick
        // health gate. Do not spend its first physical tick approaching a contact hostile.
        if (bot.getHealth() <= retreatHpThreshold) {
            beginRetreat(bot);
            retreat(bot);
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
        // DangerWatcher never elects melee against a Creeper. Preserve that invariant even when a
        // manual combat request or a target transition reaches STRIKE through another path.
        if (CombatCore.isMeleeForbiddenThreat(target)) {
            beginRetreat(bot);
            retreat(bot);
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
        CombatCore.ensureMeleeWeapon(bot);
        if (CombatCore.strikeIfReady(bot, target)) {
            // The hit itself may have consumed the final point of durability. Equip the physical
            // successor in the same task tick instead of allowing logged empty-hand attacks.
            CombatCore.ensureMeleeWeapon(bot);
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
        LivingEntity threat = refreshRetreatThreat(bot);
        if (threat == null) {
            bot.getActionPack().stopAll();
            retreatThreat = null;
            retreatDestination = null;
            healTicks = 0;
            eating = false;
            if (kills >= targetKills) {
                complete();
            } else {
                phase = Phase.HEAL;
            }
            return;
        }

        double distance = bot.distanceTo(threat);
        if (!isImmediatePressure(bot, threat)) {
            bot.getActionPack().stopAll();
            retreatThreat = null;
            retreatDestination = null;
            healTicks = 0;
            eating = false;
            if (kills >= targetKills) {
                complete();
            } else {
                phase = Phase.HEAL;
            }
            return;
        }

        // A blocked tunnel must not turn retreat into passive death. Keep trying to open distance,
        // but counterattack whenever the pursuer remains in melee range.
        boolean meleeForbidden = CombatCore.isMeleeForbiddenThreat(threat);
        if (!meleeForbidden && distance <= CombatCore.ATTACK_RANGE + 0.25D) {
            CombatCore.lookAt(bot, threat);
            CombatCore.ensureMeleeWeapon(bot);
            if (CombatCore.strikeIfReady(bot, threat)) {
                CombatCore.ensureMeleeWeapon(bot);
                if (!threat.isAlive()) {
                    bot.getActionPack().stopAll();
                    retreatThreat = null;
                    retreatDestination = null;
                    return;
                }
            }
        }

        if (retreatDestination == null || bot.getActionPack().isPathExecutorIdle()) {
            retreatDestination = EvadeTask.admitBestSurfaceEscapePath(
                    bot, threat, threat.getBlockPos(), RETREAT_STEP_DISTANCE);
            if (retreatDestination == null) {
                fail("combat_no_valid_retreat_route");
            }
        }
    }

    private void beginRetreat(AIPlayerEntity bot) {
        bot.stopUsingItem();
        eating = false;
        healTicks = 0;
        retreatDestination = null;
        phase = Phase.RETREAT;
    }

    private void heal(AIPlayerEntity bot) {
        healTicks++;
        LivingEntity pressure = refreshRetreatThreat(bot);
        if (isImmediatePressure(bot, pressure)) {
            beginRetreat(bot);
            retreat(bot);
            return;
        }
        if (bot.getHealth() > retreatHpThreshold + 4.0F) {
            bot.getActionPack().stopAll();
            eating = false;
            phase = Phase.ACQUIRE;
            return;
        }
        if (!eating && InventoryAction.findFoodSlot(bot) >= 0) {
            bot.getActionPack().stopAll();
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
        if (!defensiveEngagementAllowed(bot) || phase == Phase.RETREAT) {
            return;
        }
        CombatCore.startApproach(bot, target);
    }

    private boolean defensiveEngagementAllowed(AIPlayerEntity bot) {
        if (defensiveAnchor == null) {
            return true;
        }
        BlockPos here = bot.getBlockPos();
        if (belowDefenseFloor(here) || outsideDefenseRadius(here)) {
            return disengageOrRetreatFromImmediatePressure(
                    bot, "bot_left_leash", here);
        }
        if (target != null && target.isAlive()) {
            BlockPos targetPos = target.getBlockPos();
            if (belowDefenseFloor(targetPos) || outsideDefenseRadius(targetPos)) {
                return disengageOrRetreatFromImmediatePressure(
                        bot, "target_left_leash", targetPos);
            }
        }
        int visibleHostiles = observableActiveHostiles(bot).size();
        if (visibleHostiles > AIBotConfig.get().combat().maxEnemiesToFight()) {
            return disengageOrRetreatFromImmediatePressure(
                    bot, "enemy_limit_exceeded", here);
        }
        return true;
    }

    private boolean disengageOrRetreatFromImmediatePressure(AIPlayerEntity bot,
                                                              String reason,
                                                              BlockPos observed) {
        LivingEntity pressure = refreshRetreatThreat(bot);
        if (isImmediatePressure(bot, pressure)) {
            if (phase != Phase.RETREAT) {
                beginRetreat(bot);
            }
            return true;
        }
        endDefensiveEngagement(bot, reason, observed);
        return false;
    }

    /**
     * Credits only the bound primary. A secondary pressure target may be counterattacked during
     * retreat, but its death never advances the requested kill quota.
     */
    private boolean settleDeadPrimary(AIPlayerEntity bot) {
        if (target == null || target.isAlive()) {
            return false;
        }
        LivingEntity defeated = target;
        target = null;
        if (retreatThreat == defeated) {
            retreatThreat = null;
            retreatDestination = null;
        }
        kills++;
        bot.stopUsingItem();
        eating = false;

        LivingEntity pressure = refreshRetreatThreat(bot);
        if (isImmediatePressure(bot, pressure)) {
            beginRetreat(bot);
            return true;
        }

        bot.getActionPack().stopAll();
        retreatThreat = null;
        retreatDestination = null;
        if (kills >= targetKills) {
            complete();
        } else {
            phase = Phase.ACQUIRE;
        }
        return true;
    }

    private LivingEntity refreshRetreatThreat(AIPlayerEntity bot) {
        LivingEntity next = nearestObservablePressure(bot);
        if (retreatThreat != next) {
            retreatThreat = next;
            retreatDestination = null;
        }
        return retreatThreat;
    }

    private LivingEntity nearestObservablePressure(AIPlayerEntity bot) {
        LivingEntity nearest = isObservablePressure(bot, target) ? target : null;
        double nearestDistance = nearest == null
                ? Double.POSITIVE_INFINITY : bot.squaredDistanceTo(nearest);
        for (LivingEntity hostile : observableActiveHostiles(bot)) {
            double distance = bot.squaredDistanceTo(hostile);
            if (distance < nearestDistance) {
                nearest = hostile;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private List<LivingEntity> observableActiveHostiles(AIPlayerEntity bot) {
        return bot.getServerWorld().getEntitiesByClass(
                LivingEntity.class,
                bot.getBoundingBox().expand(CombatCore.hostilePressureScanRange()),
                entity -> entity != bot
                        && DangerWatcher.isActiveHostileThreat(bot, entity)
                        && ObservableWorldQuery.canObserveEntity(bot, entity)
                        && CombatCore.isWithinHostilePressureEnvelope(bot, entity)
                        && CombatCore.hasLineOfSight(bot, entity));
    }

    private static boolean isObservablePressure(AIPlayerEntity bot, LivingEntity entity) {
        return entity != null
                && entity.isAlive()
                && ObservableWorldQuery.canObserveEntity(bot, entity)
                && CombatCore.hasLineOfSight(bot, entity);
    }

    private static boolean isImmediatePressure(AIPlayerEntity bot, LivingEntity entity) {
        if (!isObservablePressure(bot, entity)) {
            return false;
        }
        // A ranged mob can still deal damage while food occupies the main hand. Distance alone is
        // not a healing boundary; only losing factual LOS releases this pressure.
        if (CombatCore.isRangedThreat(entity)) {
            return true;
        }
        double safeDistance = CombatCore.isMeleeForbiddenThreat(entity)
                ? CREEPER_HEAL_SAFE_DISTANCE : HEAL_SAFE_DISTANCE;
        return bot.distanceTo(entity) < safeDistance;
    }

    private boolean belowDefenseFloor(BlockPos pos) {
        return pos.getY() < defensiveAnchor.getY() - DEFENSIVE_MAX_VERTICAL_DROP;
    }

    private boolean outsideDefenseRadius(BlockPos pos) {
        double dx = pos.getX() - defensiveAnchor.getX();
        double dz = pos.getZ() - defensiveAnchor.getZ();
        return dx * dx + dz * dz
                > DEFENSIVE_MAX_HORIZONTAL_DISTANCE * DEFENSIVE_MAX_HORIZONTAL_DISTANCE;
    }

    private void endDefensiveEngagement(AIPlayerEntity bot, String reason, BlockPos observed) {
        bot.getActionPack().stopAll();
        BotLog.danger(bot, "defensive_combat_disengaged",
                "reason", reason,
                "anchor", defensiveAnchor.toShortString(),
                "observed", observed.toShortString());
        complete();
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
        retreatThreat = null;
        retreatDestination = null;
        if (kills >= targetKills) {
            complete();
        } else {
            phase = Phase.ACQUIRE;
        }
    }
}
