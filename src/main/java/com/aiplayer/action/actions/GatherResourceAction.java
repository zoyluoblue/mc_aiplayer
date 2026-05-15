package com.aiplayer.action.actions;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.mining.OreProspectResult;
import com.aiplayer.mining.OreProspectTarget;
import com.aiplayer.mining.OreProspector;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class GatherResourceAction extends BaseAction {
    private String resourceType;
    private int quantity;
    private BlockPos targetPos;
    private BlockPos treeAnchor;
    private BlockPos treeSearchAnchor;
    private BlockPos treeExploreTarget;
    private BlockPos treeTargetStand;
    private int ticksRunning;
    private int breakTicks;
    private int moveTicksWithoutProgress;
    private int treesCut;
    private int logsCutFromCurrentTree;
    private int treeExploreAttempts;
    private int nextTreeFindTick;
    private double closestTargetDistanceSq;
    private final Set<BlockPos> rejectedTargets = new HashSet<>();
    private static final int MAX_TICKS = 12000;
    private static final int TARGET_MOVE_TIMEOUT_TICKS = 200;
    private static final int MAX_REJECTED_TARGETS = 12;
    private static final int TREE_EXPLORE_STEP_BLOCKS = 24;
    private static final int TREE_EXPLORE_MAX_POINTS = 12;
    private static final int TREE_EXPLORE_STAND_SEARCH_RADIUS = 8;
    private static final int TREE_EXPLORE_STAND_VERTICAL_RADIUS = 5;
    private static final int TREE_EXPLORE_PATH_CHECK_LIMIT = 8;
    private static final int TREE_FIND_INTERVAL_TICKS = 40;
    private static final int PROSPECT_SCAN_RADIUS = 48;
    private static final int PROSPECT_SCAN_VERTICAL_RADIUS = 32;
    private static final int PROSPECT_SCAN_BLOCK_BUDGET = 60_000;
    private static final int PROSPECT_MAX_RETRIES = 3;

    public GatherResourceAction(AiPlayerEntity aiPlayer, Task task) {
        super(aiPlayer, task);
    }

    @Override
    protected void onStart() {
        resourceType = task.getStringParameter("resource", "wood").toLowerCase(Locale.ROOT);
        quantity = task.getIntParameter("quantity", 16);
        ticksRunning = 0;
        breakTicks = 0;
        moveTicksWithoutProgress = 0;
        treesCut = 0;
        logsCutFromCurrentTree = 0;
        treeSearchAnchor = null;
        treeExploreTarget = null;
        treeTargetStand = null;
        treeExploreAttempts = 0;
        nextTreeFindTick = 0;
        closestTargetDistanceSq = Double.MAX_VALUE;
        rejectedTargets.clear();
        prepareTools();
        if (hasEnough()) {
            result = ActionResult.success("已收集 " + quantity + " 个 " + resourceType);
            return;
        }
        if (!hasRequiredTool()) {
            result = ActionResult.failure(requiredToolMessage());
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("收集材料超时：" + resourceType);
            return;
        }

        prepareTools();
        if (hasEnough()) {
            result = ActionResult.success("已收集 " + quantity + " 个 " + resourceType);
            return;
        }
        if (!hasRequiredTool()) {
            result = ActionResult.failure(requiredToolMessage());
            return;
        }

        if (targetPos == null || !isCurrentTargetValid()) {
            Optional<BlockPos> found = findTargetForCurrentState();
            if (found.isEmpty()) {
                if (isStoneResource()) {
                    digDownForStone();
                    return;
                }
                if (finishCurrentTreeIfNeeded()) {
                    if (hasEnough()) {
                        result = ActionResult.success("已砍完 " + quantity + " 棵树");
                    }
                    return;
                }
                if ((isTreeResource() || isWoodResource()) && moveToTreeSearchPoint()) {
                    return;
                }
                result = ActionResult.failure("附近找不到可收集的 " + resourceType);
                return;
            }
            targetPos = found.get();
            if (!isTreeResource() && !isWoodResource()) {
                treeTargetStand = null;
            }
            treeExploreTarget = null;
            nextTreeFindTick = 0;
            if (isTreeResource() && treeAnchor == null) {
                treeAnchor = targetPos;
                logsCutFromCurrentTree = 0;
            }
            breakTicks = 0;
            resetTargetMovement();
        }

        SurvivalUtils.equipBestToolForBlock(aiPlayer, aiPlayer.level().getBlockState(targetPos).getBlock());
        BlockPos moveTarget = treeTargetStand == null || !canStandAt(treeTargetStand) ? targetPos : treeTargetStand;
        double moveRange = treeTargetStand == null ? getInteractionRange() : 1.5D;
        if (!SurvivalUtils.moveNear(aiPlayer, moveTarget, moveRange)) {
            breakTicks = 0;
            trackMovementToTarget();
            return;
        }
        if ((isTreeResource() || isWoodResource()) && !canWorkBlockDirectly(targetPos, getInteractionRange())) {
            rejectCurrentTarget();
            return;
        }
        resetTargetMovement();

        aiPlayer.lookAtWorkTarget(targetPos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < getBreakDelay()) {
            return;
        }

        boolean broken = SurvivalUtils.breakBlock(aiPlayer, targetPos);
        if (!broken) {
            rejectCurrentTarget();
            return;
        }
        if (isTreeResource()) {
            logsCutFromCurrentTree++;
        } else {
            aiPlayer.craftPlanksFromLogs(quantity);
        }
        targetPos = null;
        treeTargetStand = null;
        breakTicks = 0;
        if (hasEnough()) {
            result = ActionResult.success(isTreeResource()
                ? "已砍完 " + quantity + " 棵树"
                : "已收集 " + quantity + " 个 " + resourceType);
        }
    }

    @Override
    protected void onCancel() {
        aiPlayer.getNavigation().stop();
        aiPlayer.setSprinting(false);
    }

    @Override
    public String getDescription() {
        if (isTreeResource()) {
            return "砍树 " + treesCut + "/" + quantity + " 棵";
        }
        return "收集 " + currentAmount() + "/" + quantity + " 个 " + resourceType;
    }

    private void prepareTools() {
        if (isTreeResource()) {
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("axe"));
            return;
        }
        if (isWoodResource()) {
            aiPlayer.craftWoodenAxe();
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("axe"));
            return;
        }
        if (isStoneResource() || isOreResource()) {
            if (needsIronPickaxe()) {
                aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("pickaxe"));
                return;
            }
            if (needsStonePickaxe()) {
                aiPlayer.craftStonePickaxe();
            } else if (aiPlayer.getBestToolStackFor("pickaxe").isEmpty()) {
                aiPlayer.craftWoodenPickaxe();
            }
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("pickaxe"));
        }
    }

    private boolean hasEnough() {
        if (isTreeResource()) {
            return treesCut >= quantity;
        }
        if (isWoodResource()) {
            aiPlayer.craftPlanksFromLogs(quantity);
            return aiPlayer.getWoodenPlankCount() >= quantity;
        }
        if (resourceType.contains("stone") || resourceType.contains("cobble") || resourceType.contains("石")) {
            return aiPlayer.getItemCount(Items.COBBLESTONE) >= quantity;
        }
        Item item = targetItem();
        return item != Items.AIR && aiPlayer.getItemCount(item) >= quantity;
    }

    private Optional<BlockPos> findTarget() {
        Predicate<Block> predicate;
        if (isTreeResource()) {
            if (treeAnchor != null) {
                return findNearestLogInCurrentTree();
            }
            return findReachableLogTarget(pos -> true, 32, 12);
        } else if (isWoodResource()) {
            return findReachableLogTarget(pos -> true, 32, 12);
        } else if (isStoneResource()) {
            if (aiPlayer.getBestToolStackFor("pickaxe").isEmpty()) {
                return Optional.empty();
            }
            return SurvivalUtils.findNearestBlock(aiPlayer,
                (pos, state) -> !rejectedTargets.contains(pos)
                    && SurvivalUtils.isStone(state.getBlock())
                    && hasAirNeighbor(pos),
                32,
                12);
        } else if (resourceType.contains("sand") || resourceType.contains("沙")) {
            predicate = block -> block == Blocks.SAND || block == Blocks.RED_SAND;
        } else {
            if (!hasRequiredTool()) {
                return Optional.empty();
            }
            Block targetOre = targetOreBlock();
            if (targetOre == Blocks.AIR) {
                return Optional.empty();
            }
            predicate = block -> block == targetOre;
        }
        return SurvivalUtils.findNearestBlock(aiPlayer,
            (pos, state) -> !rejectedTargets.contains(pos) && predicate.test(state.getBlock()),
            32,
            12);
    }

    private Optional<BlockPos> findTargetForCurrentState() {
        if ((isTreeResource() || isWoodResource()) && treeAnchor == null) {
            if (ticksRunning < nextTreeFindTick) {
                return Optional.empty();
            }
            nextTreeFindTick = ticksRunning + TREE_FIND_INTERVAL_TICKS;
        }
        Optional<BlockPos> found = findTarget();
        if (found.isPresent() || isStoneResource()) {
            return found;
        }
        return prospectTarget();
    }

    private Optional<BlockPos> prospectTarget() {
        List<String> blockIds = prospectBlockIds();
        if (blockIds.isEmpty()) {
            return Optional.empty();
        }
        OreProspectTarget target = OreProspectTarget.forBlocks(
            "gather_resource",
            resourceType,
            resourceType,
            null,
            "any",
            blockIds,
            aiPlayer.level().getMinY(),
            aiPlayer.level().getMinY() + aiPlayer.level().getHeight() - 1
        );
        Set<BlockPos> skipped = new HashSet<>();
        OreProspectResult lastResult = null;
        for (int attempt = 0; attempt < PROSPECT_MAX_RETRIES; attempt++) {
            Set<BlockPos> rejected = new HashSet<>(rejectedTargets);
            rejected.addAll(skipped);
            lastResult = OreProspector.scan(
                aiPlayer,
                target,
                rejected,
                PROSPECT_SCAN_RADIUS,
                PROSPECT_SCAN_VERTICAL_RADIUS,
                PROSPECT_SCAN_BLOCK_BUDGET
            );
            if (!lastResult.found()) {
                break;
            }
            BlockPos candidate = lastResult.orePos();
            if (isProspectTargetCollectible(candidate)) {
                AiPlayerMod.info("action", "AiPlayer '{}' gather prospect result: resource={}, {}",
                    aiPlayer.getAiPlayerName(), resourceType, lastResult.toLogText());
                return Optional.of(candidate);
            }
            skipped.add(candidate);
        }
        rejectedTargets.addAll(skipped);
        AiPlayerMod.info("action", "AiPlayer '{}' gather prospect result: resource={}, skipped_not_collectible={}, {}",
            aiPlayer.getAiPlayerName(),
            resourceType,
            skipped.size(),
            lastResult == null ? "未执行扫描" : lastResult.toLogText());
        return Optional.empty();
    }

    private List<String> prospectBlockIds() {
        if (isTreeResource() || isWoodResource()) {
            return List.of(
                blockId(Blocks.OAK_LOG),
                blockId(Blocks.SPRUCE_LOG),
                blockId(Blocks.BIRCH_LOG),
                blockId(Blocks.JUNGLE_LOG),
                blockId(Blocks.ACACIA_LOG),
                blockId(Blocks.DARK_OAK_LOG),
                blockId(Blocks.MANGROVE_LOG),
                blockId(Blocks.CHERRY_LOG)
            );
        }
        if (resourceType.contains("sand") || resourceType.contains("沙")) {
            return List.of(blockId(Blocks.SAND), blockId(Blocks.RED_SAND));
        }
        if (isOreResource()) {
            Block ore = targetOreBlock();
            Block deepslateOre = deepslateOreBlock();
            return deepslateOre == Blocks.AIR
                ? List.of(blockId(ore))
                : List.of(blockId(ore), blockId(deepslateOre));
        }
        Block namedBlock = blockFromResourceName(resourceType);
        if (namedBlock != Blocks.AIR) {
            return List.of(blockId(namedBlock));
        }
        return List.of();
    }

    private boolean isProspectTargetCollectible(BlockPos pos) {
        if (pos == null || !hasAirNeighbor(pos)) {
            return false;
        }
        if (aiPlayer.position().distanceToSqr(Vec3.atCenterOf(pos)) <= getInteractionRange() * getInteractionRange()) {
            return true;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stand = pos.relative(direction);
            if (canStandAt(stand) && hasReachableNavigationPath(stand)) {
                return true;
            }
        }
        BlockPos below = pos.below();
        return canStandAt(below) && hasReachableNavigationPath(below);
    }

    private int getBreakDelay() {
        if (targetPos != null) {
            return SurvivalUtils.getBreakDelay(aiPlayer, aiPlayer.level().getBlockState(targetPos).getBlock());
        }
        return 24;
    }

    private double getInteractionRange() {
        return isTreeResource() || isWoodResource() ? 4.5D : 3.0D;
    }

    private boolean isTreeResource() {
        return resourceType.contains("tree") || resourceType.contains("树");
    }

    private boolean isWoodResource() {
        return resourceType.contains("wood") || resourceType.contains("log") || resourceType.contains("plank") ||
            resourceType.contains("木");
    }

    private boolean isStoneResource() {
        return resourceType.contains("stone") || resourceType.contains("cobble") || resourceType.contains("石");
    }

    private boolean isOreResource() {
        return targetOreBlock() != Blocks.AIR;
    }

    private Item targetItem() {
        if (resourceType.contains("coal") || resourceType.contains("煤")) {
            return Items.COAL;
        }
        if (resourceType.contains("iron") || resourceType.contains("铁")) {
            return Items.RAW_IRON;
        }
        if (resourceType.contains("copper") || resourceType.contains("铜")) {
            return Items.RAW_COPPER;
        }
        if (resourceType.contains("gold") || resourceType.contains("金")) {
            return Items.RAW_GOLD;
        }
        if (resourceType.contains("diamond") || resourceType.contains("钻石")) {
            return Items.DIAMOND;
        }
        if (resourceType.contains("redstone") || resourceType.contains("红石")) {
            return Items.REDSTONE;
        }
        if (resourceType.contains("lapis") || resourceType.contains("青金石")) {
            return Items.LAPIS_LAZULI;
        }
        if (resourceType.contains("emerald") || resourceType.contains("绿宝石")) {
            return Items.EMERALD;
        }
        Block namedBlock = blockFromResourceName(resourceType);
        Item namedItem = namedBlock.asItem();
        if (namedItem != null && namedItem != Items.AIR) {
            return namedItem;
        }
        return Items.AIR;
    }

    private Block targetOreBlock() {
        if (resourceType.contains("coal") || resourceType.contains("煤")) {
            return Blocks.COAL_ORE;
        }
        if (resourceType.contains("iron") || resourceType.contains("铁")) {
            return Blocks.IRON_ORE;
        }
        if (resourceType.contains("copper") || resourceType.contains("铜")) {
            return Blocks.COPPER_ORE;
        }
        if (resourceType.contains("gold") || resourceType.contains("金")) {
            return Blocks.GOLD_ORE;
        }
        if (resourceType.contains("diamond") || resourceType.contains("钻石")) {
            return Blocks.DIAMOND_ORE;
        }
        if (resourceType.contains("redstone") || resourceType.contains("红石")) {
            return Blocks.REDSTONE_ORE;
        }
        if (resourceType.contains("lapis") || resourceType.contains("青金石")) {
            return Blocks.LAPIS_ORE;
        }
        if (resourceType.contains("emerald") || resourceType.contains("绿宝石")) {
            return Blocks.EMERALD_ORE;
        }
        return Blocks.AIR;
    }

    private boolean hasRequiredTool() {
        if (!isStoneResource() && !isOreResource()) {
            return true;
        }
        if (needsIronPickaxe()) {
            return hasIronOrBetterPickaxe();
        }
        if (needsStonePickaxe()) {
            return hasStoneOrBetterPickaxe();
        }
        return !aiPlayer.getBestToolStackFor("pickaxe").isEmpty();
    }

    private boolean needsStonePickaxe() {
        Block ore = targetOreBlock();
        return ore == Blocks.IRON_ORE || ore == Blocks.COPPER_ORE || ore == Blocks.LAPIS_ORE;
    }

    private boolean needsIronPickaxe() {
        Block ore = targetOreBlock();
        return ore == Blocks.GOLD_ORE || ore == Blocks.DIAMOND_ORE || ore == Blocks.REDSTONE_ORE || ore == Blocks.EMERALD_ORE;
    }

    private boolean hasStoneOrBetterPickaxe() {
        return aiPlayer.getBestToolStackFor("pickaxe").is(Items.STONE_PICKAXE) || hasIronOrBetterPickaxe();
    }

    private boolean hasIronOrBetterPickaxe() {
        return aiPlayer.getBestToolStackFor("pickaxe").is(Items.IRON_PICKAXE)
            || aiPlayer.getBestToolStackFor("pickaxe").is(Items.DIAMOND_PICKAXE)
            || aiPlayer.getBestToolStackFor("pickaxe").is(Items.NETHERITE_PICKAXE);
    }

    private String requiredToolMessage() {
        if (needsIronPickaxe()) {
            return "缺少铁镐，无法像生存玩家一样收集 " + resourceType;
        }
        if (needsStonePickaxe()) {
            return "缺少石镐，无法像生存玩家一样收集 " + resourceType;
        }
        return "缺少镐，无法像生存玩家一样收集 " + resourceType;
    }

    private Optional<BlockPos> findNearestLogInCurrentTree() {
        BlockPos anchor = treeAnchor;
        if (anchor == null) {
            return Optional.empty();
        }
        return findReachableLogTarget(pos -> {
            int dx = Math.abs(pos.getX() - anchor.getX());
            int dz = Math.abs(pos.getZ() - anchor.getZ());
            int dy = pos.getY() - anchor.getY();
            return dx <= 3 && dz <= 3 && dy >= -1 && dy <= 10;
        }, 8, 12);
    }

    private Optional<BlockPos> findReachableLogTarget(java.util.function.Predicate<BlockPos> extraFilter, int horizontalRadius, int verticalRadius) {
        BlockPos center = aiPlayer.blockPosition();
        TreeWorkTarget best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(
            center.offset(-horizontalRadius, -verticalRadius, -horizontalRadius),
            center.offset(horizontalRadius, verticalRadius, horizontalRadius)
        )) {
            BlockPos pos = candidate.immutable();
            if (!aiPlayer.level().hasChunkAt(pos)
                || rejectedTargets.contains(pos)
                || !extraFilter.test(pos)
                || !SurvivalUtils.isLog(aiPlayer.level().getBlockState(pos).getBlock())) {
                continue;
            }
            TreeWorkTarget target = resolveTreeWorkTarget(pos);
            if (target == null) {
                continue;
            }
            double score = target.score(center);
            if (score < bestScore) {
                bestScore = score;
                best = target;
            }
        }
        if (best == null) {
            treeTargetStand = null;
            return Optional.empty();
        }
        treeTargetStand = best.standPos();
        return Optional.of(best.logPos());
    }

    private boolean moveToTreeSearchPoint() {
        if (treeSearchAnchor == null) {
            treeSearchAnchor = aiPlayer.blockPosition().immutable();
        }
        if (treeExploreTarget == null
            || aiPlayer.blockPosition().closerThan(treeExploreTarget, 2.5D)
            || !canStandAt(treeExploreTarget)) {
            treeExploreTarget = nextTreeExploreTarget();
            resetTargetMovement();
        }
        if (treeExploreTarget == null) {
            if (treeExploreAttempts < TREE_EXPLORE_MAX_POINTS) {
                return true;
            }
            result = ActionResult.failure("附近和探索路线都没有找到可收集的 " + resourceType + "，请移动到树林附近或提供木材");
            return true;
        }
        if (SurvivalUtils.moveNear(aiPlayer, treeExploreTarget, 2.5D)) {
            nextTreeFindTick = 0;
            treeExploreTarget = null;
            resetTargetMovement();
            return true;
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(treeExploreTarget));
        if (distanceSq + 0.5D < closestTargetDistanceSq) {
            closestTargetDistanceSq = distanceSq;
            moveTicksWithoutProgress = 0;
            return true;
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > TARGET_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isStuck()) {
            treeExploreTarget = null;
            resetTargetMovement();
        }
        return true;
    }

    private BlockPos nextTreeExploreTarget() {
        if (treeExploreAttempts >= TREE_EXPLORE_MAX_POINTS) {
            return null;
        }
        Direction[] directions = orderedTreeExploreDirections();
        int attempt = treeExploreAttempts++;
        int ring = attempt / directions.length + 1;
        Direction direction = directions[attempt % directions.length];
        BlockPos rough = treeSearchAnchor.relative(direction, TREE_EXPLORE_STEP_BLOCKS * ring);
        return nearestReachableStandAround(rough, TREE_EXPLORE_STAND_SEARCH_RADIUS, TREE_EXPLORE_STAND_VERTICAL_RADIUS).orElse(null);
    }

    private Direction[] orderedTreeExploreDirections() {
        Direction primary = horizontalDirectionFromYaw();
        return new Direction[] {
            primary,
            primary.getClockWise(),
            primary.getCounterClockWise(),
            primary.getOpposite()
        };
    }

    private Direction horizontalDirectionFromYaw() {
        float yaw = aiPlayer.getYRot();
        int index = Math.floorMod(Math.round(yaw / 90.0F), 4);
        return switch (index) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private Optional<BlockPos> nearestReachableStandAround(BlockPos rough, int horizontalRadius, int verticalRadius) {
        BlockPos current = aiPlayer.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(
            rough.offset(-horizontalRadius, -verticalRadius, -horizontalRadius),
            rough.offset(horizontalRadius, verticalRadius, horizontalRadius)
        )) {
            BlockPos pos = candidate.immutable();
            if (!aiPlayer.level().hasChunkAt(pos) || !canStandAt(pos)) {
                continue;
            }
            double distance = pos.distSqr(current);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        int pathChecks = 0;
        if (best != null && hasReachableNavigationPath(best)) {
            return Optional.of(best);
        }
        for (BlockPos candidate : BlockPos.betweenClosed(
            rough.offset(-horizontalRadius, -verticalRadius, -horizontalRadius),
            rough.offset(horizontalRadius, verticalRadius, horizontalRadius)
        )) {
            if (pathChecks >= TREE_EXPLORE_PATH_CHECK_LIMIT) {
                break;
            }
            BlockPos pos = candidate.immutable();
            if (!aiPlayer.level().hasChunkAt(pos) || !canStandAt(pos)) {
                continue;
            }
            pathChecks++;
            if (hasReachableNavigationPath(pos)) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    private TreeWorkTarget resolveTreeWorkTarget(BlockPos logPos) {
        if (logPos == null) {
            return null;
        }
        if (canWorkBlockDirectly(logPos, getInteractionRange())) {
            return new TreeWorkTarget(logPos.immutable(), aiPlayer.blockPosition().immutable(), 0.0D);
        }
        Optional<BlockPos> stand = reachableTreeWorkStand(logPos);
        return stand.map(pos -> new TreeWorkTarget(logPos.immutable(), pos, verticalPenalty(logPos, pos))).orElse(null);
    }

    private Optional<BlockPos> reachableTreeWorkStand(BlockPos logPos) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(logPos.offset(-4, -3, -4), logPos.offset(4, 2, 4))) {
            BlockPos stand = candidate.immutable();
            if (!aiPlayer.level().hasChunkAt(stand) || !canStandAt(stand)) {
                continue;
            }
            if (Vec3.atCenterOf(stand).distanceToSqr(Vec3.atCenterOf(logPos)) > getInteractionRange() * getInteractionRange()) {
                continue;
            }
            if (!hasReachableNavigationPath(stand)) {
                continue;
            }
            double score = stand.distSqr(aiPlayer.blockPosition()) + verticalPenalty(logPos, stand);
            if (score < bestScore) {
                bestScore = score;
                best = stand;
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean canWorkBlockDirectly(BlockPos pos, double range) {
        return pos != null && aiPlayer.position().distanceToSqr(Vec3.atCenterOf(pos)) <= range * range;
    }

    private boolean hasReachableNavigationPath(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        if (aiPlayer.blockPosition().equals(pos)) {
            return true;
        }
        Path path = aiPlayer.getNavigation().createPath(pos, 1);
        return path != null && path.canReach();
    }

    private double verticalPenalty(BlockPos logPos, BlockPos standPos) {
        return Math.abs(logPos.getY() - standPos.getY()) * 8.0D;
    }

    private record TreeWorkTarget(BlockPos logPos, BlockPos standPos, double penalty) {
        private double score(BlockPos current) {
            return standPos.distSqr(current) + penalty;
        }
    }

    private boolean canStandAt(BlockPos pos) {
        Block feet = aiPlayer.level().getBlockState(pos).getBlock();
        Block head = aiPlayer.level().getBlockState(pos.above()).getBlock();
        Block support = aiPlayer.level().getBlockState(pos.below()).getBlock();
        return feet == Blocks.AIR
            && head == Blocks.AIR
            && support != Blocks.AIR
            && support != Blocks.WATER
            && support != Blocks.LAVA;
    }

    private boolean isCurrentTargetValid() {
        if (targetPos == null) {
            return false;
        }
        Block block = aiPlayer.level().getBlockState(targetPos).getBlock();
        if (isTreeResource() || isWoodResource()) {
            return SurvivalUtils.isLog(block)
                && (canWorkBlockDirectly(targetPos, getInteractionRange())
                    || (treeTargetStand != null && canStandAt(treeTargetStand)));
        }
        if (isStoneResource()) {
            return SurvivalUtils.isStone(block) && hasAirNeighbor(targetPos);
        }
        List<String> targetBlockIds = prospectBlockIds();
        if (!targetBlockIds.isEmpty()) {
            return targetBlockIds.contains(blockId(block)) && isProspectTargetCollectible(targetPos);
        }
        return block != Blocks.AIR && isProspectTargetCollectible(targetPos);
    }

    private boolean finishCurrentTreeIfNeeded() {
        if (!isTreeResource() || treeAnchor == null || logsCutFromCurrentTree <= 0) {
            return false;
        }
        treesCut++;
        treeAnchor = null;
        logsCutFromCurrentTree = 0;
        targetPos = null;
        treeTargetStand = null;
        rejectedTargets.clear();
        return true;
    }

    private void trackMovementToTarget() {
        if (targetPos == null) {
            return;
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(targetPos));
        if (distanceSq + 0.5D < closestTargetDistanceSq) {
            closestTargetDistanceSq = distanceSq;
            moveTicksWithoutProgress = 0;
            return;
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > TARGET_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isDone()) {
            rejectCurrentTarget();
        }
    }

    private void rejectCurrentTarget() {
        if (targetPos != null) {
            rejectedTargets.add(targetPos);
        }
        targetPos = null;
        treeTargetStand = null;
        breakTicks = 0;
        resetTargetMovement();
        if (rejectedTargets.size() > MAX_REJECTED_TARGETS) {
            if (isStoneResource()) {
                return;
            }
            result = ActionResult.failure("附近的 " + resourceType + " 暂时无法到达");
        }
    }

    private void resetTargetMovement() {
        moveTicksWithoutProgress = 0;
        closestTargetDistanceSq = Double.MAX_VALUE;
    }

    private int currentAmount() {
        if (isWoodResource()) {
            return aiPlayer.getWoodenPlankCount();
        }
        if (isStoneResource()) {
            return aiPlayer.getItemCount(Items.COBBLESTONE);
        }
        Item item = targetItem();
        return item == Items.AIR ? 0 : aiPlayer.getItemCount(item);
    }

    private void digDownForStone() {
        BlockPos digTarget = findDownwardDigTarget();
        if (digTarget == null) {
            result = ActionResult.failure("附近找不到可挖的石头，也无法继续向下挖");
            return;
        }
        Block block = aiPlayer.level().getBlockState(digTarget).getBlock();
        SurvivalUtils.equipBestToolForBlock(aiPlayer, block);
        aiPlayer.lookAtWorkTarget(digTarget);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < SurvivalUtils.getBreakDelay(aiPlayer, block)) {
            return;
        }
        if (!SurvivalUtils.breakBlock(aiPlayer, digTarget)) {
            breakTicks = 0;
            return;
        }
        targetPos = null;
        breakTicks = 0;
        if (hasEnough()) {
            result = ActionResult.success("已收集 " + quantity + " 个 " + resourceType);
        }
    }

    private BlockPos findDownwardDigTarget() {
        BlockPos center = aiPlayer.blockPosition();
        int minY = aiPlayer.level().getMinY();
        for (int depth = 1; depth <= 8; depth++) {
            BlockPos candidate = center.below(depth);
            if (candidate.getY() <= minY) {
                return null;
            }
            Block block = aiPlayer.level().getBlockState(candidate).getBlock();
            if (block != Blocks.AIR && block != Blocks.BEDROCK) {
                return candidate;
            }
        }
        return null;
    }

    private Block deepslateOreBlock() {
        if (resourceType.contains("coal") || resourceType.contains("煤")) {
            return Blocks.DEEPSLATE_COAL_ORE;
        }
        if (resourceType.contains("iron") || resourceType.contains("铁")) {
            return Blocks.DEEPSLATE_IRON_ORE;
        }
        if (resourceType.contains("copper") || resourceType.contains("铜")) {
            return Blocks.DEEPSLATE_COPPER_ORE;
        }
        if (resourceType.contains("gold") || resourceType.contains("金")) {
            return Blocks.DEEPSLATE_GOLD_ORE;
        }
        if (resourceType.contains("diamond") || resourceType.contains("钻石")) {
            return Blocks.DEEPSLATE_DIAMOND_ORE;
        }
        if (resourceType.contains("redstone") || resourceType.contains("红石")) {
            return Blocks.DEEPSLATE_REDSTONE_ORE;
        }
        if (resourceType.contains("lapis") || resourceType.contains("青金石")) {
            return Blocks.DEEPSLATE_LAPIS_ORE;
        }
        if (resourceType.contains("emerald") || resourceType.contains("绿宝石")) {
            return Blocks.DEEPSLATE_EMERALD_ORE;
        }
        return Blocks.AIR;
    }

    private static String blockId(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? "minecraft:air" : key.toString();
    }

    private static Block blockFromResourceName(String name) {
        if (name == null || name.isBlank()) {
            return Blocks.AIR;
        }
        Block block = blockFromId(name);
        if (block != Blocks.AIR || name.contains(":")) {
            return block;
        }
        return blockFromId("minecraft:" + name);
    }

    private static Block blockFromId(String id) {
        try {
            Block block = BuiltInRegistries.BLOCK.getValue(ResourceLocation.parse(id));
            return block == null ? Blocks.AIR : block;
        } catch (RuntimeException ignored) {
            return Blocks.AIR;
        }
    }

    private boolean hasAirNeighbor(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (aiPlayer.level().getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }
}
