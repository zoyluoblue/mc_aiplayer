package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
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
    protected void onStart(AIPlayerEntity bot) {
        CombatCore.equipMelee(bot);
        meatBaseline = HarvestCore.countInventoryItems(bot, RAW_MEATS);
        collected = 0;
        lastProgressTick = 0;
        pickupGrace = 0;
        roamCount = 0;
        phase = Phase.ACQUIRE;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
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

    private void acquire(AIPlayerEntity bot) {
        target = nearestPrey(bot);
        if (target != null) {
            lastProgressTick = elapsed;
            phase = Phase.APPROACH;
            CombatCore.startApproach(bot, target);
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
            phase = Phase.APPROACH;
            CombatCore.startApproach(bot, prey);
            lastProgressTick = elapsed;
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
        int topY = Math.min(world.getBottomY() + world.getHeight() - 2, 110);
        for (int y = topY; y > world.getBottomY() + 1; y--) {
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
        // 卡路障检测:站位连续不变即视为卡住(实测:寻路追猎物时卡在 1 格台阶前跨不上去)。
        BlockPos at = bot.getBlockPos();
        if (at.equals(approachStuckPos)) {
            if (elapsed - approachStuckTick > APPROACH_STUCK_TICKS) {
                // 改直线追实时位置:WalkToController 会跳上 1 格台阶 / 侧移绕障,比静态寻路更跟手。
                BotLog.action(bot, "hunt_approach_stuck", "pos", at.toShortString(),
                        "dist", (int) bot.distanceTo(target));
                bot.getActionPack().startWalkTo(target.getPos());
                approachStuckTick = elapsed;
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

    private void strike(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            beginPickup(bot);
            return;
        }
        CombatCore.lookAt(bot, target);
        if (bot.distanceTo(target) > CombatCore.ATTACK_RANGE + 0.75F) {
            phase = Phase.APPROACH;
            CombatCore.startApproach(bot, target);
            return;
        }
        CombatCore.strikeIfReady(bot, target);
        lastProgressTick = elapsed; // 近身攻击中算进展(避免对峙时看门狗误杀)
    }

    private void beginPickup(AIPlayerEntity bot) {
        target = null;
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
