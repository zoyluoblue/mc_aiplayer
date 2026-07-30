package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Comparator;
import java.util.Optional;

public final class CombatCore {
    public static final float ATTACK_RANGE = 3.0F;
    private static final double CLOSE_HOSTILE_PRESSURE_RANGE = 10.0D;
    /** Creepers can erase a zero-death mission before ordinary melee pressure is re-entered. */
    private static final double CREEPER_PRESSURE_RANGE = 16.0D;
    private static final double RANGED_HOSTILE_PRESSURE_RANGE = 20.0D;

    private CombatCore() {
    }

    public static void equipMelee(AIPlayerEntity bot) {
        EquipAction.equipBestArmor(bot);
        ensureMeleeWeapon(bot);
    }

    /**
     * Re-evaluates the complete inventory at an attack boundary. This is intentionally above the
     * low-level interaction primitive: a melee hit may consume the held weapon's final durability,
     * and the combat owner must atomically select its physical successor before another tick can
     * continue empty-handed.
     */
    public static void ensureMeleeWeapon(AIPlayerEntity bot) {
        EquipAction.equipBestWeapon(bot);
    }

    /** Shared combat policy: projectile-capable mobs keep pressure while line of sight remains. */
    static boolean isRangedThreat(LivingEntity entity) {
        return entity instanceof RangedAttackMob;
    }

    static double hostilePressureScanRange() {
        return RANGED_HOSTILE_PRESSURE_RANGE;
    }

    /**
     * A close hostile remains pressure even while rounding a corner. Beyond that close envelope,
     * only an observable ranged attacker with factual line of sight can still deal damage while
     * the bot is eating or settling another combat target.
     */
    static boolean isWithinHostilePressureEnvelope(AIPlayerEntity bot, LivingEntity entity) {
        double distanceSquared = bot.squaredDistanceTo(entity);
        if (distanceSquared
                <= CLOSE_HOSTILE_PRESSURE_RANGE * CLOSE_HOSTILE_PRESSURE_RANGE) {
            return true;
        }
        if (entity instanceof CreeperEntity
                && distanceSquared <= CREEPER_PRESSURE_RANGE * CREEPER_PRESSURE_RANGE) {
            return true;
        }
        return isRangedThreat(entity)
                && distanceSquared
                <= RANGED_HOSTILE_PRESSURE_RANGE * RANGED_HOSTILE_PRESSURE_RANGE
                && hasLineOfSight(bot, entity);
    }

    /**
     * Shared combat policy for mobs that need a dedicated tactic instead of generic melee.
     * Creepers require explosive spacing; Endermen require a low ceiling/water/leg trap strategy
     * that the ordinary approach/strike loop does not own.  Until those tactics exist, survival
     * safety must create distance rather than turn an interrupted mining mission into a duel.
     */
    static boolean isMeleeForbiddenThreat(LivingEntity entity) {
        return entity instanceof CreeperEntity || entity instanceof EndermanEntity;
    }

    public static Optional<LivingEntity> nearestTarget(AIPlayerEntity bot, EntityType<?> targetType, double range) {
        return bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(range),
                        entity -> entity.isAlive() && entity.getType().equals(targetType) && entity != bot)
                .stream()
                .filter(entity -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveEntity(bot, entity))
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    public static Optional<LivingEntity> nearestHostileAround(AIPlayerEntity bot, BlockPos center, double range) {
        Box box = new Box(center).expand(range);
        return bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, box,
                        entity -> entity instanceof HostileEntity && entity.isAlive() && entity != bot)
                .stream()
                .filter(entity -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveEntity(bot, entity))
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    public static boolean inMeleeRange(AIPlayerEntity bot, LivingEntity target) {
        return bot.distanceTo(target) <= ATTACK_RANGE;
    }

    // 视线/可达判定:bot 眼睛 → 目标眼睛之间做一次方块 raycast,中间被实心方块挡住(非 MISS)即视为
    // 够不到(隔墙/隔隧道)。raycast 只检测方块、不含实体,正好判断"有没有墙挡着"。被挡的怪近战打不到、
    // 远程射不到、苦力怕炸不到,不应触发/维持战斗(实测 bug:被方块阻隔的怪让 bot 一直"正在战斗")。
    public static boolean hasLineOfSight(AIPlayerEntity bot, LivingEntity mob) {
        HitResult hit = bot.getServerWorld().raycast(new RaycastContext(
                bot.getEyePos(), mob.getEyePos(),
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void lookAt(AIPlayerEntity bot, LivingEntity target) {
        Vec3d targetCenter = target.getPos().add(0.0D, target.getHeight() * 0.5D, 0.0D);
        LookAction.lookAt(bot, targetCenter);
    }

    public static void startApproach(AIPlayerEntity bot, LivingEntity target) {
        ActionResult result = bot.getActionPack().startPathTo(target.getBlockPos());
        if (result.isFailed()) {
            bot.getActionPack().startWalkTo(target.getPos());
        }
    }

    public static boolean strikeIfReady(AIPlayerEntity bot, LivingEntity target) {
        lookAt(bot, target);
        if (bot.getAttackCooldownProgress(0.5F) < 0.95F) {
            return false;
        }
        InteractAction.attackEntity(bot, target);
        return true;
    }
}
