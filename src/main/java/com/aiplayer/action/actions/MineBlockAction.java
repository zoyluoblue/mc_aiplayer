package com.aiplayer.action.actions;

import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.recipe.MiningGoalResolver;
import com.aiplayer.recipe.MiningResource;
import com.aiplayer.recipe.SurvivalRecipeBook;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class MineBlockAction extends BaseAction {
    private Block targetBlock;
    private int targetQuantity;
    private int minedCount;
    private BlockPos currentTarget;
    private BlockPos tunnelCursor;
    private int miningDirectionX;
    private int miningDirectionZ;
    private int ticksRunning;
    private int breakTicks;
    private MakeItemAction delegate;
    private static final int MAX_TICKS = 24000;

    public MineBlockAction(AiPlayerEntity aiPlayer, Task task) {
        super(aiPlayer, task);
    }

    @Override
    protected void onStart() {
        String blockName = miningTargetParameter();
        if (blockName == null) {
            result = ActionResult.failure("挖矿任务缺少目标：需要 block、target、blockType、item 或 source 参数");
            return;
        }
        targetQuantity = task.getIntParameter("quantity", 8);
        String delegatedItem = itemForMiningTarget(blockName);
        if (delegatedItem != null) {
            targetBlock = MiningGoalResolver.resolve(blockName)
                .map(MiningGoalResolver.Goal::profile)
                .map(this::blockFromProfile)
                .orElseGet(() -> MiningResource.findByMineTarget(blockName).map(this::blockFromProfile).orElse(Blocks.AIR));
            startDelegate(delegatedItem);
            return;
        }
        targetBlock = parseBlock(blockName);
        minedCount = 0;
        ticksRunning = 0;
        breakTicks = 0;
        currentTarget = null;
        chooseMiningDirection();
        tunnelCursor = aiPlayer.blockPosition().offset(miningDirectionX, 0, miningDirectionZ);

        if (targetBlock == null || targetBlock == Blocks.AIR) {
            result = ActionResult.failure("无效的方块类型：" + blockName);
            return;
        }

        if (SurvivalUtils.requiresPickaxe(targetBlock) && !preparePickaxe()) {
            result = ActionResult.failure("需要先收集木头制作镐，才能像生存玩家一样挖掘 " + targetBlock.getName().getString());
        }
    }

    @Override
    protected void onTick() {
        if (delegate != null) {
            delegate.tick();
            if (delegate.isComplete()) {
                result = delegate.getResult();
            }
            return;
        }
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("挖掘超时，已获得 " + minedCount + " 个目标方块");
            return;
        }

        if (minedCount >= targetQuantity) {
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
            result = ActionResult.success("已挖到 " + minedCount + " 个 " + targetBlock.getName().getString());
            return;
        }

        if (SurvivalUtils.requiresPickaxe(targetBlock) && !preparePickaxe()) {
            result = ActionResult.failure("没有可用的镐，无法继续挖掘");
            return;
        }

        if (currentTarget == null || aiPlayer.level().getBlockState(currentTarget).isAir()) {
            currentTarget = findNextTarget().orElse(null);
            breakTicks = 0;
        }

        if (currentTarget == null) {
            currentTarget = nextTunnelBlock();
            if (currentTarget == null) {
                tunnelCursor = tunnelCursor.offset(miningDirectionX, 0, miningDirectionZ);
                return;
            }
        }

        SurvivalUtils.equipBestToolForBlock(aiPlayer, aiPlayer.level().getBlockState(currentTarget).getBlock());
        if (!SurvivalUtils.moveNear(aiPlayer, currentTarget, 3.0)) {
            breakTicks = 0;
            return;
        }

        aiPlayer.lookAtWorkTarget(currentTarget);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < getBreakDelay(currentTarget)) {
            return;
        }

        boolean targetMatches = aiPlayer.level().getBlockState(currentTarget).getBlock() == targetBlock;
        if (SurvivalUtils.breakBlock(aiPlayer, currentTarget) && targetMatches) {
            minedCount++;
        }
        currentTarget = null;
        breakTicks = 0;
    }

    @Override
    protected void onCancel() {
        if (delegate != null) {
            delegate.cancel();
            return;
        }
        aiPlayer.getNavigation().stop();
        aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
    }

    @Override
    public String getDescription() {
        if (delegate != null) {
            return delegate.getDescription();
        }
        return "挖掘 " + targetQuantity + " 个 " + targetBlock.getName().getString() + "，已获得 " + minedCount;
    }

    private boolean preparePickaxe() {
        if (needsIronPickaxe()) {
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("pickaxe"));
            return hasIronOrBetterPickaxe();
        }
        if (needsStonePickaxe()) {
            aiPlayer.craftStonePickaxe();
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("pickaxe"));
            return hasStoneOrBetterPickaxe();
        }
        if (aiPlayer.getBestToolStackFor("pickaxe").isEmpty()) {
            aiPlayer.craftWoodenPickaxe();
        }
        aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("pickaxe"));
        return !aiPlayer.getBestToolStackFor("pickaxe").isEmpty();
    }

    private boolean needsStonePickaxe() {
        return targetBlock == Blocks.IRON_ORE || targetBlock == Blocks.DEEPSLATE_IRON_ORE ||
            targetBlock == Blocks.COPPER_ORE || targetBlock == Blocks.DEEPSLATE_COPPER_ORE ||
            targetBlock == Blocks.LAPIS_ORE || targetBlock == Blocks.DEEPSLATE_LAPIS_ORE;
    }

    private boolean needsIronPickaxe() {
        return targetBlock == Blocks.GOLD_ORE || targetBlock == Blocks.DEEPSLATE_GOLD_ORE ||
            targetBlock == Blocks.DIAMOND_ORE || targetBlock == Blocks.DEEPSLATE_DIAMOND_ORE ||
            targetBlock == Blocks.REDSTONE_ORE || targetBlock == Blocks.DEEPSLATE_REDSTONE_ORE ||
            targetBlock == Blocks.EMERALD_ORE || targetBlock == Blocks.DEEPSLATE_EMERALD_ORE;
    }

    private boolean hasStoneOrBetterPickaxe() {
        return aiPlayer.getBestToolStackFor("pickaxe").is(Items.STONE_PICKAXE) || hasIronOrBetterPickaxe();
    }

    private boolean hasIronOrBetterPickaxe() {
        return aiPlayer.getBestToolStackFor("pickaxe").is(Items.IRON_PICKAXE) ||
            aiPlayer.getBestToolStackFor("pickaxe").is(Items.DIAMOND_PICKAXE) ||
            aiPlayer.getBestToolStackFor("pickaxe").is(Items.NETHERITE_PICKAXE);
    }

    private Optional<BlockPos> findNextTarget() {
        return SurvivalUtils.findNearestBlock(aiPlayer, state -> state.getBlock() == targetBlock, 18, 10);
    }

    private BlockPos nextTunnelBlock() {
        for (int i = 0; i < 4; i++) {
            BlockPos base = tunnelCursor.offset(miningDirectionX * i, 0, miningDirectionZ * i);
            BlockPos[] candidates = {base, base.above()};
            for (BlockPos pos : candidates) {
                Block block = aiPlayer.level().getBlockState(pos).getBlock();
                if (!aiPlayer.level().getBlockState(pos).isAir() && block != Blocks.BEDROCK) {
                    return pos;
                }
            }
        }
        return null;
    }

    private int getBreakDelay(BlockPos pos) {
        return SurvivalUtils.getBreakDelay(aiPlayer, aiPlayer.level().getBlockState(pos).getBlock());
    }

    private void chooseMiningDirection() {
        net.minecraft.world.entity.player.Player nearestPlayer = findNearestPlayer();
        if (nearestPlayer == null) {
            miningDirectionX = 1;
            miningDirectionZ = 0;
            return;
        }
        net.minecraft.world.phys.Vec3 lookVec = nearestPlayer.getLookAngle();
        double angle = Math.atan2(lookVec.z, lookVec.x) * 180.0 / Math.PI;
        angle = (angle + 360) % 360;
        if (angle >= 315 || angle < 45) {
            miningDirectionX = 1;
            miningDirectionZ = 0;
        } else if (angle >= 45 && angle < 135) {
            miningDirectionX = 0;
            miningDirectionZ = 1;
        } else if (angle >= 135 && angle < 225) {
            miningDirectionX = -1;
            miningDirectionZ = 0;
        } else {
            miningDirectionX = 0;
            miningDirectionZ = -1;
        }
    }

    private net.minecraft.world.entity.player.Player findNearestPlayer() {
        java.util.List<? extends net.minecraft.world.entity.player.Player> players = aiPlayer.level().players();
        net.minecraft.world.entity.player.Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (net.minecraft.world.entity.player.Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            double distance = aiPlayer.distanceTo(player);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private Block parseBlock(String blockName) {
        MiningResource.Profile miningProfile = MiningResource.findByMineTarget(blockName).orElse(null);
        if (miningProfile != null) {
            return blockFromProfile(miningProfile);
        }
        String normalized = blockName.toLowerCase(Locale.ROOT).replace(" ", "_");
        Map<String, String> resourceToOre = new HashMap<>() {{
            put("iron", "iron_ore");
            put("铁", "iron_ore");
            put("diamond", "diamond_ore");
            put("钻石", "diamond_ore");
            put("coal", "coal_ore");
            put("煤", "coal_ore");
            put("gold", "gold_ore");
            put("金", "gold_ore");
            put("copper", "copper_ore");
            put("铜", "copper_ore");
            put("redstone", "redstone_ore");
            put("红石", "redstone_ore");
            put("lapis", "lapis_ore");
            put("青金石", "lapis_ore");
            put("emerald", "emerald_ore");
            put("绿宝石", "emerald_ore");
            put("stone", "stone");
            put("石头", "stone");
        }};
        normalized = resourceToOre.getOrDefault(normalized, normalized);
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        ResourceLocation resourceLocation = ResourceLocation.parse(normalized);
        return BuiltInRegistries.BLOCK.getValue(resourceLocation);
    }

    private String itemForMiningTarget(String blockName) {
        MiningGoalResolver.Goal goal = MiningGoalResolver.resolve(blockName).orElse(null);
        if (goal != null) {
            return goal.finalItem();
        }
        String normalizedItem = SurvivalRecipeBook.normalizeItemId(blockName);
        if (SurvivalRecipeBook.findLocal(normalizedItem).isPresent()
            || SurvivalRecipeBook.explicitBaseSource(normalizedItem).isPresent()) {
            return normalizedItem;
        }
        return null;
    }

    private String miningTargetParameter() {
        String[] keys = {"block", "target", "blockType", "item", "source"};
        for (String key : keys) {
            String value = task.getStringParameter(key, null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void startDelegate(String item) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("item", item);
        parameters.put("quantity", Math.max(1, targetQuantity));
        parameters.put("task_id", task.getTaskId());
        delegate = new MakeItemAction(aiPlayer, new Task("make_item", parameters));
        delegate.start();
        if (delegate.isComplete()) {
            result = delegate.getResult();
        }
    }

    private Block blockFromProfile(MiningResource.Profile profile) {
        if (profile.blockIds().isEmpty()) {
            return Blocks.AIR;
        }
        ResourceLocation resourceLocation = ResourceLocation.parse(profile.blockIds().getFirst());
        return BuiltInRegistries.BLOCK.getValue(resourceLocation);
    }
}
