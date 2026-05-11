package com.aiplayer.action.actions;

import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class PlaceBlockAction extends BaseAction {
    private Block blockToPlace;
    private BlockPos targetPos;
    private int ticksRunning;
    private static final int MAX_TICKS = 200;

    public PlaceBlockAction(AiPlayerEntity aiPlayer, Task task) {
        super(aiPlayer, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("block");
        int x = task.getIntParameter("x", 0);
        int y = task.getIntParameter("y", 0);
        int z = task.getIntParameter("z", 0);
        
        targetPos = new BlockPos(x, y, z);
        ticksRunning = 0;
        
        blockToPlace = parseBlock(blockName);
        
        if (blockToPlace == null || blockToPlace == Blocks.AIR) {
            result = ActionResult.failure("无效的方块类型：" + blockName);
            return;
        }
        Item item = SurvivalUtils.getPlacementItem(blockToPlace);
        if (!aiPlayer.hasItem(item, 1)) {
            result = ActionResult.failure("背包里没有可放置的 " + blockToPlace.getName().getString());
            return;
        }
        
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("放置方块超时");
            return;
        }
        
        if (!SurvivalUtils.moveNear(aiPlayer, targetPos, 4.0)) {
            return;
        }
        
        BlockState currentState = aiPlayer.level().getBlockState(targetPos);
        if (!currentState.isAir() && !currentState.liquid()) {
            result = ActionResult.failure("目标位置不是空的");
            return;
        }
        
        if (!SurvivalUtils.placeBlock(aiPlayer, targetPos, blockToPlace)) {
            result = ActionResult.failure("背包里没有可放置的 " + blockToPlace.getName().getString());
            return;
        }
        result = ActionResult.success("已放置 " + blockToPlace.getName().getString());
    }

    @Override
    protected void onCancel() {
        aiPlayer.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "放置 " + blockToPlace.getName().getString() + " 到 " + targetPos;
    }

    private Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");
        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }
        ResourceLocation resourceLocation = ResourceLocation.parse(blockName);
        return BuiltInRegistries.BLOCK.getValue(resourceLocation);
    }
}
