package com.aiplayer.action.actions;

import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

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
    private int ticksRunning;
    private int breakTicks;
    private int moveTicksWithoutProgress;
    private int treesCut;
    private int logsCutFromCurrentTree;
    private double closestTargetDistanceSq;
    private final Set<BlockPos> rejectedTargets = new HashSet<>();
    private static final int MAX_TICKS = 12000;
    private static final int TARGET_MOVE_TIMEOUT_TICKS = 200;
    private static final int MAX_REJECTED_TARGETS = 12;

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

        if (targetPos == null || aiPlayer.level().getBlockState(targetPos).isAir()) {
            Optional<BlockPos> found = findTarget();
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
                result = ActionResult.failure("附近找不到可收集的 " + resourceType);
                return;
            }
            targetPos = found.get();
            if (isTreeResource() && treeAnchor == null) {
                treeAnchor = targetPos;
                logsCutFromCurrentTree = 0;
            }
            breakTicks = 0;
            resetTargetMovement();
        }

        if (!SurvivalUtils.moveNear(aiPlayer, targetPos, getInteractionRange())) {
            breakTicks = 0;
            trackMovementToTarget();
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
            return aiPlayer.getItemCount(Items.OAK_PLANKS) >= quantity;
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
            predicate = SurvivalUtils::isLog;
        } else if (isWoodResource()) {
            predicate = SurvivalUtils::isLog;
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
            predicate = block -> block == targetOre;
        }
        return SurvivalUtils.findNearestBlock(aiPlayer,
            (pos, state) -> !rejectedTargets.contains(pos) && predicate.test(state.getBlock()),
            32,
            12);
    }

    private int getBreakDelay() {
        if (isTreeResource() || isWoodResource()) {
            return aiPlayer.getBestToolStackFor("axe").isEmpty() ? 60 : 24;
        }
        if (isStoneResource() || isOreResource()) {
            return aiPlayer.getBestToolStackFor("pickaxe").isEmpty() ? 100 : 36;
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
        return aiPlayer.hasItem(Items.STONE_PICKAXE, 1) || hasIronOrBetterPickaxe();
    }

    private boolean hasIronOrBetterPickaxe() {
        return aiPlayer.hasItem(Items.IRON_PICKAXE, 1)
            || aiPlayer.hasItem(Items.DIAMOND_PICKAXE, 1)
            || aiPlayer.hasItem(Items.NETHERITE_PICKAXE, 1);
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
        return SurvivalUtils.findNearestBlock(aiPlayer, (pos, state) -> {
            if (rejectedTargets.contains(pos) || !SurvivalUtils.isLog(state.getBlock())) {
                return false;
            }
            int dx = Math.abs(pos.getX() - anchor.getX());
            int dz = Math.abs(pos.getZ() - anchor.getZ());
            int dy = pos.getY() - anchor.getY();
            return dx <= 3 && dz <= 3 && dy >= -1 && dy <= 10;
        }, 8, 12);
    }

    private boolean finishCurrentTreeIfNeeded() {
        if (!isTreeResource() || treeAnchor == null || logsCutFromCurrentTree <= 0) {
            return false;
        }
        treesCut++;
        treeAnchor = null;
        logsCutFromCurrentTree = 0;
        targetPos = null;
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
            return aiPlayer.getItemCount(Items.OAK_PLANKS);
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
        aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("pickaxe"));
        aiPlayer.lookAtWorkTarget(digTarget);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < (SurvivalUtils.requiresPickaxe(block) ? 36 : 20)) {
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

    private boolean hasAirNeighbor(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (aiPlayer.level().getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }
}
