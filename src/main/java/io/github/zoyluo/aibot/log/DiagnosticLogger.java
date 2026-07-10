package io.github.zoyluo.aibot.log;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试回溯用的详细诊断日志。不改动任何既有实体/任务类:
 * - 每 SNAPSHOT_INTERVAL tick 对每个 bot 打一条富快照(位置/血量/饱食/模式/在地/坠落/空气/任务+阶段+进度/手持/背包/路径与挖掘状态)。
 * - 每 tick 与上一 tick 对比,捕捉关键事件:掉血、坠落距离突增、从世界消失/死亡(这正是排查"Bob 突然不见了"所需)。
 * 全部在主线程的 server tick 内调用(G2 线程安全)。
 */
public final class DiagnosticLogger {
    public static final DiagnosticLogger INSTANCE = new DiagnosticLogger();

    private static final int SNAPSHOT_INTERVAL = 40; // 2 秒一条富快照
    private boolean enabled = true;

    private final Map<UUID, Sample> last = new ConcurrentHashMap<>();

    private DiagnosticLogger() {
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void clear(AIPlayerEntity bot) {
        last.remove(bot.getUuid());
    }

    public void clearAll() {
        last.clear();
    }

    public void tick(MinecraftServer server) {
        if (!enabled) {
            return;
        }
        // 服务器停止中:实体会被正常卸载(isAlive 翻 false、从 all() 移除),
        // 此时不做死亡/消失检测,避免把"关服卸载"误报成 diag_bot_died / diag_bot_vanished。
        if (server.isStopping()) {
            last.clear();
            return;
        }
        int tick = server.getTicks();

        Map<UUID, Sample> current = new LinkedHashMap<>();
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            UUID id = bot.getUuid();
            Sample now = sampleOf(bot);
            current.put(id, now);

            Sample prev = last.get(id);
            detectEvents(bot, prev, now);

            if (tick % SNAPSHOT_INTERVAL == 0) {
                snapshot(bot, now);
            }
        }

        // 检测"消失":上一 tick 还在、这一 tick 不在 all() 里 —— 这是 Bob 突然不见的核心线索
        for (Map.Entry<UUID, Sample> entry : last.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                Sample gone = entry.getValue();
                BotLog.lifecycle("diag_bot_vanished",
                        "name", gone.name,
                        "last_pos", gone.x + "," + gone.y + "," + gone.z,
                        "last_hp", fmt(gone.health),
                        "last_food", gone.food,
                        "last_task", gone.taskName + "/" + gone.taskPhase,
                        "on_ground", gone.onGround,
                        "fall", fmt(gone.fallDistance),
                        "removed", gone.removed,
                        "alive", gone.alive,
                        "note", "上一tick还在,这一tick已不在AIPlayerManager.all();对照last_* 判断是死亡/掉虚空/被移除");
            }
        }

        last.clear();
        last.putAll(current);
    }

    private void detectEvents(AIPlayerEntity bot, Sample prev, Sample now) {
        if (prev == null) {
            return;
        }
        // 掉血
        if (now.health < prev.health - 0.01F) {
            BotLog.danger(bot, "diag_health_drop",
                    "from", fmt(prev.health),
                    "to", fmt(now.health),
                    "delta", fmt(prev.health - now.health),
                    "pos", now.x + "," + now.y + "," + now.z,
                    "task", now.taskName + "/" + now.taskPhase,
                    "fall", fmt(now.fallDistance),
                    "in_lava", now.inLava,
                    "submerged", now.submerged,
                    "air", now.air);
        }
        // 死亡(还在列表但已不 alive)
        if (prev.alive && !now.alive) {
            BotLog.danger(bot, "diag_bot_died",
                    "pos", now.x + "," + now.y + "," + now.z,
                    "task", now.taskName + "/" + now.taskPhase,
                    "fall", fmt(now.fallDistance),
                    "in_lava", now.inLava,
                    "air", now.air);
        }
        // 大幅坠落
        if (now.fallDistance > 4.0F && now.fallDistance > prev.fallDistance + 2.0F) {
            BotLog.danger(bot, "diag_falling",
                    "fall", fmt(now.fallDistance),
                    "pos", now.x + "," + now.y + "," + now.z,
                    "on_ground", now.onGround,
                    "task", now.taskName + "/" + now.taskPhase);
        }
        // Y 骤降(疑似掉洞/掉虚空)
        if (prev.y - now.y > 3) {
            BotLog.danger(bot, "diag_y_drop",
                    "from_y", prev.y,
                    "to_y", now.y,
                    "pos", now.x + "," + now.y + "," + now.z,
                    "task", now.taskName + "/" + now.taskPhase);
        }
    }

    private void snapshot(AIPlayerEntity bot, Sample s) {
        BotLog.action(bot, "diag_snapshot",
                // —— 状态 ——
                "pos", s.x + "," + s.y + "," + s.z,
                "hp", fmt(s.health) + "/" + fmt(s.maxHealth),
                "food", s.food,
                "air", s.air,
                "mode", s.mode,
                "on_ground", s.onGround,
                "in_lava", s.inLava,
                "submerged", s.submerged,
                "fall", fmt(s.fallDistance),
                "light", s.light,
                // —— 目标 ——
                "goal", GoalExecutor.INSTANCE.describeActiveGoal(bot),
                "step", GoalExecutor.INSTANCE.describeActiveStep(bot),
                // —— 任务 ——
                "task", s.taskName,
                "task_state", s.taskState,
                "task_phase", s.taskPhase,
                "task_progress", fmt((float) s.taskProgress),
                "path_idle", s.pathIdle,
                "mining_idle", s.miningIdle,
                // —— 周围环境 ——
                "nearby", scanNearby(bot),
                // —— 背包 ——
                "held", s.held,
                "inv", s.inventory);
    }

    // 周围 24 格内生物(打猎/战斗诊断关键):动物数(+最近类型@距离) / 敌怪数(+最近类型@距离)。
    // 仅在富快照(每 SNAPSHOT_INTERVAL)时扫一次,不进每 tick 的 Sample,避免每刻扫实体拖 TPS。
    private static String scanNearby(AIPlayerEntity bot) {
        try {
            CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "diagnostic_nearby");
            Box box = bot.getBoundingBox().expand(24.0D);
            List<LivingEntity> ents = bot.getServerWorld().getEntitiesByClass(
                    LivingEntity.class, box,
                    e -> e.isAlive() && e != bot && ObservableWorldQuery.canObserveEntity(bot, e));
            int animals = 0;
            int hostiles = 0;
            LivingEntity nearAnimal = null;
            LivingEntity nearHostile = null;
            double da = Double.MAX_VALUE;
            double dh = Double.MAX_VALUE;
            for (LivingEntity e : ents) {
                double d = bot.distanceTo(e);
                if (e instanceof HostileEntity) {
                    hostiles++;
                    if (d < dh) {
                        dh = d;
                        nearHostile = e;
                    }
                } else if (e instanceof PassiveEntity) {
                    animals++;
                    if (d < da) {
                        da = d;
                        nearAnimal = e;
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("animals=").append(animals);
            if (nearAnimal != null) {
                sb.append('(').append(typeId(nearAnimal)).append('@').append((int) da).append(')');
            }
            sb.append(" hostiles=").append(hostiles);
            if (nearHostile != null) {
                sb.append('(').append(typeId(nearHostile)).append('@').append((int) dh).append(')');
            }
            return sb.toString();
        } catch (RuntimeException ignored) {
            return "?";
        }
    }

    private static String typeId(LivingEntity e) {
        return Registries.ENTITY_TYPE.getId(e.getType()).getPath();
    }

    private Sample sampleOf(AIPlayerEntity bot) {
        Sample s = new Sample();
        s.name = bot.getGameProfile().getName();
        s.x = (int) Math.floor(bot.getX());
        s.y = (int) Math.floor(bot.getY());
        s.z = (int) Math.floor(bot.getZ());
        s.yaw = bot.getYaw();
        s.health = bot.getHealth();
        s.maxHealth = bot.getMaxHealth();
        s.food = bot.getHungerManager().getFoodLevel();
        s.air = bot.getAir();
        s.onGround = bot.isOnGround();
        s.fallDistance = bot.fallDistance;
        s.alive = bot.isAlive();
        s.removed = bot.isRemoved();
        try {
            s.mode = bot.interactionManager.getGameMode().getName();
        } catch (RuntimeException ignored) {
            s.mode = "?";
        }
        try {
            s.submerged = bot.isSubmergedInWater();
            BlockPos at = bot.getBlockPos();
            s.inLava = bot.getServerWorld().getBlockState(at).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)
                    || bot.getServerWorld().getBlockState(at.down()).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA);
            s.light = bot.getServerWorld().getLightLevel(at);
        } catch (RuntimeException ignored) {
            // 世界访问失败时保持默认,不影响其它字段
        }

        TaskStatus status = TaskManager.INSTANCE.status(bot);
        s.taskName = status.name();
        s.taskState = String.valueOf(status.state());
        s.taskProgress = status.progress();
        s.taskPhase = extractPhase(status.description());

        try {
            s.pathIdle = bot.getActionPack().isPathExecutorIdle();
            s.miningIdle = bot.getActionPack().isMiningIdle();
        } catch (RuntimeException ignored) {
            // ignore
        }

        ItemStack main = bot.getMainHandStack();
        s.held = main.isEmpty() ? "empty" : Registries.ITEM.getId(main.getItem()) + "x" + main.getCount();
        s.inventory = inventorySummary(bot);
        return s;
    }

    private static String inventorySummary(AIPlayerEntity bot) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : bot.getInventory().main) {
            if (!stack.isEmpty()) {
                counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return "empty";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.getKey()).append('x').append(e.getValue());
        }
        return sb.toString();
    }

    private static String extractPhase(String description) {
        if (description == null) {
            return "";
        }
        int idx = description.indexOf("phase=");
        if (idx < 0) {
            return "";
        }
        int end = description.indexOf(' ', idx);
        return end < 0 ? description.substring(idx + 6) : description.substring(idx + 6, end);
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static final class Sample {
        String name = "";
        int x;
        int y;
        int z;
        float yaw;
        float health;
        float maxHealth;
        int food;
        int air;
        boolean onGround;
        float fallDistance;
        boolean alive = true;
        boolean removed;
        String mode = "?";
        boolean submerged;
        boolean inLava;
        int light;
        String taskName = "idle";
        String taskState = "";
        double taskProgress;
        String taskPhase = "";
        boolean pathIdle = true;
        boolean miningIdle = true;
        String held = "empty";
        String inventory = "empty";
    }
}
