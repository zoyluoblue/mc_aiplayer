package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.ContainerAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MiningAction;
import io.github.zoyluo.aibot.action.ToolSelector;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class StripMineTask extends AbstractTask {
    private enum Phase {
        PREP,
        TUNNEL,
        MINE_BLOCK,
        SCAN_VEIN,
        MINE_VEIN,
        LIGHT,
        MOVE,
        RETURN,
        RETURN_DEPOSIT,
        RETURN_TO_WORK,
        DONE
    }

    private enum StepKind {
        TUNNEL,
        DESCEND,
        MOVE_ONLY
    }

    private static final int MAX_VEIN_BLOCKS = 64;
    private static final double REACH_SQUARED = 20.25D;

    private final Direction direction;
    private final int length;
    private final int branchSpacing;
    private final BlockPos depotChest;
    private final Set<Block> targetOres;
    private final boolean veinOnly;
    private final boolean autoDescend;
    private final Deque<Step> steps = new ArrayDeque<>();
    private final Deque<BlockPos> blocksToMine = new ArrayDeque<>();
    private final Deque<BlockPos> veinBlocks = new ArrayDeque<>();
    private final Set<BlockPos> queuedVeinBlocks = new HashSet<>();
    private Phase phase = Phase.PREP;
    private Step currentStep;
    private BlockPos origin;
    private BlockPos activeDepotChest;
    private BlockPos returnStand;
    private BlockPos currentMiningBlock;
    private BlockPos currentVeinBlock;
    private boolean miningStarted;
    private boolean returningForFinalStop;
    private int tunnelBlocksMined;
    private int veinBlocksMined;
    private int distanceCompleted;
    private int descentStepsPlanned;
    private String note = "";

    public StripMineTask(Direction direction, int length, int branchSpacing, BlockPos depotChest, Set<Block> targetOres) {
        this(direction, length, branchSpacing, depotChest, targetOres, false);
    }

    public static StripMineTask mineNearbyVein(Set<Block> targetOres) {
        return new StripMineTask(Direction.NORTH, 0, 0, null, targetOres, true);
    }

    public static StripMineTask forOre(Block targetOre, int count) {
        int length = Math.min(128, Math.max(64, count * 16));
        return new StripMineTask(Direction.NORTH, length, 4, null, OreScan.oreFamily(targetOre), false, true);
    }

    private StripMineTask(Direction direction,
                          int length,
                          int branchSpacing,
                          BlockPos depotChest,
                          Set<Block> targetOres,
                          boolean veinOnly) {
        this(direction, length, branchSpacing, depotChest, targetOres, veinOnly,
                !veinOnly && targetOres != null && !targetOres.isEmpty());
    }

    private StripMineTask(Direction direction,
                          int length,
                          int branchSpacing,
                          BlockPos depotChest,
                          Set<Block> targetOres,
                          boolean veinOnly,
                          boolean autoDescend) {
        this.direction = direction.getHorizontal() == -1 ? Direction.NORTH : direction;
        this.length = Math.max(0, length);
        this.branchSpacing = Math.max(0, branchSpacing);
        this.depotChest = depotChest == null ? null : depotChest.toImmutable();
        this.targetOres = targetOres == null || targetOres.isEmpty() ? OreScan.COMMON_ORES : OreScan.expandOreFamilies(targetOres);
        this.veinOnly = veinOnly;
        this.autoDescend = autoDescend;
    }

    @Override
    public String name() {
        return veinOnly ? "mine_vein" : "strip_mine";
    }

    @Override
    public String describe() {
        String ores = targetOres.stream()
                .map(Registries.BLOCK::getId)
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(","));
        return name() + " dir=" + direction
                + " distance=" + distanceCompleted + "/" + length
                + " tunnel_blocks=" + tunnelBlocksMined
                + " vein_blocks=" + veinBlocksMined
                + " phase=" + phase
                + (note.isBlank() ? "" : " note=" + note)
                + " ores=" + ores;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (veinOnly) {
            return veinBlocks.isEmpty() ? 0.0D : Math.min(0.95D, veinBlocksMined / (double) (veinBlocksMined + veinBlocks.size()));
        }
        return length == 0 ? 0.0D : Math.min(0.95D, (double) distanceCompleted / length);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.PREP;
        origin = bot.getBlockPos().toImmutable();
        activeDepotChest = resolveDepotChest(bot);
        steps.clear();
        blocksToMine.clear();
        veinBlocks.clear();
        queuedVeinBlocks.clear();
        currentStep = null;
        currentMiningBlock = null;
        currentVeinBlock = null;
        miningStarted = false;
        returningForFinalStop = false;
        descentStepsPlanned = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > Math.max(2400, (descentStepsPlanned + length + branchSpacing * Math.max(1, length / Math.max(1, branchSpacing))) * 400)) {
            fail("strip_mine_timeout");
            return;
        }
        switch (phase) {
            case PREP -> prep(bot);
            case TUNNEL -> tunnel(bot);
            case MINE_BLOCK -> mineBlock(bot);
            case SCAN_VEIN -> scanVein(bot);
            case MINE_VEIN -> mineVein(bot);
            case LIGHT -> light(bot);
            case MOVE -> move(bot);
            case RETURN -> returnToDepot(bot);
            case RETURN_DEPOSIT -> deposit(bot);
            case RETURN_TO_WORK -> returnToWork(bot);
            case DONE -> complete();
        }
    }

    private void prep(AIPlayerEntity bot) {
        if (veinOnly) {
            scanNearbyVeins(bot, bot.getBlockPos(), 6);
            if (veinBlocks.isEmpty()) {
                fail("no_ore_vein_in_range");
                return;
            }
            phase = Phase.MINE_VEIN;
            return;
        }
        buildPlan(bot);
        phase = Phase.TUNNEL;
    }

    private void buildPlan(AIPlayerEntity bot) {
        steps.clear();
        descentStepsPlanned = 0;
        BlockPos miningOrigin = origin;
        if (shouldDescendToOreLayer(bot)) {
            int targetY = Math.max(bot.getServerWorld().getBottomY() + 6, OreScan.preferredMiningY(targetOres));
            descentStepsPlanned = Math.max(0, origin.getY() - targetY);
            // FLOW-1:斜楼梯下挖——每级 横移1格 + 下降1格(1:1),形成可回头走的阶梯,
            // 而非竖直直坠。每级 DESCEND 步会挖 stand + 其上方 2 格(身位),其下方为实心地面。
            BlockPos cursor = origin;
            for (int step = 1; step <= descentStepsPlanned; step++) {
                cursor = cursor.offset(direction).down().toImmutable();
                steps.addLast(new Step(cursor, StepKind.DESCEND, 0));
            }
            miningOrigin = cursor;
            note = "descending_stairs_to_y:" + targetY;
        }
        Direction left = direction.rotateYCounterclockwise();
        Direction right = direction.rotateYClockwise();
        int branchDepth = branchSpacing <= 0 ? 0 : Math.min(branchSpacing, 8);
        for (int distance = 1; distance <= length; distance++) {
            BlockPos main = miningOrigin.offset(direction, distance);
            steps.addLast(new Step(main, StepKind.TUNNEL, distance));
            if (branchDepth > 0 && distance % branchSpacing == 0) {
                addBranch(main, left, branchDepth);
                addBranch(main, right, branchDepth);
            }
        }
    }

    private boolean shouldDescendToOreLayer(AIPlayerEntity bot) {
        if (!autoDescend || veinOnly || targetOres.stream().noneMatch(OreScan::isOreBlock)) {
            return false;
        }
        int targetY = Math.max(bot.getServerWorld().getBottomY() + 6, OreScan.preferredMiningY(targetOres));
        if (origin.getY() <= targetY + 2) {
            return false;
        }
        return !hasExposedOreNearby(bot, origin, 12, 8);
    }

    private boolean hasExposedOreNearby(AIPlayerEntity bot, BlockPos center, int horizontalRadius, int verticalRadius) {
        BlockPos min = center.add(-horizontalRadius, -verticalRadius, -horizontalRadius);
        BlockPos max = center.add(horizontalRadius, verticalRadius, horizontalRadius);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (Math.abs(pos.getY() - center.getY()) > 3) {
                continue;
            }
            if (OreScan.isOre(bot.getServerWorld().getBlockState(pos), targetOres) && isExposed(bot.getServerWorld(), pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExposed(ServerWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (world.getBlockState(pos.offset(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private void addBranch(BlockPos base, Direction side, int depth) {
        for (int branch = 1; branch <= depth; branch++) {
            steps.addLast(new Step(base.offset(side, branch), StepKind.TUNNEL, base.getManhattanDistance(origin)));
        }
        for (int branch = depth - 1; branch >= 0; branch--) {
            steps.addLast(new Step(base.offset(side, branch), StepKind.MOVE_ONLY, base.getManhattanDistance(origin)));
        }
    }

    private void tunnel(AIPlayerEntity bot) {
        if (shouldReturn(bot)) {
            beginReturn(bot);
            return;
        }
        currentStep = steps.pollFirst();
        if (currentStep == null) {
            note = "completed";
            phase = Phase.DONE;
            return;
        }
        distanceCompleted = Math.max(distanceCompleted, Math.min(length, currentStep.distance()));
        if (currentStep.kind() == StepKind.MOVE_ONLY) {
            phase = Phase.MOVE;
            move(bot);
            return;
        }
        if (!safeStandTarget(bot.getServerWorld(), currentStep.stand())) {
            fail("unsafe_tunnel_target: " + shortPos(currentStep.stand()));
            return;
        }
        blocksToMine.clear();
        addIfSolid(bot.getServerWorld(), currentStep.stand());
        addIfSolid(bot.getServerWorld(), currentStep.stand().up());
        if (currentStep.kind() == StepKind.DESCEND) {
            addIfSolid(bot.getServerWorld(), currentStep.stand().up(2));
        }
        if (blocksToMine.isEmpty()) {
            phase = Phase.SCAN_VEIN;
        } else {
            phase = Phase.MINE_BLOCK;
        }
    }

    private void mineBlock(AIPlayerEntity bot) {
        if (currentMiningBlock == null) {
            currentMiningBlock = blocksToMine.pollFirst();
            miningStarted = false;
            if (currentMiningBlock == null) {
                phase = Phase.SCAN_VEIN;
                return;
            }
        }
        if (bot.getServerWorld().getBlockState(currentMiningBlock).isAir()) {
            Standability.clearCache();
            currentMiningBlock = null;
            tunnelBlocksMined++;
            return;
        }
        if (OreScan.adjacentHazard(bot.getServerWorld(), currentMiningBlock)) {
            fail("hazard_near: " + shortPos(currentMiningBlock));
            return;
        }
        if (!canReach(bot, currentMiningBlock)) {
            fail("block_out_of_reach: " + shortPos(currentMiningBlock));
            return;
        }
        if (!miningStarted && bot.getActionPack().isMiningIdle()) {
            BlockState state = bot.getServerWorld().getBlockState(currentMiningBlock);
            ToolSelector.equipBestTool(bot, state);
            ActionResult result = MiningAction.startMining(bot, currentMiningBlock,
                    Direction.getFacing(bot.getEyePos().subtract(currentMiningBlock.toCenterPos())));
            if (result.isFailed()) {
                fail(result.reason());
                return;
            }
            miningStarted = true;
        }
    }

    private void scanVein(AIPlayerEntity bot) {
        scanNearbyVeins(bot, currentStep == null ? bot.getBlockPos() : currentStep.stand(), 3);
        if (!veinBlocks.isEmpty()) {
            phase = Phase.MINE_VEIN;
            return;
        }
        phase = Phase.LIGHT;
    }

    private void mineVein(AIPlayerEntity bot) {
        if (currentVeinBlock == null) {
            currentVeinBlock = veinBlocks.pollFirst();
            miningStarted = false;
            if (currentVeinBlock == null) {
                if (veinOnly) {
                    complete();
                } else {
                    phase = Phase.LIGHT;
                }
                return;
            }
        }
        if (bot.getServerWorld().getBlockState(currentVeinBlock).isAir()) {
            Standability.clearCache();
            currentVeinBlock = null;
            veinBlocksMined++;
            return;
        }
        if (!OreScan.isOre(bot.getServerWorld().getBlockState(currentVeinBlock), targetOres)) {
            currentVeinBlock = null;
            return;
        }
        if (OreScan.adjacentHazard(bot.getServerWorld(), currentVeinBlock)) {
            note = "skip_hazard_ore:" + shortPos(currentVeinBlock);
            currentVeinBlock = null;
            return;
        }
        if (!canReach(bot, currentVeinBlock)) {
            BlockPos stand = adjacentStand(bot, currentVeinBlock);
            if (stand == null) {
                note = "skip_unreachable_ore:" + shortPos(currentVeinBlock);
                currentVeinBlock = null;
                return;
            }
            if (!near(bot, stand)) {
                if (bot.getActionPack().isPathExecutorIdle()) {
                    ActionResult result = bot.getActionPack().startPathTo(stand);
                    if (result.isFailed()) {
                        note = "skip_unreachable_ore:" + result.reason();
                        currentVeinBlock = null;
                    }
                }
                return;
            }
            bot.getActionPack().stopAll();
        }
        if (!miningStarted && bot.getActionPack().isMiningIdle()) {
            BlockState state = bot.getServerWorld().getBlockState(currentVeinBlock);
            ToolSelector.equipBestTool(bot, state);
            ActionResult result = MiningAction.startMining(bot, currentVeinBlock,
                    Direction.getFacing(bot.getEyePos().subtract(currentVeinBlock.toCenterPos())));
            if (result.isFailed()) {
                note = result.reason();
                currentVeinBlock = null;
                return;
            }
            miningStarted = true;
        }
    }

    private void light(AIPlayerEntity bot) {
        if (!AIBotConfig.get().mining().placeTorches()
                || distanceCompleted == 0
                || distanceCompleted % 8 != 0
                || bot.getServerWorld().getLightLevel(net.minecraft.world.LightType.BLOCK, bot.getBlockPos()) >= 8) {
            phase = Phase.MOVE;
            return;
        }
        int torchSlot = InventoryAction.findItem(bot, Items.TORCH).orElse(-1);
        if (torchSlot < 0) {
            note = "no_torch";
            phase = Phase.MOVE;
            return;
        }
        InventoryAction.equipFromSlot(bot, torchSlot);
        Optional<BlockPos> torchPos = torchPosition(bot);
        if (torchPos.isPresent()) {
            ActionResult result = BuildAction.placeBlockAt(bot, torchPos.get());
            if (result.isFailed()) {
                note = "torch_failed:" + result.reason();
            }
        }
        phase = Phase.MOVE;
    }

    private void move(AIPlayerEntity bot) {
        if (currentStep == null || near(bot, currentStep.stand())) {
            bot.getActionPack().stopAll();
            phase = Phase.TUNNEL;
            return;
        }
        if ((currentStep.kind() == StepKind.TUNNEL || currentStep.kind() == StepKind.DESCEND)
                && currentStep.stand().getManhattanDistance(bot.getBlockPos()) <= 4) {
            if (bot.getActionPack().isWalkToIdle()) {
                bot.getActionPack().startWalkTo(currentStep.stand().toCenterPos());
            }
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(currentStep.stand());
            if (result.isFailed()) {
                fail("path_to_tunnel_failed: " + result.reason());
            }
        }
    }

    private boolean shouldReturn(AIPlayerEntity bot) {
        AIBotConfig.Mining mining = AIBotConfig.get().mining();
        if (freeMainSlots(bot) < mining.returnWhenFreeSlots()) {
            note = "inventory_near_full";
            return true;
        }
        ItemStack selected = bot.getInventory().getMainHandStack();
        if (selected.isDamageable()
                && selected.getMaxDamage() > 0
                && selected.getMaxDamage() - selected.getDamage() <= selected.getMaxDamage() * mining.toolDurabilityFloor()) {
            note = "tool_durability_low";
            return true;
        }
        return false;
    }

    private void beginReturn(AIPlayerEntity bot) {
        returnStand = bot.getBlockPos().toImmutable();
        returningForFinalStop = "tool_durability_low".equals(note);
        if (activeDepotChest == null) {
            phase = Phase.DONE;
            return;
        }
        phase = Phase.RETURN;
    }

    private void returnToDepot(AIPlayerEntity bot) {
        BlockPos stand = adjacentStand(bot, activeDepotChest);
        if (stand == null) {
            fail("no_stand_position_for_depot");
            return;
        }
        if (near(bot, stand)) {
            bot.getActionPack().stopAll();
            phase = Phase.RETURN_DEPOSIT;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(stand);
            if (result.isFailed()) {
                fail("return_path_failed: " + result.reason());
            }
        }
    }

    private void deposit(AIPlayerEntity bot) {
        Inventory container = ContainerAction.resolve(bot, activeDepotChest).orElse(null);
        if (container == null) {
            fail("depot_missing");
            return;
        }
        ContainerAction.TransferResult result = ContainerAction.depositOne(container, bot, depositFilter(), 64);
        if (result.movedAny()) {
            return;
        }
        if (returningForFinalStop) {
            phase = Phase.DONE;
            return;
        }
        phase = Phase.RETURN_TO_WORK;
    }

    private void returnToWork(AIPlayerEntity bot) {
        if (returnStand == null || near(bot, returnStand)) {
            bot.getActionPack().stopAll();
            phase = Phase.TUNNEL;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(returnStand);
            if (result.isFailed()) {
                fail("return_to_work_failed: " + result.reason());
            }
        }
    }

    private Predicate<ItemStack> depositFilter() {
        return stack -> !ContainerAction.isReservedTool(stack)
                && !stack.isOf(Items.TORCH)
                && !stack.contains(DataComponentTypes.FOOD);
    }

    private BlockPos resolveDepotChest(AIPlayerEntity bot) {
        if (depotChest != null) {
            return depotChest;
        }
        return BotMemoryStore.INSTANCE.of(bot.getUuid())
                .placeIn(bot.getServerWorld(), "depot", "home", "base", "chest")
                .flatMap(pos -> ContainerAction.resolve(bot, pos).isPresent()
                        ? Optional.of(pos.toImmutable())
                        : ContainerTask.nearestContainerNear(bot, pos, 4))
                .orElse(null);
    }

    private void addIfSolid(ServerWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isAir()) {
            blocksToMine.addLast(pos.toImmutable());
        }
    }

    private void scanNearbyVeins(AIPlayerEntity bot, BlockPos center, int radius) {
        BlockPos.stream(center.add(-radius, -radius, -radius), center.add(radius, radius, radius))
                .map(BlockPos::toImmutable)
                .filter(pos -> OreScan.isOre(bot.getServerWorld().getBlockState(pos), targetOres))
                .sorted(Comparator.comparingDouble(pos -> pos.getSquaredDistance(bot.getBlockPos())))
                .findFirst()
                .ifPresent(seed -> OreScan.veinFrom(bot.getServerWorld(), seed, targetOres, MAX_VEIN_BLOCKS)
                        .forEach(pos -> {
                            if (queuedVeinBlocks.add(pos)) {
                                veinBlocks.addLast(pos);
                            }
                        }));
    }

    private Optional<BlockPos> torchPosition(AIPlayerEntity bot) {
        BlockPos base = bot.getBlockPos();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos pos = base.offset(direction);
            if (bot.getServerWorld().getBlockState(pos).isAir()
                    && !bot.getServerWorld().getBlockState(pos.down()).isAir()) {
                return Optional.of(pos.toImmutable());
            }
        }
        return Optional.empty();
    }

    private static boolean safeStandTarget(ServerWorld world, BlockPos stand) {
        if (OreScan.adjacentHazard(world, stand)) {
            return false;
        }
        if (!world.getFluidState(stand).isEmpty() || !world.getFluidState(stand.up()).isEmpty()) {
            return false;
        }
        return !world.getBlockState(stand.down()).isAir()
                && world.getFluidState(stand.down()).isEmpty();
    }

    private static boolean canReach(AIPlayerEntity bot, BlockPos target) {
        return bot.getEyePos().squaredDistanceTo(target.toCenterPos()) <= REACH_SQUARED;
    }

    private static boolean near(AIPlayerEntity bot, BlockPos target) {
        return bot.getBlockPos().getSquaredDistance(target) <= 1.0D;
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos target) {
        Standability.clearCache();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(direction);
            if (Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate.toImmutable();
            }
        }
        return null;
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

    private static String shortPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record Step(BlockPos stand, StepKind kind, int distance) {
    }
}
