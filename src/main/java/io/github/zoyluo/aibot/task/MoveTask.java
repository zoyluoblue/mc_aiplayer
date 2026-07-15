package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.DigNav;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.UUID;

public final class MoveTask extends AbstractTask {
    private static final int DIG_NO_PROGRESS_LIMIT = 200; // 挖掘式直行 10s 没破块/没迈步 → 放弃
    private static final int DIG_MAX_ELAPSED = 2400;
    private static final double ARRIVE_SQUARED = 4.0D;     // 挖掘式到 goal 2 格内即视为到达

    // —— 分段中继导航(绕大湖/大障碍)——
    // 失败机理(real_nav_far 实测):目标 120 格外隔着大湖。A* 不走水柱(深水不可站立、挖掘阶段
    // 也不挖含流体方块),WALK_MAX_NODES=10k 的预算又不够搜出整条绕湖长路 → 寻路"彻底失败" →
    // 旧逻辑直接降级挖掘式直行 → DigNav 朝目标硬挖、一头挖进湖里 → touchingWater 熔断 fail。
    // 解法:把"一步直达"拆成"多段经停"——每段 ≤40 格,A* 预算内必然可解;中继点沿 bot→goal
    // 方位角左右扫偏角,只选"干燥可站"的落脚点,湖是绕出来的,不是挖出来的。
    private static final int WAYPOINT_MAX_HOPS = 6;             // 中继跳数上限:防湖湾地形里无限折返
    private static final double WAYPOINT_ARRIVE_SQUARED = 9.0D; // 距中继点 ≤3 格即视为经停到达
    private static final int WAYPOINT_PATH_ATTEMPTS = 5;        // 单次选点最多对几个候选实跑 A*(同步寻路单次有 50ms 级预算,封顶防单 tick 长卡)
    private static final int[] WAYPOINT_DEFLECTIONS_DEG = {0, 30, -30, 60, -60, 90, -90}; // 偏角序列:先直奔,再左右扇形扫开

    private BlockPos goal;
    private final double startDistance;
    private final UUID targetPlayerUuid;
    private final String targetPlayerName;
    private BlockPos resolvedGoal;
    private boolean digging;                               // 纯寻路走不通 → 降级为挖掘式直行
    private final BlockMiner miner = new BlockMiner();
    private int digLastProgressTick;
    private BlockPos waypoint;                             // 经停模式:当前中继点;null = 直奔最终 goal
    private int waypointHops;                              // 已采用中继点次数(上限 WAYPOINT_MAX_HOPS)
    private int nextPlayerRepathTick;
    private int nextWaterRetryTick;

    public MoveTask(BlockPos start, BlockPos goal) {
        this(start, goal, null, "");
    }

    private MoveTask(BlockPos start, BlockPos goal, UUID targetPlayerUuid, String targetPlayerName) {
        this.goal = goal.toImmutable();
        this.startDistance = Math.sqrt(start.getSquaredDistance(goal));
        this.targetPlayerUuid = targetPlayerUuid;
        this.targetPlayerName = targetPlayerName == null ? "" : targetPlayerName;
    }

    public MoveTask(AIPlayerEntity bot, BlockPos goal) {
        this(bot.getBlockPos(), goal);
    }

    /** One-shot approach to a moving player. Completion uses live entity distance, not a stale block snapshot. */
    public MoveTask(AIPlayerEntity bot, ServerPlayerEntity target) {
        this(bot.getBlockPos(), target.getBlockPos(), target.getUuid(), target.getGameProfile().getName());
    }

    @Override
    public String name() {
        return "move";
    }

    @Override
    public String describe() {
        if (targetPlayerUuid != null) {
            return (digging ? "Digging toward " : "Moving to ") + targetPlayerName;
        }
        return (digging ? "Digging to " : "Walking to ") + compact(goal);
    }

    @Override
    public double progress() {
        if (startDistance <= 0.1D || state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, elapsed / Math.max(20.0D, startDistance * 12.0D));
    }

    @Override
    public boolean isWaiting() {
        // 挖掘式直行时 bot 站着挖、位置基本不变 → 视为 waiting,让 StuckWatcher 不误判(由本任务看门狗兜底)。
        return digging;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        // 越界目标快速认输:y 超出世界范围(虚空下/建筑上限外)物理不可达,任何走/挖都是空转
        //(实测朝 y330 目标"挖天"耗满 2400t 不认输——空转是实操里最隐蔽的故障形态)。
        ServerWorld world = bot.getServerWorld();
        int bottom = world.getBottomY();
        int top = bottom + world.getHeight();
        if (goal.getY() < bottom || goal.getY() >= top) {
            fail("goal_out_of_world y=" + goal.getY());
            return;
        }
        if (!refreshPlayerGoal(bot, true)) {
            return;
        }
        nextPlayerRepathTick = 20;
        startWalkOrDig(bot);
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        digging = false;
        startWalkOrDig(bot);
    }

    private void startWalkOrDig(AIPlayerEntity bot) {
        ActionResult result = bot.getActionPack().startPathTo(goal);
        if (result.isFailed()) {
            if (isWaterGoal(bot)) {
                // 水面目标不能降级成 DigNav。挖掘直行只会钻进湖底，然后被安全网拉回岸边。
                digging = false;
                waypoint = null;
                resolvedGoal = null;
                nextWaterRetryTick = Math.max(nextWaterRetryTick, elapsed + 40);
                BotLog.action(bot, "move_water_path_retry", "goal", compact(goal), "reason", result.reason());
                return;
            }
            // 中继优先于挖掘降级:挖掘式直行是"最后手段"——它无视地形朝坐标硬挖,目标隔水时
            // 必然一路挖进湖里触发溺水熔断、任务必败。寻路失败先试分段中继(走得通就不动土),
            // 中继也选不出落脚点才退回挖掘式直行。
            if (tryWaypointRelay(bot, "path_start:" + result.reason())) {
                return;
            }
            beginDigging(bot, result.reason()); // 纯寻路一开始就失败(被墙/SEARCH_LIMIT)→ 直接挖掘式直行
            return;
        }
        waypoint = null; // 直达寻路成功 → 不需要经停(也清掉 resume 残留的旧中继)
        resolvedGoal = bot.getActionPack().activePathGoal();
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        ServerPlayerEntity playerTarget = currentPlayerTarget(bot);
        if (targetPlayerUuid != null) {
            if (playerTarget == null || playerTarget.getServerWorld() != bot.getServerWorld()) {
                bot.getActionPack().stopAll();
                fail("target_player_offline_or_other_dimension");
                return;
            }
            if (bot.distanceTo(playerTarget) <= 3.0D) {
                miner.cancel(bot);
                bot.getActionPack().stopAll();
                complete();
                return;
            }
            BlockPos liveGoal = playerTarget.getBlockPos();
            if (goal.getSquaredDistance(liveGoal) > 4.0D && elapsed >= nextPlayerRepathTick) {
                retargetPlayer(bot, liveGoal);
                return;
            }
        } else if (bot.getBlockPos().getSquaredDistance(currentGoal()) <= 2.25D
                || (digging && bot.getBlockPos().getSquaredDistance(goal) <= ARRIVE_SQUARED)) {
            miner.cancel(bot);
            complete();
            return;
        }
        if (digging) {
            digTick(bot);
            return;
        }
        // 经停模式:正赶往中继点。到达中继点 ≠ 任务完成,在 waypointTick 里换乘(重新直奔最终 goal)。
        if (waypoint != null) {
            waypointTick(bot);
            return;
        }
        // 纯寻路模式:寻路执行器空闲(到不了)→ 降级挖掘式直行,而不是直接 did_not_reach 卡死。
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 5) {
            if (isWaterGoal(bot)) {
                if (elapsed >= nextWaterRetryTick) {
                    nextWaterRetryTick = elapsed + 40;
                    startWalkOrDig(bot);
                }
                if (elapsed > 1200) {
                    fail("move_water_path_timeout");
                }
                return;
            }
            beginDigging(bot, "path_idle");
            return;
        }
        if (elapsed > 1200) {
            fail("move_timeout");
        }
    }

    private void beginDigging(AIPlayerEntity bot, String reason) {
        digging = true;
        waypoint = null; // 互斥:进挖掘模式即放弃经停
        digLastProgressTick = elapsed;
        bot.getActionPack().stopAll(); // 清掉寻路状态,改由 DigNav 驱动
        BotLog.action(bot, "move_dig_fallback", "goal", compact(goal), "reason", reason);
    }

    private void retargetPlayer(AIPlayerEntity bot, BlockPos liveGoal) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        goal = liveGoal.toImmutable();
        resolvedGoal = null;
        digging = false;
        waypoint = null;
        waypointHops = 0;
        nextPlayerRepathTick = elapsed + 20;
        startWalkOrDig(bot);
        BotLog.action(bot, "move_player_retarget", "player", targetPlayerName, "goal", compact(goal));
    }

    private boolean refreshPlayerGoal(AIPlayerEntity bot, boolean failWhenMissing) {
        if (targetPlayerUuid == null) {
            return true;
        }
        ServerPlayerEntity target = currentPlayerTarget(bot);
        if (target == null || target.getServerWorld() != bot.getServerWorld()) {
            if (failWhenMissing) {
                fail("target_player_offline_or_other_dimension");
            }
            return false;
        }
        goal = target.getBlockPos().toImmutable();
        return true;
    }

    private ServerPlayerEntity currentPlayerTarget(AIPlayerEntity bot) {
        return targetPlayerUuid == null ? null : bot.getServer().getPlayerManager().getPlayer(targetPlayerUuid);
    }

    private boolean isWaterGoal(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        return Standability.isSwimmable(world, goal)
                || world.getFluidState(goal).isIn(FluidTags.WATER)
                || world.getFluidState(goal.down()).isIn(FluidTags.WATER);
    }

    private void digTick(AIPlayerEntity bot) {
        // 安全熔断(实测致死根因):挖掘式直行会朝坐标**挖穿一切**,最危险。一旦把 bot 挖进水下(溺水)
        // 或挖进怪堆(正在挨打),立即放弃,交生存层(NavSafetyNet/DangerWatcher)或大脑处理——
        // 绝不一路挖到淹死/被围殴致死。
        // 病根:大脑 move_to 盲目挖向坐标 → digStep 一路挖进水域 → bot 头没入水中;NavSafetyNet 每 tick
        // 上浮换气,但下一 tick 本任务又 digStep 把 bot 挖回水里 → "上浮↔挖回"活锁几分钟、零进展,
        // 最终溺水/被怪打死(实测两次死亡)。在这里 submerged/挨打即熔断,从根上打破活锁、保命第一。
        // 熔断提前:脚一沾水就停(原来等头没入 submerged 才停——那时已半淹,安全网要拖很久才能救上岸;
        // 实测 real_nav_far 挖到湖边 73t 即灌水)。touchingWater 时水还没过头,立即停手交安全网上岸。
        if (bot.isTouchingWater()) {
            miner.cancel(bot);
            // 熔断保命 × 中继绕行的分工:熔断只负责"别淹死",不负责"把路走完"——沾水说明挖掘直行
            // 正把 bot 往水体里送,继续挖必然重演活锁。所以 fail 之前先尝试分段中继:挑一个偏离水体
            // 的干燥落脚点重新寻路绕行(湖只能绕,不能挖)。中继也找不到(四面环水/跳数耗尽)才维持
            // 原语义 fail("move_dig_drowning"),交安全网上岸、大脑另谋出路。
            if (tryWaypointRelay(bot, "dig_drowning")) {
                return;
            }
            fail("move_dig_drowning");
            return;
        }
        if (bot.hurtTime > 0) {
            miner.cancel(bot);
            fail("move_dig_under_attack");
            return;
        }
        if (elapsed > DIG_MAX_ELAPSED) {
            miner.cancel(bot);
            fail("move_dig_timeout");
            return;
        }
        if (elapsed - digLastProgressTick > DIG_NO_PROGRESS_LIMIT) {
            miner.cancel(bot);
            fail("move_dig_no_progress"); // 挖不动/受阻(如四面岩浆)→ 交还,交生存层/大脑
            return;
        }
        if (DigNav.digStep(bot, miner, goal)) {
            digLastProgressTick = elapsed;
        }
    }

    // ==================== 分段中继导航 ====================

    /**
     * 经停模式主循环:到达中继点(≤3 格)或这一段路提前走断(执行器空闲)→ 清掉中继点,
     * 重新对最终 goal 直达寻路;直达仍不通就再选下一个中继点,逐段啃完全程。
     */
    private void waypointTick(AIPlayerEntity bot) {
        if (elapsed > 1200) {
            fail("move_timeout"); // 与纯寻路模式同一条总闸,经停绕路也不许无限耗
            return;
        }
        boolean arrived = bot.getBlockPos().getSquaredDistance(waypoint) <= WAYPOINT_ARRIVE_SQUARED;
        if (!arrived && !bot.getActionPack().isPathExecutorIdle()) {
            return; // 仍在赶往中继点的路上
        }
        // 经停到达(或这一段提前断了也就地换乘):重新直奔最终 goal——离湖更近、视角变了,直达可能已经可解。
        waypoint = null;
        ActionResult result = bot.getActionPack().startPathTo(goal);
        if (!result.isFailed()) {
            resolvedGoal = bot.getActionPack().activePathGoal();
            return;
        }
        if (tryWaypointRelay(bot, "relay_next:" + result.reason())) {
            return;
        }
        // 中继耗尽 → 走原失败路径(降级挖掘式直行,由其熔断/看门狗定生死)
        beginDigging(bot, "waypoint_exhausted");
    }

    /**
     * 尝试进入/延续经停模式:选一个干燥可站的中继点并对它寻路成功 → 记录状态 + 打点。
     * 返回 false 表示中继救不了(跳数耗尽或扇形里选不出落脚点),调用方走原失败路径。
     */
    private boolean tryWaypointRelay(AIPlayerEntity bot, String reason) {
        if (waypointHops >= WAYPOINT_MAX_HOPS) {
            BotLog.action(bot, "move_waypoint_exhausted",
                    "why", "hops_limit", "hops", waypointHops, "goal", compact(goal), "reason", reason);
            return false;
        }
        BlockPos picked = pickWaypoint(bot, goal);
        if (picked == null) {
            BotLog.action(bot, "move_waypoint_exhausted",
                    "why", "no_candidate", "hops", waypointHops, "goal", compact(goal), "reason", reason);
            return false;
        }
        waypoint = picked;
        waypointHops++;
        digging = false; // 可能从挖掘熔断转入:经停段按纯寻路走,不再动土
        BotLog.action(bot, "move_waypoint",
                "to", waypoint.toShortString(), "hop", waypointHops, "goal", compact(goal), "reason", reason);
        return true;
    }

    /**
     * 中继点选择:以 bot→goal 方位角 θ 为基准,偏角 {0°,±30°,±60°,±90°}(外层)×
     * 前出距离 {goal距离一半钳到≤40, 24, 12}(内层)生成候选;每个候选取地表落脚 y,
     * 必须同时满足:可站立 + 干列(湖面/浅滩水点全排除)+ 不比当前更远离 goal 超 10%(防背向倒退)。
     * 第一个几何合格且 startPathTo 不失败的候选即采用(寻路成功即顺带启动了去程)。
     * 距离钳 ≤40:保证每一段都落在 A* 步行预算(10k 节点)稳定可解的范围内——分段正是为此。
     */
    private BlockPos pickWaypoint(AIPlayerEntity bot, BlockPos target) {
        ServerWorld world = bot.getServerWorld();
        double bx = bot.getX();
        double bz = bot.getZ();
        double dxGoal = target.getX() + 0.5D - bx;
        double dzGoal = target.getZ() + 0.5D - bz;
        double goalDist = Math.sqrt(dxGoal * dxGoal + dzGoal * dzGoal);
        if (goalDist < 1.0D) {
            return null; // 已经贴脸,没有"前出中继"可言
        }
        double theta = Math.atan2(dzGoal, dxGoal);
        double maxGoalDist = goalDist * 1.10D;
        double maxGoalDistSq = maxGoalDist * maxGoalDist;
        double[] distances = {Math.min(goalDist / 2.0D, 40.0D), 24.0D, 12.0D};
        int pathAttempts = 0;
        for (int deg : WAYPOINT_DEFLECTIONS_DEG) {
            double phi = theta + Math.toRadians(deg);
            double cos = Math.cos(phi);
            double sin = Math.sin(phi);
            for (double dist : distances) {
                int x = (int) Math.floor(bx + dist * cos);
                int z = (int) Math.floor(bz + dist * sin);
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (!Standability.isStandable(world, candidate)) {
                    continue;
                }
                if (!isDryColumn(world, candidate)) {
                    continue; // 湖面取出的悬空格/浅滩水脚全排除——中继点自己先别站进水里
                }
                if (candidate.getSquaredDistance(target) > maxGoalDistSq) {
                    continue;
                }
                if (pathAttempts >= WAYPOINT_PATH_ATTEMPTS) {
                    return null; // 同步 A* 单次 ~50ms 级,封顶实跑次数防单 tick 长卡
                }
                pathAttempts++;
                if (!bot.getActionPack().startPathTo(candidate).isFailed()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * 干列检查:候选脚格及其向下 4 格全部无流体才算"干"。
     * MOTION_BLOCKING_NO_LEAVES 在湖面上取到的是水面上方的悬空格、在浅滩取到的脚格本身是水,
     * 两类都必须排除——否则中继点把 bot 直接引进水里,绕行变送死。
     */
    private static boolean isDryColumn(ServerWorld world, BlockPos feet) {
        for (int i = 0; i <= 4; i++) {
            if (!world.getFluidState(feet.down(i)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private BlockPos currentGoal() {
        return resolvedGoal == null ? goal : resolvedGoal;
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
