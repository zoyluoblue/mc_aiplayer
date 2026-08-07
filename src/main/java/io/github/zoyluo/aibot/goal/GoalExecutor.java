package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.brain.BotReporter;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.MiningBudget;
import io.github.zoyluo.aibot.mining.MiningMissionBudget;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.task.BlueprintLoader;
import io.github.zoyluo.aibot.task.BlueprintSchema;
import io.github.zoyluo.aibot.task.BuildTask;
import io.github.zoyluo.aibot.task.CheckpointableTask;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.CreateObsidianTask;
import io.github.zoyluo.aibot.task.DescendToYTask;
import io.github.zoyluo.aibot.task.DigDownTask;
import io.github.zoyluo.aibot.task.FarmTask;
import io.github.zoyluo.aibot.task.GatherQuotaTask;
import io.github.zoyluo.aibot.task.HuntSearchCursor;
import io.github.zoyluo.aibot.task.HuntPickupCheckpoint;
import io.github.zoyluo.aibot.task.HuntTask;
import io.github.zoyluo.aibot.task.MilkCowTask;
import io.github.zoyluo.aibot.task.MiningServiceTask;
import io.github.zoyluo.aibot.mining.MiningCursor;
import io.github.zoyluo.aibot.task.MineTask;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.OreDigTask;
import io.github.zoyluo.aibot.task.PlaceStationsTask;
import io.github.zoyluo.aibot.task.ResupplyTask;
import io.github.zoyluo.aibot.task.SmeltTask;
import io.github.zoyluo.aibot.task.StockpileTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskState;
import io.github.zoyluo.aibot.task.TaskStatus;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.persist.MissionRecord;
import io.github.zoyluo.aibot.persist.MissionRuntimeRecord;
import io.github.zoyluo.aibot.persist.MissionSpec;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.stat.Stats;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class GoalExecutor {
    public static final GoalExecutor INSTANCE = new GoalExecutor();
    private static final int BASE_LIFETIME_REPLAN_LIMIT = 12;
    private static final int MAX_CONSECUTIVE_REPLANS = 3;
    private static final int MAX_POSTCONDITION_REPLANS = 3;
    private static final int MAX_POSTCONDITION_FINGERPRINT_BYTES = 32_768;
    private static final String POSTCONDITION_REPLANS_KEY =
            "postcondition_replans";
    private static final String POSTCONDITION_LAST_MATCHED_KEY =
            "postcondition_last_matched";
    private static final String POSTCONDITION_FINGERPRINT_KEY =
            "postcondition_fingerprint";
    private static final String POSTCONDITION_PREFIX = "postcondition_";
    private static final Set<String> POSTCONDITION_REPAIR_KEYS = Set.of(
            POSTCONDITION_REPLANS_KEY,
            POSTCONDITION_LAST_MATCHED_KEY,
            POSTCONDITION_FINGERPRINT_KEY);
    private static final String SKIPPED_TARGET_PREFIX = "skipped_target.";
    private static final int MAX_SKIPPED_TARGET_RECEIPTS = 32;
    private static final int MAX_SKIPPED_TARGET_TEXT_BYTES = 8_192;
    private static final Set<String> SKIPPED_TARGET_ENTRY_KEYS = Set.of(
            "kind", "item", "count", "block", "ores", "input", "output",
            "pos", "tag_present", "tag", "best_effort", "reason");
    private static final Set<String> LEGACY_REPLAN_SNAPSHOT_KEYS = Set.of(
            "snap_steps", "snap_target", "snap_x", "snap_y", "snap_z");
    private static final Set<String> MODERN_REPLAN_SNAPSHOT_KEYS = Set.of(
            "snap_steps", "snap_target", "snap_x", "snap_y", "snap_z",
            "snap_dimension", "snap_hunt_raw_meat", "snap_hunt_visited_sectors");
    private static final String AUXILIARY_MINING_CONTINUATION_KEY =
            "aux_mining_continuation";
    private static final String CAPACITY_PARENT_DELIVERED_KEY =
            "capacity_parent_delivered";
    private static final String CAPACITY_PARENT_FACE_KEY =
            "capacity_parent_face";
    private static final String CAPACITY_PARENT_SERVICES_USED_KEY =
            "capacity_parent_services_used";
    private static final String SETTLED_SERVICE_PREFIX = "settled_service.";
    private static final String HUNT_CURSOR_PREFIX = "hunt.";
    private static final int MAX_SETTLED_SERVICE_TOMBSTONES = 16;
    private static final Set<String> SETTLED_SERVICE_ENTRY_KEYS = Set.of(
            "schema", "descriptor", "dimension", "work_face", "pocket_axis",
            "pocket_a", "pocket_b", "failure");
    private static final Set<String> ACTIVE_SERVICE_POCKET_PHASES = Set.of(
            "OPEN_DISPOSAL_POCKET",
            "CAPTURE_DISPOSAL_BASELINE",
            "DROP_DISPOSABLE",
            "SETTLE_DISPOSABLE",
            "RETURN_TO_DISPOSAL_FACE",
            "SEAL_DISPOSAL_POCKET");
    private static final Set<String> SERVICE_POCKET_IDENTITY_KEYS = Set.of(
            "pocket_entry",
            "pocket_sink",
            "pocket_direction",
            "pocket_entities",
            "pocket_lineage",
            "pocket_baseline",
            "pocket_ledger",
            "pocket_drop_committed",
            "pocket_ledger_verified",
            "pocket_phase_started",
            "pocket_failure",
            "pocket_clear_index");

    private final Map<UUID, ActivePlan> activePlans = new ConcurrentHashMap<>();
    // P0 目标队列(对话式助手根基):复合指令"先搞吃的再挖铁"需要连续目标。原单 plan 模型下
    // 第二个目标会被拒/覆盖(prompt 甚至要求"一次一个,调完 STOP")。现在:活跃目标存在时新目标入队,
    // 当前目标完成/失败后自动出队衔接(像真人:手头干完接着办下一件,办不成说一声跳过)。
    private final Map<UUID, java.util.Deque<Goal>> goalQueue = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastGoalFailTick = new ConcurrentHashMap<>(); // 优化2:goal 整体失败时刻,拦大脑随后手动逐格挖矿
    private final Map<UUID, Goal> userGoal = new ConcurrentHashMap<>(); // B:用户原始高层目标,防大脑把它降级成其前置子目标(挖钻石→做铁镐)
    private final Map<UUID, GoalResult> lastResults = new ConcurrentHashMap<>();
    // Death is a recoverable interruption for long-running Missions. The exact Mission record is
    // detached while the safety-origin corpse run owns TaskManager, then restored with the same id.
    private final Map<UUID, MissionRuntimeRecord> deathSuspended = new ConcurrentHashMap<>();
    // Reuses the same durable detached-runtime container for a different gate: an open physical
    // service pocket may resume only after the bot returns to the checkpoint's exact dimension.
    private final Map<UUID, String> dimensionSuspended = new ConcurrentHashMap<>();
    // A malformed checkpoint that still claims physical pocket ownership cannot be executed or
    // discarded. Keep its complete MissionRuntimeRecord durable until explicit cancellation; a
    // later process restore will re-run the same fail-closed probe and rebuild this marker.
    private final Map<UUID, String> restoreQuarantined = new ConcurrentHashMap<>();
    // Semantic validation of a raw active pocket is transactional: restore failures must be
    // quarantined without first publishing an irreversible terminal result/chat/episode.
    private final Set<UUID> pocketRestorePreflights = ConcurrentHashMap.newKeySet();
    private final AtomicLong resultSequence = new AtomicLong();

    private GoalExecutor() {
    }

    public boolean submit(AIPlayerEntity bot, Goal goal) {
        return submit(bot, goal, null);
    }

    /**
     * A detached runtime still owns the bot's user-intent authority. New submissions may extend
     * its durable queue, but must never allocate a second ActivePlan or Task while the suspended
     * checkpoint (especially an open disposal pocket) remains unresolved.
     */
    private Optional<Boolean> submitIntoSuspendedRuntime(AIPlayerEntity bot, Goal goal) {
        UUID uuid = bot.getUuid();
        while (true) {
            MissionRuntimeRecord suspended = deathSuspended.get(uuid);
            if (suspended == null) {
                return Optional.empty();
            }
            Optional<Goal> suspendedGoal = suspended.active() == null
                    || suspended.active().spec() == null
                    ? Optional.empty() : suspended.active().spec().toGoal();
            if (suspendedGoal.filter(goal::equals).isPresent()
                    || suspended.queue().stream().map(MissionSpec::toGoal)
                    .flatMap(Optional::stream).anyMatch(goal::equals)) {
                BotLog.task(bot, "goal_submit_ignored", "goal", goal,
                        "reason", "duplicate_suspended_runtime");
                return Optional.of(true);
            }
            if (restoreQuarantined.containsKey(uuid)) {
                BotLog.task(bot, "goal_submit_rejected", "goal", goal,
                        "reason", "restore_quarantined_physical_pocket");
                report(bot, "当前挖矿口袋检查点已隔离，需先取消该任务后才能开始新目标。");
                return Optional.of(false);
            }
            if (suspendedGoal.filter(parent -> isPrerequisiteOf(bot, goal, parent))
                    .isPresent()) {
                BotLog.task(bot, "goal_downgrade_blocked", "sub", goal,
                        "user", suspendedGoal.orElseThrow());
                report(bot, "这是当前目标的前置步骤，系统恢复后会自动完成，无需单独做。");
                return Optional.of(false);
            }
            List<MissionSpec> appended = new ArrayList<>(suspended.queue());
            appended.add(MissionSpec.fromGoal(goal));
            MissionRuntimeRecord updated = new MissionRuntimeRecord(
                    suspended.active(), appended, suspended.userPaused());
            if (!deathSuspended.replace(uuid, suspended, updated)) {
                continue;
            }
            BotLog.task(bot, "goal_queued", "goal", goal,
                    "behind", suspendedGoal.map(String::valueOf).orElse("suspended_queue"),
                    "queue_size", appended.size(), "state", "suspended");
            report(bot, "记下了，当前挖矿现场恢复并收尾后就去办：" + goalLabel(goal));
            markDirty(bot);
            return Optional.of(true);
        }
    }

    private boolean submit(AIPlayerEntity bot, Goal goal, RestoreSeed restore) {
        Optional<Boolean> suspendedSubmission = submitIntoSuspendedRuntime(bot, goal);
        if (suspendedSubmission.isPresent()) {
            return suspendedSubmission.orElseThrow();
        }
        // GOALFIX-GF3:幂等——同一 bot 已有相同目标的活跃计划时,忽略重复 submit
        //(防大脑连点 mine_ore/achieve_goal 覆盖计划、打断进行中的步骤)。
        ActivePlan existing = activePlans.get(bot.getUuid());
        if (existing != null && existing.goal.equals(goal)) {
            BotLog.task(bot, "goal_submit_ignored", "goal", goal, "reason", "duplicate_active_plan");
            return true;
        }
        // P0 队列:已有进行中的目标 → 新目标入队(去重),手头干完自动接续。复合指令/连续吩咐的根基。
        // 注意放在"前置降级拦截"之后判定才安全?不——降级拦截在下面,先让它检查:子目标仍要拦。
        java.util.Deque<Goal> queued = goalQueue.computeIfAbsent(bot.getUuid(), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        if (existing != null) {
            Goal ugQ = userGoal.get(bot.getUuid());
            if (ugQ != null && !ugQ.equals(goal) && isPrerequisiteOf(bot, goal, ugQ)) {
                BotLog.task(bot, "goal_downgrade_blocked", "sub", goal, "user", ugQ);
                report(bot, "这是当前目标的前置步骤,系统会自动完成,无需单独做。");
                return false;
            }
            if (queued.stream().anyMatch(goal::equals)) {
                BotLog.task(bot, "goal_submit_ignored", "goal", goal, "reason", "duplicate_queued");
                return true;
            }
            queued.addLast(goal);
            BotLog.task(bot, "goal_queued", "goal", goal, "behind", String.valueOf(existing.goal), "queue_size", queued.size());
            report(bot, "记下了,等手头这件干完就去办:" + goalLabel(goal));
            markDirty(bot);
            return true;
        }
        // B:保护用户原始目标——大脑不能把它降级成其前置子目标。实测:挖钻石失败后大脑 achieve_goal 做铁镐、
        // mine_ore 挖铁(都是挖钻石的前置)覆盖了目标,做完铁镐还误报"任务完成、最初要求是挖铁做镐"。
        Goal ug = userGoal.get(bot.getUuid());
        if (ug != null && !ug.equals(goal) && isPrerequisiteOf(bot, goal, ug)) {
            BotLog.task(bot, "goal_downgrade_blocked", "sub", goal, "user", ug);
            report(bot, "这是当前目标的前置步骤,系统会自动完成,无需单独做。要更换目标请直接告诉我。");
            return false;
        }
        UUID missionId = restore == null ? UUID.randomUUID() : restore.missionId();
        int startedTick = restore == null ? bot.getServer().getTicks() : restore.startedTick();
        Optional<CapacityParentNamespace> decodedCapacityParent = restore == null
                ? Optional.empty()
                : CapacityParentNamespace.decode(restore.capacityParentNamespace());
        if (restore != null && !restore.capacityParentNamespace().isBlank()
                && decodedCapacityParent.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            GoalEvaluation invalidEvaluation = GoalPredicates.forGoal(goal).evaluate(
                    GoalSnapshotCollector.collect(bot, goal, restore.context()));
            recordImmediateResult(bot, missionId, goal, startedTick, invalidEvaluation,
                    GoalResult.classify(invalidEvaluation, false),
                    "mission_restore_invalid_capacity_parent_namespace");
            return false;
        }
        CapacityParentNamespace restoredCapacityParent =
                decodedCapacityParent.orElse(null);
        GoalPredicate predicate = GoalPredicates.forGoal(goal);
        GoalSnapshotCollector.Context context = restore == null ? initialContext(bot, goal) : restore.context();
        GoalEvaluation initialEvaluation = predicate.evaluate(GoalSnapshotCollector.collect(bot, goal, context));
        if (restore != null && (!restore.validationFailure().isBlank()
                || restore.huntSearchCursor() == null
                || restore.skippedTargetReceipts() == null
                || !skippedTargetReceiptsAuthorized(
                goal, restore.skippedTargetReceipts()))) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    restore.validationFailure().isBlank()
                            ? restore.huntSearchCursor() == null
                            ? "mission_restore_invalid_hunt_search_cursor"
                            : "mission_restore_invalid_skipped_target_receipts"
                            : restore.validationFailure());
            return false;
        }
        List<SkippedTargetReceipt> restoredSkippedTargets = restore == null
                ? List.of() : restore.skippedTargetReceipts();
        List<GoalResult.SkippedStep> restoredSkippedResults =
                restoredSkippedTargets.stream()
                        .map(SkippedTargetReceipt::asSkippedStep).toList();
        int persistedCapacityParentDelivered = restore == null
                ? -1 : restore.capacityParentDelivered();
        int persistedCapacityParentServicesUsed = restore == null
                ? 0 : restore.capacityParentServicesUsed();
        String persistedCapacityParentFace = restore == null
                ? "" : restore.capacityParentFace();
        BlockPos decodedCapacityParentFace = decodePos(
                persistedCapacityParentFace).orElse(null);
        if (persistedCapacityParentDelivered < -1
                || persistedCapacityParentServicesUsed < 0
                || !persistedCapacityParentFace.isBlank()
                && decodedCapacityParentFace == null) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_capacity_parent_checkpoint");
            return false;
        }
        int persistedRareResourceEpoch = restore == null
                ? 0 : restore.rareResourceRetriesUsed();
        int rareEpochMarginPool = rareMissionEpochMarginPool(goal);
        if (persistedRareResourceEpoch < 0
                || persistedRareResourceEpoch
                > MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH + rareEpochMarginPool) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_rare_resource_retry_count");
            return false;
        }
        int persistedRareEpochMarginUsed = restore == null
                ? 0 : restore.rareEpochMarginUsed();
        if (!validRestoredRareEpochMargin(persistedRareResourceEpoch,
                persistedRareEpochMarginUsed, rareEpochMarginPool)) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_rare_epoch_margin");
            return false;
        }
        Map<String, String> restoredSettledServiceValues = restore == null
                ? Map.of() : restore.settledServiceTombstone();
        Optional<List<SettledServiceTombstone>> restoredSettledServices =
                decodeSettledServiceTombstones(restoredSettledServiceValues);
        if (restoredSettledServices.isEmpty()
                || restoredSettledServices.orElseThrow().stream()
                .anyMatch(settled -> !missionId.toString().equals(
                        settled.authority().descriptor().missionId()))) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_settled_service_tombstone");
            return false;
        }
        Map<String, String> restoredHuntCheckpoint = restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.HUNT
                ? restore.taskCheckpoint() : Map.of();
        Optional<HuntPickupCheckpoint.Metadata> restoredHunt =
                HuntPickupCheckpoint.inspect(restoredHuntCheckpoint);
        if (restore != null && restore.taskCheckpointKind() == GoalStep.Kind.HUNT
                && restoredHunt.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_hunt_pickup_checkpoint");
            return false;
        }
        boolean unsettledHuntPickup =
                restoredHunt.filter(HuntPickupCheckpoint.Metadata::open).isPresent();
        boolean committedHuntPickup = restoredHunt.isPresent() && !unsettledHuntPickup;
        if (restoredHunt.filter(metadata ->
                hasSameDimensionOpenHuntTimeRollback(
                        metadata,
                        bot.getServerWorld().getRegistryKey().getValue().toString(),
                        bot.getServerWorld().getTime())
                || !metadata.open()
                && !trustedClosedHuntPickupReceipt(bot, metadata)).isPresent()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_hunt_pickup_checkpoint");
            return false;
        }
        Map<String, String> restoredServiceCheckpoint = restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MINING_SERVICE
                ? restore.taskCheckpoint() : Map.of();
        Optional<MiningServiceTask.RestoreMetadata> restoredService =
                MiningServiceTask.inspectCheckpoint(restoredServiceCheckpoint);
        boolean mergedSettledTerminalReceipt = false;
        String mergedSettledTerminalFailure = "";
        String liveServiceDimension = bot.getServerWorld().getRegistryKey()
                .getValue().toString();
        if (!restoredServiceCheckpoint.isEmpty() && (restoredService.isEmpty()
                || !liveServiceDimension.equals(
                restoredService.orElseThrow().serviceDimension())
                && restoredService.orElseThrow().terminalFailure().isBlank())) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_mining_service_checkpoint");
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "MINING_SERVICE",
                    "reason", "invalid_task_checkpoint");
            return false;
        }
        if (restoredService.isPresent() && !restoredSettledServices.orElseThrow().isEmpty()) {
            MiningServiceTask.RestoreMetadata currentMetadata = restoredService.orElseThrow();
            Optional<SettledServiceAuthority> currentServiceAuthority =
                    persistedServiceAuthority(currentMetadata);
            boolean guardedGeometry = currentServiceAuthority
                    .map(current -> restoredSettledServices.orElseThrow().stream()
                            .anyMatch(settled -> settled.sameGeometry(current)))
                    .orElse(false);
            Optional<SettledServiceTombstone> sameKey = currentServiceAuthority
                    .flatMap(current -> restoredSettledServices.orElseThrow().stream()
                            .filter(settled -> settled.key().equals(current.key()))
                            .findFirst());
            boolean terminalReceipt = !currentMetadata.terminalFailure().isBlank();
            boolean exactIdempotentReceipt = terminalReceipt && sameKey
                    .filter(settled -> settled.failureReason().equals(
                            currentMetadata.terminalFailure())).isPresent();
            boolean conflictingTerminalAuthority = terminalReceipt && !exactIdempotentReceipt
                    && (sameKey.isPresent() || guardedGeometry);
            if (currentServiceAuthority.isEmpty()
                    || hasActiveServicePocket(restoredServiceCheckpoint) && guardedGeometry
                    || conflictingTerminalAuthority) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_invalid_settled_service_tombstone");
                return false;
            }
            if (exactIdempotentReceipt) {
                // Crash window after guard publication but before the failed task reference was
                // detached. The two authorities are byte-for-byte equivalent; consume the task
                // copy only after the decoded service has passed every goal/profile/parent
                // compatibility check below.
                mergedSettledTerminalReceipt = true;
                mergedSettledTerminalFailure = currentMetadata.terminalFailure();
            }
        }
        Map<String, String> restoredOreDigCheckpoint = restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MINE_ORE
                ? restore.taskCheckpoint() : Map.of();
        Optional<OreDigTask.RestoreMetadata> restoredOreDig =
                inspectOreDigCheckpointForGoal(goal, restoredOreDigCheckpoint);
        boolean restoredOreDigPhysicalDebt = hasOreDigPhysicalLedger(
                restoredOreDigCheckpoint);
        if (restore != null && restore.taskCheckpointKind() == GoalStep.Kind.MINE_ORE
                && restoredOreDig.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_ore_dig_checkpoint");
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "MINE_ORE",
                    "reason", "invalid_task_checkpoint");
            return false;
        }
        Map<String, String> restoredMiningCheckpoint = restore == null
                ? Map.of() : restore.miningCheckpoint();
        Optional<OreDigTask.RestoreMetadata> decodedMining =
                OreDigTask.inspectCheckpoint(restoredMiningCheckpoint);
        Optional<OreDigTask.RestoreMetadata> restoredMining =
                inspectOreDigCheckpointForGoal(goal, restoredMiningCheckpoint);
        boolean ignoredBootstrapMiningNamespace = restoredService
                .filter(metadata -> metadata.policy().profile()
                        == MiningServiceTask.ServiceProfile.RARE_ORE_BATCH
                        || metadata.policy().profile()
                        == MiningServiceTask.ServiceProfile.RARE_DESCENT_KIT)
                .filter(metadata -> metadata.serviceBoundary() == 0)
                .flatMap(metadata -> decodedMining.filter(mining ->
                        mining.rareMissionTarget() == 0
                                && !mining.batchOpen()
                                && !OreDigTask.oreFingerprint(metadata.ores()).equals(
                                OreDigTask.oreFingerprint(mining.ores()))))
                .isPresent()
                && !hasOreDigPhysicalLedger(restoredMiningCheckpoint);
        if (ignoredBootstrapMiningNamespace) {
            // A completed ordinary prerequisite (most commonly coal for torches) is the latest
            // branch namespace until the first rare batch starts.  Boundary zero owns a synthetic
            // rare work face and may discard only that closed, different-family predecessor.
            restoredMiningCheckpoint = Map.of();
            restoredMining = Optional.empty();
        }
        if (!restoredMiningCheckpoint.isEmpty() && restoredMining.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_mining_checkpoint");
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "MINING_CURSOR",
                    "reason", "invalid_mining_checkpoint");
            return false;
        }
        boolean restoredProtectedRareMining = originalLongRareOreTargetCount(goal) > 0
                && restoredMining.isPresent()
                && restoredMining.orElseThrow().rareMissionTarget()
                == originalLongRareOreTargetCount(goal)
                && miningStepFeedsGoal(goal, restoredMining.orElseThrow().ores());
        Map<String, String> restoredAuxiliaryMiningCheckpoint = restore == null
                ? Map.of() : restore.auxiliaryMiningCheckpoint();
        Optional<OreDigTask.RestoreMetadata> restoredAuxiliaryMining =
                OreDigTask.inspectCheckpoint(restoredAuxiliaryMiningCheckpoint, 0)
                        .filter(metadata -> metadata.rareMissionTarget() == 0);
        String restoredAuxiliaryMiningContinuationFingerprint = restore == null
                ? "" : restore.auxiliaryMiningContinuationFingerprint();
        boolean restoredAuxiliaryMiningContinuation =
                !restoredAuxiliaryMiningContinuationFingerprint.isBlank();
        boolean unmarkedClosedAuxiliaryService = restoredCapacityParent == null
                && restoredAuxiliaryMining.filter(metadata -> !metadata.batchOpen()).isPresent()
                && restoredService.map(MiningServiceTask.RestoreMetadata::policy)
                .map(MiningServiceTask.ServicePolicy::profile)
                .filter(profile -> profile == MiningServiceTask.ServiceProfile.ORE_BATCH)
                .isPresent();
        String restoredAuxiliaryFingerprint = restoredAuxiliaryMining
                .map(metadata -> OreDigTask.oreFingerprint(metadata.ores()))
                .orElse("");
        boolean validAuxiliaryContinuation = restoredAuxiliaryMiningContinuation
                && restoredCapacityParent == null
                && restoredService.isEmpty()
                && restoredAuxiliaryMining.filter(metadata -> !metadata.batchOpen()).isPresent()
                && restoredAuxiliaryFingerprint.equals(
                        restoredAuxiliaryMiningContinuationFingerprint)
                && !hasOreDigPhysicalLedger(restoredAuxiliaryMiningCheckpoint);
        if (restoredAuxiliaryMiningContinuation && !validAuxiliaryContinuation
                || !restoredAuxiliaryMiningCheckpoint.isEmpty()
                && (restoredAuxiliaryMining.isEmpty() || !restoredProtectedRareMining
                || restoredCapacityParent == null && !unmarkedClosedAuxiliaryService
                && !validAuxiliaryContinuation)) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_auxiliary_mining_checkpoint");
            return false;
        }
        Map<String, String> restoredCapacityParentCheckpoint = switch (
                restoredCapacityParent == null
                        ? CapacityParentNamespace.MINING : restoredCapacityParent) {
            case MINING -> restoredMiningCheckpoint;
            case AUXILIARY -> restoredAuxiliaryMiningCheckpoint;
        };
        Optional<OreDigTask.RestoreMetadata> restoredCapacityParentMetadata =
                restoredCapacityParent == null ? Optional.empty()
                        : OreDigTask.inspectCheckpoint(restoredCapacityParentCheckpoint, 0);
        int restoredCapacityParentDelivered = persistedCapacityParentDelivered;
        int restoredCapacityParentServicesUsed = persistedCapacityParentServicesUsed;
        BlockPos restoredCapacityParentFace = decodedCapacityParentFace;
        if (restoredCapacityParent != null && restoredCapacityParentDelivered < 0
                && restoredCapacityParentMetadata.isPresent()) {
            // Legacy capacity-parent snapshots predate the explicit progress watermark. Binding
            // them to their current delivered count is conservative: restart cannot manufacture a
            // repeat service until the exact parent physically delivers another target item.
            restoredCapacityParentDelivered = restoredCapacityParentMetadata.orElseThrow()
                    .delivered();
        }
        if (restoredCapacityParent != null && restoredCapacityParentMetadata.isPresent()) {
            OreDigTask.RestoreMetadata parent = restoredCapacityParentMetadata.orElseThrow();
            if (restoredCapacityParentFace == null) {
                // Legacy capacity-parent snapshots had no work-face witness. Bind them to the
                // current cursor so restart cannot manufacture branch movement.
                restoredCapacityParentFace = parent.cursor().face();
            }
            if (restoredCapacityParentServicesUsed == 0) {
                // Under the former delivered-only rule, a persisted delivery watermark N could
                // have consumed at least one and at most N+1 services. Use the conservative upper
                // estimate so migration never grants extra service budget.
                restoredCapacityParentServicesUsed = Math.min(parent.targetCount(),
                        Math.max(1, restoredCapacityParentDelivered + 1));
            }
        }
        boolean validCapacityParentMarkers;
        if (restoredCapacityParent == null) {
            validCapacityParentMarkers = restoredCapacityParentDelivered == -1
                    && restoredCapacityParentServicesUsed == 0
                    && restoredCapacityParentFace == null;
        } else if (restoredCapacityParentMetadata.isEmpty()
                || restoredCapacityParentDelivered < 0
                || restoredCapacityParentServicesUsed < 1
                || restoredCapacityParentFace == null) {
            validCapacityParentMarkers = false;
        } else {
            OreDigTask.RestoreMetadata parent = restoredCapacityParentMetadata.orElseThrow();
            int upperBound = parent.batchOpen() ? parent.delivered() : parent.targetCount();
            validCapacityParentMarkers = restoredCapacityParentDelivered <= upperBound
                    && restoredCapacityParentServicesUsed <= parent.targetCount()
                    && (restore.taskCheckpointKind() != GoalStep.Kind.MINING_SERVICE
                    || restoredCapacityParentDelivered == parent.delivered()
                    && restoredCapacityParentFace.equals(parent.cursor().face()));
        }
        boolean restoredCommittedCapacityParent = restoredCapacityParent != null
                && validCommittedCapacityParent(
                restoredCapacityParentMetadata,
                restore.taskCheckpointKind(),
                restore.taskCheckpoint(),
                restoredCapacityParentCheckpoint);
        boolean restoredTaskIsCapacityParent = restoredCapacityParent != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MINE_ORE
                && restore.taskCheckpoint().equals(restoredCapacityParentCheckpoint);
        boolean restoredCapacityRepairOre = restoredCapacityParent != null
                && restoredOreDig.isPresent()
                && !restoredTaskIsCapacityParent;
        boolean supportedCapacityRepairTask = restore == null
                || restore.taskCheckpointKind() == null
                || restore.taskCheckpointKind() == GoalStep.Kind.MINING_SERVICE
                || restore.taskCheckpointKind() == GoalStep.Kind.MINE_ORE
                || restore.taskCheckpointKind() == GoalStep.Kind.MINE
                || restore.taskCheckpointKind() == GoalStep.Kind.DESCEND_TO_Y;
        if (!validCapacityParentMarkers || restoredCapacityParent != null
                && (!validCapacityParentRetry(restoredCapacityParentMetadata)
                && !restoredCommittedCapacityParent
                || restoredCapacityParent == CapacityParentNamespace.MINING
                && !restoredAuxiliaryMiningCheckpoint.isEmpty()
                || restoredCapacityParent == CapacityParentNamespace.AUXILIARY
                && !restoredProtectedRareMining
                || !supportedCapacityRepairTask)) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_capacity_parent_checkpoint");
            return false;
        }
        boolean protectedRareNamespace = restoredOreDig.isPresent()
                && restoredOreDig.orElseThrow().rareMissionTarget() == 0
                && restoredProtectedRareMining;
        if (restoredOreDig.isPresent() && restoredMining.isPresent()
                && !protectedRareNamespace && !restoredCapacityRepairOre
                && !restoredOreDigCheckpoint.equals(restoredMiningCheckpoint)) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_mining_checkpoint");
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> restoredRareEpochOwner =
                restoredProtectedRareMining
                        ? restoredMining
                        : restoredOreDig.filter(metadata ->
                        metadata.rareMissionTarget()
                                == originalLongRareOreTargetCount(goal)
                                && miningStepFeedsGoal(goal, metadata.ores()));
        OptionalInt normalizedRareResourceEpoch = normalizeRestoredRareResourceEpoch(
                persistedRareResourceEpoch, restoredRareEpochOwner);
        if (normalizedRareResourceEpoch.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_rare_resource_epoch");
            return false;
        }
        int restoredRareResourceEpoch = normalizedRareResourceEpoch.getAsInt();
        Map<String, String> restoredDigDownCheckpoint = restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MINE
                ? restore.taskCheckpoint() : Map.of();
        Optional<DigDownTask.RestoreMetadata> restoredDigDown =
                DigDownTask.inspectCheckpoint(restoredDigDownCheckpoint);
        if (restore != null && restore.taskCheckpointKind() == GoalStep.Kind.MINE
                && restoredDigDown.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_dig_down_checkpoint");
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "MINE",
                    "reason", "invalid_task_checkpoint");
            return false;
        }
        Map<String, String> restoredDescendCheckpoint = restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.DESCEND_TO_Y
                ? restore.taskCheckpoint() : Map.of();
        Optional<DescendToYTask.RestoreMetadata> restoredDescend =
                DescendToYTask.inspectCheckpoint(restoredDescendCheckpoint);
        if (restore != null && restore.taskCheckpointKind() == GoalStep.Kind.DESCEND_TO_Y
                && restoredDescend.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_descend_checkpoint");
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "DESCEND_TO_Y",
                    "reason", "invalid_task_checkpoint");
            return false;
        }
        Map<String, String> restoredObsidianCheckpoint = restore == null
                ? Map.of() : restore.obsidianCheckpoint();
        if (restoredObsidianCheckpoint.isEmpty() && restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MAKE_OBSIDIAN) {
            // Backward compatibility for snapshots written before the dedicated transaction
            // namespace existed.
            restoredObsidianCheckpoint = restore.taskCheckpoint();
        }
        Optional<CreateObsidianTask.RestoreMetadata> restoredObsidian =
                CreateObsidianTask.inspectCheckpoint(restoredObsidianCheckpoint);
        if (!restoredObsidianCheckpoint.isEmpty() && restoredObsidian.isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_invalid_obsidian_checkpoint");
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated", "type", "MAKE_OBSIDIAN",
                    "reason", "invalid_task_checkpoint");
            return false;
        }
        boolean unsettledObsidian = restoredObsidian
                .map(CreateObsidianTask.RestoreMetadata::transactionOpen)
                .orElse(false);
        boolean committedService = restoredService
                .map(MiningServiceTask.RestoreMetadata::done).orElse(false);
        int restoredPendingBoundary = restoredObsidian
                .map(CreateObsidianTask.RestoreMetadata::pendingServiceBoundary)
                .orElse(0);
        if (restoredService.isPresent()) {
            MiningServiceTask.RestoreMetadata serviceMetadata = restoredService.orElseThrow();
            MiningServiceTask.ServiceProfile profile = serviceMetadata.policy().profile();
            boolean obsidianOres = serviceMetadata.ores().equals(
                    OreScan.expandOreFamilies(Set.of(net.minecraft.block.Blocks.OBSIDIAN)));
            boolean missionMatches = missionId.toString().equals(
                    serviceMetadata.serviceMissionId());
            boolean incompatibleServiceIdentity;
            if (restoredCapacityParent != null
                    && profile != MiningServiceTask.ServiceProfile.ORE_BATCH) {
                incompatibleServiceIdentity = true;
            } else if (profile == MiningServiceTask.ServiceProfile.OBSIDIAN_8) {
                CreateObsidianTask.RestoreMetadata obsidianMetadata = restoredObsidian.orElse(null);
                boolean policyMatches = obsidianMetadata != null
                        && serviceMetadata.serviceTargetCount() == obsidianMetadata.targetCount()
                        && serviceMetadata.policy().equals(
                        MiningServiceTask.ServicePolicy.obsidian8(
                                obsidianMetadata.targetCount(),
                                serviceMetadata.serviceBoundary()));
                boolean pendingIdentity = obsidianMetadata != null
                        && restoredPendingBoundary > 0
                        && serviceMetadata.serviceBoundary() == restoredPendingBoundary;
                boolean alreadyAcknowledgedIdentity = obsidianMetadata != null
                        && serviceMetadata.done()
                        && restoredPendingBoundary == 0
                        && serviceMetadata.serviceBoundary()
                        == obsidianMetadata.servicedCollected();
                incompatibleServiceIdentity = !obsidianOres || !missionMatches
                        || !policyMatches
                        || (!pendingIdentity && !alreadyAcknowledgedIdentity);
            } else if (profile == MiningServiceTask.ServiceProfile.OBSIDIAN_PREFLIGHT) {
                incompatibleServiceIdentity = !obsidianOres || !missionMatches
                        || serviceMetadata.serviceBoundary() != 0
                        || restoredPendingBoundary > 0
                        || unsettledObsidian
                        || !serviceMetadata.policy().equals(
                        MiningServiceTask.ServicePolicy.obsidianPreflight(
                                serviceMetadata.serviceTargetCount()));
            } else if (profile == MiningServiceTask.ServiceProfile.RARE_DESCENT_KIT) {
                BlockPos serviceFace = decodePos(restoredServiceCheckpoint.get("work_face"))
                        .orElse(null);
                BlockPos embeddedCursorFace = decodePos(
                        restoredServiceCheckpoint.get("cursor_face")).orElse(null);
                incompatibleServiceIdentity = !missionMatches
                        || restoredPendingBoundary > 0
                        || restoredRareResourceEpoch != 0
                        || serviceMetadata.serviceTargetCount() != 64
                        || serviceMetadata.serviceBoundary() != 0
                        || originalLongRareOreTargetCount(goal) != 64
                        || serviceFace == null
                        || !serviceFace.equals(embeddedCursorFace)
                        // Boundary-zero KIT precedes the first rare branch. The only legal prior
                        // namespace is a closed different-family bootstrap cursor, which was
                        // discarded above. Any namespace that remains is incompatible history.
                        || restoredMining.isPresent()
                        || !miningStepFeedsGoal(goal, serviceMetadata.ores())
                        || !serviceMetadata.policy().equals(
                        MiningServiceTask.ServicePolicy.rareDescentKit(64));
            } else if (profile == MiningServiceTask.ServiceProfile.RARE_ORE_BATCH) {
                BlockPos serviceFace = decodePos(restoredServiceCheckpoint.get("work_face"))
                        .orElse(null);
                boolean retryBoundaryZero = serviceMetadata.serviceBoundary() == 0
                        && restoredRareResourceEpoch > 0;
                boolean miningNamespaceRequired = serviceMetadata.serviceBoundary() > 0
                        || retryBoundaryZero;
                boolean miningNamespacePresent = restoredMining.isPresent();
                boolean miningFaceMatches = !miningNamespacePresent
                        ? !miningNamespaceRequired
                        : serviceFace != null && serviceFace.equals(
                        restoredMining.orElseThrow().cursor().face());
                boolean miningOreFamilyMatches = !miningNamespacePresent
                        ? !miningNamespaceRequired
                        : OreDigTask.oreFingerprint(serviceMetadata.ores()).equals(
                        OreDigTask.oreFingerprint(restoredMining.orElseThrow().ores()));
                boolean retryEpochMatches = !miningNamespaceRequired
                        || restoredMining.map(OreDigTask.RestoreMetadata::resourceEpoch)
                        .filter(epoch -> epoch == restoredRareResourceEpoch).isPresent();
                incompatibleServiceIdentity = !missionMatches
                        || restoredPendingBoundary > 0
                        || serviceMetadata.serviceTargetCount()
                        != originalLongRareOreTargetCount(goal)
                        || serviceMetadata.serviceBoundary() > goalTargetCount(bot, goal)
                        || !miningFaceMatches
                        || !miningOreFamilyMatches
                        || !retryEpochMatches
                        || !miningStepFeedsGoal(goal, serviceMetadata.ores())
                        || !serviceMetadata.policy().equals(
                        MiningServiceTask.ServicePolicy.rareOreBatch(
                                serviceMetadata.serviceTargetCount(),
                                serviceMetadata.serviceBoundary(),
                                restoredRareResourceEpoch));
            } else {
                BlockPos serviceFace = decodePos(restoredServiceCheckpoint.get("work_face"))
                        .orElse(null);
                boolean miningNamespaceMatches;
                if (restoredCapacityParent != null) {
                    miningNamespaceMatches = capacityParentMatchesService(
                            restoredServiceCheckpoint, restoredCapacityParentCheckpoint);
                } else if (restoredAuxiliaryMining.isPresent()) {
                    miningNamespaceMatches = ordinaryServiceParentMatches(
                            restoredServiceCheckpoint,
                            restoredAuxiliaryMiningCheckpoint, false);
                } else {
                    miningNamespaceMatches = ordinaryServiceMiningNamespaceMatches(
                            goal, serviceMetadata.ores(), serviceFace, restoredMining);
                }
                MiningServiceTask.ServicePolicy expectedOrdinaryPolicy =
                        !serviceMetadata.policy().maintainsTunnelingTools()
                                && serviceMetadata.policy().emergencyBlocksReserved()
                                > MiningServiceTask.ServicePolicy.defaultOre(false)
                                .emergencyBlocksReserved()
                                ? MiningServiceTask.ServicePolicy.capacityHandoff(
                                serviceMetadata.policy().emergencyBlocksReserved())
                                : MiningServiceTask.ServicePolicy.defaultOre(
                                serviceMetadata.policy().maintainsTunnelingTools());
                incompatibleServiceIdentity = !missionMatches
                        || restoredPendingBoundary > 0
                        || serviceMetadata.serviceTargetCount() != 0
                        || serviceMetadata.serviceBoundary() != 0
                        || restoredCapacityParent != null
                        && serviceMetadata.policy().maintainsTunnelingTools()
                        || !miningNamespaceMatches
                        || !serviceMetadata.policy().equals(expectedOrdinaryPolicy);
            }
            if (incompatibleServiceIdentity) {
                String reason = switch (profile) {
                    case ORE_BATCH ->
                            "mission_restore_incompatible_mining_service_checkpoint";
                    case RARE_DESCENT_KIT ->
                            "mission_restore_incompatible_rare_descent_kit_checkpoint";
                    case RARE_ORE_BATCH ->
                            "mission_restore_incompatible_rare_ore_service_checkpoint";
                    case OBSIDIAN_PREFLIGHT, OBSIDIAN_8 ->
                            "mission_restore_incompatible_obsidian_service_checkpoint";
                };
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false), reason);
                return false;
            }
        }
        if (mergedSettledTerminalReceipt) {
            if (!liveServiceDimension.equals(
                    restoredService.orElseThrow().serviceDimension())) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick,
                        initialEvaluation, GoalResult.Status.FAILED,
                        mergedSettledTerminalFailure);
                return false;
            }
            // All identity and parent checks above were evaluated against the immutable receipt.
            // It is now safe to discard only the effective task copy; the guard remains the sole
            // durable replay authority.
            restoredServiceCheckpoint = Map.of();
            restoredService = Optional.empty();
        }
        // Crash window: service reached DONE after the obsidian boundary was persisted, but before
        // GoalExecutor could acknowledge it. Commit that acknowledgement idempotently on restore;
        // otherwise the same eight-block boundary would be serviced forever.
        if (committedService && restoredPendingBoundary > 0) {
            Optional<Map<String, String>> acknowledged =
                    CreateObsidianTask.acknowledgeServiceBoundary(restoredObsidianCheckpoint);
            if (acknowledged.isEmpty()) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_invalid_obsidian_service_ack");
                return false;
            }
            restoredObsidianCheckpoint = acknowledged.orElseThrow();
            restoredObsidian = CreateObsidianTask.inspectCheckpoint(restoredObsidianCheckpoint);
        }
        boolean unsettledService = restoredService
                .map(metadata -> !metadata.done()).orElse(false);
        boolean restoredTerminalServiceFailure = restoredService
                .map(MiningServiceTask.RestoreMetadata::terminalFailure)
                .filter(failure -> !failure.isBlank())
                .isPresent();
        boolean activeServicePocket = unsettledService
                && hasActiveServicePocket(restoredServiceCheckpoint);
        boolean interruptedDescend = restoredDescend
                .map(DescendToYTask.RestoreMetadata::transactionOpen).orElse(false);
        boolean committedDescend = restoredDescend.isPresent() && !interruptedDescend;
        GoalPlanner.GoalPlan plan = GoalPlanner.plan(
                bot, goal, context, missionId.toString());
        boolean openCapacityRepairOre = restoredCapacityRepairOre
                && restoredOreDig.map(OreDigTask.RestoreMetadata::batchOpen)
                .orElse(false);
        boolean committedCapacityRepairOre = restoredCapacityRepairOre
                && !openCapacityRepairOre && !restoredOreDigPhysicalDebt;
        if (openCapacityRepairOre && !restoredOreDigPhysicalDebt
                && (!plan.success() || !hasFreshMiningSuccessor(
                plan.steps(), restoredOreDig.orElseThrow()))) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_capacity_repair_checkpoint");
            return false;
        }
        if (restoredAuxiliaryMiningContinuation && plan.success()
                && !hasFreshMiningSuccessor(
                plan.steps(), restoredAuxiliaryMining.orElseThrow().ores())) {
            // The marker was created only after an exact failed service yielded to a fresh plan.
            // A later successful plan that no longer contains this ore family is positive proof
            // that its closed cursor is stale; retire both together instead of poisoning restore.
            restoredAuxiliaryMiningCheckpoint = Map.of();
            restoredAuxiliaryMining = Optional.empty();
            restoredAuxiliaryMiningContinuation = false;
            restoredAuxiliaryMiningContinuationFingerprint = "";
        }
        boolean committedRareDescentKit = committedService && restoredService
                .map(MiningServiceTask.RestoreMetadata::policy)
                .map(MiningServiceTask.ServicePolicy::profile)
                .filter(profile -> profile
                        == MiningServiceTask.ServiceProfile.RARE_DESCENT_KIT)
                .isPresent();
        boolean committedRareDescentKitReady = committedRareDescentKit
                && MiningServiceTask.rareDescentKitReady(bot)
                && MiningServiceTask.ownedMissionDepot(bot, missionId.toString());
        if (committedRareDescentKit && !committedRareDescentKitReady
                && plan.success()
                && plan.steps().stream().noneMatch(GoalStep::isRareDescentKitService)) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_rare_descent_kit_not_ready");
            return false;
        }
        boolean discardRestoredTaskCheckpoint = mergedSettledTerminalReceipt
                || committedCapacityRepairOre || committedHuntPickup;
        if (restoredCommittedCapacityParent) {
            // TaskManager can publish COMPLETED before NavSafety/DangerWatcher owns the rest of
            // the tick. A periodic or stopping snapshot taken during that safety transaction sees
            // the exact closed OreDig checkpoint while the capacity marker is still present. That
            // is a committed parent, not a retry and not corrupt history: settle it idempotently
            // without replaying the already-delivered batch.
            CapacityParentNamespace committedParent = restoredCapacityParent;
            String committedFingerprint = OreDigTask.oreFingerprint(
                    restoredCapacityParentMetadata.orElseThrow().ores());
            boolean immediateSameFamilyService = plan.success()
                    && !plan.steps().isEmpty()
                    && plan.steps().get(0).kind() == GoalStep.Kind.MINING_SERVICE
                    && committedFingerprint.equals(OreDigTask.oreFingerprint(
                    plan.steps().get(0).ores()));
            restoredCapacityParent = null;
            restoredCapacityParentDelivered = -1;
            restoredCapacityParentFace = null;
            restoredCapacityParentServicesUsed = 0;
            discardRestoredTaskCheckpoint = true;
            restoredOreDigCheckpoint = Map.of();
            restoredOreDig = Optional.empty();
            if (committedParent == CapacityParentNamespace.AUXILIARY
                    && !immediateSameFamilyService) {
                restoredAuxiliaryMiningCheckpoint = Map.of();
                restoredAuxiliaryMining = Optional.empty();
            }
        }
        Optional<MiningServiceTask.RestoreMetadata> stableRestoredService = restoredService;
        boolean ordinaryService = stableRestoredService
                .map(MiningServiceTask.RestoreMetadata::policy)
                .map(MiningServiceTask.ServicePolicy::profile)
                .filter(profile -> profile == MiningServiceTask.ServiceProfile.ORE_BATCH)
                .isPresent();
        boolean freshOrdinaryServiceSuccessor = ordinaryService && plan.success()
                && hasFreshMiningSuccessor(plan.steps(),
                restoredService.orElseThrow().ores());
        Optional<OreDigTask.RestoreMetadata> ordinaryServiceParent = !ordinaryService
                ? Optional.empty()
                : restoredCapacityParent != null
                ? restoredCapacityParentMetadata
                : restoredAuxiliaryMining.isPresent()
                ? restoredAuxiliaryMining
                : restoredMining.filter(metadata -> metadata.rareMissionTarget() == 0
                && OreDigTask.oreFingerprint(metadata.ores()).equals(
                OreDigTask.oreFingerprint(stableRestoredService.orElseThrow().ores())));
        Map<String, String> ordinaryServiceParentCheckpoint = restoredCapacityParent != null
                ? restoredCapacityParentCheckpoint
                : restoredAuxiliaryMining.isPresent()
                ? restoredAuxiliaryMiningCheckpoint : restoredMiningCheckpoint;
        // A final ordinary hand-off is deliberately scheduled after its last OreDig batch. On a
        // restart the live inventory already satisfies that prerequisite, so the fresh plan has no
        // same-family mining successor by design. The exact closed branch namespace, mission-bound
        // service checkpoint and matching work face established above are the durable identity;
        // keep the service (including an open disposal ledger) instead of treating it as stale.
        boolean terminalOrdinaryServiceRestore = unsettledService && ordinaryService
                && !freshOrdinaryServiceSuccessor
                && !restoredService.orElseThrow().policy().maintainsTunnelingTools()
                && ordinaryServiceParent.filter(metadata -> metadata.rareMissionTarget() == 0
                        && !metadata.batchOpen()
                        && OreDigTask.oreFingerprint(metadata.ores()).equals(
                        OreDigTask.oreFingerprint(stableRestoredService.orElseThrow().ores())))
                .isPresent()
                && !hasOreDigPhysicalLedger(ordinaryServiceParentCheckpoint);
        boolean capacityOrdinaryServiceRestore = unsettledService && ordinaryService
                && restoredCapacityParent != null;
        boolean capacityRetryRestore = restoredCapacityParent != null
                && restoredCapacityParentMetadata
                .filter(OreDigTask.RestoreMetadata::batchOpen).isPresent();
        if (restoredOreDig.filter(OreDigTask.RestoreMetadata::batchOpen)
                .filter(metadata -> metadata.rareMissionTarget() != 0).isPresent()
                && (!restoredOreDigPhysicalDebt || plan.success())
                && !hasFreshMiningSuccessor(
                plan.steps(), restoredOreDig.orElseThrow())) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_ore_dig_successor");
            return false;
        }
        if (unsettledService && ordinaryService) {
            boolean miningLedgerOpen = !restoredProtectedRareMining
                    && hasOreDigPhysicalLedger(restoredMiningCheckpoint);
            if (miningLedgerOpen) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_orphaned_ordinary_service_ledger");
                return false;
            }
            // A live pocket is its own exact physical successor and must always replay first. A
            // marked dynamic capacity service likewise owns an exact debited parent cursor even if
            // the fresh symbolic planner temporarily cannot see the supplies held by that service.
            if (plan.success() && !freshOrdinaryServiceSuccessor
                    && !terminalOrdinaryServiceRestore
                    && !capacityOrdinaryServiceRestore && !activeServicePocket
                    && !restoredTerminalServiceFailure) {
                // The prerequisite may have become true between checkpoint and restart. With no
                // physical disposal debt, the fresh plan is authoritative and the stale service
                // can be dropped instead of mutating an unrelated ore family.
                boolean retireClosedAuxiliary = !restoredAuxiliaryMiningCheckpoint.isEmpty()
                        && failedClosedAuxiliaryServiceMatches(
                        restoredServiceCheckpoint, restoredAuxiliaryMiningCheckpoint);
                discardRestoredTaskCheckpoint = true;
                unsettledService = false;
                if (retireClosedAuxiliary) {
                    // The exact closed auxiliary cursor existed only to bind this service. Keeping
                    // it after dropping the service would poison the next restart: an unrelated
                    // fresh task checkpoint cannot authorize the orphaned aux_mining namespace.
                    restoredAuxiliaryMiningCheckpoint = Map.of();
                    restoredAuxiliaryMining = Optional.empty();
                }
            }
        }
        if (restoredOreDig.isPresent()
                && restoredOreDig.orElseThrow().rareMissionTarget() == 0
                && !hasFreshMiningSuccessor(plan.steps(), restoredOreDig.orElseThrow())
                && restoredCapacityParent == null) {
            boolean matchingMiningNamespace = restoredMining.isPresent()
                    && restoredOreDigCheckpoint.equals(restoredMiningCheckpoint);
            boolean ordinaryPhysicalDebt = restoredOreDigPhysicalDebt
                    || matchingMiningNamespace
                    && hasOreDigPhysicalLedger(restoredMiningCheckpoint);
            if (ordinaryPhysicalDebt && plan.success()) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_orphaned_ordinary_mining_ledger");
                return false;
            }
            if (!ordinaryPhysicalDebt && hasFreshMiningSuccessor(
                    plan.steps(), restoredOreDig.orElseThrow().ores())) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_incompatible_ore_dig_successor");
                return false;
            }
            if (!ordinaryPhysicalDebt) {
                discardRestoredTaskCheckpoint = true;
                if (matchingMiningNamespace) {
                    restoredMiningCheckpoint = Map.of();
                    restoredMining = Optional.empty();
                }
                restoredOreDig = Optional.empty();
            }
        }
        if (plan.success()
                && restoredMining.isPresent()
                && restoredMining.orElseThrow().rareMissionTarget() == 0
                && !hasFreshMiningSuccessor(plan.steps(), restoredMining.orElseThrow().ores())
                && !terminalOrdinaryServiceRestore
                && restoredCapacityParent != CapacityParentNamespace.MINING) {
            if (hasOreDigPhysicalLedger(restoredMiningCheckpoint)) {
                queued.removeFirstOccurrence(goal);
                recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                        GoalResult.classify(initialEvaluation, false),
                        "mission_restore_orphaned_ordinary_mining_ledger");
                return false;
            }
            restoredMiningCheckpoint = Map.of();
            restoredMining = Optional.empty();
        }
        if (initialEvaluation.state() == GoalEvaluation.State.SATISFIED
                && !unsettledObsidian && !unsettledService && !unsettledHuntPickup
                && restoredDigDown.isEmpty() && !interruptedDescend
                && restoredOreDig.filter(
                OreDigTask.RestoreMetadata::batchOpen).isEmpty()) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.Status.COMPLETED, "already_satisfied",
                    restoredSkippedResults);
            return true;
        }
        boolean restoredPreflight = restoredService
                .map(MiningServiceTask.RestoreMetadata::policy)
                .map(MiningServiceTask.ServicePolicy::profile)
                .filter(profile -> profile
                        == MiningServiceTask.ServiceProfile.OBSIDIAN_PREFLIGHT)
                .isPresent();
        int plannedPreflightTarget = plannedObsidianPreflightTarget(plan.steps());
        boolean freshObsidianIdentityPresent = plan.steps().stream().anyMatch(
                step -> step.isObsidianPreflight()
                        || step.kind() == GoalStep.Kind.MAKE_OBSIDIAN);
        int directObsidianRemainingTarget = directObsidianRemainingTarget(
                bot, goal, initialEvaluation);
        boolean plannedPreflightUnknown = plannedPreflightTarget < 0
                && !plan.success() && !freshObsidianIdentityPresent
                && restoredPreflight && directObsidianRemainingTarget > 0
                && restoredService.orElseThrow().serviceTargetCount()
                == directObsidianRemainingTarget;
        boolean preflightTargetMatches = restoredPreflight
                && restoredService.orElseThrow().serviceTargetCount() == plannedPreflightTarget;
        // A failed fresh plan may not reach the preflight node at all (for example the exact
        // supplies are in the service checkpoint's depot). Schema-4 remains authoritative only
        // for a direct obsidian goal whose live remaining count proves the same target. Compound
        // goals, a successful plan with no identity, and a different positive target fail closed.
        if (unsettledService && restoredPreflight
                && !preflightTargetMatches && !plannedPreflightUnknown) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_obsidian_service_checkpoint");
            return false;
        }
        // DONE is the commit record for the same preflight transaction. If a failed fresh plan
        // cannot materialize its preflight node, the exact direct-goal remaining count is the same
        // authority used above for a RUNNING restore; otherwise a crash between task completion and
        // GoalExecutor's hand-off would turn a valid committed preflight into plan_failed.
        boolean committedPreflight = committedService
                && (preflightTargetMatches || plannedPreflightUnknown);
        Set<Block> interruptedServiceOres = unsettledService
                ? restoredService.map(MiningServiceTask.RestoreMetadata::ores).orElse(Set.of())
                : Set.of();
        boolean restoredObsidianBoundary = restoredService
                .map(MiningServiceTask.RestoreMetadata::policy)
                .map(MiningServiceTask.ServicePolicy::profile)
                .filter(profile -> profile == MiningServiceTask.ServiceProfile.OBSIDIAN_8)
                .isPresent();
        int restoredObsidianCollected = restoredObsidian.isPresent()
                ? nonNegativeInt(restoredObsidianCheckpoint.get("collected")) : -1;
        int restoredObsidianRemainingTarget = restoredObsidian.isPresent()
                ? Math.max(0, restoredObsidian.orElseThrow().targetCount()
                - restoredObsidianCollected) : -1;
        // DONE is the commit record for the complete MAKE transaction. Its inventory result is
        // already part of the fresh snapshot, so the checkpoint must not reinsert the original
        // MAKE ahead of a later STOCKPILE/BUILD tail after a crash-window restore. If inventory
        // subsequently regressed, the fresh plan owns that new deficit instead of stale history.
        boolean committedObsidianMake = restoredObsidian.isPresent()
                && restoredObsidianRemainingTarget == 0
                && "DONE".equals(restoredObsidianCheckpoint.get("phase"));
        boolean interruptedObsidian = restoredObsidian.isPresent()
                && !committedObsidianMake
                && (unsettledObsidian || (restore != null
                && restore.taskCheckpointKind() == GoalStep.Kind.MAKE_OBSIDIAN)
                // RUNNING and DONE inter-batch service both belong to the same factual MAKE
                // transaction. Waiting until the service is committed lets a stale target32
                // boundary replay over a fresh remaining target8 before identity validation.
                || restoredObsidianBoundary);
        boolean transactionTargetMatchesFresh = interruptedObsidian
                && restoredObsidianRemainingTarget > 0
                && plannedPreflightTarget == restoredObsidianRemainingTarget;
        boolean transactionTargetMatchesUnknownDirect = interruptedObsidian
                && restoredObsidianRemainingTarget > 0
                && plannedPreflightTarget < 0 && !plan.success()
                && !freshObsidianIdentityPresent
                && directObsidianRemainingTarget == restoredObsidianRemainingTarget;
        boolean interruptedDigDown = restoredDigDown.isPresent();
        // A direct obsidian goal can be reconstructed from its exact transaction alone. A compound
        // goal cannot: its fresh plan owns unrelated material and terminal steps (notably BUILD).
        // When that plan failed, replaying only service -> MAKE would mutate the world and then lose
        // the unrecoverable tail; isolate the restore before scheduling either task.
        boolean compoundObsidianTailUnavailable = !plan.success()
                && !isDirectObsidianGoal(goal)
                && !unsettledHuntPickup
                && (restoredPreflight || restoredObsidianBoundary || interruptedObsidian);
        if (compoundObsidianTailUnavailable) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_compound_obsidian_tail_unavailable",
                    restoredSkippedResults);
            return false;
        }
        // The service policy binds the original transaction target, while a fresh plan sees only
        // what remains after the checkpoint's factual collected count. Bind both views explicitly:
        // target32/collected8 must match fresh target24; a stale target32 cannot impersonate a live
        // target8 merely because its service and transaction namespaces are internally consistent.
        if (interruptedObsidian
                && !transactionTargetMatchesFresh
                && !transactionTargetMatchesUnknownDirect) {
            queued.removeFirstOccurrence(goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false),
                    "mission_restore_incompatible_obsidian_service_checkpoint",
                    restoredSkippedResults);
            return false;
        }
        if (!plan.success() && !unsettledHuntPickup && interruptedServiceOres.isEmpty()
                && !committedPreflight && !interruptedObsidian
                && !interruptedDigDown && !interruptedDescend
                && !restoredOreDigPhysicalDebt) {
            String reason = !mergedSettledTerminalFailure.isBlank()
                    ? mergedSettledTerminalFailure
                    : !restoredSettledServices.orElseThrow().isEmpty()
                    ? settledServiceFailureWithoutContinuation(
                    restoredSettledServices.orElseThrow())
                    : committedRareDescentKit && !committedRareDescentKitReady
                    ? "mission_restore_rare_descent_kit_not_ready"
                    : "plan_failed:" + String.join(",", plan.unresolved());
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.classify(initialEvaluation, false), reason,
                    restoredSkippedResults);
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, bot, "goal_plan_failed",
                    "goal", goal,
                    "unresolved", plan.unresolved());
            return false;
        }
        List<GoalStep> restoredSteps;
        if (unsettledHuntPickup) {
            HuntPickupCheckpoint.Metadata metadata = restoredHunt.orElseThrow();
            GoalStep settlement = GoalStep.hunt(metadata.targetCount());
            if (!metadata.requireFullQuota()) {
                settlement = settlement.asBestEffort();
            }
            restoredSteps = new ArrayList<>(List.of(settlement));
        } else {
            restoredSteps = new ArrayList<>(applySkippedTargetReceipts(
                    plan.success() ? plan.steps() : List.of(), restoredSkippedTargets));
        }
        if (restoredOreDig.map(OreDigTask.RestoreMetadata::batchOpen).orElse(false)
                && (!capacityRetryRestore || restoredCapacityRepairOre
                || restoredOreDigPhysicalDebt)) {
            replayInterruptedOreBatchFirst(restoredSteps,
                    restoredOreDig.orElseThrow());
        } else if (capacityRetryRestore && !unsettledService && plan.success()) {
            reconcileCapacityRetryAfterPrerequisites(restoredSteps,
                    restoredCapacityParentMetadata.orElseThrow());
        }
        boolean rebuiltObsidianAcquisition = false;
        if (!interruptedServiceOres.isEmpty()) {
            // Replanning from the current inventory normally omits the already-entered inter-batch
            // service step. Replay it first so a restart at the depot cannot begin the next ore
            // batch from the wrong position; its checkpoint returns to the exact saved work face.
            // If planning currently fails because the tool/food is at that depot, run service alone;
            // assignNext's postcondition repair replans after the inventory has been replenished.
            MiningServiceTask.ServicePolicy restoredPolicy = restoredService.orElseThrow().policy();
            int pendingBoundary = restoredObsidian
                    .map(CreateObsidianTask.RestoreMetadata::pendingServiceBoundary)
                    .orElse(0);
            GoalStep serviceStep;
            if (restoredPolicy.profile() == MiningServiceTask.ServiceProfile.OBSIDIAN_8) {
                if (pendingBoundary <= 0) {
                    recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                            GoalResult.Status.FAILED,
                            "mission_restore_orphaned_obsidian_service");
                    return false;
                }
                serviceStep = GoalStep.obsidianService(
                        restoredService.orElseThrow().serviceBoundary(),
                        restoredService.orElseThrow().serviceTargetCount());
                rebuildObsidianAcquisition(goal, restoredSteps, List.of(serviceStep),
                        restoredObsidian.orElseThrow().targetCount());
                rebuiltObsidianAcquisition = true;
            } else if (restoredPolicy.profile()
                    == MiningServiceTask.ServiceProfile.OBSIDIAN_PREFLIGHT) {
                serviceStep = GoalStep.obsidianPreflight(
                        restoredService.orElseThrow().serviceTargetCount());
                int continuationTarget = restoredObsidian
                        .map(CreateObsidianTask.RestoreMetadata::targetCount)
                        .orElse(restoredService.orElseThrow().serviceTargetCount());
                rebuildObsidianAcquisition(goal, restoredSteps, List.of(serviceStep),
                        continuationTarget);
                rebuiltObsidianAcquisition = true;
            } else if (restoredPolicy.profile()
                    == MiningServiceTask.ServiceProfile.RARE_DESCENT_KIT) {
                serviceStep = GoalStep.rareDescentKitService(
                        interruptedServiceOres,
                        restoredService.orElseThrow().serviceTargetCount());
                // Entering KIT proves every bootstrap dependency was already completed. A fresh
                // inventory plan may rediscover resources currently stored in the mission chest;
                // retaining that acquisition prefix after replay would break the sealed
                // KIT->DESCEND hand-off and spend the freshly restored reserve twice. Keep only a
                // proven final descent/rare tail; otherwise run service alone and let the normal
                // postcondition repair plan from the completed live attestation.
                List<GoalStep> descentTail = rareDescentTail(
                        restoredSteps, interruptedServiceOres);
                restoredSteps.clear();
                restoredSteps.add(0, serviceStep);
                restoredSteps.addAll(descentTail);
            } else if (restoredPolicy.profile()
                    == MiningServiceTask.ServiceProfile.RARE_ORE_BATCH) {
                serviceStep = GoalStep.rareOreService(
                        interruptedServiceOres,
                        restoredService.orElseThrow().serviceBoundary(),
                        restoredService.orElseThrow().serviceTargetCount());
                restoredSteps.add(0, serviceStep);
            } else {
                boolean explicitCapacityPolicy = !restoredPolicy.maintainsTunnelingTools()
                        && restoredPolicy.emergencyBlocksReserved()
                        > MiningServiceTask.ServicePolicy.defaultOre(false)
                        .emergencyBlocksReserved();
                if ((explicitCapacityPolicy || capacityOrdinaryServiceRestore)
                        && ordinaryServiceParent.isEmpty()) {
                    queued.removeFirstOccurrence(goal);
                    recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                            GoalResult.classify(initialEvaluation, false),
                            "mission_restore_orphaned_capacity_handoff_cursor");
                    return false;
                }
                serviceStep = terminalOrdinaryServiceRestore || explicitCapacityPolicy
                        || capacityOrdinaryServiceRestore
                        ? GoalStep.miningHandoffService(interruptedServiceOres,
                        ordinaryServiceParent.orElseThrow().targetCount(),
                        restoredPolicy.emergencyBlocksReserved())
                        : GoalStep.miningService(interruptedServiceOres,
                        restore.completedSteps(), restoredPolicy.maintainsTunnelingTools());
                restoredSteps.add(0, serviceStep);
            }
        }
        if (committedPreflight) {
            int continuationTarget = restoredObsidian
                    .map(CreateObsidianTask.RestoreMetadata::targetCount)
                    .orElse(restoredService.orElseThrow().serviceTargetCount());
            rebuildObsidianAcquisition(goal, restoredSteps, List.of(), continuationTarget);
            rebuiltObsidianAcquisition = true;
        }
        if (!rebuiltObsidianAcquisition && interruptedObsidian) {
            rebuildObsidianAcquisition(goal, restoredSteps, List.of(),
                    restoredObsidian.orElseThrow().targetCount());
            rebuiltObsidianAcquisition = true;
        }
        if (!rebuiltObsidianAcquisition && !committedObsidianMake) {
            restoredObsidian.ifPresent(metadata ->
                    reconcileObsidianSteps(restoredSteps, metadata.targetCount(), false));
        }
        restoredDigDown.ifPresent(metadata -> reconcileDigDownSteps(restoredSteps, metadata));
        if (interruptedDescend) {
            reconcileDescendSteps(restoredSteps, restoredDescend.orElseThrow().targetY());
        }
        // replace 边界:A 活跃、B 已排队，随后同批 stop + B。只有 B 已成功规划后才从旧队列移除；
        // 若 replacement 规划失败，保留 B 让下一 tick 的常规 queue drain 再处理，不能静默丢目标。
        queued.removeFirstOccurrence(goal);
        if (restoredSteps.isEmpty()) {
            activePlans.remove(bot.getUuid());
            GoalResult.Status status = GoalResult.classify(initialEvaluation, false);
            String reason = !mergedSettledTerminalFailure.isBlank()
                    ? mergedSettledTerminalFailure
                    : restoredSettledServices.orElseThrow().isEmpty()
                    ? "empty_plan_unsatisfied"
                    : settledServiceFailureWithoutContinuation(
                    restoredSettledServices.orElseThrow());
            recordImmediateResult(bot, missionId, goal, startedTick,
                    initialEvaluation, status, reason,
                    restoredSkippedResults);
            return false;
        }
        HuntSearchCursor huntSearchCursor = restore == null
                ? HuntSearchCursor.initial() : restore.huntSearchCursor();
        ActivePlan active = new ActivePlan(missionId, startedTick, goal, predicate, context,
                new ArrayDeque<>(restoredSteps), restoredSteps.size(),
                restoredSteps.stream().map(GoalStep::describe).toList(),
                huntSearchCursor, restoredSkippedTargets);
        active.huntPickupSettlement = unsettledHuntPickup;
        if (restore != null) {
            active.completedSteps = (int) Math.min(Integer.MAX_VALUE,
                    (long) restore.completedSteps()
                            + (restoredCommittedCapacityParent ? 1L : 0L));
            active.lifetimeReplans = restore.lifetimeReplans();
            active.rareResourceRetriesUsed = restoredRareResourceEpoch;
            active.rareEpochMarginUsed = restore.rareEpochMarginUsed();
            active.replanCount = restore.replanCount();
            if (restore.postconditionRepair().persisted()) {
                active.postconditionReplans =
                        restore.postconditionRepair().replans();
                active.lastEvaluationMatched =
                        restore.postconditionRepair().lastMatched();
                active.lastRepairFingerprint =
                        restore.postconditionRepair().fingerprint();
            }
            restore.replanSnapshot().ifPresent(snapshot -> {
                active.snapSteps = snapshot.steps();
                active.snapTargetCount = snapshot.targetCount();
                active.snapX = snapshot.x();
                active.snapY = snapshot.y();
                active.snapZ = snapshot.z();
                active.snapDimension = snapshot.dimension();
                active.snapHuntRawMeat = snapshot.huntRawMeat() >= 0
                        ? snapshot.huntRawMeat() : rawMeatCount(bot);
                active.snapHuntVisitedSectors = snapshot.huntVisitedSectors() >= 0
                        ? snapshot.huntVisitedSectors()
                        : active.huntSearchCursor.visitedCount();
            });
            if (!discardRestoredTaskCheckpoint) {
                active.taskCheckpointKind = restore.taskCheckpointKind();
                active.taskCheckpoint.putAll(restore.taskCheckpoint());
            }
            active.miningCheckpoint.putAll(restoredMiningCheckpoint);
            if (!(committedService && restoredAuxiliaryMining
                    .filter(metadata -> !metadata.batchOpen()).isPresent())) {
                active.auxiliaryMiningCheckpoint.putAll(
                        restoredAuxiliaryMiningCheckpoint);
            }
            active.capacityParentNamespace = restoredCapacityParent;
            active.capacityParentDelivered = restoredCapacityParentDelivered;
            active.capacityParentFace = restoredCapacityParentFace;
            active.capacityParentServicesUsed = restoredCapacityParentServicesUsed;
            active.auxiliaryMiningContinuationFingerprint =
                    restoredAuxiliaryMiningContinuationFingerprint;
            restoredSettledServices.orElseThrow().forEach(settled ->
                    active.settledServiceTombstones.put(settled.key(), settled));
            if (!committedObsidianMake) {
                active.obsidianCheckpoint.putAll(restoredObsidianCheckpoint);
            }
            // Backward-compatible recovery for snapshots written before the dedicated
            // cross-batch cursor namespace existed.
            if (!discardRestoredTaskCheckpoint
                    && active.miningCheckpoint.isEmpty()
                    && restore.taskCheckpointKind() == GoalStep.Kind.MINE_ORE) {
                active.miningCheckpoint.putAll(restore.taskCheckpoint());
            }
            if (!committedObsidianMake && active.obsidianCheckpoint.isEmpty()
                    && restore.taskCheckpointKind() == GoalStep.Kind.MAKE_OBSIDIAN) {
                active.obsidianCheckpoint.putAll(restore.taskCheckpoint());
            }
            if (committedObsidianMake
                    && active.taskCheckpointKind == GoalStep.Kind.MAKE_OBSIDIAN) {
                active.taskCheckpoint.clear();
                active.taskCheckpointKind = null;
            }
            if (committedDescend
                    && active.taskCheckpointKind == GoalStep.Kind.DESCEND_TO_Y) {
                active.taskCheckpoint.clear();
                active.taskCheckpointKind = null;
            }
            if (committedService
                    && active.taskCheckpointKind == GoalStep.Kind.MINING_SERVICE) {
                active.taskCheckpoint.clear();
                active.taskCheckpointKind = null;
            }
        }
        if (restore == null || !restore.postconditionRepair().persisted()) {
            active.lastEvaluationMatched = initialEvaluation.matched();
        }
        // Legacy snapshots did not persist the replan baseline. Start them from current factual
        // state so old completed steps or a negative mining Y cannot manufacture progress on the
        // first post-restart failure. New snapshots retain the exact prior comparison boundary.
        if (restore == null || restore.replanSnapshot().isEmpty()) {
            net.minecraft.util.math.BlockPos sp0 = bot.getBlockPos();
            active.snapSteps = active.completedSteps;
            active.snapX = sp0.getX();
            active.snapY = sp0.getY();
            active.snapZ = sp0.getZ();
            active.snapTargetCount = goalTargetCount(bot, goal);
            active.snapDimension = bot.getServerWorld().getRegistryKey()
                    .getValue().toString();
            active.snapHuntRawMeat = rawMeatCount(bot);
            active.snapHuntVisitedSectors = active.huntSearchCursor.visitedCount();
        }
        activePlans.put(bot.getUuid(), active);
        // 工作记忆 episode 边界:新目标=新 episode,上一件事的排除项/轨迹作废。
        // (replan 不走这里——handleStepFailure 原地改 plan.steps,工作记忆跨 replan 存活,这正是设计。)
        io.github.zoyluo.aibot.task.EpisodeMemory.INSTANCE.reset(bot.getUuid());
        userGoal.putIfAbsent(bot.getUuid(), goal); // B:首个目标记为"用户原始目标";后续前置子目标被上面拦下,换目标由用户消息清空
        BotLog.task(bot, "goal_plan", "goal", goal,
                "steps", restoredSteps.stream().map(GoalStep::describe).toList());
        if (!pocketRestorePreflights.contains(bot.getUuid())) {
            report(bot, "我会按 " + restoredSteps.size() + " 步完成目标。");
        }
        captureTransitionAndAssignNext(bot, active);
        return true;
    }

    public boolean tickBot(MinecraftServer server, AIPlayerEntity bot) {
        MissionRuntimeRecord suspended = deathSuspended.get(bot.getUuid());
        if (suspended != null) {
            if (restoreQuarantined.containsKey(bot.getUuid())) {
                return true;
            }
            if (!bot.isAlive()) {
                return true;
            }
            String requiredDimension = dimensionSuspended.get(bot.getUuid());
            if (requiredDimension != null && !requiredDimension.equals(
                    bot.getServerWorld().getRegistryKey().getValue().toString())) {
                return true;
            }
            Optional<Task> recovery = TaskManager.INSTANCE.getActive(bot);
            if (recovery.isPresent() || TaskManager.INSTANCE.hasPaused(bot)) {
                return true;
            }
            if (deathSuspended.remove(bot.getUuid(), suspended)) {
                dimensionSuspended.remove(bot.getUuid());
                restoreQuarantined.remove(bot.getUuid());
                BotLog.lifecycle(bot, "mission_death_resume",
                        "mission_id", suspended.active() == null ? "queued_only" : suspended.active().missionId());
                restoreRuntime(bot, suspended);
                markDirty(bot);
            }
            return hasActivePlan(bot) || queuedGoalCount(bot) > 0;
        }
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            return hasActivePlan(bot) || queuedGoalCount(bot) > 0 || TaskManager.INSTANCE.hasPaused(bot);
        }
        ActivePlan plan = activePlans.get(bot.getUuid());
        if (plan == null) {
            if (TaskManager.INSTANCE.getActive(bot).isEmpty()
                    && !TaskManager.INSTANCE.hasPaused(bot)
                    && !bot.getActionPack().hasActiveActions()) {
                return startNextQueuedIfIdle(bot);
            }
            return false;
        }
        if (plan.huntSearchCursor.consumeDirty()) {
            markDirty(bot);
        }
        if (plan.currentTask instanceof HuntTask hunt
                && hunt.consumeCheckpointDirty()) {
            captureTaskEvidence(bot, plan);
            markDirty(bot);
        }
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        if (active.isPresent()) {
            if (plan.currentTask != null && active.get() != plan.currentTask) {
                TaskState missionState = plan.currentTask.state();
                if (missionState == TaskState.COMPLETED
                        || missionState == TaskState.FAILED) {
                    // A safety task may be assigned after TaskManager removed this terminal
                    // mission task but before GoalExecutor commits its checkpoint. Do not mistake
                    // that safety ownership for a user replacement, and do not assign the next
                    // mission step over it. Once safety releases the slot, the ordinary terminal
                    // branch below settles the original task exactly once.
                    return true;
                }
                // FREEZE fix:有外来活跃任务时,先看我们的 step 是否被暂存进 paused 池。
                // 生存任务(战斗/逃跑/进食)抢占会把当前 step pauseFor 进 paused 池——这是临时抢占,
                // 打完会 resume,绝不能放弃整个目标(实测:刷怪→combat→goal_abandoned×12→从零重规划空转)。
                if (TaskManager.INSTANCE.hasPaused(bot)) {
                    return true;
                }
                // step 既不活跃也不在暂停池 = 被玩家显式指令真正替换 → 放弃目标让位。
                BotLog.task(bot, "goal_abandoned", "goal", plan.goal, "reason", "foreign_task_assigned");
                finishActive(bot, plan, evaluate(bot, plan), "foreign_task_assigned", false, true);
                return false;
            }
            return true;
        }
        // GOALFIX-GF1 P0-B:当前步被安全网暂停(生存任务抢占)→ 等待 resume,不要误判为步骤结束而跳步。
        if (TaskManager.INSTANCE.hasPaused(bot)) {
            return true;
        }
        if (plan.current == null) {
            assignNext(bot, plan);
            return true;
        }
        // SAFETY work has its own lifecycle and may have completed/failed after the mission step
        // stopped. The global lastStatus is therefore not an identity-safe source for the current
        // plan step (an Evade failure previously replaced OreDig's real terminal reason). Read the
        // task object that was actually assigned for this step.
        TaskStatus status = plan.currentTask == null
                ? TaskManager.INSTANCE.status(bot)
                : TaskStatus.from(plan.currentTask);
        if (status.state() == TaskState.COMPLETED) {
            BotLog.task(bot, "goal_step_completed", "step", plan.current.describe());
            captureTaskEvidence(bot, plan);
            if (plan.huntPickupSettlement) {
                settleRestoredHuntPickup(bot, plan);
                return true;
            }
            if (plan.current.kind() == GoalStep.Kind.MINE_ORE
                    && rareMissionTargetForMiningStep(plan.goal, plan.current.ores()) > 0) {
                int completedEpoch = plan.rareResourceRetriesUsed;
                if (!settleCompletedRareBatch(plan)) {
                    finishActive(bot, plan, evaluate(bot, plan),
                            "rare_batch_commit_checkpoint_invalid", false, true);
                    return true;
                }
                BotLog.task(bot, "goal_rare_batch_resource_epoch_settled",
                        "completed_epoch", completedEpoch,
                        "next_epoch", 0);
            }
            if (plan.current.kind() == GoalStep.Kind.MINE_ORE
                    && plan.capacityParentNamespace != null
                    && plan.currentCapacityParentRetry
                    && currentOreTaskOwnsCapacityParent(plan)
                    && !settleCompletedCapacityParent(plan)) {
                finishActive(bot, plan, evaluate(bot, plan),
                        "capacity_parent_commit_checkpoint_invalid", false, true,
                        GoalResult.Status.FAILED);
                return true;
            }
            if (plan.current.kind() == GoalStep.Kind.MAKE_OBSIDIAN) {
                Optional<CreateObsidianTask.RestoreMetadata> metadata =
                        CreateObsidianTask.inspectCheckpoint(plan.obsidianCheckpoint);
                if (metadata.isEmpty()) {
                    finishActive(bot, plan, evaluate(bot, plan),
                            "create_obsidian_completed_without_checkpoint", false, true);
                    return true;
                }
                if (metadata.isPresent() && metadata.orElseThrow().pendingServiceBoundary() > 0) {
                    GoalEvaluation boundaryEvaluation = evaluate(bot, plan);
                    if (boundaryEvaluation.state() == GoalEvaluation.State.SATISFIED) {
                        finishActive(bot, plan, boundaryEvaluation,
                                "postcondition_satisfied_at_obsidian_boundary", false, true);
                        return true;
                    }
                    int boundary = metadata.orElseThrow().pendingServiceBoundary();
                    clearCompletedTaskCheckpoint(plan);
                    plan.completedSteps++;
                    // addFirst in reverse execution order: service commits before the original
                    // target CreateObsidianTask is reconstructed from its dedicated checkpoint.
                    GoalStep continuation = GoalStep.makeObsidian(
                            metadata.orElseThrow().targetCount());
                    GoalStep service = GoalStep.obsidianService(
                            boundary, metadata.orElseThrow().targetCount());
                    plan.steps.addFirst(continuation);
                    plan.steps.addFirst(service);
                    plan.totalSteps += 2;
                    plan.stepLabels.add(service.describe());
                    plan.stepLabels.add(continuation.describe());
                    BotLog.task(bot, "goal_obsidian_service_scheduled",
                            "boundary", boundary,
                            "target", metadata.orElseThrow().targetCount());
                    plan.current = null;
                    plan.currentTask = null;
                    captureTransitionAndAssignNext(bot, plan);
                    return true;
                }
            }
            if (plan.current.kind() == GoalStep.Kind.MINING_SERVICE
                    && plan.current.isObsidianService()) {
                Optional<CreateObsidianTask.RestoreMetadata> metadata =
                        CreateObsidianTask.inspectCheckpoint(plan.obsidianCheckpoint);
                int expectedBoundary = metadata
                        .map(CreateObsidianTask.RestoreMetadata::pendingServiceBoundary)
                        .orElse(0);
                Optional<MiningServiceTask.RestoreMetadata> serviceMetadata =
                        MiningServiceTask.inspectCheckpoint(plan.taskCheckpoint);
                boolean identityMatches = metadata.isPresent() && serviceMetadata.isPresent()
                        && serviceMetadata.orElseThrow().done()
                        && serviceMetadata.orElseThrow().policy().profile()
                        == MiningServiceTask.ServiceProfile.OBSIDIAN_8
                        && plan.missionId.toString().equals(
                        serviceMetadata.orElseThrow().serviceMissionId())
                        && serviceMetadata.orElseThrow().serviceTargetCount()
                        == metadata.orElseThrow().targetCount()
                        && serviceMetadata.orElseThrow().serviceBoundary() == expectedBoundary
                        && serviceMetadata.orElseThrow().policy().equals(
                        MiningServiceTask.ServicePolicy.obsidian8(
                                metadata.orElseThrow().targetCount(), expectedBoundary));
                Optional<Map<String, String>> acknowledged = identityMatches
                        && expectedBoundary == plan.current.count()
                        ? CreateObsidianTask.acknowledgeServiceBoundary(plan.obsidianCheckpoint)
                        : Optional.empty();
                if (acknowledged.isEmpty()) {
                    finishActive(bot, plan, evaluate(bot, plan),
                            "obsidian_service_ack_failed:expected=" + expectedBoundary
                                    + ":completed=" + plan.current.count(), false, true);
                    return true;
                }
                plan.obsidianCheckpoint.clear();
                plan.obsidianCheckpoint.putAll(acknowledged.orElseThrow());
                BotLog.task(bot, "goal_obsidian_service_acknowledged",
                        "boundary", expectedBoundary);
            }
            if (plan.current.kind() == GoalStep.Kind.ACQUIRE_WATER) {
                retireRelocatedOrdinaryMiningCheckpoint(bot, plan);
            }
            if (plan.current.kind() == GoalStep.Kind.MINING_SERVICE) {
                retireClosedAuxiliaryMiningCheckpoint(plan);
            }
            clearCompletedTaskCheckpoint(plan);
            plan.completedSteps++; // Phase A:完成一步=进展信号
            GoalEvaluation completedEvaluation = evaluate(bot, plan);
            if (completedEvaluation.state() == GoalEvaluation.State.SATISFIED) {
                finishActive(bot, plan, completedEvaluation,
                        "postcondition_satisfied_after_step", false, true);
                return true;
            }
            plan.current = null;
            plan.currentTask = null;
            captureTransitionAndAssignNext(bot, plan);
            return true;
        }
        if (status.state() == TaskState.FAILED) {
            if ((status.failureReason().startsWith("mining_service_dimension_mismatch:")
                    || status.failureReason().startsWith("hunt_pickup_dimension_mismatch:"))
                    && suspendDimensionBoundPocket(bot, plan)) {
                return true;
            }
            handleStepFailure(server, bot, plan, status.failureReason());
            return true;
        }
        // GOALFIX-GF1 P0-B:其它状态(如上一任务残留的 lastStatus)→ 防御性 no-op,
        // 步骤推进只由 COMPLETED 分支驱动,失败由 FAILED 分支驱动。
        return true;
    }

    private void settleRestoredHuntPickup(AIPlayerEntity bot, ActivePlan plan) {
        Optional<HuntPickupCheckpoint.Metadata> receipt =
                HuntPickupCheckpoint.inspect(plan.taskCheckpoint);
        if (plan.current == null || plan.current.kind() != GoalStep.Kind.HUNT
                || !(plan.currentTask instanceof HuntTask hunt)
                || !hunt.isSettlementOnly()
                || receipt.isEmpty() || receipt.orElseThrow().open()
                || !trustedClosedHuntPickupReceipt(
                bot, receipt.orElseThrow())) {
            finishActive(bot, plan, evaluate(bot, plan),
                    "hunt_pickup_settlement_receipt_invalid",
                    false, true, GoalResult.Status.FAILED);
            return;
        }
        plan.taskCheckpoint.clear();
        plan.taskCheckpointKind = null;
        plan.huntPickupSettlement = false;
        plan.current = null;
        plan.currentTask = null;
        plan.steps.clear();

        GoalEvaluation evaluation = evaluate(bot, plan);
        if (evaluation.state() == GoalEvaluation.State.SATISFIED) {
            finishActive(bot, plan, evaluation,
                    "postcondition_satisfied_after_hunt_pickup_settlement",
                    false, true);
            return;
        }
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                bot, plan.goal, snapshotContext(plan), plan.missionId.toString());
        List<GoalStep> continuation = applySkippedTargetReceipts(
                fresh.success() ? fresh.steps() : List.of(),
                plan.skippedTargetReceipts);
        if (!fresh.success() || continuation.isEmpty()) {
            finishActive(bot, plan, evaluation,
                    fresh.success() ? "hunt_pickup_settlement_replan_empty"
                            : "hunt_pickup_settlement_replan_failed:"
                            + String.join(",", fresh.unresolved()),
                    false, true);
            return;
        }
        plan.steps.addAll(continuation);
        plan.totalSteps = continuation.size();
        plan.stepLabels.clear();
        plan.stepLabels.addAll(
                continuation.stream().map(GoalStep::describe).toList());
        BotLog.task(bot, "goal_hunt_pickup_settlement_replanned",
                "steps", continuation.stream().map(GoalStep::describe).toList(),
                "postcondition_replans", plan.postconditionReplans,
                "failure_replans", plan.replanCount);
        captureTransitionAndAssignNext(bot, plan);
    }

    static boolean hasSameDimensionOpenHuntTimeRollback(
            HuntPickupCheckpoint.Metadata metadata,
            String liveDimension,
            long currentWorldTime) {
        return metadata != null && metadata.open()
                && metadata.dimension().equals(liveDimension)
                && currentWorldTime < metadata.pickupStartedWorldTime();
    }

    static boolean trustedClosedHuntPickupReceipt(
            HuntPickupCheckpoint.Metadata metadata,
            String liveDimension,
            int currentInventory,
            int currentPickupStat,
            long currentWorldTime) {
        if (metadata == null || metadata.open()
                || !metadata.dimension().equals(liveDimension)) {
            return false;
        }
        return switch (metadata.transactionState()) {
            case CLOSED_COLLECTED -> HuntPickupCheckpoint.collectionCoversBoundUnits(
                    metadata.inventoryBaseline(), currentInventory,
                    metadata.pickupStatBaseline(), currentPickupStat,
                    Math.max(1, metadata.boundUnits()));
            case CLOSED_NO_RAW -> metadata.boundUnits() == 0
                    && HuntPickupCheckpoint.ageAt(
                    metadata.pickupStartedWorldTime(), currentWorldTime)
                    >= HuntPickupCheckpoint.RECOVERY_LIMIT_TICKS;
            case OPEN -> false;
        };
    }

    private static boolean trustedClosedHuntPickupReceipt(
            AIPlayerEntity bot, HuntPickupCheckpoint.Metadata metadata) {
        Identifier expectedId = metadata == null
                ? null : Identifier.tryParse(metadata.expectedRawItemId());
        Item expected = expectedId == null
                ? null : Registries.ITEM.getOptionalValue(expectedId).orElse(null);
        if (expected == null
                || !Registries.ITEM.getId(expected).toString().equals(
                metadata.expectedRawItemId())) {
            return false;
        }
        int inventory = io.github.zoyluo.aibot.action.HarvestCore.countInventoryItems(
                bot, Set.of(expected));
        int pickupStat = bot.getStatHandler().getStat(Stats.PICKED_UP, expected);
        return trustedClosedHuntPickupReceipt(
                metadata,
                bot.getServerWorld().getRegistryKey().getValue().toString(),
                inventory,
                pickupStat,
                bot.getServerWorld().getTime());
    }

    public boolean hasActivePlan(AIPlayerEntity bot) {
        return activePlans.containsKey(bot.getUuid()) || deathSuspended.containsKey(bot.getUuid());
    }

    /** Exact active-goal probe used by deterministic runtime verification and diagnostics. */
    public boolean isActiveGoal(AIPlayerEntity bot, Goal goal) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        if (plan != null) {
            return plan.goal.equals(goal);
        }
        MissionRuntimeRecord suspended = deathSuspended.get(bot.getUuid());
        return suspended != null && suspended.active() != null
                && suspended.active().spec() != null
                && suspended.active().spec().toGoal().filter(goal::equals).isPresent();
    }

    public void clear(AIPlayerEntity bot) {
        cancelAll(bot);
    }

    /** Detaches only the active mission. Queue promotion is deliberately a separate step. */
    public boolean cancelCurrent(AIPlayerEntity bot) {
        return cancelCurrent(bot, "intent_cancelled");
    }

    public boolean cancelCurrent(AIPlayerEntity bot, String reason) {
        UUID uuid = bot.getUuid();
        ActivePlan active = activePlans.get(uuid);
        MissionRuntimeRecord suspended = deathSuspended.remove(uuid);
        dimensionSuspended.remove(uuid);
        restoreQuarantined.remove(uuid);
        boolean changed = active != null || suspended != null;
        if (active != null) {
            finishActive(bot, active, evaluate(bot, active), reason, true, false);
        }
        changed |= userGoal.remove(uuid) != null;
        changed |= lastGoalFailTick.remove(uuid) != null;
        if (changed) {
            io.github.zoyluo.aibot.task.EpisodeMemory.INSTANCE.reset(uuid);
            // Suspended/quarantined Missions have no ActivePlan for finishActive to persist.
            // Explicit cancellation must durably remove their raw physical checkpoint as well.
            markDirty(bot);
        }
        return changed;
    }

    public int clearQueue(AIPlayerEntity bot) {
        java.util.Deque<Goal> queued = goalQueue.remove(bot.getUuid());
        int removed = queued == null ? 0 : queued.size();
        if (removed > 0) {
            markDirty(bot);
        }
        return removed;
    }

    public boolean cancelAll(AIPlayerEntity bot) {
        boolean changed = cancelCurrent(bot);
        return clearQueue(bot) > 0 || changed;
    }

    /** Death is a factual failure, not a user cancellation. Queue promotion waits for recovery to finish. */
    public boolean failCurrent(AIPlayerEntity bot, String reason) {
        ActivePlan active = activePlans.get(bot.getUuid());
        if (active == null) {
            return false;
        }
        finishActive(bot, active, evaluate(bot, active), reason, false, false, GoalResult.Status.FAILED);
        return true;
    }

    /** Detach a Mission without publishing a terminal result while corpse recovery runs. */
    public boolean suspendForDeath(AIPlayerEntity bot) {
        UUID uuid = bot.getUuid();
        if (deathSuspended.containsKey(uuid)) {
            return true;
        }
        MissionRuntimeRecord runtime = captureRuntime(bot);
        if (runtime.active() == null && runtime.queue().isEmpty()) {
            return false;
        }
        dimensionSuspended.remove(uuid);
        restoreQuarantined.remove(uuid);
        deathSuspended.put(uuid, runtime);
        activePlans.remove(uuid);
        goalQueue.remove(uuid);
        userGoal.remove(uuid);
        BotLog.lifecycle(bot, "mission_death_suspended",
                "mission_id", runtime.active() == null ? "queued_only" : runtime.active().missionId(),
                "queued", runtime.queue().size());
        markDirty(bot);
        return true;
    }

    /**
     * Detaches an open service pocket without publishing failure when external movement changes
     * dimensions. The exact ledger remains persistable in deathSuspended; tickBot restores it only
     * after the bot returns to the checkpoint's dimension.
     */
    private boolean suspendDimensionBoundPocket(AIPlayerEntity bot, ActivePlan plan) {
        if (bot == null || plan == null || plan.current == null) {
            return false;
        }
        captureTaskEvidence(bot, plan);
        Optional<String> required = Optional.empty();
        if (plan.current.kind() == GoalStep.Kind.MINING_SERVICE) {
            required =
                MiningServiceTask.inspectCheckpoint(plan.taskCheckpoint)
                        .filter(ignored -> hasActiveServicePocket(plan.taskCheckpoint))
                        .map(MiningServiceTask.RestoreMetadata::serviceDimension);
        } else if (plan.current.kind() == GoalStep.Kind.HUNT) {
            required = HuntPickupCheckpoint.inspect(plan.taskCheckpoint)
                    .filter(HuntPickupCheckpoint.Metadata::open)
                    .map(HuntPickupCheckpoint.Metadata::dimension);
        }
        if (required.isEmpty()) {
            return false;
        }
        MissionRuntimeRecord runtime = captureRuntime(bot);
        if (runtime.active() == null) {
            return false;
        }
        UUID uuid = bot.getUuid();
        String requiredDimension = required.orElseThrow();
        deathSuspended.put(uuid, runtime);
        dimensionSuspended.put(uuid, requiredDimension);
        restoreQuarantined.remove(uuid);
        activePlans.remove(uuid);
        goalQueue.remove(uuid);
        userGoal.remove(uuid);
        TaskManager.INSTANCE.abort(bot);
        BotLog.lifecycle(bot, "mission_dimension_suspended",
                "mission_id", runtime.active().missionId(),
                "required_dimension", requiredDimension,
                "live_dimension", bot.getServerWorld().getRegistryKey()
                        .getValue().toString());
        markDirty(bot);
        return true;
    }

    /** Drop every in-memory projection for a Bot without publishing a terminal result (server unload path). */
    public void unload(AIPlayerEntity bot) {
        UUID uuid = bot.getUuid();
        activePlans.remove(uuid);
        goalQueue.remove(uuid);
        lastGoalFailTick.remove(uuid);
        userGoal.remove(uuid);
        lastResults.remove(uuid);
        deathSuspended.remove(uuid);
        dimensionSuspended.remove(uuid);
        restoreQuarantined.remove(uuid);
        pocketRestorePreflights.remove(uuid);
        io.github.zoyluo.aibot.task.EpisodeMemory.INSTANCE.reset(uuid);
    }

    public void clearAllRuntime() {
        activePlans.clear();
        goalQueue.clear();
        lastGoalFailTick.clear();
        userGoal.clear();
        lastResults.clear();
        deathSuspended.clear();
        dimensionSuspended.clear();
        restoreQuarantined.clear();
        pocketRestorePreflights.clear();
    }

    public int queuedGoalCount(AIPlayerEntity bot) {
        java.util.Deque<Goal> queued = goalQueue.get(bot.getUuid());
        return queued == null ? 0 : queued.size();
    }

    public Optional<GoalResult> lastResult(AIPlayerEntity bot) {
        return Optional.ofNullable(lastResults.get(bot.getUuid()));
    }

    public Optional<GoalResult> resultAfter(AIPlayerEntity bot, long sequence) {
        GoalResult result = lastResults.get(bot.getUuid());
        return result != null && result.sequence() > sequence ? Optional.of(result) : Optional.empty();
    }

    public String resultSummary(GoalResult result) {
        return resultMessage(result.status(), result.evaluation(), result.reason());
    }

    public MissionRuntimeRecord captureRuntime(AIPlayerEntity bot) {
        MissionRuntimeRecord suspended = deathSuspended.get(bot.getUuid());
        if (suspended != null) {
            return suspended;
        }
        ActivePlan active = activePlans.get(bot.getUuid());
        if (active != null) {
            captureTaskEvidence(bot, active);
        }
        MissionRecord activeRecord = active == null ? null : new MissionRecord(
                active.missionId.toString(), MissionSpec.fromGoal(active.goal), checkpoint(active));
        java.util.Deque<Goal> queued = goalQueue.get(bot.getUuid());
        List<MissionSpec> queue = queued == null ? List.of() : queued.stream().map(MissionSpec::fromGoal).toList();
        boolean hasPersistableMission = activeRecord != null || !queue.isEmpty();
        return new MissionRuntimeRecord(activeRecord, queue,
                hasPersistableMission && TaskManager.INSTANCE.isUserPaused(bot));
    }

    public void restoreRuntime(AIPlayerEntity bot, MissionRuntimeRecord runtime) {
        if (runtime == null) {
            return;
        }
        Map<String, String> rawTaskCheckpoint = activeTaskCheckpoint(runtime);
        boolean rawActivePocket = hasActiveServicePocket(rawTaskCheckpoint);
        Optional<MiningServiceTask.RestoreMetadata> rawService =
                MiningServiceTask.inspectCheckpoint(rawTaskCheckpoint);
        if (rawActivePocket
                && rawService.filter(metadata -> !metadata.serviceDimension().isBlank())
                .isEmpty()) {
            quarantinePhysicalPocketRestore(bot, runtime,
                    "invalid_mining_service_pocket_checkpoint");
            return;
        }
        Optional<String> pocketDimension = activePocketServiceDimension(runtime);
        String liveDimension = bot.getServerWorld().getRegistryKey()
                .getValue().toString();
        if (pocketDimension.filter(required -> !required.equals(liveDimension)).isPresent()) {
            UUID uuid = bot.getUuid();
            deathSuspended.put(uuid, runtime);
            dimensionSuspended.put(uuid, pocketDimension.orElseThrow());
            restoreQuarantined.remove(uuid);
            activePlans.remove(uuid);
            goalQueue.remove(uuid);
            userGoal.remove(uuid);
            TaskManager.INSTANCE.abort(bot);
            BotLog.lifecycle(bot, "mission_dimension_suspended",
                    "required_dimension", pocketDimension.orElseThrow(),
                    "live_dimension", liveDimension);
            markDirty(bot);
            return;
        }
        MissionRecord activeRecord = runtime.active();
        if (rawActivePocket
                && (activeRecord == null || activeRecord.spec() == null)) {
            quarantinePhysicalPocketRestore(bot, runtime,
                    "missing_goal_for_mining_service_pocket");
            return;
        }
        if (activeRecord != null && activeRecord.spec() != null) {
            Optional<Goal> restored = activeRecord.spec().toGoal();
            if (restored.isPresent()) {
                boolean submitted;
                if (rawActivePocket) {
                    pocketRestorePreflights.add(bot.getUuid());
                }
                try {
                    submitted = submit(bot, restored.get(),
                            restoreSeed(bot, restored.get(), activeRecord));
                    if (rawActivePocket && (!submitted
                            || !restoredActivePocketAuthority(
                            bot, activeRecord.missionId(), rawTaskCheckpoint))) {
                        quarantinePhysicalPocketRestore(bot, runtime,
                                "mining_service_pocket_restore_not_established");
                        return;
                    }
                } catch (RuntimeException restoreFailure) {
                    if (!rawActivePocket) {
                        throw restoreFailure;
                    }
                    BotLog.error(bot, "mission_pocket_restore_failed",
                            restoreFailure, "mission_id", activeRecord.missionId());
                    quarantinePhysicalPocketRestore(bot, runtime,
                            "mining_service_pocket_restore_start_failed");
                    return;
                } finally {
                    if (rawActivePocket) {
                        pocketRestorePreflights.remove(bot.getUuid());
                    }
                }
                // This is a replay of an existing mission, not a new plan announcement. Commit
                // exactly the task-level assignment that was withheld during semantic preflight.
                if (rawActivePocket
                        && !TaskManager.INSTANCE.publishCurrentAssignment(bot)) {
                    quarantinePhysicalPocketRestore(bot, runtime,
                            "mining_service_pocket_restore_assignment_lost");
                    return;
                }
            } else if (restored.isEmpty()) {
                if (rawActivePocket) {
                    quarantinePhysicalPocketRestore(bot, runtime,
                            "invalid_goal_for_mining_service_pocket");
                    return;
                }
                BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, bot,
                        "mission_restore_isolated", "type", activeRecord.spec().type(), "reason", "invalid_spec");
            }
        }
        for (MissionSpec spec : runtime.queue()) {
            Optional<Goal> queued = spec.toGoal();
            if (queued.isPresent()) {
                submit(bot, queued.get());
            } else {
                BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, bot,
                        "mission_queue_restore_isolated", "type", spec.type(), "reason", "invalid_spec");
            }
        }
        if (runtime.userPaused() && (hasActivePlan(bot) || queuedGoalCount(bot) > 0)) {
            TaskManager.INSTANCE.pauseUserIntent(bot, "restore_persisted_pause");
        }
    }

    private boolean restoredActivePocketAuthority(AIPlayerEntity bot,
                                                  String missionId,
                                                  Map<String, String> rawCheckpoint) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        if (plan == null || missionId == null
                || !plan.missionId.toString().equals(missionId)
                || plan.current == null
                || plan.current.kind() != GoalStep.Kind.MINING_SERVICE
                || !(plan.currentTask instanceof MiningServiceTask service)
                || (service.state() != TaskState.RUNNING
                && service.state() != TaskState.PAUSED)
                || TaskManager.INSTANCE.getActive(bot)
                .filter(task -> task == service).isEmpty()) {
            return false;
        }
        Map<String, String> liveCheckpoint = service.checkpoint();
        if (!hasActiveServicePocket(liveCheckpoint)) {
            return false;
        }
        Optional<MiningServiceTask.RestoreMetadata> raw =
                MiningServiceTask.inspectCheckpoint(rawCheckpoint);
        Optional<MiningServiceTask.RestoreMetadata> live =
                MiningServiceTask.inspectCheckpoint(liveCheckpoint);
        if (raw.isEmpty() || live.isEmpty()
                || !persistedServiceAuthority(raw.orElseThrow()).equals(
                persistedServiceAuthority(live.orElseThrow()))) {
            return false;
        }
        if (!java.util.Objects.equals(rawCheckpoint.get("phase"),
                liveCheckpoint.get("phase"))) {
            return false;
        }
        return SERVICE_POCKET_IDENTITY_KEYS.stream().allMatch(key ->
                java.util.Objects.equals(
                        rawCheckpoint.get(key), liveCheckpoint.get(key)));
    }

    private void quarantinePhysicalPocketRestore(AIPlayerEntity bot,
                                                 MissionRuntimeRecord runtime,
                                                 String reason) {
        UUID uuid = bot.getUuid();
        deathSuspended.put(uuid, runtime);
        dimensionSuspended.remove(uuid);
        restoreQuarantined.put(uuid, reason);
        activePlans.remove(uuid);
        goalQueue.remove(uuid);
        userGoal.remove(uuid);
        lastResults.remove(uuid);
        lastGoalFailTick.remove(uuid);
        TaskManager.INSTANCE.resetToIdle(bot);
        BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, bot,
                "mission_restore_quarantined", "reason", reason,
                "mission_id", runtime.active() == null
                        ? "queued_only" : runtime.active().missionId());
        markDirty(bot);
    }

    private static Map<String, String> activeTaskCheckpoint(
            MissionRuntimeRecord runtime) {
        MissionRecord active = runtime == null ? null : runtime.active();
        if (active == null || active.checkpoint() == null) {
            return Map.of();
        }
        Map<String, String> task = new java.util.LinkedHashMap<>();
        active.checkpoint().forEach((key, value) -> {
            if (key.startsWith("task.") && key.length() > "task.".length()) {
                task.put(key.substring("task.".length()), value);
            }
        });
        return Map.copyOf(task);
    }

    private static Optional<String> activePocketServiceDimension(
            MissionRuntimeRecord runtime) {
        Map<String, String> task = activeTaskCheckpoint(runtime);
        MissionRecord active = runtime == null ? null : runtime.active();
        String taskKind = active == null || active.checkpoint() == null
                ? "" : active.checkpoint().getOrDefault("task_kind", "");
        Optional<String> huntDimension = "HUNT".equals(taskKind)
                ? HuntPickupCheckpoint.inspect(task)
                .filter(HuntPickupCheckpoint.Metadata::open)
                .map(HuntPickupCheckpoint.Metadata::dimension)
                : Optional.empty();
        if (huntDimension.isPresent()) {
            return huntDimension;
        }
        if (!hasActiveServicePocket(task)) {
            return Optional.empty();
        }
        return MiningServiceTask.inspectCheckpoint(task)
                .map(MiningServiceTask.RestoreMetadata::serviceDimension)
                .filter(dimension -> !dimension.isBlank());
    }

    private static Map<String, String> checkpoint(ActivePlan active) {
        Map<String, String> checkpoint = new java.util.LinkedHashMap<>();
        checkpoint.put("origin", encodePos(active.origin));
        checkpoint.put("started_tick", String.valueOf(active.startedTick));
        if (active.buildAnchor != null) {
            checkpoint.put("build_anchor", encodePos(active.buildAnchor));
            checkpoint.put("build_placed", String.valueOf(active.buildPlaced));
            checkpoint.put("build_skipped", String.valueOf(active.buildSkipped));
        }
        if (!active.boundContainers.isEmpty()) {
            checkpoint.put("containers", active.boundContainers.stream().map(GoalExecutor::encodePos).sorted()
                    .collect(java.util.stream.Collectors.joining(";")));
        }
        checkpoint.put("revision", String.valueOf(active.completedSteps));
        checkpoint.put("lifetime_replans", String.valueOf(active.lifetimeReplans));
        checkpoint.put("rare_resource_retries_used", String.valueOf(
                active.rareResourceRetriesUsed));
        checkpoint.put("rare_epoch_margin_used", String.valueOf(
                active.rareEpochMarginUsed));
        checkpoint.put("replan_count", String.valueOf(active.replanCount));
        checkpoint.putAll(encodePostconditionRepairCheckpoint(
                active.postconditionReplans,
                active.lastEvaluationMatched,
                active.lastRepairFingerprint));
        checkpoint.put("snap_steps", String.valueOf(active.snapSteps));
        checkpoint.put("snap_target", String.valueOf(active.snapTargetCount));
        checkpoint.put("snap_x", String.valueOf(active.snapX));
        checkpoint.put("snap_y", String.valueOf(active.snapY));
        checkpoint.put("snap_z", String.valueOf(active.snapZ));
        checkpoint.put("snap_dimension", active.snapDimension);
        checkpoint.put("snap_hunt_raw_meat", String.valueOf(active.snapHuntRawMeat));
        checkpoint.put("snap_hunt_visited_sectors",
                String.valueOf(active.snapHuntVisitedSectors));
        checkpoint.putAll(encodeHuntSearchCursorNamespace(active.huntSearchCursor));
        checkpoint.putAll(encodeSkippedTargetReceipts(active.skippedTargetReceipts));
        if (active.taskCheckpointKind != null && !active.taskCheckpoint.isEmpty()) {
            checkpoint.put("task_kind", active.taskCheckpointKind.name());
            active.taskCheckpoint.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> checkpoint.put("task." + entry.getKey(), entry.getValue()));
        }
        if (!active.miningCheckpoint.isEmpty()) {
            active.miningCheckpoint.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> checkpoint.put("mining." + entry.getKey(), entry.getValue()));
        }
        if (!active.auxiliaryMiningCheckpoint.isEmpty()) {
            active.auxiliaryMiningCheckpoint.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> checkpoint.put(
                            "aux_mining." + entry.getKey(), entry.getValue()));
        }
        if (active.capacityParentNamespace != null) {
            checkpoint.put("capacity_parent", active.capacityParentNamespace.persistedName);
            checkpoint.put(CAPACITY_PARENT_DELIVERED_KEY,
                    String.valueOf(active.capacityParentDelivered));
            checkpoint.put(CAPACITY_PARENT_FACE_KEY,
                    encodePos(active.capacityParentFace));
            checkpoint.put(CAPACITY_PARENT_SERVICES_USED_KEY,
                    String.valueOf(active.capacityParentServicesUsed));
        }
        if (!active.auxiliaryMiningContinuationFingerprint.isBlank()) {
            checkpoint.put(AUXILIARY_MINING_CONTINUATION_KEY,
                    active.auxiliaryMiningContinuationFingerprint);
        }
        if (!active.obsidianCheckpoint.isEmpty()) {
            active.obsidianCheckpoint.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> checkpoint.put("obsidian." + entry.getKey(), entry.getValue()));
        }
        if (!active.settledServiceTombstones.isEmpty()) {
            checkpoint.put(SETTLED_SERVICE_PREFIX + "count",
                    String.valueOf(active.settledServiceTombstones.size()));
            int index = 0;
            for (SettledServiceTombstone settled
                    : active.settledServiceTombstones.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue).toList()) {
                String entryPrefix = SETTLED_SERVICE_PREFIX + index++ + ".";
                settled.encode().forEach((key, value) ->
                        checkpoint.put(entryPrefix + key, value));
            }
        }
        return Map.copyOf(checkpoint);
    }

    static Map<String, String> encodeHuntSearchCursorNamespace(HuntSearchCursor cursor) {
        java.util.LinkedHashMap<String, String> encoded = new java.util.LinkedHashMap<>();
        cursor.encode().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> encoded.put(
                        HUNT_CURSOR_PREFIX + entry.getKey(), entry.getValue()));
        return Map.copyOf(encoded);
    }

    /**
     * A missing namespace is legacy only when no hunt watermark exists. Any modern watermark
     * without its cursor, or any present but partial, unknown, or malformed namespace, fails closed
     * so a restart cannot erase factual search history.
     */
    static Optional<HuntSearchCursor> decodeHuntSearchCursorNamespace(
            Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return Optional.of(HuntSearchCursor.initial());
        }
        java.util.LinkedHashMap<String, String> encoded = new java.util.LinkedHashMap<>();
        boolean present = false;
        for (Map.Entry<String, String> entry : checkpoint.entrySet()) {
            String key = entry.getKey();
            if ("hunt".equals(key) || "hunt.".equals(key)) {
                return Optional.empty();
            }
            if (key != null && key.startsWith(HUNT_CURSOR_PREFIX)) {
                present = true;
                String nested = key.substring(HUNT_CURSOR_PREFIX.length());
                if (nested.isBlank() || entry.getValue() == null
                        || encoded.put(nested, entry.getValue()) != null) {
                    return Optional.empty();
                }
            }
        }
        return present
                ? HuntSearchCursor.decode(encoded)
                : hasAnyHuntReplanWatermark(checkpoint)
                ? Optional.empty()
                : Optional.of(HuntSearchCursor.initial());
    }

    static Map<String, String> encodeSkippedTargetReceipts(
            List<SkippedTargetReceipt> receipts) {
        List<SkippedTargetReceipt> values =
                receipts == null ? List.of() : List.copyOf(receipts);
        if (values.size() > MAX_SKIPPED_TARGET_RECEIPTS) {
            throw new IllegalArgumentException("too many skipped target receipts");
        }
        java.util.LinkedHashMap<String, String> encoded = new java.util.LinkedHashMap<>();
        encoded.put(SKIPPED_TARGET_PREFIX + "schema", "1");
        encoded.put(SKIPPED_TARGET_PREFIX + "count", String.valueOf(values.size()));
        for (int index = 0; index < values.size(); index++) {
            String prefix = SKIPPED_TARGET_PREFIX + String.format("%02d.", index);
            encodeSkippedTargetReceipt(values.get(index)).forEach(
                    (key, value) -> encoded.put(prefix + key, value));
        }
        return Map.copyOf(encoded);
    }

    /**
     * Missing is the sole legacy representation. Once present, the bounded collection and every
     * target field are exact-key and canonical so a damaged receipt cannot suppress another step.
     */
    static Optional<List<SkippedTargetReceipt>> decodeSkippedTargetReceipts(
            Map<String, String> checkpoint) {
        Map<String, String> source = checkpoint == null ? Map.of() : checkpoint;
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        boolean present = false;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey();
            if ("skipped_target".equals(key) || SKIPPED_TARGET_PREFIX.equals(key)) {
                return Optional.empty();
            }
            if (key != null && key.startsWith(SKIPPED_TARGET_PREFIX)) {
                present = true;
                String nested = key.substring(SKIPPED_TARGET_PREFIX.length());
                if (nested.isBlank() || entry.getValue() == null
                        || values.put(nested, entry.getValue()) != null) {
                    return Optional.empty();
                }
            }
        }
        if (!present) {
            return Optional.of(List.of());
        }
        try {
            if (!"1".equals(values.get("schema"))) {
                return Optional.empty();
            }
            OptionalInt decodedCount = canonicalNonNegativeInt(values.get("count"));
            if (decodedCount.isEmpty()
                    || decodedCount.getAsInt() > MAX_SKIPPED_TARGET_RECEIPTS) {
                return Optional.empty();
            }
            int count = decodedCount.getAsInt();
            Set<String> expected = new HashSet<>();
            expected.add("schema");
            expected.add("count");
            for (int index = 0; index < count; index++) {
                String prefix = String.format("%02d.", index);
                for (String key : SKIPPED_TARGET_ENTRY_KEYS) {
                    expected.add(prefix + key);
                }
            }
            if (!values.keySet().equals(expected)) {
                return Optional.empty();
            }
            List<SkippedTargetReceipt> decoded = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String prefix = String.format("%02d.", index);
                java.util.LinkedHashMap<String, String> entry =
                        new java.util.LinkedHashMap<>();
                for (String key : SKIPPED_TARGET_ENTRY_KEYS) {
                    entry.put(key, values.get(prefix + key));
                }
                decoded.add(decodeSkippedTargetReceipt(entry).orElseThrow());
            }
            return Optional.of(List.copyOf(decoded));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * Applies durable skip receipts only to ordinary fresh Planner output. Each receipt removes the
     * earliest still-present same target at most once; count is intentionally ignored by
     * GoalStep.sameTarget, while every other identity field remains exact.
     */
    static List<GoalStep> applySkippedTargetReceipts(
            List<GoalStep> freshSteps, List<SkippedTargetReceipt> receipts) {
        List<GoalStep> remaining =
                new ArrayList<>(freshSteps == null ? List.of() : freshSteps);
        for (SkippedTargetReceipt receipt
                : receipts == null ? List.<SkippedTargetReceipt>of() : receipts) {
            for (int index = 0; index < remaining.size(); index++) {
                if (receipt.step().sameTarget(remaining.get(index))) {
                    remaining.remove(index);
                    break;
                }
            }
        }
        return List.copyOf(remaining);
    }

    static boolean skippedTargetReceiptsAuthorized(
            Goal goal, List<SkippedTargetReceipt> receipts) {
        return goal != null && receipts != null
                && receipts.stream().allMatch(receipt ->
                receipt != null && shouldSkipFailedStep(
                        goal, receipt.step(), receipt.reason()));
    }

    private static Map<String, String> encodeSkippedTargetReceipt(
            SkippedTargetReceipt receipt) {
        GoalStep step = java.util.Objects.requireNonNull(receipt, "receipt").step();
        String tag = step.tag();
        return Map.ofEntries(
                Map.entry("kind", step.kind().name()),
                Map.entry("item", encodeRegistryItem(step.item())),
                Map.entry("count", String.valueOf(step.count())),
                Map.entry("block", encodeRegistryBlock(step.block())),
                Map.entry("ores", encodeRegistryBlocks(step.ores())),
                Map.entry("input", encodeRegistryItem(step.input())),
                Map.entry("output", encodeRegistryItem(step.output())),
                Map.entry("pos", step.pos() == null ? "" : encodePos(step.pos())),
                Map.entry("tag_present", String.valueOf(tag != null)),
                Map.entry("tag", tag == null ? "" : encodeCanonicalText(tag)),
                Map.entry("best_effort", String.valueOf(step.bestEffort())),
                Map.entry("reason", encodeCanonicalText(receipt.reason())));
    }

    private static Optional<SkippedTargetReceipt> decodeSkippedTargetReceipt(
            Map<String, String> values) {
        if (values == null || !values.keySet().equals(SKIPPED_TARGET_ENTRY_KEYS)) {
            return Optional.empty();
        }
        try {
            GoalStep.Kind kind = GoalStep.Kind.valueOf(values.get("kind"));
            OptionalInt count = canonicalNonNegativeInt(values.get("count"));
            if (count.isEmpty()) {
                return Optional.empty();
            }
            Item item = decodeRegistryItem(values.get("item"));
            Block block = decodeRegistryBlock(values.get("block"));
            Set<Block> ores = decodeRegistryBlocks(values.get("ores"));
            Item input = decodeRegistryItem(values.get("input"));
            Item output = decodeRegistryItem(values.get("output"));
            BlockPos pos = values.get("pos").isEmpty()
                    ? null : decodePos(values.get("pos")).orElseThrow();
            if (pos != null && !encodePos(pos).equals(values.get("pos"))) {
                return Optional.empty();
            }
            boolean tagPresent = decodeCanonicalBoolean(
                    values.get("tag_present")).orElseThrow();
            String decodedTag = decodeCanonicalText(values.get("tag")).orElseThrow();
            if (!tagPresent && !decodedTag.isEmpty()) {
                return Optional.empty();
            }
            String tag = tagPresent ? decodedTag : null;
            boolean bestEffort = decodeCanonicalBoolean(
                    values.get("best_effort")).orElseThrow();
            String reason = decodeCanonicalText(values.get("reason")).orElseThrow();
            GoalStep step = new GoalStep(
                    kind, item, count.getAsInt(), block, ores,
                    input, output, pos, tag, bestEffort);
            if (step.count() != count.getAsInt()) {
                return Optional.empty();
            }
            SkippedTargetReceipt receipt = new SkippedTargetReceipt(step, reason);
            return encodeSkippedTargetReceipt(receipt).equals(values)
                    ? Optional.of(receipt) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static String encodeRegistryItem(Item item) {
        return item == null ? "" : Registries.ITEM.getId(item).toString();
    }

    private static Item decodeRegistryItem(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("missing item id");
        }
        if (encoded.isEmpty()) {
            return null;
        }
        Identifier id = Identifier.tryParse(encoded);
        Item item = id == null ? null : Registries.ITEM.getOptionalValue(id).orElse(null);
        if (item == null || !id.toString().equals(encoded)
                || !Registries.ITEM.getId(item).toString().equals(encoded)) {
            throw new IllegalArgumentException("invalid item id");
        }
        return item;
    }

    private static String encodeRegistryBlock(Block block) {
        return block == null ? "" : Registries.BLOCK.getId(block).toString();
    }

    private static Block decodeRegistryBlock(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("missing block id");
        }
        if (encoded.isEmpty()) {
            return null;
        }
        Identifier id = Identifier.tryParse(encoded);
        Block block = id == null ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
        if (block == null || !id.toString().equals(encoded)
                || !Registries.BLOCK.getId(block).toString().equals(encoded)) {
            throw new IllegalArgumentException("invalid block id");
        }
        return block;
    }

    private static String encodeRegistryBlocks(Set<Block> blocks) {
        return (blocks == null ? Set.<Block>of() : blocks).stream()
                .map(GoalExecutor::encodeRegistryBlock)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static Set<Block> decodeRegistryBlocks(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("missing block set");
        }
        if (encoded.isEmpty()) {
            return Set.of();
        }
        java.util.LinkedHashSet<Block> blocks = new java.util.LinkedHashSet<>();
        for (String value : encoded.split(",", -1)) {
            Block block = decodeRegistryBlock(value);
            if (block == null || !blocks.add(block)) {
                throw new IllegalArgumentException("invalid block set");
            }
        }
        Set<Block> decoded = Set.copyOf(blocks);
        if (!encodeRegistryBlocks(decoded).equals(encoded)) {
            throw new IllegalArgumentException("non-canonical block set");
        }
        return decoded;
    }

    private static String encodeCanonicalText(String value) {
        String text = value == null ? "" : value;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SKIPPED_TARGET_TEXT_BYTES) {
            throw new IllegalArgumentException("skipped target text too large");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Optional<String> decodeCanonicalText(String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_SKIPPED_TARGET_TEXT_BYTES) {
                return Optional.empty();
            }
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            return encodeCanonicalText(decoded).equals(encoded)
                    ? Optional.of(decoded) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> decodeCanonicalBoolean(String value) {
        if ("true".equals(value)) {
            return Optional.of(true);
        }
        if ("false".equals(value)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    private static RestoreSeed restoreSeed(AIPlayerEntity bot, Goal goal, MissionRecord record) {
        Map<String, String> checkpoint = record.checkpoint() == null ? Map.of() : record.checkpoint();
        Optional<String> validationFailure =
                restoreCheckpointValidationFailure(checkpoint);
        OptionalInt completedSteps = decodePersistedMissionCounter(checkpoint, "revision");
        OptionalInt lifetimeReplans =
                decodePersistedMissionCounter(checkpoint, "lifetime_replans");
        OptionalInt replanCount =
                decodePersistedMissionCounter(checkpoint, "replan_count");
        Optional<ReplanSnapshot> replanSnapshot = decodeReplanSnapshot(checkpoint);
        Optional<HuntSearchCursor> huntSearchCursor =
                decodeHuntSearchCursorNamespace(checkpoint);
        Optional<List<SkippedTargetReceipt>> skippedTargetReceipts =
                decodeSkippedTargetReceipts(checkpoint);
        Optional<PostconditionRepairCheckpoint> postconditionRepair =
                decodePostconditionRepairCheckpoint(checkpoint);
        GoalSnapshotCollector.Context fallback = initialContext(bot, goal);
        BlockPos origin = decodePos(checkpoint.get("origin")).orElse(fallback.origin());
        Set<BlockPos> containers = new HashSet<>();
        String encodedContainers = checkpoint.get("containers");
        if (encodedContainers != null && !encodedContainers.isBlank()) {
            for (String encoded : encodedContainers.split(";")) {
                decodePos(encoded).ifPresent(containers::add);
            }
        }
        BlockPos buildAnchor = decodePos(checkpoint.get("build_anchor")).orElse(null);
        BlueprintSchema blueprint = null;
        if (goal instanceof Goal.Build build && buildAnchor != null) {
            try {
                blueprint = BlueprintLoader.load(build.blueprint());
            } catch (IOException exception) {
                BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, bot,
                        "mission_checkpoint_blueprint_missing", "blueprint", build.blueprint());
            }
        }
        GoalSnapshotCollector.Context context = new GoalSnapshotCollector.Context(
                origin, containers, blueprint, buildAnchor,
                nonNegativeInt(checkpoint.get("build_placed")),
                nonNegativeInt(checkpoint.get("build_skipped")));
        UUID missionId;
        try {
            missionId = UUID.fromString(record.missionId());
        } catch (RuntimeException exception) {
            missionId = UUID.randomUUID();
        }
        int restoredStartedTick = checkpoint.containsKey("started_tick")
                ? nonNegativeInt(checkpoint.get("started_tick"))
                : bot.getServer().getTicks();
        GoalStep.Kind taskCheckpointKind = decodeStepKind(checkpoint.get("task_kind")).orElse(null);
        Map<String, String> taskCheckpoint = new java.util.LinkedHashMap<>();
        Map<String, String> miningCheckpoint = new java.util.LinkedHashMap<>();
        Map<String, String> auxiliaryMiningCheckpoint = new java.util.LinkedHashMap<>();
        Map<String, String> obsidianCheckpoint = new java.util.LinkedHashMap<>();
        Map<String, String> settledServiceTombstone = new java.util.LinkedHashMap<>();
        checkpoint.forEach((key, value) -> {
            if (key.startsWith("task.") && key.length() > "task.".length()) {
                taskCheckpoint.put(key.substring("task.".length()), value);
            } else if (key.startsWith("mining.") && key.length() > "mining.".length()) {
                miningCheckpoint.put(key.substring("mining.".length()), value);
            } else if (key.startsWith("aux_mining.")
                    && key.length() > "aux_mining.".length()) {
                auxiliaryMiningCheckpoint.put(
                        key.substring("aux_mining.".length()), value);
            } else if (key.startsWith("obsidian.") && key.length() > "obsidian.".length()) {
                obsidianCheckpoint.put(key.substring("obsidian.".length()), value);
            } else if ("settled_service".equals(key)) {
                // Preserve the exact illegal root so the strict collection decoder rejects it;
                // silently ignoring this key would turn a corrupt guard namespace into legacy
                // no-guard state.
                settledServiceTombstone.put("__root__", value);
            } else if (key.startsWith(SETTLED_SERVICE_PREFIX)) {
                settledServiceTombstone.put(
                        key.substring(SETTLED_SERVICE_PREFIX.length()), value);
            }
        });
        // task_kind is routing metadata, not physical authority. A strict MiningService codec can
        // safely recover its effective kind even when an older/corrupt writer omitted or mislabeled
        // the top-level discriminator; restoreRuntime quarantines identity-bearing invalid pockets
        // before this point, so this inference can never turn malformed geometry into fresh work.
        Optional<MiningServiceTask.RestoreMetadata> inferredService =
                MiningServiceTask.inspectCheckpoint(taskCheckpoint);
        if (inferredService.isPresent()
                && (hasActiveServicePocket(taskCheckpoint)
                || !inferredService.orElseThrow().terminalFailure().isBlank())) {
            taskCheckpointKind = GoalStep.Kind.MINING_SERVICE;
        }
        return new RestoreSeed(
                missionId,
                context,
                completedSteps.orElse(0),
                lifetimeReplans.orElse(0),
                decodePersistedRareResourceEpoch(checkpoint).orElse(-1),
                decodePersistedRareEpochMarginUsed(checkpoint).orElse(-1),
                replanCount.orElse(0),
                replanSnapshot,
                huntSearchCursor.orElse(null),
                skippedTargetReceipts.orElse(null),
                validationFailure.orElse(""),
                postconditionRepair.orElse(PostconditionRepairCheckpoint.legacy()),
                restoredStartedTick,
                taskCheckpointKind,
                Map.copyOf(taskCheckpoint),
                Map.copyOf(miningCheckpoint),
                Map.copyOf(auxiliaryMiningCheckpoint),
                checkpoint.getOrDefault("capacity_parent", ""),
                decodePersistedCapacityParentDelivered(checkpoint).orElse(-2),
                checkpoint.getOrDefault(CAPACITY_PARENT_FACE_KEY, ""),
                decodePersistedCapacityParentServicesUsed(checkpoint).orElse(-1),
                checkpoint.getOrDefault(AUXILIARY_MINING_CONTINUATION_KEY, ""),
                Map.copyOf(obsidianCheckpoint),
                Map.copyOf(settledServiceTombstone));
    }

    /**
     * Validates the mission-level restart envelope before any decoded fallback can erase the
     * distinction between an absent legacy field and a malformed persisted value.
     */
    static Optional<String> restoreCheckpointValidationFailure(
            Map<String, String> checkpoint) {
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        Map<String, String> task = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && key.startsWith("task.")
                    && key.length() > "task.".length()) {
                task.put(key.substring("task.".length()), value);
            }
        });
        boolean huntKind = "HUNT".equals(values.get("task_kind"));
        boolean huntIdentity = "hunt_pickup".equals(task.get("cursor_kind"));
        if ((huntKind || huntIdentity)
                && (!huntKind || !huntIdentity
                || HuntPickupCheckpoint.inspect(task).isEmpty())) {
            return Optional.of("mission_restore_invalid_hunt_pickup_checkpoint");
        }
        if (decodeHuntSearchCursorNamespace(values).isEmpty()) {
            return Optional.of("mission_restore_invalid_hunt_search_cursor");
        }
        if (decodeSkippedTargetReceipts(values).isEmpty()) {
            return Optional.of("mission_restore_invalid_skipped_target_receipts");
        }
        if (hasAnyReplanSnapshotField(values)
                && decodeReplanSnapshot(values).isEmpty()) {
            return Optional.of("mission_restore_invalid_replan_snapshot");
        }
        if (decodePersistedMissionCounter(values, "revision").isEmpty()) {
            return Optional.of("mission_restore_invalid_completed_step_count");
        }
        if (decodePersistedMissionCounter(values, "lifetime_replans").isEmpty()) {
            return Optional.of("mission_restore_invalid_lifetime_replan_count");
        }
        if (decodePersistedMissionCounter(values, "replan_count").isEmpty()) {
            return Optional.of("mission_restore_invalid_replan_count");
        }
        if (decodePostconditionRepairCheckpoint(values).isEmpty()) {
            return Optional.of("mission_restore_invalid_postcondition_repair_checkpoint");
        }
        return Optional.empty();
    }

    /**
     * Missing is a legacy zero. Once persisted, mission budget counters must remain non-negative
     * integers; malformed values must not refresh a retry budget on restart.
     */
    static OptionalInt decodePersistedMissionCounter(
            Map<String, String> checkpoint, String key) {
        if (checkpoint == null || !checkpoint.containsKey(key)) {
            return OptionalInt.of(0);
        }
        return canonicalNonNegativeInt(checkpoint.get(key));
    }

    static Map<String, String> encodePostconditionRepairCheckpoint(
            int replans, int lastMatched, String fingerprint) {
        if (!validPostconditionRepairState(replans, lastMatched, fingerprint)) {
            throw new IllegalArgumentException("invalid postcondition repair state");
        }
        String encodedFingerprint = fingerprint.isEmpty() ? ""
                : Base64.getUrlEncoder().withoutPadding().encodeToString(
                fingerprint.getBytes(StandardCharsets.UTF_8));
        return Map.of(
                POSTCONDITION_REPLANS_KEY, String.valueOf(replans),
                POSTCONDITION_LAST_MATCHED_KEY, String.valueOf(lastMatched),
                POSTCONDITION_FINGERPRINT_KEY, encodedFingerprint);
    }

    /**
     * Missing is the legacy representation. Once any namespaced field exists, the complete
     * canonical triple is required so a restart cannot reset the repair count or forget the last
     * rejected plan fingerprint.
     */
    static Optional<PostconditionRepairCheckpoint> decodePostconditionRepairCheckpoint(
            Map<String, String> checkpoint) {
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        Set<String> presentKeys = values.keySet().stream()
                .filter(key -> key != null && key.startsWith(POSTCONDITION_PREFIX))
                .collect(java.util.stream.Collectors.toSet());
        if (presentKeys.isEmpty()) {
            return Optional.of(PostconditionRepairCheckpoint.legacy());
        }
        if (!presentKeys.equals(POSTCONDITION_REPAIR_KEYS)) {
            return Optional.empty();
        }

        OptionalInt replans = canonicalNonNegativeInt(
                values.get(POSTCONDITION_REPLANS_KEY));
        OptionalInt lastMatched = canonicalNonNegativeInt(
                values.get(POSTCONDITION_LAST_MATCHED_KEY));
        if (replans.isEmpty() || lastMatched.isEmpty()) {
            return Optional.empty();
        }
        String fingerprint = decodeCanonicalPostconditionFingerprint(
                values.get(POSTCONDITION_FINGERPRINT_KEY)).orElse(null);
        if (fingerprint == null || !validPostconditionRepairState(
                replans.getAsInt(), lastMatched.getAsInt(), fingerprint)) {
            return Optional.empty();
        }
        return Optional.of(new PostconditionRepairCheckpoint(
                true, replans.getAsInt(), lastMatched.getAsInt(), fingerprint));
    }

    private static OptionalInt canonicalNonNegativeInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 && String.valueOf(parsed).equals(value)
                    ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    private static Optional<String> decodeCanonicalPostconditionFingerprint(
            String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        if (encoded.isEmpty()) {
            return Optional.of("");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (decoded.length > MAX_POSTCONDITION_FINGERPRINT_BYTES) {
                return Optional.empty();
            }
            String fingerprint = new String(decoded, StandardCharsets.UTF_8);
            String canonical = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(fingerprint.getBytes(StandardCharsets.UTF_8));
            return canonical.equals(encoded)
                    ? Optional.of(fingerprint) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static boolean validPostconditionRepairState(
            int replans, int lastMatched, String fingerprint) {
        if (replans < 0 || replans > MAX_POSTCONDITION_REPLANS
                || lastMatched < 0 || fingerprint == null
                || fingerprint.getBytes(StandardCharsets.UTF_8).length
                > MAX_POSTCONDITION_FINGERPRINT_BYTES) {
            return false;
        }
        return replans == 0 ? fingerprint.isEmpty() : !fingerprint.isBlank();
    }

    /** Missing is the only legacy value; every persisted value must be a non-negative watermark. */
    static OptionalInt decodePersistedCapacityParentDelivered(
            Map<String, String> checkpoint) {
        if (checkpoint == null || !checkpoint.containsKey(CAPACITY_PARENT_DELIVERED_KEY)) {
            return OptionalInt.of(-1);
        }
        try {
            int parsed = Integer.parseInt(checkpoint.get(CAPACITY_PARENT_DELIVERED_KEY));
            return parsed >= 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    /** Missing is the legacy no-counter value; every persisted value must be positive. */
    static OptionalInt decodePersistedCapacityParentServicesUsed(
            Map<String, String> checkpoint) {
        if (checkpoint == null
                || !checkpoint.containsKey(CAPACITY_PARENT_SERVICES_USED_KEY)) {
            return OptionalInt.of(0);
        }
        try {
            int parsed = Integer.parseInt(
                    checkpoint.get(CAPACITY_PARENT_SERVICES_USED_KEY));
            return parsed > 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    /**
     * Missing means a legacy epoch-zero snapshot; malformed or negative values fail closed. The
     * mission-derived upper bound (per-batch retry plus the bounded margin pool) is enforced at
     * the restore site because only the goal knows the durable mission target.
     */
    static OptionalInt decodePersistedRareResourceEpoch(Map<String, String> checkpoint) {
        if (checkpoint == null || !checkpoint.containsKey("rare_resource_retries_used")) {
            return OptionalInt.of(0);
        }
        try {
            int parsed = Integer.parseInt(checkpoint.get("rare_resource_retries_used"));
            return parsed >= 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    /** Missing is a legacy zero-margin snapshot; malformed or negative values fail closed. */
    static OptionalInt decodePersistedRareEpochMarginUsed(Map<String, String> checkpoint) {
        if (checkpoint == null || !checkpoint.containsKey("rare_epoch_margin_used")) {
            return OptionalInt.of(0);
        }
        return canonicalNonNegativeInt(checkpoint.get("rare_epoch_margin_used"));
    }

    /** Bounded mission-level epoch margin pool derived from the durable original rare target. */
    static int rareMissionEpochMarginPool(Goal goal) {
        int missionTarget = originalLongRareOreTargetCount(goal);
        return missionTarget >= MiningBudget.EXPEDITION_THRESHOLD
                ? MiningBudget.rareMissionEpochMargin(
                MiningBudget.rareMissionBatchCount(missionTarget))
                : 0;
    }

    /**
     * A batch epoch beyond the per-batch retry must have been paid from the mission margin pool;
     * accepting a smaller persisted ledger would let a restart manufacture extra bounded epochs.
     */
    static boolean validRestoredRareEpochMargin(int persistedEpoch,
                                               int marginUsed,
                                               int marginPool) {
        return marginUsed >= 0 && marginUsed <= marginPool
                && persistedEpoch - MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH
                <= marginUsed;
    }

    private static Optional<ReplanSnapshot> decodeReplanSnapshot(Map<String, String> checkpoint) {
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        Set<String> snapshotKeys = values.keySet().stream()
                .filter(key -> key != null && key.startsWith("snap_"))
                .collect(java.util.stream.Collectors.toSet());
        boolean legacy = snapshotKeys.equals(LEGACY_REPLAN_SNAPSHOT_KEYS);
        boolean modern = snapshotKeys.equals(MODERN_REPLAN_SNAPSHOT_KEYS);
        if (!legacy && !modern) {
            return Optional.empty();
        }
        OptionalInt steps = canonicalNonNegativeInt(values.get("snap_steps"));
        OptionalInt target = canonicalNonNegativeInt(values.get("snap_target"));
        Optional<Integer> x = canonicalSignedInt(values.get("snap_x"));
        Optional<Integer> y = canonicalSignedInt(values.get("snap_y"));
        Optional<Integer> z = canonicalSignedInt(values.get("snap_z"));
        if (steps.isEmpty() || target.isEmpty()
                || x.isEmpty() || y.isEmpty() || z.isEmpty()) {
            return Optional.empty();
        }
        if (legacy) {
            return Optional.of(new ReplanSnapshot(
                    steps.getAsInt(), target.getAsInt(),
                    x.orElseThrow(), y.orElseThrow(), z.orElseThrow(),
                    "", -1, -1));
        }
        OptionalInt huntRaw = canonicalNonNegativeInt(
                values.get("snap_hunt_raw_meat"));
        OptionalInt huntSectors = canonicalNonNegativeInt(
                values.get("snap_hunt_visited_sectors"));
        String dimension = values.get("snap_dimension");
        Identifier dimensionId = dimension == null
                ? null : Identifier.tryParse(dimension);
        if (huntRaw.isEmpty() || huntSectors.isEmpty()
                || dimensionId == null || !dimensionId.toString().equals(dimension)) {
            return Optional.empty();
        }
        return Optional.of(new ReplanSnapshot(
                steps.getAsInt(), target.getAsInt(),
                x.orElseThrow(), y.orElseThrow(), z.orElseThrow(),
                dimension, huntRaw.getAsInt(), huntSectors.getAsInt()));
    }

    static boolean hasAnyReplanSnapshotField(Map<String, String> checkpoint) {
        return checkpoint != null && checkpoint.keySet().stream()
                .anyMatch(key -> key != null && key.startsWith("snap_"));
    }

    private static boolean hasAnyHuntReplanWatermark(
            Map<String, String> checkpoint) {
        return checkpoint != null
                && (checkpoint.containsKey("snap_hunt_raw_meat")
                || checkpoint.containsKey("snap_hunt_visited_sectors"));
    }

    private static Optional<Integer> canonicalSignedInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return String.valueOf(parsed).equals(value)
                    ? Optional.of(parsed) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<GoalStep.Kind> decodeStepKind(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(GoalStep.Kind.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static int nonNegativeInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static String encodePos(net.minecraft.util.math.BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Optional<net.minecraft.util.math.BlockPos> decodePos(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = value.split(",");
            if (parts.length != 3) {
                return Optional.empty();
            }
            return Optional.of(new net.minecraft.util.math.BlockPos(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public boolean startNextQueuedIfIdle(AIPlayerEntity bot) {
        if (hasActivePlan(bot)) {
            return false;
        }
        return advanceQueue(bot);
    }

    /** 诊断埋点:当前激活的顶层目标(无则 "none")。日志用,保留英文便于排查。 */
    public String describeActiveGoal(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        return plan == null ? "none" : String.valueOf(plan.goal);
    }

    /** 面板任务链条:目标标题(中文)。物品 id 保留 minecraft:xxx,客户端再本地化成中文名。 */
    public String activeGoalTitle(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        return plan == null ? "无目标" : goalLabel(plan.goal);
    }

    private static String goalLabel(Goal goal) {
        return switch (goal) {
            case Goal.HaveItem g -> "获取 " + io.github.zoyluo.aibot.craft.ItemNames.cn(g.item()) + " ×" + g.count();
            case Goal.MineOre g -> "采矿 ×" + g.count();
            case Goal.HarvestCrop g -> "种收 " + io.github.zoyluo.aibot.craft.ItemNames.cn(g.produce()) + " ×" + g.count();
            case Goal.Food g -> "备食物(熟食) ×" + g.cookedCount();
            case Goal.Armor g -> "武装(整套护甲+剑)";
            case Goal.Workstation g -> "搭建工作站";
            case Goal.Stockpile g -> "囤货 " + io.github.zoyluo.aibot.craft.ItemNames.cn(g.item()) + " ×" + g.count();
            case Goal.HavePickaxeTier g -> "升级镐 (tier " + g.tier() + ")";
            case Goal.Build g -> "盖房子(" + g.blueprint() + ")";
        };
    }

    /** 诊断埋点:当前正在执行的步骤 + 进度 [第几步/总步数](无激活步则 "")。 */
    public String describeActiveStep(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        if (plan == null || plan.current == null) {
            return "";
        }
        int idx = plan.totalSteps - plan.steps.size(); // current 已从 steps 取出,正在做第 idx 步
        return plan.current.describe() + " [" + idx + "/" + plan.totalSteps + "]";
    }

    /** 面板任务链条:完整步骤描述列表(无激活计划则空)。 */
    public java.util.List<String> activeGoalSteps(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        return plan == null ? java.util.List.of() : plan.stepLabels;
    }

    /** 面板任务链条:当前所处步骤的 0 基下标。 */
    public int activeGoalCurrentIndex(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        if (plan == null || plan.current == null) {
            return 0;
        }
        return Math.max(0, plan.totalSteps - plan.steps.size() - 1);
    }

    /** 面板任务链条:总步数。 */
    public int activeGoalTotalSteps(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUuid());
        return plan == null ? 0 : plan.totalSteps;
    }

    // P0 队列衔接:当前目标了结(完成/失败)后,自动开始队列里的下一个;规划失败的逐个跳过并说明。
    private boolean advanceQueue(AIPlayerEntity bot) {
        java.util.Deque<Goal> queued = goalQueue.get(bot.getUuid());
        if (queued == null) {
            return false;
        }
        Goal next;
        while ((next = queued.pollFirst()) != null) {
            report(bot, "接着办下一件:" + goalLabel(next));
            if (submit(bot, next)) {
                if (hasActivePlan(bot)) {
                    return true;
                }
                // 目标已经满足、没有创建 active plan：继续检查队列下一项。
            }
            // submit 失败(规划不成/被拦)已在内部 report 过原因,继续试队列里再下一个
        }
        goalQueue.remove(bot.getUuid(), queued);
        return false;
    }

    /**
     * Captures a committed mission transition before successor dispatch can synchronously start
     * work or throw. A second capture records the assigned task's initial checkpoint when dispatch
     * succeeds. BotPersistence keeps both captures asynchronous with respect to disk I/O.
     */
    private void captureTransitionAndAssignNext(AIPlayerEntity bot, ActivePlan plan) {
        captureBeforeAndAfterDispatch(
                () -> markDirty(bot),
                () -> assignNext(bot, plan));
    }

    /** Package-private ordering seam for transition unit tests. */
    static void captureBeforeAndAfterDispatch(Runnable capture, Runnable dispatch) {
        java.util.Objects.requireNonNull(capture, "capture");
        java.util.Objects.requireNonNull(dispatch, "dispatch");
        capture.run();
        dispatch.run();
        capture.run();
    }

    private void assignNext(AIPlayerEntity bot, ActivePlan plan) {
        GoalStep step = plan.steps.pollFirst();
        if (step == null) {
            GoalEvaluation evaluation = evaluate(bot, plan);
            if (evaluation.state() == GoalEvaluation.State.SATISFIED) {
                finishActive(bot, plan, evaluation, "postcondition_satisfied", false, true);
                return;
            }
            if (!(plan.goal instanceof Goal.Build) && bot.isAlive()
                    && withinPostconditionRepairBudget(plan.postconditionReplans)) {
                GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                        bot, plan.goal, snapshotContext(plan), plan.missionId.toString());
                List<GoalStep> repairSteps = applySkippedTargetReceipts(
                        fresh.success() ? fresh.steps() : List.of(),
                        plan.skippedTargetReceipts);
                String fingerprint =
                        repairSteps.stream().map(GoalStep::describe).toList().toString();
                boolean progress = evaluation.matched() > plan.lastEvaluationMatched;
                if (fresh.success() && !repairSteps.isEmpty()
                        && shouldAcceptPostconditionRepair(
                        evaluation.matched(), plan.lastEvaluationMatched,
                        fingerprint, plan.lastRepairFingerprint)) {
                    plan.postconditionReplans++;
                    plan.lastEvaluationMatched = Math.max(plan.lastEvaluationMatched, evaluation.matched());
                    plan.lastRepairFingerprint = fingerprint;
                    BotLog.task(bot, "goal_postcondition_repair", "goal", plan.goal,
                            "matched", evaluation.matched(), "required", evaluation.required(),
                            "steps", fingerprint, "repair", plan.postconditionReplans);
                    plan.steps.clear();
                    plan.steps.addAll(repairSteps);
                    plan.totalSteps = repairSteps.size();
                    plan.current = null;
                    plan.currentTask = null;
                    captureTransitionAndAssignNext(bot, plan);
                    return;
                }
                BotLog.task(bot, "goal_postcondition_repair_rejected",
                        "goal", plan.goal,
                        "success", fresh.success(),
                        "steps", fingerprint,
                        "unresolved", fresh.unresolved(),
                        "progress", progress,
                        "same", fingerprint.equals(plan.lastRepairFingerprint));
            }
            finishActive(bot, plan, evaluation, "postcondition_unsatisfied", false, true);
            return;
        }
        plan.currentCapacityParentRetry = false;
        Optional<Task> task = stepToTask(bot, step, plan);
        if (task.isEmpty()) {
            finishActive(bot, plan, evaluate(bot, plan), "unmapped_step:" + step.describe(), false, true);
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, bot, "goal_step_unmapped", "step", step.describe());
            return;
        }
        plan.current = step;
        plan.currentTask = task.get();
        int done = plan.totalSteps - plan.steps.size();
        BotLog.task(bot, "goal_step", "index", done, "total", plan.totalSteps, "step", step.describe());
        TaskOrigin origin = TaskOrigin.mission(plan.missionId, step.describe());
        if (pocketRestorePreflights.contains(bot.getUuid())) {
            TaskManager.INSTANCE.assignSilently(bot, task.get(), origin);
        } else {
            TaskManager.INSTANCE.assign(bot, task.get(), origin);
        }
    }

    static boolean withinPostconditionRepairBudget(int replans) {
        return replans >= 0 && replans < MAX_POSTCONDITION_REPLANS;
    }

    static boolean shouldAcceptPostconditionRepair(
            int currentMatched, int lastMatched,
            String candidateFingerprint, String lastFingerprint) {
        return currentMatched > lastMatched
                || !java.util.Objects.equals(candidateFingerprint, lastFingerprint);
    }

    // Phase A 进度信号:目标产物当前库存计数(HaveItem/Stockpile 用其物品,MineOre 用矿石掉落)。
    private static int goalTargetCount(AIPlayerEntity bot, Goal goal) {
        if (goal instanceof Goal.HaveItem hi) {
            return io.github.zoyluo.aibot.action.HarvestCore.countInventoryItems(bot, java.util.Set.of(hi.item()));
        }
        if (goal instanceof Goal.Stockpile sp) {
            return io.github.zoyluo.aibot.action.HarvestCore.countInventoryItems(bot, java.util.Set.of(sp.item()));
        }
        if (goal instanceof Goal.MineOre mo) {
            return io.github.zoyluo.aibot.action.HarvestCore.countInventoryItems(bot,
                    io.github.zoyluo.aibot.action.HarvestCore.expectedDropsFor(mo.ores()));
        }
        return 0;
    }

    private static int rawMeatCount(AIPlayerEntity bot) {
        return HuntTask.rawMeatCount(bot);
    }

    static boolean isUnsettledHuntPhysicalDebt(GoalStep.Kind kind, String reason) {
        if (kind != GoalStep.Kind.HUNT || reason == null) {
            return false;
        }
        return reason.startsWith("hunt_surface_return_timeout")
                || reason.startsWith("hunt_surface_return_unreachable")
                || reason.startsWith("hunt_surface_anchor_missing")
                || reason.startsWith("hunt_dimension_changed")
                || reason.startsWith("hunt_drop_unrecovered")
                || reason.startsWith("hunt_pickup_observation_timeout")
                || reason.startsWith("hunt_water_rescue_timeout")
                || reason.startsWith("hunt_pickup_checkpoint_capacity")
                || reason.startsWith("hunt_pickup_time_rollback")
                || reason.startsWith("hunt_pickup_dimension_mismatch")
                || reason.startsWith("hunt_pickup_invalid_checkpoint")
                || reason.startsWith("hunt_search_capacity_exhausted")
                || reason.startsWith("hunt_search_ordinal_exhausted");
    }

    /** Pure policy boundary for the generic best-effort failure branch. */
    static boolean shouldSkipFailedStep(Goal goal, GoalStep step, String reason) {
        if (step == null || isUnsettledHuntPhysicalDebt(step.kind(), reason)) {
            return false;
        }
        boolean cookFinalOfFood = goal instanceof Goal.Food
                && step.kind() == GoalStep.Kind.COOK_FOOD;
        boolean foodGoalBestEffort = goal instanceof Goal.Food;
        return !cookFinalOfFood && (step.bestEffort()
                || foodGoalBestEffort
                || step.kind() == GoalStep.Kind.STOCKPILE);
    }

    /**
     * Pure replan-watermark policy. Goal output and completed steps are universal progress.
     * HUNT then accepts only factual food/search gains; all other tasks retain the existing
     * controlled movement signal used by branch mining.
     */
    static boolean madeReplanProgress(
            GoalStep.Kind kind,
            int completedSteps, int snapshotSteps,
            int targetCount, int snapshotTargetCount,
            int huntRawMeat, int snapshotHuntRawMeat,
            int huntVisitedSectors, int snapshotHuntVisitedSectors,
            String dimension, String snapshotDimension,
            int x, int y, int z,
            int snapshotX, int snapshotY, int snapshotZ) {
        if (completedSteps > snapshotSteps || targetCount > snapshotTargetCount) {
            return true;
        }
        if (kind == GoalStep.Kind.HUNT) {
            return huntRawMeat > snapshotHuntRawMeat
                    || huntVisitedSectors > snapshotHuntVisitedSectors;
        }
        if (dimension == null || dimension.isBlank()
                || snapshotDimension == null || snapshotDimension.isBlank()
                || !dimension.equals(snapshotDimension)) {
            return false;
        }
        long dx = (long) x - snapshotX;
        long dz = (long) z - snapshotZ;
        return y < snapshotY || dx * dx + dz * dz >= 64L;
    }

    /**
     * A long rare-ore mission has one bounded retry epoch per eight-item batch. Three retries per
     * batch preserve the existing no-progress allowance while the base budget covers bootstrap and
     * short missions. The immutable top-level goal is used so partial inventory or a restart cannot
     * shrink or refresh the lifetime bound.
     */
    static int lifetimeReplanLimit(Goal goal) {
        return longRareOreLifetimeReplanLimit(originalLongRareOreTargetCount(goal));
    }

    static int longRareOreLifetimeReplanLimit(int targetCount) {
        if (targetCount < MiningBudget.EXPEDITION_THRESHOLD) {
            return BASE_LIFETIME_REPLAN_LIMIT;
        }
        int batchCount = MiningBudget.forQuota(targetCount, true, ToolTier.IRON).batchCount();
        return Math.max(BASE_LIFETIME_REPLAN_LIMIT, MAX_CONSECUTIVE_REPLANS * batchCount);
    }

    static boolean withinReplanBudget(Goal goal, int consecutiveReplans, int lifetimeReplans) {
        return withinReplanBudget(
                lifetimeReplanLimit(goal), consecutiveReplans, lifetimeReplans);
    }

    static boolean withinReplanBudget(
            int lifetimeLimit, int consecutiveReplans, int lifetimeReplans) {
        return consecutiveReplans < MAX_CONSECUTIVE_REPLANS
                && lifetimeReplans < lifetimeLimit;
    }

    private static int originalLongRareOreTargetCount(Goal goal) {
        int target = 0;
        if (goal instanceof Goal.MineOre mineOre && isRareOre(mineOre.ores())) {
            target = mineOre.count();
        } else if (goal instanceof Goal.HaveItem haveItem && isRareOreDrop(haveItem.item())) {
            target = haveItem.count();
        } else if (goal instanceof Goal.Stockpile stockpile && isRareOreDrop(stockpile.item())) {
            target = stockpile.count();
        }
        return target >= MiningBudget.EXPEDITION_THRESHOLD ? target : 0;
    }

    /**
     * Only the OreDig batches that directly deliver the long rare-ore goal own its durable mission
     * identity. Coal and iron bootstrap batches remain ordinary channels even when their parent is
     * a 64-diamond expedition; assigning 64 to those families makes their codec fail closed and
     * disables the one bounded local channel-tool resupply.
     */
    static int rareMissionTargetForMiningStep(Goal goal, Set<Block> ores) {
        int missionTarget = originalLongRareOreTargetCount(goal);
        return missionTarget > 0 && miningStepFeedsGoal(goal, ores) ? missionTarget : 0;
    }

    private static boolean isProtectedRareMiningCheckpoint(
            Goal goal, Map<String, String> checkpoint) {
        int missionTarget = originalLongRareOreTargetCount(goal);
        return missionTarget >= MiningBudget.EXPEDITION_THRESHOLD
                && OreDigTask.inspectCheckpoint(checkpoint, missionTarget)
                .filter(metadata -> metadata.rareMissionTarget() == missionTarget
                        && miningStepFeedsGoal(goal, metadata.ores()))
                .isPresent();
    }

    private static Optional<OreDigTask.RestoreMetadata> inspectOreDigCheckpointForGoal(
            Goal goal,
            Map<String, String> checkpoint) {
        int missionTarget = originalLongRareOreTargetCount(goal);
        if (missionTarget == 0) {
            return OreDigTask.inspectCheckpoint(checkpoint, 0);
        }
        Optional<OreDigTask.RestoreMetadata> rare =
                OreDigTask.inspectCheckpoint(checkpoint, missionTarget)
                        .filter(metadata -> miningStepFeedsGoal(goal, metadata.ores()));
        if (rare.isPresent()) {
            return rare;
        }
        return OreDigTask.inspectCheckpoint(checkpoint, 0)
                .filter(metadata -> !miningStepFeedsGoal(goal, metadata.ores()));
    }

    /**
     * Reconciles the compatibility persistence key with the durable owner of the current rare batch.
     * The key historically accumulated one retry for the whole mission; epoch zero therefore accepts
     * and normalizes a persisted one. A later epoch — the per-batch retry or a mission-margin
     * epoch — cannot do the inverse because that would lose a physically committed epoch debit and
     * manufacture another resource epoch after restart.
     */
    static OptionalInt normalizeRestoredRareResourceEpoch(
            int persistedEpoch,
            Optional<OreDigTask.RestoreMetadata> owner) {
        if (persistedEpoch < 0) {
            return OptionalInt.empty();
        }
        if (owner == null || owner.isEmpty() || !owner.orElseThrow().batchOpen()) {
            // Without an open durable batch only the legacy mission-global one may normalize down;
            // a margin-funded epoch always has its open batch, so anything larger fails closed.
            return persistedEpoch <= MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH
                    ? OptionalInt.of(0) : OptionalInt.empty();
        }
        int durableEpoch = owner.orElseThrow().resourceEpoch();
        if (durableEpoch == 0) {
            return persistedEpoch <= MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH
                    ? OptionalInt.of(0) : OptionalInt.empty();
        }
        int epochCapacity = MiningBudget.rareMissionResourceEpochCapacity(
                MiningBudget.rareMissionBatchCount(owner.orElseThrow().rareMissionTarget()));
        if (durableEpoch >= 1 && durableEpoch < epochCapacity
                && persistedEpoch == durableEpoch) {
            return OptionalInt.of(durableEpoch);
        }
        return OptionalInt.empty();
    }

    private static boolean hasFreshMiningSuccessor(List<GoalStep> steps, Set<Block> ores) {
        String fingerprint = OreDigTask.oreFingerprint(ores);
        return steps != null && steps.stream().anyMatch(step ->
                step.kind() == GoalStep.Kind.MINE_ORE
                        && fingerprint.equals(OreDigTask.oreFingerprint(step.ores())));
    }

    private static boolean hasFreshMiningSuccessor(
            List<GoalStep> steps,
            OreDigTask.RestoreMetadata metadata) {
        if (metadata.remainingCount() == 0) {
            // The task still needs one commit tick (and possibly pickup-debt settlement), but the
            // fresh inventory plan correctly has no remaining ore step to match.
            return true;
        }
        String fingerprint = OreDigTask.oreFingerprint(metadata.ores());
        return steps != null && steps.stream().anyMatch(step ->
                step.kind() == GoalStep.Kind.MINE_ORE
                        && metadata.acceptsStepTarget(step.count())
                        && fingerprint.equals(OreDigTask.oreFingerprint(step.ores())));
    }

    /**
     * A disposal pocket remains a physical transaction until MiningService resets its complete
     * identity after both mouth cells and promised capacity have been re-attested.  In particular,
     * committed+verified is not settlement while RETURN or SEAL still owns these fields.
     */
    static boolean hasActiveServicePocket(Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return false;
        }
        String phase = checkpoint.get("phase");
        if (phase != null && ACTIVE_SERVICE_POCKET_PHASES.contains(phase)) {
            return true;
        }
        return SERVICE_POCKET_IDENTITY_KEYS.stream().anyMatch(checkpoint::containsKey);
    }

    /** A lone guard has one causal typed fact; multiple guards need an exact geometry key. */
    private static String settledServiceFailureWithoutContinuation(
            List<SettledServiceTombstone> settled) {
        return settled != null && settled.size() == 1
                ? settled.getFirst().failureReason()
                : "mission_restore_settled_service_guard_without_continuation";
    }

    static boolean validCapacityParentRetry(
            Optional<OreDigTask.RestoreMetadata> parent) {
        return parent != null && parent.filter(metadata -> metadata.batchOpen()
                && metadata.rareMissionTarget() == 0
                && metadata.inventoryServiceUsed()).isPresent();
    }

    static boolean validCommittedCapacityParent(
            Optional<OreDigTask.RestoreMetadata> parent,
            GoalStep.Kind taskKind,
            Map<String, String> taskCheckpoint,
            Map<String, String> parentCheckpoint) {
        return taskKind == GoalStep.Kind.MINE_ORE
                && taskCheckpoint != null
                && parentCheckpoint != null
                && !parentCheckpoint.isEmpty()
                && taskCheckpoint.equals(parentCheckpoint)
                && parent != null
                && parent.filter(metadata -> !metadata.batchOpen()
                        && metadata.rareMissionTarget() == 0
                        && !metadata.inventoryServiceUsed()).isPresent()
                && !hasOreDigPhysicalLedger(parentCheckpoint);
    }

    static boolean capacityParentMatchesService(
            Map<String, String> serviceCheckpoint,
            Map<String, String> parentCheckpoint) {
        return ordinaryServiceParentMatches(serviceCheckpoint, parentCheckpoint, true);
    }

    static boolean failedClosedAuxiliaryServiceMatches(
            Map<String, String> serviceCheckpoint,
            Map<String, String> auxiliaryCheckpoint) {
        return !hasActiveServicePocket(serviceCheckpoint)
                && ordinaryServiceParentMatches(
                serviceCheckpoint, auxiliaryCheckpoint, false);
    }

    private static boolean ordinaryServiceParentMatches(
            Map<String, String> serviceCheckpoint,
            Map<String, String> parentCheckpoint,
            boolean capacityRetry) {
        Optional<MiningServiceTask.RestoreMetadata> service =
                MiningServiceTask.inspectCheckpoint(serviceCheckpoint);
        Optional<OreDigTask.RestoreMetadata> parent =
                OreDigTask.inspectCheckpoint(parentCheckpoint, 0);
        if (service.isEmpty() || parent.isEmpty()
                || service.orElseThrow().policy().profile()
                != MiningServiceTask.ServiceProfile.ORE_BATCH
                || service.orElseThrow().serviceTargetCount() != 0
                || service.orElseThrow().serviceBoundary() != 0
                || parent.orElseThrow().rareMissionTarget() != 0
                || capacityRetry != parent.orElseThrow().batchOpen()
                || capacityRetry && !parent.orElseThrow().inventoryServiceUsed()
                || hasOreDigPhysicalLedger(parentCheckpoint)
                || !OreDigTask.oreFingerprint(service.orElseThrow().ores()).equals(
                OreDigTask.oreFingerprint(parent.orElseThrow().ores()))
                || !parent.orElseThrow().cursor().face().equals(
                decodePos(serviceCheckpoint.get("work_face")).orElse(null))) {
            return false;
        }
        String[][] cursorFields = {
                {"origin", "cursor_origin"},
                {"face", "cursor_face"},
                {"direction", "cursor_direction"},
                {"leg", "cursor_leg"},
                {"steps_left", "cursor_steps_left"},
                {"leg_length", "cursor_leg_length"},
                {"batches", "cursor_batches"}
        };
        for (String[] fields : cursorFields) {
            if (!java.util.Objects.equals(
                    parentCheckpoint.get(fields[0]), serviceCheckpoint.get(fields[1]))) {
                return false;
            }
        }
        return true;
    }

    static boolean ordinaryServiceMiningNamespaceMatches(
            Goal goal,
            Set<Block> serviceOres,
            BlockPos serviceFace,
            Optional<OreDigTask.RestoreMetadata> mining) {
        if (mining == null || mining.isEmpty()) {
            return true;
        }
        OreDigTask.RestoreMetadata metadata = mining.orElseThrow();
        int rareTarget = originalLongRareOreTargetCount(goal);
        if (rareTarget > 0 && metadata.rareMissionTarget() == rareTarget
                && miningStepFeedsGoal(goal, metadata.ores())) {
            return true;
        }
        return metadata.rareMissionTarget() == 0
                && OreDigTask.oreFingerprint(serviceOres).equals(
                OreDigTask.oreFingerprint(metadata.ores()))
                && serviceFace != null && serviceFace.equals(metadata.cursor().face());
    }

    private static boolean hasOreDigPhysicalLedger(Map<String, String> checkpoint) {
        return checkpoint != null && (checkpoint.containsKey("pending_pickup_pos")
                || checkpoint.containsKey("active_break_pos"));
    }

    private static boolean isRareOre(Set<Block> ores) {
        Set<Block> expanded = OreScan.expandOreFamilies(ores == null ? Set.of() : ores);
        return expanded.contains(Blocks.DIAMOND_ORE)
                || expanded.contains(Blocks.DEEPSLATE_DIAMOND_ORE)
                || expanded.contains(Blocks.EMERALD_ORE)
                || expanded.contains(Blocks.DEEPSLATE_EMERALD_ORE);
    }

    private static boolean isRareOreDrop(Item item) {
        return item == Items.DIAMOND || item == Items.EMERALD;
    }

    private void handleStepFailure(MinecraftServer server, AIPlayerEntity bot, ActivePlan plan, String reason) {
        captureTaskEvidence(bot, plan);
        Optional<SettledServiceTombstone> replayGuard =
                matchingSettledServiceGuard(bot, plan, reason);
        if (replayGuard.isPresent()) {
            // MiningService reached its exact PREPARE capacity predicate and rejected the
            // forbidden geometry before any pocket mutation. This typed fact is terminal; generic
            // replanning would merely route back to the same guard.
            finishActive(bot, plan, evaluate(bot, plan),
                    replayGuard.orElseThrow().failureReason(), false, true,
                    GoalResult.Status.FAILED);
            return;
        }
        // A failed service with any live disposal identity still owns a physical world mutation.
        // Generic replanning would restore MINE_ORE from its separate cursor namespace and replace
        // this checkpoint on the next capture, orphaning the unsealed ItemEntity ledger.  The task
        // has already exhausted its own bounded recovery, so stop the mission with the exact typed
        // reason instead of manufacturing a fresh transaction.
        if (plan.current != null && plan.current.kind() == GoalStep.Kind.MINING_SERVICE
                && hasActiveServicePocket(plan.taskCheckpoint)) {
            finishActive(bot, plan, evaluate(bot, plan), reason,
                    false, true, GoalResult.Status.FAILED);
            return;
        }
        boolean failedClosedPrimaryService = plan.current != null
                && plan.current.kind() == GoalStep.Kind.MINING_SERVICE
                && plan.taskCheckpointKind == GoalStep.Kind.MINING_SERVICE
                && plan.auxiliaryMiningCheckpoint.isEmpty()
                && !plan.miningCheckpoint.isEmpty()
                && failedClosedAuxiliaryServiceMatches(
                plan.taskCheckpoint, plan.miningCheckpoint);
        boolean failedClosedAuxiliaryService = plan.current != null
                && plan.current.kind() == GoalStep.Kind.MINING_SERVICE
                && plan.taskCheckpointKind == GoalStep.Kind.MINING_SERVICE
                && !plan.auxiliaryMiningCheckpoint.isEmpty()
                && failedClosedAuxiliaryServiceMatches(
                plan.taskCheckpoint, plan.auxiliaryMiningCheckpoint);
        Optional<MiningServiceTask.RestoreMetadata> settledTerminalService =
                settledTerminalServiceFailure(plan, reason);
        if (settledTerminalService.isPresent()) {
            // The double-sealed pocket has no remaining physical authority. This receipt exists
            // solely to bridge TaskManager -> GoalExecutor settlement. Persist its semantic
            // identity and exact work face before consuming it so repair/craft/supply prefixes and
            // process restarts cannot reopen the same pocket as a fresh service transaction.
            MiningServiceTask.RestoreMetadata settledMetadata =
                    settledTerminalService.orElseThrow();
            SettledServiceAuthority authority = persistedServiceAuthority(
                    settledMetadata).orElseThrow();
            SettledServiceTombstone tombstone = new SettledServiceTombstone(
                    authority, reason);
            Optional<SettledServiceTombstone> sameGeometry =
                    plan.settledServiceTombstones.values().stream()
                            .filter(settled -> settled.sameGeometry(tombstone))
                            .findFirst();
            SettledServiceTombstone existing = sameGeometry.orElse(null);
            if (existing != null && !existing.equals(tombstone)
                    || existing == null && plan.settledServiceTombstones.size()
                    >= MAX_SETTLED_SERVICE_TOMBSTONES) {
                BotLog.task(bot, "goal_terminal_service_tombstone_capacity_rejected",
                        "reason", reason,
                        "entries", plan.settledServiceTombstones.size());
                finishActive(bot, plan, evaluate(bot, plan), reason,
                        false, true, GoalResult.Status.FAILED);
                return;
            }
            plan.settledServiceTombstones.putIfAbsent(tombstone.key(), tombstone);
            plan.taskCheckpoint.clear();
            plan.taskCheckpointKind = null;
            // Disconnect the failed receipt source before any immediate capture/restart window;
            // the bounded tombstone is now the sole durable authority for this result.
            plan.currentTask = null;
            BotLog.task(bot, "goal_terminal_service_receipt_consumed",
                    "reason", reason,
                    "face", authority.geometry().workFace().toShortString(),
                    "entries", plan.settledServiceTombstones.size());
            if (!bot.getServerWorld().getRegistryKey().getValue().toString()
                    .equals(settledMetadata.serviceDimension())) {
                finishActive(bot, plan, evaluate(bot, plan), reason,
                        false, true, GoalResult.Status.FAILED);
                return;
            }
        }
        if (resumeOreDigAfterToolRecovery(bot, plan, reason)) {
            return;
        }
        if (scheduleOrdinaryChannelToolResupply(bot, plan, reason)) {
            return;
        }
        if ("ore_dig_inventory_service_required".equals(reason)) {
            if (!scheduleRareInventoryService(bot, plan)
                    && !scheduleBoundedCapacityHandoff(bot, plan)) {
                finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            }
            return;
        }
        if (isLongRareChannelToolFailure(plan, reason)) {
            if (!scheduleRareResourceRetry(bot, plan, reason)) {
                finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            }
            return;
        }
        if (reason != null && reason.startsWith("ore_dig_torch_epoch_exhausted:")) {
            if (!scheduleRareResourceRetry(bot, plan, reason)) {
                finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            }
            return;
        }
        if (isLongRareResourceEpochTimeout(plan, reason)) {
            if (!scheduleRareResourceRetry(bot, plan, reason)) {
                // Every funded epoch owns one independent 24,000-tick window: the batch's own
                // retry first, then bounded mission-margin epochs while the shared pool lasts.
                // Once both are spent the cumulative checkpoint is terminal and must not fall
                // through to a refreshable replan.
                finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            }
            return;
        }
        GoalStep failedStep = plan.current;
        if (isUnsettledHuntPhysicalDebt(
                failedStep == null ? null : failedStep.kind(), reason)) {
            // Meat in inventory cannot erase a return-to-surface debt. In particular, Goal.Food
            // normally treats HUNT as best-effort; letting these failures reach that branch would
            // skip directly to cooking while the bot is still underground.
            finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            return;
        }
        // A descent that is physically below its requested hand-off layer is a broken safety
        // invariant, not useful mining progress. Replanning from that Y would skip DESCEND_TO_Y and
        // continue the mission on an unverified floor, so terminate the mission fail-closed.
        if (reason != null && (reason.startsWith("descend_overshoot_unrecoverable")
                || reason.startsWith("descend_landing_pose_drift")
                || reason.startsWith("descend_invalid_checkpoint")
                || reason.startsWith("descend_timeout")
                || reason.startsWith("descend_water_seal_checkpoint_capacity")
                || reason.startsWith("descend_no_safe_landing")
                || reason.startsWith("dig_down_return_failed")
                // AcquireWater owns a durable, bounded surface-search cursor. Once that cursor or
                // its hard budget is exhausted, replanning the same acquisition cannot create new
                // evidence; doing so used to replay satisfied craft steps and eventually replace
                // the exhausted checkpoint with a fresh 12,000-tick search.
                || reason.startsWith("acquire_water_timeout")
                || reason.startsWith("acquire_water_search_exhausted")
                || reason.startsWith("acquire_water_no_reachable_surface_route")
                || reason.startsWith("acquire_water_surface_return_unreachable")
                || reason.startsWith("acquire_water_invalid_checkpoint")
                // The persisted search cursor has factually observed all four directions at the
                // same work face as closed. Replaying that cursor cannot reveal a new exit and used
                // to refresh progress by rotating in place until the global timeout.
                || reason.startsWith("create_obsidian_search_enclosed")
                // Both perpendicular branch candidates are factually old or unsafe, while forward
                // is an observed boundary and reverse is the controlled corridor. Replanning the
                // same durable cursor cannot create a new work face and only repeats the failure.
                || reason.startsWith("ore_dig_branch_boundary_trapped:")
                // OreDig has already spent its durable physical-pickup deadline at this point.
                // A fresh plan cannot recover the vanished/occluded finite drop, and must not
                // replace the factual coordinate with an unrelated underground supply error.
                || reason.startsWith("ore_dig_drop_unrecovered"))) {
            finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            return;
        }
        // A failed DESCEND attempt is terminal evidence for that task instance, not a resumable
        // cursor. Its saved target_count is the original batch size while a fresh plan asks only
        // for the remaining delivery; carrying the old checkpoint into that smaller step makes
        // DigDown reject it as corrupt and causes a second pointless replan. RETURN failure is
        // handled fail-closed above because unresolved physical return debt must never be erased.
        if (plan.current != null && plan.current.kind() == GoalStep.Kind.MINE) {
            plan.taskCheckpoint.clear();
            plan.taskCheckpointKind = null;
        }
        // 第4层:显式 best-effort 步骤失败不阻断整体目标——跳过它直接继续下一步。
        // 长配额挖矿的地表食物 readiness 不带该标记，HUNT/COOK 失败必须阻断，不能把缺粮拖到矿底。
        // 打猎(Goal.Food)整体 best-effort:任何前置(砍树/做剑)失败都降级继续(用现有工具/空手猎),绝不卡死/发呆。
        // 例外:Food 目标的 COOK_FOOD 是终局产出步——失败(no_raw_food)=整个目标必败,skip 等于无声放弃。
        // 放行到下面的 replan:重新感知择源(打猎扑空后动物多半已不在,replan 会落到浆果/面包等兜底源;
        // 实测打猎 1t 扑空 → 烤无肉 → 静默结束,从未给过兜底源机会)。
        if (shouldSkipFailedStep(plan.goal, plan.current, reason)) {
            if (settledTerminalService.isEmpty()) {
                captureTaskEvidence(bot, plan);
            }
            if (plan.skippedTargetReceipts.size()
                    >= MAX_SKIPPED_TARGET_RECEIPTS) {
                finishActive(bot, plan, evaluate(bot, plan),
                        "skipped_target_receipt_capacity_exhausted",
                        false, true, GoalResult.Status.FAILED);
                return;
            }
            SkippedTargetReceipt receipt =
                    new SkippedTargetReceipt(plan.current, reason);
            plan.skippedTargetReceipts.add(receipt);
            plan.skippedSteps.add(receipt.asSkippedStep());
            BotLog.task(bot, "goal_step_skipped_besteffort", "step", plan.current.describe(), "reason", reason);
            clearCompletedTaskCheckpoint(plan);
            plan.current = null;
            plan.currentTask = null;
            captureTransitionAndAssignNext(bot, plan);
            return;
        }
        // Phase A 进度感知预算(断点恢复核心):有进展→清零"连续无进展"计数。HUNT
        // 只把净新增生肉或首次访问的搜索 sector 视为额外进展，绝不让追逐造成的横移/下潜
        // 刷新预算；其他步骤保留矿道横移与下潜语义。
        net.minecraft.util.math.BlockPos bp = bot.getBlockPos();
        int curTarget = goalTargetCount(bot, plan.goal);
        int currentHuntRawMeat = rawMeatCount(bot);
        int currentHuntVisitedSectors = plan.huntSearchCursor.visitedCount();
        String currentDimension = bot.getServerWorld().getRegistryKey()
                .getValue().toString();
        boolean madeProgress = madeReplanProgress(
                plan.current == null ? null : plan.current.kind(),
                plan.completedSteps, plan.snapSteps,
                curTarget, plan.snapTargetCount,
                currentHuntRawMeat, plan.snapHuntRawMeat,
                currentHuntVisitedSectors, plan.snapHuntVisitedSectors,
                currentDimension, plan.snapDimension,
                bp.getX(), bp.getY(), bp.getZ(),
                plan.snapX, plan.snapY, plan.snapZ);
        if (madeProgress) {
            plan.replanCount = 0; // 进展赦免
        }
        plan.snapSteps = plan.completedSteps;
        plan.snapTargetCount = curTarget;
        plan.snapX = bp.getX();
        plan.snapY = bp.getY();
        plan.snapZ = bp.getZ();
        plan.snapDimension = currentDimension;
        plan.snapHuntRawMeat = currentHuntRawMeat;
        plan.snapHuntVisitedSectors = currentHuntVisitedSectors;
        // 死亡闸:连续 3 次无进展 replan，或耗尽与原始长配额批次数绑定的终生预算，
        // 或 replan 关闭 → 判死。计数在闸后递增，因此上限表示实际允许的 replan 次数。
        if (!withinReplanBudget(plan.goal, plan.replanCount, plan.lifetimeReplans)
                || !AIBotConfig.get().goal().replanOnFailureEnabled()) {
            finishActive(bot, plan, evaluate(bot, plan), reason, false, true);
            return;
        }
        plan.replanCount++;
        plan.lifetimeReplans++;
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                bot, plan.goal, snapshotContext(plan), plan.missionId.toString());
        Optional<CreateObsidianTask.RestoreMetadata> obsidianRestore =
                CreateObsidianTask.inspectCheckpoint(plan.obsidianCheckpoint);
        List<GoalStep> replanned = new ArrayList<>(applySkippedTargetReceipts(
                fresh.success() ? fresh.steps() : List.of(),
                plan.skippedTargetReceipts));
        if (obsidianRestore.isPresent()) {
            CreateObsidianTask.RestoreMetadata metadata = obsidianRestore.get();
            if (!fresh.success() && !metadata.transactionOpen()) {
                finishActive(bot, plan, evaluate(bot, plan),
                        settledTerminalService.isPresent() ? reason
                                : "replan_failed:" + String.join(",", fresh.unresolved()),
                        false, true);
                return;
            }
            // An open water/pickup/break transaction is a physical obligation and must be settled
            // before any newly planned acquisition. A safe search checkpoint may wait behind tool
            // or water repair, but its original target identity is still retained.
            reconcileObsidianSteps(replanned, metadata.targetCount(), metadata.transactionOpen());
        }
        if (failedClosedPrimaryService && fresh.success()) {
            OreDigTask.RestoreMetadata failedCursor = OreDigTask.inspectCheckpoint(
                    plan.miningCheckpoint, 0).orElseThrow();
            Set<Block> failedFamily = failedCursor.ores();
            boolean sameFamilyContinuation = hasFreshMiningSuccessor(
                    replanned, failedFamily);
            // The service has already double-sealed its empty pocket and published PREPARE without
            // pocket identity.  Retire only that exact task checkpoint.  Its closed primary cursor
            // remains authoritative while the fresh plan still contains the same ore family; once
            // the dependency is satisfied, keeping it would bind a later unrelated service to a
            // stale work face.
            plan.taskCheckpoint.clear();
            plan.taskCheckpointKind = null;
            if (sameFamilyContinuation) {
                BotLog.task(bot, "goal_failed_primary_service_cursor_retained",
                        "reason", reason,
                        "family", OreDigTask.oreFingerprint(failedFamily));
            } else {
                plan.miningCheckpoint.clear();
                BotLog.task(bot, "goal_failed_primary_service_retired",
                        "reason", reason,
                        "family", OreDigTask.oreFingerprint(failedFamily));
            }
        }
        if (failedClosedAuxiliaryService && fresh.success()) {
            OreDigTask.RestoreMetadata failedCursor = OreDigTask.inspectCheckpoint(
                    plan.auxiliaryMiningCheckpoint, 0).orElseThrow();
            Set<Block> failedFamily = failedCursor.ores();
            boolean sameFamilyContinuation = hasFreshMiningSuccessor(
                    replanned, failedFamily);
            // The failed service has no live pocket, so its task checkpoint can be retired once a
            // fresh plan exists. Its closed ordinary cursor is different: preserve it while that
            // same family is still planned, including across intervening repair steps and restarts.
            plan.taskCheckpoint.clear();
            plan.taskCheckpointKind = null;
            if (sameFamilyContinuation) {
                plan.auxiliaryMiningContinuationFingerprint =
                        OreDigTask.oreFingerprint(failedFamily);
                BotLog.task(bot, "goal_failed_auxiliary_service_cursor_retained",
                        "reason", reason,
                        "family", OreDigTask.oreFingerprint(failedFamily));
            } else {
                plan.auxiliaryMiningCheckpoint.clear();
                plan.auxiliaryMiningContinuationFingerprint = "";
                BotLog.task(bot, "goal_failed_auxiliary_service_retired",
                        "reason", reason,
                        "family", OreDigTask.oreFingerprint(failedFamily));
            }
        }
        BotLog.task(bot, "goal_replan", "goal", plan.goal, "reason", reason,
                "steps", replanned.stream().map(GoalStep::describe).toList(),
                "unresolved", fresh.unresolved());
        if ((!fresh.success() && obsidianRestore.isEmpty()) || replanned.isEmpty()) {
            finishActive(bot, plan, evaluate(bot, plan),
                    settledTerminalService.isPresent() ? reason
                            : fresh.success() ? "replan_empty"
                            : "replan_failed:" + String.join(",", fresh.unresolved()),
                    false, true);
            return;
        }
        // 防呆:若重规划的第一步与刚失败的步骤完全相同,且失败是"硬卡死"类(挖不动/卡住/超时),
        // 重试只会原样再失败一次(实测#9 的 replan 风暴根因)。直接判失败,交大脑/玩家换思路。
        if (plan.current != null && plan.current.equals(replanned.get(0))
                && isHardFailure(reason) && !madeProgress) {
            finishActive(bot, plan, evaluate(bot, plan), "replan_same_step:" + reason, false, true);
            return;
        }
        plan.steps.clear();
        plan.steps.addAll(replanned);
        plan.totalSteps = replanned.size();
        plan.current = null;
        plan.currentTask = null;
        report(bot, "遇到问题,我重新规划了一次。");
        captureTransitionAndAssignNext(bot, plan);
    }

    private static Optional<MiningServiceTask.RestoreMetadata> settledTerminalServiceFailure(
            ActivePlan plan, String reason) {
        if (plan == null || plan.current == null
                || plan.current.kind() != GoalStep.Kind.MINING_SERVICE
                || plan.taskCheckpointKind != GoalStep.Kind.MINING_SERVICE
                || hasActiveServicePocket(plan.taskCheckpoint)) {
            return Optional.empty();
        }
        return MiningServiceTask.inspectCheckpoint(plan.taskCheckpoint)
                .filter(metadata -> !metadata.done()
                        && !metadata.terminalFailure().isBlank()
                        && metadata.terminalFailure().equals(reason));
    }

    /**
     * A generic background resupply can finish after OreDig has already published its typed tool
     * failure. Replanning the whole parent goal at that point is both unnecessary and harmful: an
     * underground obsidian dependency would be asked to re-establish every surface bootstrap
     * reserve even though the exact ore branch now has a usable replacement pickaxe. Resume only
     * the attested open batch, preserving its physical ledgers, cursor and already-spent hard
     * budget. Any identity mismatch falls through to the ordinary fail-closed planner path.
     */
    private boolean resumeOreDigAfterToolRecovery(AIPlayerEntity bot,
                                                   ActivePlan plan,
                                                   String reason) {
        if (plan.current == null || plan.current.kind() != GoalStep.Kind.MINE_ORE
                || plan.taskCheckpointKind != GoalStep.Kind.MINE_ORE) {
            return false;
        }
        int rareMissionTarget = rareMissionTargetForMiningStep(
                plan.goal, plan.current.ores());
        String expectedTargetReason = "need_better_tool:"
                + ToolTier.requiredPickaxeItemId(plan.current.ores());
        boolean targetToolRecovered = expectedTargetReason.equals(reason);
        boolean channelToolRecovered = rareMissionTarget == 0
                && hasRecoveredMiningChannelTool(bot, reason);
        if ((!targetToolRecovered && !channelToolRecovered)
                || plan.current.ores().stream().noneMatch(block ->
                ToolTier.canHarvestWithInventory(bot, block.getDefaultState()))) {
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> restored = OreDigTask.inspectCheckpoint(
                plan.taskCheckpoint, rareMissionTarget);
        if (restored.isEmpty()) {
            return false;
        }
        OreDigTask.RestoreMetadata metadata = restored.orElseThrow();
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.current.ores());
        if (!metadata.batchOpen()
                || !metadata.acceptsStepTarget(plan.current.count())
                || metadata.rareMissionTarget() != rareMissionTarget
                || !expectedFingerprint.equals(OreDigTask.oreFingerprint(metadata.ores()))) {
            return false;
        }

        Optional<Task> resumed = stepToTask(bot, plan.current, plan);
        if (resumed.isEmpty() || !(resumed.orElseThrow() instanceof OreDigTask)) {
            return false;
        }
        plan.currentTask = resumed.orElseThrow();
        BotLog.task(bot, "goal_step_retry_after_tool_recovery",
                "step", plan.current.describe(),
                "mission_id", plan.missionId,
                "budget_used", metadata.budgetUsed(),
                "face", metadata.cursor().face().toShortString());
        captureBeforeAndAfterDispatch(
                () -> markDirty(bot),
                () -> TaskManager.INSTANCE.assign(bot, plan.currentTask,
                        TaskOrigin.mission(plan.missionId, plan.current.describe())));
        return true;
    }

    private static boolean hasRecoveredMiningChannelTool(AIPlayerEntity bot, String reason) {
        String prefix = "need_mining_channel_tool:";
        if (reason == null || !reason.startsWith(prefix)) {
            return false;
        }
        String requiredId = reason.substring(prefix.length());
        if (requiredId.isBlank()) {
            return false;
        }
        return java.util.stream.Stream.concat(
                        bot.getInventory().main.stream(), bot.getInventory().offHand.stream())
                .anyMatch(stack -> !stack.isEmpty()
                        && requiredId.equals(Registries.ITEM.getId(stack.getItem()).toString())
                        && MiningServiceTask.usableDurability(stack) > 0);
    }

    private static boolean isLongRareChannelToolFailure(ActivePlan plan, String reason) {
        return plan.current != null
                && plan.current.kind() == GoalStep.Kind.MINE_ORE
                && originalLongRareOreTargetCount(plan.goal) >= MiningBudget.EXPEDITION_THRESHOLD
                && "need_mining_channel_tool:minecraft:stone_pickaxe".equals(reason)
                && miningStepFeedsGoal(plan.goal, plan.current.ores());
    }

    private static boolean isLongRareResourceEpochTimeout(ActivePlan plan, String reason) {
        if (plan.current == null || plan.current.kind() != GoalStep.Kind.MINE_ORE
                || plan.taskCheckpointKind != GoalStep.Kind.MINE_ORE) {
            return false;
        }
        int missionTarget = originalLongRareOreTargetCount(plan.goal);
        return OreDigTask.inspectCheckpoint(plan.taskCheckpoint, missionTarget)
                .filter(ore -> ore.rareMissionTarget() == missionTarget
                        && miningStepFeedsGoal(plan.goal, ore.ores()))
                .filter(ore -> isLongRareResourceEpochTimeout(ore, reason))
                .isPresent();
    }

    static boolean isLongRareResourceEpochTimeout(OreDigTask.RestoreMetadata ore,
                                                  String reason) {
        if (ore == null || reason == null
                || !reason.startsWith("ore_dig_timeout collected=")
                || !ore.batchOpen()
                || ore.rareMissionTarget() < MiningBudget.EXPEDITION_THRESHOLD) {
            return false;
        }
        int epochCapacity = MiningBudget.rareMissionResourceEpochCapacity(
                MiningBudget.rareMissionBatchCount(ore.rareMissionTarget()));
        if (ore.resourceEpoch() < 0 || ore.resourceEpoch() >= epochCapacity) {
            return false;
        }
        return ore.budgetUsed()
                == MiningMissionBudget.rareOreDigCumulativeHardWindowTicks(
                ore.resourceEpoch(), epochCapacity);
    }

    /**
     * An ordinary OreDig can discover channel-tool exhaustion after it has switched its hand to a
     * torch or another utility item. In that state the generic low-durability watcher has no
     * factual held pickaxe to observe. Restore and pause the exact open batch, run one physical
     * ResupplyTask, then let the normal pause stack resume that same task instance. The checkpoint
     * debit makes this bounded across retries and process restarts.
     */
    private boolean scheduleOrdinaryChannelToolResupply(AIPlayerEntity bot,
                                                        ActivePlan plan,
                                                        String reason) {
        String prefix = "need_mining_channel_tool:";
        if (plan.current == null || plan.current.kind() != GoalStep.Kind.MINE_ORE
                || plan.taskCheckpointKind != GoalStep.Kind.MINE_ORE
                || rareMissionTargetForMiningStep(plan.goal, plan.current.ores()) != 0
                || reason == null || !reason.startsWith(prefix)) {
            return false;
        }
        String requiredId = reason.substring(prefix.length());
        Item requested;
        try {
            requested = Registries.ITEM.getOptionalValue(Identifier.of(requiredId)).orElse(null);
        } catch (RuntimeException invalidIdentifier) {
            return false;
        }
        if (requested == null) {
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> metadata =
                OreDigTask.inspectCheckpoint(plan.taskCheckpoint, 0);
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.current.ores());
        if (metadata.isEmpty()
                || !metadata.orElseThrow().batchOpen()
                || !metadata.orElseThrow().acceptsStepTarget(plan.current.count())
                || metadata.orElseThrow().rareMissionTarget() != 0
                || metadata.orElseThrow().inventoryServiceUsed()
                || !expectedFingerprint.equals(OreDigTask.oreFingerprint(
                metadata.orElseThrow().ores()))
                || plan.current.ores().stream().noneMatch(block ->
                ToolTier.canHarvestWithInventory(bot, block.getDefaultState()))) {
            return false;
        }
        Optional<Map<String, String>> debited =
                OreDigTask.debitChannelToolResupply(plan.taskCheckpoint);
        if (debited.isEmpty()) {
            return false;
        }
        plan.taskCheckpoint.clear();
        plan.taskCheckpoint.putAll(debited.orElseThrow());
        if (expectedFingerprint.equals(plan.miningCheckpoint.get("ore_fingerprint"))) {
            plan.miningCheckpoint.clear();
            plan.miningCheckpoint.putAll(plan.taskCheckpoint);
        }
        Optional<Task> resumed = stepToTask(bot, plan.current, plan);
        if (resumed.isEmpty() || !(resumed.orElseThrow() instanceof OreDigTask)) {
            return false;
        }
        plan.currentTask = resumed.orElseThrow();
        captureBeforeAndAfterDispatch(
                () -> markDirty(bot),
                () -> {
                    TaskManager.INSTANCE.assign(bot, plan.currentTask,
                            TaskOrigin.mission(plan.missionId, plan.current.describe()));
                    TaskManager.INSTANCE.pauseFor(bot, "ore_channel_tool_resupply");
                    TaskManager.INSTANCE.assign(bot, ResupplyTask.tool(requested),
                            TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND,
                                    "ore_channel_tool_resupply"));
                });
        BotLog.task(bot, "goal_ore_channel_resupply_scheduled",
                "required", requiredId,
                "mission_id", plan.missionId,
                "budget_used", metadata.orElseThrow().budgetUsed(),
                "face", metadata.orElseThrow().cursor().face().toShortString());
        return true;
    }

    /**
     * Atomically trades this exact open batch's single resource retry — or, once that is spent,
     * one bounded mission-level margin epoch (F2) — for one fresh rare-service step while
     * preserving the OreDig hard budget, cursor and physical pickup/break ledgers.
     */
    private boolean scheduleRareResourceRetry(AIPlayerEntity bot,
                                              ActivePlan plan,
                                              String reason) {
        if (plan.current == null || plan.current.kind() != GoalStep.Kind.MINE_ORE
                || plan.taskCheckpointKind != GoalStep.Kind.MINE_ORE) {
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> metadata =
                OreDigTask.inspectCheckpoint(plan.taskCheckpoint,
                        originalLongRareOreTargetCount(plan.goal));
        if (metadata.isEmpty()) {
            return false;
        }
        OreDigTask.RestoreMetadata ore = metadata.orElseThrow();
        boolean torchFailure = reason.equals(OreDigTask.resourceEpochFailureReason(
                ore.torchPlacements(), ore.resourceEpoch()));
        boolean channelToolFailure = isLongRareChannelToolFailure(plan, reason);
        boolean epochTimeout = isLongRareResourceEpochTimeout(ore, reason);
        // Epochs beyond the per-batch retry are paid from the finite mission margin pool; the
        // durable ledger below makes every draw exactly-once across replans and restarts.
        int marginPool = rareMissionEpochMarginPool(plan.goal);
        boolean marginDraw = ore.resourceEpoch()
                >= MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH;
        if (!ore.batchOpen()
                || ore.rareMissionTarget() != originalLongRareOreTargetCount(plan.goal)
                || plan.rareResourceRetriesUsed != ore.resourceEpoch()
                || marginDraw && plan.rareEpochMarginUsed >= marginPool
                || (!torchFailure && !channelToolFailure && !epochTimeout)
                || !miningStepFeedsGoal(plan.goal, ore.ores())) {
            return false;
        }
        Optional<Map<String, String>> advanced =
                OreDigTask.advanceResourceEpoch(plan.taskCheckpoint);
        if (advanced.isEmpty()) {
            return false;
        }

        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                bot, plan.goal, snapshotContext(plan), plan.missionId.toString());
        if (!fresh.success()) {
            return false;
        }
        List<GoalStep> continuation = new ArrayList<>(applySkippedTargetReceipts(
                fresh.steps(), plan.skippedTargetReceipts));
        String fingerprint = OreDigTask.oreFingerprint(ore.ores());
        int serviceBoundary = goalTargetCount(bot, plan.goal);
        int serviceIndex = -1;
        int miningIndex = -1;
        for (int index = 0; index < continuation.size(); index++) {
            GoalStep step = continuation.get(index);
            if (serviceIndex < 0 && step.isRareOreService()
                    && step.count() == serviceBoundary
                    && step.rareOreMissionTarget() == originalLongRareOreTargetCount(plan.goal)
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                serviceIndex = index;
            } else if (miningIndex < 0 && step.kind() == GoalStep.Kind.MINE_ORE
                    && ore.acceptsStepTarget(step.count())
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                miningIndex = index;
            }
        }
        if (miningIndex < 0) {
            return false;
        }
        GoalStep service = serviceIndex >= 0
                ? continuation.get(serviceIndex)
                : GoalStep.rareOreService(
                ore.ores(), serviceBoundary, originalLongRareOreTargetCount(plan.goal));
        GoalStep mining = continuation.get(miningIndex);
        if (serviceIndex >= 0) {
            if (serviceIndex > miningIndex) {
                continuation.remove(serviceIndex);
                continuation.remove(miningIndex);
            } else {
                continuation.remove(miningIndex);
                continuation.remove(serviceIndex);
            }
        } else {
            continuation.remove(miningIndex);
        }
        continuation.add(0, mining);
        continuation.add(0, service);

        // Commit only after the complete successor schedule has been proven. A crash can therefore
        // observe either the failed epoch plus its task, or the advanced epoch plus the
        // service-first schedule (margin ledger included), never a refreshed cursor with no
        // corresponding epoch or margin debit.
        plan.taskCheckpoint.clear();
        plan.taskCheckpoint.putAll(advanced.orElseThrow());
        plan.taskCheckpointKind = GoalStep.Kind.MINE_ORE;
        plan.miningCheckpoint.clear();
        plan.miningCheckpoint.putAll(advanced.orElseThrow());
        plan.rareResourceRetriesUsed = ore.resourceEpoch() + 1;
        if (marginDraw) {
            plan.rareEpochMarginUsed++;
            BotLog.task(bot, "rare_epoch_margin_drawn",
                    "used", plan.rareEpochMarginUsed,
                    "pool", marginPool,
                    "epoch", plan.rareResourceRetriesUsed);
        }
        plan.steps.clear();
        plan.steps.addAll(continuation);
        plan.totalSteps = continuation.size();
        plan.current = null;
        plan.currentTask = null;
        BotLog.task(bot, "goal_rare_resource_epoch_advanced",
                "epoch", plan.rareResourceRetriesUsed,
                "trigger", channelToolFailure ? "channel_tool"
                        : epochTimeout ? "epoch_timeout" : "torch",
                "budget", ore.budgetUsed(),
                "face", ore.cursor().face().toShortString(),
                "service_boundary", service.count());
        captureTransitionAndAssignNext(bot, plan);
        return true;
    }

    /** Schedules the one sealed inventory service owned by this exact OreDig batch. */
    private boolean scheduleRareInventoryService(AIPlayerEntity bot, ActivePlan plan) {
        int missionTarget = originalLongRareOreTargetCount(plan.goal);
        if (plan.current == null || plan.current.kind() != GoalStep.Kind.MINE_ORE
                || missionTarget < MiningBudget.EXPEDITION_THRESHOLD) {
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> metadata =
                OreDigTask.inspectCheckpoint(plan.taskCheckpoint, missionTarget);
        if (metadata.isEmpty()) {
            return false;
        }
        OreDigTask.RestoreMetadata ore = metadata.orElseThrow();
        if (!ore.batchOpen() || ore.rareMissionTarget() != missionTarget
                || !miningStepFeedsGoal(plan.goal, ore.ores())) {
            return false;
        }
        Optional<Map<String, String>> debited =
                OreDigTask.debitInventoryService(plan.taskCheckpoint);
        if (debited.isEmpty()) {
            return false;
        }

        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                bot, plan.goal, snapshotContext(plan), plan.missionId.toString());
        if (!fresh.success()) {
            return false;
        }
        List<GoalStep> continuation = new ArrayList<>(applySkippedTargetReceipts(
                fresh.steps(), plan.skippedTargetReceipts));
        String fingerprint = OreDigTask.oreFingerprint(ore.ores());
        int serviceIndex = -1;
        int miningIndex = -1;
        for (int index = 0; index < continuation.size(); index++) {
            GoalStep step = continuation.get(index);
            if (serviceIndex < 0 && step.isRareOreService()
                    && step.rareOreMissionTarget() == missionTarget
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                serviceIndex = index;
            } else if (miningIndex < 0 && step.kind() == GoalStep.Kind.MINE_ORE
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                miningIndex = index;
            }
        }
        if (serviceIndex < 0 || miningIndex < 0) {
            return false;
        }
        GoalStep service = continuation.get(serviceIndex);
        GoalStep mining = continuation.get(miningIndex);
        if (serviceIndex > miningIndex) {
            continuation.remove(serviceIndex);
            continuation.remove(miningIndex);
        } else {
            continuation.remove(miningIndex);
            continuation.remove(serviceIndex);
        }
        continuation.add(0, mining);
        continuation.add(0, service);

        plan.taskCheckpoint.clear();
        plan.taskCheckpoint.putAll(debited.orElseThrow());
        plan.taskCheckpointKind = GoalStep.Kind.MINE_ORE;
        plan.miningCheckpoint.clear();
        plan.miningCheckpoint.putAll(debited.orElseThrow());
        plan.steps.clear();
        plan.steps.addAll(continuation);
        plan.totalSteps = continuation.size();
        plan.current = null;
        plan.currentTask = null;
        BotLog.task(bot, "goal_rare_inventory_service_scheduled",
                "budget", ore.budgetUsed(),
                "face", ore.cursor().face().toShortString(),
                "service_boundary", service.count());
        captureTransitionAndAssignNext(bot, plan);
        return true;
    }

    /**
     * Inserts a capacity-only ORE_BATCH service before retrying the exact failed ordinary or
     * small-rare OreDig step. A first service debits the shared inventory-service bit. A later
     * service must retain that bit and strictly advance either the delivered watermark or the
     * factual branch work face. The persisted service count is capped by the original target
     * count, matching MiningMissionBudget's exact dynamic-service upper bound across restarts.
     */
    private boolean scheduleBoundedCapacityHandoff(AIPlayerEntity bot, ActivePlan plan) {
        if (plan.current == null || plan.current.kind() != GoalStep.Kind.MINE_ORE
                || plan.taskCheckpointKind != GoalStep.Kind.MINE_ORE) {
            return false;
        }
        boolean primaryParent = plan.taskCheckpoint.equals(plan.miningCheckpoint)
                && plan.auxiliaryMiningCheckpoint.isEmpty();
        boolean existingAuxiliaryParent = plan.taskCheckpoint.equals(
                plan.auxiliaryMiningCheckpoint)
                && !plan.auxiliaryMiningCheckpoint.isEmpty();
        boolean protectedRareParent = isProtectedRareMiningCheckpoint(
                plan.goal, plan.miningCheckpoint)
                && !hasOreDigPhysicalLedger(plan.miningCheckpoint);
        boolean createAuxiliaryParent = plan.capacityParentNamespace == null
                && !primaryParent && !existingAuxiliaryParent
                && plan.auxiliaryMiningCheckpoint.isEmpty() && protectedRareParent;
        if (!primaryParent && !existingAuxiliaryParent && !createAuxiliaryParent) {
            return false;
        }
        Map<String, String> parentCheckpoint = primaryParent
                ? plan.miningCheckpoint
                : existingAuxiliaryParent
                ? plan.auxiliaryMiningCheckpoint : plan.taskCheckpoint;
        Optional<OreDigTask.RestoreMetadata> task =
                OreDigTask.inspectCheckpoint(plan.taskCheckpoint, 0);
        Optional<OreDigTask.RestoreMetadata> parent =
                OreDigTask.inspectCheckpoint(parentCheckpoint, 0);
        if (task.isEmpty() || parent.isEmpty()) {
            return false;
        }
        OreDigTask.RestoreMetadata ore = task.orElseThrow();
        OreDigTask.RestoreMetadata durable = parent.orElseThrow();
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.current.ores());
        CapacityParentNamespace expectedParent = primaryParent
                ? CapacityParentNamespace.MINING : CapacityParentNamespace.AUXILIARY;
        boolean repeatedHandoff = plan.capacityParentNamespace != null;
        boolean branchAdvanced = plan.capacityParentFace != null
                && !ore.cursor().face().equals(plan.capacityParentFace);
        boolean validHandoffProgress = repeatedHandoff
                ? plan.capacityParentNamespace == expectedParent
                && plan.capacityParentDelivered >= 0
                && plan.capacityParentFace != null
                && plan.capacityParentServicesUsed >= 1
                && plan.capacityParentServicesUsed < ore.targetCount()
                && ore.inventoryServiceUsed() && durable.inventoryServiceUsed()
                && (ore.delivered() > plan.capacityParentDelivered || branchAdvanced)
                : plan.capacityParentDelivered == -1
                && plan.capacityParentFace == null
                && plan.capacityParentServicesUsed == 0
                && !ore.inventoryServiceUsed() && !durable.inventoryServiceUsed();
        if (!ore.batchOpen() || !durable.batchOpen()
                || ore.rareMissionTarget() != 0 || durable.rareMissionTarget() != 0
                || !validHandoffProgress
                || !ore.acceptsStepTarget(plan.current.count())
                || !durable.acceptsStepTarget(plan.current.count())
                || ore.delivered() != durable.delivered()
                || !expectedFingerprint.equals(OreDigTask.oreFingerprint(ore.ores()))
                || !expectedFingerprint.equals(OreDigTask.oreFingerprint(durable.ores()))
                || !ore.cursor().equals(durable.cursor())
                || hasOreDigPhysicalLedger(plan.taskCheckpoint)
                || hasOreDigPhysicalLedger(parentCheckpoint)) {
            return false;
        }
        Optional<Map<String, String>> debit =
                OreDigTask.debitCapacityHandoff(
                        plan.taskCheckpoint, plan.capacityParentDelivered,
                        plan.capacityParentFace);
        if (debit.isEmpty()) {
            return false;
        }

        Map<String, String> debited = debit.orElseThrow();
        GoalStep retry = plan.current;
        GoalStep service = GoalStep.miningHandoffService(
                retry.ores(), Math.max(1, ore.delivered()),
                miningParentStoneLikeReserve(
                        plan.goal, 0, plan.miningCheckpoint));
        plan.taskCheckpoint.clear();
        plan.taskCheckpoint.putAll(debited);
        plan.taskCheckpointKind = GoalStep.Kind.MINE_ORE;
        plan.capacityParentDelivered = ore.delivered();
        plan.capacityParentFace = ore.cursor().face();
        plan.capacityParentServicesUsed++;
        if (primaryParent) {
            plan.miningCheckpoint.clear();
            plan.miningCheckpoint.putAll(debited);
            plan.capacityParentNamespace = CapacityParentNamespace.MINING;
        } else {
            plan.auxiliaryMiningCheckpoint.clear();
            plan.auxiliaryMiningCheckpoint.putAll(debited);
            plan.capacityParentNamespace = CapacityParentNamespace.AUXILIARY;
        }
        plan.steps.addFirst(retry);
        plan.steps.addFirst(service);
        plan.totalSteps++;
        plan.stepLabels.add(service.describe());
        plan.current = null;
        plan.currentTask = null;
        BotLog.task(bot, "goal_mining_capacity_handoff_scheduled",
                "budget", ore.budgetUsed(),
                "face", ore.cursor().face().toShortString(),
                "stone_reserve", service.miningHandoffStoneLikeReserve(),
                "delivered_watermark", plan.capacityParentDelivered,
                "face_watermark", plan.capacityParentFace.toShortString(),
                "services_used", plan.capacityParentServicesUsed,
                "service_limit", ore.targetCount(),
                "repeat", repeatedHandoff,
                "ore_fingerprint", expectedFingerprint);
        captureTransitionAndAssignNext(bot, plan);
        return true;
    }

    // 优化2:目标最近(withinTicks 内)是否整体失败过——供 ActionDispatcher 拦截大脑失败后的手动逐格挖矿。
    public boolean recentlyFailed(AIPlayerEntity bot, int withinTicks) {
        Integer t = lastGoalFailTick.get(bot.getUuid());
        return t != null && bot.getServer().getTicks() - t < withinTicks;
    }

    // B:用户发来新消息时清空原始目标记忆(允许用户正常更换目标);由 BrainCoordinator 在收到用户消息时调用。
    public void clearUserGoal(AIPlayerEntity bot) {
        userGoal.remove(bot.getUuid());
    }

    // B:sub 是否是 parent(用户原始目标)的前置——sub 的产物落在 parent 计划某一步的产出里。
    // 覆盖主 case:做铁镐(HaveItem)/挖铁(MineOre)都是挖钻石计划里的前置步骤,会被拦下。
    private boolean isPrerequisiteOf(AIPlayerEntity bot, Goal sub, Goal parent) {
        GoalPlanner.GoalPlan parentPlan = GoalPlanner.plan(bot, parent);
        Set<Item> items = new HashSet<>();
        Set<Block> ores = new HashSet<>();
        for (GoalStep s : parentPlan.steps()) {
            if (s.item() != null) {
                items.add(s.item());
            }
            if (s.kind() == GoalStep.Kind.MINE_ORE) {
                ores.addAll(s.ores());
            }
        }
        if (sub instanceof Goal.HaveItem hi) {
            return items.contains(hi.item());
        }
        if (sub instanceof Goal.MineOre mo) {
            for (Block b : mo.ores()) {
                if (ores.contains(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Optional<Task> stepToTask(AIPlayerEntity bot, GoalStep step, ActivePlan plan) {
        return switch (step.kind()) {
            // Planner GATHER counts are incremental deliveries; GatherQuotaTask owns an absolute
            // family quota (all log species, all forage foods, or the exact item). Convert at the
            // execution boundary just like CRAFT, otherwise a replan that asks for +3 logs beside
            // 29 existing logs is misread as an already-satisfied absolute target of 3.
            case GATHER -> Optional.of(new GatherQuotaTask(step.item(), gatherTargetCount(
                    GatherQuotaTask.acceptedInventoryCount(bot, step.item()), step.count())));
            // DIGDOWN(实测#8):MINE 步改用 DigDownTask——站着就近垂直下挖,不定位/不寻路,
            // 永不"够不到/走不过去"空转。取代旧的 OreSeekTask.digBlocks(它会锁定垂直够不到的石头 stuck)。
            case MINE -> Optional.of(new DigDownTask(
                    step.block(), step.count(), plan.takeTaskCheckpoint(GoalStep.Kind.MINE)));
            // OREDIG(实测#10):MINE_ORE 步改用 OreDigTask(BlockMiner 控制式直挖隧道),
            // 取代 OreSeekTask——后者"A*接近被埋矿"在 #6/#8/#10 连续 stuck。
            case MINE_ORE -> {
                Map<String, String> oreCheckpoint =
                        plan.checkpointForMineOre(step.ores());
                plan.currentCapacityParentRetry =
                        plan.isCapacityParentRetry(step, oreCheckpoint);
                int rareMissionTarget = rareMissionTargetForMiningStep(
                        plan.goal, step.ores());
                yield Optional.of(new OreDigTask(
                        step.ores(), step.count(), rareMissionTarget,
                        miningParentStoneLikeReserve(
                                plan.goal, rareMissionTarget,
                                rareMissionTarget > 0
                                        ? oreCheckpoint : plan.miningCheckpoint),
                        oreCheckpoint));
            }
            case MINING_SERVICE -> {
                MiningServiceInvocation invocation =
                        miningServiceInvocation(step, plan);
                Map<String, String> serviceCheckpoint =
                        plan.takeTaskCheckpoint(GoalStep.Kind.MINING_SERVICE);
                MiningCursor serviceCursor = serviceCursor(
                        bot, plan, step, serviceCheckpoint);
                yield Optional.of(new MiningServiceTask(
                        step.ores(),
                        serviceCheckpoint,
                        invocation.policy(),
                        invocation.boundary(),
                        plan.missionId.toString(),
                        invocation.target(),
                        serviceCursor,
                        plan.settledServiceTombstones.values().stream()
                                .map(SettledServiceTombstone::replayGuard)
                                .toList()));
            }
            // GoalStep.CRAFT carries an incremental delivery count, while CraftTask accepts an
            // absolute inventory target. Preserve already-crafted copies when the planner emits
            // multiple batches of the same tool (for example 1 bootstrap + 4 expedition picks).
            case CRAFT -> Optional.of(new CraftTask(step.item(), craftTargetCount(
                    io.github.zoyluo.aibot.action.InventoryAction.countItem(bot, step.item()),
                    step.count())));
            case SMELT -> Optional.of(new SmeltTask(step.input(), step.output(), step.count()));
            case MOVE -> Optional.of(new MoveTask(bot, step.pos()));
            // P3:FARM 步 → 数量受限的 FarmTask(就地开垦/播种/等熟/收割,收够 count 个产出即完成)。
            case FARM -> Optional.of(new FarmTask(bot.getBlockPos(), 4, step.input(), step.block(),
                    true, false, step.item(), step.count()));
            // 第4层:HUNT 步 → HuntTask 猎杀动物取生肉(备粮)。
            case HUNT -> Optional.of(new HuntTask(
                    step.count(), !step.bestEffort(), plan.huntSearchCursor,
                    plan.peekTaskCheckpoint(GoalStep.Kind.HUNT)));
            // P0 食物闭环:COOK_FOOD 步 → SmeltTask cookAll 模式,把背包生肉逐种烤成熟肉。
            case COOK_FOOD -> Optional.of(new SmeltTask(step.count(), !step.bestEffort()));
            // 蛋糕链:MILK_COW 步 → MilkCowTask 用空桶挤 count 桶牛奶。
            case MILK_COW -> Optional.of(new MilkCowTask(step.count()));
            // Phase2:PLACE_STATIONS 步 → 摆好工作台/熔炉/箱子。
            case PLACE_STATIONS -> Optional.of(new PlaceStationsTask());
            // Phase3:STOCKPILE 步 → 把背包资源存进附近箱子(存所有非工具)。
            case STOCKPILE -> Optional.of(new StockpileTask(true));
            // 挖深层矿:DESCEND_TO_Y 步 → 连续挖竖井下到矿层。
            case DESCEND_TO_Y -> Optional.of(new DescendToYTask(
                    step.pos().getY(), plan.takeTaskCheckpoint(GoalStep.Kind.DESCEND_TO_Y)));
            case ACQUIRE_WATER -> Optional.of(new io.github.zoyluo.aibot.task.AcquireWaterTask(
                    plan.origin, plan.peekTaskCheckpoint(GoalStep.Kind.ACQUIRE_WATER)));
            case MAKE_OBSIDIAN -> Optional.of(new CreateObsidianTask(
                    step.count(), plan.checkpointForObsidian()));
            // 盖房:BUILD 步 → BuildTask(自动选址 autoSite + 整地 flatten,真实起伏地形也能落成);材料已由规划期备齐;
            // 蓝图读取失败(被删/坏档)→ empty,assignNext 按"步骤无法执行"收尾。
            // flatten=true:真实地形罕有现成平地,lenient 选址选最平点 + FLATTEN 挖高填低整平(治 real_build no_flat_site)。
            case BUILD -> {
                try {
                    BlockPos anchor = plan.buildAnchor;
                    yield Optional.of(new BuildTask(
                            BlueprintLoader.load(step.tag()), anchor, anchor == null, anchor == null));
                } catch (IOException e) {
                    yield Optional.empty();
                }
            }
        };
    }

    /**
     * Mirrors MINING_SERVICE construction without consuming any task checkpoint.
     *
     * <p>An independent branch cursor remains the strongest cross-namespace witness. When that
     * namespace is legitimately absent, an interrupted service must retain its own already-decoded
     * cursor (including an explicit cursor-less legacy/obsidian checkpoint) instead of inventing
     * authority from the bot's crash position. Only a genuinely fresh service may anchor itself to
     * the current position.</p>
     */
    private static MiningCursor serviceCursor(
            AIPlayerEntity bot, ActivePlan plan, GoalStep step,
            Map<String, String> serviceCheckpoint) {
        MiningCursor branchCursor = plan.miningCursorForService(step.ores());
        if (branchCursor != null) {
            return branchCursor;
        }
        if (serviceCheckpoint != null && !serviceCheckpoint.isEmpty()) {
            return MiningServiceTask.inspectCheckpoint(serviceCheckpoint)
                    .map(MiningServiceTask.RestoreMetadata::miningCursor)
                    .orElse(null);
        }
        return MiningCursor.initial(bot.getBlockPos(), 48);
    }

    private static MiningServiceInvocation miningServiceInvocation(
            GoalStep step, ActivePlan plan) {
        if (step.isObsidianService()) {
            int target = step.obsidianTransactionTarget();
            return new MiningServiceInvocation(
                    MiningServiceTask.ServicePolicy.obsidian8(target, step.count()),
                    target, step.count());
        }
        if (step.isObsidianPreflight()) {
            int target = step.obsidianTransactionTarget();
            return new MiningServiceInvocation(
                    MiningServiceTask.ServicePolicy.obsidianPreflight(target), target, 0);
        }
        if (step.isRareOreService()) {
            int target = step.rareOreMissionTarget();
            return new MiningServiceInvocation(
                    MiningServiceTask.ServicePolicy.rareOreBatch(
                            target, step.count(), plan.rareResourceRetriesUsed),
                    target, step.count());
        }
        if (step.isRareDescentKitService()) {
            int target = step.rareDescentKitMissionTarget();
            return new MiningServiceInvocation(
                    MiningServiceTask.ServicePolicy.rareDescentKit(target), target, 0);
        }
        MiningServiceTask.ServicePolicy policy = step.isMiningHandoffService()
                ? MiningServiceTask.ServicePolicy.capacityHandoff(
                step.miningHandoffStoneLikeReserve())
                : MiningServiceTask.ServicePolicy.defaultOre(
                step.maintainsTunnelingTools());
        return new MiningServiceInvocation(policy, 0, 0);
    }

    static int craftTargetCount(int existing, int increment) {
        return Math.max(0, existing) + Math.max(1, increment);
    }

    static int gatherTargetCount(int existingFamilyCount, int increment) {
        return Math.max(0, existingFamilyCount) + Math.max(1, increment);
    }

    /** Replays an interrupted bootstrap in place; unresolved physical return debt always runs first. */
    private static void reconcileDigDownSteps(List<GoalStep> steps,
                                              DigDownTask.RestoreMetadata metadata) {
        GoalStep restored = GoalStep.mine(metadata.targetBlock(), metadata.targetCount());
        for (int index = 0; index < steps.size(); index++) {
            GoalStep step = steps.get(index);
            if (step.kind() == GoalStep.Kind.MINE && step.block() == metadata.targetBlock()) {
                steps.remove(index);
                if (!metadata.returnDebt()) {
                    steps.add(index, restored);
                    return;
                }
                break;
            }
        }
        if (metadata.returnDebt()) {
            steps.add(0, restored);
        } else {
            steps.add(restored);
        }
    }

    /** An entered descent owns an unresolved physical hand-off and must survive replanning. */
    private static void reconcileDescendSteps(List<GoalStep> steps, int targetY) {
        steps.removeIf(step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y);
        steps.add(0, GoalStep.descendToY(targetY));
    }

    /**
     * An active ore batch may hold an exact face plus a physical break/pickup ledger. Replanning
     * at the mine layer can prepend a routine service checkpoint, but that must not overwrite the
     * active task cursor. Move the planner's live remaining-count batch to the front; a real tool
     * deficit will fail typed and replan into service without losing the branch debt.
     */
    private static void replayInterruptedOreBatchFirst(
            List<GoalStep> steps,
            OreDigTask.RestoreMetadata metadata) {
        String fingerprint = OreDigTask.oreFingerprint(metadata.ores());
        for (int index = 0; index < steps.size(); index++) {
            GoalStep step = steps.get(index);
            if (step.kind() == GoalStep.Kind.MINE_ORE
                    && metadata.acceptsStepTarget(step.count())
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                if (index > 0) {
                    steps.remove(index);
                    steps.add(0, step);
                }
                return;
            }
        }
        // A failed fresh planner can legitimately omit the interrupted task while its exact
        // break/pickup ledger remains physical authority. Recreate the remaining logical batch;
        // a fully delivered open checkpoint uses its original target only to run the final commit
        // tick and clear that ledger.
        int replayTarget = metadata.remainingCount() > 0
                ? metadata.remainingCount() : metadata.targetCount();
        steps.add(0, GoalStep.mineOre(metadata.ores(), replayTarget));
    }

    /**
     * A settled capacity handoff leaves no open service/pickup/break transaction. Its OreDig
     * checkpoint is continuation identity, not authority to jump ahead of newly-live repair
     * prerequisites. Keep the matching fresh step in planner order; if planning omitted it, append
     * one after the dependency prefix instead of moving it to the front.
     */
    private static void reconcileCapacityRetryAfterPrerequisites(
            List<GoalStep> steps,
            OreDigTask.RestoreMetadata metadata) {
        String fingerprint = OreDigTask.oreFingerprint(metadata.ores());
        for (GoalStep step : steps) {
            if (step.kind() == GoalStep.Kind.MINE_ORE
                    && metadata.acceptsStepTarget(step.count())
                    && fingerprint.equals(OreDigTask.oreFingerprint(step.ores()))) {
                return;
            }
        }
        steps.add(GoalStep.mineOre(metadata.ores(), metadata.targetCount()));
    }

    /**
     * Extracts only the sealed successor of a target64 descent kit. Returning an empty list is
     * intentional: the restored service can finish alone and the ordinary postcondition repair
     * will then plan from its live ready/depot attestation. It is safer than replaying any
     * bootstrap acquisition between KIT and DESCEND.
     */
    static List<GoalStep> rareDescentTail(List<GoalStep> steps, Set<Block> ores) {
        if (steps == null || steps.isEmpty() || ores == null || ores.isEmpty()) {
            return List.of();
        }
        int targetY = io.github.zoyluo.aibot.mining.MiningChain.bestY(ores);
        String fingerprint = OreDigTask.oreFingerprint(ores);
        for (int index = 0; index + 2 < steps.size(); index++) {
            GoalStep descend = steps.get(index);
            GoalStep boundaryZero = steps.get(index + 1);
            GoalStep firstBatch = steps.get(index + 2);
            if (descend.kind() == GoalStep.Kind.DESCEND_TO_Y
                    && descend.pos() != null
                    && descend.pos().getY() == targetY
                    && boundaryZero.isRareOreService()
                    && boundaryZero.count() == 0
                    && boundaryZero.rareOreMissionTarget() == 64
                    && fingerprint.equals(
                    OreDigTask.oreFingerprint(boundaryZero.ores()))
                    && firstBatch.kind() == GoalStep.Kind.MINE_ORE
                    && firstBatch.count() == MiningBudget.EXPEDITION_THRESHOLD
                    && fingerprint.equals(OreDigTask.oreFingerprint(firstBatch.ores()))) {
                return List.copyOf(steps.subList(index, steps.size()));
            }
        }
        return List.of();
    }

    /** Returns the one fresh preflight target only when its MAKE step proves the same identity. */
    private static int plannedObsidianPreflightTarget(List<GoalStep> steps) {
        int preflightTarget = -1;
        int makeTarget = -1;
        for (GoalStep step : steps) {
            if (step.isObsidianPreflight()) {
                int candidate = step.obsidianTransactionTarget();
                if (preflightTarget > 0 && preflightTarget != candidate) {
                    return -1;
                }
                preflightTarget = candidate;
            } else if (step.kind() == GoalStep.Kind.MAKE_OBSIDIAN) {
                if (makeTarget > 0 && makeTarget != step.count()) {
                    return -1;
                }
                makeTarget = step.count();
            }
        }
        return preflightTarget > 0 && preflightTarget == makeTarget
                ? preflightTarget : -1;
    }

    /**
     * Replays an entered obsidian transaction without a direct obsidian goal's stale acquisition
     * prefix. Compound goals retain every unrelated step because mergeGathers may hoist other
     * material acquisition ahead of MAKE_OBSIDIAN.
     */
    private static void rebuildObsidianAcquisition(Goal goal,
                                                   List<GoalStep> steps,
                                                   List<GoalStep> replayPrefix,
                                                   int transactionTarget) {
        List<GoalStep> remainder = new ArrayList<>();
        if (isDirectObsidianGoal(goal)) {
            boolean afterMake = false;
            for (GoalStep step : steps) {
                if (!afterMake) {
                    afterMake = step.kind() == GoalStep.Kind.MAKE_OBSIDIAN;
                    continue;
                }
                if (step.kind() != GoalStep.Kind.MAKE_OBSIDIAN
                        && !step.isObsidianPreflight()) {
                    remainder.add(step);
                }
            }
        } else {
            // Build and other compound goals can have unrelated material acquisition hoisted ahead
            // of MAKE_OBSIDIAN by mergeGathers. Preserve those steps; only the explicit preflight
            // and MAKE nodes are superseded by the restored transaction identity.
            for (GoalStep step : steps) {
                if (step.kind() != GoalStep.Kind.MAKE_OBSIDIAN
                        && !step.isObsidianPreflight()) {
                    remainder.add(step);
                }
            }
        }
        steps.clear();
        steps.addAll(replayPrefix);
        steps.add(GoalStep.makeObsidian(transactionTarget));
        steps.addAll(remainder);
    }

    private static boolean isDirectObsidianGoal(Goal goal) {
        return goal instanceof Goal.HaveItem haveItem && haveItem.item() == Items.OBSIDIAN
                || goal instanceof Goal.Stockpile stockpile && stockpile.item() == Items.OBSIDIAN;
    }

    static int capacityHandoffStoneLikeReserveForTargets(int obsidianTarget,
                                                          int longRareTarget) {
        return miningParentStoneLikeReserveForTargets(
                obsidianTarget, longRareTarget, false, 0);
    }

    /**
     * A side-fluid placement and a capacity handoff are owned by the same durable mining parent.
     * Before a long rare batch opens, ordinary bootstrap work protects only the emergency reserve.
     * Once the rare parent is open, epoch zero additionally protects its sealed retry heads; epoch
     * one has already spent that debit. Direct obsidian acquisition retains its larger immutable
     * bootstrap/channel-retry horizon throughout all prerequisite phases.
     */
    private static int miningParentStoneLikeReserve(
            Goal goal,
            int activeRareMissionTarget,
            Map<String, String> rareParentCheckpoint) {
        int longRareTarget = originalLongRareOreTargetCount(goal);
        Optional<OreDigTask.RestoreMetadata> openRareParent = longRareTarget > 0
                ? OreDigTask.inspectCheckpoint(rareParentCheckpoint, longRareTarget)
                .filter(metadata -> metadata.batchOpen()
                        && metadata.rareMissionTarget() == longRareTarget
                        && miningStepFeedsGoal(goal, metadata.ores()))
                : Optional.empty();
        boolean openingRareParent = activeRareMissionTarget == longRareTarget
                && longRareTarget > 0;
        return miningParentStoneLikeReserveForTargets(
                directObsidianTarget(goal),
                longRareTarget,
                openingRareParent || openRareParent.isPresent(),
                openRareParent.map(OreDigTask.RestoreMetadata::resourceEpoch).orElse(0));
    }

    static int miningParentStoneLikeReserveForTargets(int obsidianTarget,
                                                       int longRareTarget,
                                                       boolean openRareParent,
                                                       int rareParentResourceEpoch) {
        if (obsidianTarget > 0) {
            return capacityHandoffStoneLikeReserveForObsidianTarget(obsidianTarget);
        }
        if (longRareTarget >= MiningBudget.EXPEDITION_THRESHOLD
                && openRareParent && rareParentResourceEpoch == 0) {
            return MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE;
        }
        return MiningBudget.EMERGENCY_STONE_LIKE;
    }

    private static int directObsidianTarget(Goal goal) {
        return goal instanceof Goal.HaveItem haveItem
                && haveItem.item() == Items.OBSIDIAN ? haveItem.count()
                : goal instanceof Goal.Stockpile stockpile
                && stockpile.item() == Items.OBSIDIAN ? stockpile.count() : 0;
    }

    static int capacityHandoffStoneLikeReserveForObsidianTarget(int obsidianTarget) {
        if (obsidianTarget > 0) {
            return MiningServiceTask.ServicePolicy.bootstrapStoneLikeTarget(obsidianTarget)
                    + MiningBudget.OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE;
        }
        return MiningBudget.EMERGENCY_STONE_LIKE;
    }

    private static int directObsidianRemainingTarget(AIPlayerEntity bot,
                                                     Goal goal,
                                                     GoalEvaluation evaluation) {
        int carried = io.github.zoyluo.aibot.action.HarvestCore.countInventoryItems(
                bot, Set.of(Items.OBSIDIAN));
        if (goal instanceof Goal.HaveItem haveItem && haveItem.item() == Items.OBSIDIAN) {
            return Math.max(0, haveItem.count() - carried);
        }
        if (goal instanceof Goal.Stockpile stockpile && stockpile.item() == Items.OBSIDIAN) {
            return Math.max(0, stockpile.count() - evaluation.matched() - carried);
        }
        return -1;
    }

    /** Keeps the original transaction target while allowing prerequisite repair ahead of it. */
    private static void reconcileObsidianSteps(List<GoalStep> steps,
                                               int restoredTarget,
                                               boolean resumeFirst) {
        GoalStep restored = GoalStep.makeObsidian(restoredTarget);
        if (resumeFirst) {
            steps.removeIf(step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN);
            steps.add(0, restored);
            return;
        }
        int first = -1;
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).kind() != GoalStep.Kind.MAKE_OBSIDIAN) {
                continue;
            }
            if (first < 0) {
                first = index;
                steps.set(index, restored);
            } else {
                steps.remove(index--);
            }
        }
        if (first < 0) {
            steps.add(restored);
        }
    }

    private GoalEvaluation evaluate(AIPlayerEntity bot, ActivePlan plan) {
        GoalSnapshot snapshot = GoalSnapshotCollector.collect(bot, plan.goal, snapshotContext(plan));
        plan.lastStructure = snapshot.structure().orElse(null);
        return plan.predicate.evaluate(snapshot);
    }

    private static GoalSnapshotCollector.Context snapshotContext(ActivePlan plan) {
        return new GoalSnapshotCollector.Context(
                plan.origin,
                plan.boundContainers,
                plan.blueprint,
                plan.buildAnchor,
                plan.buildPlaced,
                plan.buildSkipped);
    }

    private static void captureTaskEvidence(AIPlayerEntity bot, ActivePlan plan) {
        if (plan.currentTask instanceof CheckpointableTask checkpointable && plan.current != null) {
            Map<String, String> candidate = checkpointable.checkpoint();
            boolean consumedTerminalReceipt = plan.current.kind()
                    == GoalStep.Kind.MINING_SERVICE
                    && MiningServiceTask.inspectCheckpoint(candidate)
                    .filter(metadata -> !metadata.terminalFailure().isBlank())
                    .map(metadata -> persistedServiceAuthority(metadata)
                            .map(authority -> java.util.Optional.ofNullable(
                                    plan.settledServiceTombstones.get(authority.key()))
                                    .filter(settled -> settled.failureReason().equals(
                                            metadata.terminalFailure()))
                                    .isPresent())
                            .orElse(false))
                    .orElse(false);
            // Empty means the task cannot attest a trustworthy successor state (for example a
            // constructor rejected a corrupt restore). Never erase the last durable checkpoint.
            if (!candidate.isEmpty() && !consumedTerminalReceipt) {
                plan.taskCheckpoint.clear();
                plan.taskCheckpoint.putAll(candidate);
                plan.taskCheckpointKind = plan.current.kind();
                if (plan.current.kind() == GoalStep.Kind.MINING_SERVICE
                        && !plan.auxiliaryMiningContinuationFingerprint.isBlank()
                        && ordinaryServiceParentMatches(candidate,
                        plan.auxiliaryMiningCheckpoint, false)) {
                    // The current service checkpoint now durably binds the closed auxiliary cursor;
                    // the ordinary service identity replaces the transient continuation marker.
                    plan.auxiliaryMiningContinuationFingerprint = "";
                }
                if (plan.current.kind() == GoalStep.Kind.MINE_ORE) {
                    String previousFingerprint = plan.miningCheckpoint.get("ore_fingerprint");
                    String currentFingerprint = plan.taskCheckpoint.get("ore_fingerprint");
                    if (plan.capacityParentNamespace != null
                            && plan.currentCapacityParentRetry) {
                        Map<String, String> parent = plan.capacityParentNamespace
                                == CapacityParentNamespace.AUXILIARY
                                ? plan.auxiliaryMiningCheckpoint : plan.miningCheckpoint;
                        parent.clear();
                        parent.putAll(plan.taskCheckpoint);
                    } else if (plan.capacityParentNamespace != null) {
                        // While a capacity debit is open, every non-owner OreDig is a repair
                        // prefix. Its task.* checkpoint is durable independently, but it may
                        // neither replace the parent branch nor an unrelated protected branch,
                        // even when it happens to use the same ore family.
                    } else if (shouldReplaceMiningCheckpoint(
                            previousFingerprint,
                            currentFingerprint,
                            miningStepFeedsGoal(plan.goal, plan.current.ores()))) {
                        plan.miningCheckpoint.clear();
                        plan.miningCheckpoint.putAll(plan.taskCheckpoint);
                    }
                } else if (plan.current.kind() == GoalStep.Kind.MAKE_OBSIDIAN) {
                    plan.obsidianCheckpoint.clear();
                    plan.obsidianCheckpoint.putAll(plan.taskCheckpoint);
                }
            }
        }
        if (plan.currentTask instanceof BuildTask build) {
            plan.blueprint = build.blueprint();
            plan.buildAnchor = build.anchor();
            plan.buildPlaced = build.placedBlocks();
            plan.buildSkipped = build.skippedBlocks();
        } else if (plan.currentTask instanceof StockpileTask stockpile) {
            plan.boundContainers.addAll(stockpile.depositedContainers());
        } else if (plan.currentTask instanceof PlaceStationsTask stations && !stations.placedPositions().isEmpty()) {
            plan.origin = stations.placedPositions().iterator().next();
        }
    }

    /** Releases the retry debit only after both durable namespaces attest the closed rare batch. */
    private static boolean settleCompletedRareBatch(ActivePlan plan) {
        int missionTarget = originalLongRareOreTargetCount(plan.goal);
        if (missionTarget < MiningBudget.EXPEDITION_THRESHOLD
                || plan.current == null || plan.current.kind() != GoalStep.Kind.MINE_ORE
                || plan.taskCheckpointKind != GoalStep.Kind.MINE_ORE
                || !plan.taskCheckpoint.equals(plan.miningCheckpoint)) {
            return false;
        }
        Optional<OreDigTask.RestoreMetadata> task = OreDigTask.inspectCheckpoint(
                plan.taskCheckpoint, missionTarget);
        Optional<OreDigTask.RestoreMetadata> mining = OreDigTask.inspectCheckpoint(
                plan.miningCheckpoint, missionTarget);
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.current.ores());
        if (task.isEmpty() || mining.isEmpty()
                || task.orElseThrow().batchOpen()
                || mining.orElseThrow().batchOpen()
                || task.orElseThrow().resourceEpoch() != 0
                || mining.orElseThrow().resourceEpoch() != 0
                || task.orElseThrow().rareMissionTarget() != missionTarget
                || !expectedFingerprint.equals(
                OreDigTask.oreFingerprint(task.orElseThrow().ores()))) {
            return false;
        }
        // The epoch counter is batch-scoped and resets with the closed commit. The margin ledger
        // (plan.rareEpochMarginUsed) is deliberately NOT reset here: it is mission-scoped and a
        // committed batch must not refund margin epochs the mission has already spent.
        plan.rareResourceRetriesUsed = 0;
        return true;
    }

    /** Distinguishes the debited parent retry from a different-family repair OreDig task. */
    private static boolean currentOreTaskOwnsCapacityParent(ActivePlan plan) {
        if (plan == null || plan.current == null
                || plan.current.kind() != GoalStep.Kind.MINE_ORE
                || plan.taskCheckpointKind != GoalStep.Kind.MINE_ORE
                || plan.capacityParentNamespace == null
                || !plan.currentCapacityParentRetry) {
            return false;
        }
        Map<String, String> parent = plan.capacityParentNamespace
                == CapacityParentNamespace.AUXILIARY
                ? plan.auxiliaryMiningCheckpoint : plan.miningCheckpoint;
        return !parent.isEmpty() && parent.equals(plan.taskCheckpoint)
                && java.util.Objects.equals(parent.get("ore_fingerprint"),
                OreDigTask.oreFingerprint(plan.current.ores()));
    }

    /** Closes the dynamic service debit only after its exact OreDig retry commits the batch. */
    private static boolean settleCompletedCapacityParent(ActivePlan plan) {
        if (plan.current == null || plan.current.kind() != GoalStep.Kind.MINE_ORE
                || plan.taskCheckpointKind != GoalStep.Kind.MINE_ORE
                || plan.capacityParentNamespace == null) {
            return false;
        }
        Map<String, String> parentCheckpoint = plan.capacityParentNamespace
                == CapacityParentNamespace.AUXILIARY
                ? plan.auxiliaryMiningCheckpoint : plan.miningCheckpoint;
        Optional<OreDigTask.RestoreMetadata> parent =
                OreDigTask.inspectCheckpoint(parentCheckpoint, 0);
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.current.ores());
        if (parent.isEmpty() || parent.orElseThrow().batchOpen()
                || parent.orElseThrow().rareMissionTarget() != 0
                || plan.capacityParentDelivered < 0
                || plan.capacityParentDelivered > parent.orElseThrow().targetCount()
                || plan.capacityParentFace == null
                || plan.capacityParentServicesUsed < 1
                || plan.capacityParentServicesUsed > parent.orElseThrow().targetCount()
                || !parentCheckpoint.equals(plan.taskCheckpoint)
                || !expectedFingerprint.equals(
                OreDigTask.oreFingerprint(parent.orElseThrow().ores()))
                || hasOreDigPhysicalLedger(parentCheckpoint)) {
            return false;
        }
        CapacityParentNamespace completedParent = plan.capacityParentNamespace;
        plan.capacityParentNamespace = null;
        plan.capacityParentDelivered = -1;
        plan.capacityParentFace = null;
        plan.capacityParentServicesUsed = 0;
        if (completedParent == CapacityParentNamespace.AUXILIARY) {
            GoalStep successor = plan.steps.peekFirst();
            boolean sameFamilyService = successor != null
                    && successor.kind() == GoalStep.Kind.MINING_SERVICE
                    && expectedFingerprint.equals(
                    OreDigTask.oreFingerprint(successor.ores()));
            if (!sameFamilyService) {
                plan.auxiliaryMiningCheckpoint.clear();
                plan.auxiliaryMiningContinuationFingerprint = "";
            }
        }
        return true;
    }

    /** Settles a completed service without discarding a still-planned ordinary branch cursor. */
    private static void retireClosedAuxiliaryMiningCheckpoint(ActivePlan plan) {
        if (plan.current == null || plan.current.kind() != GoalStep.Kind.MINING_SERVICE
                || plan.auxiliaryMiningCheckpoint.isEmpty()) {
            return;
        }
        String expectedFingerprint = OreDigTask.oreFingerprint(plan.current.ores());
        OreDigTask.inspectCheckpoint(plan.auxiliaryMiningCheckpoint, 0)
                .filter(metadata -> !metadata.batchOpen()
                        && expectedFingerprint.equals(
                        OreDigTask.oreFingerprint(metadata.ores())))
                .ifPresent(metadata -> {
                    if (hasFreshMiningSuccessor(
                            new ArrayList<>(plan.steps), metadata.ores())) {
                        plan.auxiliaryMiningContinuationFingerprint = expectedFingerprint;
                    } else {
                        plan.auxiliaryMiningCheckpoint.clear();
                        plan.auxiliaryMiningContinuationFingerprint = "";
                    }
                });
    }

    /**
     * A task-level cursor attests only the currently executing step.  Once that step commits, a
     * stale RETURN/DONE cursor must not survive into a later craft/smelt step and be replayed after
     * restart. Dedicated branch/transaction namespaces are intentionally retained separately.
     */
    private static void clearCompletedTaskCheckpoint(ActivePlan plan) {
        if (plan.current != null && plan.taskCheckpointKind == plan.current.kind()) {
            plan.taskCheckpoint.clear();
            plan.taskCheckpointKind = null;
        }
    }

    /**
     * A successful physical water expedition establishes a new work region. A closed ordinary
     * cursor has no pickup/break debt and may be retired; an open transaction or a long rare-ore
     * mission keeps its exact face and remains fail-closed.
     */
    private static void retireRelocatedOrdinaryMiningCheckpoint(
            AIPlayerEntity bot, ActivePlan plan) {
        Optional<OreDigTask.RestoreMetadata> metadata =
                OreDigTask.inspectCheckpoint(plan.miningCheckpoint);
        if (!shouldRetireRelocatedOrdinaryMiningCheckpoint(
                metadata, hasOreDigPhysicalLedger(plan.miningCheckpoint))) {
            return;
        }
        BlockPos retiredFace = metadata.orElseThrow().cursor().face();
        plan.miningCheckpoint.clear();
        BotLog.task(bot, "goal_mining_cursor_retired_after_water_relocation",
                "face", retiredFace.toShortString(),
                "ore_fingerprint", OreDigTask.oreFingerprint(
                        metadata.orElseThrow().ores()));
    }

    static boolean shouldRetireRelocatedOrdinaryMiningCheckpoint(
            Optional<OreDigTask.RestoreMetadata> metadata,
            boolean physicalLedgerOpen) {
        return metadata != null
                && metadata.isPresent()
                && !metadata.orElseThrow().batchOpen()
                && metadata.orElseThrow().rareMissionTarget() == 0
                && !physicalLedgerOpen;
    }

    private static boolean miningStepFeedsGoal(Goal goal, Set<Block> ores) {
        Set<Item> drops = io.github.zoyluo.aibot.action.HarvestCore.expectedDropsFor(ores);
        if (goal instanceof Goal.HaveItem haveItem) {
            return drops.contains(haveItem.item());
        }
        if (goal instanceof Goal.Stockpile stockpile) {
            return drops.contains(stockpile.item());
        }
        if (goal instanceof Goal.MineOre mineOre) {
            Set<Block> requested = OreScan.expandOreFamilies(mineOre.ores());
            return ores.stream().anyMatch(requested::contains);
        }
        if (goal instanceof Goal.Armor) {
            return drops.contains(Items.RAW_IRON);
        }
        if (goal instanceof Goal.HavePickaxeTier pickaxe) {
            return pickaxe.tier() >= io.github.zoyluo.aibot.mining.ToolTier.DIAMOND
                    ? drops.contains(Items.DIAMOND)
                    : pickaxe.tier() >= io.github.zoyluo.aibot.mining.ToolTier.IRON
                    && drops.contains(Items.RAW_IRON);
        }
        return false;
    }

    /** Pure policy boundary so prerequisite coal/iron cannot overwrite a valuable rare-ore branch. */
    static boolean shouldReplaceMiningCheckpoint(String previousFingerprint,
                                                 String currentFingerprint,
                                                 boolean feedsGoal) {
        if (currentFingerprint == null || currentFingerprint.isBlank()) {
            return false;
        }
        if (previousFingerprint == null || previousFingerprint.isBlank()
                || previousFingerprint.equals(currentFingerprint)
                || feedsGoal) {
            return true;
        }
        // Keep an established rare branch across ordinary prerequisite work, but otherwise the
        // latest physical OreDig face is authoritative (coal -> iron included).
        return !isRareOreFingerprint(previousFingerprint)
                || isRareOreFingerprint(currentFingerprint);
    }

    private static boolean isRareOreFingerprint(String fingerprint) {
        return fingerprint != null && (fingerprint.contains("diamond_ore")
                || fingerprint.contains("emerald_ore")
                || fingerprint.contains("ancient_debris"));
    }

    private static GoalSnapshotCollector.Context initialContext(AIPlayerEntity bot, Goal goal) {
        if (goal instanceof Goal.Stockpile) {
            net.minecraft.util.math.BlockPos base = io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE
                    .of(bot.getUuid())
                    .placeIn(bot.getServerWorld(), "base")
                    .orElse(bot.getBlockPos());
            return GoalSnapshotCollector.Context.at(base);
        }
        return GoalSnapshotCollector.Context.at(bot.getBlockPos());
    }

    private void finishActive(AIPlayerEntity bot,
                              ActivePlan plan,
                              GoalEvaluation evaluation,
                              String reason,
                              boolean cancelled,
                              boolean advanceQueue) {
        finishActive(bot, plan, evaluation, reason, cancelled, advanceQueue, null);
    }

    private void finishActive(AIPlayerEntity bot,
                              ActivePlan plan,
                              GoalEvaluation evaluation,
                              String reason,
                              boolean cancelled,
                              boolean advanceQueue,
                              GoalResult.Status forcedStatus) {
        if (!activePlans.remove(bot.getUuid(), plan)) {
            return;
        }
        GoalResult.Status status = forcedStatus == null
                ? GoalResult.classify(evaluation, cancelled) : forcedStatus;
        GoalResult result = new GoalResult(
                resultSequence.incrementAndGet(),
                plan.missionId,
                plan.goal,
                status,
                evaluation,
                reason,
                plan.startedTick,
                bot.getServer().getTicks(),
                plan.skippedSteps,
                plan.lastStructure);
        publishResult(bot, result);
        userGoal.remove(bot.getUuid());
        if (status == GoalResult.Status.FAILED || status == GoalResult.Status.PARTIAL) {
            lastGoalFailTick.put(bot.getUuid(), bot.getServer().getTicks());
        }
        if (advanceQueue) {
            advanceQueue(bot);
        }
    }

    private void recordImmediateResult(AIPlayerEntity bot,
                                       UUID missionId,
                                       Goal goal,
                                       int startedTick,
                                       GoalEvaluation evaluation,
                                       GoalResult.Status status,
                                       String reason) {
        recordImmediateResult(
                bot, missionId, goal, startedTick, evaluation, status, reason, List.of());
    }

    private void recordImmediateResult(AIPlayerEntity bot,
                                       UUID missionId,
                                       Goal goal,
                                       int startedTick,
                                       GoalEvaluation evaluation,
                                       GoalResult.Status status,
                                       String reason,
                                       List<GoalResult.SkippedStep> skippedSteps) {
        if (pocketRestorePreflights.contains(bot.getUuid())) {
            return;
        }
        publishResult(bot, new GoalResult(
                resultSequence.incrementAndGet(), missionId, goal, status, evaluation, reason,
                startedTick, bot.getServer().getTicks(),
                skippedSteps == null ? List.of() : List.copyOf(skippedSteps), null));
    }

    private void publishResult(AIPlayerEntity bot, GoalResult result) {
        lastResults.put(bot.getUuid(), result);
        BotLog.task(bot, "goal_result",
                "sequence", result.sequence(),
                "mission_id", result.missionId(),
                "goal", result.goal(),
                "status", result.status(),
                "matched", result.evaluation().matched(),
                "required", result.evaluation().required(),
                "reason", result.reason(),
                "skipped", result.skippedSteps().size(),
                "evidence", result.evaluation().evidence());
        if (result.status() == GoalResult.Status.COMPLETED) {
            io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.zoyluo.aibot.memory.EpisodeLog.Type.GOAL_DONE, bot.getBlockPos(), goalLabel(result.goal()));
        } else if (result.status() != GoalResult.Status.CANCELLED) {
            io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.zoyluo.aibot.memory.EpisodeLog.Type.GOAL_FAILED, bot.getBlockPos(), goalLabel(result.goal()));
        }
        String message = resultMessage(result.status(), result.evaluation(), result.reason());
        reportTerminal(bot, message, result.status());
        markDirty(bot);
    }

    private static void markDirty(AIPlayerEntity bot) {
        io.github.zoyluo.aibot.persist.BotPersistence.INSTANCE.markDirty(bot.getServer());
    }

    private static String resultMessage(GoalResult.Status status, GoalEvaluation evaluation, String reason) {
        return switch (status) {
            case COMPLETED -> "目标已验收完成。";
            case PARTIAL -> "目标只完成了一部分(" + evaluation.matched() + "/" + evaluation.required() + "):"
                    + String.join(",", evaluation.unmet());
            case FAILED -> "目标未通过最终验收:" + (evaluation.unmet().isEmpty() ? reason : String.join(",", evaluation.unmet()));
            case CANCELLED -> "目标已取消。";
        };
    }

    private static void reportTerminal(AIPlayerEntity bot, String text, GoalResult.Status status) {
        BotReporter.INSTANCE.onGoalResult(bot, status, text);
    }

    private static void report(AIPlayerEntity bot, String text) {
        BotReporter.INSTANCE.onGoalMessage(bot, text);
    }

    // 硬卡死类失败:原样重试只会再失败(挖不动/卡住/超时/够不到)。区别于"缺料/缺镐"这类重规划能补的。
    private static boolean isHardFailure(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.contains("no_progress")
                || reason.contains("dig_down_blocked")
                || reason.contains("stuck:")
                || reason.contains("timeout")
                || reason.contains("no_reachable");
    }

    // P1:目标失败时给出可执行的中文引导,避免大脑收到原始 reason 后用 move 乱走探索而遇险。
    private static String humanGoalFailure(String reason) {
        String r = reason == null ? "" : reason;
        if (r.contains("no_resource_after_explore")) {
            // EXPLORE 已定向走出去几片区域都找过(非"原地没找到"),如实区分,免得大脑再让 move 乱走重试。
            return "我已经走出去好几片区域找过了,还是没找到需要的资源,暂时无法继续。我会待在原地,不乱走也不空手挖。";
        }
        if (r.contains("no_resource_nearby") || r.contains("no_reachable") || r.contains("no_ore_found")) {
            return "我在较大范围内都没找到可用的树木/石头/矿石,暂时无法继续。我会待在原地,不乱走也不空手挖。";
        }
        if (r.startsWith("need_better_tool") || r.startsWith("need_pickaxe")) {
            return "我还缺合适的镐,正在自动准备;若准备不出来我会停下,不会空手硬挖。";
        }
        if (r.startsWith("descend_overshoot_unrecoverable")
                || r.startsWith("descend_landing_pose_drift")) {
            return "下矿落点偏离了已验证台阶,我已立即停止任务,不会在错误深度继续挖。";
        }
        return "目标失败:" + (r.isBlank() ? "步骤失败" : r);
    }

    private static final class ActivePlan {
        private UUID missionId;
        private final int startedTick;
        private final Goal goal;
        private final GoalPredicate predicate;
        private net.minecraft.util.math.BlockPos origin;
        private final Set<net.minecraft.util.math.BlockPos> boundContainers = new HashSet<>();
        private final ArrayDeque<GoalStep> steps;
        private final java.util.List<String> stepLabels; // 完整步骤描述(steps 会随执行 poll 空,这里留全量供面板任务链条展示)
        private final List<GoalResult.SkippedStep> skippedSteps = new ArrayList<>();
        private final List<SkippedTargetReceipt> skippedTargetReceipts = new ArrayList<>();
        private GoalStep current;
        private Task currentTask;
        private BlueprintSchema blueprint;
        private net.minecraft.util.math.BlockPos buildAnchor;
        private int buildPlaced;
        private int buildSkipped;
        private StructureReport lastStructure;
        private int totalSteps;
        private int replanCount;       // Phase A:语义=连续无进展 replan 数(有进展则清零)
        private int postconditionReplans;
        private int lastEvaluationMatched;
        private String lastRepairFingerprint = "";
        // Phase A 韧性·进度感知预算(断点恢复):
        private int completedSteps;    // 累计完成步数(单调增)
        private int lifetimeReplans;   // 终生 replan 数(永不重置,长稀有矿按原始批次数扩展)
        // 当前稀有矿批次的资源 epoch(0=首个,1=批内 retry,>=2=margin epoch)：replan 保留，
        // 只在该批 closed commit 后归零。
        private int rareResourceRetriesUsed;
        // 任务级 margin epoch 抽取账本(F2)：跨批次单调递增,批次 commit 不归零,
        // 只有全新 mission 才从 0 开始;上限 = MiningBudget.rareMissionEpochMargin(batchCount)。
        private int rareEpochMarginUsed;
        private int snapSteps;         // 上次 replan 时 completedSteps 快照
        private int snapTargetCount;   // 上次 replan 时目标产物库存计数
        private int snapX, snapY, snapZ; // 上次 replan 时 bot 坐标(横向位移/下潜=进展判据)
        private String snapDimension = "";
        private int snapHuntRawMeat;
        private int snapHuntVisitedSectors;
        /** Mission-scoped surface search facts shared by every HUNT task and replan. */
        private final HuntSearchCursor huntSearchCursor;
        /** Synthetic restore step that settles one durable PICKUP before any live replan. */
        private boolean huntPickupSettlement;
        private GoalStep.Kind taskCheckpointKind;
        private final Map<String, String> taskCheckpoint = new java.util.LinkedHashMap<>();
        /** Latest branch-mining cursor; survives intervening MINING_SERVICE checkpoints. */
        private final Map<String, String> miningCheckpoint = new java.util.LinkedHashMap<>();
        /** Ordinary prerequisite cursor temporarily suspended above a protected rare branch. */
        private final Map<String, String> auxiliaryMiningCheckpoint =
                new java.util.LinkedHashMap<>();
        /** Explicit owner of a dynamically inserted capacity service and its exact retry debit. */
        private CapacityParentNamespace capacityParentNamespace;
        /** Target-item delivery count at the most recently scheduled capacity service. */
        private int capacityParentDelivered = -1;
        /** Exact OreDig work face at the most recently scheduled capacity service. */
        private BlockPos capacityParentFace;
        /** Bounded dynamic services already consumed by this open ordinary OreDig batch. */
        private int capacityParentServicesUsed;
        /** Runtime owner bit: only the exact checkpoint selected from the capacity parent may
         * advance or settle that parent; same-family repair OreDig tasks remain independent. */
        private boolean currentCapacityParentRetry;
        /** Closed ordinary cursor retained across a successful replan until that family resumes. */
        private String auxiliaryMiningContinuationFingerprint = "";
        /** Original-target obsidian transaction; survives prerequisite water/tool repair tasks. */
        private final Map<String, String> obsidianCheckpoint = new java.util.LinkedHashMap<>();
        /** Bounded, non-evicting settled pocket facts retained for the life of this Mission. */
        private final java.util.LinkedHashMap<String, SettledServiceTombstone>
                settledServiceTombstones = new java.util.LinkedHashMap<>();

        private ActivePlan(UUID missionId,
                           int startedTick,
                           Goal goal,
                           GoalPredicate predicate,
                           GoalSnapshotCollector.Context context,
                           ArrayDeque<GoalStep> steps,
                           int totalSteps,
                           java.util.List<String> stepLabels,
                           HuntSearchCursor huntSearchCursor,
                           List<SkippedTargetReceipt> skippedTargetReceipts) {
            this.missionId = missionId;
            this.startedTick = startedTick;
            this.goal = goal;
            this.predicate = predicate;
            this.origin = context.origin();
            this.boundContainers.addAll(context.boundContainers());
            this.blueprint = context.blueprint();
            this.buildAnchor = context.buildAnchor();
            this.buildPlaced = context.buildPlaced();
            this.buildSkipped = context.buildSkipped();
            this.steps = steps;
            this.totalSteps = totalSteps;
            this.stepLabels = new ArrayList<>(stepLabels);
            this.huntSearchCursor = java.util.Objects.requireNonNull(
                    huntSearchCursor, "huntSearchCursor");
            this.skippedTargetReceipts.addAll(
                    java.util.Objects.requireNonNull(
                            skippedTargetReceipts, "skippedTargetReceipts"));
            this.skippedTargetReceipts.stream()
                    .map(SkippedTargetReceipt::asSkippedStep)
                    .forEach(this.skippedSteps::add);
        }

        private Map<String, String> takeTaskCheckpoint(GoalStep.Kind kind) {
            if (taskCheckpointKind != kind || taskCheckpoint.isEmpty()) {
                return Map.of();
            }
            Map<String, String> restored = Map.copyOf(taskCheckpoint);
            taskCheckpoint.clear();
            taskCheckpointKind = null;
            return restored;
        }

        /**
         * Water search restore is transactional: keep the prior durable cursor until the running
         * task publishes a trusted non-empty successor or completes. An invalid restore returns an
         * empty checkpoint, so consuming here would silently turn corruption/exhaustion into a
         * fresh expedition on the next plan.
         */
        private Map<String, String> peekTaskCheckpoint(GoalStep.Kind kind) {
            if (taskCheckpointKind != kind || taskCheckpoint.isEmpty()) {
                return Map.of();
            }
            return Map.copyOf(taskCheckpoint);
        }

        private Map<String, String> checkpointForMineOre(Set<Block> ores) {
            String expected = OreDigTask.oreFingerprint(ores);
            Map<String, String> current = takeTaskCheckpoint(GoalStep.Kind.MINE_ORE);
            if (expected.equals(current.get("ore_fingerprint"))) {
                return current;
            }
            if (expected.equals(auxiliaryMiningCheckpoint.get("ore_fingerprint"))) {
                Map<String, String> restored = Map.copyOf(auxiliaryMiningCheckpoint);
                if (expected.equals(auxiliaryMiningContinuationFingerprint)
                        && capacityParentNamespace == null) {
                    // Promote the suspended closed cursor into the newly assigned task atomically.
                    // Keeping both namespaces would make a restart ambiguous once MINE_ORE starts.
                    auxiliaryMiningCheckpoint.clear();
                    auxiliaryMiningContinuationFingerprint = "";
                }
                return restored;
            }
            if (expected.equals(miningCheckpoint.get("ore_fingerprint"))) {
                return Map.copyOf(miningCheckpoint);
            }
            return Map.of();
        }

        private boolean isCapacityParentRetry(GoalStep step,
                                              Map<String, String> selectedCheckpoint) {
            if (step == null || step.kind() != GoalStep.Kind.MINE_ORE
                    || capacityParentNamespace == null
                    || selectedCheckpoint == null || selectedCheckpoint.isEmpty()) {
                return false;
            }
            Map<String, String> parent = capacityParentNamespace
                    == CapacityParentNamespace.AUXILIARY
                    ? auxiliaryMiningCheckpoint : miningCheckpoint;
            Optional<OreDigTask.RestoreMetadata> metadata =
                    OreDigTask.inspectCheckpoint(parent, 0);
            return selectedCheckpoint.equals(parent)
                    && metadata.filter(value -> value.batchOpen()
                            && value.rareMissionTarget() == 0
                            && value.acceptsStepTarget(step.count())
                            && OreDigTask.oreFingerprint(value.ores()).equals(
                            OreDigTask.oreFingerprint(step.ores())))
                    .isPresent();
        }

        /** Read-only cursor handoff: service geometry may bind to the branch face, but cannot
         * consume, clear or replace the parent OreDig checkpoint needed by the next batch. */
        private MiningCursor miningCursorForService(Set<Block> ores) {
            String expected = OreDigTask.oreFingerprint(ores);
            int rareMissionTarget = rareMissionTargetForMiningStep(goal, ores);
            Optional<OreDigTask.RestoreMetadata> auxiliary = OreDigTask.inspectCheckpoint(
                            Map.copyOf(auxiliaryMiningCheckpoint), 0)
                    .filter(metadata -> expected.equals(
                            OreDigTask.oreFingerprint(metadata.ores())));
            if (auxiliary.isPresent()) {
                return auxiliary.orElseThrow().cursor();
            }
            return OreDigTask.inspectCheckpoint(
                            Map.copyOf(miningCheckpoint), rareMissionTarget)
                    .filter(metadata -> expected.equals(
                            OreDigTask.oreFingerprint(metadata.ores())))
                    .map(OreDigTask.RestoreMetadata::cursor)
                    .orElse(null);
        }

        private Map<String, String> checkpointForObsidian() {
            if (taskCheckpointKind == GoalStep.Kind.MAKE_OBSIDIAN
                    && !taskCheckpoint.isEmpty()) {
                return Map.copyOf(taskCheckpoint);
            }
            return Map.copyOf(obsidianCheckpoint);
        }
    }

    private record MiningServiceInvocation(MiningServiceTask.ServicePolicy policy,
                                           int target,
                                           int boundary) {
    }

    private record SettledServiceDescriptor(
            String oreFingerprint,
            MiningServiceTask.ServicePolicy policy,
            String missionId,
            int target,
            int boundary) {
        private static final String CODEC_VERSION = "1";

        private SettledServiceDescriptor {
            if (!validOreFingerprint(oreFingerprint) || policy == null
                    || missionId == null || missionId.isBlank()
                    || missionId.indexOf('|') >= 0
                    || missionId.chars().anyMatch(Character::isISOControl)
                    || !MiningServiceTask.validServiceDescriptor(
                    policy, target, boundary)) {
                throw new IllegalArgumentException("invalid_settled_service_descriptor");
            }
        }

        private static SettledServiceDescriptor fromMetadata(
                MiningServiceTask.RestoreMetadata metadata) {
            return new SettledServiceDescriptor(
                    OreDigTask.oreFingerprint(metadata.ores()), metadata.policy(),
                    metadata.serviceMissionId(), metadata.serviceTargetCount(),
                    metadata.serviceBoundary());
        }

        private String encode() {
            return String.join("|",
                    CODEC_VERSION,
                    policy.profile().name(),
                    missionId,
                    String.valueOf(target),
                    String.valueOf(boundary),
                    String.valueOf(policy.targetToolUsableDurability()),
                    String.valueOf(policy.channelToolUsableDurability()),
                    String.valueOf(policy.foodMinUnits()),
                    String.valueOf(policy.torchMinCount()),
                    String.valueOf(policy.freeSlotsMin()),
                    String.valueOf(policy.emergencyBlocksReserved()),
                    String.valueOf(policy.futureStickReserve()),
                    String.valueOf(policy.craftingTableRequired()),
                    oreFingerprint);
        }

        private static Optional<SettledServiceDescriptor> decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return Optional.empty();
            }
            try {
                String[] parts = encoded.split("\\|", -1);
                if (parts.length != 14 || !CODEC_VERSION.equals(parts[0])
                        || !"true".equals(parts[12]) && !"false".equals(parts[12])) {
                    return Optional.empty();
                }
                MiningServiceTask.ServicePolicy policy =
                        new MiningServiceTask.ServicePolicy(
                                MiningServiceTask.ServiceProfile.valueOf(parts[1]),
                                Integer.parseInt(parts[5]),
                                Integer.parseInt(parts[6]),
                                Integer.parseInt(parts[7]),
                                Integer.parseInt(parts[8]),
                                Integer.parseInt(parts[9]),
                                Integer.parseInt(parts[10]),
                                Integer.parseInt(parts[11]),
                                Boolean.parseBoolean(parts[12]));
                SettledServiceDescriptor descriptor = new SettledServiceDescriptor(
                        parts[13], policy, parts[2], Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]));
                return descriptor.encode().equals(encoded)
                        ? Optional.of(descriptor) : Optional.empty();
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }

        private static boolean validOreFingerprint(String fingerprint) {
            if (fingerprint == null || fingerprint.isBlank()) {
                return false;
            }
            Set<Block> blocks = new HashSet<>();
            for (String encoded : fingerprint.split(",", -1)) {
                Identifier id = Identifier.tryParse(encoded);
                Block block = id == null
                        ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
                if (block == null || block == Blocks.AIR || !blocks.add(block)) {
                    return false;
                }
            }
            return OreDigTask.oreFingerprint(blocks).equals(fingerprint);
        }
    }

    private record SettledServiceAuthority(
            SettledServiceDescriptor descriptor,
            String dimension,
            MiningServiceTask.DisposalGeometry geometry) {
        private SettledServiceAuthority {
            Identifier dimensionId = dimension == null
                    ? null : Identifier.tryParse(dimension);
            if (descriptor == null || dimensionId == null
                    || !dimensionId.toString().equals(dimension) || geometry == null) {
                throw new IllegalArgumentException("invalid_settled_service_authority");
            }
        }

        private String key() {
            return descriptor.encode() + "@" + dimension + "@"
                    + encodePos(geometry.workFace()) + "@"
                    + geometry.pocketAxis().name();
        }
    }

    private record SettledServiceTombstone(
            SettledServiceAuthority authority,
            String failureReason) {
        private SettledServiceTombstone {
            if (authority == null
                    || !MiningServiceTask.validTerminalFailureReason(failureReason)) {
                throw new IllegalArgumentException("invalid_settled_service_tombstone");
            }
        }

        private String key() {
            return authority.key();
        }

        private MiningServiceTask.DisposalGeometry geometry() {
            return authority.geometry();
        }

        private boolean sameGeometry(SettledServiceAuthority other) {
            return other != null
                    && authority.dimension().equals(other.dimension())
                    && authority.geometry().equals(other.geometry());
        }

        private boolean sameGeometry(SettledServiceTombstone other) {
            return other != null && sameGeometry(other.authority());
        }

        private MiningServiceTask.DisposalReplayGuard replayGuard() {
            return new MiningServiceTask.DisposalReplayGuard(
                    authority.dimension(), authority.geometry(), failureReason);
        }

        private Map<String, String> encode() {
            MiningServiceTask.DisposalGeometry geometry = authority.geometry();
            return Map.of(
                    "schema", "1",
                    "descriptor", authority.descriptor().encode(),
                    "dimension", authority.dimension(),
                    "work_face", encodePos(geometry.workFace()),
                    "pocket_axis", geometry.pocketAxis().name(),
                    "pocket_a", encodePos(geometry.firstEntry()),
                    "pocket_b", encodePos(geometry.secondEntry()),
                    "failure", failureReason);
        }

        private static Optional<SettledServiceTombstone> decode(
                Map<String, String> values) {
            if (values == null || !values.keySet().equals(
                    SETTLED_SERVICE_ENTRY_KEYS)
                    || !"1".equals(values.get("schema"))) {
                return Optional.empty();
            }
            try {
                Optional<SettledServiceDescriptor> descriptor =
                        SettledServiceDescriptor.decode(values.get("descriptor"));
                Optional<BlockPos> workFace = decodePos(values.get("work_face"));
                Optional<BlockPos> pocketA = decodePos(values.get("pocket_a"));
                Optional<BlockPos> pocketB = decodePos(values.get("pocket_b"));
                Direction.Axis axis = Direction.Axis.valueOf(values.get("pocket_axis"));
                if (descriptor.isEmpty() || workFace.isEmpty()
                        || pocketA.isEmpty() || pocketB.isEmpty()
                        || !encodePos(workFace.orElseThrow()).equals(
                        values.get("work_face"))
                        || !encodePos(pocketA.orElseThrow()).equals(
                        values.get("pocket_a"))
                        || !encodePos(pocketB.orElseThrow()).equals(
                        values.get("pocket_b"))) {
                    return Optional.empty();
                }
                MiningServiceTask.DisposalGeometry geometry =
                        new MiningServiceTask.DisposalGeometry(
                                workFace.orElseThrow(), axis,
                                pocketA.orElseThrow(), pocketB.orElseThrow());
                SettledServiceAuthority authority = new SettledServiceAuthority(
                        descriptor.orElseThrow(), values.get("dimension"), geometry);
                return Optional.of(new SettledServiceTombstone(
                        authority, values.get("failure")));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }
    }

    private static Optional<List<SettledServiceTombstone>>
    decodeSettledServiceTombstones(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Optional.of(List.of());
        }
        try {
            String rawCount = values.getOrDefault("count", "");
            int count = Integer.parseInt(rawCount);
            if (count < 1 || count > MAX_SETTLED_SERVICE_TOMBSTONES
                    || !String.valueOf(count).equals(rawCount)) {
                return Optional.empty();
            }
            Set<String> expected = new HashSet<>();
            expected.add("count");
            for (int index = 0; index < count; index++) {
                for (String key : SETTLED_SERVICE_ENTRY_KEYS) {
                    expected.add(index + "." + key);
                }
            }
            if (!values.keySet().equals(expected)) {
                return Optional.empty();
            }
            List<SettledServiceTombstone> decoded = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            Set<String> geometries = new HashSet<>();
            String previousIdentity = null;
            for (int index = 0; index < count; index++) {
                Map<String, String> entry = new java.util.LinkedHashMap<>();
                String prefix = index + ".";
                for (String key : SETTLED_SERVICE_ENTRY_KEYS) {
                    entry.put(key, values.get(prefix + key));
                }
                SettledServiceTombstone tombstone =
                        SettledServiceTombstone.decode(entry).orElseThrow();
                String geometryKey = tombstone.authority().dimension() + "@"
                        + encodePos(tombstone.geometry().workFace()) + "@"
                        + tombstone.geometry().pocketAxis().name();
                if (!identities.add(tombstone.key()) || !geometries.add(geometryKey)
                        || previousIdentity != null
                        && previousIdentity.compareTo(tombstone.key()) >= 0) {
                    return Optional.empty();
                }
                previousIdentity = tombstone.key();
                decoded.add(tombstone);
            }
            return Optional.of(List.copyOf(decoded));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<SettledServiceAuthority> persistedServiceAuthority(
            MiningServiceTask.RestoreMetadata metadata) {
        Identifier dimension = metadata == null ? null
                : Identifier.tryParse(metadata.serviceDimension());
        if (metadata == null || metadata.miningCursor() == null
                || metadata.workFace() == null
                || !metadata.workFace().equals(metadata.miningCursor().face())
                || dimension == null
                || !dimension.toString().equals(metadata.serviceDimension())) {
            return Optional.empty();
        }
        return MiningServiceTask.DisposalGeometry.fromCursor(metadata.miningCursor())
                .map(geometry -> new SettledServiceAuthority(
                        SettledServiceDescriptor.fromMetadata(metadata),
                        metadata.serviceDimension(),
                        geometry));
    }

    private static Optional<SettledServiceAuthority> liveServiceAuthority(
            AIPlayerEntity bot, MiningServiceTask.RestoreMetadata metadata) {
        if (bot == null || metadata == null
                || !bot.getServerWorld().getRegistryKey().getValue().toString()
                .equals(metadata.serviceDimension())) {
            return Optional.empty();
        }
        return persistedServiceAuthority(metadata);
    }

    private static Optional<SettledServiceTombstone> matchingSettledServiceGuard(
            AIPlayerEntity bot, ActivePlan plan, String reason) {
        if (bot == null || plan == null || plan.current == null
                || plan.current.kind() != GoalStep.Kind.MINING_SERVICE
                || !MiningServiceTask.validTerminalFailureReason(reason)) {
            return Optional.empty();
        }
        Optional<MiningServiceTask.RestoreMetadata> metadata =
                MiningServiceTask.inspectCheckpoint(plan.taskCheckpoint);
        Optional<SettledServiceAuthority> authority = metadata
                .flatMap(value -> liveServiceAuthority(bot, value));
        return authority.flatMap(current -> plan.settledServiceTombstones.values()
                .stream()
                .filter(settled -> settled.failureReason().equals(reason)
                        && settled.sameGeometry(current))
                .findFirst());
    }

    private record ReplanSnapshot(int steps,
                                  int targetCount,
                                  int x,
                                  int y,
                                  int z,
                                  String dimension,
                                  int huntRawMeat,
                                  int huntVisitedSectors) {
    }

    record SkippedTargetReceipt(GoalStep step, String reason) {
        SkippedTargetReceipt {
            java.util.Objects.requireNonNull(step, "step");
            reason = reason == null ? "" : reason;
            if (reason.getBytes(StandardCharsets.UTF_8).length
                    > MAX_SKIPPED_TARGET_TEXT_BYTES
                    || step.tag() != null && step.tag().getBytes(StandardCharsets.UTF_8).length
                    > MAX_SKIPPED_TARGET_TEXT_BYTES) {
                throw new IllegalArgumentException("skipped target receipt text too large");
            }
        }

        GoalResult.SkippedStep asSkippedStep() {
            return new GoalResult.SkippedStep(step.describe(), reason);
        }
    }

    record PostconditionRepairCheckpoint(boolean persisted,
                                         int replans,
                                         int lastMatched,
                                         String fingerprint) {
        private static PostconditionRepairCheckpoint legacy() {
            return new PostconditionRepairCheckpoint(false, 0, 0, "");
        }
    }

    private record RestoreSeed(UUID missionId,
                               GoalSnapshotCollector.Context context,
                               int completedSteps,
                               int lifetimeReplans,
                               int rareResourceRetriesUsed,
                               int rareEpochMarginUsed,
                               int replanCount,
                               Optional<ReplanSnapshot> replanSnapshot,
                               HuntSearchCursor huntSearchCursor,
                               List<SkippedTargetReceipt> skippedTargetReceipts,
                               String validationFailure,
                               PostconditionRepairCheckpoint postconditionRepair,
                               int startedTick,
                               GoalStep.Kind taskCheckpointKind,
                               Map<String, String> taskCheckpoint,
                               Map<String, String> miningCheckpoint,
                               Map<String, String> auxiliaryMiningCheckpoint,
                               String capacityParentNamespace,
                               int capacityParentDelivered,
                               String capacityParentFace,
                               int capacityParentServicesUsed,
                               String auxiliaryMiningContinuationFingerprint,
                               Map<String, String> obsidianCheckpoint,
                               Map<String, String> settledServiceTombstone) {
    }

    private enum CapacityParentNamespace {
        MINING("mining"),
        AUXILIARY("auxiliary");

        private final String persistedName;

        CapacityParentNamespace(String persistedName) {
            this.persistedName = persistedName;
        }

        private static Optional<CapacityParentNamespace> decode(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return java.util.Arrays.stream(values())
                    .filter(candidate -> candidate.persistedName.equals(value))
                    .findFirst();
        }
    }
}
