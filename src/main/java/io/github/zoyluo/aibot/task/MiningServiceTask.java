package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.ContainerAction;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.mining.MiningBudget;
import io.github.zoyluo.aibot.mining.MiningCursor;
import io.github.zoyluo.aibot.mining.MiningFoodReserve;
import io.github.zoyluo.aibot.mining.MiningMissionBudget;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.mode.FakePlayerMotion;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Service checkpoint between mining batches: remember the work face, unload by-products, replenish
 * a missing tool/food, and return to the exact face. Target drops never leave the inventory, so the
 * parent Goal's inventory postcondition remains exact. Surface acquisition belongs to Planner
 * readiness; this underground service only consumes backpack/depot supplies or performs an exact
 * local craft, and never starts Hunt/Gather exploration from the mine bottom.
 */
public final class MiningServiceTask extends AbstractTask implements CheckpointableTask {
    private static final int CHECKPOINT_SCHEMA = 8;
    private static final int RARE_DESCENT_KIT_CHECKPOINT_SCHEMA = 9;
    private static final int RESOURCE_HORIZON_CHECKPOINT_SCHEMA = 5;
    private static final int IDENTITY_V1_CHECKPOINT_SCHEMA = 4;
    private static final int POLICY_V1_CHECKPOINT_SCHEMA = 3;
    private static final int LEGACY_CHECKPOINT_SCHEMA = 2;
    private static final double REACH_SQUARED = 20.25D;
    private static final int MAX_ELAPSED = MiningMissionBudget.SERVICE_HARD_WINDOW_TICKS;
    private static final int NO_PROGRESS_LIMIT = 600;
    private static final int EMERGENCY_BLOCK_RESERVE = MiningBudget.EMERGENCY_STONE_LIKE;
    private static final int MIN_FREE_MAIN_SLOTS = 4;
    private static final int STONE_PICKAXE_USABLE_DURABILITY = 130;
    private static final int STONE_PICKAXE_HEAD_COST = 3;
    private static final int STONE_PICKAXE_STICK_COST = 2;
    private static final int ORDINARY_CHANNEL_CONTINUATION_SUPPORT = 1;
    private static final int OBSIDIAN_SERVICE_INTERVAL = 8;
    private static final int POCKET_SETTLE_LIMIT = 100;
    private static final int POCKET_BASELINE_STABLE_TICKS = 20;
    private static final int POCKET_PRESEAL_STABLE_TICKS = 20;
    private static final int MAX_SURVIVAL_STACK_COUNT = 64;
    /**
     * One service tick can commit at most one inventory stack to the pocket. The durable service
     * hard window therefore provides a strict single-task bound for checkpoint counts and tracked
     * entity identities, even when opening spoil repeatedly refills an inventory slot.
     */
    static final int POCKET_CHECKPOINT_MAX_STACKS = MAX_ELAPSED;
    static final int POCKET_CHECKPOINT_MAX_ITEM_COUNT =
            POCKET_CHECKPOINT_MAX_STACKS * MAX_SURVIVAL_STACK_COUNT;
    private static final String POCKET_ORE_RETRY_PREFIX = "retry_disposal_ore:";
    private static final String POCKET_ORE_FAILURE_PREFIX =
            "mining_service_disposal_ore_preserved:";
    private static final String STANDALONE_MISSION_ID = "standalone";
    private static final Direction[] DEPOT_APPROACH_DIRECTIONS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Item[] DISPOSABLE_JUNK = {
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
            Items.DIRT, Items.GRAVEL, Items.SAND,
            Items.DIORITE, Items.ANDESITE, Items.GRANITE, Items.TUFF};
    private static final Item[] DISPOSAL_SEAL_BLOCKS = {
            Items.DIRT, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.GRASS_BLOCK,
            Items.PODZOL, Items.MYCELIUM, Items.TUFF, Items.ANDESITE,
            Items.DIORITE, Items.GRANITE, Items.NETHERRACK,
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE};

    private enum Phase {
        PREPARE,
        OPEN_MISSION_DEPOT_ALCOVE,
        PLACE_MISSION_DEPOT,
        VERIFY_MISSION_DEPOT,
        TO_DEPOT,
        DEPOSIT,
        OPEN_DISPOSAL_POCKET,
        CAPTURE_DISPOSAL_BASELINE,
        DROP_DISPOSABLE,
        SETTLE_DISPOSABLE,
        RETURN_TO_DISPOSAL_FACE,
        SEAL_DISPOSAL_POCKET,
        SUPPLIES,
        BLOCKS,
        TOOL,
        FOOD,
        RETURN_TO_FACE,
        DONE
    }

    private final Set<net.minecraft.block.Block> targetOres;
    private final Set<Item> targetDrops;
    private final Item requiredPickaxe;
    private final ServiceCheckpoint restoredCheckpoint;
    private final boolean invalidCheckpoint;
    private final ServicePolicy policy;
    private final String serviceMissionId;
    private final int serviceTargetCount;
    private final int serviceBoundary;
    private final MiningCursor miningCursor;
    private final java.util.List<DisposalReplayGuard> disposalReplayGuards;
    /** Dimension-bound physical authority; first bound onStart, never inferred on restore. */
    private String serviceDimension = "";
    private final BlockMiner disposalMiner = new BlockMiner();
    private final BlockMiner missionDepotMiner = new BlockMiner();
    private Phase phase = Phase.PREPARE;
    private BlockPos workFace;
    private BlockPos depot;
    private Task child;
    private Item childToolItem = Items.AIR;
    private String note = "";
    private int depotApproachIndex;
    private int budgetOffset;
    private int lastProgressBudget;
    private BlockPos lastProgressPos;
    private Phase lastProgressPhase = Phase.PREPARE;
    private BlockPos pocketEntry;
    private BlockPos pocketSink;
    private Direction pocketDirection;
    private final java.util.LinkedHashSet<UUID> pocketEntityIds = new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashMap<UUID, PocketLineage> pocketLineage =
            new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<Item, Integer> pocketDropLedger =
            new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<Item, Integer> pocketBaseline =
            new java.util.LinkedHashMap<>();
    private boolean pocketDropCommitted;
    private boolean pocketLedgerVerified;
    private int pocketPhaseStartedBudget;
    private String pocketTerminalFailure = "";
    /** Durable receipt for a physically settled failure whose pocket identity was retired. */
    private String terminalFailureReceipt = "";
    private int pocketBaselineStableTicks;
    private int pocketPresealStableTicks;
    private int pocketClearIndex;
    private Direction missionDepotDirection;
    private String missionDepotDimension = "";
    private int missionDepotClearIndex;
    private boolean missionDepotPlacementCommitted;
    private boolean missionDepotRetirementCompleted;

    public MiningServiceTask(Set<net.minecraft.block.Block> targetOres) {
        this(targetOres, Map.of(), ServicePolicy.defaultOre(false), 0);
    }

    public MiningServiceTask(Set<net.minecraft.block.Block> targetOres,
                             Map<String, String> checkpoint) {
        this(targetOres, checkpoint, ServicePolicy.defaultOre(false), 0);
    }

    public MiningServiceTask(Set<net.minecraft.block.Block> targetOres,
                             Map<String, String> checkpoint,
                             boolean maintainTunnelingTools) {
        this(targetOres, checkpoint, ServicePolicy.defaultOre(maintainTunnelingTools), 0);
    }

    public MiningServiceTask(Set<net.minecraft.block.Block> targetOres,
                             Map<String, String> checkpoint,
                             ServicePolicy expectedPolicy) {
        this(targetOres, checkpoint, expectedPolicy, 0);
    }

    public MiningServiceTask(Set<net.minecraft.block.Block> targetOres,
                             Map<String, String> checkpoint,
                             ServicePolicy expectedPolicy,
                             int expectedServiceBoundary) {
        this(targetOres, checkpoint, expectedPolicy, expectedServiceBoundary,
                STANDALONE_MISSION_ID,
                inferredServiceTarget(expectedPolicy, expectedServiceBoundary));
    }

    public MiningServiceTask(Set<net.minecraft.block.Block> targetOres,
                             Map<String, String> checkpoint,
                             ServicePolicy expectedPolicy,
                             int expectedServiceBoundary,
                             String expectedMissionId,
                             int expectedTargetCount) {
        this(targetOres, checkpoint, expectedPolicy, expectedServiceBoundary,
                expectedMissionId, expectedTargetCount, null);
    }

    public MiningServiceTask(Set<net.minecraft.block.Block> targetOres,
                             Map<String, String> checkpoint,
                             ServicePolicy expectedPolicy,
                             int expectedServiceBoundary,
                             String expectedMissionId,
                             int expectedTargetCount,
                             MiningCursor expectedMiningCursor) {
        this(targetOres, checkpoint, expectedPolicy, expectedServiceBoundary,
                expectedMissionId, expectedTargetCount, expectedMiningCursor, java.util.List.of());
    }

    public MiningServiceTask(Set<net.minecraft.block.Block> targetOres,
                             Map<String, String> checkpoint,
                             ServicePolicy expectedPolicy,
                             int expectedServiceBoundary,
                             String expectedMissionId,
                             int expectedTargetCount,
                             MiningCursor expectedMiningCursor,
                             java.util.List<DisposalReplayGuard> disposalReplayGuards) {
        this.targetOres = targetOres == null || targetOres.isEmpty()
                ? OreScan.COMMON_ORES : OreScan.expandOreFamilies(targetOres);
        this.targetDrops = HarvestCore.expectedDropsFor(this.targetOres);
        this.requiredPickaxe = ToolTier.requiredPickaxeItem(this.targetOres);
        ServicePolicy requestedPolicy = expectedPolicy == null
                ? ServicePolicy.defaultOre(false) : expectedPolicy;
        String requestedMissionId = expectedMissionId == null || expectedMissionId.isBlank()
                ? STANDALONE_MISSION_ID : expectedMissionId;
        Optional<ServiceCheckpoint> decoded = ServiceCheckpoint.decode(
                checkpoint, this.targetOres, requestedPolicy, expectedServiceBoundary,
                requestedMissionId, expectedTargetCount, expectedMiningCursor);
        this.restoredCheckpoint = decoded.orElse(null);
        this.invalidCheckpoint = checkpoint != null && !checkpoint.isEmpty() && decoded.isEmpty();
        this.policy = restoredCheckpoint == null ? requestedPolicy : restoredCheckpoint.policy();
        this.serviceMissionId = restoredCheckpoint == null
                ? requestedMissionId : restoredCheckpoint.serviceMissionId();
        this.serviceTargetCount = restoredCheckpoint == null
                ? expectedTargetCount : restoredCheckpoint.serviceTargetCount();
        this.serviceBoundary = restoredCheckpoint == null
                ? expectedServiceBoundary : restoredCheckpoint.serviceBoundary();
        this.miningCursor = expectedMiningCursor != null
                ? expectedMiningCursor
                : restoredCheckpoint == null ? null : restoredCheckpoint.miningCursor();
        this.disposalReplayGuards = disposalReplayGuards == null
                ? java.util.List.of() : java.util.List.copyOf(disposalReplayGuards);
        if (!validIdentity(this.policy, this.serviceMissionId,
                this.serviceTargetCount, this.serviceBoundary)) {
            throw new IllegalArgumentException("invalid_mining_service_identity:"
                    + this.policy.profile() + ":" + this.serviceMissionId + ":"
                    + this.serviceTargetCount + ":" + this.serviceBoundary);
        }
    }

    /** Exact lateral disposal pair derived from the same cursor rule used by pocket selection. */
    public record DisposalGeometry(BlockPos workFace,
                                   Direction.Axis pocketAxis,
                                   BlockPos firstEntry,
                                   BlockPos secondEntry) {
        public DisposalGeometry {
            if (workFace == null || pocketAxis == null
                    || !pocketAxis.isHorizontal()
                    || firstEntry == null || secondEntry == null) {
                throw new IllegalArgumentException("invalid_disposal_geometry");
            }
            workFace = workFace.toImmutable();
            firstEntry = firstEntry.toImmutable();
            secondEntry = secondEntry.toImmutable();
            BlockPos expectedFirst = pocketAxis == Direction.Axis.X
                    ? workFace.west() : workFace.north();
            BlockPos expectedSecond = pocketAxis == Direction.Axis.X
                    ? workFace.east() : workFace.south();
            if (!firstEntry.equals(expectedFirst) || !secondEntry.equals(expectedSecond)) {
                throw new IllegalArgumentException("inconsistent_disposal_geometry");
            }
        }

        public static Optional<DisposalGeometry> fromCursor(MiningCursor cursor) {
            if (cursor == null || cursor.face() == null) {
                return Optional.empty();
            }
            Direction forward = cursorForward(cursor);
            Direction.Axis axis = forward.getAxis() == Direction.Axis.X
                    ? Direction.Axis.Z : Direction.Axis.X;
            BlockPos face = cursor.face();
            return Optional.of(new DisposalGeometry(
                    face, axis,
                    axis == Direction.Axis.X ? face.west() : face.north(),
                    axis == Direction.Axis.X ? face.east() : face.south()));
        }
    }

    public record DisposalReplayGuard(String dimension,
                                      DisposalGeometry geometry,
                                      String failureReason) {
        public DisposalReplayGuard {
            if (dimension == null || dimension.isBlank() || geometry == null
                    || !validTerminalFailureReason(failureReason)) {
                throw new IllegalArgumentException("invalid_disposal_replay_guard");
            }
        }

        private boolean matches(AIPlayerEntity bot, DisposalGeometry candidate) {
            return candidate != null && geometry.equals(candidate)
                    && dimension.equals(currentDimension(bot));
        }
    }

    public enum ServiceProfile {
        ORE_BATCH,
        RARE_DESCENT_KIT,
        RARE_ORE_BATCH,
        OBSIDIAN_PREFLIGHT,
        OBSIDIAN_8
    }

    /**
     * Immutable resource contract for one service boundary. Durability values are usable block
     * breaks: every damageable tool keeps its last point in reserve, matching ToolTier's
     * nearly-broken boundary.
     */
    public record ServicePolicy(ServiceProfile profile,
                                int targetToolUsableDurability,
                                int channelToolUsableDurability,
                                int foodMinUnits,
                                int torchMinCount,
                                int freeSlotsMin,
                                int emergencyBlocksReserved,
                                int futureStickReserve,
                                boolean craftingTableRequired) {
        public ServicePolicy {
            if (profile == null || targetToolUsableDurability < 0
                    || channelToolUsableDurability < 0 || foodMinUnits < 0
                    || torchMinCount < 0
                    || freeSlotsMin < 0 || emergencyBlocksReserved < 0
                    || futureStickReserve < 0) {
                throw new IllegalArgumentException("invalid_mining_service_policy");
            }
            boolean valid = switch (profile) {
                case ORE_BATCH -> targetToolUsableDurability == 1
                        && (channelToolUsableDurability == 0
                        || channelToolUsableDurability
                        == MiningBudget.TUNNELING_SERVICE_TARGET
                        * STONE_PICKAXE_USABLE_DURABILITY)
                        && foodMinUnits == MiningFoodReserve.MIN_DEEP_MINE_UNITS
                        && torchMinCount == 0
                        && freeSlotsMin == MIN_FREE_MAIN_SLOTS
                        // A terminal parent hand-off may bind a larger already-provisioned stone
                        // horizon. It never invents or refills that reserve; disposal and the final
                        // attestation merely prove it was not consumed as mining spoil.
                        && emergencyBlocksReserved >= EMERGENCY_BLOCK_RESERVE
                        && futureStickReserve == 0
                        && !craftingTableRequired;
                case RARE_ORE_BATCH -> targetToolUsableDurability >= 1
                        && targetToolUsableDurability <= 8
                        && channelToolUsableDurability
                        == MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                        * STONE_PICKAXE_USABLE_DURABILITY
                        && torchMinCount >= MiningBudget.RARE_BATCH_TORCH_LIMIT
                        && torchMinCount % MiningBudget.RARE_BATCH_TORCH_LIMIT == 0
                        && freeSlotsMin == MIN_FREE_MAIN_SLOTS
                        && (emergencyBlocksReserved
                        == MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE
                        || emergencyBlocksReserved == EMERGENCY_BLOCK_RESERVE)
                        && foodMinUnits == MiningBudget.rareServiceFoodMinimum(
                        emergencyBlocksReserved == MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE
                                ? 0 : 1)
                        && futureStickReserve >= 0
                        && futureStickReserve % (MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                        * STONE_PICKAXE_STICK_COST) == 0
                        && futureStickReserve == Math.max(0,
                        torchMinCount / MiningBudget.RARE_BATCH_TORCH_LIMIT - 1)
                        * MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                        * STONE_PICKAXE_STICK_COST
                        && craftingTableRequired;
                case RARE_DESCENT_KIT -> targetToolUsableDurability == 8
                        && channelToolUsableDurability == 5 * STONE_PICKAXE_USABLE_DURABILITY
                        && foodMinUnits == MiningBudget.RARE_BOOTSTRAP_FOOD
                        && torchMinCount == MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES
                        && freeSlotsMin == MIN_FREE_MAIN_SLOTS
                        && emergencyBlocksReserved == MiningBudget.RARE_BOOTSTRAP_STONE_LIKE
                        && futureStickReserve
                        == MiningBudget.DIAMOND_STACK_CHANNEL_REPAIR_STICKS
                        && craftingTableRequired;
                case OBSIDIAN_PREFLIGHT, OBSIDIAN_8 -> targetToolUsableDurability >= 1
                        && channelToolUsableDurability
                        == MiningBudget.TUNNELING_SERVICE_TARGET
                        * STONE_PICKAXE_USABLE_DURABILITY
                        && foodMinUnits == MiningFoodReserve.MIN_DEEP_MINE_UNITS
                        && torchMinCount == 0
                        && freeSlotsMin == MIN_FREE_MAIN_SLOTS
                        && futureStickReserve == futureSticksForRemainingTarget(
                        targetToolUsableDurability)
                        && futureStickReserve % (MiningBudget.TUNNELING_SERVICE_TARGET
                        * STONE_PICKAXE_STICK_COST) == 0
                        && emergencyBlocksReserved == stoneReserveFor(futureStickReserve)
                        && craftingTableRequired;
            };
            if (!valid) {
                throw new IllegalArgumentException("invalid_mining_service_policy:" + profile);
            }
        }

        public static ServicePolicy defaultOre(boolean maintainTunnelingTools) {
            return new ServicePolicy(
                    ServiceProfile.ORE_BATCH,
                    1,
                    maintainTunnelingTools
                            ? MiningBudget.TUNNELING_SERVICE_TARGET
                            * STONE_PICKAXE_USABLE_DURABILITY : 0,
                    MiningFoodReserve.MIN_DEEP_MINE_UNITS,
                    0,
                    MIN_FREE_MAIN_SLOTS,
                    EMERGENCY_BLOCK_RESERVE,
                    0,
                    false);
        }

        public static ServicePolicy capacityHandoff(int protectedStoneLike) {
            return new ServicePolicy(
                    ServiceProfile.ORE_BATCH,
                    1,
                    0,
                    MiningFoodReserve.MIN_DEEP_MINE_UNITS,
                    0,
                    MIN_FREE_MAIN_SLOTS,
                    Math.max(EMERGENCY_BLOCK_RESERVE, protectedStoneLike),
                    0,
                    false);
        }

        public static ServicePolicy rareOreBatch(int targetCount, int completedBoundary) {
            return rareOreBatch(targetCount, completedBoundary, 0);
        }

        public static ServicePolicy rareDescentKit(int targetCount) {
            if (targetCount != 64) {
                throw new IllegalArgumentException(
                        "invalid_rare_descent_kit_target:" + targetCount);
            }
            return new ServicePolicy(
                    ServiceProfile.RARE_DESCENT_KIT,
                    8,
                    5 * STONE_PICKAXE_USABLE_DURABILITY,
                    MiningBudget.RARE_BOOTSTRAP_FOOD,
                    MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES,
                    MIN_FREE_MAIN_SLOTS,
                    MiningBudget.RARE_BOOTSTRAP_STONE_LIKE,
                    MiningBudget.DIAMOND_STACK_CHANNEL_REPAIR_STICKS,
                    true);
        }

        public static ServicePolicy rareOreBatch(int targetCount,
                                                 int completedBoundary,
                                                 int resourceRetriesUsed) {
            if (targetCount < MiningBudget.EXPEDITION_THRESHOLD
                    || completedBoundary < 0 || completedBoundary >= targetCount
                    || resourceRetriesUsed < 0
                    || resourceRetriesUsed
                    > MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH) {
                throw new IllegalArgumentException("invalid_rare_ore_service_boundary:target="
                        + targetCount + ":boundary=" + completedBoundary
                        + ":resource_retries=" + resourceRetriesUsed);
            }
            int remainingBatches = remainingRareBatches(targetCount, completedBoundary);
            int remainingResourceEpochs = remainingBatches
                    * MiningBudget.RARE_RESOURCE_EPOCHS_PER_BATCH - resourceRetriesUsed;
            int sticksPerRepair = MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                    * STONE_PICKAXE_STICK_COST;
            return new ServicePolicy(
                    ServiceProfile.RARE_ORE_BATCH,
                    Math.min(8, targetCount - completedBoundary),
                    MiningBudget.RARE_TUNNELING_SERVICE_TARGET
                            * STONE_PICKAXE_USABLE_DURABILITY,
                    MiningBudget.rareServiceFoodMinimum(resourceRetriesUsed),
                    remainingResourceEpochs * MiningBudget.RARE_BATCH_TORCH_LIMIT,
                    MIN_FREE_MAIN_SLOTS,
                    resourceRetriesUsed > 0
                            ? EMERGENCY_BLOCK_RESERVE
                            : MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE,
                    Math.max(0, remainingResourceEpochs - 1)
                            * sticksPerRepair,
                    true);
        }

        public static int remainingRareBatches(int targetCount, int completedBoundary) {
            int remaining = Math.max(0, targetCount - completedBoundary);
            return (int) ((remaining + 7L) / 8L);
        }

        public static ServicePolicy obsidian8(int targetCount, int completedBoundary) {
            if (targetCount <= 0 || completedBoundary <= 0
                    || completedBoundary % OBSIDIAN_SERVICE_INTERVAL != 0
                    || completedBoundary >= targetCount) {
                throw new IllegalArgumentException("invalid_obsidian_service_boundary:target="
                        + targetCount + ":boundary=" + completedBoundary);
            }
            int remainingTarget = targetCount - completedBoundary;
            int futureStickReserve = futureSticksForRemainingTarget(remainingTarget);
            return new ServicePolicy(
                    ServiceProfile.OBSIDIAN_8,
                    remainingTarget,
                    MiningBudget.TUNNELING_SERVICE_TARGET
                            * STONE_PICKAXE_USABLE_DURABILITY,
                    MiningFoodReserve.MIN_DEEP_MINE_UNITS,
                    0,
                    MIN_FREE_MAIN_SLOTS,
                    stoneReserveFor(futureStickReserve),
                    futureStickReserve,
                    true);
        }

        public static ServicePolicy obsidianPreflight(int targetCount) {
            if (targetCount <= 0) {
                throw new IllegalArgumentException(
                        "invalid_obsidian_preflight_target:" + targetCount);
            }
            int remainingTarget = targetCount;
            int futureStickReserve = futureSticksForRemainingTarget(remainingTarget);
            return new ServicePolicy(
                    ServiceProfile.OBSIDIAN_PREFLIGHT,
                    remainingTarget,
                    MiningBudget.TUNNELING_SERVICE_TARGET
                            * STONE_PICKAXE_USABLE_DURABILITY,
                    MiningFoodReserve.MIN_DEEP_MINE_UNITS,
                    0,
                    MIN_FREE_MAIN_SLOTS,
                    stoneReserveFor(futureStickReserve),
                    futureStickReserve,
                    true);
        }

        public boolean maintainsTunnelingTools() {
            return channelToolUsableDurability > 0;
        }

        public static int futureSticksBeforeFirstPool(int targetCount) {
            return futureSticksForRemainingTarget(Math.max(1, targetCount));
        }

        public static int futureSticksAfterBoundary(int targetCount, int boundary) {
            return futureSticksForRemainingTarget(Math.max(1,
                    Math.max(1, targetCount) - Math.max(0, boundary)));
        }

        public static int bootstrapStickTarget(int targetCount) {
            return futureSticksBeforeFirstPool(targetCount)
                    + MiningBudget.TUNNELING_SERVICE_TARGET * STONE_PICKAXE_STICK_COST;
        }

        public static int bootstrapStoneLikeTarget(int targetCount) {
            return stoneReserveFor(futureSticksBeforeFirstPool(targetCount))
                    + MiningBudget.TUNNELING_SERVICE_TARGET * STONE_PICKAXE_HEAD_COST;
        }

        private static int futureSticksForRemainingTarget(int remainingTarget) {
            return (Math.max(1, remainingTarget) - 1) / OBSIDIAN_SERVICE_INTERVAL
                    * MiningBudget.TUNNELING_SERVICE_TARGET
                    * STONE_PICKAXE_STICK_COST;
        }

        private static int stoneReserveFor(int futureSticks) {
            return EMERGENCY_BLOCK_RESERVE
                    + futureSticks / STONE_PICKAXE_STICK_COST * STONE_PICKAXE_HEAD_COST;
        }
    }

    private static boolean validBoundary(ServiceProfile profile, int boundary) {
        return switch (profile) {
            case ORE_BATCH, RARE_DESCENT_KIT, OBSIDIAN_PREFLIGHT -> boundary == 0;
            case RARE_ORE_BATCH -> boundary >= 0;
            case OBSIDIAN_8 -> boundary > 0
                    && boundary % OBSIDIAN_SERVICE_INTERVAL == 0;
        };
    }

    private static int inferredServiceTarget(ServicePolicy policy, int boundary) {
        ServicePolicy resolved = policy == null ? ServicePolicy.defaultOre(false) : policy;
        return switch (resolved.profile()) {
            case ORE_BATCH -> 0;
            case RARE_DESCENT_KIT -> 64;
            case RARE_ORE_BATCH -> 0;
            case OBSIDIAN_PREFLIGHT -> resolved.targetToolUsableDurability();
            case OBSIDIAN_8 -> Math.max(0, boundary)
                    + resolved.targetToolUsableDurability();
        };
    }

    private static boolean validIdentity(ServicePolicy policy,
                                         String missionId,
                                         int targetCount,
                                         int boundary) {
        if (missionId == null || missionId.isBlank() || !validBoundary(policy.profile(), boundary)) {
            return false;
        }
        return switch (policy.profile()) {
            case ORE_BATCH -> targetCount == 0;
            case RARE_DESCENT_KIT -> targetCount == 64 && boundary == 0
                    && policy.equals(ServicePolicy.rareDescentKit(targetCount));
            case RARE_ORE_BATCH -> targetCount > boundary
                    && (policy.equals(ServicePolicy.rareOreBatch(targetCount, boundary, 0))
                    || policy.equals(ServicePolicy.rareOreBatch(targetCount, boundary, 1)));
            case OBSIDIAN_PREFLIGHT -> targetCount > 0
                    && policy.equals(ServicePolicy.obsidianPreflight(targetCount));
            case OBSIDIAN_8 -> targetCount > boundary
                    && policy.equals(ServicePolicy.obsidian8(targetCount, boundary));
        };
    }

    /** Strict mission-independent descriptor gate used by durable settled-service codecs. */
    public static boolean validServiceDescriptor(
            ServicePolicy policy, int targetCount, int boundary) {
        return policy != null && validIdentity(
                policy, STANDALONE_MISSION_ID, targetCount, boundary);
    }

    @Override
    public String name() {
        return "mining_service";
    }

    @Override
    public String describe() {
        return "Mining service phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case PREPARE -> 0.05D;
            case OPEN_MISSION_DEPOT_ALCOVE -> 0.10D;
            case PLACE_MISSION_DEPOT -> 0.14D;
            case VERIFY_MISSION_DEPOT -> 0.17D;
            case TO_DEPOT -> 0.2D;
            case DEPOSIT -> 0.4D;
            case OPEN_DISPOSAL_POCKET -> 0.30D;
            case CAPTURE_DISPOSAL_BASELINE -> 0.34D;
            case DROP_DISPOSABLE -> 0.37D;
            case SETTLE_DISPOSABLE -> 0.40D;
            case RETURN_TO_DISPOSAL_FACE -> 0.43D;
            case SEAL_DISPOSAL_POCKET -> 0.46D;
            case SUPPLIES -> 0.45D;
            case BLOCKS -> 0.5D;
            case TOOL -> 0.65D;
            case FOOD -> 0.75D;
            case RETURN_TO_FACE -> 0.9D;
            case DONE -> 1.0D;
        };
    }

    @Override
    public boolean isWaiting() {
        return child != null && child.isWaiting();
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (invalidCheckpoint) {
            fail("mining_service_invalid_checkpoint");
            return;
        }
        String liveDimension = currentDimension(bot);
        serviceDimension = restoredCheckpoint == null
                ? liveDimension : restoredCheckpoint.serviceDimension();
        budgetOffset = restoredCheckpoint == null ? 0 : restoredCheckpoint.budgetUsed();
        lastProgressBudget = restoredCheckpoint == null
                ? 0 : restoredCheckpoint.lastProgressBudget();
        workFace = restoredCheckpoint != null
                ? restoredCheckpoint.workFace()
                : miningCursor != null
                ? miningCursor.face().toImmutable()
                : bot.getBlockPos().toImmutable();
        terminalFailureReceipt = restoredCheckpoint == null
                ? "" : restoredCheckpoint.terminalFailure();
        // A settled receipt is result authority, not resumable service work. Restore only the
        // immutable fields needed to round-trip that exact checkpoint, then publish the failure
        // before live depot/cursor compatibility checks or memory writes can mutate runtime state.
        if (!terminalFailureReceipt.isBlank()) {
            depot = restoredCheckpoint.depot();
            missionDepotDirection = restoredCheckpoint.missionDepotDirection();
            missionDepotDimension = restoredCheckpoint.missionDepotDimension();
            missionDepotClearIndex = restoredCheckpoint.missionDepotClearIndex();
            missionDepotPlacementCommitted =
                    restoredCheckpoint.missionDepotPlacementCommitted();
            missionDepotRetirementCompleted =
                    restoredCheckpoint.missionDepotRetirementCompleted();
            phase = Phase.PREPARE;
            child = null;
            lastProgressPos = bot.getBlockPos().toImmutable();
            lastProgressPhase = phase;
            pocketPhaseStartedBudget = restoredCheckpoint.pocketPhaseStartedBudget();
            note = "restored_terminal_failure";
            BotLog.task(bot, "mining_service_terminal_failure_restored",
                    "face", workFace.toShortString(),
                    "reason", terminalFailureReceipt);
            fail(terminalFailureReceipt);
            return;
        }
        if (!liveDimension.equals(serviceDimension)) {
            fail("mining_service_dimension_mismatch:checkpoint="
                    + serviceDimension + ":live=" + liveDimension);
            return;
        }
        if (isRareDescentKit()
                && (miningCursor == null || !miningCursor.face().equals(workFace))) {
            fail("mining_service_rare_descent_kit_cursor_missing_or_stale");
            return;
        }
        BotMemoryStore.INSTANCE.of(bot.getUuid()).markPlace("mine_face", bot.getServerWorld(), workFace);
        depot = restoredCheckpoint == null ? null : restoredCheckpoint.depot();
        if (depot == null) {
            depot = resolveDepot(bot);
        }
        pocketEntry = restoredCheckpoint == null ? null : restoredCheckpoint.pocketEntry();
        pocketSink = restoredCheckpoint == null ? null : restoredCheckpoint.pocketSink();
        pocketDirection = restoredCheckpoint == null ? null : restoredCheckpoint.pocketDirection();
        pocketDropCommitted = restoredCheckpoint != null
                && restoredCheckpoint.pocketDropCommitted();
        pocketLedgerVerified = restoredCheckpoint != null
                && restoredCheckpoint.pocketLedgerVerified();
        pocketEntityIds.clear();
        pocketLineage.clear();
        pocketDropLedger.clear();
        pocketBaseline.clear();
        if (restoredCheckpoint != null) {
            pocketEntityIds.addAll(restoredCheckpoint.pocketEntityIds());
            pocketLineage.putAll(restoredCheckpoint.pocketLineage());
            pocketDropLedger.putAll(restoredCheckpoint.pocketDropLedger());
            pocketBaseline.putAll(restoredCheckpoint.pocketBaseline());
        }
        pocketTerminalFailure = restoredCheckpoint == null
                ? "" : restoredCheckpoint.pocketFailure();
        pocketBaselineStableTicks = 0;
        pocketPresealStableTicks = 0;
        pocketClearIndex = restoredCheckpoint == null
                ? 0 : restoredCheckpoint.pocketClearIndex();
        missionDepotDirection = restoredCheckpoint == null
                ? directionToOwnedMissionDepot(bot, depot)
                : restoredCheckpoint.missionDepotDirection();
        missionDepotDimension = restoredCheckpoint == null
                ? isRareDescentKit() ? currentDimension(bot)
                : depot == null ? "" : currentDimension(bot)
                : restoredCheckpoint.missionDepotDimension();
        if (isRareDescentKit() && !missionDepotDimension.isBlank()
                && !missionDepotDimension.equals(currentDimension(bot))) {
            fail("mining_service_mission_depot_dimension_mismatch");
            return;
        }
        missionDepotClearIndex = restoredCheckpoint == null
                ? 0 : restoredCheckpoint.missionDepotClearIndex();
        missionDepotPlacementCommitted = restoredCheckpoint != null
                && restoredCheckpoint.missionDepotPlacementCommitted();
        missionDepotRetirementCompleted = restoredCheckpoint != null
                && restoredCheckpoint.missionDepotRetirementCompleted();
        if (restoredCheckpoint == null && isRareDescentKit() && depot != null) {
            if (missionDepotDirection != null && ownedMissionDepot(bot, serviceMissionId)) {
                missionDepotPlacementCommitted = true;
                missionDepotClearIndex = 2;
            } else {
                depot = null;
                missionDepotDirection = null;
                missionDepotClearIndex = 0;
                missionDepotPlacementCommitted = false;
            }
        }
        // Child tasks and paths are intentionally rebuilt from live inventory/world state. Normal
        // service phases restart through PREPARE. A disposal transaction is different: once an
        // ordinary world drop exists, its durable physical containment phase must be resumed and
        // may never silently become a fresh open-corridor drop.
        phase = restoredCheckpoint != null
                && (isPocketPhase(restoredCheckpoint.phase())
                || isMissionDepotPhase(restoredCheckpoint.phase()))
                ? restoredCheckpoint.phase() : Phase.PREPARE;
        if (pocketDropCommitted && (phase == Phase.DROP_DISPOSABLE
                || phase == Phase.OPEN_DISPOSAL_POCKET)) {
            phase = Phase.SETTLE_DISPOSABLE;
        }
        child = null;
        depotApproachIndex = 0;
        lastProgressPos = bot.getBlockPos().toImmutable();
        lastProgressPhase = phase;
        pocketPhaseStartedBudget = restoredCheckpoint == null
                ? totalBudget() : restoredCheckpoint.pocketPhaseStartedBudget();
        if (restoredCheckpoint != null) {
            note = "restored_from_" + restoredCheckpoint.phase().name().toLowerCase(java.util.Locale.ROOT);
            BotLog.task(bot, "mining_service_restored",
                    "face", workFace.toShortString(),
                    "phase", restoredCheckpoint.phase(),
                    "depot", depot == null ? "none" : depot.toShortString());
        }
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (!serviceDimension.equals(currentDimension(bot))) {
            bot.getActionPack().stopAll();
            fail("mining_service_dimension_mismatch:checkpoint="
                    + serviceDimension + ":live=" + currentDimension(bot));
            return;
        }
        observeProgress(bot);
        if (totalBudget() > MAX_ELAPSED) {
            if (hasCommittedDisposalDebt()) {
                beginPocketTerminalRecovery(bot, "mining_service_timeout:" + phase);
            } else {
                fail("mining_service_timeout:" + phase);
                return;
            }
        }
        if (totalBudget() - lastProgressBudget > NO_PROGRESS_LIMIT) {
            if (hasCommittedDisposalDebt()) {
                beginPocketTerminalRecovery(bot, "mining_service_no_progress:" + phase);
            } else {
                fail("mining_service_no_progress:" + phase);
                return;
            }
        }
        switch (phase) {
            case PREPARE -> prepare(bot);
            case OPEN_MISSION_DEPOT_ALCOVE -> openMissionDepotAlcove(bot);
            case PLACE_MISSION_DEPOT -> placeMissionDepot(bot);
            case VERIFY_MISSION_DEPOT -> verifyMissionDepot(bot);
            case TO_DEPOT -> walkToDepot(bot);
            case DEPOSIT -> deposit(bot);
            case OPEN_DISPOSAL_POCKET -> openDisposalPocket(bot);
            case CAPTURE_DISPOSAL_BASELINE -> captureDisposalBaseline(bot);
            case DROP_DISPOSABLE -> dropDisposable(bot);
            case SETTLE_DISPOSABLE -> settleDisposable(bot);
            case RETURN_TO_DISPOSAL_FACE -> returnToDisposalFace(bot);
            case SEAL_DISPOSAL_POCKET -> sealDisposalPocket(bot);
            case SUPPLIES -> serviceMissionSupplies(bot);
            case BLOCKS -> serviceEmergencyBlocks(bot);
            case TOOL -> serviceTool(bot);
            case FOOD -> serviceFood(bot);
            case RETURN_TO_FACE -> returnToFace(bot);
            case DONE -> complete();
        }
        observeProgress(bot);
    }

    private int totalBudget() {
        return budgetOffset + elapsed;
    }

    private void noteProgress() {
        lastProgressBudget = totalBudget();
    }

    private void observeProgress(AIPlayerEntity bot) {
        BlockPos current = bot.getBlockPos();
        if (lastProgressPos == null || !lastProgressPos.equals(current)
                || lastProgressPhase != phase) {
            lastProgressPos = current.toImmutable();
            lastProgressPhase = phase;
            noteProgress();
        }
    }

    private void prepare(AIPlayerEntity bot) {
        if (isRareDescentKit() && !liveExactMissionDepot(bot)) {
            if (depot != null && missionDepotPlacementCommitted) {
                fail("mining_service_mission_depot_committed_chest_missing");
                return;
            }
            if (depot == null || missionDepotDirection == null) {
                if (!selectMissionDepotAlcove(bot)) {
                    return;
                }
            }
            phase = Phase.OPEN_MISSION_DEPOT_ALCOVE;
            return;
        }
        int freeSlots = freeMainSlots(bot);
        int requiredFreeSlots = requiredWorkingFreeSlots(bot);
        boolean needsUnload = freeSlots < requiredFreeSlots;
        int targetToolUsable = targetToolUsableDurability(bot);
        boolean needsTool = targetToolUsable < policy.targetToolUsableDurability();
        int channelToolUsable = usableDurability(bot, Items.STONE_PICKAXE);
        boolean needsChannelTool = policy.maintainsTunnelingTools()
                && channelToolUsable < policy.channelToolUsableDurability();
        boolean needsFood = MiningFoodReserve.units(bot.getInventory())
                < policy.foodMinUnits();
        boolean needsTorches = InventoryAction.countItem(bot, Items.TORCH)
                < policy.torchMinCount();
        boolean needsEmergencyBlocks = requiresProtectedEmergencyBlocks();
        needsEmergencyBlocks = needsEmergencyBlocks
                && stoneLikeCount(bot) < policy.emergencyBlocksReserved();
        boolean needsWaterBucket = isObsidianProfile()
                && InventoryAction.countItem(bot, Items.WATER_BUCKET) < 1;
        boolean needsCraftingTable = policy.craftingTableRequired()
                && InventoryAction.countItem(bot, Items.CRAFTING_TABLE) < 1;
        boolean needsFutureSticks = InventoryAction.countItem(bot, Items.STICK)
                < preRepairStickRequirement(bot);
        boolean needsKitRetirement = isRareDescentKit()
                && !missionDepotRetirementCompleted;
        // Rare batch service is anchored to the current mining face. Its mission depot may be
        // several layers away at the descent hand-off, so a remembered chest is not permission to
        // preempt locally sufficient service with a remote round trip. Supply stages may still use
        // the chest when it is already physically reachable from this face.
        if (depot != null && !isRareOreBatch()
                && (needsUnload || needsTool || needsChannelTool
                || needsFood || needsTorches || needsEmergencyBlocks || needsWaterBucket
                || needsCraftingTable || needsFutureSticks || needsKitRetirement)) {
            phase = Phase.TO_DEPOT;
            return;
        }
        if (isRareDescentKit() && depot == null) {
            fail("mining_service_mission_depot_missing");
            return;
        }
        if (needsUnload) {
            startDisposalPocket(bot, requiredFreeSlots);
            return;
        }
        phase = Phase.SUPPLIES;
    }

    private boolean selectMissionDepotAlcove(AIPlayerEntity bot) {
        if (!isRareDescentKit()) {
            fail("mining_service_mission_depot_wrong_profile");
            return false;
        }
        if (!ensureAtMissionDepotWorkFace(bot)) {
            return false;
        }
        if (InventoryAction.countItem(bot, Items.CHEST) < 1) {
            fail("mining_service_mission_depot_chest_missing");
            return false;
        }
        Direction forward = cursorForward(miningCursor);
        Direction[] candidates = {
                forward.rotateYClockwise(), forward.rotateYCounterclockwise()};
        String lastFailure = "no_observable_side";
        for (Direction direction : candidates) {
            BlockPos candidate = workFace.offset(direction);
            String failure = missionDepotCandidateFailure(bot, candidate);
            if (failure != null) {
                lastFailure = failure;
                continue;
            }
            missionDepotDirection = direction;
            depot = candidate.toImmutable();
            missionDepotDimension = currentDimension(bot);
            missionDepotClearIndex = 0;
            missionDepotPlacementCommitted = false;
            missionDepotRetirementCompleted = false;
            phase = Phase.OPEN_MISSION_DEPOT_ALCOVE;
            note = "mission_depot_alcove_selected:" + direction.asString();
            noteProgress();
            return true;
        }
        fail("mining_service_mission_depot_no_safe_site:" + lastFailure);
        return false;
    }

    private String missionDepotCandidateFailure(AIPlayerEntity bot, BlockPos candidate) {
        var world = bot.getServerWorld();
        BlockPos support = candidate.down();
        if (!ObservableWorldQuery.canObserveBlock(bot, support)) {
            return "support_unobservable";
        }
        BlockState supportState = world.getBlockState(support);
        if (!supportState.getFluidState().isEmpty()
                || supportState.getCollisionShape(world, support).isEmpty()) {
            return "support_unsafe";
        }
        for (BlockPos cell : new BlockPos[]{candidate, candidate.up()}) {
            if (!ObservableWorldQuery.canObserveCell(bot, cell)
                    && !ObservableWorldQuery.canObserveBlock(bot, cell)) {
                return "cell_unobservable";
            }
            BlockState cellState = world.getBlockState(cell);
            if (!cellState.getFluidState().isEmpty()) {
                return "fluid_blocked";
            }
            if (OreScan.isOreBlock(cellState.getBlock())) {
                return "ore_preserved:" + Registries.BLOCK.getId(cellState.getBlock());
            }
            if (!cellState.isAir() && cellState.getHardness(world, cell) < 0.0F) {
                return "unbreakable:" + cell.toShortString();
            }
            if (world.getBlockEntity(cell) != null) {
                return "occupied_block_entity:" + cell.toShortString();
            }
        }
        return null;
    }

    private void openMissionDepotAlcove(AIPlayerEntity bot) {
        if (!validMissionDepotIdentity() || !ensureAtMissionDepotWorkFace(bot)) {
            return;
        }
        if (ContainerAction.resolve(bot, depot).isPresent()) {
            if (missionDepotOwnedByDifferentMissionAt(bot, serviceMissionId, depot)) {
                fail("mining_service_mission_depot_owner_mismatch");
                return;
            }
            missionDepotPlacementCommitted = true;
            missionDepotMiner.cancel(bot);
            phase = Phase.VERIFY_MISSION_DEPOT;
            noteProgress();
            return;
        }
        if (missionDepotPlacementCommitted) {
            fail("mining_service_mission_depot_committed_chest_missing");
            return;
        }
        if (missionDepotMiner.target() != null) {
            BlockMiner.Status active = missionDepotMiner.tick(bot);
            if (active == BlockMiner.Status.DONE) {
                missionDepotClearIndex = Math.min(2, missionDepotClearIndex + 1);
                noteProgress();
            } else if (active == BlockMiner.Status.FAILED) {
                fail("mining_service_mission_depot_mine_failed:"
                        + missionDepotMiner.failureReason());
            }
            return;
        }
        if (missionDepotClearIndex > 0
                && !bot.getServerWorld().getBlockState(depot).isAir()) {
            fail("mining_service_mission_depot_geometry_changed:lower");
            return;
        }
        if (missionDepotClearIndex >= 2) {
            if (!validateOpenMissionDepotAlcove(bot)) {
                fail("mining_service_mission_depot_geometry_unsafe");
                return;
            }
            phase = Phase.PLACE_MISSION_DEPOT;
            note = "mission_depot_alcove_open";
            noteProgress();
            return;
        }
        BlockPos target = missionDepotClearIndex == 0 ? depot : depot.up();
        if (!ObservableWorldQuery.canObserveCell(bot, target)
                && !ObservableWorldQuery.canObserveBlock(bot, target)) {
            fail("mining_service_mission_depot_target_unobservable:"
                    + target.toShortString());
            return;
        }
        BlockState targetState = bot.getServerWorld().getBlockState(target);
        if (targetState.isAir()) {
            missionDepotClearIndex++;
            noteProgress();
            return;
        }
        if (OreScan.isOreBlock(targetState.getBlock())) {
            fail("mining_service_mission_depot_ore_preserved:"
                    + Registries.BLOCK.getId(targetState.getBlock()));
            return;
        }
        if (!targetState.getFluidState().isEmpty()) {
            fail("mining_service_mission_depot_fluid_blocked:"
                    + target.toShortString());
            return;
        }
        if (targetState.getHardness(bot.getServerWorld(), target) < 0.0F
                || bot.getServerWorld().getBlockEntity(target) != null) {
            fail("mining_service_mission_depot_target_unmineable:"
                    + target.toShortString());
            return;
        }
        missionDepotMiner.begin(bot, target);
        BlockMiner.Status status = missionDepotMiner.tick(bot);
        if (status == BlockMiner.Status.FAILED) {
            fail("mining_service_mission_depot_mine_failed:"
                    + missionDepotMiner.failureReason());
        }
    }

    private void placeMissionDepot(AIPlayerEntity bot) {
        if (!validMissionDepotIdentity() || !ensureAtMissionDepotWorkFace(bot)) {
            return;
        }
        if (ContainerAction.resolve(bot, depot).isPresent()) {
            if (missionDepotOwnedByDifferentMissionAt(bot, serviceMissionId, depot)) {
                fail("mining_service_mission_depot_owner_mismatch");
                return;
            }
            missionDepotPlacementCommitted = true;
            phase = Phase.VERIFY_MISSION_DEPOT;
            noteProgress();
            return;
        }
        if (missionDepotPlacementCommitted) {
            fail("mining_service_mission_depot_committed_chest_missing");
            return;
        }
        if (!validateOpenMissionDepotAlcove(bot)) {
            fail("mining_service_mission_depot_geometry_changed_before_place");
            return;
        }
        java.util.OptionalInt chestSlot = InventoryAction.findItem(bot, Items.CHEST);
        if (chestSlot.isEmpty()) {
            fail("mining_service_mission_depot_chest_missing");
            return;
        }
        if (InventoryAction.equipFromSlot(bot, chestSlot.getAsInt()) < 0) {
            fail("mining_service_mission_depot_chest_equip_failed");
            return;
        }
        ActionResult placed = BuildAction.placeBlockAt(bot, depot);
        if (placed.isFailed()) {
            fail("mining_service_mission_depot_place_failed:" + placed.reason());
            return;
        }
        missionDepotPlacementCommitted = true;
        if (ContainerAction.resolve(bot, depot).isEmpty()) {
            fail("mining_service_mission_depot_verify_failed_after_place");
            return;
        }
        phase = Phase.VERIFY_MISSION_DEPOT;
        note = "mission_depot_placed";
        noteProgress();
    }

    private void verifyMissionDepot(AIPlayerEntity bot) {
        if (!validMissionDepotIdentity() || !ensureAtMissionDepotWorkFace(bot)) {
            return;
        }
        if (!missionDepotPlacementCommitted) {
            fail("mining_service_mission_depot_uncommitted_verify");
            return;
        }
        if (!canInteractWithDepot(bot, depot)
                || ContainerAction.resolve(bot, depot).isEmpty()) {
            fail("mining_service_mission_depot_committed_chest_missing");
            return;
        }
        var memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
        Optional<BlockPos> remembered = memory.placeIn(bot.getServerWorld(), "mining_depot");
        Optional<String> owner = memory.recall("mining_depot_owner");
        // A same-mission owner fact is a position binding, not a reusable label. Accept an
        // owner-less remembered position as the legitimate markPlace->remember crash window, and
        // accept a different mission at another position as normal mission replacement. But a
        // current-mission owner pointing at A can never authorize checkpoint candidate B.
        boolean currentOwnerAtDifferentPosition = owner.filter(serviceMissionId::equals).isPresent()
                && (remembered.isEmpty() || !remembered.orElseThrow().equals(depot));
        boolean differentOwnerAtCandidate = remembered.filter(depot::equals).isPresent()
                && owner.isPresent() && !serviceMissionId.equals(owner.orElseThrow());
        if (currentOwnerAtDifferentPosition || differentOwnerAtCandidate) {
            fail("mining_service_mission_depot_owner_mismatch");
            return;
        }
        memory.markPlace("mining_depot", bot.getServerWorld(), depot);
        memory.remember("mining_depot_owner", serviceMissionId);
        if (!liveExactMissionDepot(bot)) {
            fail("mining_service_mission_depot_verify_failed");
            return;
        }
        phase = Phase.DEPOSIT;
        note = "mission_depot_verified";
        noteProgress();
        BotLog.action(bot, "mining_service_mission_depot_verified",
                "mission", serviceMissionId,
                "depot", depot.toShortString(),
                "direction", missionDepotDirection.asString());
    }

    private boolean validateOpenMissionDepotAlcove(AIPlayerEntity bot) {
        if (!validMissionDepotIdentity()) {
            return false;
        }
        var world = bot.getServerWorld();
        for (BlockPos cell : new BlockPos[]{depot, depot.up()}) {
            if (!ObservableWorldQuery.canObserveCell(bot, cell)
                    || !world.getBlockState(cell).isAir()
                    || !world.getBlockState(cell).getFluidState().isEmpty()) {
                return false;
            }
        }
        BlockPos support = depot.down();
        return ObservableWorldQuery.canObserveBlock(bot, support)
                && world.getBlockState(support).getFluidState().isEmpty()
                && !world.getBlockState(support).getCollisionShape(world, support).isEmpty();
    }

    private boolean ensureAtMissionDepotWorkFace(AIPlayerEntity bot) {
        if (workFace != null && bot.getBlockPos().equals(workFace)) {
            bot.getActionPack().stopAll();
            return true;
        }
        if (workFace == null) {
            fail("mining_service_mission_depot_work_face_missing");
            return false;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startSurfacePathTo(workFace);
            if (result.isFailed()) {
                fail("mining_service_mission_depot_work_face_unreachable:"
                        + result.reason());
            }
        }
        return false;
    }

    private boolean validMissionDepotIdentity() {
        if (!isRareDescentKit() || miningCursor == null || workFace == null
                || depot == null || missionDepotDirection == null
                || !missionDepotDirection.getAxis().isHorizontal()
                || !miningCursor.face().equals(workFace)) {
            return false;
        }
        Direction forward = cursorForward(miningCursor);
        return (missionDepotDirection == forward.rotateYClockwise()
                || missionDepotDirection == forward.rotateYCounterclockwise())
                && depot.equals(workFace.offset(missionDepotDirection));
    }

    private static boolean isMissionDepotPhase(Phase candidate) {
        return candidate == Phase.OPEN_MISSION_DEPOT_ALCOVE
                || candidate == Phase.PLACE_MISSION_DEPOT
                || candidate == Phase.VERIFY_MISSION_DEPOT;
    }

    private void startDisposalPocket(AIPlayerEntity bot, int requiredFreeSlots) {
        startDisposalPocket(bot, requiredFreeSlots, null, "");
    }

    private void startDisposalPocket(AIPlayerEntity bot,
                                     int requiredFreeSlots,
                                     Direction rejectedDirection,
                                     String retryMarker) {
        if (pocketDropCommitted) {
            fail("mining_service_additional_disposal_required:free=" + freeMainSlots(bot)
                    + ":required=" + requiredFreeSlots);
            return;
        }
        if (miningCursor == null || workFace == null
                || !miningCursor.face().equals(workFace)) {
            fail("mining_service_disposal_cursor_missing_or_stale");
            return;
        }
        if (!bot.getBlockPos().equals(workFace)) {
            fail("mining_service_disposal_not_at_cursor_face:at="
                    + bot.getBlockPos().toShortString() + ":face=" + workFace.toShortString());
            return;
        }
        DisposalGeometry candidateGeometry = DisposalGeometry.fromCursor(miningCursor)
                .orElse(null);
        Optional<DisposalReplayGuard> forbidden = disposalReplayGuards.stream()
                .filter(guard -> guard.matches(bot, candidateGeometry))
                .findFirst();
        if (forbidden.isPresent()) {
            // PREPARE/ensureWorkingCapacity has factually re-attested the slot deficit. The exact
            // lateral pair was already double-sealed after both sides proved ore-blocked, so fail
            // with that typed result before centering, mining, dropping or any other world change.
            fail(forbidden.orElseThrow().failureReason());
            return;
        }
        // A completed OreDig walk may publish the correct BlockPos while the fake player is still
        // on that cell's forward edge and carrying ordinary server-side horizontal velocity.  The
        // OPEN phase is durable physical-debt authority, so it must never be entered until the
        // service owns a stationary, centered work face.  Once OPEN is published any later drift
        // correctly remains fail-closed and must be sealed as unresolved geometry debt.
        if (!ensureCenteredAtWorkFace(bot)) {
            fail("mining_service_disposal_center_failed:at="
                    + bot.getBlockPos().toShortString() + ":face=" + workFace.toShortString());
            return;
        }
        int sealable = availableDisposableSealBlocks(bot);
        if (sealable < 2) {
            fail("mining_service_disposal_seal_reserve_depleted:have=" + sealable
                    + ":required=2");
            return;
        }
        Direction forward = cursorForward(miningCursor);
        Direction[] candidates = {forward.rotateYClockwise(), forward.rotateYCounterclockwise()};
        Direction selected = null;
        for (Direction candidate : candidates) {
            if (candidate == rejectedDirection) {
                continue;
            }
            BlockPos entry = workFace.offset(candidate);
            BlockPos sink = workFace.offset(candidate, 2);
            if (candidate == missionDepotDirection
                    || depot != null && (entry.equals(depot) || entry.up().equals(depot)
                    || sink.equals(depot) || sink.up().equals(depot))) {
                continue;
            }
            if (observablePocketMouth(bot, entry)) {
                selected = candidate;
                break;
            }
        }
        if (selected == null) {
            fail(rejectedDirection != null
                    ? "mining_service_disposal_no_alternate_after_ore:"
                    + rejectedDirection.asString()
                    : isRareDescentKit()
                    ? "mining_service_rare_descent_kit_disposal_conflicts_with_depot"
                    : "mining_service_disposal_pocket_unobservable");
            return;
        }
        pocketDirection = selected;
        pocketEntry = workFace.offset(selected);
        pocketSink = workFace.offset(selected, 2);
        pocketEntityIds.clear();
        pocketLineage.clear();
        pocketDropLedger.clear();
        pocketBaseline.clear();
        pocketTerminalFailure = retryMarker == null ? "" : retryMarker;
        pocketClearIndex = 0;
        pocketLedgerVerified = false;
        enterPocketPhase(Phase.OPEN_DISPOSAL_POCKET);
        note = "opening_disposal_pocket:" + pocketDirection.asString();
    }

    private void openDisposalPocket(AIPlayerEntity bot) {
        if (!validPocketIdentity() || !ensureAtWorkFace(bot)) {
            return;
        }
        // Poll the already-issued physical break before selecting/observing another cell. Once
        // the lower entry breaks, the still-solid head block can occlude its now-empty center;
        // BlockMiner's owned target is the factual attestation that advances the durable index.
        if (disposalMiner.target() != null) {
            BlockMiner.Status active = disposalMiner.tick(bot);
            if (active == BlockMiner.Status.DONE) {
                pocketClearIndex = Math.min(4, pocketClearIndex + 1);
                noteProgress();
            } else if (active == BlockMiner.Status.FAILED) {
                sealPocketThenFail("mining_service_disposal_mine_failed:"
                        + disposalMiner.failureReason());
            }
            return;
        }
        BlockPos target = nextPocketSolid(bot);
        if (state == TaskState.FAILED) {
            return;
        }
        if (target != null) {
            BlockState state = bot.getServerWorld().getBlockState(target);
            if (OreScan.isOreBlock(state.getBlock())) {
                String reason = POCKET_ORE_FAILURE_PREFIX
                        + Registries.BLOCK.getId(state.getBlock());
                if (isPocketOreRetryMarker()) {
                    sealPocketThenFail(reason);
                } else {
                    sealPocketThenRetryAlternative(reason);
                }
                return;
            }
            if (!state.getFluidState().isEmpty() || state.getHardness(
                    bot.getServerWorld(), target) < 0.0F) {
                sealPocketThenFail("mining_service_disposal_target_unmineable:"
                        + target.toShortString());
                return;
            }
            BlockMiner.Status status = beginDisposalMine(bot, target);
            if (status == BlockMiner.Status.FAILED) {
                sealPocketThenFail("mining_service_disposal_mine_failed:"
                        + disposalMiner.failureReason());
            }
            return;
        }
        disposalMiner.cancel(bot);
        if (!validateOpenPocket(bot)) {
            String reason = "mining_service_disposal_geometry_unsafe";
            // A naturally open cave behind an otherwise observable mouth can reveal missing
            // sink support only after the entry cells have been physically cleared.  Settle that
            // mutation with the existing durable double-seal transaction, then try the one
            // remaining side exactly once.  A second unsafe pocket terminalizes instead of
            // ping-ponging between the two sides.
            if (isPocketOreRetryMarker()) {
                sealPocketThenFail(reason);
            } else {
                sealPocketThenRetryAlternative(reason);
            }
            return;
        }
        if (isPocketOreRetryMarker()) {
            BotLog.action(bot, "mining_service_disposal_alternate_open",
                    "direction", pocketDirection.asString(),
                    "entry", pocketEntry.toShortString(),
                    "sink", pocketSink.toShortString());
            pocketTerminalFailure = "";
        }
        pocketBaseline.clear();
        pocketBaselineStableTicks = 0;
        enterPocketPhase(Phase.CAPTURE_DISPOSAL_BASELINE);
        note = "disposal_pocket_open";
    }

    private void captureDisposalBaseline(AIPlayerEntity bot) {
        if (!validPocketIdentity() || !ensureAtWorkFace(bot)) {
            return;
        }
        if (!validateOpenPocket(bot)) {
            sealPocketThenFail("mining_service_disposal_geometry_changed");
            return;
        }
        Optional<ItemEntity> openingSpoil = nearestUntrackedOpeningSpoil(bot);
        if (openingSpoil.isPresent()) {
            ItemEntity entity = openingSpoil.orElseThrow();
            pocketBaselineStableTicks = 0;
            if (canAcceptEntireStack(bot, entity.getStack())) {
                collectOpeningSpoil(bot, entity);
                return;
            }
            DisposalCandidate candidate = disposableCandidate(
                    bot, 2, entity.getStack().getItem());
            if (candidate == null) {
                candidate = disposableCandidate(bot, 2);
            }
            if (candidate == null || !dropPrebaselineDisposable(bot, candidate)) {
                sealPocketThenFail("mining_service_inventory_reserve_depleted_before_baseline");
            }
            return;
        }
        if (!ensureCenteredAtWorkFace(bot)) {
            return;
        }
        if (!prebaselineDropsContained(bot)) {
            if (pocketPhaseAge() > POCKET_SETTLE_LIMIT) {
                sealPocketThenFail("mining_service_disposal_prebaseline_settle_timeout");
            }
            return;
        }
        Optional<Map<Item, Integer>> boundedObserved = boundedCheckpointCounts(
                sinkItemCounts(bot), POCKET_CHECKPOINT_MAX_STACKS,
                POCKET_CHECKPOINT_MAX_ITEM_COUNT);
        if (boundedObserved.isEmpty()) {
            sealPocketThenFail("mining_service_disposal_baseline_limit_exceeded");
            return;
        }
        Map<Item, Integer> observed = boundedObserved.orElseThrow();
        if (!observed.equals(pocketBaseline)) {
            pocketBaseline.clear();
            pocketBaseline.putAll(observed);
            pocketBaselineStableTicks = 0;
            return;
        }
        pocketBaselineStableTicks++;
        if (pocketBaselineStableTicks < POCKET_BASELINE_STABLE_TICKS) {
            return;
        }
        // Freeze every observable baseline UUID as the root of this pocket's physical lineage.
        // Vanilla may later merge a post-baseline drop into one of these entities (or the reverse),
        // destroying one UUID while preserving the exact combined stack count. Keeping both roots
        // lets restartable attestation accept only survivors descended from this frozen baseline or
        // a subsequent ordinary player drop, without mutating ItemEntity owner/count metadata.
        pocketEntityIds.clear();
        pocketLineage.clear();
        for (ItemEntity entity : observableSinkItems(bot)) {
            if (pocketEntityIds.size() >= POCKET_CHECKPOINT_MAX_STACKS) {
                sealPocketThenFail("mining_service_disposal_baseline_identity_limit_exceeded");
                return;
            }
            pocketEntityIds.add(entity.getUuid());
            pocketLineage.put(entity.getUuid(), new PocketLineage(
                    entity.getStack().getItem(), entity.getStack().getCount(), false));
        }
        pocketPresealStableTicks = 0;
        enterPocketPhase(Phase.DROP_DISPOSABLE);
        note = "disposal_baseline_stable";
    }

    private void dropDisposable(AIPlayerEntity bot) {
        if (!validPocketIdentity() || !ensureAtWorkFace(bot)) {
            return;
        }
        if (!validateOpenPocket(bot)) {
            sealPocketThenFail("mining_service_disposal_geometry_changed");
            return;
        }
        Optional<ItemEntity> openingSpoil = nearestUntrackedOpeningSpoil(bot);
        if (openingSpoil.isPresent()
                && canAcceptEntireStack(bot, openingSpoil.orElseThrow().getStack())) {
            collectOpeningSpoil(bot, openingSpoil.orElseThrow());
            return;
        }
        if (!ensureCenteredAtWorkFace(bot)) {
            return;
        }
        int required = requiredWorkingFreeSlots(bot);
        DisposalCandidate candidate = capacityDisposableCandidate(bot, 2);
        if (candidate == null) {
            if (openingSpoil.isEmpty()
                    && projectedFreeSlotsAfterSealing(bot) >= required) {
                // A sealed capacity transaction is expensive and an open OreDig batch can cross
                // hundreds of ordinary channel blocks before the next target vein. Keep draining
                // every whole disposable stack that factually frees a slot, rather than stopping
                // at the four-slot minimum and carrying several hundred cobblestone forward. The
                // candidate selector still preserves target drops, both seal blocks, and the
                // exact protected stone horizon.
                pocketDropCommitted = true;
                enterPocketPhase(Phase.SETTLE_DISPOSABLE);
            } else {
                sealPocketThenFail("mining_service_inventory_reserve_depleted:free="
                        + freeMainSlots(bot) + ":required=" + required);
            }
            return;
        }
        LookAction.lookAt(bot, pocketSink.toCenterPos().add(0.0D, 0.25D, 0.0D));
        Optional<ItemEntity> dropped = InventoryAction.dropSlotEntity(
                bot, candidate.slot(), candidate.count());
        if (dropped.isEmpty()) {
            sealPocketThenFail("mining_service_disposal_drop_entity_missing");
            return;
        }
        ItemEntity entity = dropped.orElseThrow();
        ItemStack stack = entity.getStack();
        if (!recordPocketLedgerDrop(stack, entity.getUuid())) {
            sealPocketThenFail("mining_service_disposal_ledger_limit_exceeded");
            return;
        }
        enterPocketPhase(Phase.SETTLE_DISPOSABLE);
        note = "disposal_drop_in_flight:" + Registries.ITEM.getId(stack.getItem())
                + ":" + stack.getCount();
        BotLog.action(bot, "mining_service_disposal_drop",
                "entity", entity.getUuid(),
                "item", Registries.ITEM.getId(stack.getItem()),
                "count", stack.getCount(),
                "sink", pocketSink.toShortString());
    }

    private void settleDisposable(AIPlayerEntity bot) {
        if (!validPocketIdentity() || !ensureAtWorkFace(bot)) {
            return;
        }
        if (!validateOpenPocket(bot)) {
            sealPocketThenFail("mining_service_disposal_geometry_changed");
            return;
        }
        // A freshly dropped tracked entity normally crosses the mouth before reaching the sink.
        // Do not collect it as opening spoil or call that in-flight state an escape. Live UUID
        // attestation below is the only authority to leave SETTLE; a tracked entity that never
        // reaches the sink therefore ends in the bounded settle-timeout recovery path.
        Optional<ItemEntity> openingSpoil = nearestUntrackedOpeningSpoil(bot);
        if (!sinkLedgerSatisfied(bot)) {
            pocketPresealStableTicks = 0;
            if (pocketPhaseAge() > POCKET_SETTLE_LIMIT) {
                sealPocketThenFail("mining_service_disposal_settle_timeout");
            }
            return;
        }
        if (openingSpoil.isPresent()) {
            pocketPresealStableTicks = 0;
            if (canAcceptEntireStack(bot, openingSpoil.orElseThrow().getStack())) {
                collectOpeningSpoil(bot, openingSpoil.orElseThrow());
            } else {
                enterPocketPhase(Phase.DROP_DISPOSABLE);
                note = "opening_spoil_requires_inventory_slot";
            }
            return;
        }
        if (!ensureCenteredAtWorkFace(bot)) {
            pocketPresealStableTicks = 0;
            return;
        }
        noteProgress();
        int required = requiredWorkingFreeSlots(bot);
        if (projectedFreeSlotsAfterSealing(bot) < required) {
            enterPocketPhase(Phase.DROP_DISPOSABLE);
            return;
        }
        // One settled ledger generation proves only that its tracked drop reached the sink. A
        // long ordinary branch may still carry several whole junk stacks after the minimum
        // capacity promise has been met. Re-enter DROP while another candidate can factually free
        // a post-seal slot; the next generation remains covered by the same bounded UUID/count
        // ledger and the same physical pocket. This is deliberately checked only after the prior
        // generation is attested so two in-flight drops can never be conflated.
        if (capacityDisposableCandidate(bot, 2) != null) {
            enterPocketPhase(Phase.DROP_DISPOSABLE);
            note = "disposal_more_capacity_available";
            return;
        }
        // Opening-block drops and ordinary player drops update collision/pickup after this task's
        // tick.  Sealing on the first good snapshot can therefore push or admit one delayed item
        // only after the four-slot postcondition was checked.  Require a full vanilla pickup-delay
        // window of consecutive, ledger-satisfied capacity observations before either mouth block
        // is placed.  Any late pickup changes the live inventory and sends the next tick back
        // through DROP_DISPOSABLE before success can be published.
        pocketPresealStableTicks++;
        if (pocketPresealStableTicks < POCKET_PRESEAL_STABLE_TICKS) {
            note = "waiting_for_disposal_preseal_stability:"
                    + pocketPresealStableTicks + "/" + POCKET_PRESEAL_STABLE_TICKS;
            return;
        }
        pocketDropCommitted = true;
        pocketLedgerVerified = true;
        enterPocketPhase(Phase.SEAL_DISPOSAL_POCKET);
        note = "disposal_sink_verified";
    }

    private void sealDisposalPocket(AIPlayerEntity bot) {
        if (!validPocketIdentity() || !ensureAtWorkFace(bot)) {
            return;
        }
        if (!ensureCenteredAtWorkFace(bot)) {
            return;
        }
        if (failOnTrackedLedgerEscape(bot)) {
            return;
        }
        if (pocketTerminalFailure.isBlank()) {
            Optional<ItemEntity> openingSpoil = nearestUntrackedOpeningSpoil(bot);
            if (openingSpoil.isPresent()) {
                pocketLedgerVerified = false;
                enterPocketPhase(Phase.DROP_DISPOSABLE);
                note = "disposal_seal_blocked_by_opening_spoil";
                return;
            }
        }
        boolean lowerSealed = isSolidSeal(bot, pocketEntry);
        boolean upperSealed = isSolidSeal(bot, pocketEntry.up());
        // A second preserved ore cell is a routing exhaustion, not an unresolved disposal ledger.
        // It may yield back to GoalExecutor only if the still-open mouth is observably empty before
        // both seals are placed atomically in this server tick.  A partial seal cannot re-attest the
        // now-occluded sink, and any tracked/baseline/untracked ItemEntity keeps the pocket identity
        // live so the mission remains fail-closed instead of orphaning physical world state.
        boolean retireClosedOreFailure = canRetirePreservedOrePocketBeforeSeal(bot)
                && ((!lowerSealed && !upperSealed)
                || preservedOreStillBlocksMouthFrontier(bot));
        // A persisted verified bit is only a hint. Re-attest the live ledger immediately before
        // mutating either mouth cell so a delayed pickup cannot turn an old observation into
        // authority to seal an incomplete sink.
        if (sinkLedgerSatisfied(bot)) {
            if (!pocketLedgerVerified) {
                pocketLedgerVerified = true;
                noteProgress();
            }
        } else if (pocketTerminalFailure.isBlank()) {
            pocketLedgerVerified = false;
            if (lowerSealed || upperSealed) {
                rememberPocketTerminalFailure(
                        "mining_service_disposal_ledger_lost_before_seal");
            } else {
                enterPocketPhase(Phase.SETTLE_DISPOSABLE);
                return;
            }
        }
        if (!lowerSealed && !upperSealed && pocketTerminalFailure.isBlank()
                && projectedFreeSlotsAfterSealing(bot) < requiredWorkingFreeSlots(bot)) {
            pocketLedgerVerified = false;
            enterPocketPhase(Phase.DROP_DISPOSABLE);
            note = "disposal_capacity_changed_before_seal";
            return;
        }
        // Seal the feet cell first against the already-attested pocket floor, then use that newly
        // placed block as the support for the head seal. Natural ore veins can leave a valid open
        // 2x2 sink with no persistent block adjacent to the head cell; requiring a head-first
        // placement therefore makes a physically sealable pocket terminal in strict survival.
        // Both placements still form one bounded transaction in this task tick, so no entity tick
        // can admit or return an item between the two physical placements.
        for (int placements = 0; placements < 2; placements++) {
            lowerSealed = isSolidSeal(bot, pocketEntry);
            upperSealed = isSolidSeal(bot, pocketEntry.up());
            BlockPos target = !lowerSealed ? pocketEntry
                    : !upperSealed ? pocketEntry.up() : null;
            if (target == null) {
                break;
            }
            int slot = sealBlockSlot(bot);
            if (slot < 0 || InventoryAction.equipFromSlot(bot, slot) < 0) {
                String reason = "mining_service_disposal_seal_block_missing";
                if (pocketPhaseAge() > POCKET_SETTLE_LIMIT) {
                    failPocketSealRecovery(reason);
                    return;
                }
                rememberPocketTerminalFailure(reason);
                note = "waiting_for_disposal_seal_block";
                return;
            }
            ActionResult placed = BuildAction.placeBlockAt(bot, target);
            if (placed.isFailed() || !isSolidSeal(bot, target)) {
                if (pocketPhaseAge() > POCKET_SETTLE_LIMIT) {
                    failPocketSealRecovery(
                            "mining_service_disposal_seal_failed:" + placed.reason());
                }
                return;
            }
            noteProgress();
        }
        lowerSealed = isSolidSeal(bot, pocketEntry);
        upperSealed = isSolidSeal(bot, pocketEntry.up());
        if (!lowerSealed || !upperSealed) {
            note = "waiting_for_disposal_double_seal";
            return;
        }
        disposalMiner.cancel(bot);
        if (!bot.getBlockPos().equals(workFace)) {
            fail("mining_service_disposal_return_anchor_lost");
            return;
        }
        note = "disposal_pocket_sealed";
        BotLog.action(bot, "mining_service_disposal_sealed",
                "face", workFace.toShortString(),
                "entry", pocketEntry.toShortString(),
                "sink", pocketSink.toShortString());
        if (isPocketOreRetryMarker()) {
            Direction rejected = pocketRetryRejectedDirection();
            String marker = pocketTerminalFailure;
            resetPocketAttempt(bot);
            // The old pocket debt is now physically settled. Publish a decoder-valid non-pocket
            // phase before attempting the alternate selection, which may fail without creating a
            // half-checkpoint (pocket phase with no pocket identity).
            phase = Phase.PREPARE;
            noteProgress();
            startDisposalPocket(bot, requiredWorkingFreeSlots(bot), rejected, marker);
            if (state == TaskState.RUNNING) {
                BotLog.action(bot, "mining_service_disposal_ore_reroute",
                        "rejected", rejected == null ? "unknown" : rejected.asString(),
                        "selected", pocketDirection == null
                                ? "none" : pocketDirection.asString());
            }
            return;
        }
        if (!pocketTerminalFailure.isBlank()) {
            String terminalFailure = pocketTerminalFailure;
            if (retireClosedOreFailure) {
                BlockPos settledEntry = pocketEntry;
                BlockPos settledSink = pocketSink;
                // Publish the terminal receipt before clearing the pocket payload. A snapshot may
                // occur after TaskManager removes this failed task but before GoalExecutor handles
                // it, and must not turn the settled failure into a replayable PREPARE checkpoint.
                terminalFailureReceipt = terminalFailure;
                resetPocketAttempt(bot);
                phase = Phase.PREPARE;
                noteProgress();
                note = "closed_disposal_ore_failure";
                BotLog.action(bot, "mining_service_disposal_ore_closed",
                        "face", workFace.toShortString(),
                        "entry", settledEntry.toShortString(),
                        "sink", settledSink.toShortString(),
                        "reason", terminalFailure);
            }
            fail(terminalFailure);
            return;
        }
        int freeSlots = freeMainSlots(bot);
        int requiredFreeSlots = requiredWorkingFreeSlots(bot);
        if (freeSlots < requiredFreeSlots) {
            fail("mining_service_inventory_postseal_capacity_regressed:free=" + freeSlots
                    + ":required=" + requiredFreeSlots);
            return;
        }
        // Both physical mouth cells are sealed, the live ledger was re-attested above, and the
        // promised capacity is still factual. Only at this settlement boundary may the durable
        // transaction identity be forgotten. Keeping it while publishing SUPPLIES would create a
        // checkpoint that its own decoder must reject because pocket authority is phase-scoped.
        resetPocketAttempt(bot);
        phase = Phase.SUPPLIES;
        noteProgress();
    }

    private void returnToDisposalFace(AIPlayerEntity bot) {
        if (!validPocketIdentity()) {
            rememberPocketTerminalFailure("mining_service_disposal_identity_lost");
            note = "waiting_for_disposal_identity";
            return;
        }
        if (bot.getBlockPos().equals(workFace)) {
            bot.getActionPack().stopAll();
            Phase next = !pocketTerminalFailure.isBlank() || pocketLedgerVerified
                    ? Phase.SEAL_DISPOSAL_POCKET : Phase.SETTLE_DISPOSABLE;
            enterPocketPhase(next);
            note = next == Phase.SEAL_DISPOSAL_POCKET
                    ? "returned_to_seal_disposal_pocket"
                    : "returned_to_verify_disposal_ledger";
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(workFace);
            if (result.isFailed()) {
                result = bot.getActionPack().startDigPathTo(workFace);
            }
            if (result.isFailed()) {
                String reason = "mining_service_disposal_unsealed_return_failed:"
                        + result.reason();
                note = reason;
                if (pocketPhaseAge() > POCKET_SETTLE_LIMIT) {
                    // Physical reachability is unresolved, so never publish a settled-looking
                    // failure. Keep RETURN plus the complete pocket payload restartable.
                    pocketTerminalFailure = reason;
                    pocketDropCommitted = true;
                    fail(reason);
                }
            }
        }
    }

    private void sealPocketThenFail(String reason) {
        String normalized = reason == null
                ? "mining_service_disposal_failed" : reason;
        if (isPocketOreRetryMarker()) {
            pocketTerminalFailure = normalized;
        } else {
            rememberPocketTerminalFailure(normalized);
        }
        pocketDropCommitted = true;
        if (phase != Phase.SEAL_DISPOSAL_POCKET) {
            // A failure reached from an unverified generation must not inherit an older SEAL bit;
            // the terminal seal remains fail-closed but cannot relabel in-flight UUIDs as escapes.
            pocketLedgerVerified = false;
        }
        enterPocketPhase(Phase.SEAL_DISPOSAL_POCKET);
    }

    private void sealPocketThenRetryAlternative(String reason) {
        String normalized = reason == null
                ? "mining_service_disposal_ore_preserved" : reason;
        pocketTerminalFailure = POCKET_ORE_RETRY_PREFIX
                + pocketDirection.name() + ":" + normalized;
        // The mouth may already be partly open. Reuse the committed-debt recovery path until both
        // cells are physically sealed; only then may the opposite side become the active pocket.
        pocketDropCommitted = true;
        enterPocketPhase(Phase.SEAL_DISPOSAL_POCKET);
    }

    private boolean isPocketOreRetryMarker() {
        return pocketTerminalFailure != null
                && pocketTerminalFailure.startsWith(POCKET_ORE_RETRY_PREFIX);
    }

    private Direction pocketRetryRejectedDirection() {
        return pocketRetryRejectedDirection(pocketTerminalFailure);
    }

    private static Direction pocketRetryRejectedDirection(String marker) {
        if (marker == null || !marker.startsWith(POCKET_ORE_RETRY_PREFIX)) {
            return null;
        }
        int start = POCKET_ORE_RETRY_PREFIX.length();
        int end = marker.indexOf(':', start);
        if (end <= start) {
            return null;
        }
        try {
            return Direction.valueOf(marker.substring(start, end));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean validPocketLedgerIdentityAuthority(
            Phase phase,
            boolean committed,
            String failure,
            Set<UUID> entities,
            Map<Item, Integer> baseline,
            Map<Item, Integer> ledger) {
        if (phase == null || failure == null || entities == null
                || baseline == null || ledger == null) {
            return false;
        }
        boolean normalPrebaseline = phase == Phase.CAPTURE_DISPOSAL_BASELINE
                && failure.isBlank() && ledger.isEmpty();
        boolean terminalPrebaseline = (phase == Phase.RETURN_TO_DISPOSAL_FACE
                || phase == Phase.SEAL_DISPOSAL_POCKET)
                && committed && !failure.isBlank()
                && ledger.isEmpty() && !entities.isEmpty();
        return normalPrebaseline || terminalPrebaseline
                || validPocketLedgerIdentity(entities, baseline, ledger);
    }

    private static boolean validPocketLedgerIdentity(
            Set<UUID> entities, Map<Item, Integer> baseline, Map<Item, Integer> ledger) {
        if (entities == null || baseline == null || ledger == null) {
            return false;
        }
        long total = 0L;
        long minimumEntities = 0L;
        Set<Item> items = new java.util.LinkedHashSet<>(baseline.keySet());
        items.addAll(ledger.keySet());
        for (Item item : items) {
            Integer baselineCount = baseline.getOrDefault(item, 0);
            Integer ledgerCount = ledger.getOrDefault(item, 0);
            if (baselineCount < 0 || ledgerCount < 0
                    || baselineCount == 0 && ledgerCount == 0) {
                return false;
            }
            long count = (long) baselineCount + ledgerCount;
            total = saturatedPositiveAdd(total, count);
            minimumEntities = saturatedPositiveAdd(minimumEntities,
                    (count + MAX_SURVIVAL_STACK_COUNT - 1L)
                            / MAX_SURVIVAL_STACK_COUNT);
        }
        long entityCount = entities.size();
        // Every distinct item needs enough original lineage roots for its combined baseline and
        // ledger count, while every UUID must account for at least one physical item. Roots remain
        // in the set after a merge consumes them, so restart can distinguish legal lineage loss
        // from padding with provenance-free UUIDs.
        return minimumEntities <= entityCount && entityCount <= total;
    }

    private static boolean validPocketPhaseCheckpoint(
            Phase phase,
            boolean committed,
            boolean ledgerVerified,
            Set<UUID> entities,
            Map<Item, Integer> baseline,
            Map<Item, Integer> ledger,
            String failure,
            int clearIndex) {
        if (!isPocketPhase(phase) || entities == null || baseline == null
                || ledger == null || failure == null) {
            return false;
        }
        return switch (phase) {
            case OPEN_DISPOSAL_POCKET -> !committed && !ledgerVerified
                    && entities.isEmpty() && baseline.isEmpty() && ledger.isEmpty();
            case CAPTURE_DISPOSAL_BASELINE -> clearIndex == 4 && !ledgerVerified
                    && ledger.isEmpty() && committed == !entities.isEmpty();
            case DROP_DISPOSABLE -> clearIndex == 4 && !ledgerVerified;
            case SETTLE_DISPOSABLE -> clearIndex == 4
                    && !ledgerVerified && (committed || !ledger.isEmpty());
            case RETURN_TO_DISPOSAL_FACE -> committed || !ledger.isEmpty();
            case SEAL_DISPOSAL_POCKET -> committed
                    && (!failure.isBlank() || ledgerVerified)
                    && (clearIndex == 4 || !failure.isBlank());
            default -> false;
        };
    }

    private static boolean validPocketFailureCheckpoint(
            String failure,
            Phase phase,
            Direction direction,
            MiningCursor cursor,
            boolean committed,
            boolean ledgerVerified,
            Set<UUID> entities,
            Map<Item, Integer> baseline,
            Map<Item, Integer> ledger) {
        if (failure == null) {
            return false;
        }
        if (failure.isBlank()) {
            return true;
        }
        if (!failure.startsWith(POCKET_ORE_RETRY_PREFIX)) {
            return committed && (phase == Phase.SEAL_DISPOSAL_POCKET
                    || phase == Phase.RETURN_TO_DISPOSAL_FACE);
        }
        Direction rejected = pocketRetryRejectedDirection(failure);
        if (rejected == null || cursor == null || direction == null
                || ledgerVerified || entities == null || !entities.isEmpty()
                || baseline == null || !baseline.isEmpty()
                || ledger == null || !ledger.isEmpty()) {
            return false;
        }
        Direction forward = cursorForward(cursor);
        Direction clockwise = forward.rotateYClockwise();
        Direction counterclockwise = forward.rotateYCounterclockwise();
        if (phase == Phase.SEAL_DISPOSAL_POCKET
                || phase == Phase.RETURN_TO_DISPOSAL_FACE) {
            return committed && rejected == direction;
        }
        if (phase != Phase.OPEN_DISPOSAL_POCKET || committed) {
            return false;
        }
        return rejected == clockwise && direction == counterclockwise
                || rejected == counterclockwise && direction == clockwise;
    }

    private void resetPocketAttempt(AIPlayerEntity bot) {
        disposalMiner.cancel(bot);
        pocketEntry = null;
        pocketSink = null;
        pocketDirection = null;
        pocketEntityIds.clear();
        pocketLineage.clear();
        pocketDropLedger.clear();
        pocketBaseline.clear();
        pocketDropCommitted = false;
        pocketLedgerVerified = false;
        pocketBaselineStableTicks = 0;
        pocketPresealStableTicks = 0;
        pocketClearIndex = 0;
        pocketTerminalFailure = "";
    }

    private boolean canRetirePreservedOrePocketBeforeSeal(AIPlayerEntity bot) {
        return pocketTerminalFailure != null
                && pocketTerminalFailure.startsWith(POCKET_ORE_FAILURE_PREFIX)
                && pocketEntityIds.isEmpty()
                && pocketLineage.isEmpty()
                && pocketDropLedger.isEmpty()
                && pocketBaseline.isEmpty()
                && nearestUntrackedOpeningSpoil(bot).isEmpty()
                && observableSinkItems(bot).isEmpty();
    }

    /**
     * Proves that the preserved ore itself is the still-visible mouth barrier at the monotonic
     * clear frontier. This narrowly extends empty-pocket retirement for entry/index-0 and
     * entry-up/index-1 ore; sink ore retains the stricter both-mouths-open pre-seal requirement.
     */
    private boolean preservedOreStillBlocksMouthFrontier(AIPlayerEntity bot) {
        if (pocketEntry == null || pocketTerminalFailure == null
                || pocketClearIndex < 0 || pocketClearIndex > 1) {
            return false;
        }
        BlockPos barrier = pocketClearIndex == 0 ? pocketEntry : pocketEntry.up();
        if (!ObservableWorldQuery.canObserveBlock(bot, barrier)) {
            return false;
        }
        net.minecraft.block.Block block = bot.getServerWorld().getBlockState(barrier).getBlock();
        return OreScan.isOreBlock(block)
                && pocketTerminalFailure.equals(
                POCKET_ORE_FAILURE_PREFIX + Registries.BLOCK.getId(block));
    }

    private void rememberPocketTerminalFailure(String reason) {
        if (pocketTerminalFailure.isBlank()) {
            pocketTerminalFailure = reason == null || reason.isBlank()
                    ? "mining_service_disposal_failed" : reason;
        }
    }

    private void promotePocketTerminalFailure(String reason) {
        String normalized = reason == null || reason.isBlank()
                ? "mining_service_disposal_failed" : reason;
        if (isPocketOreRetryMarker()) {
            // A retry marker is routing authority, never a terminal reason. Hard/no-progress or
            // physical-return failure must revoke reroute authority before the old mouth is sealed.
            pocketTerminalFailure = normalized;
        } else {
            rememberPocketTerminalFailure(normalized);
        }
    }

    private void failPocketSealRecovery(String reason) {
        String normalized = reason == null || reason.isBlank()
                ? "mining_service_disposal_seal_failed" : reason;
        // A retry marker is routing state, not a terminal reason. Once the bounded physical seal
        // recovery itself is impossible, publish the typed seal failure instead of allowing the
        // committed-debt guard to renew the same recovery forever past the service hard window.
        pocketTerminalFailure = normalized;
        fail(normalized);
    }

    private void beginPocketTerminalRecovery(AIPlayerEntity bot, String reason) {
        promotePocketTerminalFailure(reason);
        pocketDropCommitted = true;
        disposalMiner.cancel(bot);
        if (bot.getBlockPos().equals(workFace)) {
            if (phase != Phase.SEAL_DISPOSAL_POCKET) {
                enterPocketPhase(Phase.SEAL_DISPOSAL_POCKET);
            }
        } else if (phase != Phase.RETURN_TO_DISPOSAL_FACE) {
            bot.getActionPack().stopAll();
            enterPocketPhase(Phase.RETURN_TO_DISPOSAL_FACE);
        }
    }

    private boolean hasCommittedDisposalDebt() {
        if (pocketEntry == null || pocketSink == null || pocketDirection == null) {
            return false;
        }
        // OPEN is conservative by design: a persisted clearIndex=0 cannot prove that an active
        // first break had not already mutated the world immediately before checkpoint capture.
        boolean openPocketMutation = phase == Phase.OPEN_DISPOSAL_POCKET
                || phase == Phase.CAPTURE_DISPOSAL_BASELINE
                || phase == Phase.DROP_DISPOSABLE
                || phase == Phase.SETTLE_DISPOSABLE
                || phase == Phase.RETURN_TO_DISPOSAL_FACE
                || phase == Phase.SEAL_DISPOSAL_POCKET;
        return pocketDropCommitted || !pocketDropLedger.isEmpty()
                || pocketClearIndex > 0 || openPocketMutation;
    }

    private BlockMiner.Status beginDisposalMine(AIPlayerEntity bot, BlockPos target) {
        // A service is entered precisely when renewable channel picks may be exhausted. Use the
        // ordinary best physical tool for the four bounded pocket cells; channel-only selection
        // would deadlock the repair that is meant to recreate those stone picks.
        disposalMiner.begin(bot, target);
        return disposalMiner.tick(bot);
    }

    private BlockPos nextPocketSolid(AIPlayerEntity bot) {
        BlockPos[] cells = {pocketEntry, pocketEntry.up(), pocketSink, pocketSink.up()};
        if (pocketClearIndex >= cells.length) {
            return null;
        }
        BlockPos cell = cells[Math.max(0, pocketClearIndex)];
        if (!ObservableWorldQuery.canObserveCell(bot, cell)
                && !ObservableWorldQuery.canObserveBlock(bot, cell)) {
            fail("mining_service_disposal_target_unobservable:" + cell.toShortString());
            return null;
        }
        BlockState state = bot.getServerWorld().getBlockState(cell);
        if (state.isAir()) {
            pocketClearIndex++;
            noteProgress();
            return pocketClearIndex >= cells.length ? null : nextPocketSolid(bot);
        }
        return cell;
    }

    private boolean validateOpenPocket(AIPlayerEntity bot) {
        if (!validPocketIdentity()) {
            return false;
        }
        var world = bot.getServerWorld();
        BlockPos[] open = {pocketEntry, pocketEntry.up(), pocketSink, pocketSink.up()};
        for (BlockPos cell : open) {
            if (!ObservableWorldQuery.canObserveCell(bot, cell)) {
                return false;
            }
            BlockState state = world.getBlockState(cell);
            if (!state.getCollisionShape(world, cell).isEmpty()
                    || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        BlockPos[] supports = {pocketEntry.down(), pocketSink.down(),
                pocketSink.offset(pocketDirection), pocketSink.offset(pocketDirection).up()};
        for (BlockPos support : supports) {
            if (!ObservableWorldQuery.canObserveBlock(bot, support)) {
                return false;
            }
            BlockState state = world.getBlockState(support);
            if (state.getCollisionShape(world, support).isEmpty()
                    || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        // The lower mouth is the first seal target. Its observed floor support remains outside the
        // four cleared pocket cells; once placed, it becomes the legal support for the head seal.
        return hasObservedPlacementSupport(bot, pocketEntry);
    }

    private static boolean hasObservedPlacementSupport(AIPlayerEntity bot, BlockPos target) {
        for (Direction direction : Direction.values()) {
            BlockPos support = target.offset(direction);
            if (!ObservableWorldQuery.canObserveBlock(bot, support)) {
                continue;
            }
            BlockState state = bot.getServerWorld().getBlockState(support);
            if (!state.getCollisionShape(bot.getServerWorld(), support).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean ensureAtWorkFace(AIPlayerEntity bot) {
        if (bot.getBlockPos().equals(workFace)) {
            return true;
        }
        if (isPrebaselineCaptureDebt()) {
            // A prebaseline UUID is not a post-baseline ledger identity. Keep CAPTURE authority
            // while physically returning so restart continues containment and stable-baseline
            // attestation rather than skipping directly to an empty-ledger seal.
            if (bot.getActionPack().isPathExecutorIdle()) {
                ActionResult result = bot.getActionPack().startPathTo(workFace);
                if (result.isFailed()) {
                    result = bot.getActionPack().startDigPathTo(workFace);
                }
                if (result.isFailed()) {
                    sealPocketThenFail(
                            "mining_service_disposal_prebaseline_return_failed:"
                                    + result.reason());
                    return false;
                }
            }
            note = "returning_prebaseline_capture_to_face:at="
                    + bot.getBlockPos().toShortString();
            return false;
        }
        if (hasCommittedDisposalDebt()) {
            bot.getActionPack().stopAll();
            if (pocketDropLedger.isEmpty()) {
                promotePocketTerminalFailure(
                        "mining_service_disposal_geometry_anchor_changed:phase=" + phase
                                + ":at=" + bot.getBlockPos().toShortString());
                pocketDropCommitted = true;
            }
            if (phase != Phase.RETURN_TO_DISPOSAL_FACE) {
                enterPocketPhase(Phase.RETURN_TO_DISPOSAL_FACE);
            }
            note = "returning_to_disposal_face:at=" + bot.getBlockPos().toShortString();
            return false;
        }
        fail("mining_service_disposal_anchor_changed:at=" + bot.getBlockPos().toShortString());
        return false;
    }

    private boolean isPrebaselineCaptureDebt() {
        return phase == Phase.CAPTURE_DISPOSAL_BASELINE
                && pocketDropCommitted && pocketDropLedger.isEmpty()
                && !pocketEntityIds.isEmpty();
    }

    private boolean validPocketIdentity() {
        if (miningCursor == null || workFace == null
                || pocketDirection == null || !pocketDirection.getAxis().isHorizontal()) {
            return false;
        }
        Direction forward = cursorForward(miningCursor);
        return miningCursor.face().equals(workFace)
                && (pocketDirection == forward.rotateYClockwise()
                || pocketDirection == forward.rotateYCounterclockwise())
                && pocketEntry != null && pocketSink != null
                && pocketEntry.equals(workFace.offset(pocketDirection))
                && pocketSink.equals(workFace.offset(pocketDirection, 2));
    }

    private static Direction cursorForward(MiningCursor cursor) {
        Direction[] directions = {Direction.NORTH, Direction.EAST,
                Direction.SOUTH, Direction.WEST};
        if (cursor.directionIndex() >= 0 && cursor.directionIndex() < directions.length) {
            return directions[cursor.directionIndex()];
        }
        int dx = cursor.face().getX() - cursor.origin().getX();
        int dz = cursor.face().getZ() - cursor.origin().getZ();
        if (Math.abs(dx) > Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return Direction.NORTH;
    }

    private static boolean observablePocketMouth(AIPlayerEntity bot, BlockPos entry) {
        return (ObservableWorldQuery.canObserveCell(bot, entry)
                || ObservableWorldQuery.canObserveBlock(bot, entry))
                && (ObservableWorldQuery.canObserveCell(bot, entry.up())
                || ObservableWorldQuery.canObserveBlock(bot, entry.up()));
    }

    private boolean recordPocketLedgerDrop(ItemStack stack, UUID entityId) {
        if (stack == null || stack.isEmpty() || entityId == null
                || !pocketEntityIds.contains(entityId)
                && pocketEntityIds.size() >= POCKET_CHECKPOINT_MAX_STACKS) {
            return false;
        }
        Item item = stack.getItem();
        int merged = checkedPocketLedgerCount(
                pocketDropLedger.getOrDefault(item, 0), stack.getCount());
        if (merged < 0) {
            return false;
        }
        long total = 0L;
        for (Map.Entry<Item, Integer> entry : pocketDropLedger.entrySet()) {
            total = saturatedPositiveAdd(total,
                    entry.getKey() == item ? merged : entry.getValue());
            if (total > POCKET_CHECKPOINT_MAX_ITEM_COUNT) {
                return false;
            }
        }
        if (!pocketDropLedger.containsKey(item)) {
            total = saturatedPositiveAdd(total, merged);
        }
        if (total > POCKET_CHECKPOINT_MAX_ITEM_COUNT) {
            return false;
        }
        if (pocketLineage.containsKey(entityId)) {
            return false;
        }
        pocketEntityIds.add(entityId);
        pocketLineage.put(entityId, new PocketLineage(item, stack.getCount(), true));
        pocketDropLedger.put(item, merged);
        // Any ledger extension creates a new attestation generation. A verified bit from an older
        // SEAL visit cannot authorize the new UUID or survive in a restartable DROP/SETTLE state.
        pocketLedgerVerified = false;
        return true;
    }

    static int checkedPocketLedgerCount(int currentCount, int incrementCount) {
        if (currentCount < 0 || incrementCount <= 0) {
            return -1;
        }
        long merged = (long) currentCount + incrementCount;
        return merged > POCKET_CHECKPOINT_MAX_ITEM_COUNT ? -1 : (int) merged;
    }

    private static long saturatedPositiveAdd(long left, long right) {
        long safeLeft = Math.max(0L, left);
        long safeRight = Math.max(0L, right);
        return safeLeft > Long.MAX_VALUE - safeRight
                ? Long.MAX_VALUE : safeLeft + safeRight;
    }

    private static Optional<Map<Item, Integer>> boundedCheckpointCounts(
            Map<Item, Long> counts, int maxEntries, int maxTotal) {
        if (counts == null || counts.size() > maxEntries || maxTotal < 0) {
            return Optional.empty();
        }
        Map<Item, Integer> bounded = new LinkedHashMap<>();
        long total = 0L;
        for (Map.Entry<Item, Long> entry : counts.entrySet()) {
            Item item = entry.getKey();
            Long count = entry.getValue();
            if (item == null || count == null || count <= 0L || count > maxTotal) {
                return Optional.empty();
            }
            total = saturatedPositiveAdd(total, count);
            if (total > maxTotal) {
                return Optional.empty();
            }
            bounded.put(item, count.intValue());
        }
        return Optional.of(Map.copyOf(bounded));
    }

    private Map<Item, Long> sinkItemCounts(AIPlayerEntity bot) {
        Map<Item, Long> counts = new java.util.LinkedHashMap<>();
        for (ItemEntity entity : observableSinkItems(bot)) {
            ItemStack stack = entity.getStack();
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), (long) stack.getCount(),
                        MiningServiceTask::saturatedPositiveAdd);
            }
        }
        return counts;
    }

    private java.util.List<ItemEntity> observableSinkItems(AIPlayerEntity bot) {
        if (pocketSink == null) {
            return java.util.List.of();
        }
        Box sink = pocketSinkBox();
        // Keep a small query margin so entities touching the cell boundary are still considered by
        // the spatial index, but never let that observational tolerance redefine physical custody.
        Box query = sink.expand(0.01D);
        return bot.getServerWorld().getEntitiesByClass(
                ItemEntity.class, query,
                entity -> entity.isAlive()
                        && !entity.getStack().isEmpty()
                        && fullyContains(sink, entity.getBoundingBox())
                        && ObservableWorldQuery.canObserveEntity(bot, entity));
    }

    /**
     * Finds every block drop still occupying the two-cell mouth. Those entities are not contained
     * by the sink ledger at their current position: sealing around one can push it back into pickup
     * range after the service has declared success. Tracked UUIDs are excluded here because they
     * are either still in flight before verification or handled as a provenance escape by SEAL.
     * The remaining item must not be restricted to the disposal whitelist; a natural clay, flint,
     * or other non-junk drop consumes the same promised inventory slot. Keep the player on the
     * durable work-face cell and use ordinary pickup collision to recover the spoil before taking
     * the incremental sink baseline.
     */
    private Optional<ItemEntity> nearestUntrackedOpeningSpoil(AIPlayerEntity bot) {
        return observableOpeningSpoil(bot).stream()
                .filter(entity -> !pocketEntityIds.contains(entity.getUuid()))
                .min(java.util.Comparator.comparingDouble(entity -> entity.distanceTo(bot)));
    }

    private java.util.List<ItemEntity> observableOpeningSpoil(AIPlayerEntity bot) {
        if (pocketEntry == null) {
            return java.util.List.of();
        }
        // Opening drops may bounce backward into the player's work-face cell. Looking only inside
        // the entry column can seal while that pickup-ready spoil is still outside the mouth, then
        // let it consume a promised free slot after service completion. Cover the complete factual
        // work-face-to-mouth corridor while still excluding the contained sink below.
        int minX = Math.min(workFace.getX(), pocketEntry.getX());
        int minZ = Math.min(workFace.getZ(), pocketEntry.getZ());
        int maxX = Math.max(workFace.getX(), pocketEntry.getX()) + 1;
        int maxZ = Math.max(workFace.getZ(), pocketEntry.getZ()) + 1;
        var corridor = new Box(
                minX, workFace.getY(), minZ,
                maxX, workFace.getY() + 2.0D, maxZ).expand(0.35D);
        // Server-side vanilla pickup checks extend beyond the exact block corridor.  Union that
        // factual envelope so an opening drop just behind or beside the centered player cannot be
        // invisible to the pre-seal capacity transaction and then occupy a promised slot later.
        var pickup = bot.getBoundingBox().expand(1.0D, 0.5D, 1.0D);
        var mouth = corridor.union(pickup);
        var sink = pocketSink == null ? null : pocketSinkBox();
        return bot.getServerWorld().getEntitiesByClass(
                ItemEntity.class, mouth,
                entity -> entity.isAlive()
                        && !entity.getStack().isEmpty()
                        && (sink == null || !fullyContains(sink, entity.getBoundingBox()))
                        && ObservableWorldQuery.canObserveEntity(bot, entity));
    }

    private boolean failOnTrackedLedgerEscape(AIPlayerEntity bot) {
        // Before live ledger verification a tracked entity in the mouth is merely in flight.
        // Only SEAL owns factual evidence that every tracked UUID was previously inside the sink;
        // after that boundary the same observation proves a real provenance escape.
        if (phase != Phase.SEAL_DISPOSAL_POCKET || !pocketLedgerVerified
                || pocketDropLedger.isEmpty() || pocketEntityIds.isEmpty()
                || observableOpeningSpoil(bot).stream()
                .noneMatch(entity -> pocketEntityIds.contains(entity.getUuid()))) {
            return false;
        }
        String reason = "mining_service_disposal_tracked_entity_escaped";
        if (reason.equals(pocketTerminalFailure)) {
            // The evidence breach is already durable. Let SEAL continue on the following tick;
            // re-entering the same phase here would refresh its bounded recovery age forever.
            return false;
        }
        // Collection would destroy the only durable UUID provenance and a replacement drop could
        // not prove continuity. Preserve the ledger/identity payload, seal the physical mouth, and
        // publish one exact typed failure instead of rolling authority back by aggregate count.
        // Identity escape outranks an earlier timeout/geometry reason because it invalidates the
        // factual provenance used by every successful or terminal seal path.
        pocketTerminalFailure = reason;
        pocketDropCommitted = true;
        return true;
    }

    private boolean prebaselineDropsContained(AIPlayerEntity bot) {
        if (!pocketDropLedger.isEmpty() || pocketEntityIds.isEmpty() || pocketSink == null) {
            return true;
        }
        Box sink = pocketSinkBox();
        for (UUID uuid : pocketEntityIds) {
            var entity = bot.getServerWorld().getEntity(uuid);
            if (entity instanceof ItemEntity item && item.isAlive()
                    && !fullyContains(sink, item.getBoundingBox())) {
                return false;
            }
        }
        return true;
    }

    private Box pocketSinkBox() {
        return new Box(
                pocketSink.getX(), pocketSink.getY(), pocketSink.getZ(),
                pocketSink.getX() + 1.0D, pocketSink.getY() + 2.0D,
                pocketSink.getZ() + 1.0D);
    }

    private static boolean fullyContains(Box outer, Box inner) {
        return inner.minX >= outer.minX && inner.maxX <= outer.maxX
                && inner.minY >= outer.minY && inner.maxY <= outer.maxY
                && inner.minZ >= outer.minZ && inner.maxZ <= outer.maxZ;
    }

    private void collectOpeningSpoil(AIPlayerEntity bot, ItemEntity entity) {
        FakePlayerMotion.nudgeWithinBlockToward(
                bot, workFace, entity.getPos(), 0.45D, "disposal_opening_spoil");
        note = "collecting_disposal_opening_spoil:"
                + Registries.ITEM.getId(entity.getStack().getItem());
        if (pocketPhaseAge() > POCKET_SETTLE_LIMIT) {
            sealPocketThenFail("mining_service_disposal_opening_spoil_timeout");
        }
    }

    private boolean ensureCenteredAtWorkFace(AIPlayerEntity bot) {
        double centerX = workFace.getX() + 0.5D;
        double centerZ = workFace.getZ() + 0.5D;
        double dx = bot.getX() - centerX;
        double dz = bot.getZ() - centerZ;
        if (dx * dx + dz * dz <= 1.0E-6D
                && bot.getVelocity().lengthSquared() <= 1.0E-8D
                && bot.isOnGround()) {
            return true;
        }
        return FakePlayerMotion.returnToBlockCenter(
                bot, workFace, "disposal_work_face_center");
    }

    /**
     * A completely full inventory cannot collect the two blocks mined from a reused mouth. Drop
     * one ordinary junk stack before fixing the incremental baseline; after it settles, the stable
     * baseline deliberately includes this already-contained stack and later ledger increments stay
     * exact. The committed bit makes a restart return and seal rather than abandoning the world
     * entity while baseline capture is still in progress.
     */
    private boolean dropPrebaselineDisposable(AIPlayerEntity bot, DisposalCandidate candidate) {
        LookAction.lookAt(bot, pocketSink.toCenterPos().add(0.0D, 0.25D, 0.0D));
        Optional<ItemEntity> dropped = InventoryAction.dropSlotEntity(
                bot, candidate.slot(), candidate.count());
        if (dropped.isEmpty()) {
            return false;
        }
        ItemEntity entity = dropped.orElseThrow();
        pocketEntityIds.add(entity.getUuid());
        pocketDropCommitted = true;
        noteProgress();
        note = "disposal_prebaseline_drop_in_flight:"
                + Registries.ITEM.getId(entity.getStack().getItem())
                + ":" + entity.getStack().getCount();
        BotLog.action(bot, "mining_service_disposal_prebaseline_drop",
                "entity", entity.getUuid(),
                "item", Registries.ITEM.getId(entity.getStack().getItem()),
                "count", entity.getStack().getCount(),
                "sink", pocketSink.toShortString());
        return true;
    }

    private static boolean canAcceptEntireStack(AIPlayerEntity bot, ItemStack incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return true;
        }
        int capacity = 0;
        for (ItemStack stack : bot.getInventory().main) {
            if (stack.isEmpty()) {
                capacity += incoming.getMaxCount();
            } else if (ItemStack.areItemsAndComponentsEqual(stack, incoming)) {
                capacity += Math.max(0, stack.getMaxCount() - stack.getCount());
            }
            if (capacity >= incoming.getCount()) {
                return true;
            }
        }
        return false;
    }

    private boolean sinkLedgerSatisfied(AIPlayerEntity bot) {
        Map<Item, Long> actual = sinkItemCounts(bot);
        Map<Item, Long> tracked = new java.util.LinkedHashMap<>();
        if (!attestPocketLedgerIdentity(bot, tracked)) {
            return false;
        }
        reconcilePocketBaselineFromTrackedLedger(bot, actual, tracked);
        for (Map.Entry<Item, Integer> entry : pocketDropLedger.entrySet()) {
            long expected = (long) pocketBaseline.getOrDefault(entry.getKey(), 0)
                    + entry.getValue();
            if (actual.getOrDefault(entry.getKey(), 0L) < expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * Re-establishes physical provenance from the persisted baseline + player-drop lineage.
     * Vanilla same-item merging may destroy either participant UUID, so missing roots are legal;
     * only live survivors whose UUID belongs to this lineage can cover baseline + ledger counts.
     * An unrelated aggregate sink total therefore cannot replace all known identities.
     */
    private boolean attestPocketLedgerIdentity(
            AIPlayerEntity bot, Map<Item, Long> tracked) {
        if (tracked == null) {
            return false;
        }
        tracked.clear();
        if (pocketDropLedger.isEmpty()) {
            return true;
        }
        if (pocketSink == null || pocketEntityIds.isEmpty()) {
            return false;
        }
        Box sink = pocketSinkBox();
        if (pocketLineage.isEmpty()) {
            // Legacy schema-6 checkpoints did not persist item/count roles. Keep their original
            // stricter rule: every UUID must remain live; only new lineage checkpoints can prove a
            // consumed vanilla-merge root safely.
            for (UUID uuid : pocketEntityIds) {
                var entity = bot.getServerWorld().getEntity(uuid);
                if (!(entity instanceof ItemEntity item) || !item.isAlive()
                        || !fullyContains(sink, item.getBoundingBox())
                        || !ObservableWorldQuery.canObserveEntity(bot, item)
                        || item.getStack().isEmpty()) {
                    tracked.clear();
                    return false;
                }
                tracked.merge(item.getStack().getItem(), (long) item.getStack().getCount(),
                        MiningServiceTask::saturatedPositiveAdd);
            }
            for (Map.Entry<Item, Integer> entry : pocketDropLedger.entrySet()) {
                if (tracked.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                    tracked.clear();
                    return false;
                }
            }
            return true;
        }

        Map<Item, Long> committed = new LinkedHashMap<>();
        Map<Item, Long> liveLedgerRoots = new LinkedHashMap<>();
        Map<Item, Long> positiveExcess = new LinkedHashMap<>();
        Map<Item, Long> missingBaseline = new LinkedHashMap<>();
        for (UUID uuid : pocketEntityIds) {
            PocketLineage lineage = pocketLineage.get(uuid);
            if (lineage == null) {
                tracked.clear();
                return false;
            }
            committed.merge(lineage.item(), (long) lineage.count(),
                    MiningServiceTask::saturatedPositiveAdd);
            var entity = bot.getServerWorld().getEntity(uuid);
            if (!(entity instanceof ItemEntity item) || !item.isAlive()
                    || !fullyContains(sink, item.getBoundingBox())
                    || !ObservableWorldQuery.canObserveEntity(bot, item)
                    || item.getStack().isEmpty()
                    || item.getStack().getItem() != lineage.item()) {
                if (!lineage.ledger()) {
                    missingBaseline.merge(lineage.item(), (long) lineage.count(),
                            MiningServiceTask::saturatedPositiveAdd);
                }
                continue;
            }
            int currentCount = item.getStack().getCount();
            tracked.merge(lineage.item(), (long) currentCount,
                    MiningServiceTask::saturatedPositiveAdd);
            if (lineage.ledger()) {
                liveLedgerRoots.merge(lineage.item(),
                        (long) Math.min(lineage.count(), currentCount),
                        MiningServiceTask::saturatedPositiveAdd);
            } else if (currentCount < lineage.count()) {
                missingBaseline.merge(lineage.item(),
                        (long) lineage.count() - currentCount,
                        MiningServiceTask::saturatedPositiveAdd);
            }
            if (currentCount > lineage.count()) {
                positiveExcess.merge(lineage.item(),
                        (long) currentCount - lineage.count(),
                        MiningServiceTask::saturatedPositiveAdd);
            }
        }
        for (Map.Entry<Item, Integer> entry : pocketDropLedger.entrySet()) {
            Item item = entry.getKey();
            long liveTotal = tracked.getOrDefault(item, 0L);
            long committedTotal = committed.getOrDefault(item, 0L);
            long mergeExcess = Math.max(0L,
                    positiveExcess.getOrDefault(item, 0L)
                            - missingBaseline.getOrDefault(item, 0L));
            long guaranteedLedger = saturatedPositiveAdd(
                    liveLedgerRoots.getOrDefault(item, 0L), mergeExcess);
            if (liveTotal > committedTotal || guaranteedLedger < entry.getValue()) {
                tracked.clear();
                return false;
            }
        }
        return true;
    }

    /**
     * A stable pre-drop baseline can legitimately shrink while the mouth is still open: an
     * opening-spoil entity may enter the player's ordinary pickup radius after baseline capture.
     * Never forgive the committed ledger for that race.  Rebase only when the full per-item ledger
     * remains directly attested by this task's tracked UUIDs inside the observable sink; then the
     * largest baseline still physically possible is {@code observed - ledger}.  Missing/merged-away
     * tracked identities stay fail-closed even when an unrelated aggregate happens to be large.
     */
    private void reconcilePocketBaselineFromTrackedLedger(
            AIPlayerEntity bot, Map<Item, Long> observed, Map<Item, Long> tracked) {
        if (pocketDropLedger.isEmpty()) {
            return;
        }
        for (Map.Entry<Item, Integer> entry : pocketDropLedger.entrySet()) {
            Item item = entry.getKey();
            int recorded = pocketBaseline.getOrDefault(item, 0);
            int rebased = reconciledPocketBaselineCount(
                    recorded,
                    observed.getOrDefault(item, 0L),
                    entry.getValue(),
                    tracked.getOrDefault(item, 0L));
            if (rebased >= recorded) {
                continue;
            }
            if (rebased == 0) {
                pocketBaseline.remove(item);
            } else {
                pocketBaseline.put(item, rebased);
            }
            BotLog.action(bot, "mining_service_disposal_baseline_rebased",
                    "item", Registries.ITEM.getId(item),
                    "from", recorded,
                    "to", rebased,
                    "observed", observed.getOrDefault(item, 0L),
                    "ledger", entry.getValue(),
                    "tracked", tracked.getOrDefault(item, 0L));
        }
    }

    static int reconciledPocketBaselineCount(int recordedBaseline,
                                             long observedTotal,
                                             int committedLedger,
                                             long trackedLedgerInSink) {
        int recorded = Math.max(0, recordedBaseline);
        int ledger = Math.max(0, committedLedger);
        long observed = Math.max(0L, observedTotal);
        long tracked = Math.max(0L, trackedLedgerInSink);
        if (recorded == 0 || ledger == 0 || tracked < ledger || observed < ledger) {
            return recorded;
        }
        return (int) Math.min((long) recorded, observed - ledger);
    }

    private void enterPocketPhase(Phase next) {
        if (next == Phase.DROP_DISPOSABLE || next == Phase.SETTLE_DISPOSABLE) {
            // DROP/SETTLE always describe an unverified ledger generation. This also prevents a
            // persisted SEAL observation from leaking through an opening-spoil or capacity retry.
            pocketLedgerVerified = false;
            pocketPresealStableTicks = 0;
        }
        phase = next;
        pocketPhaseStartedBudget = totalBudget();
        noteProgress();
    }

    private int pocketPhaseAge() {
        return Math.max(0, totalBudget() - pocketPhaseStartedBudget);
    }

    private static boolean isPocketPhase(Phase candidate) {
        return candidate == Phase.OPEN_DISPOSAL_POCKET
                || candidate == Phase.CAPTURE_DISPOSAL_BASELINE
                || candidate == Phase.DROP_DISPOSABLE
                || candidate == Phase.SETTLE_DISPOSABLE
                || candidate == Phase.RETURN_TO_DISPOSAL_FACE
                || candidate == Phase.SEAL_DISPOSAL_POCKET;
    }

    private void walkToDepot(AIPlayerEntity bot) {
        if (depot == null) {
            if (isRareDescentKit()) {
                fail("mining_service_mission_depot_missing");
                return;
            }
            phase = Phase.SUPPLIES;
            return;
        }
        if (canInteractWithDepot(bot, depot)) {
            if (ContainerAction.resolve(bot, depot).isEmpty()) {
                if (isRareDescentKit()) {
                    fail("mining_service_mission_depot_missing");
                    return;
                }
                note = "depot_missing";
                depot = null;
                phase = Phase.PREPARE;
                return;
            }
            bot.getActionPack().stopAll();
            phase = Phase.DEPOSIT;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = startDepotApproach(bot, depot);
            if (result.isFailed()) {
                if (isRareDescentKit()) {
                    fail("mining_service_mission_depot_unreachable:" + result.reason());
                    return;
                }
                note = result.reason();
                depot = null;
                phase = Phase.PREPARE;
            }
        }
    }

    private void deposit(AIPlayerEntity bot) {
        if (depot == null || !canInteractWithDepot(bot, depot)) {
            phase = Phase.TO_DEPOT;
            return;
        }
        if (isRareDescentKit() && !liveExactMissionDepot(bot)) {
            fail("mining_service_mission_depot_owner_mismatch");
            return;
        }
        Inventory container = ContainerAction.resolve(bot, depot).orElse(null);
        if (container == null) {
            if (isRareDescentKit()) {
                fail("mining_service_mission_depot_missing");
                return;
            }
            depot = null;
            phase = Phase.PREPARE;
            return;
        }
        int pendingBefore = pendingChannelPickaxes(bot);
        ContainerAction.TransferResult result = ContainerAction.depositOne(
                container, bot, depositFilter(bot), 64);
        if (isRareDescentKit() && result.movedAny()
                && pendingChannelPickaxes(bot) > pendingBefore) {
            fail("mining_service_mission_depot_retirement_weakened_channel_pool");
            return;
        }
        if (!result.movedAny() && "nothing_to_deposit".equals(result.reason())) {
            int emergencyBlocks = stoneLikeCount(bot);
            int surplus = Math.max(0,
                    emergencyBlocks - protectedStoneLikeForPendingCrafts(bot));
            if (surplus > 0) {
                result = ContainerAction.depositOne(container, bot,
                        stack -> stack.isOf(Items.COBBLESTONE)
                                || stack.isOf(Items.COBBLED_DEEPSLATE)
                                || stack.isOf(Items.BLACKSTONE),
                        surplus);
                if (isRareDescentKit() && result.movedAny()
                        && pendingChannelPickaxes(bot) > pendingBefore) {
                    fail("mining_service_mission_depot_retirement_weakened_channel_pool");
                    return;
                }
            }
        }
        if (result.movedAny()) {
            BotLog.action(bot, "mining_service_deposit", "count", result.count());
            noteProgress();
            return;
        }
        if (!"nothing_to_deposit".equals(result.reason())) {
            if (isRareDescentKit() && "container_full".equals(result.reason())) {
                fail("mining_service_mission_depot_capacity_depleted");
                return;
            }
            note = result.reason();
        }
        int freeSlots = freeMainSlots(bot);
        int requiredFreeSlots = requiredWorkingFreeSlots(bot);
        if (freeSlots < requiredFreeSlots) {
            fail("mining_service_inventory_reserve_depleted:free=" + freeSlots
                    + ":required=" + requiredFreeSlots);
            return;
        }
        if (isRareDescentKit()
                && usableDurability(bot, Items.STONE_PICKAXE)
                >= policy.channelToolUsableDurability()
                && !hasSafelyRetirableKitPickaxe(bot)) {
            missionDepotRetirementCompleted = true;
            note = "mission_depot_retirement_complete";
            noteProgress();
        }
        phase = Phase.SUPPLIES;
    }

    /** Proves the complete remaining mission horizon before spending any repair material. */
    private void serviceMissionSupplies(AIPlayerEntity bot) {
        if (!ensureWorkingCapacity(bot)) {
            return;
        }
        int torches = InventoryAction.countItem(bot, Items.TORCH);
        if (torches < policy.torchMinCount()) {
            if (withdrawFromDepot(bot, Items.TORCH, policy.torchMinCount() - torches)) {
                note = "withdrew_torch_reserve";
                noteProgress();
                return;
            }
            fail("mining_service_torch_reserve_depleted:have=" + torches
                    + ":required=" + policy.torchMinCount());
            return;
        }

        int food = MiningFoodReserve.units(bot.getInventory());
        if (food < policy.foodMinUnits()) {
            if (withdrawFoodReserve(bot, policy.foodMinUnits() - food)) {
                return;
            }
            fail("mining_service_food_reserve_depleted:have=" + food
                    + ":required=" + policy.foodMinUnits());
            return;
        }

        int requiredSticks = preRepairStickRequirement(bot);
        if (!ensureStickCapacity(bot, requiredSticks, currentChannelRepairStickCost(bot))) {
            return;
        }
        phase = Phase.BLOCKS;
    }

    private void serviceEmergencyBlocks(AIPlayerEntity bot) {
        if (!ensureWorkingCapacity(bot)) {
            return;
        }
        if (!requiresProtectedEmergencyBlocks()) {
            phase = Phase.TOOL;
            return;
        }
        if (isObsidianProfile()
                && InventoryAction.countItem(bot, Items.WATER_BUCKET) < 1) {
            if (withdrawFromDepot(bot, Items.WATER_BUCKET, 1)) {
                note = "withdrew_water_bucket";
                noteProgress();
                return;
            }
            fail("mining_service_water_bucket_missing");
            return;
        }
        int have = stoneLikeCount(bot);
        int required = policy.emergencyBlocksReserved();
        if (have >= required) {
            phase = Phase.TOOL;
            return;
        }
        int missing = required - have;
        int moved = withdrawStoneLikeFromDepot(bot, missing);
        if (moved > 0) {
            note = "withdrew_emergency_blocks";
            noteProgress();
            BotLog.action(bot, "mining_service_emergency_blocks_withdraw",
                    "count", moved,
                    "have", stoneLikeCount(bot),
                    "required", required);
            return;
        }
        fail("mining_service_emergency_blocks_reserve_depleted:have=" + have
                + ":required=" + required);
    }

    private void serviceTool(AIPlayerEntity bot) {
        if (child != null) {
            child.tick(bot);
            if (child.state() == TaskState.COMPLETED) {
                Item completedTool = childToolItem;
                child = null;
                childToolItem = Items.AIR;
                noteProgress();
                if (isRareDescentKit() && completedTool == Items.STONE_PICKAXE) {
                    missionDepotRetirementCompleted = false;
                    phase = Phase.TO_DEPOT;
                    note = "returning_for_post_craft_retirement";
                }
            } else if (child.state() == TaskState.FAILED || child.state() == TaskState.CANCELLED) {
                String childFailure = child.failureReason();
                child = null;
                Item exhausted = childToolItem;
                childToolItem = Items.AIR;
                if (exhausted == Items.STONE_PICKAXE) {
                    fail("mining_service_channel_tool_durability_depleted:have="
                            + usableDurability(bot, Items.STONE_PICKAXE)
                            + ":required=" + policy.channelToolUsableDurability()
                            + ":cause=" + childFailure);
                } else {
                    fail("mining_service_target_tool_durability_depleted:item="
                            + Registries.ITEM.getId(requiredPickaxe)
                            + ":have=" + targetToolUsableDurability(bot)
                            + ":required=" + policy.targetToolUsableDurability()
                            + ":cause=" + childFailure);
                }
            }
            return;
        }
        if (!ensureWorkingCapacity(bot)) {
            return;
        }
        if (!ensureCraftingTable(bot)) {
            return;
        }
        if (!ensureStickCapacity(bot, preRepairStickRequirement(bot),
                currentChannelRepairStickCost(bot))) {
            return;
        }
        int targetToolUsable = targetToolUsableDurability(bot);
        if (targetToolUsable < policy.targetToolUsableDurability()) {
            if (withdrawFromDepot(bot, requiredPickaxe, 1)) {
                note = "withdrew_target_pickaxe";
                noteProgress();
                return;
            }
            if (!ensureStickCapacity(bot, preRepairStickRequirement(bot),
                    currentChannelRepairStickCost(bot))) {
                return;
            }
            int desired = InventoryAction.countItem(bot, requiredPickaxe) + 1;
            child = new CraftTask(requiredPickaxe, desired);
            childToolItem = requiredPickaxe;
            child.start(bot);
            return;
        }
        int remainingTunnelingDurability = usableDurability(bot, Items.STONE_PICKAXE);
        int targetTunnelingDurability = policy.channelToolUsableDurability();
        if (policy.maintainsTunnelingTools()
                && remainingTunnelingDurability < targetTunnelingDurability) {
            int totalTunnelingPicks = InventoryAction.countItem(bot, Items.STONE_PICKAXE);
            int deficit = (targetTunnelingDurability - remainingTunnelingDurability
                    + STONE_PICKAXE_USABLE_DURABILITY - 1)
                    / STONE_PICKAXE_USABLE_DURABILITY;
            // The descent mission depot is the durable retirement sink for exhausted cheap
            // picks. Its stone-pickaxe entries are therefore not a replacement supply: taking
            // one back would immediately make it safely retirable again and loop forever between
            // TOOL and DEPOSIT. A freshly-created descent kit crafts its channel replacements
            // from the sealed stick/stone reserve; other service profiles retain ordinary depot
            // withdrawal semantics.
            if (!isRareDescentKit()
                    && withdrawFromDepot(bot, Items.STONE_PICKAXE, deficit)) {
                note = "withdrew_tunneling_picks";
                noteProgress();
                return;
            }
            int requiredSticks = deficit * STONE_PICKAXE_STICK_COST;
            if (!ensureStickCapacity(bot,
                    preRepairStickRequirement(bot), requiredSticks)) {
                return;
            }
            int requiredStoneLike = deficit * STONE_PICKAXE_HEAD_COST;
            int stoneLike = stoneLikeCount(bot);
            int usableStoneLike = Math.max(0, stoneLike - policy.emergencyBlocksReserved());
            if (usableStoneLike < requiredStoneLike) {
                int moved = withdrawStoneLikeFromDepot(
                        bot, requiredStoneLike - usableStoneLike);
                if (moved > 0) {
                    note = "withdrew_channel_tool_material";
                    noteProgress();
                    return;
                }
                stoneLike = stoneLikeCount(bot);
                usableStoneLike = Math.max(0,
                        stoneLike - policy.emergencyBlocksReserved());
            }
            if (usableStoneLike < requiredStoneLike) {
                fail("mining_service_channel_material_reserve_depleted:have=" + stoneLike
                        + ":reserved=" + policy.emergencyBlocksReserved()
                        + ":needed=" + requiredStoneLike);
                return;
            }
            child = new CraftTask(Items.STONE_PICKAXE, totalTunnelingPicks + deficit);
            childToolItem = Items.STONE_PICKAXE;
            child.start(bot);
            note = "replenishing_tunneling_picks";
            return;
        }
        phase = Phase.FOOD;
    }

    private boolean ensureCraftingTable(AIPlayerEntity bot) {
        if (!policy.craftingTableRequired()
                || InventoryAction.countItem(bot, Items.CRAFTING_TABLE) > 0) {
            return true;
        }
        if (withdrawFromDepot(bot, Items.CRAFTING_TABLE, 1)) {
            note = "withdrew_crafting_table";
            noteProgress();
            return false;
        }
        fail("mining_service_crafting_table_missing");
        return false;
    }

    private boolean ensureStickCapacity(AIPlayerEntity bot, int required, int currentCraftCost) {
        int have = InventoryAction.countItem(bot, Items.STICK);
        if (have >= required) {
            return true;
        }
        if (withdrawFromDepot(bot, Items.STICK, required - have)) {
            note = "withdrew_tool_sticks";
            noteProgress();
            return false;
        }
        fail("mining_service_tool_stick_reserve_depleted:have=" + have
                + ":current=" + currentCraftCost
                + ":future=" + policy.futureStickReserve()
                + ":target=" + targetToolStickReserve(bot)
                + ":required=" + required);
        return false;
    }

    private int preRepairStickRequirement(AIPlayerEntity bot) {
        return policy.futureStickReserve()
                + targetToolStickReserve(bot)
                + pendingTargetToolCraftStickCost(bot)
                + currentChannelRepairStickCost(bot);
    }

    private int protectedStickRequirement(AIPlayerEntity bot) {
        return policy.futureStickReserve() + targetToolStickReserve(bot);
    }

    private int targetToolStickReserve(AIPlayerEntity bot) {
        if (isRareDescentKit() && requiredPickaxe == Items.IRON_PICKAXE) {
            return MiningBudget.DIAMOND_STACK_TARGET_TOOL_STICKS;
        }
        if (policy.profile() != ServiceProfile.RARE_ORE_BATCH
                || requiredPickaxe != Items.IRON_PICKAXE) {
            return 0;
        }
        return Math.min(4,
                InventoryAction.countItem(bot, Items.IRON_INGOT) / 3
                        * STONE_PICKAXE_STICK_COST);
    }

    private int pendingTargetToolCraftStickCost(AIPlayerEntity bot) {
        if (!isRareDescentKit() || requiredPickaxe != Items.IRON_PICKAXE
                || targetToolUsableDurability(bot) >= policy.targetToolUsableDurability()) {
            return 0;
        }
        return STONE_PICKAXE_STICK_COST;
    }

    private int currentChannelRepairStickCost(AIPlayerEntity bot) {
        return pendingChannelPickaxes(bot) * STONE_PICKAXE_STICK_COST;
    }

    private void serviceFood(AIPlayerEntity bot) {
        if (!ensureWorkingCapacity(bot)) {
            return;
        }
        int have = MiningFoodReserve.units(bot.getInventory());
        if (have >= policy.foodMinUnits()) {
            if (!validateReady(bot)) {
                return;
            }
            phase = workFace == null || atWorkFace(bot, workFace)
                    ? Phase.DONE : Phase.RETURN_TO_FACE;
            return;
        }
        if (withdrawFoodReserve(bot, policy.foodMinUnits() - have)) {
            return;
        }
        fail("mining_service_food_reserve_depleted:have=" + have
                + ":required=" + policy.foodMinUnits());
    }

    private boolean withdrawFoodReserve(AIPlayerEntity bot, int missingUnits) {
        Inventory container = accessibleDepot(bot);
        if (container == null || missingUnits <= 0) {
            return false;
        }
        Optional<Item> reserveItem = MiningFoodReserve.firstReserveItem(container);
        if (reserveItem.isEmpty()) {
            return false;
        }
        int requested = MiningFoodReserve.itemsForUnits(reserveItem.get(), missingUnits);
        ContainerAction.TransferResult result = ContainerAction.withdrawOne(
                container, bot, reserveItem.get(), requested);
        if (!result.movedAny()) {
            return false;
        }
        note = "withdrew_food_reserve";
        noteProgress();
        BotLog.action(bot, "mining_service_food_withdraw",
                "item", reserveItem.get(), "count", result.count());
        return true;
    }

    private boolean validateReady(AIPlayerEntity bot) {
        if (isRareDescentKit() && !liveExactMissionDepot(bot)) {
            fail("mining_service_mission_depot_owner_mismatch");
            return false;
        }
        // The checkpoint flag is only a durable hint. Inventory and task persistence can be
        // observed on opposite sides of a crash, so re-attest that no safely-retirable wood/stone
        // pick has reappeared before publishing the sealed kit as ready.
        if (isRareDescentKit() && (!missionDepotRetirementCompleted
                || hasSafelyRetirableKitPickaxe(bot))) {
            missionDepotRetirementCompleted = false;
            phase = Phase.TO_DEPOT;
            note = "pending_post_craft_retirement";
            return false;
        }
        int freeSlots = freeMainSlots(bot);
        if (freeSlots < policy.freeSlotsMin()) {
            fail("mining_service_inventory_reserve_depleted:free=" + freeSlots
                    + ":required=" + policy.freeSlotsMin());
            return false;
        }
        if (targetToolUsableDurability(bot) < policy.targetToolUsableDurability()) {
            fail("mining_service_target_tool_durability_depleted:item="
                    + Registries.ITEM.getId(requiredPickaxe));
            return false;
        }
        int torches = InventoryAction.countItem(bot, Items.TORCH);
        if (torches < policy.torchMinCount()) {
            fail("mining_service_torch_reserve_depleted:have=" + torches
                    + ":required=" + policy.torchMinCount());
            return false;
        }
        int food = MiningFoodReserve.units(bot.getInventory());
        if (food < policy.foodMinUnits()) {
            fail("mining_service_food_reserve_depleted:have=" + food
                    + ":required=" + policy.foodMinUnits());
            return false;
        }
        if (policy.maintainsTunnelingTools()
                && usableDurability(bot, Items.STONE_PICKAXE)
                < policy.channelToolUsableDurability()) {
            fail("mining_service_channel_tool_durability_depleted:have="
                    + usableDurability(bot, Items.STONE_PICKAXE)
                    + ":required=" + policy.channelToolUsableDurability());
            return false;
        }
        if (isObsidianProfile()
                && InventoryAction.countItem(bot, Items.WATER_BUCKET) < 1) {
            fail("mining_service_water_bucket_missing");
            return false;
        }
        if (requiresProtectedEmergencyBlocks()
                && stoneLikeCount(bot) < policy.emergencyBlocksReserved()) {
            fail("mining_service_emergency_blocks_reserve_depleted:have="
                    + stoneLikeCount(bot) + ":required=" + policy.emergencyBlocksReserved());
            return false;
        }
        if (policy.craftingTableRequired()
                && InventoryAction.countItem(bot, Items.CRAFTING_TABLE) < 1) {
            fail("mining_service_crafting_table_missing");
            return false;
        }
        int sticks = InventoryAction.countItem(bot, Items.STICK);
        int protectedSticks = protectedStickRequirement(bot);
        if (sticks < protectedSticks) {
            fail("mining_service_tool_stick_reserve_depleted:have=" + sticks
                    + ":current=0:future=" + policy.futureStickReserve()
                    + ":target=" + targetToolStickReserve(bot)
                    + ":required=" + protectedSticks);
            return false;
        }
        return true;
    }

    private boolean isObsidianProfile() {
        return policy.profile() == ServiceProfile.OBSIDIAN_PREFLIGHT
                || policy.profile() == ServiceProfile.OBSIDIAN_8;
    }

    private boolean requiresProtectedEmergencyBlocks() {
        return isObsidianProfile()
                || policy.profile() == ServiceProfile.RARE_ORE_BATCH
                || isRareDescentKit()
                || policy.profile() == ServiceProfile.ORE_BATCH
                && !policy.maintainsTunnelingTools()
                && policy.emergencyBlocksReserved() > EMERGENCY_BLOCK_RESERVE;
    }

    private boolean isRareDescentKit() {
        return policy.profile() == ServiceProfile.RARE_DESCENT_KIT;
    }

    private boolean isRareOreBatch() {
        return policy.profile() == ServiceProfile.RARE_ORE_BATCH;
    }

    private boolean liveExactMissionDepot(AIPlayerEntity bot) {
        return depot != null && validMissionDepotIdentity()
                && missionDepotMemoryMatches(bot, serviceMissionId, depot)
                && canInteractWithDepot(bot, depot)
                && ContainerAction.resolve(bot, depot).isPresent();
    }

    private boolean withdrawFromDepot(AIPlayerEntity bot, Item item, int count) {
        Inventory container = accessibleDepot(bot);
        if (container == null) {
            return false;
        }
        return ContainerAction.withdrawOne(container, bot, item, count).movedAny();
    }

    private int withdrawStoneLikeFromDepot(AIPlayerEntity bot, int count) {
        Inventory container = accessibleDepot(bot);
        if (container == null || count <= 0) {
            return 0;
        }
        int moved = 0;
        Item[] stoneLike = {Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE};
        for (Item item : stoneLike) {
            if (moved >= count) {
                break;
            }
            ContainerAction.TransferResult result = ContainerAction.withdrawOne(
                    container, bot, item, count - moved);
            moved += result.count();
        }
        return moved;
    }

    private Inventory accessibleDepot(AIPlayerEntity bot) {
        if (depot == null || !canInteractWithDepot(bot, depot)) {
            return null;
        }
        return ContainerAction.resolve(bot, depot).orElse(null);
    }

    private void returnToFace(AIPlayerEntity bot) {
        if (workFace == null || atWorkFace(bot, workFace)) {
            bot.getActionPack().stopAll();
            phase = Phase.DONE;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(workFace);
            if (result.isFailed()) {
                result = bot.getActionPack().startDigPathTo(workFace);
            }
            if (result.isFailed()) {
                fail("mining_service_return_failed:" + result.reason());
            }
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        if (child != null && child.state() == TaskState.RUNNING) {
            child.pause(bot);
        }
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (!serviceDimension.equals(currentDimension(bot))) {
            bot.getActionPack().stopAll();
            fail("mining_service_dimension_mismatch:checkpoint="
                    + serviceDimension + ":live=" + currentDimension(bot));
            return;
        }
        if (child != null && child.state() == TaskState.PAUSED) {
            child.resume(bot);
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        if (child != null) {
            child.abort(bot);
        }
        disposalMiner.cancel(bot);
        missionDepotMiner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    public Map<String, String> checkpoint() {
        if (invalidCheckpoint
                || serviceDimension.isBlank()
                || (policy.profile() == ServiceProfile.RARE_ORE_BATCH
                && miningCursor == null)
                || (isRareDescentKit() && miningCursor == null)) {
            return Map.of();
        }
        BlockPos face = workFace == null ? BlockPos.ORIGIN : workFace;
        // Phase can transition to DONE one tick before AbstractTask publishes COMPLETED. Persist
        // that state as an already committed service boundary so the checkpoint decodes itself and
        // a restart cannot replay a zero-work completion with a non-zero hard budget.
        boolean committed = state == TaskState.COMPLETED || phase == Phase.DONE;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", String.valueOf(isRareDescentKit()
                ? RARE_DESCENT_KIT_CHECKPOINT_SCHEMA : CHECKPOINT_SCHEMA));
        values.put("work_face", encodePos(face));
        if (depot != null) {
            values.put("depot", encodePos(depot));
        }
        values.put("phase", committed ? Phase.DONE.name() : phase.name());
        values.put("channel_tools", String.valueOf(policy.maintainsTunnelingTools()));
        values.put("service_profile", policy.profile().name());
        values.put("service_mission_id", serviceMissionId);
        values.put("service_dimension", serviceDimension);
        values.put("service_target_count", String.valueOf(serviceTargetCount));
        values.put("service_boundary", String.valueOf(serviceBoundary));
        values.put("target_tool_usable", String.valueOf(policy.targetToolUsableDurability()));
        values.put("channel_tool_usable", String.valueOf(policy.channelToolUsableDurability()));
        values.put("food_min_units", String.valueOf(policy.foodMinUnits()));
        values.put("torch_min_count", String.valueOf(policy.torchMinCount()));
        values.put("free_slots_min", String.valueOf(policy.freeSlotsMin()));
        values.put("emergency_blocks_reserved", String.valueOf(policy.emergencyBlocksReserved()));
        values.put("future_stick_reserve", String.valueOf(policy.futureStickReserve()));
        values.put("crafting_table_required", String.valueOf(policy.craftingTableRequired()));
        if (isRareDescentKit()) {
            values.put("mission_depot_dimension", missionDepotDimension);
            values.put("mission_depot_clear_index", String.valueOf(missionDepotClearIndex));
            values.put("mission_depot_place_committed",
                    String.valueOf(missionDepotPlacementCommitted));
            values.put("mission_depot_retirement_completed",
                    String.valueOf(missionDepotRetirementCompleted));
            if (missionDepotDirection != null) {
                values.put("mission_depot_direction", missionDepotDirection.name());
            }
        }
        MiningCursor cursor = miningCursor;
        if (cursor != null) {
            values.put("cursor_schema", String.valueOf(cursor.schema()));
            values.put("cursor_origin", encodePos(cursor.origin()));
            values.put("cursor_face", encodePos(cursor.face()));
            values.put("cursor_direction", String.valueOf(cursor.directionIndex()));
            values.put("cursor_leg", String.valueOf(cursor.legIndex()));
            values.put("cursor_steps_left", String.valueOf(cursor.stepsLeft()));
            values.put("cursor_leg_length", String.valueOf(cursor.legLength()));
            values.put("cursor_batches", String.valueOf(cursor.completedBatches()));
        }
        if (!committed && pocketEntry != null && pocketSink != null && pocketDirection != null) {
            values.put("pocket_entry", encodePos(pocketEntry));
            values.put("pocket_sink", encodePos(pocketSink));
            values.put("pocket_direction", pocketDirection.name());
            values.put("pocket_entities", pocketEntityIds.stream()
                    .map(UUID::toString).sorted()
                    .collect(java.util.stream.Collectors.joining(",")));
            if (!pocketLineage.isEmpty()) {
                values.put("pocket_lineage", encodePocketLineage(pocketLineage));
            }
            values.put("pocket_baseline", encodeItemLedger(pocketBaseline));
            values.put("pocket_ledger", encodeItemLedger(pocketDropLedger));
            values.put("pocket_drop_committed", String.valueOf(pocketDropCommitted));
            values.put("pocket_ledger_verified", String.valueOf(pocketLedgerVerified));
            values.put("pocket_phase_started", String.valueOf(
                    Math.min(pocketPhaseStartedBudget, MAX_ELAPSED)));
            values.put("pocket_failure", pocketTerminalFailure);
            values.put("pocket_clear_index", String.valueOf(pocketClearIndex));
        }
        if (!committed && state == TaskState.FAILED && !terminalFailureReceipt.isBlank()) {
            values.put("terminal_failure", terminalFailureReceipt);
        }
        int durableBudget = committed ? 0 : Math.min(totalBudget(), MAX_ELAPSED);
        values.put("budget_used", String.valueOf(durableBudget));
        values.put("last_progress_budget", String.valueOf(
                committed ? 0 : Math.min(lastProgressBudget, durableBudget)));
        values.put("ores", targetOres.stream()
                .map(Registries.BLOCK::getId)
                .map(Identifier::toString)
                .sorted()
                .collect(java.util.stream.Collectors.joining(",")));
        return Map.copyOf(values);
    }

    /** Ores needed to reconstruct an interrupted service step before replanned mining resumes. */
    public static Set<net.minecraft.block.Block> restoredTargetOres(Map<String, String> values) {
        return inspectCheckpoint(values)
                .map(RestoreMetadata::ores)
                .orElse(Set.of());
    }

    /** Exact live inventory attestation used to omit an already-completed target-64 kit step. */
    public static boolean rareDescentKitReady(AIPlayerEntity bot) {
        if (bot == null
                || freeMainSlots(bot) < MIN_FREE_MAIN_SLOTS
                || usableDurability(bot, Items.STONE_PICKAXE)
                < 5 * STONE_PICKAXE_USABLE_DURABILITY
                || InventoryAction.countItem(bot, Items.TORCH)
                < MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES
                || MiningFoodReserve.units(bot.getInventory())
                < MiningBudget.RARE_BOOTSTRAP_FOOD
                || stoneLikeCount(bot) < MiningBudget.RARE_BOOTSTRAP_STONE_LIKE
                || InventoryAction.countItem(bot, Items.STICK)
                < MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS
                || InventoryAction.countItem(bot, Items.CRAFTING_TABLE) < 1) {
            return false;
        }
        int targetUsable = 0;
        for (ItemStack stack : bot.getInventory().main) {
            if (ToolTier.pickaxeTier(stack) >= ToolTier.IRON) {
                targetUsable += usableDurability(stack);
            }
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            if (ToolTier.pickaxeTier(stack) >= ToolTier.IRON) {
                targetUsable += usableDurability(stack);
            }
        }
        return targetUsable >= 8;
    }

    /** Mission ownership is exact, dimension-bound, currently observable, and physically live. */
    public static boolean ownedMissionDepot(AIPlayerEntity bot, String missionId) {
        if (bot == null || missionId == null || missionId.isBlank()) {
            return false;
        }
        var memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
        if (!memory.recall("mining_depot_owner")
                .filter(missionId::equals).isPresent()) {
            return false;
        }
        Optional<BlockPos> place = memory.placeIn(bot.getServerWorld(), "mining_depot");
        if (place.isEmpty()) {
            return false;
        }
        BlockPos pos = place.orElseThrow();
        boolean observable = ObservableWorldQuery.canObserveCell(bot, pos)
                || ObservableWorldQuery.canObserveBlock(bot, pos);
        return observable && ContainerAction.resolve(bot, pos).isPresent();
    }

    public static Optional<RestoreMetadata> inspectCheckpoint(Map<String, String> values) {
        return ServiceCheckpoint.decode(values, null, null, null, null, null, null)
                .map(checkpoint -> new RestoreMetadata(
                        checkpoint.ores(), checkpoint.phase() == Phase.DONE,
                        checkpoint.policy(), checkpoint.serviceMissionId(),
                        checkpoint.serviceTargetCount(), checkpoint.serviceBoundary(),
                        checkpoint.serviceDimension(), checkpoint.terminalFailure(),
                        checkpoint.workFace(),
                        checkpoint.miningCursor()));
    }

    public record RestoreMetadata(Set<net.minecraft.block.Block> ores,
                                  boolean done,
                                  ServicePolicy policy,
                                  String serviceMissionId,
                                  int serviceTargetCount,
                                  int serviceBoundary,
                                  String serviceDimension,
                                  String terminalFailure,
                                  BlockPos workFace,
                                  MiningCursor miningCursor) {
        public RestoreMetadata {
            ores = ores == null ? Set.of() : Set.copyOf(ores);
            policy = policy == null ? ServicePolicy.defaultOre(false) : policy;
            serviceMissionId = serviceMissionId == null
                    ? STANDALONE_MISSION_ID : serviceMissionId;
            serviceDimension = serviceDimension == null ? "" : serviceDimension;
            terminalFailure = terminalFailure == null ? "" : terminalFailure;
            workFace = workFace == null ? null : workFace.toImmutable();
        }
    }

    private static Optional<Set<net.minecraft.block.Block>> decodeTargetOres(Map<String, String> values) {
        if (values == null) {
            return Optional.empty();
        }
        String encoded = values.get("ores");
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        Set<net.minecraft.block.Block> ores = new java.util.LinkedHashSet<>();
        for (String id : encoded.split(",")) {
            try {
                net.minecraft.block.Block block = Registries.BLOCK
                        .getOptionalValue(Identifier.of(id)).orElse(null);
                if (block == null) {
                    return Optional.empty();
                }
                ores.add(block);
            } catch (RuntimeException invalid) {
                return Optional.empty();
            }
        }
        return ores.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(ores));
    }

    private Predicate<ItemStack> depositFilter(AIPlayerEntity bot) {
        return stack -> !targetDrops.contains(stack.getItem())
                && (!ContainerAction.isReservedTool(stack)
                || (isRareDescentKit()
                ? isSafelyRetirableKitPickaxe(bot, stack)
                : isUnusableCheapPickaxe(stack)))
                && !stack.isOf(Items.TORCH)
                && !stack.isOf(Items.STICK)
                && !stack.isOf(Items.IRON_INGOT)
                && !stack.isOf(Items.DIAMOND)
                && !stack.isOf(Items.CRAFTING_TABLE)
                && !stack.isOf(Items.BUCKET)
                && !stack.isOf(Items.WATER_BUCKET)
                && !stack.isOf(Items.COBBLESTONE)
                && !stack.isOf(Items.COBBLED_DEEPSLATE)
                && !stack.isOf(Items.BLACKSTONE)
                && !stack.contains(DataComponentTypes.FOOD);
    }

    private boolean hasSafelyRetirableKitPickaxe(AIPlayerEntity bot) {
        for (ItemStack stack : bot.getInventory().main) {
            if (isSafelyRetirableKitPickaxe(bot, stack)) {
                return true;
            }
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            if (isSafelyRetirableKitPickaxe(bot, stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSafelyRetirableKitPickaxe(AIPlayerEntity bot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.isOf(Items.WOODEN_PICKAXE)) {
            return true;
        }
        if (!stack.isOf(Items.STONE_PICKAXE)) {
            return false;
        }
        int pendingBefore = pendingChannelPickaxes(bot);
        int remainingAfter = Math.max(0,
                usableDurability(bot, Items.STONE_PICKAXE) - usableDurability(stack));
        int deficitAfter = Math.max(0,
                policy.channelToolUsableDurability() - remainingAfter);
        int pendingAfter = (deficitAfter + STONE_PICKAXE_USABLE_DURABILITY - 1)
                / STONE_PICKAXE_USABLE_DURABILITY;
        return pendingAfter <= pendingBefore;
    }

    private static int freeMainSlots(AIPlayerEntity bot) {
        int free = 0;
        for (ItemStack stack : bot.getInventory().main) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** Re-evaluated before every withdrawal/craft stage so a partial refill cannot consume the
     * final four delivery slots or strand a later multi-stack reserve transfer. */
    private boolean ensureWorkingCapacity(AIPlayerEntity bot) {
        int required = requiredWorkingFreeSlots(bot);
        if (freeMainSlots(bot) >= required) {
            return true;
        }
        // A rare batch must recover capacity at its exact work face. Returning to the descent
        // depot for ordinary spoil would turn every eight-diamond boundary into a cross-layer
        // commute. The sealed pocket transaction preserves the cursor and its exact return
        // contract; RARE_DESCENT_KIT remains depot-backed so cheap-pick retirement is unchanged.
        if (isRareOreBatch()) {
            startDisposalPocket(bot, required);
            return false;
        }
        if (depot != null) {
            phase = canInteractWithDepot(bot, depot) ? Phase.DEPOSIT : Phase.TO_DEPOT;
            return false;
        }
        if (isRareDescentKit()) {
            fail("mining_service_mission_depot_missing");
            return false;
        }
        startDisposalPocket(bot, required);
        return false;
    }

    private int requiredWorkingFreeSlots(AIPlayerEntity bot) {
        int targetToolSlots = targetToolUsableDurability(bot)
                < policy.targetToolUsableDurability() ? 1 : 0;
        int channelToolSlots = pendingChannelPickaxes(bot);
        int outputSlots = requiredPickaxe == Items.STONE_PICKAXE
                ? Math.max(targetToolSlots, channelToolSlots)
                : targetToolSlots + channelToolSlots;
        int supplySlots = additionalStackSlots(
                bot, Items.TORCH, policy.torchMinCount());
        supplySlots += additionalFoodStackSlots(bot);
        supplySlots += additionalStackSlots(
                bot, Items.STICK, preRepairStickRequirement(bot));
        int missingStoneLike = Math.max(0,
                stoneLikeRefillTarget(bot) - stoneLikeCount(bot));
        supplySlots += divideRoundUp(missingStoneLike,
                new ItemStack(Items.COBBLESTONE).getMaxCount());
        if (policy.craftingTableRequired()
                && InventoryAction.countItem(bot, Items.CRAFTING_TABLE) < 1) {
            supplySlots++;
        }
        if (isObsidianProfile()
                && InventoryAction.countItem(bot, Items.WATER_BUCKET) < 1) {
            supplySlots++;
        }
        return Math.min(bot.getInventory().main.size(),
                policy.freeSlotsMin() + outputSlots + supplySlots);
    }

    private static int additionalStackSlots(AIPlayerEntity bot, Item item, int targetCount) {
        int missing = Math.max(0, targetCount - InventoryAction.countItem(bot, item));
        if (missing <= 0) {
            return 0;
        }
        int mergeCapacity = 0;
        int maxCount = Math.max(1, new ItemStack(item).getMaxCount());
        for (ItemStack stack : bot.getInventory().main) {
            if (stack.isOf(item)) {
                mergeCapacity += Math.max(0, stack.getMaxCount() - stack.getCount());
            }
        }
        return divideRoundUp(Math.max(0, missing - mergeCapacity), maxCount);
    }

    private int additionalFoodStackSlots(AIPlayerEntity bot) {
        int missingUnits = Math.max(0,
                policy.foodMinUnits() - MiningFoodReserve.units(bot.getInventory()));
        if (missingUnits <= 0) {
            return 0;
        }
        // The depot may contain only berries (two physical items per normalized unit). Reserve
        // the conservative peak without reading a remote container through unloaded geometry.
        return divideRoundUp(missingUnits * 2,
                new ItemStack(Items.SWEET_BERRIES).getMaxCount());
    }

    private static int divideRoundUp(int value, int divisor) {
        if (value <= 0) {
            return 0;
        }
        return 1 + (value - 1) / Math.max(1, divisor);
    }

    private int protectedStoneLikeForPendingCrafts(AIPlayerEntity bot) {
        // Keep one block outside the serialized 16-block emergency policy while an ordinary
        // service rebuilds the channel-tool pool. The immediately resumed OreDig batch may need
        // that exact block to catch a foot-level target drop; allowing disposal seals to consume
        // it leaves a nominally healthy service unable to commit its first target break. Deriving
        // the allowance here preserves checkpoint compatibility and leaves rare/obsidian/handoff
        // resource horizons unchanged.
        int continuationSupport = policy.profile() == ServiceProfile.ORE_BATCH
                && policy.maintainsTunnelingTools()
                ? ORDINARY_CHANNEL_CONTINUATION_SUPPORT : 0;
        return Math.max(EMERGENCY_BLOCK_RESERVE, policy.emergencyBlocksReserved())
                + pendingStonePickaxeCrafts(bot) * STONE_PICKAXE_HEAD_COST
                + continuationSupport;
    }

    /** Capacity tracks only stone that this service can physically withdraw. Ordinary ore
     * service preserves an existing emergency pool but never replenishes it; rare/obsidian
     * profiles explicitly refill their protected reserve in the BLOCKS phase. */
    private int stoneLikeRefillTarget(AIPlayerEntity bot) {
        int protectedRefill = requiresProtectedEmergencyBlocks()
                ? policy.emergencyBlocksReserved() : 0;
        return protectedRefill
                + pendingStonePickaxeCrafts(bot) * STONE_PICKAXE_HEAD_COST;
    }

    private int pendingStonePickaxeCrafts(AIPlayerEntity bot) {
        int targetStonePick = requiredPickaxe == Items.STONE_PICKAXE
                && targetToolUsableDurability(bot) < policy.targetToolUsableDurability() ? 1 : 0;
        return Math.max(targetStonePick, pendingChannelPickaxes(bot));
    }

    private int pendingChannelPickaxes(AIPlayerEntity bot) {
        if (!policy.maintainsTunnelingTools()) {
            return 0;
        }
        int deficit = Math.max(0, policy.channelToolUsableDurability()
                - usableDurability(bot, Items.STONE_PICKAXE));
        return (deficit + STONE_PICKAXE_USABLE_DURABILITY - 1)
                / STONE_PICKAXE_USABLE_DURABILITY;
    }

    private DisposalCandidate disposableCandidate(AIPlayerEntity bot, int sealsStillRequired) {
        return disposableCandidate(bot, sealsStillRequired, Set.of(), false);
    }

    private DisposalCandidate capacityDisposableCandidate(AIPlayerEntity bot,
                                                           int sealsStillRequired) {
        return disposableCandidate(bot, sealsStillRequired, Set.of(), true);
    }

    private DisposalCandidate disposableCandidate(AIPlayerEntity bot,
                                                   int sealsStillRequired,
                                                   Item excludedItem) {
        return disposableCandidate(bot, sealsStillRequired,
                excludedItem == null ? Set.of() : Set.of(excludedItem), false);
    }

    private DisposalCandidate disposableCandidate(AIPlayerEntity bot,
                                                   int sealsStillRequired,
                                                   Set<Item> excludedItems,
                                                   boolean requireProjectedCapacityGain) {
        int requiredSeals = Math.max(0, sealsStillRequired);
        int projectedBefore = projectedFreeSlotsAfterSealing(bot);
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            ItemStack stack = bot.getInventory().main.get(slot);
            boolean disposablePick = isRareDescentKit()
                    ? isSafelyRetirableKitPickaxe(bot, stack)
                    : isUnusableCheapPickaxe(stack);
            if (stack.isEmpty() || targetDrops.contains(stack.getItem())
                    || excludedItems.contains(stack.getItem()) || !disposablePick) {
                continue;
            }
            return new DisposalCandidate(slot, stack.getCount());
        }
        int sealableBefore = availableDisposableSealBlocks(bot);
        int protectedStone = protectedStoneLikeForPendingCrafts(bot);
        int stoneLike = stoneLikeCount(bot);
        DisposalCandidate partialFallback = null;
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            ItemStack stack = bot.getInventory().main.get(slot);
            if (stack.isEmpty() || targetDrops.contains(stack.getItem())
                    || excludedItems.contains(stack.getItem())
                    || !isDisposableJunk(stack.getItem())) {
                continue;
            }
            int count = stack.getCount();
            int sealContribution = isDisposalSealItem(stack.getItem()) ? count : 0;
            if (sealableBefore - sealContribution < requiredSeals) {
                count -= requiredSeals - (sealableBefore - sealContribution);
            }
            if (isStoneLikeItem(stack.getItem())) {
                count = Math.min(count, Math.max(0, stoneLike - protectedStone));
            }
            if (count <= 0) {
                continue;
            }
            DisposalCandidate candidate = new DisposalCandidate(slot, count);
            // Inventory service is a capacity transaction, not merely an item-count reduction.
            // A protected stone stack may have disposable excess while its retained reserve still
            // occupies the same slot. Dropping that excess cannot satisfy a free-slot promise and
            // only destroys mission resources. Prefer a candidate that factually increases the
            // post-seal slot projection; DROP_DISPOSABLE rejects all non-improving fallbacks.
            if (projectedFreeSlotsAfterSealing(bot, candidate) > projectedBefore) {
                return candidate;
            }
            if (partialFallback == null) {
                partialFallback = candidate;
            }
        }
        return requireProjectedCapacityGain ? null : partialFallback;
    }

    private record DisposalCandidate(int slot, int count) {
    }

    private record SealSource(boolean offhand, int slot) {
    }

    private record PocketLineage(Item item, int count, boolean ledger) {
        private PocketLineage {
            if (item == null || item == Items.AIR || count <= 0
                    || count > MAX_SURVIVAL_STACK_COUNT) {
                throw new IllegalArgumentException("invalid_pocket_lineage");
            }
        }
    }

    private int availableDisposableSealBlocks(AIPlayerEntity bot) {
        int nonStone = 0;
        for (ItemStack stack : bot.getInventory().main) {
            if (!stack.isEmpty() && isDisposalSealItem(stack.getItem())
                    && !isStoneLikeItem(stack.getItem())) {
                nonStone += stack.getCount();
            }
        }
        // findItem promotes offhand stacks into main inventory before placement. Candidate
        // reservation and the post-seal projection must therefore use that same physical domain;
        // otherwise a valid offhand seal is ignored and a whole disposable main stack is reduced
        // to a non-slot-releasing partial drop.
        for (ItemStack stack : bot.getInventory().offHand) {
            if (!stack.isEmpty() && isDisposalSealItem(stack.getItem())
                    && !isStoneLikeItem(stack.getItem())) {
                nonStone += stack.getCount();
            }
        }
        return nonStone + Math.max(0,
                stoneLikeCount(bot) - protectedStoneLikeForPendingCrafts(bot));
    }

    private int sealBlockSlot(AIPlayerEntity bot) {
        SealSource source = selectSealSource(
                bot.getInventory().main,
                bot.getInventory().offHand,
                protectedStoneLikeForPendingCrafts(bot));
        if (source == null) {
            return -1;
        }
        return source.offhand()
                ? InventoryAction.promoteOffhandSlot(bot, source.slot()).orElse(-1)
                : source.slot();
    }

    private int projectedFreeSlotsAfterSealing(AIPlayerEntity bot) {
        return projectedFreeSlotsAfterSealing(bot, null);
    }

    private int projectedFreeSlotsAfterSealing(AIPlayerEntity bot,
                                                DisposalCandidate hypotheticalDrop) {
        int originalFreeSlots = freeMainSlots(bot);
        java.util.List<ItemStack> projectedMain = new java.util.ArrayList<>();
        java.util.List<ItemStack> projectedOffhand = new java.util.ArrayList<>();
        for (ItemStack stack : bot.getInventory().main) {
            projectedMain.add(stack.copy());
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            projectedOffhand.add(stack.copy());
        }
        if (hypotheticalDrop != null) {
            int slot = hypotheticalDrop.slot();
            if (slot < 0 || slot >= projectedMain.size()
                    || hypotheticalDrop.count() <= 0
                    || hypotheticalDrop.count() > projectedMain.get(slot).getCount()) {
                return originalFreeSlots;
            }
            projectedMain.get(slot).decrement(hypotheticalDrop.count());
        }

        int selectedSlot = bot.getInventory().selectedSlot;
        if (selectedSlot < 0 || selectedSlot >= Math.min(9, projectedMain.size())) {
            selectedSlot = 0;
        }
        int protectedStone = protectedStoneLikeForPendingCrafts(bot);
        for (int placement = 0; placement < 2; placement++) {
            SealSource source = selectSealSource(
                    projectedMain, projectedOffhand, protectedStone);
            if (source == null) {
                return originalFreeSlots;
            }
            int sourceSlot = source.slot();
            if (source.offhand()) {
                int destination = projectedFirstEmptySlot(
                        projectedMain, 0, projectedMain.size());
                if (destination < 0) {
                    destination = selectedSlot;
                }
                ItemStack moving = projectedOffhand.get(sourceSlot);
                ItemStack displaced = projectedMain.get(destination);
                projectedMain.set(destination, moving);
                projectedOffhand.set(sourceSlot, displaced);
                sourceSlot = destination;
            }
            if (sourceSlot <= 8) {
                selectedSlot = sourceSlot;
            } else {
                int hotbarSlot = projectedFirstEmptySlot(
                        projectedMain, 0, Math.min(9, projectedMain.size()));
                if (hotbarSlot < 0) {
                    hotbarSlot = selectedSlot;
                }
                ItemStack moving = projectedMain.get(sourceSlot);
                ItemStack displaced = projectedMain.get(hotbarSlot);
                projectedMain.set(hotbarSlot, moving);
                projectedMain.set(sourceSlot, displaced);
                selectedSlot = hotbarSlot;
            }
            projectedMain.get(selectedSlot).decrement(1);
        }
        return (int) projectedMain.stream().filter(ItemStack::isEmpty).count();
    }

    /**
     * Chooses the same seal source for live placement and capacity projection. Non-stone blocks
     * keep the existing material preference. When sealing must consume stone-like surplus, use
     * the smallest eligible main stack first so it can release a real delivery slot instead of
     * shaving two blocks from a full reserve stack while a one- or two-block spoil stack stays.
     */
    private static SealSource selectSealSource(java.util.List<ItemStack> main,
                                               java.util.List<ItemStack> offhand,
                                               int protectedStone) {
        for (Item candidate : DISPOSAL_SEAL_BLOCKS) {
            if (isStoneLikeItem(candidate)) {
                continue;
            }
            for (int slot = 0; slot < main.size(); slot++) {
                if (main.get(slot).isOf(candidate)) {
                    return new SealSource(false, slot);
                }
            }
            for (int slot = 0; slot < offhand.size(); slot++) {
                if (offhand.get(slot).isOf(candidate)) {
                    return new SealSource(true, slot);
                }
            }
        }
        if (projectedStoneLikeCount(main, offhand) - 1 < protectedStone) {
            return null;
        }
        int mainSlot = smallestStoneLikeStack(main);
        if (mainSlot >= 0) {
            return new SealSource(false, mainSlot);
        }
        int offhandSlot = smallestStoneLikeStack(offhand);
        return offhandSlot < 0 ? null : new SealSource(true, offhandSlot);
    }

    private static int smallestStoneLikeStack(java.util.List<ItemStack> stacks) {
        int selected = -1;
        int selectedCount = Integer.MAX_VALUE;
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (!stack.isEmpty() && isStoneLikeItem(stack.getItem())
                    && stack.getCount() < selectedCount) {
                selected = slot;
                selectedCount = stack.getCount();
            }
        }
        return selected;
    }

    private static int projectedFirstEmptySlot(java.util.List<ItemStack> stacks,
                                               int fromInclusive,
                                               int toExclusive) {
        for (int slot = Math.max(0, fromInclusive);
             slot < Math.min(stacks.size(), toExclusive); slot++) {
            if (stacks.get(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static int projectedStoneLikeCount(java.util.List<ItemStack> main,
                                               java.util.List<ItemStack> offhand) {
        int count = 0;
        for (ItemStack stack : main) {
            if (isStoneLikeItem(stack.getItem())) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : offhand) {
            if (isStoneLikeItem(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean isDisposableJunk(Item item) {
        for (Item candidate : DISPOSABLE_JUNK) {
            if (candidate == item) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDisposalSealItem(Item item) {
        for (Item candidate : DISPOSAL_SEAL_BLOCKS) {
            if (candidate == item) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStoneLikeItem(Item item) {
        return item == Items.COBBLESTONE
                || item == Items.COBBLED_DEEPSLATE
                || item == Items.BLACKSTONE;
    }

    private static boolean isSolidSeal(AIPlayerEntity bot, BlockPos pos) {
        if (pos == null || !ObservableWorldQuery.canObserveBlock(bot, pos)) {
            return false;
        }
        BlockState state = bot.getServerWorld().getBlockState(pos);
        return !state.isReplaceable()
                && !state.getCollisionShape(bot.getServerWorld(), pos).isEmpty();
    }

    private static boolean isUnusableCheapPickaxe(ItemStack stack) {
        return (stack.isOf(Items.WOODEN_PICKAXE)
                || stack.isOf(Items.STONE_PICKAXE))
                && usableDurability(stack) == 0;
    }

    public static int usableDurability(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return stack.isDamageable()
                ? Math.max(0, stack.getMaxDamage() - stack.getDamage() - 1)
                : Math.max(0, stack.getCount());
    }

    private static int usableDurability(AIPlayerEntity bot, Item item) {
        int remaining = 0;
        for (ItemStack stack : bot.getInventory().main) {
            if (stack.isOf(item)) {
                remaining += usableDurability(stack);
            }
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            if (stack.isOf(item)) {
                remaining += usableDurability(stack);
            }
        }
        return remaining;
    }

    private int targetToolUsableDurability(AIPlayerEntity bot) {
        int requiredTier = ToolTier.requiredPickaxeTier(targetOres);
        if (requiredTier <= ToolTier.NONE) {
            return Integer.MAX_VALUE;
        }
        int usable = 0;
        for (ItemStack stack : bot.getInventory().main) {
            if (ToolTier.pickaxeTier(stack) >= requiredTier) {
                usable += usableDurability(stack);
            }
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            if (ToolTier.pickaxeTier(stack) >= requiredTier) {
                usable += usableDurability(stack);
            }
        }
        return usable;
    }

    private static int stoneLikeCount(AIPlayerEntity bot) {
        return InventoryAction.countItem(bot, Items.COBBLESTONE)
                + InventoryAction.countItem(bot, Items.COBBLED_DEEPSLATE)
                + InventoryAction.countItem(bot, Items.BLACKSTONE);
    }

    private BlockPos resolveDepot(AIPlayerEntity bot) {
        BlockPos owned = resolveOwnedMissionDepotPosition(bot, serviceMissionId);
        if (isRareDescentKit()) {
            return owned;
        }
        if (policy.profile() == ServiceProfile.RARE_ORE_BATCH && owned != null) {
            return owned;
        }
        return BotMemoryStore.INSTANCE.of(bot.getUuid())
                .placeIn(bot.getServerWorld(), "depot", "home", "base", "chest")
                .map(BlockPos::toImmutable)
                .orElse(null);
    }

    private static BlockPos resolveOwnedMissionDepotPosition(
            AIPlayerEntity bot, String missionId) {
        if (bot == null || missionId == null || missionId.isBlank()) {
            return null;
        }
        var memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
        if (memory.recall("mining_depot_owner")
                .filter(missionId::equals).isEmpty()) {
            return null;
        }
        return memory.placeIn(bot.getServerWorld(), "mining_depot")
                .map(BlockPos::toImmutable)
                .orElse(null);
    }

    private Direction directionToOwnedMissionDepot(AIPlayerEntity bot, BlockPos pos) {
        if (!isRareDescentKit() || miningCursor == null || workFace == null || pos == null
                || !missionDepotMemoryMatches(bot, serviceMissionId, pos)) {
            return null;
        }
        Direction forward = cursorForward(miningCursor);
        for (Direction candidate : new Direction[]{
                forward.rotateYClockwise(), forward.rotateYCounterclockwise()}) {
            if (pos.equals(workFace.offset(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean missionDepotMemoryMatches(
            AIPlayerEntity bot, String missionId, BlockPos pos) {
        if (bot == null || missionId == null || missionId.isBlank() || pos == null) {
            return false;
        }
        var memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
        return memory.recall("mining_depot_owner").filter(missionId::equals).isPresent()
                && memory.placeIn(bot.getServerWorld(), "mining_depot")
                .filter(pos::equals).isPresent();
    }

    private static boolean missionDepotOwnedByDifferentMissionAt(
            AIPlayerEntity bot, String missionId, BlockPos pos) {
        if (bot == null || missionId == null || pos == null) {
            return false;
        }
        var memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
        Optional<BlockPos> remembered = memory.placeIn(bot.getServerWorld(), "mining_depot");
        Optional<String> owner = memory.recall("mining_depot_owner");
        return remembered.filter(pos::equals).isPresent()
                && owner.isPresent() && !missionId.equals(owner.orElseThrow());
    }

    /**
     * A remembered depot is only a navigation hint. Candidate approach positions are derived from
     * coordinates alone; no block state or block entity is inspected until {@link #canInteractWithDepot}
     * succeeds at close range.
     */
    private ActionResult startDepotApproach(AIPlayerEntity bot, BlockPos pos) {
        ActionResult lastFailure = ActionResult.failed("depot_unreachable");
        for (int offset = 0; offset < DEPOT_APPROACH_DIRECTIONS.length; offset++) {
            int index = (depotApproachIndex + offset) % DEPOT_APPROACH_DIRECTIONS.length;
            BlockPos candidate = pos.offset(DEPOT_APPROACH_DIRECTIONS[index]);
            // A depot trip is inventory service, not terrain construction. Allowing the generic
            // path's pillar/dig fallback can choose a vertical shortcut beside an otherwise open
            // chest, consume the protected stone pool and oscillate above/below the interaction
            // ring. Walk only existing collision-supported terrain; if every side is unreachable,
            // the caller safely falls back to backpack preparation instead of reshaping the route.
            ActionResult result = bot.getActionPack().startSurfacePathTo(candidate);
            if (!result.isFailed()) {
                depotApproachIndex = (index + 1) % DEPOT_APPROACH_DIRECTIONS.length;
                return result;
            }
            lastFailure = result;
        }
        depotApproachIndex = (depotApproachIndex + 1) % DEPOT_APPROACH_DIRECTIONS.length;
        return lastFailure;
    }

    private static boolean canInteractWithDepot(AIPlayerEntity bot, BlockPos pos) {
        return bot.getEyePos().squaredDistanceTo(pos.toCenterPos()) <= REACH_SQUARED
                && ObservableWorldQuery.canObserveCell(bot, pos);
    }

    private static boolean atWorkFace(AIPlayerEntity bot, BlockPos pos) {
        return bot.getBlockPos().equals(pos);
    }

    private static String currentDimension(AIPlayerEntity bot) {
        return bot.getServerWorld().getRegistryKey().getValue().toString();
    }

    private record ServiceCheckpoint(BlockPos workFace,
                                     BlockPos depot,
                                     Phase phase,
                                     Set<net.minecraft.block.Block> ores,
                                     ServicePolicy policy,
                                     String serviceMissionId,
                                     int serviceTargetCount,
                                     int serviceBoundary,
                                     String serviceDimension,
                                     int budgetUsed,
                                     int lastProgressBudget,
                                     MiningCursor miningCursor,
                                     String missionDepotDimension,
                                     Direction missionDepotDirection,
                                     int missionDepotClearIndex,
                                     boolean missionDepotPlacementCommitted,
                                     boolean missionDepotRetirementCompleted,
                                     BlockPos pocketEntry,
                                     BlockPos pocketSink,
                                     Direction pocketDirection,
                                     Set<UUID> pocketEntityIds,
                                     Map<UUID, PocketLineage> pocketLineage,
                                     Map<Item, Integer> pocketBaseline,
                                     Map<Item, Integer> pocketDropLedger,
                                     boolean pocketDropCommitted,
                                     boolean pocketLedgerVerified,
                                     int pocketPhaseStartedBudget,
                                     String pocketFailure,
                                     int pocketClearIndex,
                                     String terminalFailure) {
        private ServiceCheckpoint {
            workFace = workFace.toImmutable();
            depot = depot == null ? null : depot.toImmutable();
            phase = phase == null ? Phase.PREPARE : phase;
            ores = ores == null ? Set.of() : Set.copyOf(ores);
            policy = policy == null ? ServicePolicy.defaultOre(false) : policy;
            serviceMissionId = serviceMissionId == null
                    ? STANDALONE_MISSION_ID : serviceMissionId;
            serviceDimension = serviceDimension == null ? "" : serviceDimension;
            missionDepotDimension = missionDepotDimension == null
                    ? "" : missionDepotDimension;
            pocketEntry = pocketEntry == null ? null : pocketEntry.toImmutable();
            pocketSink = pocketSink == null ? null : pocketSink.toImmutable();
            pocketEntityIds = pocketEntityIds == null ? Set.of() : Set.copyOf(pocketEntityIds);
            pocketLineage = pocketLineage == null ? Map.of() : Map.copyOf(pocketLineage);
            pocketBaseline = pocketBaseline == null ? Map.of() : Map.copyOf(pocketBaseline);
            pocketDropLedger = pocketDropLedger == null ? Map.of() : Map.copyOf(pocketDropLedger);
            pocketFailure = pocketFailure == null ? "" : pocketFailure;
            terminalFailure = terminalFailure == null ? "" : terminalFailure;
        }

        private static Optional<ServiceCheckpoint> decode(
                Map<String, String> values,
                Set<net.minecraft.block.Block> expectedOres,
                ServicePolicy expectedPolicy,
                Integer expectedServiceBoundary,
                String expectedMissionId,
                Integer expectedTargetCount,
                MiningCursor expectedMiningCursor) {
            if (values == null || values.isEmpty()) {
                return Optional.empty();
            }
            try {
                int schema = Integer.parseInt(values.getOrDefault("schema", ""));
                boolean legacy = schema == LEGACY_CHECKPOINT_SCHEMA;
                boolean policyV1 = schema == POLICY_V1_CHECKPOINT_SCHEMA;
                boolean identityV1 = schema == IDENTITY_V1_CHECKPOINT_SCHEMA;
                boolean resourceV1 = schema == RESOURCE_HORIZON_CHECKPOINT_SCHEMA;
                boolean rareDescentKitV2 = schema == RARE_DESCENT_KIT_CHECKPOINT_SCHEMA;
                if (!legacy && !policyV1 && !identityV1 && !resourceV1
                        && schema != CHECKPOINT_SCHEMA && !rareDescentKitV2) {
                    return Optional.empty();
                }
                Set<String> required = new java.util.HashSet<>(Set.of(
                        "schema", "work_face", "phase", "channel_tools",
                        "ores", "budget_used", "last_progress_budget"));
                if (!legacy) {
                    required.addAll(Set.of("service_profile", "target_tool_usable",
                            "channel_tool_usable", "food_min_units", "free_slots_min",
                            "emergency_blocks_reserved"));
                }
                if (identityV1 || resourceV1 || schema == CHECKPOINT_SCHEMA
                        || rareDescentKitV2) {
                    required.addAll(Set.of("service_mission_id", "service_target_count",
                            "service_boundary", "future_stick_reserve",
                            "crafting_table_required"));
                }
                if (resourceV1 || schema == CHECKPOINT_SCHEMA || rareDescentKitV2) {
                    required.add("torch_min_count");
                }
                if (schema == CHECKPOINT_SCHEMA || rareDescentKitV2) {
                    required.add("service_dimension");
                }
                Set<String> allowed = new java.util.HashSet<>(required);
                allowed.add("depot");
                Set<String> pocketKeys = Set.of(
                        "pocket_entry", "pocket_sink", "pocket_direction",
                        "pocket_entities", "pocket_baseline", "pocket_ledger",
                        "pocket_drop_committed", "pocket_ledger_verified",
                        "pocket_phase_started", "pocket_failure",
                        "pocket_clear_index");
                Set<String> cursorKeys = Set.of(
                        "cursor_schema", "cursor_origin", "cursor_face", "cursor_direction",
                        "cursor_leg", "cursor_steps_left", "cursor_leg_length", "cursor_batches");
                Set<String> missionDepotKeys = Set.of(
                        "mission_depot_dimension", "mission_depot_clear_index",
                        "mission_depot_place_committed",
                        "mission_depot_retirement_completed");
                if (schema == CHECKPOINT_SCHEMA) {
                    allowed.addAll(pocketKeys);
                    allowed.add("pocket_lineage");
                    allowed.addAll(cursorKeys);
                    allowed.add("terminal_failure");
                }
                if (rareDescentKitV2) {
                    required.addAll(missionDepotKeys);
                    allowed.addAll(missionDepotKeys);
                    allowed.add("mission_depot_direction");
                    allowed.addAll(cursorKeys);
                    allowed.add("terminal_failure");
                }
                if (!values.keySet().containsAll(required)
                        || !allowed.containsAll(values.keySet())) {
                    return Optional.empty();
                }
                BlockPos face = decodePos(values.get("work_face")).orElse(null);
                if (face == null) {
                    return Optional.empty();
                }
                String serviceDimension = values.getOrDefault("service_dimension", "");
                if (schema == CHECKPOINT_SCHEMA || rareDescentKitV2) {
                    Identifier dimensionId = Identifier.tryParse(serviceDimension);
                    if (dimensionId == null
                            || !dimensionId.toString().equals(serviceDimension)) {
                        return Optional.empty();
                    }
                }
                BlockPos depot = null;
                if (values.containsKey("depot")) {
                    depot = decodePos(values.get("depot")).orElse(null);
                    if (depot == null) {
                        return Optional.empty();
                    }
                }
                Phase phase = Phase.valueOf(values.get("phase"));
                Set<net.minecraft.block.Block> ores = decodeTargetOres(values).orElse(null);
                if (ores == null) {
                    return Optional.empty();
                }
                if (expectedOres != null
                        && !OreScan.expandOreFamilies(expectedOres).equals(ores)) {
                    return Optional.empty();
                }
                String channel = values.get("channel_tools");
                if (!"true".equals(channel) && !"false".equals(channel)) {
                    return Optional.empty();
                }
                boolean channelTools = Boolean.parseBoolean(channel);
                ServicePolicy policy;
                String serviceMissionId;
                int serviceTargetCount;
                int serviceBoundary;
                if (legacy) {
                    policy = ServicePolicy.defaultOre(channelTools);
                    serviceMissionId = expectedMissionId == null
                            ? "legacy" : expectedMissionId;
                    serviceTargetCount = expectedTargetCount == null ? 0 : expectedTargetCount;
                    serviceBoundary = 0;
                } else if (policyV1) {
                    // Schema 3 did not bind an obsidian service to an exact boundary and cannot
                    // safely auto-ack one after restart. Only ordinary ore service is migratable.
                    if (ServiceProfile.valueOf(values.get("service_profile"))
                            != ServiceProfile.ORE_BATCH) {
                        return Optional.empty();
                    }
                    policy = ServicePolicy.defaultOre(channelTools);
                    if (Integer.parseInt(values.get("target_tool_usable"))
                            != policy.targetToolUsableDurability()
                            || Integer.parseInt(values.get("channel_tool_usable"))
                            != policy.channelToolUsableDurability()
                            || Integer.parseInt(values.get("food_min_units"))
                            != policy.foodMinUnits()
                            || Integer.parseInt(values.get("free_slots_min"))
                            != policy.freeSlotsMin()
                            || Integer.parseInt(values.get("emergency_blocks_reserved"))
                            != policy.emergencyBlocksReserved()) {
                        return Optional.empty();
                    }
                    serviceMissionId = expectedMissionId == null
                            ? "legacy" : expectedMissionId;
                    serviceTargetCount = expectedTargetCount == null ? 0 : expectedTargetCount;
                    serviceBoundary = 0;
                } else {
                    String table = values.get("crafting_table_required");
                    if (!"true".equals(table) && !"false".equals(table)) {
                        return Optional.empty();
                    }
                    policy = new ServicePolicy(
                            ServiceProfile.valueOf(values.get("service_profile")),
                            Integer.parseInt(values.get("target_tool_usable")),
                            Integer.parseInt(values.get("channel_tool_usable")),
                            Integer.parseInt(values.get("food_min_units")),
                            resourceV1 || schema == CHECKPOINT_SCHEMA || rareDescentKitV2
                                    ? Integer.parseInt(values.get("torch_min_count")) : 0,
                            Integer.parseInt(values.get("free_slots_min")),
                            Integer.parseInt(values.get("emergency_blocks_reserved")),
                            Integer.parseInt(values.get("future_stick_reserve")),
                            Boolean.parseBoolean(table));
                    serviceMissionId = values.get("service_mission_id");
                    serviceTargetCount = Integer.parseInt(values.get("service_target_count"));
                    serviceBoundary = Integer.parseInt(values.get("service_boundary"));
                }
                if (schema != CHECKPOINT_SCHEMA
                        && policy.profile() == ServiceProfile.RARE_ORE_BATCH) {
                    // Schema 5 predates the one-retry resource epoch and the cursor-bound
                    // boundary-zero service. Its remaining-horizon numbers cannot authorize a
                    // modern rare mission, even if a caller rewrites individual threshold fields.
                    return Optional.empty();
                }
                if ((policy.profile() == ServiceProfile.RARE_DESCENT_KIT
                        && !rareDescentKitV2)
                        || (rareDescentKitV2
                        && policy.profile() != ServiceProfile.RARE_DESCENT_KIT)) {
                    return Optional.empty();
                }
                if (policy.maintainsTunnelingTools() != channelTools
                        || !validIdentity(policy, serviceMissionId,
                        serviceTargetCount, serviceBoundary)
                        || expectedPolicy != null && !expectedPolicy.equals(policy)
                        || expectedServiceBoundary != null
                        && expectedServiceBoundary != serviceBoundary
                        || expectedMissionId != null
                        && !expectedMissionId.equals(serviceMissionId)
                        || expectedTargetCount != null
                        && expectedTargetCount != serviceTargetCount) {
                    return Optional.empty();
                }
                int budget = Integer.parseInt(values.get("budget_used"));
                int lastProgress = Integer.parseInt(values.get("last_progress_budget"));
                if (budget < 0 || budget > MAX_ELAPSED
                        || lastProgress < 0 || lastProgress > budget) {
                    return Optional.empty();
                }
                if (phase == Phase.DONE && (budget != 0 || lastProgress != 0)) {
                    return Optional.empty();
                }
                boolean hasCursor = cursorKeys.stream().anyMatch(values::containsKey);
                MiningCursor cursor = null;
                if (hasCursor) {
                    if ((schema != CHECKPOINT_SCHEMA && !rareDescentKitV2)
                            || !values.keySet().containsAll(cursorKeys)) {
                        return Optional.empty();
                    }
                    Map<String, String> cursorValues = Map.of(
                            "schema", values.get("cursor_schema"),
                            "origin", values.get("cursor_origin"),
                            "face", values.get("cursor_face"),
                            "direction", values.get("cursor_direction"),
                            "leg", values.get("cursor_leg"),
                            "steps_left", values.get("cursor_steps_left"),
                            "leg_length", values.get("cursor_leg_length"),
                            "batches", values.get("cursor_batches"));
                    cursor = MiningCursor.decode(cursorValues).orElse(null);
                    if (cursor == null || !cursor.face().equals(face)
                            || expectedMiningCursor != null
                            && !expectedMiningCursor.equals(cursor)) {
                        return Optional.empty();
                    }
                } else if (expectedMiningCursor != null) {
                    // Schema 6 binds every supplied/restored cursor even when no disposal pocket
                    // was needed. Silently accepting a cursor-less checkpoint would relocate a
                    // boundary-zero service after restart.
                    return Optional.empty();
                }
                if (schema == CHECKPOINT_SCHEMA
                        && policy.profile() == ServiceProfile.RARE_ORE_BATCH
                        && !hasCursor) {
                    return Optional.empty();
                }
                if (rareDescentKitV2 && !hasCursor) {
                    return Optional.empty();
                }
                String missionDepotDimension = "";
                Direction missionDepotDirection = null;
                int missionDepotClearIndex = 0;
                boolean missionDepotPlacementCommitted = false;
                boolean missionDepotRetirementCompleted = false;
                if (rareDescentKitV2) {
                    missionDepotDimension = values.get("mission_depot_dimension");
                    missionDepotClearIndex = Integer.parseInt(
                            values.get("mission_depot_clear_index"));
                    missionDepotPlacementCommitted = strictBoolean(
                            values.get("mission_depot_place_committed"));
                    missionDepotRetirementCompleted = strictBoolean(
                            values.get("mission_depot_retirement_completed"));
                    if (values.containsKey("mission_depot_direction")) {
                        missionDepotDirection = Direction.valueOf(
                                values.get("mission_depot_direction"));
                    }
                    boolean depotIdentity = missionDepotDimension != null
                            && !missionDepotDimension.isBlank()
                            && missionDepotClearIndex >= 0 && missionDepotClearIndex <= 2
                            && cursor != null && cursor.face().equals(face);
                    if (depot != null) {
                        Direction forward = cursorForward(cursor);
                        depotIdentity = depotIdentity && missionDepotDirection != null
                                && missionDepotDirection.getAxis().isHorizontal()
                                && (missionDepotDirection == forward.rotateYClockwise()
                                || missionDepotDirection == forward.rotateYCounterclockwise())
                                && depot.equals(face.offset(missionDepotDirection));
                    } else {
                        depotIdentity = depotIdentity && missionDepotDirection == null
                                && missionDepotClearIndex == 0
                                && !missionDepotPlacementCommitted
                                && !missionDepotRetirementCompleted;
                    }
                    boolean beforeVerify = phase == Phase.PREPARE
                            || phase == Phase.OPEN_MISSION_DEPOT_ALCOVE
                            || phase == Phase.PLACE_MISSION_DEPOT;
                    boolean requiresCommittedDepot = !beforeVerify;
                    if ((phase != Phase.PREPARE
                            && (depot == null || missionDepotDirection == null))
                            || (requiresCommittedDepot && !missionDepotPlacementCommitted)
                            || (missionDepotRetirementCompleted
                            && !missionDepotPlacementCommitted)
                            || (phase == Phase.DONE && !missionDepotRetirementCompleted)
                            || !depotIdentity) {
                        return Optional.empty();
                    }
                } else if (isMissionDepotPhase(phase)) {
                    return Optional.empty();
                }
                // pocket_lineage is optional only inside an otherwise complete schema-8 pocket.
                // Treating a lone lineage key as "no pocket" would let a terminal receipt silently
                // discard residual physical provenance during decode.
                boolean hasPocket = values.containsKey("pocket_lineage")
                        || pocketKeys.stream().anyMatch(values::containsKey);
                boolean hasTerminalFailure = values.containsKey("terminal_failure");
                String terminalFailure = values.getOrDefault("terminal_failure", "");
                BlockPos pocketEntry = null;
                BlockPos pocketSink = null;
                Direction pocketDirection = null;
                Set<UUID> pocketEntities = Set.of();
                Map<UUID, PocketLineage> pocketLineage = Map.of();
                Map<Item, Integer> pocketBaseline = Map.of();
                Map<Item, Integer> pocketLedger = Map.of();
                boolean pocketCommitted = false;
                boolean pocketLedgerVerified = false;
                int pocketPhaseStarted = 0;
                String pocketFailure = "";
                int pocketClearIndex = 0;
                if (hasPocket) {
                    if (schema != CHECKPOINT_SCHEMA
                            || !values.keySet().containsAll(pocketKeys)) {
                        return Optional.empty();
                    }
                    pocketEntry = decodePos(values.get("pocket_entry")).orElse(null);
                    pocketSink = decodePos(values.get("pocket_sink")).orElse(null);
                    pocketDirection = Direction.valueOf(values.get("pocket_direction"));
                    pocketEntities = decodeUuids(values.get("pocket_entities")).orElse(null);
                    pocketLineage = values.containsKey("pocket_lineage")
                            ? decodePocketLineage(values.get("pocket_lineage")).orElse(null)
                            : Map.of();
                    pocketBaseline = decodeItemLedger(
                            values.get("pocket_baseline"),
                            POCKET_CHECKPOINT_MAX_STACKS,
                            POCKET_CHECKPOINT_MAX_ITEM_COUNT).orElse(null);
                    pocketLedger = decodeItemLedger(
                            values.get("pocket_ledger"),
                            POCKET_CHECKPOINT_MAX_STACKS,
                            POCKET_CHECKPOINT_MAX_ITEM_COUNT).orElse(null);
                    pocketCommitted = strictBoolean(values.get("pocket_drop_committed"));
                    pocketLedgerVerified = strictBoolean(
                            values.get("pocket_ledger_verified"));
                    pocketPhaseStarted = Integer.parseInt(values.get("pocket_phase_started"));
                    pocketFailure = values.get("pocket_failure");
                    pocketClearIndex = Integer.parseInt(values.get("pocket_clear_index"));
                    boolean identity = cursor != null && pocketEntry != null && pocketSink != null
                            && pocketDirection != null && pocketDirection.getAxis().isHorizontal()
                            && cursor.face().equals(face)
                            && (pocketDirection == cursorForward(cursor).rotateYClockwise()
                            || pocketDirection == cursorForward(cursor).rotateYCounterclockwise())
                            && pocketEntry.equals(face.offset(pocketDirection))
                            && pocketSink.equals(face.offset(pocketDirection, 2))
                            && pocketEntities != null && pocketBaseline != null
                            && pocketLineage != null && pocketLedger != null
                            && pocketFailure != null
                            && validPocketLineage(
                            pocketEntities, pocketLineage, pocketBaseline, pocketLedger)
                            && (pocketLedger.isEmpty() || !pocketEntities.isEmpty())
                            && (pocketBaseline.isEmpty() || !pocketEntities.isEmpty()
                            || phase == Phase.CAPTURE_DISPOSAL_BASELINE)
                            && pocketEntities.size() <= POCKET_CHECKPOINT_MAX_STACKS
                            && validPocketLedgerIdentityAuthority(
                            phase, pocketCommitted, pocketFailure,
                            pocketEntities, pocketBaseline, pocketLedger)
                            && pocketClearIndex >= 0 && pocketClearIndex <= 4
                            && (!pocketLedgerVerified || pocketCommitted)
                            && pocketPhaseStarted >= 0 && pocketPhaseStarted <= budget
                            && validPocketPhaseCheckpoint(
                            phase, pocketCommitted, pocketLedgerVerified,
                            pocketEntities, pocketBaseline, pocketLedger,
                            pocketFailure, pocketClearIndex);
                    boolean validFailure = validPocketFailureCheckpoint(
                            pocketFailure, phase, pocketDirection, cursor,
                            pocketCommitted, pocketLedgerVerified,
                            pocketEntities, pocketBaseline, pocketLedger);
                    if (!identity || !validFailure) {
                        return Optional.empty();
                    }
                } else if (isPocketPhase(phase)) {
                    return Optional.empty();
                }
                if (hasTerminalFailure && (!hasCursor || cursor == null
                        || !cursor.face().equals(face))
                        || !validTerminalFailureReceipt(
                        terminalFailure, hasTerminalFailure, phase, hasPocket)) {
                    return Optional.empty();
                }
                return Optional.of(new ServiceCheckpoint(
                        face, depot, phase, ores, policy, serviceMissionId,
                        serviceTargetCount, serviceBoundary, serviceDimension,
                        budget, lastProgress, cursor,
                        missionDepotDimension, missionDepotDirection,
                        missionDepotClearIndex, missionDepotPlacementCommitted,
                        missionDepotRetirementCompleted,
                        pocketEntry, pocketSink,
                        pocketDirection, pocketEntities, pocketLineage,
                        pocketBaseline, pocketLedger,
                        pocketCommitted,
                        pocketLedgerVerified,
                        pocketPhaseStarted, pocketFailure,
                        pocketClearIndex, terminalFailure));
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }
    }

    /** Strict semantic gate shared by terminal receipts and durable replay guards. */
    public static boolean validTerminalFailureReason(String failure) {
        if (failure == null || failure.isBlank() || failure.length() > 256
                || !failure.startsWith(POCKET_ORE_FAILURE_PREFIX)
                || failure.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        String encodedBlock = failure.substring(POCKET_ORE_FAILURE_PREFIX.length());
        try {
            Identifier id = Identifier.of(encodedBlock);
            net.minecraft.block.Block block = Registries.BLOCK
                    .getOptionalValue(id).orElse(null);
            return block != null && id.toString().equals(encodedBlock)
                    && OreScan.isOreBlock(block);
        } catch (RuntimeException invalidIdentifier) {
            return false;
        }
    }

    /** Strict decoder gate for the sole settled terminal receipt currently emitted by service. */
    private static boolean validTerminalFailureReceipt(String failure,
                                                       boolean present,
                                                       Phase phase,
                                                       boolean hasPocket) {
        if (!present) {
            return failure != null && failure.isEmpty();
        }
        return phase == Phase.PREPARE && !hasPocket
                && validTerminalFailureReason(failure);
    }

    private static String encodePos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Optional<BlockPos> decodePos(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = value.split(",");
            if (parts.length != 3) {
                return Optional.empty();
            }
            return Optional.of(new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    static String encodeItemLedger(Map<Item, Integer> ledger) {
        if (ledger == null) {
            throw new IllegalStateException("invalid_pocket_ledger:null");
        }
        if (ledger.isEmpty()) {
            return "";
        }
        if (ledger.size() > POCKET_CHECKPOINT_MAX_STACKS) {
            throw new IllegalStateException("invalid_pocket_ledger:entries");
        }
        long total = 0L;
        for (Map.Entry<Item, Integer> entry : ledger.entrySet()) {
            if (entry.getKey() == null || entry.getKey() == Items.AIR
                    || entry.getValue() == null || entry.getValue() <= 0
                    || entry.getValue() > POCKET_CHECKPOINT_MAX_ITEM_COUNT) {
                throw new IllegalStateException("invalid_pocket_ledger:entry");
            }
            total = saturatedPositiveAdd(total, entry.getValue());
            if (total > POCKET_CHECKPOINT_MAX_ITEM_COUNT) {
                throw new IllegalStateException("invalid_pocket_ledger:total");
            }
        }
        return ledger.entrySet().stream()
                .sorted(java.util.Comparator.comparing(
                        entry -> Registries.ITEM.getId(entry.getKey()).toString()))
                .map(entry -> Registries.ITEM.getId(entry.getKey()) + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static String encodePocketLineage(Map<UUID, PocketLineage> lineage) {
        if (lineage == null || lineage.isEmpty()
                || lineage.size() > POCKET_CHECKPOINT_MAX_STACKS) {
            throw new IllegalStateException("invalid_pocket_lineage");
        }
        return lineage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "@"
                        + Registries.ITEM.getId(entry.getValue().item()) + "@"
                        + entry.getValue().count() + "@"
                        + (entry.getValue().ledger() ? "L" : "B"))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static Optional<Map<UUID, PocketLineage>> decodePocketLineage(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Map<UUID, PocketLineage> decoded = new LinkedHashMap<>();
        try {
            for (String entry : value.split(";", -1)) {
                String[] parts = entry.split("@", -1);
                if (parts.length != 4 || parts[0].isBlank() || parts[1].isBlank()
                        || !"L".equals(parts[3]) && !"B".equals(parts[3])) {
                    return Optional.empty();
                }
                UUID uuid = UUID.fromString(parts[0]);
                Item item = Registries.ITEM.getOptionalValue(
                        Identifier.of(parts[1])).orElse(null);
                PocketLineage root = new PocketLineage(
                        item, Integer.parseInt(parts[2]), "L".equals(parts[3]));
                if (decoded.putIfAbsent(uuid, root) != null
                        || decoded.size() > POCKET_CHECKPOINT_MAX_STACKS) {
                    return Optional.empty();
                }
            }
            return Optional.of(Map.copyOf(decoded));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static boolean validPocketLineage(Set<UUID> entities,
                                               Map<UUID, PocketLineage> lineage,
                                               Map<Item, Integer> baseline,
                                               Map<Item, Integer> ledger) {
        if (entities == null || lineage == null || baseline == null || ledger == null) {
            return false;
        }
        if (lineage.isEmpty()) {
            return true;
        }
        if (!lineage.keySet().equals(entities)) {
            return false;
        }
        Map<Item, Long> baselineRoots = new LinkedHashMap<>();
        Map<Item, Long> ledgerRoots = new LinkedHashMap<>();
        for (PocketLineage root : lineage.values()) {
            (root.ledger() ? ledgerRoots : baselineRoots).merge(
                    root.item(), (long) root.count(), MiningServiceTask::saturatedPositiveAdd);
        }
        for (Map.Entry<Item, Integer> entry : ledger.entrySet()) {
            if (ledgerRoots.getOrDefault(entry.getKey(), 0L)
                    != entry.getValue().longValue()) {
                return false;
            }
        }
        if (ledgerRoots.keySet().stream().anyMatch(item -> !ledger.containsKey(item))) {
            return false;
        }
        for (Map.Entry<Item, Integer> entry : baseline.entrySet()) {
            if (baselineRoots.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static Optional<Map<Item, Integer>> decodeItemLedger(
            String value, int maxEntries, int maxTotal) {
        if (value == null || maxEntries < 0 || maxTotal < 0) {
            return Optional.empty();
        }
        if (value.isBlank()) {
            return Optional.of(Map.of());
        }
        Map<Item, Integer> decoded = new java.util.LinkedHashMap<>();
        long total = 0L;
        try {
            for (String entry : value.split(";", -1)) {
                String[] parts = entry.split("=", -1);
                if (parts.length != 2 || parts[0].isBlank()) {
                    return Optional.empty();
                }
                Item item = Registries.ITEM.getOptionalValue(Identifier.of(parts[0])).orElse(null);
                int count = Integer.parseInt(parts[1]);
                if (item == null || item == Items.AIR || count <= 0 || count > maxTotal
                        || decoded.putIfAbsent(item, count) != null) {
                    return Optional.empty();
                }
                if (decoded.size() > maxEntries) {
                    return Optional.empty();
                }
                total = saturatedPositiveAdd(total, count);
                if (total > maxTotal) {
                    return Optional.empty();
                }
            }
            return Optional.of(Map.copyOf(decoded));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<Set<UUID>> decodeUuids(String value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.isBlank()) {
            return Optional.of(Set.of());
        }
        Set<UUID> decoded = new java.util.LinkedHashSet<>();
        try {
            for (String entry : value.split(",", -1)) {
                if (!decoded.add(UUID.fromString(entry))) {
                    return Optional.empty();
                }
            }
            return Optional.of(Set.copyOf(decoded));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static boolean strictBoolean(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("invalid_boolean:" + value);
        }
        return Boolean.parseBoolean(value);
    }
}
