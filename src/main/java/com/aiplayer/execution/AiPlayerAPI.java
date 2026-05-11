package com.aiplayer.execution;

import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

public class AiPlayerAPI {
    private final AiPlayerEntity aiPlayer;
    private final Queue<Task> actionQueue;

    public AiPlayerAPI(AiPlayerEntity aiPlayer) {
        this.aiPlayer = aiPlayer;
        this.actionQueue = new LinkedBlockingQueue<>();
    }

        public void move(double x, double y, double z) {
        Map<String, Object> params = new HashMap<>();
        params.put("x", x);
        params.put("y", y);
        params.put("z", z);
        actionQueue.add(new Task("pathfind", params));
    }

        public void build(String structureType, Map<String, Double> position) {
        if (structureType == null || structureType.trim().isEmpty()) {
            throw new IllegalArgumentException("Structure type cannot be empty");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("structure", structureType.toLowerCase());
        if (position != null && position.containsKey("x") && position.containsKey("y") && position.containsKey("z")) {
            params.put("x", position.get("x").intValue());
            params.put("y", position.get("y").intValue());
            params.put("z", position.get("z").intValue());
        }

        actionQueue.add(new Task("build", params));
    }

        public void build(String structureType) {
        build(structureType, null);
    }

        public void mine(String blockType, int count) {
        if (blockType == null || blockType.trim().isEmpty()) {
            throw new IllegalArgumentException("Block type cannot be empty");
        }

        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("blockType", blockType.toLowerCase());
        params.put("count", count);

        actionQueue.add(new Task("mine", params));
    }

        public void attack(String entityType) {
        if (entityType == null || entityType.trim().isEmpty()) {
            throw new IllegalArgumentException("Entity type cannot be empty");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("target", entityType.toLowerCase());

        actionQueue.add(new Task("attack", params));
    }

        public void craft(String itemName, int count) {
        if (itemName == null || itemName.trim().isEmpty()) {
            throw new IllegalArgumentException("Item name cannot be empty");
        }

        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("item", itemName.toLowerCase());
        params.put("count", count);

        actionQueue.add(new Task("craft", params));
    }

        public void place(String blockType, Map<String, Double> position) {
        if (blockType == null || blockType.trim().isEmpty()) {
            throw new IllegalArgumentException("Block type cannot be empty");
        }

        if (position == null || !position.containsKey("x") || !position.containsKey("y") || !position.containsKey("z")) {
            throw new IllegalArgumentException("Position must include x, y, z coordinates");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("block", blockType.toLowerCase());
        params.put("x", position.get("x").intValue());
        params.put("y", position.get("y").intValue());
        params.put("z", position.get("z").intValue());

        actionQueue.add(new Task("place", params));
    }

        public void say(String message) {
        if (message != null && !message.trim().isEmpty()) {
        }
    }

        public void follow(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new IllegalArgumentException("Player name cannot be empty");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("playerName", playerName);

        actionQueue.add(new Task("follow", params));
    }

        public void gather(String resourceType, int count) {
        if (resourceType == null || resourceType.trim().isEmpty()) {
            throw new IllegalArgumentException("Resource type cannot be empty");
        }

        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("resource", resourceType.toLowerCase());
        params.put("count", count);

        actionQueue.add(new Task("gather", params));
    }

        public Map<String, Double> getPosition() {
        Vec3 pos = aiPlayer.position();
        Map<String, Double> position = new HashMap<>();
        position.put("x", pos.x);
        position.put("y", pos.y);
        position.put("z", pos.z);
        return position;
    }

        public List<String> getNearbyBlocks(int radius) {
        if (radius <= 0 || radius > 16) {
            throw new IllegalArgumentException("Radius must be between 1 and 16");
        }

        Set<String> blockTypes = new HashSet<>();
        BlockPos aiPlayerPos = aiPlayer.blockPosition();
        for (int x = -radius; x <= radius; x += 2) {
            for (int y = -radius; y <= radius; y += 2) {
                for (int z = -radius; z <= radius; z += 2) {
                    BlockPos pos = aiPlayerPos.offset(x, y, z);
                    BlockState state = aiPlayer.level().getBlockState(pos);
                    String blockName = state.getBlock().getName().getString().toLowerCase();

                    if (!blockName.contains("air")) {
                        blockTypes.add(blockName);
                    }
                }
            }
        }

        return new ArrayList<>(blockTypes);
    }

        public List<String> getNearbyEntities(int radius) {
        if (radius <= 0 || radius > 32) {
            throw new IllegalArgumentException("Radius must be between 1 and 32");
        }

        List<String> entityNames = new ArrayList<>();
        Vec3 aiPlayerPos = aiPlayer.position();
        AABB searchBox = new AABB(
            aiPlayerPos.x - radius, aiPlayerPos.y - radius, aiPlayerPos.z - radius,
            aiPlayerPos.x + radius, aiPlayerPos.y + radius, aiPlayerPos.z + radius
        );

        List<Entity> entities = aiPlayer.level().getEntities(aiPlayer, searchBox);

        for (Entity entity : entities) {
            if (entity instanceof LivingEntity) {
                String entityName = entity.getType().getDescription().getString().toLowerCase();
                entityNames.add(entityName);
            }
        }

        return entityNames;
    }

        public boolean isIdle() {
        return actionQueue.isEmpty();
    }

        public int getPendingActionCount() {
        return actionQueue.size();
    }

        public void wait(int milliseconds) throws InterruptedException {
        if (milliseconds > 0 && milliseconds < 30000) {
            Thread.sleep(milliseconds);
        }
    }

        public Queue<Task> getActionQueue() {
        return actionQueue;
    }

        public void clearActions() {
        actionQueue.clear();
    }

        AiPlayerEntity getAiPlayerEntity() {
        return aiPlayer;
    }
}
