package io.github.zoyluo.aibot.mining;

/**
 * Quota-driven expedition budget. Values are deliberately bounded: the first trip carries enough
 * to survive and make measurable progress, while later batches consume a bounded local service
 * horizon instead of turning a 64-item request into an unbounded bootstrap chain.
 */
public record MiningBudget(
        int targetCount,
        int batchSize,
        int batchCount,
        int initialPickaxes,
        int tunnelingPickaxes,
        int ordinaryChannelPickaxes,
        int ordinaryChannelRepairPickaxes,
        int ordinaryChannelRepairSticks,
        int ordinaryChannelRepairStoneLike,
        int spareToolIngots,
        int spareToolSticks,
        int torchTarget,
        int cookedFoodTarget,
        int emergencyBlocks
) {
    public static final int EXPEDITION_THRESHOLD = 8;
    /** Eight-item probes stay lightweight; twelve or more ordinary ore items own a channel pool. */
    public static final int ORDINARY_CHANNEL_EXPEDITION_THRESHOLD = 12;
    /** Four fresh stone picks retain their last points and provide 520 usable branch breaks. */
    public static final int TUNNELING_SERVICE_TARGET = 4;
    /** Seven fresh stone picks provide 910 usable breaks for one bounded rare-ore epoch. */
    public static final int RARE_TUNNELING_SERVICE_TARGET = 7;
    /** One bounded ordinary expedition starts each dependency with an independent fresh pool. */
    public static final int ORDINARY_CHANNEL_INITIAL_PICKAXES =
            TUNNELING_SERVICE_TARGET;
    public static final int STONE_PICKAXE_USABLE_DURABILITY = 130;
    public static final int STONE_PICKAXE_STICK_COST = 2;
    public static final int STONE_PICKAXE_HEAD_COST = 3;
    /** Every ordinary OreDig batch owns at most one physical channel-tool resupply. */
    public static final int MAX_ORDINARY_CHANNEL_RESUPPLIES_PER_BATCH = 1;
    /** One bounded rare-ore batch may spend at most forty torches. */
    public static final int RARE_BATCH_TORCH_LIMIT = 40;
    /** Every rare-ore batch owns one, and only one, full resource retry cushion. */
    public static final int MAX_RARE_RESOURCE_RETRIES_PER_BATCH = 1;
    /** Compatibility name used by persisted epoch checks; the value is per open batch. */
    public static final int MAX_RARE_RESOURCE_RETRIES =
            MAX_RARE_RESOURCE_RETRIES_PER_BATCH;
    public static final int RARE_RESOURCE_EPOCHS_PER_BATCH =
            1 + MAX_RARE_RESOURCE_RETRIES_PER_BATCH;
    /** Rare expeditions batch by eight target items; margin pools derive from this size. */
    public static final int RARE_BATCH_SIZE = 8;
    /**
     * 36 格主背包是硬物理墙:pinned 64 任务的 bootstrap 携带(640 火把/228 木棍/72 食物
     * /双 pool 石材/镐具)已把 boundary service 的工作格余量压到两格。每个 margin epoch 的
     * 火把+木棍+食物约多占一格,超过两个 epoch 会让 rare service 的
     * requiredWorkingFreeSlots 合约在数学上不可满足(margin 物资不可弃置)。
     */
    public static final int RARE_MISSION_EPOCH_MARGIN_CAP = 2;
    /**
     * F2 阶段3:钻石产量是随机的,单批 2 个 epoch 有 10–30% 概率挖不满配额,把整个任务
     * 成功率压到 ~17–66%。任务级 margin 池让整个任务最多再抽 min(batchCount/2, cap) 个
     * 有界 epoch(64 目标 → 2),粮食/火把/木棍在 bootstrap 一次性预置,不会变成无界续期。
     */
    public static final int DIAMOND_STACK_EPOCH_MARGIN = 2;
    public static final int RARE_EPOCH_FOOD_ALLOWANCE = 4;
    public static final int RARE_MISSION_FOOD_BUFFER = 8;
    /** Pinned 64-diamond mission food: eighteen funded epochs plus an eight-unit safety buffer. */
    public static final int RARE_BOOTSTRAP_FOOD =
            (8 * RARE_RESOURCE_EPOCHS_PER_BATCH + DIAMOND_STACK_EPOCH_MARGIN)
                    * RARE_EPOCH_FOOD_ALLOWANCE
                    + RARE_MISSION_FOOD_BUFFER;
    public static final int RARE_SERVICE_FOOD_FLOOR = 4;
    public static final int RARE_RETRY_TORCHES = 40;
    /** Sixteen regular plus two margin forty-torch epochs; descent is added separately. */
    public static final int DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES =
            (8 * RARE_RESOURCE_EPOCHS_PER_BATCH + DIAMOND_STACK_EPOCH_MARGIN)
                    * RARE_BATCH_TORCH_LIMIT;
    public static final int DIAMOND_STACK_CHANNEL_REPAIR_STICKS =
            (8 * RARE_RESOURCE_EPOCHS_PER_BATCH + DIAMOND_STACK_EPOCH_MARGIN)
                    * RARE_TUNNELING_SERVICE_TARGET
                    * STONE_PICKAXE_STICK_COST;
    public static final int DIAMOND_STACK_TARGET_TOOL_STICKS = 4;
    /** Eighteen seven-pick channel pools plus two replacement iron-pick handles. */
    public static final int DIAMOND_STACK_BOOTSTRAP_STICKS =
            DIAMOND_STACK_CHANNEL_REPAIR_STICKS + DIAMOND_STACK_TARGET_TOOL_STICKS;
    /** Physical barricade/disposal reserve that no mining-tool repair may consume. */
    public static final int EMERGENCY_STONE_LIKE = 16;
    /** Seven stone-pick heads released only after the open batch spends its resource retry. */
    public static final int RARE_RETRY_CHANNEL_STONE_LIKE =
            RARE_TUNNELING_SERVICE_TARGET * STONE_PICKAXE_HEAD_COST;
    /** Emergency blocks plus the still-sealed stone-pick retry reserve. */
    public static final int RARE_SERVICE_PROTECTED_STONE_LIKE =
            EMERGENCY_STONE_LIKE + RARE_RETRY_CHANNEL_STONE_LIKE;
    /** Two seven-pick pools, emergency reserve, and two disposal-seal blocks. */
    public static final int RARE_BOOTSTRAP_STONE_LIKE =
            2 * RARE_TUNNELING_SERVICE_TARGET * STONE_PICKAXE_HEAD_COST
                    + EMERGENCY_STONE_LIKE + 2;
    /** One four-pick pool may be consumed by bootstrap ore/water/diamond channel recovery. */
    public static final int OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STICKS =
            TUNNELING_SERVICE_TARGET * 2;
    public static final int OBSIDIAN_BOOTSTRAP_CHANNEL_RETRY_STONE_LIKE =
            TUNNELING_SERVICE_TARGET * 3;
    /** Prepared short-run floor; long expeditions scale per service segment below. */
    public static final int OBSIDIAN_EXPEDITION_FOOD_FLOOR = 8;
    public static final int OBSIDIAN_FOOD_PER_SERVICE_SEGMENT = 4;
    public static final int OBSIDIAN_FOOD_BUFFER = 4;
    public static final int OBSIDIAN_SERVICE_SEGMENT_BLOCKS = 8;

    /**
     * Cooked-food carry for one obsidian expedition. The legacy flat 8-unit reserve only covered
     * prepared short runs: a 64-block mission crosses eight service segments at a depth where
     * hunting and depot withdrawal are both impossible, so exhausting food is an unrecoverable
     * {@code mining_service_food_reserve_depleted}. Four units per 8-block segment plus a fixed
     * buffer keeps a 64-target ration at 36 units — still a single inventory slot.
     */
    public static int obsidianExpeditionFoodTarget(int missionTarget) {
        int segments = Math.max(1,
                ceilDiv(Math.max(1, missionTarget), OBSIDIAN_SERVICE_SEGMENT_BLOCKS));
        return Math.max(OBSIDIAN_EXPEDITION_FOOD_FLOOR,
                segments * OBSIDIAN_FOOD_PER_SERVICE_SEGMENT + OBSIDIAN_FOOD_BUFFER);
    }

    /**
     * Initial surface ration: the mission quota is staged, not front-loaded. The plan tops up
     * to {@link #obsidianExpeditionFoodTarget(int)} only after the water expedition, once the
     * bot has physically moved to a second herd — nine consecutive hunts at the spawn herd
     * exhausted it on every measured public seed (replan_same_step:hunt_no_progress). Small
     * missions whose full target already fits keep the single-gate shape.
     */
    public static int obsidianExpeditionInitialFoodTarget(int missionTarget) {
        return Math.min(obsidianExpeditionFoodTarget(missionTarget),
                OBSIDIAN_EXPEDITION_FOOD_FLOOR + OBSIDIAN_FOOD_BUFFER);
    }

    public MiningBudget {
        targetCount = Math.max(1, targetCount);
        batchSize = Math.max(1, batchSize);
        batchCount = Math.max(1, batchCount);
        initialPickaxes = Math.max(1, initialPickaxes);
        tunnelingPickaxes = Math.max(0, tunnelingPickaxes);
        ordinaryChannelPickaxes = Math.max(0, ordinaryChannelPickaxes);
        ordinaryChannelRepairPickaxes = Math.max(0, ordinaryChannelRepairPickaxes);
        ordinaryChannelRepairSticks = Math.max(0, ordinaryChannelRepairSticks);
        ordinaryChannelRepairStoneLike = Math.max(0, ordinaryChannelRepairStoneLike);
        spareToolIngots = Math.max(0, spareToolIngots);
        spareToolSticks = Math.max(0, spareToolSticks);
        torchTarget = Math.max(0, torchTarget);
        cookedFoodTarget = Math.max(0, cookedFoodTarget);
        emergencyBlocks = Math.max(0, emergencyBlocks);
    }

    public static MiningBudget forQuota(int targetCount, boolean rareOre, int pickaxeTier) {
        int target = Math.max(1, targetCount);
        int batch = rareOre ? RARE_BATCH_SIZE : 16;
        int batches = ceilDiv(target, batch);
        // Rare-ore tools should break the target blocks, not pay the bulk tunnelling bill. A stack
        // therefore carries three iron picks at most and uses cheap stone picks for branch growth.
        int picks = target < EXPEDITION_THRESHOLD ? 1
                : rareOre
                ? Math.min(3, Math.max(2, 1 + ceilDiv(target, 32)))
                : Math.min(5, Math.max(2, 1 + ceilDiv(target, 16)));
        int tunnelingPicks = target >= EXPEDITION_THRESHOLD && rareOre
                && pickaxeTier == ToolTier.IRON
                // Five covers a worst-case surface→Y-59 staircase (~3 rock blocks per level)
                // plus one working pick. Rare channel pools are rebuilt at each service checkpoint.
                ? 5 : 0;
        boolean ordinaryExpedition = target >= ORDINARY_CHANNEL_EXPEDITION_THRESHOLD && !rareOre;
        int ordinaryChannelPicks = ordinaryExpedition
                ? ORDINARY_CHANNEL_INITIAL_PICKAXES : 0;
        // Each completed boundary may have to rebuild the full four-pick service pool. In
        // addition, each still-open ordinary batch owns exactly one physical one-pick resupply.
        // Both terms are derived from this finite mission's batch count; replans cannot manufacture
        // another unbounded reserve.
        int ordinaryBoundaryRepairPicks = ordinaryExpedition
                ? saturatedMultiply(Math.max(0, batches - 1), TUNNELING_SERVICE_TARGET) : 0;
        int ordinaryOpenBatchRepairPicks = ordinaryExpedition
                ? saturatedMultiply(batches, MAX_ORDINARY_CHANNEL_RESUPPLIES_PER_BATCH) : 0;
        int ordinaryRepairPicks = saturatedAdd(
                ordinaryBoundaryRepairPicks, ordinaryOpenBatchRepairPicks);
        int ordinaryRepairSticks = saturatedMultiply(
                ordinaryRepairPicks, STONE_PICKAXE_STICK_COST);
        int ordinaryRepairStoneLike = saturatedMultiply(
                ordinaryRepairPicks, STONE_PICKAXE_HEAD_COST);
        // Iron-tier expeditions can craft replacement iron picks underground. Diamond-tier tools
        // are not budgeted as consumable ingots because spending target diamonds would falsify the
        // requested net output; those are withdrawn from a depot or repaired in a later capability.
        int spareIngots = pickaxeTier == ToolTier.IRON && target >= EXPEDITION_THRESHOLD
                ? (rareOre
                ? Math.min(6, Math.max(3, (picks - 1) * 3))
                : Math.min(12, Math.max(3, (picks - 1) * 3)))
                : 0;
        int ironReplacementSticks = spareIngots / 3 * 2;
        // Every funded epoch — the two regular epochs of each batch plus the bounded mission-level
        // margin pool — is provisioned up front. Torches and sticks cannot be produced at diamond
        // depth, so an unfunded margin epoch would silently become an unbounded refresh.
        int rareResourceEpochs = tunnelingPicks > 0
                ? saturatedAdd(
                saturatedMultiply(batches, RARE_RESOURCE_EPOCHS_PER_BATCH),
                rareMissionEpochMargin(batches)) : 0;
        int channelServiceSticks = saturatedMultiply(
                rareResourceEpochs,
                RARE_TUNNELING_SERVICE_TARGET * STONE_PICKAXE_STICK_COST);
        int spareSticks = saturatedAdd(ironReplacementSticks, channelServiceSticks);
        int torches = target < EXPEDITION_THRESHOLD ? 8
                : rareOre
                ? saturatedMultiply(rareResourceEpochs, RARE_BATCH_TORCH_LIMIT)
                : Math.min(128, Math.max(32, batches * 8));
        // Rare expeditions provision every bounded resource epoch plus a fixed descent, emergency,
        // and terminal reserve. Service consumes only the current batch's bounded floor.
        int food = target < EXPEDITION_THRESHOLD
                ? 0 : rareOre ? rareMissionFoodTarget(batches)
                : Math.min(16, Math.max(8, batches * 2));
        // Rare boundary zero owns both seven-pick epoch pools. Retry heads stay sealed until epoch
        // one, while emergency and disposal blocks remain protected from tool crafting.
        int blocks = rareOre && target >= EXPEDITION_THRESHOLD
                ? RARE_BOOTSTRAP_STONE_LIKE : 16;
        return new MiningBudget(target, batch, batches, picks, tunnelingPicks,
                ordinaryChannelPicks, ordinaryRepairPicks,
                ordinaryRepairSticks, ordinaryRepairStoneLike,
                spareIngots, spareSticks, torches, food, blocks);
    }

    public int batchTarget(int batchIndex) {
        if (batchIndex < 0 || batchIndex >= batchCount) {
            return 0;
        }
        int consumed = batchIndex * batchSize;
        return Math.min(batchSize, targetCount - consumed);
    }

    public static int rareMissionFoodTarget(int batchCount) {
        int fundedEpochs = saturatedAdd(
                saturatedMultiply(Math.max(0, batchCount), RARE_RESOURCE_EPOCHS_PER_BATCH),
                rareMissionEpochMargin(batchCount));
        int epochFood = saturatedMultiply(fundedEpochs, RARE_EPOCH_FOOD_ALLOWANCE);
        return saturatedAdd(epochFood, RARE_MISSION_FOOD_BUFFER);
    }

    /**
     * Bounded mission-level epoch margin: one extra resource epoch per two batches, physically
     * capped by the 36-slot bootstrap-carry wall (64 targets → 2). The pool is shared by the
     * whole mission and never refills; drawing is gated by GoalExecutor's durable
     * {@code rare_epoch_margin_used} ledger.
     */
    public static int rareMissionEpochMargin(int batchCount) {
        return Math.min(Math.max(0, batchCount) / 2, RARE_MISSION_EPOCH_MARGIN_CAP);
    }

    /** Exclusive per-batch resource-epoch bound once the mission margin pool is counted. */
    public static int rareMissionResourceEpochCapacity(int batchCount) {
        return RARE_RESOURCE_EPOCHS_PER_BATCH + rareMissionEpochMargin(batchCount);
    }

    /** Batch count implied by a durable rare mission target (rare batches are eight items). */
    public static int rareMissionBatchCount(int missionTarget) {
        return ceilDiv(Math.max(1, missionTarget), RARE_BATCH_SIZE);
    }

    public static int rareServiceFoodMinimum(int resourceEpoch) {
        if (resourceEpoch < 0) {
            throw new IllegalArgumentException("invalid_rare_resource_epoch:" + resourceEpoch);
        }
        // Margin epochs (>= 2) reuse the final regular epoch's floor: after this service the open
        // batch still owns exactly one more bounded epoch of work, no matter how it was funded.
        int clamped = Math.min(resourceEpoch, MAX_RARE_RESOURCE_RETRIES_PER_BATCH);
        int remainingEpochs = RARE_RESOURCE_EPOCHS_PER_BATCH - clamped;
        return remainingEpochs * RARE_EPOCH_FOOD_ALLOWANCE + RARE_SERVICE_FOOD_FLOOR;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static int saturatedAdd(int left, int right) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, left) + Math.max(0, right));
    }

    private static int saturatedMultiply(int left, int right) {
        return (int) Math.min(Integer.MAX_VALUE,
                (long) Math.max(0, left) * Math.max(0, right));
    }
}
