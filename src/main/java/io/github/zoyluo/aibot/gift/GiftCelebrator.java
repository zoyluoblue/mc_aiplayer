package io.github.zoyluo.aibot.gift;

import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.task.TaskManager;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 礼物庆祝执行器:按礼物价值档位在 Bob 身边放烟花/粒子,tier4 附带原地转圈致谢。
 * 全部状态只在服务端主线程读写(celebrate 经 mc.execute 进来,tick 挂在 END_SERVER_TICK)。
 * 多发烟花走 tick 延迟队列,绝不能 sleep——handle 在主线程,阻塞即卡服。
 *
 * 档位表:
 *  tier1 = 无烟花,头顶爱心粒子;
 *  tier2 = 单发小球;
 *  tier3 = 三连发大球拖尾;
 *  tier4 = 半径 2.5 圆周 8 点环绕齐射 + 暂停任务原地转圈 60 tick + 快乐村民粒子。
 */
public final class GiftCelebrator {
    public static final GiftCelebrator INSTANCE = new GiftCelebrator();

    private static final int SPIN_TICKS = 60;
    private static final float SPIN_STEP_DEG = 24.0F;
    // 直播观感取暖色系,和公屏金色感谢呼应。
    private static final int[] PALETTE = {0xFF5C5C, 0xFFB84D, 0xFFE95C, 0xFF7AC8, 0x7ADFFF, 0xA97AFF};

    private final ArrayDeque<PendingShot> pendingShots = new ArrayDeque<>();
    private final Map<UUID, Integer> spinUntil = new HashMap<>();

    private GiftCelebrator() {
    }

    public void celebrate(AIPlayerEntity bot, int tier, GiftDispatcher.GiftEvent event) {
        ServerWorld world = bot.getServerWorld();
        Vec3d pos = bot.getPos();
        int now = world.getServer().getTicks();
        switch (Math.max(1, Math.min(4, tier))) {
            case 1 -> world.spawnParticles(ParticleTypes.HEART,
                    pos.x, pos.y + 2.2D, pos.z, 8, 0.4D, 0.3D, 0.4D, 0.02D);
            case 2 -> pendingShots.add(new PendingShot(now, world, pos,
                    rocket(FireworkExplosionComponent.Type.SMALL_BALL, 1, false, false)));
            case 3 -> {
                for (int i = 0; i < 3; i++) {
                    pendingShots.add(new PendingShot(now + i * 10, world, pos,
                            rocket(FireworkExplosionComponent.Type.LARGE_BALL, 1, true, false)));
                }
            }
            case 4 -> {
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * 2 * i / 8;
                    Vec3d shot = pos.add(Math.cos(angle) * 2.5D, 0, Math.sin(angle) * 2.5D);
                    FireworkExplosionComponent.Type shape = i % 2 == 0
                            ? FireworkExplosionComponent.Type.STAR
                            : FireworkExplosionComponent.Type.CREEPER;
                    pendingShots.add(new PendingShot(now + i * 4, world, shot, rocket(shape, 2, true, true)));
                }
                world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                        pos.x, pos.y + 1.0D, pos.z, 24, 1.2D, 0.8D, 1.2D, 0.05D);
                // 暂停当前任务原地转圈致谢;若本无任务 pauseFor 空转,resume 同样空转,无副作用。
                TaskManager.INSTANCE.pauseFor(bot, "gift_celebration");
                bot.getActionPack().stopMovement();
                spinUntil.put(bot.getUuid(), now + SPIN_TICKS);
            }
        }
        BotLog.task(bot, "gift_celebrate", "tier", tier, "gift", event.gift(), "user", event.user());
    }

    /** 主线程每 tick 调用:消费到期烟花、驱动转圈。 */
    public void tick(MinecraftServer server) {
        if (pendingShots.isEmpty() && spinUntil.isEmpty()) {
            return;
        }
        int now = server.getTicks();
        // 队列按入队序即到期序遍历;一个 tick 可能到期多发(环绕齐射间隔短)。
        Iterator<PendingShot> shots = pendingShots.iterator();
        while (shots.hasNext()) {
            PendingShot shot = shots.next();
            if (shot.dueTick() > now) {
                continue;
            }
            shots.remove();
            shot.world().spawnEntity(new FireworkRocketEntity(
                    shot.world(), shot.pos().x, shot.pos().y + 1.0D, shot.pos().z, shot.rocket()));
        }
        Iterator<Map.Entry<UUID, Integer>> spins = spinUntil.entrySet().iterator();
        while (spins.hasNext()) {
            Map.Entry<UUID, Integer> entry = spins.next();
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.getByUuid(entry.getKey()).orElse(null);
            if (bot == null || now >= entry.getValue()) {
                spins.remove();
                if (bot != null) {
                    TaskManager.INSTANCE.resumeFromPause(bot);
                }
                continue;
            }
            LookAction.setYawPitch(bot, bot.getYaw() + SPIN_STEP_DEG, 0.0F);
        }
    }

    public void clear() {
        pendingShots.clear();
        spinUntil.clear();
    }

    private static ItemStack rocket(FireworkExplosionComponent.Type shape, int flight, boolean trail, boolean twinkle) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int primary = PALETTE[random.nextInt(PALETTE.length)];
        int fade = PALETTE[random.nextInt(PALETTE.length)];
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        stack.set(DataComponentTypes.FIREWORKS, new FireworksComponent(flight, List.of(
                new FireworkExplosionComponent(shape, IntList.of(primary), IntList.of(fade), trail, twinkle))));
        return stack;
    }

    private record PendingShot(int dueTick, ServerWorld world, Vec3d pos, ItemStack rocket) {
    }
}
