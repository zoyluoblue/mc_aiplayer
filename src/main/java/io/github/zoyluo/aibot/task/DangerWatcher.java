package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DangerWatcher {
    public static final DangerWatcher INSTANCE = new DangerWatcher();
    private final Map<UUID, Integer> nextThreatAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextEatAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextResupplyAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextNightAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> observedSleepCompletionTicks = new ConcurrentHashMap<>();
    private final Map<UUID, TrapRecord> trapRecords = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextHuntAttemptTick = new ConcurrentHashMap<>();

    // 第1层 困死退避:逃避类任务(evade/shelter)在同一格反复触发却没脱身,即判"被困",
    // 退避一段时间不再空派、并按间隔节流求助。终结"夜间困坑底每 2 秒 shelter/evade 死循环刷屏"。
    private static final int TRAP_REPEAT_LIMIT = 4;      // 同格反复避险 4 次 → 判被困
    private static final int TRAP_BACKOFF_TICKS = 600;   // 被困后退避 30s 不再空派威胁任务
    private static final int TRAP_HELP_INTERVAL = 1200;  // 求助消息最短间隔 60s(防刷屏)
    private static final int HUNT_FOOD_TARGET = 3;       // 第2层 饥饿链:没食物时主动猎取的生肉数量

    private DangerWatcher() {
    }

    private record TrapRecord(BlockPos pos, int repeatCount, int lastHelpTick) {
    }

    public void scanAll(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            scanBot(server, bot);
        }
    }

    public boolean scanBot(MinecraftServer server, AIPlayerEntity bot) {
        // SAFE-DEAD:死亡的 bot 不再无限派 evade(僵尸循环)。满血复活到地表,清任务/计划,中文告知。
        if (bot.getHealth() <= 0.0F || !bot.isAlive()) {
            AIPlayerManager.INSTANCE.respawnDeadBot(bot);
            TaskManager.INSTANCE.abort(bot);
            io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.clear(bot);
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                    bot.getGameProfile().getName() + " 死亡后已自动复活到地面。");
            return true;
        }
        Optional<Threat> threat = collectTopThreat(bot);
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        if (threat.isPresent()) {
            Threat top = threat.get();
            if (top.severity().ordinal() >= Threat.Severity.MEDIUM.ordinal()
                    && shouldAssignThreatTask(active, top)
                    && canAssignThreatTask(server, bot, top)) {
                Task task = decideCombatOrEvade(bot, top);
                if (trappedBackoff(server, bot, task)) {
                    return true; // 被困:退避并(节流)求助,不再每 2 秒空派 shelter/evade
                }
                if (active.isPresent() && shouldPauseForThreat(active.get(), top, task)) {
                    TaskManager.INSTANCE.pauseFor(bot, "threat: " + top.type());
                }
                TaskManager.INSTANCE.assign(bot, task);
                nextThreatAttemptTick.put(bot.getUuid(), server.getTicks() + threatCooldownTicks(top, task));
                BotLog.danger(bot, "threat_detected",
                        "type", top.type(),
                        "severity", top.severity(),
                        "source", top.pos(),
                        "decision", task.name());
                return true;
            }
        }
        if (maybeResupply(server, bot, active)) {
            return true;
        }
        if (maybeEat(server, bot, active)) {
            return true;
        }
        if (maybeStartNightTask(server, bot, active)) {
            return true;
        }
        if (active.isEmpty() && BrainCoordinator.INSTANCE.maybeWakeForFailureOrGoal(bot)) {
            return true;
        }
        if (active.isEmpty() && TaskManager.INSTANCE.hasPaused(bot)) {
            TaskManager.INSTANCE.resumeFromPause(bot);
            return true;
        }
        return false;
    }

    private boolean maybeResupply(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        if (active.isPresent() && active.get() instanceof ResupplyTask) {
            return true;
        }
        if (active.isPresent() && (active.get() instanceof EvadeTask || active.get() instanceof CombatTask || active.get() instanceof EatTask)) {
            return false;
        }
        int now = server.getTicks();
        if (now < nextResupplyAttemptTick.getOrDefault(bot.getUuid(), 0)) {
            return false;
        }

        ResupplyTask task = null;
        ItemStack mainHand = bot.getMainHandStack();
        if (isNearlyBroken(mainHand)) {
            Item item = mainHand.getItem();
            task = ResupplyTask.tool(item);
        } else {
            AIBotConfig.Survival survival = AIBotConfig.get().survival();
            // 没食物时:周围有猎物 → 让路给 maybeEat 的猎食(野外猎肉比翻箱找小麦可靠,见第2层饥饿链),
            // 周围没猎物才走 ResupplyTask.food()(翻储备箱)。修"饿了反复 resupply 找小麦失败而不去猎肉"。
            if (bot.getHungerManager().getFoodLevel() <= survival.hungerEatThreshold()
                    && InventoryAction.findFoodSlot(bot) < 0
                    && !HuntTask.hasPreyNearby(bot)) {
                task = ResupplyTask.food();
            }
        }

        if (task == null) {
            return false;
        }
        if (active.isPresent()) {
            TaskManager.INSTANCE.pauseFor(bot, "resupply");
        }
        TaskManager.INSTANCE.assign(bot, task);
        nextResupplyAttemptTick.put(bot.getUuid(), now + 200);
        BotLog.danger(bot, "resupply_started", "need", task.describe());
        return true;
    }

    private boolean maybeEat(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        int foodLevel = bot.getHungerManager().getFoodLevel();
        AIBotConfig.Survival survival = AIBotConfig.get().survival();
        if (foodLevel > survival.hungerEatThreshold()) {
            return false;
        }
        if (active.isPresent() && active.get() instanceof EatTask) {
            return true;
        }
        int now = server.getTicks();
        if (now < nextEatAttemptTick.getOrDefault(bot.getUuid(), 0)) {
            return false;
        }
        if (InventoryAction.findFoodSlot(bot) < 0) {
            // 第2层 饥饿链:没有任何食物 → 若周围有可猎动物,主动猎杀获取生肉,而非干等饿死。
            if (huntForFood(server, bot, active)) {
                return true;
            }
            nextEatAttemptTick.put(bot.getUuid(), now + 100);
            return false;
        }

        boolean critical = foodLevel <= survival.hungerCriticalThreshold();
        if (active.isPresent()) {
            if (!critical || active.get() instanceof EvadeTask) {
                return false;
            }
            TaskManager.INSTANCE.pauseFor(bot, "hunger: " + foodLevel);
        }
        TaskManager.INSTANCE.assign(bot, new EatTask());
        nextEatAttemptTick.put(bot.getUuid(), now + 100);
        BotLog.danger(bot, "hunger_eat_started", "food", foodLevel, "critical", critical);
        return true;
    }

    // 第2层 饥饿链:没食物时主动猎食(获取生肉)。仅在不处于威胁应对(evade/combat)时派;周围无猎物则不空派。
    private boolean huntForFood(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        if (active.isPresent()) {
            if (active.get() instanceof HuntTask) {
                return true; // 已在猎食,保持
            }
            if (active.get() instanceof EvadeTask || active.get() instanceof CombatTask) {
                return false; // 正在应对威胁,别打断
            }
        }
        int now = server.getTicks();
        if (now < nextHuntAttemptTick.getOrDefault(bot.getUuid(), 0)) {
            return false;
        }
        if (!HuntTask.hasPreyNearby(bot)) {
            nextHuntAttemptTick.put(bot.getUuid(), now + 200); // 周围没猎物,过会儿再看
            return false;
        }
        if (active.isPresent()) {
            TaskManager.INSTANCE.pauseFor(bot, "hunt_for_food");
        }
        TaskManager.INSTANCE.assign(bot, new HuntTask(HUNT_FOOD_TARGET));
        nextHuntAttemptTick.put(bot.getUuid(), now + 400);
        BotLog.danger(bot, "hunt_for_food_started", "food", bot.getHungerManager().getFoodLevel());
        return true;
    }

    private Task decideCombatOrEvade(AIPlayerEntity bot, Threat threat) {
        AIBotConfig.Combat combat = AIBotConfig.get().combat();
        // combat 困死:连续多次 combat 被 stuck 中止(目标够不到——如僵尸在下方矿洞/墙后)→ 别再站桩等死,改逃跑。
        if (canFight(bot, threat, combat) && !combatStuck(bot)) {
            return new CombatTask(threat.entity().getType(), 1, combat.retreatHp());
        }
        if (!bot.getServerWorld().isDay()
                && threat.type() == Threat.Type.HOSTILE
                && !SleepTask.hasBedAccess(bot)
                && EmergencyShelterTask.hasShelterBlock(bot)) {
            return new EmergencyShelterTask();
        }
        return new EvadeTask(threat);
    }

    // 第1层:困死退避 + 求助。仅针对逃避类(evade/shelter);战斗(canFight→CombatTask)不拦。
    // bot 反复在同一格触发逃避却没移动(被围/困坑底)→ 累加;达阈值即退避(长 cooldown 静默等救援)
    // 并节流向玩家求助,而非每 2 秒空派一次 shelter/evade 刷屏。bot 真在逃(位置变)则计数自然重置。
    private boolean trappedBackoff(MinecraftServer server, AIPlayerEntity bot, Task next) {
        if (!(next instanceof EvadeTask) && !(next instanceof EmergencyShelterTask)) {
            trapRecords.remove(bot.getUuid());
            return false;
        }
        int now = server.getTicks();
        BlockPos here = bot.getBlockPos().toImmutable();
        TrapRecord rec = trapRecords.get(bot.getUuid());
        if (rec == null || !rec.pos().isWithinDistance(here, 2.5D)) {
            trapRecords.put(bot.getUuid(), new TrapRecord(here, 1, 0));
            return false;
        }
        int repeat = rec.repeatCount() + 1;
        if (repeat < TRAP_REPEAT_LIMIT) {
            trapRecords.put(bot.getUuid(), new TrapRecord(rec.pos(), repeat, rec.lastHelpTick()));
            return false;
        }
        nextThreatAttemptTick.put(bot.getUuid(), now + TRAP_BACKOFF_TICKS);
        if (now - rec.lastHelpTick() >= TRAP_HELP_INTERVAL) {
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                    bot.getGameProfile().getName() + " 被困在 (" + here.getX() + "," + here.getY() + "," + here.getZ()
                            + "),反复避险都没能脱身。请把我传送到安全开阔的地面。");
            BotLog.danger(bot, "trapped_backoff", "pos", here.getX() + "," + here.getY() + "," + here.getZ(), "repeat", repeat);
            trapRecords.put(bot.getUuid(), new TrapRecord(here, 0, now));
        } else {
            trapRecords.put(bot.getUuid(), new TrapRecord(here, repeat, rec.lastHelpTick()));
        }
        return true;
    }

    private boolean maybeStartNightTask(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        AIBotConfig.Night night = AIBotConfig.get().night();
        if (!night.autoSleep() || bot.getServerWorld().isDay() || active.isPresent()) {
            return false;
        }
        int now = server.getTicks();
        TaskStatus lastStatus = TaskManager.INSTANCE.status(bot);
        if ("sleep".equals(lastStatus.name()) && lastStatus.state() == TaskState.COMPLETED) {
            Integer observedElapsed = observedSleepCompletionTicks.putIfAbsent(bot.getUuid(), lastStatus.elapsedTicks());
            if (observedElapsed == null || observedElapsed != lastStatus.elapsedTicks()) {
                observedSleepCompletionTicks.put(bot.getUuid(), lastStatus.elapsedTicks());
                nextNightAttemptTick.put(bot.getUuid(), now + 600);
                return false;
            }
        }
        if (now < nextNightAttemptTick.getOrDefault(bot.getUuid(), 0)) {
            return false;
        }
        // 睡觉功能暂时取消(以后再加):夜间不睡床,只在有火把时补光防刷怪。
        Task task;
        if (InventoryAction.countItem(bot, net.minecraft.item.Items.TORCH) > 0) {
            task = new LightAreaTask(8, 8);
        } else {
            nextNightAttemptTick.put(bot.getUuid(), now + 600);
            return false;
        }
        TaskManager.INSTANCE.assign(bot, task);
        nextNightAttemptTick.put(bot.getUuid(), now + 600);
        BotLog.danger(bot, "night_task_started", "task", task.name());
        return true;
    }

    // combat 困死检测:连续 ≥2 次 combat 被 StuckWatcher 中止(stuck:combat),说明目标够不到 → 改逃,别站桩被打死。
    private boolean combatStuck(AIPlayerEntity bot) {
        Optional<TaskManager.FailureRecord> fail = TaskManager.INSTANCE.peekFailure(bot);
        return fail.isPresent()
                && "combat".equals(fail.get().name())
                && fail.get().reason().contains("stuck")
                && fail.get().count() >= 2;
    }

    private boolean canFight(AIPlayerEntity bot, Threat threat, AIBotConfig.Combat combat) {
        if (threat.type() != Threat.Type.HOSTILE || threat.entity() == null || !threat.entity().isAlive()) {
            return false;
        }
        if (bot.getHealth() <= combat.retreatHp()) {
            return false;
        }
        if (threat.entity() instanceof CreeperEntity && bot.distanceTo(threat.entity()) < 6.0F) {
            return false;
        }
        int hostiles = bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(8.0D),
                        entity -> entity instanceof HostileEntity && entity.isAlive())
                .size();
        return hostiles <= combat.maxEnemiesToFight() && EquipAction.bestWeaponSlot(bot).isPresent();
    }

    private static boolean shouldAssignThreatTask(Optional<Task> active, Threat threat) {
        if (active.isEmpty()) {
            return true;
        }
        Task task = active.get();
        if (task instanceof EvadeTask) {
            return false;
        }
        return !(task instanceof CombatTask) || threat.type() == Threat.Type.LOW_HP;
    }

    private static boolean shouldPauseForThreat(Task active, Threat threat, Task nextTask) {
        // 已在战斗/逃跑 → 不二次暂停(让其自行重定向)。
        if (active instanceof CombatTask || active instanceof EvadeTask) {
            return false;
        }
        // FREEZE fix:其它进行中的任务(挖矿/采集/合成…)遇任何威胁一律**暂停保留**,打完/逃完再 resume,
        // 而不是被后续 assign 直接 abort 销毁。旧逻辑对"敌对→战斗"和 LOW_HP 都返回 false=不暂停=销毁当前任务,
        // 导致 GoalExecutor 把它判为 foreign 而整体放弃目标(实测刷怪时挖矿目标被反复放弃、空转发呆)。
        return true;
    }

    private boolean canAssignThreatTask(MinecraftServer server, AIPlayerEntity bot, Threat threat) {
        return server.getTicks() >= nextThreatAttemptTick.getOrDefault(bot.getUuid(), 0);
    }

    private static int threatCooldownTicks(Threat threat, Task task) {
        if (threat.type() == Threat.Type.LOW_HP || threat.severity() == Threat.Severity.HIGH) {
            return 100;
        }
        return task instanceof EvadeTask ? 80 : 40;
    }

    private static boolean isNearlyBroken(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageable()) {
            return false;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return false;
        }
        return max - stack.getDamage() <= max * 0.10D;
    }

    private static Optional<Threat> collectTopThreat(AIPlayerEntity bot) {
        if (bot.getHealth() < 6.0F) {
            return Optional.of(new Threat(Threat.Type.LOW_HP, Threat.Severity.HIGH, null, bot.getBlockPos()));
        }
        Optional<LivingEntity> hostile = bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(8.0D),
                        entity -> entity instanceof HostileEntity && entity.isAlive())
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceTo(bot)));
        if (hostile.isPresent()) {
            return Optional.of(new Threat(Threat.Type.HOSTILE, Threat.Severity.MEDIUM, hostile.get(), hostile.get().getBlockPos()));
        }
        if (bot.isSubmergedInWater() && bot.getAir() < 50) {
            return Optional.of(new Threat(Threat.Type.DROWNING, Threat.Severity.MEDIUM, null, bot.getBlockPos()));
        }
        Optional<BlockPos> lava = BlockPos.stream(bot.getBlockPos().add(-2, -1, -2), bot.getBlockPos().add(2, 1, 2))
                .filter(pos -> {
                    BlockState state = bot.getServerWorld().getBlockState(pos);
                    return state.getFluidState().isIn(FluidTags.LAVA);
                })
                .map(BlockPos::toImmutable)
                .findFirst();
        if (lava.isPresent()) {
            return Optional.of(new Threat(Threat.Type.LAVA, Threat.Severity.HIGH, null, lava.get()));
        }
        if (bot.fallDistance > 5.0F && !bot.isOnGround()) {
            return Optional.of(new Threat(Threat.Type.FALLING, Threat.Severity.LOW, null, bot.getBlockPos()));
        }
        return Optional.empty();
    }
}
