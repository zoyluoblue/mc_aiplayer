package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import net.minecraft.util.math.BlockPos;

/**
 * 死亡找回(corpse-run):重生后赶回死亡点,把掉落的装备/物资捡回来。
 * 真实玩家死后的第一反应——不找回等于装备清零重造,挖矿深处死一次效率断崖。
 *
 * 设计要点:
 *  - 掉落物 6000t(5 分钟)despawn:死亡时刻起算总预算,赶不上就别去白跑;
 *  - 移动用既有寻路(startPathTo 两阶段),失败降级挖掘接近(startDigPathTo)——死亡点
 *    多在 bot 自己走过的路上,可达性好,不需要 MoveTask 的全套中继火力;
 *  - 完成语义宽松:到点捡完窗口即 complete(哪怕零捡取——岩浆死掉落已烧光是常态),
 *    只播报结果不纠缠;捡不回来不是任务的错,别让 goal 层 replan 死循环。
 */
public final class RecoverDropsTask extends AbstractTask {
    private static final double ARRIVE_SQUARED = 9.0D;   // 3 格内算到场,开捡
    private static final int MAX_ELAPSED = 3600;          // 赶路总闸 3 分钟
    private static final int PICKUP_WINDOW = 100;         // 到场后捡取窗口 5s(掉落散布要扫几轮)
    private static final long DESPAWN_BUDGET = 5600L;     // 掉落 6000t 消失,留 400t 余量

    private final BlockPos deathPos;
    private final long deathTick;
    private int arrivedTick = -1;
    private int repathCooldown;

    public RecoverDropsTask(BlockPos deathPos, long deathTick) {
        this.deathPos = deathPos.toImmutable();
        this.deathTick = deathTick;
    }

    @Override
    public String name() {
        return "recover_drops";
    }

    @Override
    public String describe() {
        return "Recovering drops at " + deathPos.getX() + "," + deathPos.getY() + "," + deathPos.getZ();
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return arrivedTick >= 0 ? 0.8D : Math.min(0.7D, elapsed / (double) MAX_ELAPSED);
    }

    @Override
    public boolean isWaiting() {
        return arrivedTick >= 0; // 到场捡取期站桩,别让 StuckWatcher 误判
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (bot.getServer().getTicks() - deathTick > DESPAWN_BUDGET) {
            fail("drops_expired");
            return;
        }
        startApproach(bot);
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        startApproach(bot);
    }

    private void startApproach(AIPlayerEntity bot) {
        ActionResult walk = bot.getActionPack().startPathTo(deathPos);
        if (walk.isFailed()) {
            bot.getActionPack().startDigPathTo(deathPos); // 深坑/矿道里的死亡点:挖掘接近兜底
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // (溺水熔断已收编 SurvivalGuard 统一层——水底死亡点跑尸再淹死的死循环由它兜,
        //  第二次死亡知识库立危险区,反射闸劝住第三次。)
        // 沿途顺手捡:掉落可能被水流/爆炸冲散在路上
        HarvestCore.forcePickupNearbyAnyOf(bot, null, 4.0D, 2.0D);

        if (arrivedTick >= 0) {
            HarvestCore.sweepPickupAnyOf(bot, null, 10.0D, 6);
            if (elapsed - arrivedTick >= PICKUP_WINDOW) {
                // 掉落物雷达:窗口结束时还有多少没捡走(=0 才算真干净;>0 说明捡取被什么拦了)
                CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "recover_drops_report");
                var leftovers = bot.getServerWorld().getEntitiesByClass(
                        net.minecraft.entity.ItemEntity.class,
                        bot.getBoundingBox().expand(32.0D, 16.0D, 32.0D),
                        e -> ObservableWorldQuery.canObserveEntity(bot, e));
                BotLog.action(bot, "recover_drops_done", "at", deathPos.toShortString(),
                        "leftover", leftovers.size(),
                        "nearest", leftovers.isEmpty() ? "-" : leftovers.get(0).getBlockPos().toShortString());
                complete();
            }
            return;
        }

        if (bot.getBlockPos().getSquaredDistance(deathPos) <= ARRIVE_SQUARED) {
            arrivedTick = elapsed;
            bot.getActionPack().stopMovement();
            BotLog.action(bot, "recover_drops_arrived", "at", deathPos.toShortString(),
                    "elapsed", elapsed);
            return;
        }

        if (bot.getServer().getTicks() - deathTick > DESPAWN_BUDGET) {
            fail("drops_expired_enroute"); // 赶不上了,及时止损
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            fail("recover_timeout");
            return;
        }
        // 寻路断了(执行器空闲且没到)→ 续发(startPathTo 内置节流,不会风暴)
        if (bot.getActionPack().isPathExecutorIdle() && elapsed - repathCooldown > 20) {
            repathCooldown = elapsed;
            startApproach(bot);
        }
    }
}
