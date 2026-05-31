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

    private DangerWatcher() {
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
            if (bot.getHungerManager().getFoodLevel() <= survival.hungerEatThreshold()
                    && InventoryAction.findFoodSlot(bot) < 0) {
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

    private Task decideCombatOrEvade(AIPlayerEntity bot, Threat threat) {
        AIBotConfig.Combat combat = AIBotConfig.get().combat();
        if (canFight(bot, threat, combat)) {
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
        Task task;
        if (SleepTask.hasBedAccess(bot)) {
            task = new SleepTask();
        } else if (InventoryAction.countItem(bot, net.minecraft.item.Items.TORCH) > 0) {
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
        if (active instanceof CombatTask || active instanceof EvadeTask) {
            return false;
        }
        if (threat.type() == Threat.Type.LOW_HP) {
            return false;
        }
        return !(nextTask instanceof CombatTask);
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
