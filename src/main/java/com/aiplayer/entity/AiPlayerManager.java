package com.aiplayer.entity;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.config.AiPlayerConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.nio.file.Path;

public class AiPlayerManager {
    private static final Path REMOVED_AI_TOMBSTONE_PATH = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("aiplayer_removed_ai.json");

    private final Map<String, AiPlayerEntity> activeAiPlayers;
    private final Map<UUID, AiPlayerEntity> aiPlayersByUUID;
    private final Map<UUID, AiPlayerEntity> aiPlayersByOwner;
    private final Map<UUID, ForcedChunk> forcedChunks;
    private final Map<UUID, UUID> allowedAiByRemovedOwner;
    private final Set<UUID> manuallyRemovedOwners;
    private final Set<UUID> manuallyRemovedAiUuids;

    public AiPlayerManager() {
        this.activeAiPlayers = new ConcurrentHashMap<>();
        this.aiPlayersByUUID = new ConcurrentHashMap<>();
        this.aiPlayersByOwner = new ConcurrentHashMap<>();
        this.forcedChunks = new ConcurrentHashMap<>();
        this.allowedAiByRemovedOwner = new ConcurrentHashMap<>();
        this.manuallyRemovedOwners = ConcurrentHashMap.newKeySet();
        this.manuallyRemovedAiUuids = ConcurrentHashMap.newKeySet();
        loadManualRemovalTombstones();
    }

    public AiPlayerEntity spawnAiPlayer(ServerLevel level, Vec3 position, String name, UUID ownerUuid) {
        AiPlayerMod.info("player", "Current active AI players: {}", activeAiPlayers.size());

        if (ownerUuid != null && getAiPlayerByOwner(ownerUuid) != null) {
            AiPlayerMod.warn("player", "Owner '{}' already has an AI player", ownerUuid);
            return null;
        }
        AiPlayerEntity nameConflict = getAiPlayer(name);
        if (nameConflict != null) {
            AiPlayerMod.warn("player", "AI player name '{}' already exists", name);
            return null;
        }
        AiPlayerEntity inactiveNameRecord = activeAiPlayers.get(name);
        if (inactiveNameRecord != null && !reclaimInactiveNameRecord(name, inactiveNameRecord, ownerUuid, "spawn_name_conflict")) {
            AiPlayerMod.warn("player", "AI player name '{}' is reserved by an inactive record owned by {}", name, inactiveNameRecord.getOwnerUuid());
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
                    allowAiAfterManualRemoval(ownerUuid, aiPlayer.getUUID());
                }
                forceAiPlayerChunk(aiPlayer);
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
        AiPlayerEntity aiPlayer = activeAiPlayers.get(name);
        if (aiPlayer == null || isUsable(aiPlayer)) {
            return aiPlayer;
        }
        return recoverAiPlayer(aiPlayer.level() instanceof ServerLevel serverLevel ? serverLevel : null, name, aiPlayer);
    }

    public AiPlayerEntity getAiPlayer(UUID uuid) {
        AiPlayerEntity aiPlayer = aiPlayersByUUID.get(uuid);
        if (aiPlayer == null || isUsable(aiPlayer)) {
            return aiPlayer;
        }
        return recoverAiPlayer(aiPlayer.level() instanceof ServerLevel serverLevel ? serverLevel : null,
            aiPlayer.getAiPlayerName(), aiPlayer);
    }

    public AiPlayerEntity getAiPlayerByOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return null;
        }
        AiPlayerEntity aiPlayer = aiPlayersByOwner.get(ownerUuid);
        if (aiPlayer == null) {
            aiPlayer = findIndexedAiPlayerByOwner(ownerUuid);
            if (aiPlayer != null) {
                aiPlayersByOwner.put(ownerUuid, aiPlayer);
            }
        }
        if (aiPlayer == null || isUsable(aiPlayer)) {
            return aiPlayer;
        }
        AiPlayerEntity recovered = recoverAiPlayer(aiPlayer.level() instanceof ServerLevel serverLevel ? serverLevel : null,
            aiPlayer.getAiPlayerName(), aiPlayer);
        if (recovered == null) {
            return aiPlayer;
        }
        return recovered;
    }

    public boolean removeAiPlayerByOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return false;
        }
        AiPlayerEntity aiPlayer = aiPlayersByOwner.remove(ownerUuid);
        if (aiPlayer == null) {
            aiPlayer = findIndexedAiPlayerByOwner(ownerUuid);
        }
        if (aiPlayer != null) {
            removeAiPlayerRecord(aiPlayer, ownerUuid, aiPlayer.getAiPlayerName(), "remove_by_owner");
            return true;
        }
        addManualRemoval(ownerUuid, null);
        return false;
    }

    public boolean removeAiPlayerByNameForOwner(UUID ownerUuid, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        AiPlayerEntity aiPlayer = activeAiPlayers.get(name);
        if (aiPlayer == null) {
            return false;
        }
        UUID actualOwner = aiPlayer.getOwnerUuid();
        if (!AiPlayerOwnershipPolicy.canRemoveByName(actualOwner, ownerUuid)) {
            AiPlayerMod.warn("player", "Owner '{}' attempted to remove AI player '{}' owned by '{}'", ownerUuid, name, actualOwner);
            return false;
        }
        removeAiPlayerRecord(aiPlayer, actualOwner == null ? ownerUuid : actualOwner, name, "remove_by_name");
        return true;
    }

    public void clearAllAiPlayers() {
        AiPlayerMod.info("player", "Clearing {} AI player entities", activeAiPlayers.size());
        for (AiPlayerEntity aiPlayer : activeAiPlayers.values()) {
            unforceAiPlayerChunk(aiPlayer);
            aiPlayer.discard();
        }
        activeAiPlayers.clear();
        aiPlayersByUUID.clear();
        aiPlayersByOwner.clear();
        forcedChunks.clear();
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
        registerLoadedAiPlayers(level);
        for (Map.Entry<String, AiPlayerEntity> entry : activeAiPlayers.entrySet()) {
            AiPlayerEntity aiPlayer = entry.getValue();
            if (isUsable(aiPlayer)) {
                forceAiPlayerChunk(aiPlayer);
            } else {
                AiPlayerEntity recovered = recoverAiPlayer(level, entry.getKey(), aiPlayer);
                if (recovered == null) {
                    AiPlayerMod.debug("player", "Preserving inactive AI player '{}' until owner returns", entry.getKey());
                }
            }
        }
    }

    private void removeIndexes(AiPlayerEntity aiPlayer) {
        aiPlayersByUUID.remove(aiPlayer.getUUID());
        aiPlayersByOwner.entrySet().removeIf(entry -> entry.getValue() == aiPlayer);
    }

    private AiPlayerEntity findIndexedAiPlayerByOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return null;
        }
        for (AiPlayerEntity aiPlayer : activeAiPlayers.values()) {
            if (isSameOwner(aiPlayer, ownerUuid)) {
                return aiPlayer;
            }
        }
        return null;
    }

    private boolean reclaimInactiveNameRecord(String name, AiPlayerEntity inactiveRecord, UUID requestedOwner, String reason) {
        if (inactiveRecord == null || isUsable(inactiveRecord)) {
            return false;
        }
        UUID recordOwner = inactiveRecord.getOwnerUuid();
        if (!AiPlayerOwnershipPolicy.canReclaimInactiveName(recordOwner, requestedOwner)) {
            return false;
        }
        removeAiPlayerRecord(inactiveRecord, recordOwner == null ? requestedOwner : recordOwner, name, reason);
        AiPlayerMod.info("player", "Reclaimed inactive AI player name record: name={}, owner={}, reason={}",
            name, recordOwner, reason);
        return true;
    }

    private void removeAiPlayerRecord(AiPlayerEntity aiPlayer, UUID ownerUuid, String name, String reason) {
        if (aiPlayer == null) {
            return;
        }
        UUID actualOwner = aiPlayer.getOwnerUuid();
        UUID tombstoneOwner = actualOwner;
        UUID requesterOwner = ownerUuid;
        addManualRemoval(tombstoneOwner, aiPlayer.getUUID());
        activeAiPlayers.remove(name == null || name.isBlank() ? aiPlayer.getAiPlayerName() : name, aiPlayer);
        activeAiPlayers.remove(aiPlayer.getAiPlayerName(), aiPlayer);
        aiPlayersByUUID.remove(aiPlayer.getUUID());
        if (tombstoneOwner != null) {
            aiPlayersByOwner.remove(tombstoneOwner);
        }
        aiPlayersByOwner.entrySet().removeIf(entry -> entry.getValue() == aiPlayer);
        discardLoadedAiPlayerCopies(aiPlayer, tombstoneOwner, requesterOwner, name == null || name.isBlank() ? aiPlayer.getAiPlayerName() : name);
        unforceAiPlayerChunk(aiPlayer);
        aiPlayer.discard();
        AiPlayerMod.info("player", "Removed AI player record: name={}, uuid={}, owner={}, reason={}",
            name == null || name.isBlank() ? aiPlayer.getAiPlayerName() : name,
            aiPlayer.getUUID(),
            tombstoneOwner,
            reason);
    }

    private void registerLoadedAiPlayers(ServerLevel level) {
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof AiPlayerEntity aiPlayer) || !isUsable(aiPlayer)) {
                continue;
            }
            UUID ownerUuid = aiPlayer.getOwnerUuid();
            if (isManuallyRemovedAiCopy(aiPlayer)) {
                AiPlayerMod.info("player", "Discarding manually removed AI player copy: name={}, uuid={}, owner={}",
                    aiPlayer.getAiPlayerName(), aiPlayer.getUUID(), ownerUuid);
                unforceAiPlayerChunk(aiPlayer);
                aiPlayer.discard();
                continue;
            }
            AiPlayerEntity existingByName = activeAiPlayers.get(aiPlayer.getAiPlayerName());
            if (existingByName != null
                && existingByName != aiPlayer
                && !AiPlayerOwnershipPolicy.canReplaceNameIndex(existingByName.getOwnerUuid(), ownerUuid)) {
                AiPlayerMod.warn("player", "Discarding loaded AI player copy because name index is owned: name={}, incomingUuid={}, incomingOwner={}, activeUuid={}, activeOwner={}",
                    aiPlayer.getAiPlayerName(), aiPlayer.getUUID(), ownerUuid, existingByName.getUUID(), existingByName.getOwnerUuid());
                unforceAiPlayerChunk(aiPlayer);
                aiPlayer.discard();
                continue;
            }
            AiPlayerEntity existing = ownerUuid == null ? null : aiPlayersByOwner.get(ownerUuid);
            if (existing == aiPlayer) {
                forceAiPlayerChunk(aiPlayer);
                continue;
            }
            if (existing == null || !isUsable(existing)) {
                registerExistingAiPlayer(aiPlayer, existing);
                continue;
            }
            if (isSameAiPlayer(existing, aiPlayer)) {
                AiPlayerMod.warn("player", "Discarding duplicate stale AI player copy: name={}, duplicateUuid={}, activeUuid={}",
                    aiPlayer.getAiPlayerName(), aiPlayer.getUUID(), existing.getUUID());
                aiPlayer.discard();
            }
        }
    }

    private void registerExistingAiPlayer(AiPlayerEntity aiPlayer, AiPlayerEntity previous) {
        if (previous != null) {
            activeAiPlayers.remove(previous.getAiPlayerName(), previous);
            aiPlayersByUUID.remove(previous.getUUID());
            unforceAiPlayerChunk(previous);
        }
        activeAiPlayers.put(aiPlayer.getAiPlayerName(), aiPlayer);
        aiPlayersByUUID.put(aiPlayer.getUUID(), aiPlayer);
        UUID ownerUuid = aiPlayer.getOwnerUuid();
        if (ownerUuid != null) {
            aiPlayersByOwner.put(ownerUuid, aiPlayer);
        }
        forceAiPlayerChunk(aiPlayer);
        AiPlayerMod.info("player", "Registered loaded AI player: name={}, uuid={}, owner={}, pos={}",
            aiPlayer.getAiPlayerName(), aiPlayer.getUUID(), ownerUuid, aiPlayer.position());
    }

    private boolean isUsable(AiPlayerEntity aiPlayer) {
        return aiPlayer != null && aiPlayer.isAlive() && !aiPlayer.isRemoved();
    }

    private AiPlayerEntity recoverAiPlayer(ServerLevel fallbackLevel, String name, AiPlayerEntity previous) {
        UUID ownerUuid = previous.getOwnerUuid();
        if (isManuallyRemovedAiCopy(previous)) {
            return null;
        }
        ServerPlayer owner = findOwnerPlayer(fallbackLevel, ownerUuid);
        if (owner == null) {
            return null;
        }
        AiPlayerEntity loaded = findLoadedAiPlayer(owner.serverLevel(), ownerUuid, name);
        if (loaded != null) {
            if (loaded.getOwnerUuid() == null && ownerUuid != null) {
                loaded.setOwnerUuid(ownerUuid);
            }
            registerExistingAiPlayer(loaded, previous);
            return loaded;
        }

        ServerLevel targetLevel = owner.serverLevel();
        CompoundTag saved = new CompoundTag();
        previous.addAdditionalSaveData(saved);

        AiPlayerEntity recovered = new AiPlayerEntity(AiPlayerMod.AI_PLAYER_ENTITY, targetLevel);
        recovered.readAdditionalSaveData(saved);
        recovered.setAiPlayerName(name);
        recovered.setOwnerUuid(ownerUuid);

        Vec3 position = recoveryPosition(owner);
        if (position == null) {
            AiPlayerMod.warn("player", "Could not find safe recovery position for AI player '{}' near owner {}", name, owner.getGameProfile().getName());
            return null;
        }
        recovered.setPos(position.x, position.y, position.z);

        if (!targetLevel.addFreshEntity(recovered)) {
            AiPlayerMod.warn("player", "Failed to recover AI player '{}' near owner {}", name, owner.getGameProfile().getName());
            return null;
        }

        activeAiPlayers.put(name, recovered);
        aiPlayersByUUID.remove(previous.getUUID());
        aiPlayersByUUID.put(recovered.getUUID(), recovered);
        if (ownerUuid != null) {
            aiPlayersByOwner.put(ownerUuid, recovered);
            allowAiAfterManualRemoval(ownerUuid, recovered.getUUID());
        }
        unforceAiPlayerChunk(previous);
        forceAiPlayerChunk(recovered);
        AiPlayerMod.info("player", "Recovered AI player '{}' after entity unload at {} for owner {}",
            name, position, ownerUuid);
        return recovered;
    }

    private ServerPlayer findOwnerPlayer(ServerLevel fallbackLevel, UUID ownerUuid) {
        if (ownerUuid == null || fallbackLevel == null || fallbackLevel.getServer() == null) {
            return null;
        }
        return fallbackLevel.getServer().getPlayerList().getPlayer(ownerUuid);
    }

    private Vec3 recoveryPosition(ServerPlayer owner) {
        ServerLevel level = owner.serverLevel();
        BlockPos base = owner.blockPosition();
        int[][] offsets = {
            {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {2, 1}, {2, -1}, {-2, 1}, {-2, -1},
            {1, 2}, {-1, 2}, {1, -2}, {-1, -2},
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
        };
        for (int[] offset : offsets) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos feet = base.offset(offset[0], dy, offset[1]);
                if (isRecoveryPositionSafe(level, feet)) {
                    return Vec3.atBottomCenterOf(feet);
                }
            }
        }
        return null;
    }

    private boolean isRecoveryPositionSafe(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
            && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
    }

    private AiPlayerEntity findLoadedAiPlayer(ServerLevel level, UUID ownerUuid, String name) {
        if (level == null || level.getServer() == null) {
            return null;
        }
        for (ServerLevel serverLevel : level.getServer().getAllLevels()) {
            for (var entity : serverLevel.getAllEntities()) {
                if (entity instanceof AiPlayerEntity aiPlayer
                    && isUsable(aiPlayer)
                    && isRecoveryCandidate(aiPlayer, ownerUuid, name)) {
                    if (isManuallyRemovedAiCopy(aiPlayer)) {
                        AiPlayerMod.info("player", "Discarding tombstoned loaded AI player during recovery scan: name={}, uuid={}, owner={}",
                            aiPlayer.getAiPlayerName(), aiPlayer.getUUID(), aiPlayer.getOwnerUuid());
                        unforceAiPlayerChunk(aiPlayer);
                        aiPlayer.discard();
                        continue;
                    }
                    return aiPlayer;
                }
            }
        }
        return null;
    }

    private boolean isRecoveryCandidate(AiPlayerEntity aiPlayer, UUID ownerUuid, String name) {
        if (isSameOwner(aiPlayer, ownerUuid)) {
            return true;
        }
        return name != null
            && aiPlayer.getAiPlayerName().equals(name)
            && AiPlayerOwnershipPolicy.canReclaimInactiveName(aiPlayer.getOwnerUuid(), ownerUuid);
    }

    private void discardLoadedAiPlayerCopies(AiPlayerEntity reference, UUID ownerUuid, UUID requesterOwner, String name) {
        if (!(reference.level() instanceof ServerLevel level) || level.getServer() == null) {
            return;
        }
        for (ServerLevel serverLevel : level.getServer().getAllLevels()) {
            for (var entity : serverLevel.getAllEntities()) {
                if (!(entity instanceof AiPlayerEntity aiPlayer) || !isUsable(aiPlayer)) {
                    continue;
                }
                boolean sameReference = aiPlayer == reference;
                boolean sameOwner = isSameOwner(aiPlayer, ownerUuid);
                boolean removableNameCopy = name != null
                    && aiPlayer.getAiPlayerName().equals(name)
                    && AiPlayerOwnershipPolicy.canDiscardNamedCopy(ownerUuid, requesterOwner, aiPlayer.getOwnerUuid());
                if (sameReference || sameOwner || removableNameCopy) {
                    activeAiPlayers.remove(aiPlayer.getAiPlayerName(), aiPlayer);
                    aiPlayersByUUID.remove(aiPlayer.getUUID());
                    aiPlayersByOwner.entrySet().removeIf(entry -> entry.getValue() == aiPlayer);
                    unforceAiPlayerChunk(aiPlayer);
                    aiPlayer.discard();
                }
            }
        }
    }

    private boolean isSameAiPlayer(AiPlayerEntity first, AiPlayerEntity second) {
        if (first == null || second == null) {
            return false;
        }
        UUID firstOwner = first.getOwnerUuid();
        UUID secondOwner = second.getOwnerUuid();
        return (firstOwner != null && firstOwner.equals(secondOwner))
            || first.getAiPlayerName().equals(second.getAiPlayerName());
    }

    private boolean isSameOwner(AiPlayerEntity aiPlayer, UUID ownerUuid) {
        return ownerUuid != null && ownerUuid.equals(aiPlayer.getOwnerUuid());
    }

    private boolean isManuallyRemovedAiCopy(AiPlayerEntity aiPlayer) {
        if (aiPlayer == null) {
            return false;
        }
        if (manuallyRemovedAiUuids.contains(aiPlayer.getUUID())) {
            return true;
        }
        UUID ownerUuid = aiPlayer.getOwnerUuid();
        UUID allowedUuid = ownerUuid == null ? null : allowedAiByRemovedOwner.get(ownerUuid);
        return ownerUuid != null
            && manuallyRemovedOwners.contains(ownerUuid)
            && !aiPlayer.getUUID().equals(allowedUuid);
    }

    private void addManualRemoval(UUID ownerUuid, UUID aiUuid) {
        boolean changed = false;
        if (ownerUuid != null) {
            changed |= manuallyRemovedOwners.add(ownerUuid);
            changed |= allowedAiByRemovedOwner.remove(ownerUuid) != null;
        }
        if (aiUuid != null) {
            changed |= manuallyRemovedAiUuids.add(aiUuid);
        }
        if (changed) {
            saveManualRemovalTombstones();
        }
    }

    private void allowAiAfterManualRemoval(UUID ownerUuid, UUID aiUuid) {
        if (ownerUuid == null || aiUuid == null || !manuallyRemovedOwners.contains(ownerUuid)) {
            return;
        }
        UUID previous = allowedAiByRemovedOwner.put(ownerUuid, aiUuid);
        if (!aiUuid.equals(previous)) {
            saveManualRemovalTombstones();
        }
    }

    private void loadManualRemovalTombstones() {
        if (Files.notExists(REMOVED_AI_TOMBSTONE_PATH)) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(REMOVED_AI_TOMBSTONE_PATH));
            if (parsed.isJsonArray()) {
                loadUuidArray(parsed.getAsJsonArray(), manuallyRemovedOwners);
                return;
            }
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject object = parsed.getAsJsonObject();
            if (object.has("removedOwners") && object.get("removedOwners").isJsonArray()) {
                loadUuidArray(object.getAsJsonArray("removedOwners"), manuallyRemovedOwners);
            }
            if (object.has("removedAiUuids") && object.get("removedAiUuids").isJsonArray()) {
                loadUuidArray(object.getAsJsonArray("removedAiUuids"), manuallyRemovedAiUuids);
            }
            if (object.has("allowedAiByOwner") && object.get("allowedAiByOwner").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("allowedAiByOwner").entrySet()) {
                    try {
                        allowedAiByRemovedOwner.put(UUID.fromString(entry.getKey()), UUID.fromString(entry.getValue().getAsString()));
                    } catch (IllegalArgumentException ignored) {
                        // Skip invalid tombstone rows.
                    }
                }
            }
        } catch (Exception e) {
            AiPlayerMod.warn("player", "Failed to load removed AI tombstones: {}", e.getMessage());
        }
    }

    private void saveManualRemovalTombstones() {
        try {
            Files.createDirectories(REMOVED_AI_TOMBSTONE_PATH.getParent());
            JsonObject object = new JsonObject();
            object.add("removedOwners", uuidArray(manuallyRemovedOwners));
            object.add("removedAiUuids", uuidArray(manuallyRemovedAiUuids));
            JsonObject allowed = new JsonObject();
            for (Map.Entry<UUID, UUID> entry : allowedAiByRemovedOwner.entrySet()) {
                allowed.addProperty(entry.getKey().toString(), entry.getValue().toString());
            }
            object.add("allowedAiByOwner", allowed);
            Files.writeString(REMOVED_AI_TOMBSTONE_PATH, object.toString());
        } catch (Exception e) {
            AiPlayerMod.warn("player", "Failed to save removed AI tombstones: {}", e.getMessage());
        }
    }

    private void loadUuidArray(JsonArray array, Set<UUID> target) {
        for (JsonElement element : array) {
            try {
                target.add(UUID.fromString(element.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // Skip invalid tombstone rows.
            }
        }
    }

    private JsonArray uuidArray(Collection<UUID> uuids) {
        JsonArray array = new JsonArray();
        for (UUID uuid : uuids) {
            array.add(uuid.toString());
        }
        return array;
    }

    private void forceAiPlayerChunk(AiPlayerEntity aiPlayer) {
        if (!isUsable(aiPlayer) || !(aiPlayer.level() instanceof ServerLevel level)) {
            return;
        }
        UUID key = aiPlayerIdentity(aiPlayer);
        int chunkX = aiPlayer.blockPosition().getX() >> 4;
        int chunkZ = aiPlayer.blockPosition().getZ() >> 4;
        ForcedChunk next = new ForcedChunk(level.dimension(), chunkX, chunkZ);
        ForcedChunk previous = forcedChunks.get(key);
        if (next.equals(previous)) {
            return;
        }
        if (previous != null) {
            setForcedChunk(level, previous, false);
        }
        level.setChunkForced(chunkX, chunkZ, true);
        forcedChunks.put(key, next);
    }

    private void unforceAiPlayerChunk(AiPlayerEntity aiPlayer) {
        UUID key = aiPlayerIdentity(aiPlayer);
        ForcedChunk previous = forcedChunks.remove(key);
        if (previous == null || !(aiPlayer.level() instanceof ServerLevel level)) {
            return;
        }
        setForcedChunk(level, previous, false);
    }

    private void setForcedChunk(ServerLevel currentLevel, ForcedChunk chunk, boolean forced) {
        ServerLevel targetLevel = currentLevel;
        if (!currentLevel.dimension().equals(chunk.dimension()) && currentLevel.getServer() != null) {
            targetLevel = currentLevel.getServer().getLevel(chunk.dimension());
        }
        if (targetLevel != null) {
            targetLevel.setChunkForced(chunk.x(), chunk.z(), forced);
        }
    }

    private UUID aiPlayerIdentity(AiPlayerEntity aiPlayer) {
        UUID ownerUuid = aiPlayer.getOwnerUuid();
        return ownerUuid == null ? aiPlayer.getUUID() : ownerUuid;
    }

    private record ForcedChunk(ResourceKey<Level> dimension, int x, int z) {
    }
}
