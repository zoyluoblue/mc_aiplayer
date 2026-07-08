package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class EvadeTask extends AbstractTask {
    private final Threat threat;
    private BlockPos escapeGoal;

    public EvadeTask(Threat threat) {
        this.threat = threat;
    }

    @Override
    public String name() {
        return "evade";
    }

    @Override
    public String describe() {
        return "Evading " + threat.type() + " toward " + (escapeGoal == null ? "(pending)" : compact(escapeGoal));
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, elapsed / 160.0D);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        escapeGoal = chooseGoal(bot);
        if (escapeGoal != null) {
            bot.getActionPack().startPathTo(escapeGoal);
            // 逃命必须冲刺:走路 4.3m/s 对僵尸追击 4.0m/s 只快一线,寻路绕障/起步延迟就被贴脸磨死
            //(实测无装备 bot 夜间远征被僵尸追杀致死)。冲刺 5.6m/s 才能真正甩开。
            bot.getActionPack().setSprinting(true);
        }
        // escapeGoal==null(无处可逃)→ 不启动寻路,onTick 首 tick 即 fail 交筑墙升级。
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (escapeGoal == null) {
            // 无处可逃(深处隧道/被围)→ 干净失败,交 DangerWatcher 升级筑墙自保,不假完成空转挨打。
            fail("no_valid_escape_route");
            return;
        }
        bot.getActionPack().setSprinting(true); // 持续保持(其他控制器可能每 tick 复位)
        if (bot.getBlockPos().getSquaredDistance(escapeGoal) <= 6.25D) {
            bot.getActionPack().setSprinting(false);
            complete();
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            escapeGoal = chooseGoal(bot);
            if (escapeGoal == null) {
                // BUGFIX: если нет пути — телепорт вверх на 10 блоков (аварийное спасение)
                BlockPos up = bot.getBlockPos().up(10);
                if (bot.getServerWorld().getBlockState(up).isAir() && bot.getServerWorld().getBlockState(up.up()).isAir()) {
                    bot.teleport(bot.getServerWorld(), up.getX() + 0.5D, up.getY(), up.getZ() + 0.5D, java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
                    complete();
                    return;
                }
                fail("no_valid_escape_route");
                return;
            }
            bot.getActionPack().startPathTo(escapeGoal);
        }
        if (elapsed > 400) {
            bot.getActionPack().setSprinting(false);
            fail("evade_timeout");
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().setSprinting(false);
    }

    private BlockPos chooseGoal(AIPlayerEntity bot) {
        Vec3d away = new Vec3d(1.0D, 0.0D, 0.0D);
        if (threat.entity() != null) {
            away = bot.getPos().subtract(threat.entity().getPos());
        } else if (threat.pos() != null) {
            away = bot.getPos().subtract(Vec3d.ofCenter(threat.pos()));
        }
        if (away.lengthSquared() < 0.01D) {
            away = new Vec3d(1.0D, 0.0D, 0.0D);
        }
        // 12→20 格:原 12 格停下时怪仍在感知圈内,evade 完成→任务 resume→再触发 evade,
        // 反复被蹭血磨死。20 格出圈,一次逃干净。
        away = away.normalize().multiply(20.0D);
        BlockPos base = BlockPos.ofFloored(bot.getPos().add(away));
        for (int radius = 0; radius <= 4; radius++) {
            for (BlockPos candidate : BlockPos.iterate(base.add(-radius, -2, -radius), base.add(radius, 2, radius))) {
                if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(bot.getServerWorld(), candidate)) {
                    return candidate.toImmutable();
                }
            }
        }
        // 逃向方向 20+4 格内无可站点(深处隧道四周全实心/被围)→ 返回 null 表"无处可逃",
        // 由 onTick 干净 fail 交 DangerWatcher 升级筑墙。绝不返回当前位置(旧 bug:距离=0 → 立即假完成 →
        // DangerWatcher 见威胁仍在又派 evade → 原地反复假逃被磨死,real_diamond 深层挖矿送命主因)。
        return null;
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
