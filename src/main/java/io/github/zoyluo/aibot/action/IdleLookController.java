package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.task.TaskManager;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 空闲注视:治"盯墙发呆/不看人说话"——此前空闲/等待时没有任何头部控制,头永远冻结在
 * 上一个任务留下的 yaw(实测机器人感唯一根因)。
 *
 * 行为:无任务(active/paused 都空)且主人在 12 格内 → 平滑看向主人头部;每 3~5s 换一次
 * 小幅随机视线偏移(±7° yaw / ±4° pitch),像活人不像雕像。大脑思考中(busy)也生效——
 * "说话时看着人"。任何任务存在时完全让位(任务自己控制视线)。
 */
public final class IdleLookController {
    public static final IdleLookController INSTANCE = new IdleLookController();

    private static final double LOOK_RANGE = 12.0D;

    private final Map<UUID, Integer> nextGazeShiftTick = new ConcurrentHashMap<>();
    private final Map<UUID, float[]> gazeOffset = new ConcurrentHashMap<>();

    private IdleLookController() {
    }

    public void tick(AIPlayerEntity bot) {
        if (TaskManager.INSTANCE.getActive(bot).isPresent() || TaskManager.INSTANCE.hasPaused(bot)) {
            return; // 任务自己控制视线,完全让位
        }
        ServerPlayerEntity owner = AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(uuid -> bot.getServer().getPlayerManager().getPlayer(uuid))
                .orElse(null);
        if (owner == null || owner.getServerWorld() != bot.getServerWorld()
                || bot.distanceTo(owner) > LOOK_RANGE) {
            return; // 主人不在近旁:保持现状,不强行扫视
        }
        UUID id = bot.getUuid();
        int now = bot.getServer().getTicks();
        float[] offset = gazeOffset.computeIfAbsent(id, ignored -> new float[2]);
        Integer next = nextGazeShiftTick.get(id);
        if (next == null || now >= next) {
            offset[0] = (ThreadLocalRandom.current().nextFloat() - 0.5F) * 14.0F;
            offset[1] = (ThreadLocalRandom.current().nextFloat() - 0.5F) * 8.0F;
            nextGazeShiftTick.put(id, now + 60 + ThreadLocalRandom.current().nextInt(40));
        }
        LookAction.lookAtSmooth(bot, owner.getEyePos(), offset[0], offset[1]);
    }

    public void clear() {
        nextGazeShiftTick.clear();
        gazeOffset.clear();
    }
}
