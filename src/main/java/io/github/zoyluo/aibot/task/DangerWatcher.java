package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.List;
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
    private final Map<UUID, PosRecord> darkStuckRecords = new ConcurrentHashMap<>(); // 规避:困死陷阱检测
    private final Map<UUID, Integer> nextEscapeHelpTick = new ConcurrentHashMap<>();  // 撤离求助节流
    private final Map<UUID, Integer> nextShelterAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, ShelterEpisode> shelterEpisodes = new ConcurrentHashMap<>();

    // 第1层 困死退避:逃避类任务(evade/shelter)在同一格反复触发却没脱身,即判"被困",
    // 退避一段时间不再空派、并按间隔节流求助。终结"夜间困坑底每 2 秒 shelter/evade 死循环刷屏"。
    private static final int TRAP_REPEAT_LIMIT = 4;      // 同格反复避险 4 次 → 判被困
    private static final int TRAP_BACKOFF_TICKS = 600;   // 被困后退避 30s 不再空派威胁任务
    private static final int TRAP_HELP_INTERVAL = 1200;  // 求助消息最短间隔 60s(防刷屏)
    private static final int HUNT_FOOD_TARGET = 3;       // 第2层 饥饿链:没食物时主动猎取的生肉数量
    private static final int DARK_STUCK_TICKS = 160;     // 规避:地下黑暗处静止 8s 判"困死陷阱",撤回地面
    private static final float EMERGENCY_SHELTER_HP = 8.0F; // 夜间怪海:≤4 心+有敌 → 无视冷却立即筑墙保命
    private static final double DROP_RECOVERY_MAX_DISTANCE = 80.0D;
    private static final int DROP_RECOVERY_MAX_VERTICAL_DELTA = 24;
    private static final int SHELTER_RETRY_COOLDOWN = 100;
    private static final double SHELTER_EPISODE_RADIUS = 4.0D;
    private static final double CLOSE_DEFENSIVE_HOSTILE_RADIUS = CombatCore.ATTACK_RANGE + 2.0D;

    private DangerWatcher() {
    }

    public void clear(AIPlayerEntity bot) {
        UUID id = bot.getUuid();
        nextThreatAttemptTick.remove(id);
        nextEatAttemptTick.remove(id);
        nextResupplyAttemptTick.remove(id);
        nextNightAttemptTick.remove(id);
        observedSleepCompletionTicks.remove(id);
        trapRecords.remove(id);
        nextHuntAttemptTick.remove(id);
        darkStuckRecords.remove(id);
        nextEscapeHelpTick.remove(id);
        nextShelterAttemptTick.remove(id);
        shelterEpisodes.remove(id);
    }

    public void clearAll() {
        nextThreatAttemptTick.clear();
        nextEatAttemptTick.clear();
        nextResupplyAttemptTick.clear();
        nextNightAttemptTick.clear();
        observedSleepCompletionTicks.clear();
        trapRecords.clear();
        nextHuntAttemptTick.clear();
        darkStuckRecords.clear();
        nextEscapeHelpTick.clear();
        nextShelterAttemptTick.clear();
        shelterEpisodes.clear();
    }

    private record TrapRecord(BlockPos pos, int repeatCount, int lastHelpTick) {
    }

    private record PosRecord(BlockPos pos, int sinceTick) {
    }

    private record ShelterEpisode(BlockPos anchor,
                                  int terminalTick,
                                  TaskState outcome,
                                  String reason) {
    }

    record DropRecoveryDecision(boolean allowed, String reason) {
    }

    static DropRecoveryDecision dropRecoveryDecision(BlockPos respawn,
                                                       BlockPos death,
                                                       int visibleHostiles,
                                                       boolean dangerous) {
        if (respawn == null || death == null) {
            return new DropRecoveryDecision(false, "missing_position");
        }
        if (dangerous) {
            return new DropRecoveryDecision(false, "known_danger_zone");
        }
        if (visibleHostiles > 0) {
            return new DropRecoveryDecision(false, "hostile_death_site");
        }
        if (Math.abs(respawn.getY() - death.getY()) > DROP_RECOVERY_MAX_VERTICAL_DELTA) {
            return new DropRecoveryDecision(false, "deep_route_without_trail");
        }
        if (!respawn.isWithinDistance(death, DROP_RECOVERY_MAX_DISTANCE)) {
            return new DropRecoveryDecision(false, "route_too_far");
        }
        return new DropRecoveryDecision(true, "short_clear_route");
    }

    public void scanAll(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            scanBot(server, bot);
        }
    }

    public boolean scanBot(MinecraftServer server, AIPlayerEntity bot) {
        // SAFE-DEAD:死亡的 bot 不再无限派 evade(僵尸循环)。满血复活到地表,清任务/计划,中文告知。
        if (bot.getHealth() <= 0.0F || !bot.isAlive()) {
            BlockPos deathPos = bot.getBlockPos();
            long deathTick = server.getTicks();
            int visibleHostilesAtDeath = bot.getServerWorld()
                    .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(8.0D),
                            entity -> entity instanceof HostileEntity && entity.isAlive())
                    .stream()
                    .filter(entity -> ObservableWorldQuery.canObserveEntity(bot, entity))
                    .toList().size();
            AIPlayerManager.INSTANCE.respawnDeadBot(bot);
            // 死亡找回反射:装备掉在死亡点(5 分钟 despawn),真实玩家第一反应就是跑尸。
            // 只有短、浅且死亡现场已清空的路线才自动跑尸。严格生存没有传送；从世界出生点裸体直挖
            // 回深矿既没有可证明入口，又会在掉落消失前再次送死。深矿恢复必须等未来持久化的
            // 可逆入口/trail 合同，当前先 fail-closed，立即恢复原 Mission 从地表重建物资。
            boolean dangerous = io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE
                    .isDanger(bot.getUuid(), deathPos);
            DropRecoveryDecision recovery = dropRecoveryDecision(
                    bot.getBlockPos(), deathPos, visibleHostilesAtDeath, dangerous);
            if (recovery.allowed()) {
                TaskManager.INSTANCE.assign(bot, new RecoverDropsTask(deathPos, deathTick), TaskOrigin.safety("recover_drops"));
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                        bot.getGameProfile().getName() + " 死亡后已复活,正赶回 "
                                + deathPos.toShortString() + " 找回掉落装备。");
            } else {
                BotLog.danger(bot, "drop_recovery_skipped",
                        "death", deathPos.toShortString(),
                        "respawn", bot.getBlockPos().toShortString(),
                        "hostiles", visibleHostilesAtDeath,
                        "reason", recovery.reason());
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                        bot.getGameProfile().getName() + " 死亡后已自动复活到地面。"
                                + "(放弃不安全的跑尸路线:" + recovery.reason() + ")");
            }
            return true;
        }
        Optional<Threat> threat = collectTopThreat(bot);
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        refreshShelterEpisode(bot);
        // 入浆即自救(最高优先,压倒威胁):岩浆每 tick 烧 4,几秒就死。SurvivalGuard 只中断作业、注释说
        // "让位 DangerWatcher 脱困"但从未实现——bot 泡在岩浆里被烧死(real_diamond 下潜挖穿岩浆袋,14/15 步功亏一篑)。
        // 这里补上:身陷岩浆且当前不是逃浆任务 → 立即派 LavaEscapeTask,把命先捞回来。
        if (bot.isInLava() && !(active.isPresent() && active.get() instanceof LavaEscapeTask)) {
            if (active.isPresent()) {
                TaskManager.INSTANCE.pauseFor(bot, "lava_escape");
            }
            TaskManager.INSTANCE.assign(bot, new LavaEscapeTask(), TaskOrigin.safety("lava_escape"));
            BotLog.danger(bot, "lava_escape_start", "pos", bot.getBlockPos().toShortString(),
                    "hp", (int) bot.getHealth());
            return true;
        }
        // CreateObsidianTask deliberately works near a pool and independently enforces dry,
        // standable, non-adjacent work poses. Do not replace that bounded operation with an
        // EvadeTask merely because lava is visible two cells away. Contact/adjacency/fire/low HP
        // fail the task's predicate and continue through the normal emergency path above/below.
        if (threat.isPresent()
                && threat.get().type() == Threat.Type.LAVA
                && active.isPresent()
                && active.get() instanceof CreateObsidianTask obsidianTask
                && obsidianTask.controlsNearbyLava(bot, threat.get().pos())) {
            noteThreatOwned(bot);
            threat = Optional.empty();
        }
        // OreDig owns a bounded branch cursor and rejects observed hazardous directions itself.
        // In a two-block-high mine, generic Evade has no valid open escape goal and used to pause
        // the miner every cooldown without changing the lava-facing branch. Rotate the factual
        // cursor now; actual lava contact still takes the LavaEscape path above.
        if (threat.isPresent()
                && threat.get().type() == Threat.Type.LAVA
                && active.isPresent()
                && active.get() instanceof OreDigTask oreDig
                && oreDig.avoidObservedLava(bot, threat.get().pos())) {
            noteThreatOwned(bot);
            return true;
        }
        // DigDown owns a factual staircase back to its exact entry. Visible lava closes the current
        // descent, but a generic underground Evade has no proven surface destination and can leave
        // the return owner paused forever while the same source remains visible. Let the active
        // owner turn around, or resume the exact paused owner once any prior safety task has ended.
        Task digDownCandidate = active.filter(DigDownTask.class::isInstance)
                .orElseGet(() -> active.isEmpty()
                        ? TaskManager.INSTANCE.peekPaused(bot)
                        .filter(DigDownTask.class::isInstance)
                        .orElse(null)
                        : null);
        boolean pausedDigDownCanResume = active.isEmpty()
                && !TaskManager.INSTANCE.isUserPaused(bot)
                && !bot.getActionPack().hasActiveActions()
                && bot.hurtTime == 0
                && bot.getHealth() > AIBotConfig.get().combat().retreatHp();
        if (threat.isPresent()
                && threat.get().type() == Threat.Type.LAVA
                && digDownCandidate instanceof DigDownTask digDown
                && (active.orElse(null) == digDown || pausedDigDownCanResume)
                && digDown.claimObservedLavaReturn(bot, threat.get().pos())) {
            noteThreatOwned(bot);
            if (active.isEmpty()) {
                TaskManager.INSTANCE.resumeFromPause(bot);
            }
            return true;
        }
        // A shelter is an atomic safety envelope. Let it either prove all nine cells sealed or
        // fail with shelter_unsealable; Combat/Evade/Eat must not preempt it on the next scan and
        // grow a nested pause stack while the same holes are still being closed.
        if (active.isPresent() && (active.get() instanceof EmergencyShelterTask
                || active.get() instanceof MiningBarricadeTask)) {
            return true;
        }
        // A healing EatTask is a short, atomic survival transaction. LOW_HP is expected to remain
        // true throughout the bite, and an already-observed hostile can also remain in range; using
        // either signal to pause Eat again grows a safety-on-safety stack and prevents the food from
        // ever being consumed. Contact lava and drowning are deliberately excluded here and retain
        // their higher-priority handling.
        if (active.isPresent()
                && active.get() instanceof EatTask
                && isHealingEatTransaction(bot)
                && threat.filter(DangerWatcher::isHostilePressure).isPresent()) {
            return true;
        }
        // CombatTask already owns the complete close-contact lifecycle: strike, bounded retreat,
        // food-backed healing, and defensive-leash disengagement. Replacing it with a shelter on
        // the first damage tick is actively unsafe: the hostile can already occupy a wall cell,
        // while the airborne/knocked-back bot gives the shelter a stale origin. Endermen may also
        // teleport briefly outside observable pressure; that is not a safe naked-eating boundary.
        // Let the active combat transaction settle even during that transient empty scan. Lava and
        // drowning have already been handled above.
        if (active.isPresent()
                && active.get() instanceof CombatTask
                && (threat.isEmpty()
                || threat.get().type() == Threat.Type.HOSTILE
                || threat.get().type() == Threat.Type.LOW_HP)) {
            return true;
        }
        // A controlled strip mine already owns a real rear corridor. Seal the branch before any
        // generic combat/evade logic can pillar-jump into the cave or overwrite its checkpoint.
        // A paused OreDig is also eligible because SurvivalGuard now preserves mission instances.
        Task miningCandidate = active.filter(OreDigTask.class::isInstance)
                .orElseGet(() -> TaskManager.INSTANCE.peekPaused(bot)
                        .filter(OreDigTask.class::isInstance)
                        .orElse(null));
        if (threat.isPresent()
                && threat.get().type() == Threat.Type.HOSTILE
                && miningCandidate instanceof OreDigTask oreDig
                && MiningBarricadeTask.hasMaterialsForOpenGate(bot)) {
            Optional<MiningBarricadeTask> barricade =
                    oreDig.prepareHostileBarricade(bot, threat.get().pos());
            if (barricade.isPresent()) {
                if (active.orElse(null) == oreDig) {
                    TaskManager.INSTANCE.pauseFor(bot, "mining_hostile_barricade");
                }
                TaskManager.INSTANCE.assign(bot, barricade.get(),
                        TaskOrigin.safety("mining_hostile_barricade"));
                BotLog.danger(bot, "mining_hostile_barricade_started",
                        "source", threat.get().pos(),
                        "paused", oreDig.name());
                return true;
            }
        }
        // 夜间怪海保命(治死亡螺旋):濒死(≤4 心)+ 有敌 + 当前没在筑墙 → 立即筑墙自保,**无视威胁冷却**。
        // 元凶:combat 完(~100t 没杀光)→进 100t 冷却→gather 恢复挨打→guard 中止→冷却没过 shelter
        // 派不出→再挨打到死(real_diamond 三种子全栽这,bot 会打但打不赢多怪围殴)。保命压倒一切:
        // 围一圈墙把自己封进去,怪够不到,血止住,熬过去。需有可放方块(有原木/圆石即可)。
        // 地下无处可逃:深处隧道挖矿被怪贴脸时,evade 逃向 20 格外的点多半落在实心石里→假逃磨血(已在
        // EvadeTask 修为 fail)。这里主动兜底:头顶不见天(地下)且正在挨打(hurtTime>0)→ 不等濒死直接封墙,
        // 因为地下逃跑无效、拖到 ≤4 心才入土往往已死(real_diamond 深层挖矿 evade/guard_low_hp 送命主因)。
        boolean cannotFlee = !bot.getServerWorld().isSkyVisible(bot.getBlockPos());
        boolean entombNow = bot.getHealth() <= EMERGENCY_SHELTER_HP || (cannotFlee && bot.hurtTime > 0);
        // 死亡螺旋修复(self-inflicted 倒挂):血<6 时 top 会变为 LOW_HP；即使 Threat 保留了
        // hostile entity，单看 type==HOSTILE 仍会在最该入土时失效。优先使用实体身份，并保留
        // 一次近处可达 hostile 扫描作为防御性兜底；不改 DROWNING/LAVA 的优先级。
        boolean topIsHostile = threat.isPresent() && threat.get().type() == Threat.Type.HOSTILE;
        boolean lowHpUnderHostile = threat.isPresent() && threat.get().type() == Threat.Type.LOW_HP
                && (isHostileBacked(threat.get()) || hasReachableHostile(bot));
        if ((topIsHostile || lowHpUnderHostile)
                // A Creeper shelter is safe only while sealed; reopening the owned door gives the
                // same explosive contact threat a point-blank line of sight. Creepers therefore
                // stay on the escape path even at low HP. Controlled strip mines already get the
                // stronger rear-corridor MiningBarricade branch above.
                && !isCreeperThreat(threat.get())
                && entombNow
                && EmergencyShelterTask.hasMaterialsForCurrentPose(bot)
                && canAttemptShelter(server, bot)) {
            if (active.isPresent()) {
                markThreatDirectionAvoided(active.get(), bot, threat.get());
                // Preserve mission/background work, but replace an active safety task in place.
                // Pausing Evade (also SAFETY) here used to add another frame on every shelter
                // retry, eventually burying the mission under an unbounded safety stack.
                if (shouldPreserveActiveWork(bot)) {
                    TaskManager.INSTANCE.pauseFor(bot, "emergency_entomb");
                }
            }
            TaskManager.INSTANCE.assign(bot, new EmergencyShelterTask(), TaskOrigin.safety("emergency_entomb"));
            noteShelterAttempt(server, bot);
            BotLog.danger(bot, "emergency_entomb", "hp", (int) bot.getHealth(),
                    "underground", cannotFlee, "threat", threat.get().type());
            return true;
        }
        if (threat.isPresent()) {
            Threat top = threat.get();
            if (top.severity().ordinal() >= Threat.Severity.MEDIUM.ordinal()
                    && shouldAssignThreatTask(active, top)
                    && canAssignThreatTask(server, bot, top)) {
                Task task = decideCombatOrEvade(bot, top, canAttemptShelter(server, bot));
                boolean trapped = trappedBackoff(server, bot, task);
                if (trapped) {
                    boolean criticalHostile = isHostileBacked(top)
                            && (top.type() == Threat.Type.LOW_HP
                            || top.severity() == Threat.Severity.HIGH);
                    if (!criticalHostile || hasActiveHostileDefenseOwner(bot)) {
                        return true;
                    }
                    // A hard backoff may throttle ordinary churn, but it cannot claim a live
                    // critical hostile while leaving only paused mission work. Fall through and
                    // assign the already-decided shelter/evade owner; the normal high-severity
                    // cooldown below replaces the longer diagnostic backoff.
                }
                if (active.isPresent()
                        && shouldPauseForThreat(active.get(), top, task)
                        && shouldPreserveActiveWork(bot)) {
                    if (task instanceof EmergencyShelterTask) {
                        markThreatDirectionAvoided(active.get(), bot, top);
                    }
                    TaskManager.INSTANCE.pauseFor(bot, "threat: " + top.type());
                }
                TaskManager.INSTANCE.assign(bot, task, TaskOrigin.safety("threat:" + top.type()));
                if (task instanceof EmergencyShelterTask) {
                    noteShelterAttempt(server, bot);
                }
                nextThreatAttemptTick.put(bot.getUuid(), server.getTicks() + threatCooldownTicks(top, task));
                BotLog.danger(bot, "threat_detected",
                        "type", top.type(),
                        "severity", top.severity(),
                        "source", top.pos(),
                        "decision", task.name());
                return true;
            }
        }
        // 规避加固(保命兜底):困死在地下黑暗处 → 撤回地面,优先于补给/进食。
        if (maybeEscapeDarkTrap(server, bot, active)) {
            return true;
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
        if (maybeLightDarkArea(server, bot, active)) {
            return true;
        }
        if (active.isEmpty()
                && !TaskManager.INSTANCE.isUserPaused(bot)
                && !bot.getActionPack().hasActiveActions()
                && BrainCoordinator.INSTANCE.maybeWakeForFailureOrGoal(bot)) {
            return true;
        }
        if (active.isEmpty()
                && !bot.getActionPack().hasActiveActions()
                && TaskManager.INSTANCE.hasPaused(bot)
                && canResumePausedWork(bot, threat)) {
            TaskManager.INSTANCE.resumeFromPause(bot);
            return true;
        }
        return false;
    }

    static boolean canResumePausedWork(AIPlayerEntity bot, Optional<Threat> threat) {
        return threat.isEmpty()
                && bot.hurtTime == 0
                && bot.getHealth() > AIBotConfig.get().combat().retreatHp();
    }

    private boolean maybeResupply(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        boolean criticalStarvation = bot.getHungerManager().getFoodLevel()
                <= AIBotConfig.get().survival().hungerCriticalThreshold();
        if (TaskManager.INSTANCE.isUserPaused(bot) && !criticalStarvation) {
            return false;
        }
        if (bot.getActionPack().hasActiveActions()) {
            return false; // 普通补给不抢占 action-only replacement；紧急生存分支在本方法之前处理。
        }
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
        Optional<Task> paused = active.isEmpty()
                ? TaskManager.INSTANCE.peekPaused(bot) : Optional.empty();
        // These mining tasks own an exact break/pickup/return transaction. The generic ten-percent
        // threshold is intentionally much wider (a diamond pick at raw 33 is already below it),
        // while ToolTier deliberately reports a raw-one pick as NONE. Resolve ownership separately
        // from the held slot: combat may leave a sword selected when the miner resumes. The owner
        // must first settle an already-legal break, then either yield at its service boundary or
        // report its own typed durability failure before opening a new one. DigDown additionally
        // owns the exact-return path for that failure. DescendToY does not yet expose the same typed
        // durability contract and therefore remains under generic resupply.
        boolean activeTaskOwnsMiningTransaction = active
                .filter(DangerWatcher::ownsMiningPickTransaction).isPresent();
        boolean pausedTaskOwnsMiningTransaction = paused
                .filter(DangerWatcher::ownsMiningPickTransaction).isPresent();
        boolean pausedDigDownOwnsReturnDebt = paused
                .filter(DigDownTask.class::isInstance).isPresent();
        // Crafting never spends or depends on the held mining tool. In particular, the final rare
        // bootstrap deliberately crafts fresh replacements while an old nearly-broken pick may
        // still be selected. Generic tool resupply here would pause that atomic craft and consume
        // the sealed stick/stone inputs before the five-pick hand-off is complete.
        boolean taskDoesNotUseHeldTool = active.filter(CraftTask.class::isInstance).isPresent();
        if (isNearlyBroken(mainHand)
                && mainHand.getItem() instanceof PickaxeItem
                && pausedDigDownOwnsReturnDebt
                && !taskDoesNotUseHeldTool) {
            // DigDown alone needs a generic tool to pay an exact physical RETURN debt after a
            // safety displacement. Service it from carried materials only; travelling to a
            // remembered base would compound that displacement. MiningService/CreateObsidian/
            // OreDig retain their own exact-budget or typed, persisted service boundaries instead
            // of spending materials through this generic ten-percent threshold.
            task = ResupplyTask.toolInPlace(mainHand.getItem());
        } else if (isNearlyBroken(mainHand)
                && !activeTaskOwnsMiningTransaction
                && !pausedTaskOwnsMiningTransaction
                && !taskDoesNotUseHeldTool) {
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
        TaskManager.INSTANCE.assign(bot, task, criticalStarvation
                ? TaskOrigin.safety("critical_resupply")
                : TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "resupply"));
        nextResupplyAttemptTick.put(bot.getUuid(), now + 200);
        BotLog.danger(bot, "resupply_started", "need", task.describe());
        return true;
    }

    private static boolean ownsMiningPickTransaction(Task task) {
        return task instanceof MiningServiceTask
                || task instanceof CreateObsidianTask
                || task instanceof OreDigTask
                || task instanceof DigDownTask;
    }

    private boolean maybeEat(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        int foodLevel = bot.getHungerManager().getFoodLevel();
        AIBotConfig.Survival survival = AIBotConfig.get().survival();
        boolean healingEmergency = isHealingEatTransaction(bot);
        if (foodLevel > survival.hungerEatThreshold() && !healingEmergency) {
            return false;
        }
        boolean critical = foodLevel <= survival.hungerCriticalThreshold();
        boolean urgent = critical || healingEmergency;
        if (TaskManager.INSTANCE.isUserPaused(bot) && !urgent) {
            return false;
        }
        if (bot.getActionPack().hasActiveActions() && !urgent) {
            return false; // 非紧急进食等动作完成；critical starvation 仍可抢占保命。
        }
        if (active.isPresent() && active.get() instanceof EatTask) {
            return true;
        }
        // Admission and continuation are deliberately different boundaries. Once a physical bite
        // has started, the earlier atomic-Eat branch lets it settle without growing another safety
        // frame. Before assignment, however, eating in the open beside an already-observed hostile
        // (or immediately after a hit) is never safe. A sealed shelter owns its own internal EatTask
        // and therefore does not pass through this unprotected admission gate.
        if (hasNakedEatHostilePressure(bot)) {
            return false;
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

        if (active.isPresent()) {
            if (!urgent || active.get() instanceof EvadeTask
                    || active.get() instanceof CombatTask) {
                return false;
            }
            TaskManager.INSTANCE.pauseFor(bot, healingEmergency
                    ? "low_health_heal: " + bot.getHealth()
                    : "hunger: " + foodLevel);
        }
        TaskManager.INSTANCE.assign(bot, new EatTask(), urgent
                ? TaskOrigin.safety(healingEmergency ? "low_health_heal" : "critical_hunger")
                : TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "eat"));
        nextEatAttemptTick.put(bot.getUuid(), now + 100);
        BotLog.danger(bot, "hunger_eat_started", "food", foodLevel, "critical", critical,
                "healing", healingEmergency, "hp", (int) bot.getHealth());
        return true;
    }

    // 第2层 饥饿链:没食物时主动猎食(获取生肉)。仅在不处于威胁应对(evade/combat)时派;周围无猎物则不空派。
    private boolean huntForFood(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        boolean critical = bot.getHungerManager().getFoodLevel()
                <= AIBotConfig.get().survival().hungerCriticalThreshold();
        if (TaskManager.INSTANCE.isUserPaused(bot) && !critical) {
            return false;
        }
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
        TaskManager.INSTANCE.assign(bot, new HuntTask(HUNT_FOOD_TARGET), critical
                ? TaskOrigin.safety("critical_hunt_for_food")
                : TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "hunt_for_food"));
        nextHuntAttemptTick.put(bot.getUuid(), now + 400);
        BotLog.danger(bot, "hunt_for_food_started", "food", bot.getHungerManager().getFoodLevel());
        return true;
    }

    private Task decideCombatOrEvade(AIPlayerEntity bot,
                                     Threat threat,
                                     boolean shelterAllowed) {
        AIBotConfig.Combat combat = AIBotConfig.get().combat();
        // These mobs require a dedicated tactic, never the generic defensive melee loop. Keep this
        // before every underground/night shelter admission so broad fallbacks cannot override the
        // shared policy. The emergency low-health entomb branch above still has first priority.
        if (isMeleeForbiddenThreat(threat)) {
            return new EvadeTask(threat);
        }
        boolean underground = !bot.getServerWorld().isSkyVisible(bot.getBlockPos());
        boolean hostileThreat = isHostileBacked(threat);
        boolean lowHpHostile = threat.type() == Threat.Type.LOW_HP && hostileThreat;
        // A completed/failed shelter owns this local hostile episode until pressure clears or the
        // bot genuinely relocates. When that fixed-anchor option is locked, a single close
        // non-Creeper must not fall through to naked healing merely because canFight's ordinary
        // cost/benefit gate rejects low HP. Defensive Combat starts in RETREAT, counterattacks only
        // if boxed in, and owns the later safe-heal boundary.
        if (!shelterAllowed && shouldDefensivelyFightClosePressure(bot, threat)) {
            return CombatTask.defensive(threat.entity(), combat.retreatHp(), bot.getBlockPos());
        }
        if (underground
                && hostileThreat
                && shelterAllowed
                && EmergencyShelterTask.hasMaterialsForCurrentPose(bot)
                && (lowHpHostile || requiresUndergroundShelter(bot, threat, combat))) {
            return new EmergencyShelterTask();
        }
        // combat 困死:连续多次 combat 被 stuck 中止(目标够不到——如僵尸在下方矿洞/墙后)→ 别再站桩等死,改逃跑。
        if (canFight(bot, threat, combat) && !combatStuck(bot)) {
            // Safety combat defends the interrupted work site. It binds the observed entity and
            // cannot turn into an open-ended hunt by reacquiring another mob of the same type.
            return CombatTask.defensive(threat.entity(), combat.retreatHp(), bot.getBlockPos());
        }
        if (!bot.getServerWorld().isDay()
                && threat.type() == Threat.Type.HOSTILE
                && !SleepTask.hasBedAccess(bot)
                && shelterAllowed
                && EmergencyShelterTask.hasMaterialsForCurrentPose(bot)) {
            return new EmergencyShelterTask();
        }
        return new EvadeTask(threat);
    }

    private static boolean shouldDefensivelyFightClosePressure(AIPlayerEntity bot,
                                                                Threat threat) {
        return isHostileBacked(threat)
                && !isMeleeForbiddenThreat(threat)
                && EquipAction.bestWeaponSlot(bot).isPresent()
                && visibleHostileCount(bot) == 1
                && bot.distanceTo(threat.entity()) < CLOSE_DEFENSIVE_HOSTILE_RADIUS;
    }

    private static boolean requiresUndergroundShelter(AIPlayerEntity bot,
                                                       Threat threat,
                                                       AIBotConfig.Combat combat) {
        BlockPos here = bot.getBlockPos();
        BlockPos source = threat.entity().getBlockPos();
        double dx = source.getX() - here.getX();
        double dz = source.getZ() - here.getZ();
        boolean belowDefenseFloor = source.getY() < here.getY() - 2;
        boolean beyondDefenseRadius = dx * dx + dz * dz > 64.0D;
        return belowDefenseFloor
                || beyondDefenseRadius
                || visibleHostileCount(bot) > combat.maxEnemiesToFight();
    }

    private boolean canAttemptShelter(MinecraftServer server, AIPlayerEntity bot) {
        return server.getTicks() >= nextShelterAttemptTick.getOrDefault(bot.getUuid(), 0)
                && !shelterEpisodeActive(bot)
                && EmergencyShelterTask.canStartAtCurrentPose(bot);
    }

    boolean shelterEpisodeActive(AIPlayerEntity bot) {
        return shelterEpisodes.containsKey(bot.getUuid());
    }

    private void noteShelterAttempt(MinecraftServer server, AIPlayerEntity bot) {
        nextShelterAttemptTick.put(bot.getUuid(), server.getTicks() + SHELTER_RETRY_COOLDOWN);
    }

    /** Called by the fixed-anchor owner at its exact terminal boundary. */
    void noteShelterTerminal(AIPlayerEntity bot,
                             BlockPos anchor,
                             TaskState outcome,
                             String reason) {
        if (anchor == null || bot.getServer() == null) {
            return;
        }
        int now = bot.getServer().getTicks();
        BlockPos fixedAnchor = anchor.toImmutable();
        shelterEpisodes.put(bot.getUuid(), new ShelterEpisode(
                fixedAnchor, now, outcome, reason == null ? "" : reason));
        // Assignment-time cooldowns can expire while a real shelter is still building/holding.
        // Start the retry clock at terminal instead; the episode latch below is stronger while the
        // same local hostile pressure remains continuous.
        nextShelterAttemptTick.put(bot.getUuid(), now + SHELTER_RETRY_COOLDOWN);
        // A shelter terminal is a new safety boundary, not another failed scheduler attempt. Let the
        // next scan immediately choose the episode-safe fallback (normally defensive Combat) rather
        // than waiting out the assignment-time threat cooldown with no active protection.
        nextThreatAttemptTick.remove(bot.getUuid());
        BotLog.danger(bot, "shelter_episode_terminal",
                "anchor", fixedAnchor.toShortString(),
                "outcome", outcome,
                "reason", reason == null ? "" : reason);
    }

    private void refreshShelterEpisode(AIPlayerEntity bot) {
        ShelterEpisode episode = shelterEpisodes.get(bot.getUuid());
        if (episode == null) {
            return;
        }
        boolean sameSite = episode.anchor().isWithinDistance(
                bot.getBlockPos(), SHELTER_EPISODE_RADIUS);
        boolean hostileContinues = !observableActiveHostilePressure(bot).isEmpty();
        if (sameSite && hostileContinues) {
            return;
        }
        shelterEpisodes.remove(bot.getUuid(), episode);
        // Keep the terminal-time cooldown even when LOS flickers or the bot crosses the local
        // episode radius. Removing both latches made a failed shelter immediately eligible again.
        BotLog.danger(bot, "shelter_episode_reset",
                "anchor", episode.anchor().toShortString(),
                "reason", sameSite ? "hostile_cleared" : "site_relocated");
    }

    private static void markThreatDirectionAvoided(Task interrupted,
                                                   AIPlayerEntity bot,
                                                   Threat threat) {
        if (interrupted instanceof DescendToYTask descend) {
            descend.avoidCurrentDescentDirection(bot, threat.pos());
        }
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
        // 绝境反击:困住(逃跑反复原地)且正在挨打——退避=站着等死(real_iron 实测:洞穴 13 蛛贴脸,
        // evade 目标算出原地 1t 完成,backoff 停发威胁任务后被围殴致死)。canFight 的武器/数量闸
        // 是"打得划算吗"的算计,绝境没得算:空手也开打,伤害换活命窗口。
        if (repeat >= 2 && bot.hurtTime > 0) {
            trapRecords.remove(bot.getUuid());
            var hostile = bot.getServerWorld().getEntitiesByClass(
                    net.minecraft.entity.mob.HostileEntity.class,
                    bot.getBoundingBox().expand(4.0D), e -> e.isAlive())
                    .stream()
                    .filter(e -> isActiveHostileThreat(bot, e))
                    .filter(e -> !CombatCore.isMeleeForbiddenThreat(e))
                    .filter(e -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveEntity(bot, e))
                    .findFirst().orElse(null);
            if (hostile != null) {
                BotLog.danger(bot, "trapped_fight_back", "target", hostile.getType().toString());
                if (TaskManager.INSTANCE.getActive(bot).isPresent()
                        && shouldPreserveActiveWork(bot)) {
                    TaskManager.INSTANCE.pauseFor(bot, "trapped_fight_back");
                }
                TaskManager.INSTANCE.assign(bot, new CombatTask(hostile.getType(), 1, 0.0F),
                        TaskOrigin.safety("trapped_fight_back"));
                return true;
            }
        }
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
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            return false;
        }
        AIBotConfig.Night night = AIBotConfig.get().night();
        if (!night.autoSleep()
                || bot.getServerWorld().isDay()
                || active.isPresent()
                || bot.getActionPack().hasActiveActions()) {
            return false;
        }
        // 目标计划进行中(步骤间隙 active 短暂为空)不插夜间照明:它是 foreign task,会让 GoalExecutor
        // 放弃整个目标(与 maybeLightDarkArea 同款守护——实测 real_iron_bulk 夜里挖到 91/100 时,步骤间隙被
        // 夜间点灯抢走 → goal_abandoned → 卡 light_area churn 永不完成)。深矿照明由 GoalPlanner 火把前置负责。
        if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
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
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "night_task"));
        nextNightAttemptTick.put(bot.getUuid(), now + 600);
        BotLog.danger(bot, "night_task_started", "task", task.name());
        return true;
    }

    // 规避加固:地下/黑暗处(方块光照<8)只要 idle 且有火把,就先点亮——从源头减少怪物在身边刷新。
    // 不限夜晚(地下白天 light=0 同样刷怪)。仅 active 为空(idle/目标步骤间隙)时派,避免打断挖矿。
    private boolean maybeLightDarkArea(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            return false;
        }
        if (active.isPresent() || bot.getActionPack().hasActiveActions()) {
            return false;
        }
        // 目标计划进行中(步骤间隙 active 会短暂为空)不要插照明:它是 foreign task,会让 GoalExecutor
        // 放弃整个目标(实测:金锭挖到 raw_gold、熔炼前的空隙被照明抢走 → goal_abandoned、没熔炼 → 无金锭)。
        // 深矿照明由 GoalPlanner 的挖矿前置(火把步)负责,不靠这个 idle 反射。
        if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return false;
        }
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        if (world.isSkyVisible(feet)
                || world.getLightLevel(net.minecraft.world.LightType.BLOCK, feet) >= 8) {
            return false;
        }
        if (InventoryAction.countItem(bot, net.minecraft.item.Items.TORCH) <= 0) {
            return false; // 没火把点不了——由 GoalPlanner 挖深矿前置备火把兜底
        }
        int now = server.getTicks();
        if (now < nextNightAttemptTick.getOrDefault(bot.getUuid(), 0)) {
            return false; // 复用夜间节流,避免每次扫描都派
        }
        TaskManager.INSTANCE.assign(bot, new LightAreaTask(8, 8),
                TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "dark_area_light"));
        nextNightAttemptTick.put(bot.getUuid(), now + 600);
        BotLog.danger(bot, "dark_area_lit",
                "light", world.getLightLevel(net.minecraft.world.LightType.BLOCK, feet));
        return true;
    }

    // 规避加固(保命兜底):bot 卡在"地下 + 黑暗"处 = 困死陷阱(随时被刷怪秒杀)。只盯移动类(move)卡住
    // 与 idle 静止——挖矿/熔炼等有各自看门狗或属合理静止,先让它们 fail。检测到困死就 teleport 撤回
    // 地面 + 清当前目标 + 求助(节流)。牺牲当次目标换保命;回地面后大脑可重试(届时已备火把更安全)。
    private boolean maybeEscapeDarkTrap(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        // isWaiting=任务自报"原地作业是正常态":MoveTask 挖掘式直行破硬石时一站好几秒,
        // 黑暗+同格被误判困死、被'救'上地面任务报废(nav 套件画布后实测两连 aborted)。
        if (active.isPresent() && (!"move".equals(active.get().name()) || active.get().isWaiting())) {
            darkStuckRecords.remove(bot.getUuid());
            return false;
        }
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        boolean darkUnderground = !world.isSkyVisible(feet)
                && world.getLightLevel(net.minecraft.world.LightType.BLOCK, feet) < 8;
        if (!darkUnderground) {
            darkStuckRecords.remove(bot.getUuid());
            return false;
        }
        int now = server.getTicks();
        PosRecord rec = darkStuckRecords.get(bot.getUuid());
        if (rec == null || !rec.pos().equals(feet)) {
            darkStuckRecords.put(bot.getUuid(), new PosRecord(feet, now));
            return false;
        }
        if (now - rec.sinceTick() < DARK_STUCK_TICKS) {
            return false; // 还没卡够久
        }
        darkStuckRecords.remove(bot.getUuid());
        if (!escapeToSurface(bot)) {
            return false; // 上方没有露天可站点(极少),交还其它逻辑
        }
        TaskManager.INSTANCE.abort(bot);
        // 问题4:不再 clear 目标——撤回地面后保留挖钻石目标,GoalExecutor 会重规划/重试当前步继续
        //(abort 当前困住的 task → handleStepFailure 重规划;bot 在地面、环境变了不再困)。实测:旧逻辑撤回后把任务忘了。
        BotLog.danger(bot, "dark_trap_escape",
                "from", feet.getX() + "," + feet.getY() + "," + feet.getZ());
        if (now >= nextEscapeHelpTick.getOrDefault(bot.getUuid(), 0)) {
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                    bot.getGameProfile().getName() + " 被困在黑暗矿洞太久、有被刷怪秒杀的风险,已撤回地面,稍后继续未完成的任务。");
            nextEscapeHelpTick.put(bot.getUuid(), now + TRAP_HELP_INTERVAL);
        }
        return true;
    }

    // teleport 上浮到正上方最近的露天可站点(保命兜底,清 fallDistance)。
    private boolean escapeToSurface(AIPlayerEntity bot) {
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        int top = world.getBottomY() + world.getHeight();
        for (int dy = 1; feet.getY() + dy < top - 1 && dy <= 120; dy++) {
            BlockPos cand = feet.up(dy);
            if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(world, cand)
                    && world.isSkyVisible(cand)) {
                return io.github.zoyluo.aibot.mode.CapabilityRuntime.run(
                        bot, io.github.zoyluo.aibot.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                        "danger_dark_trap_surface", () -> {
                            bot.getActionPack().stopAll();
                            bot.teleport(world, cand.getX() + 0.5D, cand.getY(), cand.getZ() + 0.5D,
                                    java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
                        });
            }
        }
        return false;
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
        if (CombatCore.isMeleeForbiddenThreat(threat.entity())) {
            return false;
        }
        int hostiles = visibleHostileCount(bot);
        return hostiles <= combat.maxEnemiesToFight() && EquipAction.bestWeaponSlot(bot).isPresent();
    }

    private static int visibleHostileCount(AIPlayerEntity bot) {
        return observableActiveHostilePressure(bot).size();
    }

    /**
     * Starting a bite is unsafe when pressure already exists, even if the ordinary threat
     * scheduler is still inside its retry cooldown. A close observed hostile does not require LOS:
     * one that just rounded a tunnel corner can reopen contact before an unprotected EatTask can
     * finish. The wider ranged envelope does require factual LOS. Continuation is handled
     * separately by the atomic-Eat branch near the top of
     * {@link #scanBot(MinecraftServer, AIPlayerEntity)}.
     */
    private static boolean hasNakedEatHostilePressure(AIPlayerEntity bot) {
        return bot.hurtTime > 0
                || !observableActiveHostilePressure(bot).isEmpty();
    }

    private static List<LivingEntity> observableActiveHostilePressure(AIPlayerEntity bot) {
        return bot.getServerWorld()
                .getEntitiesByClass(
                        LivingEntity.class,
                        bot.getBoundingBox().expand(CombatCore.hostilePressureScanRange()),
                        entity -> isActiveHostileThreat(bot, entity)
                                && ObservableWorldQuery.canObserveEntity(bot, entity)
                                && CombatCore.isWithinHostilePressureEnvelope(bot, entity));
    }

    /**
     * Some mobs are implemented as {@link HostileEntity} without being unconditionally hostile.
     * An unprovoked Enderman can stand in a cave indefinitely and must not pause a survival
     * mission or trigger a shelter that changes the local fluid boundary. Once it is angry or has
     * selected this bot as its target, it is treated exactly like every other hostile mob.
     */
    static boolean isActiveHostileThreat(AIPlayerEntity bot, LivingEntity entity) {
        if (!(entity instanceof HostileEntity) || !entity.isAlive()) {
            return false;
        }
        if (entity instanceof EndermanEntity enderman) {
            // isAngry() is only a broad tracked flag: an Enderman targeting another player or mob
            // also sets it. shouldAngerAt() binds persistent/universal anger to this exact bot and
            // remains factual if teleportation temporarily clears the live target reference.
            return enderman.getTarget() == bot
                    || enderman.shouldAngerAt(bot, bot.getServerWorld());
        }
        return true;
    }

    private static boolean shouldAssignThreatTask(Optional<Task> active, Threat threat) {
        if (active.isEmpty()) {
            return true;
        }
        Task task = active.get();
        if (task instanceof EvadeTask) {
            return false;
        }
        return !(task instanceof CombatTask);
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

    /**
     * Safety work is a replaceable owner, never another resumable mission frame. Unknown legacy
     * origins remain preservable so a missing origin cannot silently destroy user work.
     */
    private static boolean shouldPreserveActiveWork(AIPlayerEntity bot) {
        return TaskManager.INSTANCE.activeOrigin(bot)
                .map(origin -> !origin.safety())
                .orElse(true);
    }

    /**
     * A hard trapped backoff may stand down only behind an owner that is physically defending
     * against hostile pressure. SAFETY is an authority class, not proof of that property:
     * critical hunt/resupply and future safety transactions must still be replaced by real
     * hostile defense.
     */
    static boolean hasActiveHostileDefenseOwner(AIPlayerEntity bot) {
        return TaskManager.INSTANCE.getActive(bot)
                .map(task -> task instanceof EvadeTask
                        || task instanceof CombatTask
                        || task instanceof EmergencyShelterTask
                        || task instanceof MiningBarricadeTask)
                .orElse(false);
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

    private static boolean isHealingEatTransaction(AIPlayerEntity bot) {
        return bot.getHealth() <= AIBotConfig.get().combat().retreatHp()
                && bot.getHungerManager().getFoodLevel() < 20
                && InventoryAction.findFoodSlot(bot) >= 0;
    }

    private static boolean isHostilePressure(Threat threat) {
        return threat.type() == Threat.Type.LOW_HP || threat.type() == Threat.Type.HOSTILE;
    }

    private static boolean isHostileBacked(Threat threat) {
        return isHostilePressure(threat)
                && threat.entity() instanceof HostileEntity
                && threat.entity().isAlive();
    }

    private static boolean isCreeperThreat(Threat threat) {
        return threat.entity() instanceof CreeperEntity;
    }

    private static boolean isMeleeForbiddenThreat(Threat threat) {
        return threat.entity() != null
                && CombatCore.isMeleeForbiddenThreat(threat.entity());
    }

    /** A completed escape is a new safety boundary; its assignment-time debounce must not linger. */
    void noteEvadeCompleted(AIPlayerEntity bot) {
        nextThreatAttemptTick.remove(bot.getUuid());
    }

    /** Task-owned safety progress starts a new boundary; stale generic backoff must not leak on. */
    private void noteThreatOwned(AIPlayerEntity bot) {
        UUID id = bot.getUuid();
        nextThreatAttemptTick.remove(id);
        trapRecords.remove(id);
    }

    private static Optional<Threat> collectTopThreat(AIPlayerEntity bot) {
        // Close threats use the original ten-block envelope. A ranged attacker remains pressure
        // through twenty blocks only with factual LOS, matching naked-Eat admission and secondary
        // combat settlement. Sort the shared pressure set before choosing the top threat.
        List<LivingEntity> hostiles = observableActiveHostilePressure(bot);
        // Explosive pressure cannot be hidden behind a closer ordinary mob. A strict obsidian run
        // resumed its water mission while a Creeper was still visible at fifteen blocks; sorting
        // Creepers first keeps every shelter/combat branch below aligned with the no-melee policy.
        hostiles.sort(Comparator
                .comparing((LivingEntity mob) -> !(mob instanceof CreeperEntity))
                .thenComparingDouble(bot::distanceTo));
        for (LivingEntity mob : hostiles) {
            if (!canReachThreat(bot, mob)) {
                continue; // 被方块阻隔,够不到 bot → 不算威胁
            }
            // Low HP is a combat modifier, not a threat by itself. The old unconditional branch
            // emitted an entity-less LOW_HP at the bot's own position even in broad daylight with
            // no hostile nearby. Evade then chose an arbitrary +X destination and could route a
            // recovering worker from safe surface terrain into a cave. Preserve retreat priority
            // only when this observed, reachable hostile actually exists, and keep its direction.
            if (bot.getHealth() < 6.0F) {
                return Optional.of(new Threat(
                        Threat.Type.LOW_HP, Threat.Severity.HIGH, mob, mob.getBlockPos()));
            }
            Threat.Severity severity = mob instanceof CreeperEntity
                    ? Threat.Severity.HIGH : Threat.Severity.MEDIUM;
            return Optional.of(new Threat(Threat.Type.HOSTILE, severity, mob, mob.getBlockPos()));
        }
        if (bot.isSubmergedInWater() && bot.getAir() < 50) {
            return Optional.of(new Threat(Threat.Type.DROWNING, Threat.Severity.MEDIUM, null, bot.getBlockPos()));
        }
        Optional<BlockPos> lava = BlockPos.stream(bot.getBlockPos().add(-2, -1, -2), bot.getBlockPos().add(2, 1, 2))
                .filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
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

    // 怪物能否真正威胁到 bot:bot 眼睛 → 怪眼睛之间做一次方块 raycast,中间被实心方块挡住(非 MISS)即
    // 视为够不到(隔墙/隔隧道)。raycast 只检测方块、不含实体,正好判断"有没有墙挡着"。近战怪没视线打不到、
    // 远程怪没视线射不到、苦力怕没视线也炸不到——一律不算当前威胁(它们绕过来/露头后会被重新检测到)。
    private static boolean canReachThreat(AIPlayerEntity bot, LivingEntity mob) {
        return CombatCore.hasLineOfSight(bot, mob);
    }

    // 近处(8 格)是否有可达(有视线)的敌对怪。作为濒死封墙闸的防御性兜底；正常 LOW_HP
    // Threat 已携带 hostile entity。复用同款视线判定，避免把隔墙怪物算作当前压力。
    private static boolean hasReachableHostile(AIPlayerEntity bot) {
        List<LivingEntity> hostiles = bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(8.0D),
                        entity -> entity instanceof HostileEntity && entity.isAlive()
                                && ObservableWorldQuery.canObserveEntity(bot, entity));
        for (LivingEntity mob : hostiles) {
            if (canReachThreat(bot, mob)) {
                return true;
            }
        }
        return false;
    }
}
