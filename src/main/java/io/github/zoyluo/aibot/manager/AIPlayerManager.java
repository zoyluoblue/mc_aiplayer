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
import net.minecraft.world.Heightmap;
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
    // 无限死亡熔断:同名 bot UUID 不变,60s 内死 ≥3 次改去世界出生点重生(脱离刷怪点/坠落点)
    private final Map<UUID, java.util.ArrayDeque<Integer>> recentDeathTicks = new ConcurrentHashMap<>();

    private AIPlayerManager() {
    }

    /**
     * SAFE-DEAD:假玩家没有客户端,死后既不会发重生包也不会被移除。旧方案"原地缝尸"
     * (setHealth+teleport)只能骗过服务端——客户端侧的死亡姿势/deathTime/红色渲染不会因
     * 血量元数据复位而重置,结果就是"死后一直红色抽搐"的僵尸观感。
     * 唯一干净的做法:整只 despawn(客户端收到实体销毁包)再全新 spawn(全新实体+全新跟踪)。
     * 名字不变 → OfflineProfile UUID 不变 → 记忆/榜单/归属身份连续;despawn 内部已清任务/大脑。
     * 返回全新实体,调用方必须换用新引用(旧引用已从世界移除)。
     */
    private static final class PendingRespawn {
        final String name;
        final String role;
        final UUID ownerUuid;
        final float yaw;
        final ServerWorld world;
        final BlockPos deathPos;
        final long deathTick;
        final Vec3d spawnPos;
        final boolean movedToSpawn;
        int dueTick;
        int attempts;

        PendingRespawn(String name, String role, UUID ownerUuid, float yaw, ServerWorld world,
                       BlockPos deathPos, long deathTick, Vec3d spawnPos, boolean movedToSpawn, int dueTick) {
            this.name = name;
            this.role = role;
            this.ownerUuid = ownerUuid;
            this.yaw = yaw;
            this.world = world;
            this.deathPos = deathPos;
            this.deathTick = deathTick;
            this.spawnPos = spawnPos;
            this.movedToSpawn = movedToSpawn;
            this.dueTick = dueTick;
        }
    }

    private final java.util.List<PendingRespawn> pendingRespawns = new java.util.ArrayList<>();

    /**
     * 死亡处理第一步:**当 tick 立刻销毁尸体**(客户端死亡姿势/红色抽搐只有实体销毁包能重置),
     * 重生排队 10 tick 后执行——不再与销毁同 tick,避免对同名同 UUID 玩家"先拆后建"的报文竞争
     * (实测:despawn+respawn 同 tick 仍偶发客户端卡死亡特效鬼畜)。幂等,可每 tick 重复调。
     */
    public void handleDeath(MinecraftServer server, AIPlayerEntity bot) {
        if (!players.containsKey(bot.getUuid())) {
            return; // 已处理过(快速路径与危险扫描都会调)
        }
        ServerWorld world = bot.getServerWorld();
        String name = bot.getGameProfile().getName();
        String role = role(bot);
        UUID ownerUuid = botOwners.get(bot.getUuid());
        float yaw = bot.getYaw();
        BlockPos deathPos = bot.getBlockPos();
        // 情景记忆:死亡入流(用死亡位置,在移除实体之前记)。蒸馏规则:同区两死 → 危险区。
        io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.record(bot,
                io.github.zoyluo.aibot.memory.EpisodeLog.Type.DEATH, deathPos,
                bot.getRecentDamageSource() == null ? "unknown" : bot.getRecentDamageSource().getName());
        // 无限死亡熔断:60s 内第 3 次死,原地重生只会被同一批怪/环境再杀——改去世界出生点。
        int nowTick = server.getTicks();
        java.util.ArrayDeque<Integer> deaths = recentDeathTicks.computeIfAbsent(bot.getUuid(), ignored -> new java.util.ArrayDeque<>());
        deaths.addLast(nowTick);
        while (!deaths.isEmpty() && deaths.peekFirst() < nowTick - 1200) {
            deaths.removeFirst();
        }
        boolean deathLoop = deaths.size() >= 3;
        ServerWorld respawnWorld = deathLoop ? server.getOverworld() : world;
        BlockPos anchor = deathLoop ? respawnWorld.getSpawnPos() : deathPos;
        BlockPos surface = respawnWorld.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, anchor);
        despawn(server, name);
        pendingRespawns.add(new PendingRespawn(name, role, ownerUuid, yaw, respawnWorld, deathPos, nowTick,
                new Vec3d(surface.getX() + 0.5D, surface.getY(), surface.getZ() + 0.5D), deathLoop, nowTick + 10));
        BotLog.lifecycle("bot_corpse_removed", "name", name, "death_pos", LogFields.pos(deathPos));
    }

    /** 死亡处理第二步(每 tick 由 AIBotMod 调):到期的重生排队项落地。 */
    public void tickRespawns(MinecraftServer server) {
        if (pendingRespawns.isEmpty()) {
            return;
        }
        int now = server.getTicks();
        java.util.Iterator<PendingRespawn> it = pendingRespawns.iterator();
        while (it.hasNext()) {
            PendingRespawn pending = it.next();
            if (now < pending.dueTick) {
                continue;
            }
            Optional<AIPlayerEntity> fresh = spawn(server, pending.name, pending.world, pending.spawnPos,
                    pending.yaw, 0.0F, GameMode.SURVIVAL, pending.ownerUuid);
            if (fresh.isEmpty()) {
                if (++pending.attempts >= 5) {
                    it.remove();
                    BotLog.lifecycle("bot_respawn_gave_up", "name", pending.name);
                } else {
                    pending.dueTick = now + 20;
                }
                continue;
            }
            it.remove();
            AIPlayerEntity newBot = fresh.get();
            setRole(newBot, pending.role);
            // 重生保护:5s 抗性 V(免疫全部伤害)+回复 II,打破"重生秒死"循环
            newBot.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.RESISTANCE, 100, 4, false, false));
            newBot.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.REGENERATION, 100, 1, false, false));
            if (pending.movedToSpawn) {
                io.github.zoyluo.aibot.brain.BrainCoordinator.INSTANCE.sendPanelChat(newBot, "system",
                        pending.name + " 短时间内连续死亡 3 次,已撤到世界出生点休整(带 5 秒重生保护)。");
            }
            // 死亡找回反射:装备掉在死亡点(5 分钟 despawn),真实玩家第一反应就是跑尸。
            // 两个闸:①重生点离死亡点 ≤160;②死亡点不在危险区(同区两死记忆立牌,听劝别送死)。
            boolean nearEnough = newBot.getBlockPos().isWithinDistance(pending.deathPos, 160.0D);
            boolean dangerous = io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE
                    .isDanger(newBot.getUuid(), pending.deathPos);
            if (nearEnough && !dangerous) {
                io.github.zoyluo.aibot.task.TaskManager.INSTANCE.assign(newBot,
                        new io.github.zoyluo.aibot.task.RecoverDropsTask(pending.deathPos, pending.deathTick));
                io.github.zoyluo.aibot.brain.BrainCoordinator.INSTANCE.sendPanelChat(newBot, "system",
                        pending.name + " 死亡后已复活,正赶回 " + pending.deathPos.toShortString() + " 找回掉落装备。");
            } else {
                io.github.zoyluo.aibot.brain.BrainCoordinator.INSTANCE.sendPanelChat(newBot, "system",
                        pending.name + " 死亡后已自动复活到地面。" + (dangerous ? "(死亡点已是危险区,放弃跑尸)" : ""));
            }
            BotLog.danger(newBot, "bot_respawned_after_death",
                    "pos", LogFields.pos(newBot.getBlockPos()), "death_loop", pending.movedToSpawn);
        }
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
