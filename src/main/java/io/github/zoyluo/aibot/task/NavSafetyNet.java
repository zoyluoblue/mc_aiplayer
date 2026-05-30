package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAFE-1 / NAV-12:执行期环境安全网。每 tick 在所有其它检查之前运行,只在"真正致命地形"
 * (溺水 / 站在岩浆里或将陷入 / 高坠落即将砸地)时即时接管 ActionPack 自救,脱险后让出控制。
 *
 * 设计要点:
 * - 在 BotTickCoordinator 的每 bot 循环最前面调用;返回 true 表示本 tick 已接管,后续 watcher 跳过。
 * - 它在 TaskManager.tickAll(任务驱动 ActionPack)之后运行(见 AIBotMod tick 顺序),
 *   因此可以在危险 tick 覆盖任务设置的移动输入,危险解除后任务输入照常生效。
 * - 不调用 stopAll(不杀任务),只覆盖本 tick 的 forward/jump/yaw。
 */
public final class NavSafetyNet {
    public static final NavSafetyNet INSTANCE = new NavSafetyNet();

    private static final int AIR_SURFACE_THRESHOLD = 120; // 满 300;低于此且在水下→上浮换气
    private final Map<UUID, Integer> nextLogTick = new ConcurrentHashMap<>();

    private NavSafetyNet() {
    }

    public boolean tickBot(MinecraftServer server, AIPlayerEntity bot) {
        if (!bot.isAlive()) {
            return false;
        }
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();

        // 0) 窒息/卡方块:脚位或头位有实体碰撞体时先尝试挪到附近可站点。
        if (blockedColumn(world, feet) && bot.getActionPack().snapPlayerToNearestStandable("navsafe_suffocation")) {
            throttledLog(server, bot, "navsafe_suffocation_snap", feet);
            return true;
        }

        // 1) 岩浆:站在岩浆里 / 脚下是岩浆 → 立即逃离(最高优先级)
        if (inLava(world, feet) || inLava(world, feet.down())) {
            escapeLava(bot, world, feet);
            throttledLog(server, bot, "navsafe_lava_escape", feet);
            return true;
        }

        // 2) 溺水:在水下且空气将尽 → 上浮换气(水中持续 jump 会上升)
        if (bot.isSubmergedInWater() && bot.getAir() < AIR_SURFACE_THRESHOLD) {
            bot.getActionPack().setSprinting(false);
            bot.getActionPack().setForward(0.0F);
            bot.getActionPack().setJumping(true);
            throttledLog(server, bot, "navsafe_surface_for_air", feet);
            return true;
        }

        return false;
    }

    private static void escapeLava(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        // 朝最近的"安全可站"水平方向冲出 + 起跳
        Direction best = null;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos side = feet.offset(dir);
            if (!inLava(world, side) && !inLava(world, side.down())
                    && io.github.zoyluo.aibot.pathfinding.Standability.isStandable(world, side)) {
                best = dir;
                break;
            }
        }
        if (best != null) {
            double yaw = Math.toDegrees(Math.atan2(-best.getOffsetX(), best.getOffsetZ()));
            bot.setYaw((float) yaw);
            bot.setHeadYaw((float) yaw);
            bot.setBodyYaw((float) yaw);
            bot.getActionPack().setForward(1.0F);
        }
        // 无论是否找到方向,起跳脱离岩浆体
        bot.getActionPack().setJumping(true);
        bot.getActionPack().jumpOnce();
    }

    private static boolean inLava(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().isIn(FluidTags.LAVA);
    }

    private static boolean blockedColumn(ServerWorld world, BlockPos feet) {
        return hasCollision(world, feet) || hasCollision(world, feet.up());
    }

    private static boolean hasCollision(ServerWorld world, BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private void throttledLog(MinecraftServer server, AIPlayerEntity bot, String event, BlockPos pos) {
        int now = server.getTicks();
        if (now < nextLogTick.getOrDefault(bot.getUuid(), 0)) {
            return;
        }
        nextLogTick.put(bot.getUuid(), now + 40);
        BotLog.danger(bot, event,
                "pos", pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                "air", bot.getAir(),
                "hp", String.format(java.util.Locale.ROOT, "%.1f", bot.getHealth()));
    }
}
