package com.aiplayer.snapshot;

import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class WorldSnapshot {
    private static final int CHEST_HORIZONTAL_RADIUS = 12;
    private static final int CHEST_VERTICAL_RADIUS = 4;
    private static final int BLOCK_HORIZONTAL_RADIUS = 16;
    private static final int BLOCK_VERTICAL_RADIUS = 8;
    private static final int ENTITY_RADIUS = 16;
    private static final int MAX_CHESTS = 10;
    private static final int MAX_BLOCKS = 80;
    private static final int MAX_ENTITIES = 40;
    private static final int MAX_CAVES = 8;

    private final String playerCommand;
    private final AiState ai;
    private final WorldState world;
    private final List<ChestSnapshot> nearbyChests;
    private final List<BlockSnapshot> nearbyBlocks;
    private final List<CaveSnapshot> nearbyCaves;
    private final List<EntitySnapshot> nearbyEntities;

    private WorldSnapshot(
        String playerCommand,
        AiState ai,
        WorldState world,
        List<ChestSnapshot> nearbyChests,
        List<BlockSnapshot> nearbyBlocks,
        List<CaveSnapshot> nearbyCaves,
        List<EntitySnapshot> nearbyEntities
    ) {
        this.playerCommand = playerCommand;
        this.ai = ai;
        this.world = world;
        this.nearbyChests = List.copyOf(nearbyChests);
        this.nearbyBlocks = List.copyOf(nearbyBlocks);
        this.nearbyCaves = List.copyOf(nearbyCaves);
        this.nearbyEntities = List.copyOf(nearbyEntities);
    }

    public static WorldSnapshot capture(AiPlayerEntity aiPlayer, String playerCommand) {
        return new WorldSnapshot(
            playerCommand == null ? "" : playerCommand,
            AiState.capture(aiPlayer),
            WorldState.capture(aiPlayer),
            scanChests(aiPlayer),
            scanBlocks(aiPlayer),
            scanCaves(aiPlayer),
            scanEntities(aiPlayer)
        );
    }

    public static WorldSnapshot empty(String playerCommand) {
        return new WorldSnapshot(
            playerCommand == null ? "" : playerCommand,
            new AiState(
                "test",
                new int[] {0, 64, 0},
                20.0F,
                20.0F,
                "minecraft:air",
                Map.of(
                    "head", "minecraft:air",
                    "chest", "minecraft:air",
                    "legs", "minecraft:air",
                    "feet", "minecraft:air"
                ),
                List.of(),
                "",
                false,
                false,
                false
            ),
            new WorldState("minecraft:overworld", "unknown", 0L, "day", 0),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    public String getPlayerCommand() {
        return playerCommand;
    }

    public AiState getAi() {
        return ai;
    }

    public WorldState getWorld() {
        return world;
    }

    public List<ChestSnapshot> getNearbyChests() {
        return nearbyChests;
    }

    public List<BlockSnapshot> getNearbyBlocks() {
        return nearbyBlocks;
    }

    public List<CaveSnapshot> getNearbyCaves() {
        return nearbyCaves;
    }

    public List<EntitySnapshot> getNearbyEntities() {
        return nearbyEntities;
    }

    public Map<String, Integer> availableItems() {
        java.util.HashMap<String, Integer> items = new java.util.HashMap<>();
        for (InventorySnapshot stack : ai.getInventory()) {
            if (stack.getCount() > 0 && !"minecraft:air".equals(stack.getItem())) {
                items.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        for (ChestSnapshot chest : nearbyChests) {
            if (!chest.isReachable()) {
                continue;
            }
            for (InventorySnapshot stack : chest.getItems()) {
                if (stack.getCount() > 0 && !"minecraft:air".equals(stack.getItem())) {
                    items.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }
        return Map.copyOf(items);
    }

    private static List<ChestSnapshot> scanChests(AiPlayerEntity aiPlayer) {
        Level level = aiPlayer.level();
        BlockPos center = aiPlayer.blockPosition();
        List<BlockPos> positions = BlockPos.betweenClosedStream(
                center.offset(-CHEST_HORIZONTAL_RADIUS, -CHEST_VERTICAL_RADIUS, -CHEST_HORIZONTAL_RADIUS),
                center.offset(CHEST_HORIZONTAL_RADIUS, CHEST_VERTICAL_RADIUS, CHEST_HORIZONTAL_RADIUS)
            )
            .map(BlockPos::immutable)
            .filter(pos -> level.getBlockEntity(pos) instanceof Container)
            .sorted(Comparator.comparingDouble(pos -> pos.distSqr(center)))
            .limit(MAX_CHESTS)
            .toList();

        List<ChestSnapshot> chests = new ArrayList<>();
        for (BlockPos pos : positions) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Container container) {
                chests.add(ChestSnapshot.fromContainer(pos, container, isReachable(aiPlayer, pos)));
            }
        }
        return chests;
    }

    private static List<BlockSnapshot> scanBlocks(AiPlayerEntity aiPlayer) {
        Level level = aiPlayer.level();
        BlockPos center = aiPlayer.blockPosition();
        return BlockPos.betweenClosedStream(
                center.offset(-BLOCK_HORIZONTAL_RADIUS, -BLOCK_VERTICAL_RADIUS, -BLOCK_HORIZONTAL_RADIUS),
                center.offset(BLOCK_HORIZONTAL_RADIUS, BLOCK_VERTICAL_RADIUS, BLOCK_HORIZONTAL_RADIUS)
            )
            .map(BlockPos::immutable)
            .filter(pos -> isKeyBlock(level.getBlockState(pos).getBlock()))
            .sorted(Comparator.comparingDouble(pos -> pos.distSqr(center)))
            .limit(MAX_BLOCKS)
            .map(pos -> new BlockSnapshot(pos, blockKey(level.getBlockState(pos).getBlock()), isReachable(aiPlayer, pos), Math.sqrt(pos.distSqr(center))))
            .toList();
    }

    private static List<EntitySnapshot> scanEntities(AiPlayerEntity aiPlayer) {
        AABB area = aiPlayer.getBoundingBox().inflate(ENTITY_RADIUS);
        return aiPlayer.level()
            .getEntities(aiPlayer, area)
            .stream()
            .sorted(Comparator.comparingDouble(aiPlayer::distanceToSqr))
            .limit(MAX_ENTITIES)
            .map(entity -> EntitySnapshot.capture(aiPlayer, entity))
            .toList();
    }

    private static List<CaveSnapshot> scanCaves(AiPlayerEntity aiPlayer) {
        Level level = aiPlayer.level();
        BlockPos center = aiPlayer.blockPosition();
        return BlockPos.betweenClosedStream(
                center.offset(-BLOCK_HORIZONTAL_RADIUS, -BLOCK_VERTICAL_RADIUS, -BLOCK_HORIZONTAL_RADIUS),
                center.offset(BLOCK_HORIZONTAL_RADIUS, BLOCK_VERTICAL_RADIUS, BLOCK_HORIZONTAL_RADIUS)
            )
            .map(BlockPos::immutable)
            .filter(pos -> isCaveEntrance(level, pos))
            .sorted(Comparator.comparingDouble(pos -> pos.distSqr(center)))
            .limit(MAX_CAVES)
            .map(pos -> CaveSnapshot.capture(aiPlayer, pos))
            .toList();
    }

    private static boolean isReachable(AiPlayerEntity aiPlayer, BlockPos pos) {
        if (aiPlayer.blockPosition().closerThan(pos, 5.0)) {
            return true;
        }
        return aiPlayer.getNavigation().createPath(pos, 1) != null;
    }

    private static boolean isKeyBlock(Block block) {
        return SurvivalUtils.isLog(block)
            || SurvivalUtils.isStone(block)
            || SurvivalUtils.isMiningResourceBlock(block)
            || block == Blocks.CRAFTING_TABLE
            || block == Blocks.FURNACE
            || block == Blocks.CHEST
            || block == Blocks.TRAPPED_CHEST
            || block == Blocks.WATER;
    }

    private static boolean isCaveEntrance(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        Block support = level.getBlockState(pos.below()).getBlock();
        if (support == Blocks.AIR || support == Blocks.WATER || support == Blocks.LAVA || support == Blocks.BEDROCK) {
            return false;
        }
        int airNeighbors = 0;
        int exposedWalls = 0;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            Block block = level.getBlockState(pos.relative(direction)).getBlock();
            if (block == Blocks.AIR) {
                airNeighbors++;
            } else if (SurvivalUtils.isStone(block) || SurvivalUtils.isMiningResourceBlock(block)) {
                exposedWalls++;
            }
        }
        return airNeighbors >= 2 && exposedWalls >= 2;
    }

    private static String blockKey(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? "minecraft:air" : key.toString();
    }

    private static String itemKey(ItemStack stack) {
        return stack == null || stack.isEmpty()
            ? "minecraft:air"
            : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static int[] position(BlockPos pos) {
        return new int[] {pos.getX(), pos.getY(), pos.getZ()};
    }

    public static final class AiState {
        private final String name;
        private final int[] position;
        private final float health;
        private final float maxHealth;
        private final String mainHand;
        private final Map<String, String> equipment;
        private final List<InventorySnapshot> inventory;
        private final String currentGoal;
        private final boolean executing;
        private final boolean planning;
        private final boolean navigationStuck;

        private AiState(
            String name,
            int[] position,
            float health,
            float maxHealth,
            String mainHand,
            Map<String, String> equipment,
            List<InventorySnapshot> inventory,
            String currentGoal,
            boolean executing,
            boolean planning,
            boolean navigationStuck
        ) {
            this.name = name;
            this.position = position;
            this.health = health;
            this.maxHealth = maxHealth;
            this.mainHand = mainHand;
            this.equipment = Map.copyOf(equipment);
            this.inventory = List.copyOf(inventory);
            this.currentGoal = currentGoal;
            this.executing = executing;
            this.planning = planning;
            this.navigationStuck = navigationStuck;
        }

        private static AiState capture(AiPlayerEntity aiPlayer) {
            return new AiState(
                aiPlayer.getAiPlayerName(),
                position(aiPlayer.blockPosition()),
                aiPlayer.getHealth(),
                aiPlayer.getMaxHealth(),
                itemKey(aiPlayer.getMainHandItem()),
                Map.of(
                    "head", itemKey(aiPlayer.getItemBySlot(EquipmentSlot.HEAD)),
                    "chest", itemKey(aiPlayer.getItemBySlot(EquipmentSlot.CHEST)),
                    "legs", itemKey(aiPlayer.getItemBySlot(EquipmentSlot.LEGS)),
                    "feet", itemKey(aiPlayer.getItemBySlot(EquipmentSlot.FEET))
                ),
                captureInventory(aiPlayer),
                aiPlayer.getActionExecutor().getCurrentGoal(),
                aiPlayer.getActionExecutor().isExecuting(),
                aiPlayer.getActionExecutor().isPlanning(),
                aiPlayer.getNavigation().isStuck()
            );
        }

        private static List<InventorySnapshot> captureInventory(AiPlayerEntity aiPlayer) {
            List<InventorySnapshot> inventory = new ArrayList<>();
            List<ItemStack> stacks = aiPlayer.getBackpackItems();
            for (int slot = 0; slot < stacks.size(); slot++) {
                ItemStack stack = stacks.get(slot);
                if (!stack.isEmpty()) {
                    inventory.add(InventorySnapshot.fromStack(slot, stack));
                }
            }
            return inventory;
        }

        public String getName() {
            return name;
        }

        public int[] getPosition() {
            return position;
        }

        public float getHealth() {
            return health;
        }

        public float getMaxHealth() {
            return maxHealth;
        }

        public String getMainHand() {
            return mainHand;
        }

        public Map<String, String> getEquipment() {
            return equipment;
        }

        public List<InventorySnapshot> getInventory() {
            return inventory;
        }

        public String getCurrentGoal() {
            return currentGoal;
        }

        public boolean isExecuting() {
            return executing;
        }

        public boolean isPlanning() {
            return planning;
        }

        public boolean isNavigationStuck() {
            return navigationStuck;
        }
    }

    public static final class WorldState {
        private final String dimension;
        private final String biome;
        private final long dayTime;
        private final String timeOfDay;
        private final int nearbyHostiles;

        private WorldState(String dimension, String biome, long dayTime, String timeOfDay, int nearbyHostiles) {
            this.dimension = dimension;
            this.biome = biome;
            this.dayTime = dayTime;
            this.timeOfDay = timeOfDay;
            this.nearbyHostiles = nearbyHostiles;
        }

        private static WorldState capture(AiPlayerEntity aiPlayer) {
            Level level = aiPlayer.level();
            long dayTime = level.getDayTime();
            String biome = level.getBiome(aiPlayer.blockPosition())
                .unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
            int nearbyHostiles = (int) level.getEntities(aiPlayer, aiPlayer.getBoundingBox().inflate(ENTITY_RADIUS))
                .stream()
                .filter(entity -> entity instanceof Enemy)
                .count();
            return new WorldState(
                level.dimension().location().toString(),
                biome,
                dayTime,
                describeTime(dayTime),
                nearbyHostiles
            );
        }

        private static String describeTime(long dayTime) {
            long time = dayTime % 24000L;
            if (time < 12000L) {
                return "day";
            }
            if (time < 13800L) {
                return "sunset";
            }
            if (time < 22200L) {
                return "night";
            }
            return "sunrise";
        }

        public String getDimension() {
            return dimension;
        }

        public String getBiome() {
            return biome;
        }

        public long getDayTime() {
            return dayTime;
        }

        public String getTimeOfDay() {
            return timeOfDay;
        }

        public int getNearbyHostiles() {
            return nearbyHostiles;
        }
    }

    public static final class BlockSnapshot {
        private final String block;
        private final int[] position;
        private final boolean reachable;
        private final double distance;

        private BlockSnapshot(BlockPos pos, String block, boolean reachable, double distance) {
            this.block = block;
            this.position = position(pos);
            this.reachable = reachable;
            this.distance = Math.round(distance * 10.0) / 10.0;
        }

        public String getBlock() {
            return block;
        }

        public int[] getPosition() {
            return position;
        }

        public boolean isReachable() {
            return reachable;
        }

        public double getDistance() {
            return distance;
        }
    }

    public static final class CaveSnapshot {
        private final int[] position;
        private final boolean reachable;
        private final double distance;
        private final int connectedAir;
        private final int exposedWalls;
        private final int visibleOres;

        private CaveSnapshot(
            BlockPos pos,
            boolean reachable,
            double distance,
            int connectedAir,
            int exposedWalls,
            int visibleOres
        ) {
            this.position = position(pos);
            this.reachable = reachable;
            this.distance = Math.round(distance * 10.0) / 10.0;
            this.connectedAir = connectedAir;
            this.exposedWalls = exposedWalls;
            this.visibleOres = visibleOres;
        }

        private static CaveSnapshot capture(AiPlayerEntity aiPlayer, BlockPos pos) {
            Level level = aiPlayer.level();
            int connectedAir = 0;
            int exposedWalls = 0;
            int visibleOres = 0;
            for (BlockPos nearby : BlockPos.betweenClosed(pos.offset(-4, -2, -4), pos.offset(4, 3, 4))) {
                Block block = level.getBlockState(nearby).getBlock();
                if (block == Blocks.AIR) {
                    connectedAir++;
                } else if (SurvivalUtils.isMiningResourceBlock(block)) {
                    visibleOres++;
                    exposedWalls++;
                } else if (SurvivalUtils.isStone(block)) {
                    exposedWalls++;
                }
            }
            return new CaveSnapshot(
                pos,
                WorldSnapshot.isReachable(aiPlayer, pos),
                Math.sqrt(pos.distSqr(aiPlayer.blockPosition())),
                connectedAir,
                exposedWalls,
                visibleOres
            );
        }

        public int[] getPosition() {
            return position;
        }

        public boolean isReachable() {
            return reachable;
        }

        public double getDistance() {
            return distance;
        }

        public int getConnectedAir() {
            return connectedAir;
        }

        public int getExposedWalls() {
            return exposedWalls;
        }

        public int getVisibleOres() {
            return visibleOres;
        }
    }

    public static final class EntitySnapshot {
        private final String type;
        private final String name;
        private final String category;
        private final int[] position;
        private final double distance;

        private EntitySnapshot(String type, String name, String category, BlockPos position, double distance) {
            this.type = type;
            this.name = name;
            this.category = category;
            this.position = WorldSnapshot.position(position);
            this.distance = Math.round(distance * 10.0) / 10.0;
        }

        private static EntitySnapshot capture(AiPlayerEntity aiPlayer, Entity entity) {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            String type = key == null ? "unknown" : key.toString();
            String name = entity.getDisplayName() == null ? "" : entity.getDisplayName().getString();
            return new EntitySnapshot(type, name, categorize(entity), entity.blockPosition(), aiPlayer.distanceTo(entity));
        }

        private static String categorize(Entity entity) {
            if (entity instanceof Player) {
                return "player";
            }
            if (entity instanceof Enemy) {
                return "hostile";
            }
            if (entity instanceof Animal) {
                return "animal";
            }
            return "other";
        }

        public String getType() {
            return type;
        }

        public String getName() {
            return name;
        }

        public String getCategory() {
            return category;
        }

        public int[] getPosition() {
            return position;
        }

        public double getDistance() {
            return distance;
        }
    }
}
