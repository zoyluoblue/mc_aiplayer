package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
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

    private CombatCore() {
    }

    public static void equipMelee(AIPlayerEntity bot) {
        EquipAction.equipBestArmor(bot);
        EquipAction.equipBestWeapon(bot);
    }

    public static Optional<LivingEntity> nearestTarget(AIPlayerEntity bot, EntityType<?> targetType, double range) {
        return bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(range),
                        entity -> entity.isAlive() && entity.getType().equals(targetType) && entity != bot)
                .stream()
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    public static Optional<LivingEntity> nearestHostileAround(AIPlayerEntity bot, BlockPos center, double range) {
        Box box = new Box(center).expand(range);
        return bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, box,
                        entity -> entity instanceof HostileEntity && entity.isAlive() && entity != bot)
                .stream()
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    public static boolean inMeleeRange(AIPlayerEntity bot, LivingEntity target) {
        return bot.distanceTo(target) <= ATTACK_RANGE && hasLineOfSight(bot, target);
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
        bot.getActionPack().startPathTo(target.getBlockPos());
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
