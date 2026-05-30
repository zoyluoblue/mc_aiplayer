package io.github.zoyluo.aibot.perception;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogFields;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PerceptionCollector {
    private PerceptionCollector() {
    }

    public static PerceptionSnapshot collect(AIPlayerEntity bot) {
        long started = System.currentTimeMillis();
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

        BlockScan blockScan = collectBlocks(world, center, Math.min(config.radius(), 8), config.maxBlocks());
        List<PerceptionSnapshot.NearbyEntity> entities = collectEntities(bot, world, config.radius(), config.maxEntities());
        List<PerceptionSnapshot.NearbyItem> items = collectItems(bot, world, config.radius(), config.maxItems());
        PerceptionSnapshot.Highlights highlights = buildHighlights(blockScan.highlights(), entities);
        List<PerceptionSnapshot.NearbyBlock> blocks = config.includeRawLists() ? blockScan.blocks() : List.of();
        List<PerceptionSnapshot.NearbyEntity> rawEntities = config.includeRawLists() ? entities : List.of();
        List<PerceptionSnapshot.NearbyItem> rawItems = config.includeRawLists() ? items : List.of();
        TaskStatus status = TaskManager.INSTANCE.status(bot);
        PerceptionSnapshot.TaskInfo task = new PerceptionSnapshot.TaskInfo(
                status.name(),
                status.state().name(),
                round(status.progress()),
                status.elapsedTicks(),
                status.description(),
                status.failureReason());
        long elapsed = System.currentTimeMillis() - started;
        BotLog.perception(bot, "snapshot",
                "hp", bot.getHealth(),
                "hunger", bot.getHungerManager().getFoodLevel(),
                "pos", LogFields.pos(center),
                "holding", Registries.ITEM.getId(bot.getMainHandStack().getItem()),
                "blocks_n", blockScan.blocks().size(),
                "entities_n", entities.size(),
                "items_n", items.size(),
                "light", world.getLightLevel(center));
        if (elapsed > 10L) {
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.PERCEPTION, bot, "snapshot_slow", "elapsed_ms", elapsed);
        }
        return new PerceptionSnapshot(
                self,
                task,
                highlights,
                blocks,
                rawEntities,
                rawItems,
                new PerceptionSnapshot.TimeInfo(world.getTimeOfDay() % 24000L, world.isDay(), world.getLightLevel(center)));
    }

    private static BlockScan collectBlocks(ServerWorld world, BlockPos center, int radius, int limit) {
        List<PerceptionSnapshot.NearbyBlock> blocks = new ArrayList<>();
        Map<String, List<PerceptionSnapshot.NearbyBlock>> highlights = new HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    boolean water = state.getFluidState().isIn(FluidTags.WATER);
                    if (state.isAir() && !water) {
                        continue;
                    }
                    double distance = Math.sqrt(center.getSquaredDistance(pos));
                    blocks.add(new PerceptionSnapshot.NearbyBlock(
                            Registries.BLOCK.getId(state.getBlock()).toString(),
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            round(distance)));
                    addHighlights(highlights, state, pos, round(distance), water);
                }
            }
        }
        blocks.sort(Comparator.comparingDouble(PerceptionSnapshot.NearbyBlock::distance));
        highlights.replaceAll((ignored, values) -> values.stream()
                .sorted(Comparator.comparingDouble(PerceptionSnapshot.NearbyBlock::distance))
                .limit(2)
                .toList());
        return new BlockScan(blocks.stream().limit(limit).toList(), highlights);
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

    private static void addHighlights(Map<String, List<PerceptionSnapshot.NearbyBlock>> highlights,
                                      BlockState state,
                                      BlockPos pos,
                                      double distance,
                                      boolean water) {
        if (water) {
            addHighlight(highlights, "nearest_water", "minecraft:water", pos, distance);
        }
        if (state.isIn(BlockTags.LOGS)) {
            addHighlight(highlights, "nearest_tree", Registries.BLOCK.getId(state.getBlock()).toString(), pos, distance);
        }
        if (state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.DEEPSLATE)) {
            addHighlight(highlights, "nearest_stone", Registries.BLOCK.getId(state.getBlock()).toString(), pos, distance);
        }
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        if (id.endsWith("_ore")) {
            addHighlight(highlights, "nearest_ore", id, pos, distance);
        }
        if (state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER)) {
            addHighlight(highlights, "nearest_furnace", id, pos, distance);
        }
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL)) {
            addHighlight(highlights, "nearest_chest", id, pos, distance);
        }
        if (state.isIn(BlockTags.BEDS)) {
            addHighlight(highlights, "nearest_bed", id, pos, distance);
        }
        if (state.isOf(Blocks.CRAFTING_TABLE)) {
            addHighlight(highlights, "nearest_crafting_table", id, pos, distance);
        }
    }

    private static void addHighlight(Map<String, List<PerceptionSnapshot.NearbyBlock>> highlights,
                                     String key,
                                     String id,
                                     BlockPos pos,
                                     double distance) {
        highlights.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new PerceptionSnapshot.NearbyBlock(id, pos.getX(), pos.getY(), pos.getZ(), distance));
    }

    private static PerceptionSnapshot.Highlights buildHighlights(Map<String, List<PerceptionSnapshot.NearbyBlock>> blockHighlights,
                                                                 List<PerceptionSnapshot.NearbyEntity> entities) {
        List<PerceptionSnapshot.NearbyEntity> hostiles = entities.stream()
                .filter(PerceptionSnapshot.NearbyEntity::hostile)
                .limit(2)
                .toList();
        return new PerceptionSnapshot.Highlights(
                blockHighlights.getOrDefault("nearest_tree", List.of()),
                blockHighlights.getOrDefault("nearest_stone", List.of()),
                blockHighlights.getOrDefault("nearest_ore", List.of()),
                blockHighlights.getOrDefault("nearest_water", List.of()),
                blockHighlights.getOrDefault("nearest_furnace", List.of()),
                blockHighlights.getOrDefault("nearest_chest", List.of()),
                blockHighlights.getOrDefault("nearest_bed", List.of()),
                blockHighlights.getOrDefault("nearest_crafting_table", List.of()),
                hostiles);
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private record BlockScan(List<PerceptionSnapshot.NearbyBlock> blocks,
                             Map<String, List<PerceptionSnapshot.NearbyBlock>> highlights) {
    }
}
