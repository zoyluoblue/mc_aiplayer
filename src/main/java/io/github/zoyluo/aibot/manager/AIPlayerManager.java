package io.github.zoyluo.aibot.manager;

import com.mojang.authlib.GameProfile;
import io.github.zoyluo.aibot.AIBotMod;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.network.FakeClientConnection;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

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

    private AIPlayerManager() {
    }

    public Optional<AIPlayerEntity> spawn(MinecraftServer server,
                                          String name,
                                          ServerWorld world,
                                          Vec3d pos,
                                          float yaw,
                                          float pitch,
                                          GameMode gameMode) {
        String normalizedName = normalizeName(name);
        if (nameIndex.containsKey(normalizedName) || server.getPlayerManager().getPlayer(name) != null) {
            return Optional.empty();
        }

        GameProfile profile = OfflineProfileFactory.create(name);
        SyncedClientOptions options = SyncedClientOptions.createDefault();
        AIPlayerEntity player = new AIPlayerEntity(server, world, profile, options);
        FakeClientConnection connection = new FakeClientConnection(NetworkSide.SERVERBOUND);
        ConnectedClientData clientData = new ConnectedClientData(profile, 0, options, false);

        server.getPlayerManager().onPlayerConnect(connection, player, clientData);
        player.teleport(world, pos.x, pos.y, pos.z, Collections.emptySet(), yaw, pitch, true);
        player.setHealth(20.0F);
        player.reviveForAIBotSpawn();
        EntityAttributeInstance stepHeight = player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(0.6D);
        }
        player.interactionManager.changeGameMode(gameMode);

        players.put(player.getUuid(), player);
        nameIndex.put(normalizedName, player.getUuid());
        AIBotMod.LOGGER.info("[AIBot] Spawned fake player {}", name);
        return Optional.of(player);
    }

    public boolean despawn(MinecraftServer server, String name) {
        Optional<AIPlayerEntity> player = getByName(name);
        if (player.isEmpty()) {
            return false;
        }

        AIPlayerEntity entity = player.get();
        entity.getActionPack().stopAll();
        BrainCoordinator.INSTANCE.reset(entity);
        players.remove(entity.getUuid());
        nameIndex.remove(normalizeName(name));
        if (entity.networkHandler != null) {
            entity.networkHandler.onDisconnected(new DisconnectionInfo(Text.literal("AIBot despawn")));
        } else {
            server.getPlayerManager().remove(entity);
        }
        AIBotMod.LOGGER.info("[AIBot] Despawned fake player {}", name);
        return true;
    }

    public Optional<AIPlayerEntity> getByName(String name) {
        UUID uuid = nameIndex.get(normalizeName(name));
        return uuid == null ? Optional.empty() : Optional.ofNullable(players.get(uuid));
    }

    public Collection<AIPlayerEntity> all() {
        return Collections.unmodifiableCollection(players.values());
    }

    public void onServerStopping(MinecraftServer server) {
        int count = players.size();
        for (AIPlayerEntity player : players.values().toArray(AIPlayerEntity[]::new)) {
            despawn(server, player.getGameProfile().getName());
        }
        players.clear();
        nameIndex.clear();
        AIBotMod.LOGGER.info("[AIBot] AIPlayerManager.onServerStopping cleared {} bot(s)", count);
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
