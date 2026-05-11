package com.aiplayer.entity;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.config.AiPlayerConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AiPlayerManager {
    private final Map<String, AiPlayerEntity> activeAiPlayers;
    private final Map<UUID, AiPlayerEntity> aiPlayersByUUID;
    private final Map<UUID, AiPlayerEntity> aiPlayersByOwner;

    public AiPlayerManager() {
        this.activeAiPlayers = new ConcurrentHashMap<>();
        this.aiPlayersByUUID = new ConcurrentHashMap<>();
        this.aiPlayersByOwner = new ConcurrentHashMap<>();
    }

    public AiPlayerEntity spawnAiPlayer(ServerLevel level, Vec3 position, String name, UUID ownerUuid) {
        AiPlayerMod.info("player", "Current active AI players: {}", activeAiPlayers.size());

        if (ownerUuid != null && getAiPlayerByOwner(ownerUuid) != null) {
            AiPlayerMod.warn("player", "Owner '{}' already has an AI player", ownerUuid);
            return null;
        }
        if (activeAiPlayers.containsKey(name)) {
            AiPlayerMod.warn("player", "AI player name '{}' already exists", name);
            return null;
        }
        int maxAiPlayers = AiPlayerConfig.MAX_ACTIVE_AI_PLAYERS.get();
        if (activeAiPlayers.size() >= maxAiPlayers) {
            AiPlayerMod.warn("player", "Max AI player limit reached: {}", maxAiPlayers);
            return null;
        }

        AiPlayerEntity aiPlayer;
        try {
            AiPlayerMod.info("player", "EntityType: {}", AiPlayerMod.AI_PLAYER_ENTITY);
            aiPlayer = new AiPlayerEntity(AiPlayerMod.AI_PLAYER_ENTITY, level);
        } catch (Throwable e) {
            AiPlayerMod.error("player", "Failed to create AI player entity", e);
            return null;
        }

        try {
            aiPlayer.setAiPlayerName(name);
            aiPlayer.setOwnerUuid(ownerUuid);
            aiPlayer.setPos(position.x, position.y, position.z);
            boolean added = level.addFreshEntity(aiPlayer);
            if (added) {
                activeAiPlayers.put(name, aiPlayer);
                aiPlayersByUUID.put(aiPlayer.getUUID(), aiPlayer);
                if (ownerUuid != null) {
                    aiPlayersByOwner.put(ownerUuid, aiPlayer);
                }
                AiPlayerMod.info("player", "Successfully spawned AI player: {} with UUID {} at {} for owner {}",
                    name, aiPlayer.getUUID(), position, ownerUuid);
                return aiPlayer;
            }
            AiPlayerMod.error("player", "Failed to add AI player entity to world");
        } catch (Throwable e) {
            AiPlayerMod.error("player", "Exception during AI player spawn setup", e);
        }

        return null;
    }

    public AiPlayerEntity getAiPlayer(String name) {
        return activeAiPlayers.get(name);
    }

    public AiPlayerEntity getAiPlayer(UUID uuid) {
        return aiPlayersByUUID.get(uuid);
    }

    public AiPlayerEntity getAiPlayerByOwner(UUID ownerUuid) {
        AiPlayerEntity aiPlayer = aiPlayersByOwner.get(ownerUuid);
        if (aiPlayer == null || aiPlayer.isRemoved() || !aiPlayer.isAlive()) {
            aiPlayersByOwner.remove(ownerUuid);
            return null;
        }
        return aiPlayer;
    }

    public boolean removeAiPlayer(String name) {
        AiPlayerEntity aiPlayer = activeAiPlayers.remove(name);
        if (aiPlayer != null) {
            removeIndexes(aiPlayer);
            aiPlayer.discard();
            return true;
        }
        return false;
    }

    public boolean removeAiPlayerByOwner(UUID ownerUuid) {
        AiPlayerEntity aiPlayer = aiPlayersByOwner.remove(ownerUuid);
        if (aiPlayer != null) {
            activeAiPlayers.remove(aiPlayer.getAiPlayerName());
            aiPlayersByUUID.remove(aiPlayer.getUUID());
            aiPlayer.discard();
            return true;
        }
        return false;
    }

    public void clearAllAiPlayers() {
        AiPlayerMod.info("player", "Clearing {} AI player entities", activeAiPlayers.size());
        for (AiPlayerEntity aiPlayer : activeAiPlayers.values()) {
            aiPlayer.discard();
        }
        activeAiPlayers.clear();
        aiPlayersByUUID.clear();
        aiPlayersByOwner.clear();
    }

    public Collection<AiPlayerEntity> getAllAiPlayers() {
        return Collections.unmodifiableCollection(activeAiPlayers.values());
    }

    public List<String> getAiPlayerNames() {
        return new ArrayList<>(activeAiPlayers.keySet());
    }

    public int getActiveCount() {
        return activeAiPlayers.size();
    }

    public void tick(ServerLevel level) {
        Iterator<Map.Entry<String, AiPlayerEntity>> iterator = activeAiPlayers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AiPlayerEntity> entry = iterator.next();
            AiPlayerEntity aiPlayer = entry.getValue();

            if (!aiPlayer.isAlive() || aiPlayer.isRemoved()) {
                iterator.remove();
                removeIndexes(aiPlayer);
                AiPlayerMod.info("player", "Cleaned up AI player: {}", entry.getKey());
            }
        }
    }

    private void removeIndexes(AiPlayerEntity aiPlayer) {
        aiPlayersByUUID.remove(aiPlayer.getUUID());
        aiPlayersByOwner.entrySet().removeIf(entry -> entry.getValue() == aiPlayer);
    }
}
