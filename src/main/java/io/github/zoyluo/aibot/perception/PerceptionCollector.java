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
        PerceptionSnapshot.Highlights highlights = buildHighlights(bot, blockScan.highlights(), entities);
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
        if (state.getFluidState().isIn(FluidTags.LAVA)) {
            addHighlight(highlights, "nearest_lava", "minecraft:lava", pos, distance);
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

    private static PerceptionSnapshot.Highlights buildHighlights(AIPlayerEntity bot,
                                                                 Map<String, List<PerceptionSnapshot.NearbyBlock>> blockHighlights,
                                                                 List<PerceptionSnapshot.NearbyEntity> entities) {
        List<PerceptionSnapshot.NearbyEntity> hostiles = entities.stream()
                .filter(PerceptionSnapshot.NearbyEntity::hostile)
                .limit(2)
                .toList();
        // 治"感知瞎":中立生物(牛羊猪村民)≤3 只进快照,杀牛/繁殖前不用先 scan 才知道身边有牛。
        List<PerceptionSnapshot.NearbyEntity> passive = entities.stream()
                .filter(entity -> !entity.hostile() && !"minecraft:player".equals(entity.type()))
                .limit(3)
                .toList();
        return new PerceptionSnapshot.Highlights(
                blockHighlights.getOrDefault("nearest_tree", List.of()),
                blockHighlights.getOrDefault("nearest_stone", List.of()),
                blockHighlights.getOrDefault("nearest_ore", List.of()),
                blockHighlights.getOrDefault("nearest_water", List.of()),
                blockHighlights.getOrDefault("nearest_lava", List.of()),
                blockHighlights.getOrDefault("nearest_furnace", List.of()),
                blockHighlights.getOrDefault("nearest_chest", List.of()),
                blockHighlights.getOrDefault("nearest_bed", List.of()),
                blockHighlights.getOrDefault("nearest_crafting_table", List.of()),
                hostiles,
                ownerInfo(bot),
                passive,
                nearestOtherPlayer(bot));
    }

    /** 主人状态(不限半径——"主人在 80 格外"本身就是关键信息):位置/血量/5s 内是否被打+被谁打。 */
    private static PerceptionSnapshot.OwnerInfo ownerInfo(AIPlayerEntity bot) {
        return io.github.zoyluo.aibot.manager.AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(uuid -> bot.getServer().getPlayerManager().getPlayer(uuid))
                .filter(owner -> owner != null && owner.getServerWorld() == bot.getServerWorld())
                .map(owner -> new PerceptionSnapshot.OwnerInfo(
                        owner.getGameProfile().getName(),
                        round(owner.getX()),
                        round(owner.getY()),
                        round(owner.getZ()),
                        round(bot.distanceTo(owner)),
                        owner.getHealth(),
                        io.github.zoyluo.aibot.brain.OwnerEventListener.INSTANCE.recentlyHurt(owner.getUuid()),
                        io.github.zoyluo.aibot.brain.OwnerEventListener.INSTANCE.lastAttacker(owner.getUuid())))
                .orElse(null);
    }

    /** 最近的非主人真人玩家(24 格内):有生人靠近 bot 该知道。排除其它 bot。 */
    private static PerceptionSnapshot.NearbyEntity nearestOtherPlayer(AIPlayerEntity bot) {
        java.util.UUID ownerUuid = io.github.zoyluo.aibot.manager.AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null);
        return bot.getServerWorld().getPlayers().stream()
                .filter(player -> player != bot && !(player instanceof AIPlayerEntity))
                .filter(player -> ownerUuid == null || !player.getUuid().equals(ownerUuid))
                .filter(player -> bot.distanceTo(player) <= 24.0D)
                .min(Comparator.comparingDouble(bot::distanceTo))
                .map(player -> toNearbyEntity(bot, player))
                .orElse(null);
    }

    /**
     * scan_surroundings 工具的"上帝视角"报告:比常规快照更大的半径(方块 12/实体 24),
     * 一次性给出附近岩浆/水/矿/树/容器/敌对生物/掉落物的坐标与距离。key 缺席=范围内没有。
     */
    public static String scanReport(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos center = bot.getBlockPos();
        BlockScan blockScan = collectBlocks(world, center, 12, 0);
        List<PerceptionSnapshot.NearbyEntity> entities = collectEntities(bot, world, 24, 16);
        List<PerceptionSnapshot.NearbyItem> items = collectItems(bot, world, 16, 10);
        Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("center", center.getX() + "," + center.getY() + "," + center.getZ());
        report.put("scan_radius_blocks", 12);
        report.put("scan_radius_entities", 24);
        report.put("biome", world.getBiome(center).getKey()
                .map(key -> key.getValue().toString()).orElse("unknown"));
        report.put("light", world.getLightLevel(center));
        report.put("is_day", world.isDay());
        report.put("nearby", blockScan.highlights());
        report.put("entities", entities);
        report.put("ground_items", items);
        report.put("note", "absent keys mean none found within scan radius");
        return new com.google.gson.Gson().toJson(report);
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private record BlockScan(List<PerceptionSnapshot.NearbyBlock> blocks,
                             Map<String, List<PerceptionSnapshot.NearbyBlock>> highlights) {
    }
}
