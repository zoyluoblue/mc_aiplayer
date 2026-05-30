package io.github.zoyluo.aibot.manager;

import com.mojang.authlib.GameProfile;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogFields;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.network.FakeClientConnection;
import io.github.zoyluo.aibot.pathfinding.Standability;
import io.github.zoyluo.aibot.persist.BotPersistence;
import io.github.zoyluo.aibot.persist.BotRecord;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.util.OfflineProfileFactory;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AIPlayerManager {
    public static final AIPlayerManager INSTANCE = new AIPlayerManager();

    private final Map<UUID, AIPlayerEntity> players = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final Map<UUID, String> roles = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> ownerIndex = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> botOwners = new ConcurrentHashMap<>();

    private AIPlayerManager() {
    }

    public Optional<AIPlayerEntity> spawn(MinecraftServer server,
                                          String name,
                                          ServerWorld world,
                                          Vec3d pos,
                                          float yaw,
                                          float pitch,
                                          GameMode gameMode) {
        return spawn(server, name, world, pos, yaw, pitch, gameMode, null);
    }

    public Optional<AIPlayerEntity> spawn(MinecraftServer server,
                                          String name,
                                          ServerWorld world,
                                          Vec3d pos,
                                          float yaw,
                                          float pitch,
                                          GameMode gameMode,
                                          UUID ownerUuid) {
        String normalizedName = normalizeName(name);
        if (nameIndex.containsKey(normalizedName) || server.getPlayerManager().getPlayer(name) != null) {
            return Optional.empty();
        }
        if (ownerUuid != null && botOf(ownerUuid).isPresent()) {
            return Optional.empty();
        }

        GameProfile profile = OfflineProfileFactory.create(name);
        SyncedClientOptions options = SyncedClientOptions.createDefault();
        AIPlayerEntity player = new AIPlayerEntity(server, world, profile, options);
        FakeClientConnection connection = new FakeClientConnection(NetworkSide.SERVERBOUND);
        ConnectedClientData clientData = new ConnectedClientData(profile, 0, options, false);
        Vec3d safePos = safeSpawnPosition(world, pos, name);

        server.getPlayerManager().onPlayerConnect(connection, player, clientData);
        player.teleport(world, safePos.x, safePos.y, safePos.z, Collections.emptySet(), yaw, pitch, true);
        player.setHealth(20.0F);
        player.reviveForAIBotSpawn();
        EntityAttributeInstance stepHeight = player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(0.6D);
        }
        // AI 助手固定生存模式:创造模式破方块不掉落、冒险模式禁止破坏/放置,都会让采集/建造失效。
        // 故忽略传入的 gameMode(可能是召唤者的创造,或旧存档恢复的 creative),一律 SURVIVAL。
        GameMode effectiveMode = GameMode.SURVIVAL;
        player.interactionManager.changeGameMode(effectiveMode);

        players.put(player.getUuid(), player);
        nameIndex.put(normalizedName, player.getUuid());
        roles.put(player.getUuid(), "worker");
        if (ownerUuid != null) {
            ownerIndex.put(ownerUuid, player.getUuid());
            botOwners.put(player.getUuid(), ownerUuid);
        }
        BotLog.lifecycle(player, "bot_spawned", "pos", LogFields.pos(player.getBlockPos()), "mode", effectiveMode.getName());
        return Optional.of(player);
    }

    public Optional<AIPlayerEntity> respawnFromRecord(MinecraftServer server, BotRecord record) {
        RestoreTarget target = restoreTarget(server, record);
        GameMode gameMode = GameMode.SURVIVAL;  // AI 助手一律生存,忽略旧存档可能存的 creative
        Optional<AIPlayerEntity> spawned = spawn(
                server,
                record.name(),
                target.world(),
                target.pos(),
                record.yaw(),
                record.pitch(),
                gameMode,
                parseUuid(record.ownerUuid()));
        spawned.ifPresent(bot -> {
            BotPersistence.applyInventory(bot, record.inventoryNbt());
            setRole(bot, record.role());
            BotMemoryStore.INSTANCE.loadString(bot.getUuid(), record.memoryNbt());
            bot.setHealth(Math.max(1.0F, Math.min(record.health(), bot.getMaxHealth())));
            bot.getHungerManager().setFoodLevel(Math.max(0, Math.min(20, record.hunger())));
            BotLog.lifecycle(bot, "bot_restored",
                    "pos", LogFields.pos(bot.getBlockPos()),
                    "mode", gameMode.getName(),
                    "dimension", bot.getWorld().getRegistryKey().getValue(),
                    "fallback", target.fallback());
        });
        return spawned;
    }

    public boolean despawn(MinecraftServer server, String name) {
        Optional<AIPlayerEntity> player = getByName(name);
        if (player.isEmpty()) {
            return false;
        }

        AIPlayerEntity entity = player.get();
        entity.getActionPack().stopAll();
        BrainCoordinator.INSTANCE.reset(entity);
        TaskManager.INSTANCE.onBotDespawn(entity);
        io.github.zoyluo.aibot.coordination.IdleCoordinator.INSTANCE.onBotRemoved(entity);
        players.remove(entity.getUuid());
        nameIndex.remove(normalizeName(name));
        roles.remove(entity.getUuid());
        clearOwner(entity.getUuid());
        if (entity.networkHandler != null) {
            entity.networkHandler.onDisconnected(new DisconnectionInfo(Text.literal("AIBot despawn")));
        } else {
            server.getPlayerManager().remove(entity);
        }
        BotLog.lifecycle(entity, "bot_despawned", "reason", "command_or_shutdown");
        return true;
    }

    public Optional<AIPlayerEntity> getByName(String name) {
        UUID uuid = nameIndex.get(normalizeName(name));
        return uuid == null ? Optional.empty() : Optional.ofNullable(players.get(uuid));
    }

    public Optional<AIPlayerEntity> getByUuid(UUID uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    public Optional<AIPlayerEntity> botOf(UUID ownerUuid) {
        UUID botUuid = ownerIndex.get(ownerUuid);
        if (botUuid == null) {
            return Optional.empty();
        }
        AIPlayerEntity bot = players.get(botUuid);
        if (bot == null) {
            ownerIndex.remove(ownerUuid);
            return Optional.empty();
        }
        return Optional.of(bot);
    }

    public Optional<UUID> ownerOf(AIPlayerEntity bot) {
        return Optional.ofNullable(botOwners.get(bot.getUuid()));
    }

    public Collection<AIPlayerEntity> all() {
        return Collections.unmodifiableCollection(players.values());
    }

    public void setRole(AIPlayerEntity bot, String role) {
        roles.put(bot.getUuid(), normalizeRole(role));
        BotLog.lifecycle(bot, "bot_role_set", "role", role(bot));
    }

    public String role(AIPlayerEntity bot) {
        return roles.getOrDefault(bot.getUuid(), "worker");
    }

    public java.util.Set<String> roles(AIPlayerEntity bot) {
        String role = role(bot);
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        result.add("worker");
        result.add(role);
        return java.util.Set.copyOf(result);
    }

    public void onServerStopping(MinecraftServer server) {
        int count = players.size();
        for (AIPlayerEntity player : players.values().toArray(AIPlayerEntity[]::new)) {
            despawn(server, player.getGameProfile().getName());
        }
        players.clear();
        nameIndex.clear();
        ownerIndex.clear();
        botOwners.clear();
        BotLog.lifecycle("all_bots_cleared", "count", count);
    }

    private void clearOwner(UUID botUuid) {
        UUID ownerUuid = botOwners.remove(botUuid);
        if (ownerUuid != null) {
            ownerIndex.remove(ownerUuid, botUuid);
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static RestoreTarget restoreTarget(MinecraftServer server, BotRecord record) {
        RegistryKey<World> worldKey;
        try {
            worldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(record.dimension()));
        } catch (RuntimeException exception) {
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, null, "bot_restore_dimension_invalid",
                    "name", record.name(), "dimension", record.dimension());
            return overworldSpawn(server);
        }
        ServerWorld world = server.getWorld(worldKey);
        if (world == null) {
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, null, "bot_restore_world_missing",
                    "name", record.name(), "dimension", record.dimension());
            return overworldSpawn(server);
        }
        return new RestoreTarget(world, new Vec3d(record.x(), record.y(), record.z()), false);
    }

    private static RestoreTarget overworldSpawn(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        return new RestoreTarget(overworld, Vec3d.ofBottomCenter(overworld.getSpawnPos()), true);
    }

    private static Vec3d safeSpawnPosition(ServerWorld world, Vec3d requested, String name) {
        BlockPos requestedBlock = BlockPos.ofFloored(requested);
        Standability.clearCache();
        if (Standability.isStandable(world, requestedBlock)) {
            return requested;
        }
        Optional<BlockPos> safe = Standability.findNearestStandable(world, requestedBlock, 8, 128, 32);
        if (safe.isEmpty()) {
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, null, "bot_spawn_position_unsafe",
                    "name", name, "requested", LogFields.pos(requestedBlock));
            return requested;
        }
        BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, null, "bot_spawn_position_snapped",
                "name", name,
                "from", LogFields.pos(requestedBlock),
                "to", LogFields.pos(safe.get()));
        return Vec3d.ofBottomCenter(safe.get());
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "worker";
        }
        return role.trim().toLowerCase(Locale.ROOT);
    }

    private record RestoreTarget(ServerWorld world, Vec3d pos, boolean fallback) {
    }
}
