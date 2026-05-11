package com.aiplayer.util;

import com.aiplayer.entity.AiPlayerEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

public class ActionUtils {

        public static Player findNearestPlayer(AiPlayerEntity aiPlayer) {
        List<? extends Player> players = aiPlayer.level().players();

        if (players.isEmpty()) {
            return null;
        }

        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }

            double distance = aiPlayer.distanceTo(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

        public static Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");
        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }

        ResourceLocation resourceLocation = ResourceLocation.parse(blockName);
        Block block = BuiltInRegistries.BLOCK.getValue(resourceLocation);
        return block != null ? block : Blocks.AIR;
    }
}
