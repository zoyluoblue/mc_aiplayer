package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * HUNT(第2层 食物自给):主动猎杀附近可食用动物并捡起生肉,直到凑够目标数量的肉。
 *
 * 背景:CombatCore/CombatTask 原本只打**敌对怪**(HostileEntity);bot 饿了却没有"主动去搞肉"的能力
 *(EatTask 只吃现有食物,没肉就放弃)。本任务补上这一环:找最近的牛/猪/羊/鸡/兔 → 接近 → 击杀 → 捡肉 → 凑够数。
 *
 * 复用共享原语:接近/攻击走 {@link CombatCore},掉落用 {@link HarvestCore} 强拾取(与挖矿采集一致)。
 * 自包含状态机(G1,不自 assign),全程主线程(G2)。数量达成或周围无猎物即结束,交编排层处理(如继续去烤)。
 */
public final class HuntTask extends AbstractTask {
    private enum Phase { ACQUIRE, APPROACH, STRIKE, PICKUP, ROAM }
    private enum RoamResult { STARTED, RETRY, EXHAUSTED }

    private static final int SEARCH_RANGE = 64;        // 找猎物的扫描范围(动物分散→扩到 64 格,再走过去)
    private static final int MAX_ELAPSED = 3600;       // 3 分钟硬超时
    private static final int NO_PROGRESS_LIMIT = 400;  // 20s 无进展(没靠近/没掉肉)即失败
    private static final int PICKUP_RECOVERY_LIMIT = 200; // 可见掉落/在途拾取的物理恢复硬上限
    private static final int APPROACH_STUCK_TICKS = 30; // 接近时位置 1.5s 不变即判卡路障,改直线追跨台阶
    private static final int MAX_PREY_ROAMS = 10;      // 找不到猎物时漫游换片的最多次数(目标量大时多找几片)
    private static final int ROAM_DISTANCE = 32;       // 每次漫游的水平距离
    private static final int ROAM_RETRY_ROTATION_DEGREES = 11;
    private static final double MIN_ROAM_ADVANCE_SQUARED = 64.0D; // 至少实际走 8 格才算探索过一片
    private static final int WET_PREY_REJECTION_TICKS = 300; // 上岸后 15s 内不重追刚把 bot 带进水里的同一只动物
    private static final int BLIND_PICKUP_SWEEP_DELAY = 20; // 没捡到任何副产物也要有界搜索,覆盖猪等单掉落猎物

    // 可食用猎物及其生肉掉落(烤熟前先拿到生肉)。
    private static final Set<EntityType<?>> PREY = Set.of(
            EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN, EntityType.RABBIT);
    private static final Set<Item> RAW_MEATS = Set.of(
            Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT);
    private static final Set<Item> PREY_AUXILIARY_DROPS = Set.of(
            Items.LEATHER, Items.FEATHER, Items.RABBIT_HIDE, Items.RABBIT_FOOT,
            Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL, Items.LIGHT_BLUE_WOOL,
            Items.YELLOW_WOOL, Items.LIME_WOOL, Items.PINK_WOOL, Items.GRAY_WOOL,
            Items.LIGHT_GRAY_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.BLUE_WOOL,
            Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL, Items.BLACK_WOOL);
    // Walk a small observed ring around the factual kill cell. A low ItemEntity at the player's
    // feet can fail LivingEntity.canSee even though a sibling drop was physically collected there.
    private static final int[][] PICKUP_SWEEP_OFFSETS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1},
            {2, 0}, {0, 2}, {-2, 0}, {0, -2}
    };

    private final int targetMeat;
    private final boolean requireFullQuota;
    private final int maxElapsed; // 硬超时按目标量放大(每块肉约多给 24s),打大量肉不被固定 3 分钟掐断
    private int meatBaseline;
    private int collected;
    private int lastProgressTick;
    private int pickupGrace;
    private int targetMeatBaseline;
    private int pickupInventoryBaseline;
    private int targetAuxiliaryBaseline;
    private long targetAuxiliaryPickupBaseline;
    private int pickupSweepCursor;
    private BlockPos pickupOrigin;
    private Phase phase = Phase.ACQUIRE;
    private LivingEntity target;
    private BlockPos approachStuckPos; // 接近卡路障检测:上次记录的站位
    private int approachStuckTick;     // 记录该站位的 tick
    private int roamCount;             // 找猎物漫游换片次数
    private BlockPos roamTarget;       // 漫游落脚点
    private BlockPos roamOrigin;       // 本次漫游实际起点；预算只按真实位移结算
    private int roamOrdinal;           // 本次漫游成功后应结算的序号
    private int roamAttemptSerial;     // 未结算的拒绝/落水尝试也要轮换采样方向
    private boolean roamCredited;
    private int roamStartTick;         // 本次漫游起步 tick(给寻路起步宽限,防"未出发即判到达"瞬退)
    private int nextRoamRetryTick;     // 候选路径暂时全拒时退避；不把 NO_START 误报成动物耗尽
    private final Map<UUID, Integer> wetPreyRejectedUntil = new HashMap<>();
    private final BlockMiner obstacleMiner = new BlockMiner(); // 接近时挖掉眼前挡路的方块(树叶/草/泥)
    private boolean clearingObstacle;  // 正在挖挡路方块

    public HuntTask(int targetMeat) {
        this(targetMeat, false);
    }

    public HuntTask(int targetMeat, boolean requireFullQuota) {
        this.targetMeat = Math.max(1, targetMeat);
        this.requireFullQuota = requireFullQuota;
        this.maxElapsed = Math.max(MAX_ELAPSED, this.targetMeat * 480);
    }

    @Override
    public String name() {
        return "hunt";
    }

    @Override
    public String describe() {
        return "Hunting meat " + collected + "/" + targetMeat + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, (double) collected / targetMeat);
    }

    @Override
    public boolean isWaiting() {
        // 追击/捡肉期 bot 可能短暂站立或被地形挡,本任务自带 NO_PROGRESS / 漫游 / 超时三重兜底,
        // 不交给 StuckWatcher 那个"200t 位置没变就 abort"的粗监控误杀(实测追羊卡墙被它 200t abort)。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        CombatCore.equipMelee(bot);
        meatBaseline = HarvestCore.countInventoryItems(bot, RAW_MEATS);
        collected = 0;
        lastProgressTick = 0;
        pickupGrace = 0;
        targetMeatBaseline = meatBaseline;
        pickupInventoryBaseline = meatBaseline;
        targetAuxiliaryBaseline = HarvestCore.countInventoryItems(bot, PREY_AUXILIARY_DROPS);
        targetAuxiliaryPickupBaseline = pickedUpAuxiliary(bot);
        pickupSweepCursor = 0;
        pickupOrigin = null;
        roamCount = 0;
        roamAttemptSerial = 0;
        clearRoamIntent();
        nextRoamRetryTick = 0;
        wetPreyRejectedUntil.clear();
        clearingObstacle = false;
        phase = Phase.ACQUIRE;
        surfaceIfUnderground(bot);
    }

    // 地下开猎先回地表:猎物与漫游落点都在地表,bot 在矿坑/洞里时 24 个 roam 采样点 A* 全不可达
    // → roamForPrey 首调即 false → 1t 速死 no_prey(实测挖完石做炉后接打猎步必死)。
    // 与 GatherQuotaTask.trySurface 同款 teleport 上浮兜底(贴近实操的折中:实操玩家会沿来路爬出,
    // 这里一次性上浮代替,避免给打猎再造一条"爬出矿洞"依赖链)。
    private static void surfaceIfUnderground(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        // 凹陷判定不能只看 isSkyVisible:露天竖坑(挖石现场)坑底头顶见天,但 roam 落点 A* 全不可达
        // (实测 hunt 仍 1t 速死)。改比"该列地表顶面":低于顶面 3 格以上=身处坑/谷,需要上浮。
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                feet.getX(), feet.getZ());
        if (feet.getY() >= surfaceY - 3) {
            return;
        }
        int top = world.getBottomY() + world.getHeight();
        for (int dy = 1; feet.getY() + dy < top - 1 && dy <= 80; dy++) {
            BlockPos candidate = feet.up(dy);
            if (Standability.isStandable(world, candidate) && world.isSkyVisible(candidate)) {
                boolean moved = io.github.zoyluo.aibot.mode.CapabilityRuntime.run(
                        bot, io.github.zoyluo.aibot.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                        "hunt_surface", () -> {
                            bot.getActionPack().stopAll();
                            bot.teleport(world, candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                                    java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
                        });
                if (moved) {
                    BotLog.action(bot, "hunt_surfaced", "to", candidate.toShortString());
                }
                return;
            }
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        obstacleMiner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        EpisodeMemory.INSTANCE.recordTrail(bot.getUuid(), "hunt", bot.getBlockPos()); // 工作记忆:轨迹(roam 避重用)
        if (elapsed > maxElapsed) {
            fail("hunt_timeout collected=" + collected);
            return;
        }

        // 收肉计数:强拾取脚边掉落 + 固定基线绝对增量(刚击杀的肉随后落袋也会算进来)。
        HarvestCore.forcePickupNearbyAnyOf(bot, RAW_MEATS, 2.5D, 2.5D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, RAW_MEATS) - meatBaseline);
        if (total > collected) {
            collected = total;
            lastProgressTick = elapsed;
            roamCount = 0; // 打到肉=这一带有货,重置漫游预算(否则打大量肉时 MAX_PREY_ROAMS 累计早早耗尽、没凑够就收工)
            BotLog.action(bot, "hunt_collected", "total", collected + "/" + targetMeat);
        }
        if (collected >= targetMeat) {
            complete();
            return;
        }

        // A roam that slips into water must not start and consume ten new paths while the bot is
        // still swimming (seed 3000 burned the whole prey budget in 21 ticks). Hand control to the
        // shared physical shore rescue and resume ACQUIRE only after dry ground is restored.
        if (waitForDryGround(bot)) {
            return;
        }

        // 无进展看门狗:长时间没靠近猎物/没掉肉 → 干净失败,交编排层(可能周围没动物了)。
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            fail("hunt_no_progress collected=" + collected);
            return;
        }

        switch (phase) {
            case ACQUIRE -> acquire(bot);
            case APPROACH -> approach(bot);
            case STRIKE -> strike(bot);
            case PICKUP -> pickup(bot);
            case ROAM -> roamMove(bot);
        }
    }

    // 进入接近阶段:重置卡住基线/清障状态(否则沿用上一个目标的基线,新目标第一 tick 就被误判卡住),再起步寻路。
    private void beginApproach(AIPlayerEntity bot) {
        clearRoamIntent();
        phase = Phase.APPROACH;
        // Capture before combat. The target can die and its loot can enter inventory before the
        // following task tick notices !target.isAlive(); a baseline taken in beginPickup would
        // then mistake that real pickup for pre-existing food and wait forever.
        targetMeatBaseline = HarvestCore.countInventoryItems(bot, RAW_MEATS);
        targetAuxiliaryBaseline = HarvestCore.countInventoryItems(bot, PREY_AUXILIARY_DROPS);
        targetAuxiliaryPickupBaseline = pickedUpAuxiliary(bot);
        approachStuckPos = null;
        approachStuckTick = elapsed;
        clearingObstacle = false;
        obstacleMiner.cancel(bot);
        lastProgressTick = elapsed;
        CombatCore.startApproach(bot, target);
    }

    private void acquire(AIPlayerEntity bot) {
        target = nearestPrey(bot);
        if (target != null) {
            beginApproach(bot);
            return;
        }
        // 周围(64 格)没猎物 → 先漫游换片找更多,努力凑够目标(动物分散/在远处),
        // 而非"猎到一点就收工"(实测:打 10 块肉,猎到几块后附近打光就 complete,没凑够数)。
        RoamResult roam = roamForPrey(bot);
        if (roam != RoamResult.EXHAUSTED) {
            return;
        }
        // 漫游也用尽仍找不到:普通觅食可尽力收；长期挖矿 readiness 必须达到完整配额。
        if (collected > 0 && !requireFullQuota) {
            complete();
            return;
        }
        fail((collected > 0 ? "insufficient_prey" : "no_prey_found")
                + " collected=" + collected + "/" + targetMeat + " roams=" + roamCount);
    }

    private boolean waitForDryGround(AIPlayerEntity bot) {
        boolean active = NavSafetyNet.INSTANCE.isWaterRescueActive(bot);
        if (!bot.isTouchingWater() && !active) {
            return false;
        }
        bot.getActionPack().stopAll();
        if (roamTarget != null) {
            EpisodeMemory.INSTANCE.exclude(bot.getUuid(), roamTarget,
                    bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
        }
        if (target != null && target.isAlive()) {
            int rejectedUntil = elapsed + WET_PREY_REJECTION_TICKS;
            wetPreyRejectedUntil.put(target.getUuid(), rejectedUntil);
            BotLog.action(bot, "hunt_wet_prey_rejected",
                    "prey", target.getUuid(),
                    "until", rejectedUntil,
                    "at", target.getBlockPos().toShortString());
        }
        clearRoamIntent();
        target = null;
        phase = Phase.ACQUIRE;
        lastProgressTick = elapsed;
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);
        return true;
    }

    // 找不到猎物 → 走到 ROAM_DISTANCE 外的露天地表换片再找;最多 MAX_PREY_ROAMS 次。
    private RoamResult roamForPrey(AIPlayerEntity bot) {
        if (elapsed < nextRoamRetryTick) {
            return RoamResult.RETRY;
        }
        int nextRoam = roamCount + 1;
        if (nextRoam > MAX_PREY_ROAMS) {
            return RoamResult.EXHAUSTED;
        }
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        int start = Math.floorMod(roamAttemptSerial + nextRoam, dirs.length);
        // 距离自适应:满距 8 方向全寻路被拒(山顶/悬崖/水域环绕)就减半再试——近处总有能走的点,
        // 先挪过去下轮再扩(与 GatherQuotaTask.roamToNewArea 同款,治"8 连拒直接放弃"速死)。
        for (int dist = ROAM_DISTANCE; dist >= ROAM_DISTANCE / 4; dist /= 2) {
            // 轨迹避重(与 GatherQuotaTask 同款):满距档优先去没搜过的新区,减距档不挑剔兜底。
            boolean avoidTrail = dist == ROAM_DISTANCE;
            for (int i = 0; i < dirs.length; i++) {
                int[] d = dirs[(start + i) % dirs.length];
                BlockPos column = rotatedRoamColumn(feet, d[0], d[1], dist, roamAttemptSerial);
                BlockPos ground = findGround(world, column.getX(), column.getZ());
                if (ground == null
                        || EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUuid(), ground, bot.getServer().getTicks())
                        || (avoidTrail && EpisodeMemory.INSTANCE.nearTrail(
                                bot.getUuid(), "hunt", ground, 10.0D))) {
                    continue;
                }
                // Surface exploration is a sequence of waypoints, not a one-way cave descent.
                // Before accepting a lower waypoint, prove that a no-dig/no-pillar route can walk
                // back to the current surface. This rejects seed-3000's chained safe drops into a
                // Y=53 pocket whose only reverse path consumed pillar blocks and stranded the bot.
                if (!hasWalkableReturnRoute(world, ground, feet)) {
                    EpisodeMemory.INSTANCE.exclude(bot.getUuid(), ground,
                            bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
                    BotLog.action(bot, "hunt_roam_one_way_rejected",
                            "from", feet.toShortString(), "to", ground.toShortString());
                    continue;
                }
                bot.getActionPack().stopAll();
                // 寻路被拒(目标不可达/未加载)→ 试下一个方向。原来不看结果就进 ROAM,
                // 下一 tick isPathExecutorIdle 即真 → 瞬退回 ACQUIRE → 再 roam……
                // 同一秒连发 3 次 roam、瞬间烧光漫游预算,bot 原地没动(实测 hunt 在贫瘠地形 642t 空转失败)。
                if (bot.getActionPack().startSurfacePathTo(ground).isFailed()) {
                    continue;
                }
                roamTarget = ground;
                roamOrigin = feet.toImmutable();
                roamStartTick = elapsed;
                roamOrdinal = nextRoam;
                roamAttemptSerial++;
                roamCredited = false;
                phase = Phase.ROAM;
                BotLog.action(bot, "hunt_roam",
                        "to", ground.getX() + "," + ground.getY() + "," + ground.getZ(),
                        "n", nextRoam, "dist", dist);
                return RoamResult.STARTED;
            }
        }
        // A full rejection must change the next candidate geometry. Merely sleeping and retrying
        // the same 8 directions at the same 3 radii strands the bot forever on seed-3000's ridge:
        // nextRoam stays 1 because no movement was committed, so every replan repeats the exact
        // same 24 cells until hunt_no_progress. Rotate the sampling fan deterministically while
        // preserving the roam credit; successful physical movement still owns roamCount.
        roamAttemptSerial++;
        nextRoamRetryTick = elapsed + 20;
        phase = Phase.ACQUIRE;
        BotLog.action(bot, "hunt_roam_retry",
                "n", nextRoam,
                "attempt", roamAttemptSerial,
                "from", feet.toShortString());
        return RoamResult.RETRY;
    }

    /**
     * Produces a new deterministic surface-sampling fan after every fully rejected roam attempt.
     * Serial zero is byte-for-byte the old cardinal/diagonal geometry. Later attempts rotate that
     * fan through the 45-degree symmetry sector, so a narrow ridge between the compass axes can be
     * discovered without randomness, teleporting, digging, or accepting a one-way drop.
     */
    static BlockPos rotatedRoamColumn(BlockPos origin, int dx, int dz, int distance, int attemptSerial) {
        double baseAngle = Math.atan2(dz, dx);
        int rotation = Math.floorMod(attemptSerial * ROAM_RETRY_ROTATION_DEGREES, 45);
        double angle = baseAngle + Math.toRadians(rotation);
        double radius = distance * Math.sqrt((double) dx * dx + (double) dz * dz);
        int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
        int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
        return new BlockPos(x, origin.getY(), z);
    }

    static boolean hasWalkableReturnRoute(ServerWorld world, BlockPos waypoint, BlockPos origin) {
        if (waypoint.getY() >= origin.getY()) {
            return true; // returning downhill never needs a pillar; the outbound path still validates reachability
        }
        return new AStarPathfinder(world, waypoint, origin,
                4_000, 25L, false, false).findPath().success();
    }

    // 漫游途中持续扫猎物:发现就转入捕猎;到落脚点/走不动则回 ACQUIRE 重扫。
    private void roamMove(AIPlayerEntity bot) {
        LivingEntity prey = nearestPrey(bot);
        if (prey != null) {
            target = prey;
            beginApproach(bot);
            return;
        }
        double advance = roamOrigin == null
                ? 0.0D : bot.getBlockPos().getSquaredDistance(roamOrigin);
        if (!roamCredited && advance >= MIN_ROAM_ADVANCE_SQUARED) {
            roamCount = Math.max(roamCount, roamOrdinal);
            roamCredited = true;
            lastProgressTick = elapsed;
            BotLog.action(bot, "hunt_roam_committed",
                    "n", roamCount, "advance", (int) Math.sqrt(advance));
        }
        boolean arrived = roamTarget == null
                || bot.getBlockPos().getSquaredDistance(roamTarget) <= 9.0D;
        // 起步宽限 20t:startPathTo 后 A* 异步计算需几个 tick,期间 executor 仍 idle,
        // 立即判"走不动"会瞬退(roam 形同虚设)。宽限后 idle 才是真到不了;200t 上限防走太久。
        boolean gaveUp = (elapsed - roamStartTick > 20 && bot.getActionPack().isPathExecutorIdle())
                || elapsed - roamStartTick > 200;
        if (arrived || gaveUp) {
            if (!roamCredited || gaveUp) {
                excludeRoamTarget(bot);
            }
            if (gaveUp) {
                bot.getActionPack().stopAll();
            }
            clearRoamIntent();
            phase = Phase.ACQUIRE;
        }
    }

    private void excludeRoamTarget(AIPlayerEntity bot) {
        if (roamTarget != null) {
            EpisodeMemory.INSTANCE.exclude(bot.getUuid(), roamTarget,
                    bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
        }
    }

    private void clearRoamIntent() {
        roamTarget = null;
        roamOrigin = null;
        roamOrdinal = 0;
        roamCredited = false;
    }

    // 在 (x,z) 列从高往低找第一个露天可站点(地表落脚点)。
    private static BlockPos findGround(ServerWorld world, int x, int z) {
        // 用高度图直接拿该列地表,跨任意海拔都成立。原来硬上限 y=110:bot 站在 y>110 的高地/丘陵时
        // 永远找不到落脚点 → 漫游全废 → 明明附近有猎物也 hunt_stuck_no_escape(实测 y=111、有鸡 dist 13 仍失败)。
        // 树冠穿透:原 MOTION_BLOCKING 顶面在森林落在树冠上(高大云杉 20+ 格,固定下穿格数赌不赢),
        // 林下地面又不见天 → 采样点全 null → 漫游全拒速死(实测云杉林出生)。
        // 正解:MOTION_BLOCKING_NO_LEAVES 高度图原生跳过树叶,顶面=地形/树干;再下穿几格落到地面。
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = surfaceY; y >= surfaceY - 24 && y > world.getBottomY() + 1; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (Standability.isStandable(world, p)) {
                return p;
            }
        }
        return null;
    }

    private void approach(AIPlayerEntity bot) {
        if (resolveUnavailableTarget(bot)) {
            return;
        }
        CombatCore.lookAt(bot, target);
        if (CombatCore.inMeleeRange(bot, target)) {
            bot.getActionPack().stopAll();
            phase = Phase.STRIKE;
            return;
        }
        // 正在挖眼前挡路的方块(树叶/草等),挖通就继续追。
        if (clearingObstacle) {
            BlockMiner.Status s = obstacleMiner.tick(bot);
            if (s == BlockMiner.Status.MINING) {
                return;
            }
            clearingObstacle = false;
            approachStuckPos = null;
            lastProgressTick = elapsed;
            CombatCore.startApproach(bot, target);   // 清完通路重新寻路追
            return;
        }
        // 卡住检测:站位连续不变即视为被方块挡住 / 追不上。
        BlockPos at = bot.getBlockPos();
        if (at.equals(approachStuckPos)) {
            if (elapsed - approachStuckTick > APPROACH_STUCK_TICKS) {
                // 先看眼前有没有可挖的挡路方块(树叶/草/泥)——有就挖开继续追(实测:树下打猎被树叶挡得一动不动);
                BlockPos obstacle = obstacleToward(bot);
                if (obstacle != null) {
                    BotLog.action(bot, "hunt_dig_obstacle", "at", obstacle.toShortString());
                    obstacleMiner.begin(bot, obstacle);
                    obstacleMiner.tick(bot);
                    clearingObstacle = true;
                    approachStuckTick = elapsed;
                    lastProgressTick = elapsed;
                    return;
                }
                // 没有可挖障碍(石墙 / 困在坑里)→ 换地方找,别死磕。
                BotLog.action(bot, "hunt_approach_stuck", "pos", at.toShortString(),
                        "dist", (int) bot.distanceTo(target));
                target = null;
                approachStuckPos = null;
                RoamResult roam = roamForPrey(bot);
                if (roam == RoamResult.EXHAUSTED) { // 换地方找猎物;漫游用尽才收尾
                    if (collected > 0 && !requireFullQuota) {
                        complete();
                    } else {
                        fail(collected > 0 ? "insufficient_prey" : "hunt_stuck_no_escape");
                    }
                } else if (roam == RoamResult.STARTED) {
                    lastProgressTick = elapsed;
                }
            }
            return;
        }
        approachStuckPos = at;
        approachStuckTick = elapsed;
        if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle()) {
            CombatCore.startApproach(bot, target);
            lastProgressTick = elapsed; // 重新起步追击也算进展
        }
    }

    // 朝猎物方向(approach 已 lookAt,bot 朝向即猎物方向)前方挡路的、可挖的方块:脚位或头位的固体
    //(排除流体——那是 NavSafetyNet 的事)。树叶/草/泥/雪等任意工具可挖→返回;石头无镐挖不动→返回 null 改漫游。
    private BlockPos obstacleToward(AIPlayerEntity bot) {
        net.minecraft.util.math.Direction dir = bot.getHorizontalFacing();
        ServerWorld world = bot.getServerWorld();
        BlockPos ahead = bot.getBlockPos().offset(dir);
        for (BlockPos p : new BlockPos[]{ahead, ahead.up()}) {
            net.minecraft.block.BlockState st = world.getBlockState(p);
            if (!st.isAir() && st.getFluidState().isEmpty()
                    && ToolTier.canHarvestWithInventory(bot, st)) {
                return p.toImmutable();
            }
        }
        return null;
    }

    private void strike(AIPlayerEntity bot) {
        if (resolveUnavailableTarget(bot)) {
            return;
        }
        CombatCore.lookAt(bot, target);
        if (bot.distanceTo(target) > CombatCore.ATTACK_RANGE) {
            // 严格按攻击距离(3.0)判定:超出就回去再靠近。原来留 0.75 缓冲会卡在"打不到的 3.0~3.75 区间"反复挥空,
            // 加上下面每 tick 刷 lastProgressTick → 对着打不到的目标无限对砍到 maxElapsed。beginApproach 重置卡住基线。
            beginApproach(bot);
            return;
        }
        CombatCore.equipMelee(bot); // 砍之前确保手持最佳武器(实测打猎 held=dirt,拿土砍肉伤害仅 1、极慢);equipFromSlot 幂等不抖
        CombatCore.strikeIfReady(bot, target);
        // 不再每 tick 刷 lastProgressTick:命中→掉肉会在 onTick 顶部刷新进度;真打不动(够不到/无敌)则靠
        // NO_PROGRESS_LIMIT 兜底干净失败,而非"挥空也算进展"把任务拖到 maxElapsed。
    }

    /**
     * Distinguishes a factual kill from a stale entity reference.
     *
     * <p>{@link LivingEntity#isAlive()} is also false after chunk unload. Treating every removed
     * reference as a corpse creates a pickup debt for loot that never existed, while the same
     * animal can reappear alive when its chunk loads again. Only zero health or Minecraft's
     * explicit KILLED removal reason may open the atomic loot transaction.</p>
     */
    private boolean resolveUnavailableTarget(AIPlayerEntity bot) {
        if (target != null && (target.getHealth() <= 0.0F
                || target.getRemovalReason() == net.minecraft.entity.Entity.RemovalReason.KILLED)) {
            beginPickup(bot);
            return true;
        }
        if (target != null && target.isAlive()) {
            return false;
        }

        UUID lostId = target == null ? null : target.getUuid();
        Object removal = target == null ? "missing" : target.getRemovalReason();
        obstacleMiner.cancel(bot);
        bot.getActionPack().stopAll();
        target = null;
        clearingObstacle = false;
        approachStuckPos = null;
        lastProgressTick = elapsed;
        phase = Phase.ACQUIRE;
        BotLog.action(bot, "hunt_target_reacquire", "prey", lostId, "removal", removal);
        return true;
    }

    private void beginPickup(AIPlayerEntity bot) {
        pickupOrigin = target == null ? bot.getBlockPos().toImmutable()
                : target.getBlockPos().toImmutable();
        pickupInventoryBaseline = targetMeatBaseline;
        target = null;
        clearingObstacle = false;
        obstacleMiner.cancel(bot);
        bot.getActionPack().stopAll();
        pickupGrace = 0;
        pickupSweepCursor = 0;
        lastProgressTick = elapsed;
        phase = Phase.PICKUP;
    }

    private void pickup(AIPlayerEntity bot) {
        HarvestCore.sweepPickupAnyOf(bot, RAW_MEATS, 16);
        pickupGrace++;
        boolean observedDrop = HarvestCore.nearestDropAnyOf(bot, RAW_MEATS, 16).isPresent();
        boolean pickupMovementActive = !bot.getActionPack().isPathExecutorIdle()
                || !bot.getActionPack().isWalkToIdle();
        int currentMeat = HarvestCore.countInventoryItems(bot, RAW_MEATS);
        boolean collectionConfirmed = currentMeat > pickupInventoryBaseline;
        boolean auxiliaryPickupObserved = HarvestCore.countInventoryItems(bot, PREY_AUXILIARY_DROPS)
                > targetAuxiliaryBaseline
                || pickedUpAuxiliary(bot) > targetAuxiliaryPickupBaseline;
        if (!collectionConfirmed && !pickupMovementActive && pickupGrace >= 3) {
            // The kill coordinate is factual. Return there first, but do not camp forever when a
            // low sibling ItemEntity is hidden from canSee at the player's feet. Picking leather,
            // wool, feather or hide is observable proof that this kill's loot transaction began;
            // walk an observed, dry ring so the missed meat becomes visible/collidable. The fixed
            // delay covers prey without auxiliary drops (notably pigs) without hidden scans.
            if ((auxiliaryPickupObserved || pickupGrace >= BLIND_PICKUP_SWEEP_DELAY)
                    && startNextPickupSweepStep(bot)) {
                pickupMovementActive = true;
            } else if (pickupOrigin != null) {
                pickupMovementActive = HarvestCore.approachKnownPickupCell(bot, pickupOrigin);
            }
        }
        // PICKUP is an atomic inventory transaction, not a one-tick visibility hint. A dead
        // animal's ItemEntity may become visible only after this task's tick; leaving early lets a
        // roam path overwrite the only physical pickup route. Hold the debt until inventory proves
        // success, and after that until every observed drop/controller has settled.
        if (pickupGrace < PICKUP_RECOVERY_LIMIT
                && (!collectionConfirmed || observedDrop || pickupMovementActive)) {
            return;
        }
        if (!collectionConfirmed || observedDrop || pickupMovementActive) {
            bot.getActionPack().stopAll();
            fail("hunt_drop_unrecovered origin="
                    + (pickupOrigin == null ? "unknown" : pickupOrigin.toShortString())
                    + " baseline=" + pickupInventoryBaseline + " current=" + currentMeat);
            return;
        }
        bot.getActionPack().stopAll();
        pickupOrigin = null;
        pickupInventoryBaseline = currentMeat;
        phase = Phase.ACQUIRE; // 捡完去找下一只(数量够了会在 onTick 顶部 complete)
    }

    // 只猎确定掉生肉的成年 vanilla 动物。旧的“任意 AnimalEntity”兼容会把蜜蜂、青蛙等
    // 非食物生物当猎物，既浪费 200t 拾取预算又可能主动制造战斗；模组肉源应通过显式配置扩展。
    private static boolean isHuntable(LivingEntity entity) {
        return PREY.contains(entity.getType())
                && entity instanceof net.minecraft.entity.passive.AnimalEntity animal
                && !animal.isBaby();
    }

    private LivingEntity nearestPrey(AIPlayerEntity bot) {
        wetPreyRejectedUntil.entrySet().removeIf(entry -> entry.getValue() <= elapsed);
        Box box = bot.getBoundingBox().expand(SEARCH_RANGE);
        return bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, box,
                        entity -> entity.isAlive() && entity != bot && isHuntable(entity))
                .stream()
                .filter(entity -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveEntity(bot, entity))
                .filter(entity -> !wetPreyRejectedUntil.containsKey(entity.getUuid()))
                .min(Comparator.comparingDouble(bot::distanceTo))
                .orElse(null);
    }

    boolean isWetPreyTemporarilyRejected(UUID preyId) {
        return preyId != null && wetPreyRejectedUntil.getOrDefault(preyId, -1) > elapsed;
    }

    private boolean startNextPickupSweepStep(AIPlayerEntity bot) {
        if (pickupOrigin == null) {
            return false;
        }
        ServerWorld world = bot.getServerWorld();
        for (int checked = 0; checked < PICKUP_SWEEP_OFFSETS.length; checked++) {
            int[] offset = PICKUP_SWEEP_OFFSETS[
                    Math.floorMod(pickupSweepCursor++, PICKUP_SWEEP_OFFSETS.length)];
            BlockPos candidate = pickupOrigin.add(offset[0], 0, offset[1]);
            Standability.clearCache();
            if (candidate.equals(bot.getBlockPos())
                    || !ObservableWorldQuery.canObserveCell(bot, candidate)
                    || !ObservableWorldQuery.canObserveCell(bot, candidate.up())
                    || !ObservableWorldQuery.canObserveBlock(bot, candidate.down())
                    || !Standability.isStandable(world, candidate)) {
                continue;
            }
            bot.getActionPack().startWalkTo(Vec3d.ofBottomCenter(candidate), 0.15D);
            BotLog.action(bot, "hunt_pickup_observation_sweep",
                    "origin", pickupOrigin.toShortString(),
                    "to", candidate.toShortString(),
                    "step", pickupSweepCursor);
            return true;
        }
        return false;
    }

    private static long pickedUpAuxiliary(AIPlayerEntity bot) {
        long count = 0L;
        for (Item item : PREY_AUXILIARY_DROPS) {
            count += bot.getStatHandler().getStat(Stats.PICKED_UP, item);
        }
        return count;
    }

    /** 周围是否有可猎动物——供饥饿链判断"值不值得派猎食任务",避免没动物时空派必失败。 */
    public static boolean hasPreyNearby(AIPlayerEntity bot) {
        return !bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(SEARCH_RANGE),
                        entity -> entity.isAlive() && entity != bot && isHuntable(entity))
                .stream()
                .filter(entity -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveEntity(bot, entity))
                .toList()
                .isEmpty();
    }
}
