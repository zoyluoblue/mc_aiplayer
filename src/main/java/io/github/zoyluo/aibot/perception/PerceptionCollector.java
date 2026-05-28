package io.github.zoyluo.aibot.perception;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PerceptionCollector {
    private PerceptionCollector() {
    }

    public static PerceptionSnapshot collect(AIPlayerEntity bot) {
        AIBotConfig.Perception config = AIBotConfig.get().perception();
        ServerWorld world = bot.getServerWorld();
        BlockPos center = bot.getBlockPos();
        PerceptionSnapshot.SelfState self = new PerceptionSnapshot.SelfState(
                bot.getX(),
                bot.getY(),
                bot.getZ(),
                bot.getYaw(),
                bot.getPitch(),
                bot.getHealth(),
                bot.getHungerManager().getFoodLevel(),
                Registries.ITEM.getId(bot.getMainHandStack().getItem()).toString(),
                InventoryAction.summarize(bot));

        return new PerceptionSnapshot(
                self,
                collectBlocks(world, center, Math.min(config.radius(), 4), config.maxBlocks()),
                collectEntities(bot, world, config.radius(), config.maxEntities()),
                collectItems(bot, world, config.radius(), config.maxItems()),
                new PerceptionSnapshot.TimeInfo(world.getTimeOfDay() % 24000L, world.isDay(), world.getLightLevel(center)));
    }

    private static List<PerceptionSnapshot.NearbyBlock> collectBlocks(ServerWorld world, BlockPos center, int radius, int limit) {
        List<PerceptionSnapshot.NearbyBlock> blocks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    double distance = Math.sqrt(center.getSquaredDistance(pos));
                    blocks.add(new PerceptionSnapshot.NearbyBlock(
                            Registries.BLOCK.getId(state.getBlock()).toString(),
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            round(distance)));
                }
            }
        }
        blocks.sort(Comparator.comparingDouble(PerceptionSnapshot.NearbyBlock::distance));
        return blocks.stream().limit(limit).toList();
    }

    private static List<PerceptionSnapshot.NearbyEntity> collectEntities(AIPlayerEntity bot, ServerWorld world, int radius, int limit) {
        return world.getOtherEntities(bot, bot.getBoundingBox().expand(radius), entity -> entity instanceof LivingEntity)
                .stream()
                .sorted(Comparator.comparingDouble(bot::distanceTo))
                .limit(limit)
                .map(entity -> toNearbyEntity(bot, entity))
                .toList();
    }

    private static PerceptionSnapshot.NearbyEntity toNearbyEntity(AIPlayerEntity bot, Entity entity) {
        float hp = entity instanceof LivingEntity living ? living.getHealth() : 0.0F;
        return new PerceptionSnapshot.NearbyEntity(
                Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                round(entity.getX()),
                round(entity.getY()),
                round(entity.getZ()),
                round(bot.distanceTo(entity)),
                entity instanceof Monster,
                hp);
    }

    private static List<PerceptionSnapshot.NearbyItem> collectItems(AIPlayerEntity bot, ServerWorld world, int radius, int limit) {
        return world.getOtherEntities(bot, bot.getBoundingBox().expand(radius), entity -> entity instanceof ItemEntity)
                .stream()
                .sorted(Comparator.comparingDouble(bot::distanceTo))
                .limit(limit)
                .map(entity -> {
                    ItemEntity item = (ItemEntity) entity;
                    return new PerceptionSnapshot.NearbyItem(
                            Registries.ITEM.getId(item.getStack().getItem()).toString(),
                            round(item.getX()),
                            round(item.getY()),
                            round(item.getZ()));
                })
                .toList();
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
