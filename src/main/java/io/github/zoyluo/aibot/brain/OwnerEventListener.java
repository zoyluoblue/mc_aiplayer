package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.task.ChaseAttackTask;
import io.github.zoyluo.aibot.task.GuardTask;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界事件 → bot 主动反应的第一块拼图:主人被打。
 * 此前全项目零事件钩子(DangerWatcher 只轮询 bot 自身危险),主人在旁边被骷髅射满地找牙,bot 毫无感知。
 *
 * 两条响应路(按优先级):
 *  1. bot 正在 guard → 确定性直报 GuardTask.noticeThreat(attacker),零 API 延迟秒反击。
 *  2. 追杀主人期间完全静默：这是主人明确开启的节目模式，不能被"救主人"事件反向取消。
 *  3. 其余情况(且 ownerEventPush 开)→ 节流 ≥30s/bot,在 !busy && !hasActivePlan 时喂大脑一条
 *     `system:event` 警报(与弹幕同铁律:绝不打断在途请求/确定性计划)。
 *
 * 另为快照提供 recentlyHurt/lastAttacker(5s 窗口)——PerceptionCollector.ownerInfo 读。
 * bot 自己反水打主人(节目效果)不算警报,否则一边砍一边喊救主人。
 */
public final class OwnerEventListener {
    public static final OwnerEventListener INSTANCE = new OwnerEventListener();

    private static final long RECENT_HURT_WINDOW_MS = 5_000L;
    private static final long BRAIN_PUSH_INTERVAL_MS = 30_000L;
    private static final double GUARD_NOTICE_RANGE = 32.0D;

    private final Map<UUID, HurtRecord> lastHurtByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPushByBot = new ConcurrentHashMap<>();

    private record HurtRecord(long atMs, String attacker) {
    }

    private OwnerEventListener() {
    }

    /** mod 初始化时注册一次(Fabric 事件是全局静态的)。 */
    public void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (entity instanceof ServerPlayerEntity victim && !(entity instanceof AIPlayerEntity)) {
                onPlayerDamaged(victim, source);
            }
        });
    }

    private void onPlayerDamaged(ServerPlayerEntity victim, DamageSource source) {
        Entity attacker = source.getAttacker();
        if (attacker instanceof AIPlayerEntity) {
            return; // 反水剧本:bot 打主人是节目,不触发警报
        }
        var bots = AIPlayerManager.INSTANCE.all().stream()
                .filter(bot -> AIPlayerManager.INSTANCE.ownerOf(bot)
                        .map(victim.getUuid()::equals)
                        .orElse(false))
                .toList();
        if (bots.isEmpty()) {
            return;
        }
        String attackerName = attacker instanceof LivingEntity living
                ? Registries.ENTITY_TYPE.getId(living.getType()).toString()
                : source.getName();
        lastHurtByOwner.put(victim.getUuid(), new HurtRecord(System.currentTimeMillis(), attackerName));
        for (AIPlayerEntity bot : bots) {
            if (bot.getServerWorld() != victim.getServerWorld()) {
                continue;
            }
            // chase_owner 是明确的主人意图。主人同时被别的怪打到时，旧逻辑会把
            // "去护卫主人"再喂给空闲的大脑，进而中断追杀；这条事件在追杀期间必须失效。
            if (TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof ChaseAttackTask) {
                continue;
            }
            if (attacker instanceof LivingEntity living && living.isAlive()
                    && bot.distanceTo(living) <= GUARD_NOTICE_RANGE
                    && TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof GuardTask guard) {
                guard.noticeThreat(bot, living);
                BotLog.danger(bot, "owner_hurt_guard_notified", "attacker", attackerName);
                continue;
            }
            maybePushBrain(bot, victim, attackerName);
        }
    }

    private void maybePushBrain(AIPlayerEntity bot, ServerPlayerEntity victim, String attackerName) {
        if (!AIBotConfig.get().brain().ownerEventPushEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastPushByBot.get(bot.getUuid());
        if (last != null && now - last < BRAIN_PUSH_INTERVAL_MS) {
            return;
        }
        // 与弹幕同铁律:busy(在途/续航间隙)或确定性计划进行中绝不喂——handleMessage 会抢占接管+清目标。
        if (BrainCoordinator.INSTANCE.status(bot).busy()
                || io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return;
        }
        lastPushByBot.put(bot.getUuid(), now);
        String text = "警报:你的主人 " + victim.getGameProfile().getName()
                + " 正被 " + attackerName + " 攻击(主人血量 " + Math.round(victim.getHealth())
                + ",距你约 " + Math.round(bot.distanceTo(victim)) + " 格)!"
                + "要救就立刻 smart_combat(mode=guard,player_name=主人名) 或 smart_combat(mode=attack) 打掉它;顾不上就 speak 喊一声提醒。然后 finish。";
        BotLog.comm(bot, "owner_hurt_brain_push", "attacker", attackerName);
        BrainCoordinator.INSTANCE.handleMessage(bot, "system:event", text);
    }

    public boolean recentlyHurt(UUID ownerUuid) {
        HurtRecord record = lastHurtByOwner.get(ownerUuid);
        return record != null && System.currentTimeMillis() - record.atMs() <= RECENT_HURT_WINDOW_MS;
    }

    /** 5s 窗口内打主人的家伙(实体 id);窗口外返回 null(Gson 跳过 null 字段)。 */
    public String lastAttacker(UUID ownerUuid) {
        HurtRecord record = lastHurtByOwner.get(ownerUuid);
        return record != null && System.currentTimeMillis() - record.atMs() <= RECENT_HURT_WINDOW_MS
                ? record.attacker()
                : null;
    }

    public void clear() {
        lastHurtByOwner.clear();
        lastPushByBot.clear();
    }
}
