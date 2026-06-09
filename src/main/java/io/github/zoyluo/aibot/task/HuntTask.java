package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.Set;

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

    private static final int SEARCH_RANGE = 64;        // 找猎物的扫描范围(动物分散→扩到 64 格,再走过去)
    private static final int MAX_ELAPSED = 3600;       // 3 分钟硬超时
    private static final int NO_PROGRESS_LIMIT = 400;  // 20s 无进展(没靠近/没掉肉)即失败
    private static final int PICKUP_GRACE = 25;        // 击杀后多等一会儿确保肉落袋
    private static final int APPROACH_STUCK_TICKS = 30; // 接近时位置 1.5s 不变即判卡路障,改直线追跨台阶
    private static final int MAX_PREY_ROAMS = 10;      // 找不到猎物时漫游换片的最多次数(目标量大时多找几片)
    private static final int ROAM_DISTANCE = 32;       // 每次漫游的水平距离

    // 可食用猎物及其生肉掉落(烤熟前先拿到生肉)。
    private static final Set<EntityType<?>> PREY = Set.of(
            EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN, EntityType.RABBIT);
    private static final Set<Item> RAW_MEATS = Set.of(
            Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT);

    private final int targetMeat;
    private final int maxElapsed; // 硬超时按目标量放大(每块肉约多给 24s),打大量肉不被固定 3 分钟掐断
    private int meatBaseline;
    private int collected;
    private int lastProgressTick;
    private int pickupGrace;
    private Phase phase = Phase.ACQUIRE;
    private LivingEntity target;
    private BlockPos approachStuckPos; // 接近卡路障检测:上次记录的站位
    private int approachStuckTick;     // 记录该站位的 tick
    private int roamCount;             // 找猎物漫游换片次数
    private BlockPos roamTarget;       // 漫游落脚点
    private final BlockMiner obstacleMiner = new BlockMiner(); // 接近时挖掉眼前挡路的方块(树叶/草/泥)
    private boolean clearingObstacle;  // 正在挖挡路方块

    public HuntTask(int targetMeat) {
        this.targetMeat = Math.max(1, targetMeat);
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
        roamCount = 0;
        clearingObstacle = false;
        phase = Phase.ACQUIRE;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        obstacleMiner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
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
        phase = Phase.APPROACH;
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
        if (roamForPrey(bot)) {
            return;
        }
        // 漫游也用尽仍找不到:已猎到一些就尽力收(总比空手好),一块没有才失败。
        if (collected > 0) {
            complete();
            return;
        }
        fail("no_prey_found roams=" + roamCount);
    }

    // 找不到猎物 → 走到 ROAM_DISTANCE 外的露天地表换片再找;最多 MAX_PREY_ROAMS 次。
    private boolean roamForPrey(AIPlayerEntity bot) {
        if (++roamCount > MAX_PREY_ROAMS) {
            return false;
        }
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        int start = Math.floorMod(roamCount, dirs.length);
        for (int i = 0; i < dirs.length; i++) {
            int[] d = dirs[(start + i) % dirs.length];
            BlockPos ground = findGround(world, feet.getX() + d[0] * ROAM_DISTANCE, feet.getZ() + d[1] * ROAM_DISTANCE);
            if (ground != null) {
                bot.getActionPack().stopAll();
                bot.getActionPack().startPathTo(ground);
                roamTarget = ground;
                phase = Phase.ROAM;
                lastProgressTick = elapsed;
                BotLog.action(bot, "hunt_roam",
                        "to", ground.getX() + "," + ground.getY() + "," + ground.getZ(), "n", roamCount);
                return true;
            }
        }
        return false;
    }

    // 漫游途中持续扫猎物:发现就转入捕猎;到落脚点/走不动则回 ACQUIRE 重扫。
    private void roamMove(AIPlayerEntity bot) {
        LivingEntity prey = nearestPrey(bot);
        if (prey != null) {
            target = prey;
            beginApproach(bot);
            return;
        }
        if (roamTarget == null
                || bot.getBlockPos().getSquaredDistance(roamTarget) <= 9.0D
                || bot.getActionPack().isPathExecutorIdle()) {
            roamTarget = null;
            phase = Phase.ACQUIRE;
        }
    }

    // 在 (x,z) 列从高往低找第一个露天可站点(地表落脚点)。
    private static BlockPos findGround(ServerWorld world, int x, int z) {
        // 用高度图直接拿该列地表,跨任意海拔都成立。原来硬上限 y=110:bot 站在 y>110 的高地/丘陵时
        // 永远找不到落脚点 → 漫游全废 → 明明附近有猎物也 hunt_stuck_no_escape(实测 y=111、有鸡 dist 13 仍失败)。
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
        for (int y = surfaceY; y >= surfaceY - 6 && y > world.getBottomY() + 1; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (Standability.isStandable(world, p) && world.isSkyVisible(p)) {
                return p;
            }
        }
        return null;
    }

    private void approach(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            beginPickup(bot);
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
                if (!roamForPrey(bot)) {        // 换地方找猎物;漫游用尽才收尾
                    if (collected > 0) {
                        complete();
                    } else {
                        fail("hunt_stuck_no_escape");
                    }
                }
                lastProgressTick = elapsed;
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
        if (target == null || !target.isAlive()) {
            beginPickup(bot);
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

    private void beginPickup(AIPlayerEntity bot) {
        target = null;
        clearingObstacle = false;
        obstacleMiner.cancel(bot);
        bot.getActionPack().stopMovement();
        pickupGrace = 0;
        lastProgressTick = elapsed;
        phase = Phase.PICKUP;
    }

    private void pickup(AIPlayerEntity bot) {
        HarvestCore.sweepPickupAnyOf(bot, RAW_MEATS, 16);
        if (pickupGrace++ >= PICKUP_GRACE) {
            phase = Phase.ACQUIRE; // 捡完去找下一只(数量够了会在 onTick 顶部 complete)
        }
    }

    private LivingEntity nearestPrey(AIPlayerEntity bot) {
        Box box = bot.getBoundingBox().expand(SEARCH_RANGE);
        return bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, box,
                        entity -> entity.isAlive() && entity != bot && PREY.contains(entity.getType()))
                .stream()
                .min(Comparator.comparingDouble(bot::distanceTo))
                .orElse(null);
    }

    /** 周围是否有可猎动物——供饥饿链判断"值不值得派猎食任务",避免没动物时空派必失败。 */
    public static boolean hasPreyNearby(AIPlayerEntity bot) {
        return !bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(SEARCH_RANGE),
                        entity -> entity.isAlive() && entity != bot && PREY.contains(entity.getType()))
                .isEmpty();
    }
}
