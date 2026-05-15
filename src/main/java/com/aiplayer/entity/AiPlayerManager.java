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
import net.minecraft.world.level.border.WorldBorder;
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
    private static final int RECOVERY_STRICT_RADIUS = 6;
    private static final int RECOVERY_RELAXED_RADIUS = 12;
    private static final int RECOVERY_VERTICAL_BELOW = 6;
    private static final int RECOVERY_VERTICAL_ABOVE = 8;
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
            discardAiPlayer(aiPlayer, "clear_all");
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
        discardAiPlayer(aiPlayer, reason);
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
                discardAiPlayer(aiPlayer, "manual_removal_tombstone_copy");
                continue;
            }
            AiPlayerEntity existingByName = activeAiPlayers.get(aiPlayer.getAiPlayerName());
            if (existingByName != null
                && existingByName != aiPlayer
                && !AiPlayerOwnershipPolicy.canReplaceNameIndex(existingByName.getOwnerUuid(), ownerUuid)) {
                AiPlayerMod.warn("player", "Discarding loaded AI player copy because name index is owned: name={}, incomingUuid={}, incomingOwner={}, activeUuid={}, activeOwner={}",
                    aiPlayer.getAiPlayerName(), aiPlayer.getUUID(), ownerUuid, existingByName.getUUID(), existingByName.getOwnerUuid());
                unforceAiPlayerChunk(aiPlayer);
                discardAiPlayer(aiPlayer, "name_index_owned_copy");
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
                discardAiPlayer(aiPlayer, "duplicate_stale_copy");
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
        AiPlayerMod.warn("player",
            "Recovering unusable AI player: name={}, uuid={}, owner={}, alive={}, removed={}, removalReason={}, pos={}, level={}, ownerPos={}, ownerLevel={}",
            name,
            previous.getUUID(),
            ownerUuid,
            previous.isAlive(),
            previous.isRemoved(),
            removalReasonText(previous),
            previous.blockPosition().toShortString(),
            levelText(previous),
            owner.blockPosition().toShortString(),
            owner.serverLevel().dimension().location());
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
        AiPlayerMod.info("player", "Recovered AI player '{}' after entity unload at {} for owner {}; previousPos={}, previousRemovalReason={}",
            name, position, ownerUuid, previous.blockPosition().toShortString(), removalReasonText(previous));
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
        BlockPos strict = findRecoveryPosition(level, base, RECOVERY_STRICT_RADIUS, true);
        if (strict != null) {
            return Vec3.atBottomCenterOf(strict);
        }
        BlockPos relaxed = findRecoveryPosition(level, base, RECOVERY_RELAXED_RADIUS, false);
        if (relaxed != null) {
            AiPlayerMod.info("player", "Recovered AI player near owner with relaxed recovery position: owner={}, pos={}",
                owner.getGameProfile().getName(), relaxed);
            return Vec3.atBottomCenterOf(relaxed);
        }
        Vec3 forced = forcedRecoveryPosition(level, owner);
        AiPlayerMod.warn("player", "No passable recovery position found near owner {}; forcing AI recovery near player at {} because AI is invulnerable",
            owner.getGameProfile().getName(), forced);
        return forced;
    }

    private BlockPos findRecoveryPosition(ServerLevel level, BlockPos base, int radius, boolean requireSupport) {
        int[] verticalOffsets = orderedVerticalOffsets(RECOVERY_VERTICAL_BELOW, RECOVERY_VERTICAL_ABOVE);
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy : verticalOffsets) {
                        BlockPos feet = clampRecoveryY(level, base.offset(dx, dy, dz));
                        if (isRecoveryPositionUsable(level, feet, requireSupport)) {
                            return feet;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean isRecoveryPositionUsable(ServerLevel level, BlockPos feet, boolean requireSupport) {
        if (!level.getWorldBorder().isWithinBounds(feet)) {
            return false;
        }
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
            return false;
        }
        return !requireSupport || !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
    }

    private Vec3 forcedRecoveryPosition(ServerLevel level, ServerPlayer owner) {
        Vec3 look = owner.getLookAngle();
        WorldBorder border = level.getWorldBorder();
        double x = clamp(owner.getX() - look.x * 1.25D, border.getMinX() + 0.5D, border.getMaxX() - 0.5D);
        double z = clamp(owner.getZ() - look.z * 1.25D, border.getMinZ() + 0.5D, border.getMaxZ() - 0.5D);
        int minY = level.getMinY() + 1;
        int maxY = level.getMinY() + level.getHeight() - 2;
        double y = Math.max(minY, Math.min(maxY, owner.getY()));
        return new Vec3(x, y, z);
    }

    private int[] orderedVerticalOffsets(int below, int above) {
        int[] offsets = new int[below + above + 1];
        int index = 0;
        offsets[index++] = 0;
        int max = Math.max(below, above);
        for (int distance = 1; distance <= max; distance++) {
            if (distance <= below) {
                offsets[index++] = -distance;
            }
            if (distance <= above) {
                offsets[index++] = distance;
            }
        }
        return offsets;
    }

    private double clamp(double value, double min, double max) {
        if (min > max) {
            return value;
        }
        return Math.max(min, Math.min(max, value));
    }

    private BlockPos clampRecoveryY(ServerLevel level, BlockPos pos) {
        int minY = level.getMinY() + 1;
        int maxY = level.getMinY() + level.getHeight() - 2;
        if (pos.getY() >= minY && pos.getY() <= maxY) {
            return pos;
        }
        return new BlockPos(pos.getX(), Math.max(minY, Math.min(maxY, pos.getY())), pos.getZ());
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
                        discardAiPlayer(aiPlayer, "recovery_scan_tombstoned_copy");
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
                    discardAiPlayer(aiPlayer, "discard_loaded_copy");
                }
            }
        }
    }

    private void discardAiPlayer(AiPlayerEntity aiPlayer, String reason) {
        if (aiPlayer == null) {
            return;
        }
        AiPlayerMod.warn("player",
            "Discarding AI player entity: name={}, uuid={}, owner={}, reason={}, pos={}, level={}, alive={}, removed={}, removalReasonBefore={}",
            aiPlayer.getAiPlayerName(),
            aiPlayer.getUUID(),
            aiPlayer.getOwnerUuid(),
            reason == null || reason.isBlank() ? "unknown" : reason,
            aiPlayer.blockPosition().toShortString(),
            levelText(aiPlayer),
            aiPlayer.isAlive(),
            aiPlayer.isRemoved(),
            removalReasonText(aiPlayer));
        aiPlayer.discard();
        AiPlayerMod.warn("player",
            "Discarded AI player entity: name={}, uuid={}, reason={}, removed={}, removalReasonAfter={}",
            aiPlayer.getAiPlayerName(),
            aiPlayer.getUUID(),
            reason == null || reason.isBlank() ? "unknown" : reason,
            aiPlayer.isRemoved(),
            removalReasonText(aiPlayer));
    }

    private String removalReasonText(AiPlayerEntity aiPlayer) {
        if (aiPlayer == null || aiPlayer.getRemovalReason() == null) {
            return "none";
        }
        return aiPlayer.getRemovalReason().name();
    }

    private String levelText(AiPlayerEntity aiPlayer) {
        if (aiPlayer == null || aiPlayer.level() == null) {
            return "none";
        }
        return aiPlayer.level().dimension().location().toString();
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
