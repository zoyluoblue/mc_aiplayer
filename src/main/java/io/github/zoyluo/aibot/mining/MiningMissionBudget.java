package io.github.zoyluo.aibot.mining;

import io.github.zoyluo.aibot.goal.GoalPlanner;
import io.github.zoyluo.aibot.goal.GoalStep;
import net.minecraft.block.Blocks;

/**
 * Outer timeout contracts for long strict-survival mining missions.
 *
 * <p>The outer verifier must never expire before its nominal bounded child plan. Replans consume
 * the same outer budget; this class deliberately does not claim to cover every possible retry
 * combination. These constants are task-tick windows, not wall-clock evidence-run limits.</p>
 */
public final class MiningMissionBudget {
    public static final int ORE_DIG_HARD_WINDOW_TICKS = 24_000;
    public static final int SERVICE_HARD_WINDOW_TICKS = 4_800;
    public static final int DESCEND_MIN_HARD_WINDOW_TICKS = 4_800;
    /** Outer plans use the child cap so they can never expire before a legal deep descent. */
    public static final int DESCEND_HARD_WINDOW_TICKS = 40_000;
    /** Conservative common envelope for short bounded auxiliary tasks. */
    public static final int SHORT_AUXILIARY_STEP_WINDOW_TICKS = 4_800;
    /** Fallback envelope for auxiliary tasks whose child-specific bound is not yet classified. */
    public static final int AUXILIARY_STEP_WINDOW_TICKS = 24_000;
    public static final int FROM_ZERO_BOOTSTRAP_MARGIN_TICKS = 24_000;

    private static final int DESCEND_BASE_TICKS = 2_400;
    private static final int DESCEND_PER_LEVEL_TICKS = 80;
    private static final int SMELT_BASE_TICKS = 400;
    private static final int SMELT_PER_ITEM_TICKS = 260;
    private static final int OBSIDIAN_PER_TARGET_TICKS = 2_400;

    private static final int DIAMOND_STACK_TARGET = 64;
    private static final int MIN_FROM_ZERO_DESCENTS = 2;

    private MiningMissionBudget() {
    }

    /** Exact persisted child window for one descent from the factual start height. */
    public static int descendTaskWindowTicks(int fromY, int targetY) {
        long depth = Math.max(0L, (long) fromY - targetY);
        long scaled = DESCEND_BASE_TICKS + depth * DESCEND_PER_LEVEL_TICKS;
        return (int) Math.min(DESCEND_HARD_WINDOW_TICKS,
                Math.max(DESCEND_MIN_HARD_WINDOW_TICKS, scaled));
    }

    /**
     * Cumulative hard ceiling for one long rare batch's current resource epoch.
     *
     * <p>Each epoch owns an independent 24,000-tick window, while the persisted OreDig budget is
     * monotonic across the one allowed retry. Epoch zero therefore expires at 24,000 and epoch one
     * at 48,000; a restart can resume the remaining window but cannot refresh either clock.</p>
     */
    public static int rareOreDigCumulativeHardWindowTicks(int resourceEpoch) {
        if (resourceEpoch < 0
                || resourceEpoch >= MiningBudget.RARE_RESOURCE_EPOCHS_PER_BATCH) {
            throw new IllegalArgumentException("invalid_rare_resource_epoch:" + resourceEpoch);
        }
        return Math.multiplyExact(resourceEpoch + 1, ORE_DIG_HARD_WINDOW_TICKS);
    }

    /**
     * Minimum outer timeout for the pinned 64-diamond from-zero mission.
     *
     * <p>The target is split into eight durable OreDig batches. Every batch, including boundary
     * zero, owns one nominal service checkpoint and one independently bounded resource-epoch retry
     * service plus its matching OreDig window. Each OreDig batch also owns at most one exceptional
     * sealed inventory-service window. The from-zero chain additionally needs at least the
     * shallow-tool and deep-diamond descents.</p>
     */
    public static OuterTimeoutBudget diamondStack64FromZero() {
        MiningBudget target = MiningBudget.forQuota(
                DIAMOND_STACK_TARGET, true, ToolTier.IRON);
        return new OuterTimeoutBudget(
                target.batchCount(),
                0,
                target.batchCount() * MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH,
                target.batchCount(),
                0,
                target.batchCount() * MiningBudget.MAX_RARE_RESOURCE_RETRIES_PER_BATCH,
                target.batchCount(),
                MIN_FROM_ZERO_DESCENTS,
                0,
                FROM_ZERO_BOOTSTRAP_MARGIN_TICKS);
    }

    /**
     * Binds the outer timeout to the nominal plan produced from the live from-zero state. Bootstrap
     * coal/iron OreDig and service steps are charged separately instead of being hidden inside a
     * fixed margin. Floors preserve the target contract if a future planner accidentally omits a
     * target stage; the final margin belongs only to auxiliary craft/smelt/gather work.
     */
    public static OuterTimeoutBudget diamondStack64FromZero(GoalPlanner.GoalPlan nominalPlan) {
        if (nominalPlan == null) {
            throw new IllegalArgumentException("missing_nominal_mining_plan");
        }
        int targetOreDig = 0;
        int bootstrapOreDig = 0;
        int targetService = 0;
        int bootstrapService = 0;
        int ordinaryCapacityUpper = 0;
        int descents = 0;
        int auxiliaryWindowTicks = 0;
        for (GoalStep step : nominalPlan.steps()) {
            boolean diamond = isDiamondStep(step);
            switch (step.kind()) {
                case MINE_ORE -> {
                    if (diamond) {
                        targetOreDig++;
                    } else {
                        bootstrapOreDig++;
                        // A dynamic capacity hand-off may repeat after a new delivery or factual
                        // work-face movement. Persisted watermarks plus services_used<=count prevent
                        // restart/replan refresh, so the step target remains the exact upper bound.
                        ordinaryCapacityUpper = Math.addExact(
                                ordinaryCapacityUpper, step.count());
                    }
                }
                case MINING_SERVICE -> {
                    if (diamond) {
                        targetService++;
                    } else {
                        bootstrapService++;
                    }
                }
                case DESCEND_TO_Y -> descents++;
                default -> auxiliaryWindowTicks = Math.addExact(
                        auxiliaryWindowTicks,
                        auxiliaryStepWindowTicks(step.kind(), step.count()));
            }
        }
        OuterTimeoutBudget floor = diamondStack64FromZero();
        return new OuterTimeoutBudget(
                Math.max(floor.targetOreDigBatches(), targetOreDig),
                bootstrapOreDig,
                floor.retryOreDigBatches(),
                Math.max(floor.targetServiceCheckpoints(), targetService),
                bootstrapService,
                floor.retryServiceCheckpoints(),
                Math.addExact(floor.inventoryServiceCheckpoints(),
                        ordinaryCapacityUpper),
                Math.max(floor.descents(), descents),
                auxiliaryWindowTicks,
                FROM_ZERO_BOOTSTRAP_MARGIN_TICKS);
    }

    /**
     * Conservative child window for one nominal non-mining/service/descent step.
     *
     * <p>{@code CraftTask} has a 401-tick terminal boundary. {@code HuntTask} fails after
     * {@code max(3600, count * 480) + 1} task ticks; the planner's expedition hunts are batches of
     * at most four, so the shared 4,800-tick short envelope covers them with margin. Both smelting
     * kinds share {@code SmeltTask}'s {@code 400 + count * 260 + 1} terminal boundary, while
     * {@code CreateObsidianTask} owns {@code max(24000, count * 2400)} durable task ticks. Unknown
     * task families retain the former 24,000-tick charge until their own hard boundary is modeled.</p>
     */
    public static int auxiliaryStepWindowTicks(GoalStep.Kind kind, int count) {
        if (kind == null || count <= 0) {
            throw new IllegalArgumentException("invalid_auxiliary_step_window");
        }
        return switch (kind) {
            case CRAFT -> SHORT_AUXILIARY_STEP_WINDOW_TICKS;
            case HUNT -> Math.toIntExact(Math.max(
                    SHORT_AUXILIARY_STEP_WINDOW_TICKS,
                    Math.addExact(Math.multiplyExact((long) count, 480L), 1L)));
            case SMELT, COOK_FOOD -> Math.toIntExact(Math.max(
                    AUXILIARY_STEP_WINDOW_TICKS,
                    Math.addExact(Math.addExact(SMELT_BASE_TICKS,
                            Math.multiplyExact((long) count, SMELT_PER_ITEM_TICKS)), 1L)));
            case MAKE_OBSIDIAN -> (int) Math.min(Integer.MAX_VALUE, Math.max(
                    AUXILIARY_STEP_WINDOW_TICKS,
                    Math.multiplyExact((long) count, OBSIDIAN_PER_TARGET_TICKS)));
            default -> AUXILIARY_STEP_WINDOW_TICKS;
        };
    }

    private static boolean isDiamondStep(GoalStep step) {
        return step.ores().contains(Blocks.DIAMOND_ORE)
                || step.ores().contains(Blocks.DEEPSLATE_DIAMOND_ORE);
    }

    public record OuterTimeoutBudget(int targetOreDigBatches,
                                     int bootstrapOreDigBatches,
                                     int retryOreDigBatches,
                                     int targetServiceCheckpoints,
                                     int bootstrapServiceCheckpoints,
                                     int retryServiceCheckpoints,
                                     int inventoryServiceCheckpoints,
                                     int descents,
                                     int auxiliaryWindowTicks,
                                     int bootstrapMarginTicks) {
        public OuterTimeoutBudget {
            if (targetOreDigBatches < 0 || bootstrapOreDigBatches < 0
                    || retryOreDigBatches < 0
                    || targetServiceCheckpoints < 0 || bootstrapServiceCheckpoints < 0
                    || retryServiceCheckpoints < 0 || inventoryServiceCheckpoints < 0
                    || descents < 0
                    || auxiliaryWindowTicks < 0 || bootstrapMarginTicks < 0) {
                throw new IllegalArgumentException("negative_mining_timeout_component");
            }
        }

        public int oreDigBatches() {
            return Math.addExact(retryOreDigBatches,
                    Math.addExact(targetOreDigBatches, bootstrapOreDigBatches));
        }

        public int serviceCheckpoints() {
            return Math.addExact(inventoryServiceCheckpoints,
                    Math.addExact(retryServiceCheckpoints,
                    Math.addExact(targetServiceCheckpoints, bootstrapServiceCheckpoints)));
        }

        public int oreDigTicks() {
            return Math.multiplyExact(oreDigBatches(), ORE_DIG_HARD_WINDOW_TICKS);
        }

        public int serviceTicks() {
            return Math.multiplyExact(serviceCheckpoints(), SERVICE_HARD_WINDOW_TICKS);
        }

        public int descendTicks() {
            return Math.multiplyExact(descents, DESCEND_HARD_WINDOW_TICKS);
        }

        public int auxiliaryTicks() {
            return auxiliaryWindowTicks;
        }

        public int timeoutTicks() {
            return Math.addExact(bootstrapMarginTicks,
                    Math.addExact(auxiliaryTicks(), Math.addExact(
                            oreDigTicks(), Math.addExact(serviceTicks(), descendTicks()))));
        }
    }
}
