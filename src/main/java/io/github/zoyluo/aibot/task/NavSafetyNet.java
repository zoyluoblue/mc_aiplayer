package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
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
    private static final int EMERGENCY_AIR = 60;           // 低于此且上浮无望→紧急传送到可呼吸落点
    private static final int BREATHE_SCAN_UP = 5;          // 头顶向上找空气的格数
    private static final int RESCUE_RADIUS_H = 16;
    private static final int RESCUE_RADIUS_V = 16;
    private static final int SUFFOCATION_CLIMB_UP = 24;   // 窒息脱困优先垂直向上钻出的最大格数(地表方向)
    private final Map<UUID, Integer> nextLogTick = new ConcurrentHashMap<>();

    private NavSafetyNet() {
    }

    public boolean tickBot(MinecraftServer server, AIPlayerEntity bot) {
        if (!bot.isAlive()) {
            return false;
        }
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();

        // 0) 窒息/卡方块:脚位或头位有实体碰撞体时,优先**向上**钻出地表(修"越救越深")。
        if (blockedColumn(world, feet) && escapeSuffocation(bot, world, feet)) {
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
            // SAFE-DROWN:空气危急且头顶无空气可上浮(被石头封顶的水兜)→ 紧急传送到最近可呼吸落点。
            // 根因:findNearestStandable 把水当可通过,会返回水下落点;这里要求落点脚位+头位是空气(真能呼吸)。
            if (bot.getAir() <= EMERGENCY_AIR && !breathableAbove(world, feet)) {
                if (emergencyTeleportToAir(bot, world, feet)) {
                    throttledLog(server, bot, "navsafe_drown_teleport", feet);
                    return true;
                }
            }
            bot.getActionPack().setSprinting(false);
            bot.getActionPack().setForward(0.0F);
            bot.getActionPack().setJumping(true);
            throttledLog(server, bot, "navsafe_surface_for_air", feet);
            return true;
        }

        return false;
    }

    /**
     * 窒息脱困:优先**垂直向上**找第一个可站点(地表方向)并传送上去。
     * 修"越救越深"——旧实现用 snapPlayerToNearestStandable 找欧氏最近可站点,bot 被埋时最近点
     * 往往在下方/侧下方,反复 snap 把 bot 一格格往坑里拽(实测 994 列 64→63→62→61 困死)。
     * 向上钻出是被埋的正解(Standability.isStandable 已保证落点脚位+头位空气、脚下有支撑=能站能呼吸);
     * 向上 SUFFOCATION_CLIMB_UP 格内无解(深埋封顶)才回退到全向最近可站点,至少脱离当前窒息格。
     */
    private boolean escapeSuffocation(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        int top = world.getBottomY() + world.getHeight();
        for (int dy = 1; dy <= SUFFOCATION_CLIMB_UP && feet.getY() + dy < top - 1; dy++) {
            BlockPos candidate = feet.up(dy);
            if (Standability.isStandable(world, candidate)) {
                bot.getActionPack().stopAll();
                bot.teleport(world, candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                        Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
                Standability.clearCache();
                return true;
            }
        }
        // 向上无解(深埋/封顶)→ 回退原逻辑:全向最近可站点。
        return bot.getActionPack().snapPlayerToNearestStandable("navsafe_suffocation");
    }

    // 头顶 BREATHE_SCAN_UP 格内是否能露头呼吸(遇到非水的可通过格=能呼吸;遇到实体方块顶盖=封死)
    private static boolean breathableAbove(ServerWorld world, BlockPos feet) {
        for (int dy = 1; dy <= BREATHE_SCAN_UP; dy++) {
            BlockPos p = feet.up(dy);
            BlockState s = world.getBlockState(p);
            boolean water = s.getFluidState().isIn(FluidTags.WATER);
            boolean solid = !s.getCollisionShape(world, p).isEmpty();
            if (!water && !solid) {
                return true;   // 非水的空气格 → 上浮能呼吸
            }
            if (solid) {
                return false;  // 撞到实体方块顶盖,上浮无望
            }
        }
        return false;
    }

    private boolean emergencyTeleportToAir(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        Optional<BlockPos> safe = findNearestBreathableStandable(world, feet);
        if (safe.isEmpty()) {
            return false;
        }
        BlockPos to = safe.get();
        bot.getActionPack().stopAll();
        bot.teleport(world, to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D,
                Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
        Standability.clearCache();
        return true;
    }

    // 最近的"可站 + 脚位与头位都是空气(可呼吸,不是水)"落点
    private static Optional<BlockPos> findNearestBreathableStandable(ServerWorld world, BlockPos origin) {
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -RESCUE_RADIUS_H; dx <= RESCUE_RADIUS_H; dx++) {
            for (int dz = -RESCUE_RADIUS_H; dz <= RESCUE_RADIUS_H; dz++) {
                for (int dy = -RESCUE_RADIUS_V; dy <= RESCUE_RADIUS_V; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!Standability.isStandable(world, cursor)) {
                        continue;
                    }
                    if (!world.getBlockState(cursor).isAir() || !world.getBlockState(cursor.up()).isAir()) {
                        continue;   // 脚位或头位是水/方块 → 不可呼吸
                    }
                    double distance = cursor.getSquaredDistance(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = cursor.toImmutable();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
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
