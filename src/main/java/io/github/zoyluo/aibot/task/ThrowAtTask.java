package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.OptionalInt;

/**
 * 扔投掷物砸目标:雪球/鸡蛋(默认自动二选一)或显式指定物品,朝玩家或最近的某类实体扔 N 个。
 * 直播整活主力(拿雪球砸主人/砸僵尸挑衅),伤害≈0 纯节目效果。
 * 珍珠(会把 bot 传送过去)不自动选,只有显式 item=minecraft:ender_pearl 才用。
 */
public final class ThrowAtTask extends AbstractTask {
    private static final int MAX_ELAPSED = 600;   // ~30s
    private static final int THROW_INTERVAL = 8;
    private static final double MAX_RANGE = 16.0D;
    private static final double SEARCH = 32.0D;

    private final String playerName;   // 优先:指定玩家(空串=主人)
    private final String entityType;   // 次选:实体类型 path
    private final Item item;           // null=自动(雪球→鸡蛋)
    private final int throwsWanted;

    private int thrown;
    private int cooldown;

    public ThrowAtTask(String playerName, String entityType, Item item, int throwsWanted) {
        this.playerName = playerName;
        this.entityType = entityType == null ? "" : entityType.replace("minecraft:", "").trim();
        this.item = item;
        this.throwsWanted = Math.max(1, Math.min(16, throwsWanted));
    }

    @Override
    public String name() {
        return "throw_at";
    }

    @Override
    public String describe() {
        return "throw_at " + thrown + "/" + throwsWanted;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, (double) thrown / throwsWanted);
    }

    @Override
    public boolean isWaiting() {
        return true; // 站定瞄准扔属正常
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        thrown = 0;
        cooldown = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (thrown >= throwsWanted) {
            complete();
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            finishOrFail("throw_timeout");
            return;
        }
        Entity target = resolveTarget(bot);
        if (target == null) {
            finishOrFail("throw_no_target");
            return;
        }
        OptionalInt slot = throwableSlot(bot);
        if (slot.isEmpty()) {
            finishOrFail("no_throwable: 背包要有雪球 snowball/鸡蛋 egg(或显式指定的投掷物)");
            return;
        }
        double dist = bot.distanceTo(target);
        if (dist > MAX_RANGE) {
            if (bot.getActionPack().isPathExecutorIdle()) {
                bot.getActionPack().startPathTo(target.getBlockPos());
            }
            return;
        }
        bot.getActionPack().stopMovement();
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        // 简易弹道抬枪:投掷物初速 1.5、有重力,每格距离补 ~0.045 格高度。
        Vec3d aim = target.getEyePos().add(0.0D, dist * 0.045D, 0.0D);
        LookAction.lookAt(bot, aim);
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        ActionResult result = InteractAction.useItemInAir(bot, Hand.MAIN_HAND);
        if (result.isSuccess()) {
            thrown++;
            cooldown = THROW_INTERVAL;
        } else {
            cooldown = 4; // 偶发使用冷却,稍后重试
        }
    }

    private Entity resolveTarget(AIPlayerEntity bot) {
        if (entityType.isEmpty() || playerName != null) {
            String name = playerName;
            if (name == null || name.isBlank()) {
                ServerPlayerEntity owner = AIPlayerManager.INSTANCE.ownerOf(bot)
                        .map(uuid -> bot.getServer().getPlayerManager().getPlayer(uuid))
                        .orElse(null);
                return owner;
            }
            return bot.getServer().getPlayerManager().getPlayer(name.trim());
        }
        return bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(SEARCH),
                        entity -> entity.isAlive() && entity != bot
                                && Registries.ENTITY_TYPE.getId(entity.getType()).getPath().equals(entityType))
                .stream()
                .min(Comparator.comparingDouble(bot::distanceTo))
                .orElse(null);
    }

    private OptionalInt throwableSlot(AIPlayerEntity bot) {
        if (item != null) {
            return InventoryAction.findItem(bot, item);
        }
        OptionalInt snowball = InventoryAction.findItem(bot, Items.SNOWBALL);
        if (snowball.isPresent()) {
            return snowball;
        }
        return InventoryAction.findItem(bot, Items.EGG);
    }

    private void finishOrFail(String reason) {
        if (thrown > 0) {
            complete();
        } else {
            fail(reason);
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        bot.getActionPack().stopAll(); // stopMovement 清不掉 pathExecutor,被抢占时会被旧路径拖着走
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }
}
