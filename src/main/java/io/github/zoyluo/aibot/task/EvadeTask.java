package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public final class EvadeTask extends AbstractTask {
    private static final double GOAL_REACHED_SQUARED = 6.25D;
    private static final int MAX_PATH_ADMISSION_ATTEMPTS = 5;
    // One twelve-block ring lets the fixed A* budget examine every direction exactly once. Moving
    // pressure is handled by another leg at the arrival boundary; longer rings only starved the
    // fifth direction behind four disconnected twenty-block endpoints.
    private static final int ESCAPE_DISTANCE = 12;
    private static final double[] ESCAPE_ANGLES = {
            0.0D,
            Math.PI / 4.0D,
            -Math.PI / 4.0D,
            Math.PI / 2.0D,
            -Math.PI / 2.0D
    };

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
        startBestEscapePath(bot);
        // 逃命必须冲刺:走路 4.3m/s 对僵尸追击 4.0m/s 只快一线,寻路绕障/起步延迟就被贴脸磨死
        //(实测无装备 bot 夜间远征被僵尸追杀致死)。冲刺 5.6m/s 才能真正甩开。
        // escapeGoal==null(无处可逃)→ 不启动寻路,onTick 首 tick 即 fail 交筑墙升级。
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (escapeGoal == null) {
            // 无处可逃(深处隧道/被围)→ 干净失败,交 DangerWatcher 升级筑墙自保,不假完成空转挨打。
            failNoValidEscapeRoute(bot);
            return;
        }
        bot.getActionPack().setSprinting(true); // 持续保持(其他控制器可能每 tick 复位)
        if (bot.getBlockPos().getSquaredDistance(escapeGoal) <= GOAL_REACHED_SQUARED) {
            // Reaching the originally projected point is not the same as escaping a moving mob.
            // The strict obsidian run reached its first waypoint while the same Creeper was still
            // visible, completed Evade, and immediately let AcquireWater path back toward it.
            // Keep ownership and project another factual surface leg while the observed source can
            // still apply pressure; this also avoids a pause/resume/cooldown gap between legs.
            if (hasObservedUnsettledPressure(bot)) {
                BlockPos previous = escapeGoal;
                if (!startBestEscapePath(bot)) {
                    failNoValidEscapeRoute(bot);
                    return;
                }
                BotLog.action(bot, "evade_pressure_extended",
                        "from_goal", previous,
                        "to_goal", escapeGoal,
                        "source", threat.entity().getBlockPos());
                return;
            }
            DangerWatcher.INSTANCE.noteEvadeCompleted(bot);
            bot.getActionPack().stopAll();
            complete();
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            if (!startBestEscapePath(bot)) {
                failNoValidEscapeRoute(bot);
                return;
            }
        }
        if (elapsed > 400) {
            bot.getActionPack().stopAll();
            fail("evade_timeout");
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    private void failNoValidEscapeRoute(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        fail("no_valid_escape_route");
    }

    private boolean startBestEscapePath(AIPlayerEntity bot) {
        escapeGoal = admitBestSurfaceEscapePath(
                bot, threat.entity(), threat.pos(), ESCAPE_DISTANCE);
        return escapeGoal != null;
    }

    /**
     * Admits the first real surface-only route in the shared five-direction escape fan.
     * Callers receive the path executor's resolved goal, or {@code null} after every candidate
     * failed. Failure always releases all movement state so a safety owner can fail atomically.
     */
    static BlockPos admitBestSurfaceEscapePath(AIPlayerEntity bot,
                                                LivingEntity source,
                                                BlockPos rememberedSource,
                                                int distance) {
        int attempts = 0;
        for (BlockPos candidate : chooseGoals(bot, source, rememberedSource, distance)) {
            ActionResult path = bot.getActionPack().startSurfacePathTo(candidate);
            attempts++;
            if (!path.isFailed()) {
                // Sprint belongs only to an admitted live escape path. Leaving it enabled after a
                // failed admission makes ActionPack permanently busy and blocks paused work resume.
                bot.getActionPack().setSprinting(true);
                BlockPos resolved = bot.getActionPack().activePathGoal();
                return resolved == null ? candidate : resolved.toImmutable();
            }
            if (attempts >= MAX_PATH_ADMISSION_ATTEMPTS) {
                break;
            }
        }
        bot.getActionPack().stopAll();
        return null;
    }

    private static List<BlockPos> chooseGoals(AIPlayerEntity bot,
                                               LivingEntity source,
                                               BlockPos rememberedSource,
                                               int distance) {
        Vec3d away = new Vec3d(1.0D, 0.0D, 0.0D);
        if (source != null
                && source.isAlive()
                && ObservableWorldQuery.canObserveEntity(bot, source)) {
            away = bot.getPos().subtract(source.getPos());
        } else if (rememberedSource != null) {
            away = bot.getPos().subtract(Vec3d.ofCenter(rememberedSource));
        }
        // Escape is surface displacement, never vertical excavation. An entity-less LOW_HP used
        // to point at the bot's own block center; the only non-zero component was Y=-0.5, which
        // normalized into a destination twenty blocks underground. Flatten every source vector so
        // even defensive or future threat types cannot turn "run away" into "dig into a cave".
        away = new Vec3d(away.x, 0.0D, away.z);
        if (away.lengthSquared() < 0.01D) {
            return List.of(); // no spatial direction is safer than inventing an arbitrary route
        }

        // A straight projection can land on a cliff or over a ravine even when a factual lateral
        // escape exists. Search one bounded twelve-block fan and give all five directions one path
        // admission; a still-observed moving threat causes the next leg to be projected later.
        Vec3d normalized = away.normalize();
        List<BlockPos> goals = new ArrayList<>();
        for (double angle : ESCAPE_ANGLES) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            Vec3d direction = new Vec3d(
                    normalized.x * cos - normalized.z * sin,
                    0.0D,
                    normalized.x * sin + normalized.z * cos);
            Vec3d horizontal = bot.getPos().add(direction.multiply(Math.max(1, distance)));
            BlockPos base = new BlockPos(
                    net.minecraft.util.math.MathHelper.floor(horizontal.x),
                    bot.getBlockPos().getY(),
                    net.minecraft.util.math.MathHelper.floor(horizontal.z));
            BlockPos goal = findStandableNear(bot, base);
            if (goal != null
                    && bot.getBlockPos().getSquaredDistance(goal) > GOAL_REACHED_SQUARED
                    && !goals.contains(goal)) {
                goals.add(goal);
            }
        }
        return goals;
    }

    private static BlockPos findStandableNear(AIPlayerEntity bot, BlockPos base) {
        for (int radius = 0; radius <= 4; radius++) {
            for (BlockPos candidate : BlockPos.iterate(
                    base.add(-radius, -2, -radius), base.add(radius, 2, radius))) {
                if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(
                        bot.getServerWorld(), candidate)) {
                    return candidate.toImmutable();
                }
            }
        }
        return null;
    }

    private boolean hasObservedUnsettledPressure(AIPlayerEntity bot) {
        LivingEntity source = threat.entity();
        if (source == null
                || !DangerWatcher.isActiveHostileThreat(bot, source)
                || !ObservableWorldQuery.canObserveEntity(bot, source)) {
            return false;
        }
        // Creepers are never a melee target and can close the ordinary ten-block contact envelope
        // while mission work reverses direction. Continue until this exact source leaves factual
        // perception. Other mobs use the shared close/ranged combat pressure boundary.
        return source instanceof CreeperEntity
                || CombatCore.isWithinHostilePressureEnvelope(bot, source);
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
