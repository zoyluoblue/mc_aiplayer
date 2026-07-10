package io.github.zoyluo.aibot.persist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.zoyluo.aibot.coordination.Job;
import io.github.zoyluo.aibot.coordination.TaskBoard;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Versioned, single-writer runtime snapshot for Bot, Mission queue/checkpoint, pause state, and Job leases. */
public final class BotPersistence {
    public static final BotPersistence INSTANCE = new BotPersistence();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String INVENTORY_KEY = "Inventory";
    private static final String RUNTIME_FILE = "runtime.json";
    private static final String LEGACY_BOTS_FILE = "bots.json";
    private static final String LEGACY_JOBS_FILE = "jobs.json";

    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "AIBotPersistenceWriter");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<PendingWrite> pendingAsync = new AtomicReference<>();
    private final AtomicBoolean asyncDrainScheduled = new AtomicBoolean();
    private volatile boolean readOnlyDueToLoadFailure;
    private volatile boolean acceptingAsyncWrites = true;
    private volatile boolean restoring;
    private volatile boolean lastSaveSucceeded;

    private BotPersistence() {
    }

    public int saveAll(MinecraftServer server) {
        if (readOnlyDueToLoadFailure) {
            lastSaveSucceeded = false;
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, null,
                    "runtime_persist_skipped_read_only", "path", runtimeFile(server));
            return 0;
        }
        RuntimeSnapshot snapshot;
        try {
            snapshot = captureSnapshot();
        } catch (RuntimeException exception) {
            lastSaveSucceeded = false;
            BotLog.error("runtime_persist_capture_failed", exception, "path", runtimeFile(server));
            return 0;
        }
        try {
            Future<Boolean> future = writer.submit(() -> {
                drainPendingWrites();
                return writeSnapshot(runtimeFile(server), snapshot);
            });
            lastSaveSucceeded = future.get();
            return lastSaveSucceeded ? snapshot.bots().size() : 0;
        } catch (InterruptedException exception) {
            lastSaveSucceeded = false;
            Thread.currentThread().interrupt();
            BotLog.error("runtime_persist_sync_failed", exception, "path", runtimeFile(server));
            return 0;
        } catch (ExecutionException | RejectedExecutionException exception) {
            lastSaveSucceeded = false;
            BotLog.error("runtime_persist_sync_failed", exception, "path", runtimeFile(server));
            return 0;
        }
    }

    public void saveAllAsync(MinecraftServer server) {
        if (readOnlyDueToLoadFailure || !acceptingAsyncWrites || restoring) {
            return;
        }
        RuntimeSnapshot snapshot;
        try {
            snapshot = captureSnapshot();
        } catch (RuntimeException exception) {
            BotLog.error("runtime_persist_capture_failed", exception, "path", runtimeFile(server));
            return;
        }
        pendingAsync.set(new PendingWrite(runtimeFile(server), snapshot));
        scheduleAsyncDrain();
    }

    /** Mutation-driven, debounced background flush. Capture occurs on the server thread. */
    public void markDirty(MinecraftServer server) {
        saveAllAsync(server);
    }

    public void freezeWrites() {
        acceptingAsyncWrites = false;
    }

    public void resumeWrites() {
        acceptingAsyncWrites = true;
    }

    public boolean lastSaveSucceeded() {
        return lastSaveSucceeded;
    }

    public List<BotRecord> load(MinecraftServer server) {
        LoadOutcome outcome = loadSnapshot(server);
        return outcome.snapshot().bots().stream().map(PersistedBot::bot).toList();
    }

    public int loadAndRespawn(MinecraftServer server) {
        restoring = true;
        try {
            return loadAndRespawnInternal(server);
        } finally {
            restoring = false;
        }
    }

    /** Admin command boundary: never replace Job leases or Bot runtime underneath live work. */
    public int reloadIfIdle(MinecraftServer server) {
        if (!AIPlayerManager.INSTANCE.all().isEmpty()
                || TaskManager.INSTANCE.activeCount() > 0
                || !TaskBoard.INSTANCE.snapshot().isEmpty()) {
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, null,
                    "runtime_live_reload_rejected", "reason", "runtime_not_empty");
            return -1;
        }
        return loadAndRespawn(server);
    }

    private int loadAndRespawnInternal(MinecraftServer server) {
        readOnlyDueToLoadFailure = false;
        TaskBoard.INSTANCE.beginRuntimeSession();
        LoadOutcome outcome = loadSnapshot(server);
        RuntimeSnapshot snapshot = outcome.snapshot();
        List<RestoredMission> missions = new ArrayList<>();
        int restored = 0;
        for (PersistedBot persisted : snapshot.bots()) {
            if (persisted == null || persisted.bot() == null) {
                continue;
            }
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.respawnFromRecord(server, persisted.bot());
            if (bot.isPresent()) {
                restored++;
                missions.add(new RestoredMission(bot.get(), persisted.missions()));
            }
        }
        TaskBoard.INSTANCE.replaceAll(migrateJobs(snapshot.jobs()));
        for (RestoredMission restoredMission : missions) {
            GoalExecutor.INSTANCE.restoreRuntime(restoredMission.bot(), restoredMission.missions());
        }
        if (outcome.legacyMigrated() && !readOnlyDueToLoadFailure) {
            saveAll(server);
            if (lastSaveSucceeded) {
                backupLegacyFiles(server);
                BotLog.lifecycle("runtime_legacy_migrated", "bots", restored,
                        "schema", RuntimeSnapshot.CURRENT_SCHEMA);
            } else {
                BotLog.error("runtime_legacy_migration_write_failed", null,
                        "recovery", "legacy_files_were_left_in_place");
            }
        }
        return restored;
    }

    public static BotRecord capture(AIPlayerEntity bot) {
        return new BotRecord(
                bot.getGameProfile().getName(),
                bot.getWorld().getRegistryKey().getValue().toString(),
                bot.getX(), bot.getY(), bot.getZ(), bot.getYaw(), bot.getPitch(),
                bot.interactionManager.getGameMode().getName(),
                bot.getHealth(), bot.getHungerManager().getFoodLevel(),
                encodeInventory(bot), AIPlayerManager.INSTANCE.role(bot),
                BotMemoryStore.INSTANCE.saveString(bot.getUuid()),
                AIPlayerManager.INSTANCE.ownerOf(bot).map(UUID::toString).orElse(""));
    }

    public static String encodeInventory(ServerPlayerEntity player) {
        NbtCompound root = new NbtCompound();
        root.put(INVENTORY_KEY, player.getInventory().writeNbt(new NbtList()));
        return root.toString();
    }

    public static void applyInventory(ServerPlayerEntity player, String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return;
        }
        try {
            NbtCompound root = StringNbtReader.parse(snbt);
            NbtList inventory = root.getList(INVENTORY_KEY, NbtElement.COMPOUND_TYPE);
            PlayerInventory playerInventory = player.getInventory();
            playerInventory.readNbt(inventory);
            playerInventory.markDirty();
        } catch (Exception exception) {
            BotLog.error(player instanceof AIPlayerEntity bot ? bot : null, "bot_inventory_restore_failed", exception);
        }
    }

    private RuntimeSnapshot captureSnapshot() {
        List<PersistedBot> bots = new ArrayList<>();
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            bots.add(new PersistedBot(capture(bot), GoalExecutor.INSTANCE.captureRuntime(bot)));
        }
        return new RuntimeSnapshot(
                RuntimeSnapshot.CURRENT_SCHEMA,
                Instant.now().toString(),
                buildVersion(),
                TaskBoard.INSTANCE.runtimeSessionId().toString(),
                bots,
                TaskBoard.INSTANCE.snapshot());
    }

    private LoadOutcome loadSnapshot(MinecraftServer server) {
        Path runtime = runtimeFile(server);
        if (Files.exists(runtime)) {
            try (Reader reader = Files.newBufferedReader(runtime)) {
                RuntimeSnapshotCodec.DecodeResult decoded = RuntimeSnapshotCodec.decode(reader);
                if (decoded.status() == RuntimeSnapshotCodec.Status.UNSUPPORTED_SCHEMA) {
                    readOnlyDueToLoadFailure = true;
                    BotLog.error("runtime_schema_unsupported", null,
                            "path", runtime, "found", decoded.foundSchema(),
                            "supported", RuntimeSnapshot.CURRENT_SCHEMA,
                            "recovery", "downgrade_is_blocked; restore_a_compatible_backup_or_upgrade_the_mod");
                    return new LoadOutcome(emptySnapshot(), false);
                }
                if (decoded.status() == RuntimeSnapshotCodec.Status.MALFORMED) {
                    readOnlyDueToLoadFailure = true;
                    BotLog.error("runtime_persist_load_failed", null,
                            "path", runtime, "reason", decoded.reason(),
                            "recovery", "repair_or_move_runtime_json_then_restart");
                    return new LoadOutcome(emptySnapshot(), false);
                }
                return new LoadOutcome(decoded.snapshot(), false);
            } catch (IOException | RuntimeException exception) {
                readOnlyDueToLoadFailure = true;
                BotLog.error("runtime_persist_load_failed", exception,
                        "path", runtime, "recovery", "repair_or_move_runtime_json_then_restart");
                return new LoadOutcome(emptySnapshot(), false);
            }
        }
        return loadLegacy(server);
    }

    private LoadOutcome loadLegacy(MinecraftServer server) {
        Path dir = aibotDir(server);
        List<PersistedBot> bots = new ArrayList<>();
        List<Job> jobs = List.of();
        boolean found = false;
        Path botFile = dir.resolve(LEGACY_BOTS_FILE);
        if (Files.exists(botFile)) {
            found = true;
            try (Reader reader = Files.newBufferedReader(botFile)) {
                BotRecord[] records = GSON.fromJson(reader, BotRecord[].class);
                if (records != null) {
                    for (BotRecord record : records) {
                        bots.add(new PersistedBot(record, MissionRuntimeRecord.empty()));
                    }
                }
            } catch (IOException | RuntimeException exception) {
                readOnlyDueToLoadFailure = true;
                BotLog.error("legacy_bot_persist_load_failed", exception, "path", botFile);
                return new LoadOutcome(emptySnapshot(), false);
            }
        }
        Path jobFile = dir.resolve(LEGACY_JOBS_FILE);
        if (Files.exists(jobFile)) {
            found = true;
            try (Reader reader = Files.newBufferedReader(jobFile)) {
                Job[] loaded = GSON.fromJson(reader, Job[].class);
                jobs = loaded == null ? List.of() : List.of(loaded);
            } catch (IOException | RuntimeException exception) {
                readOnlyDueToLoadFailure = true;
                BotLog.error("legacy_jobs_persist_load_failed", exception, "path", jobFile);
                return new LoadOutcome(emptySnapshot(), false);
            }
        }
        return new LoadOutcome(new RuntimeSnapshot(RuntimeSnapshot.CURRENT_SCHEMA, Instant.now().toString(),
                buildVersion(), TaskBoard.INSTANCE.runtimeSessionId().toString(), bots, jobs), found);
    }

    private List<Job> migrateJobs(List<Job> loaded) {
        List<Job> migrated = new ArrayList<>();
        for (Job job : loaded == null ? List.<Job>of() : loaded) {
            if (job == null || job.id() == null || job.params() == null) {
                continue;
            }
            if (job.scope() != null) {
                migrated.add(job);
                continue;
            }
            UUID owner = job.claimant() == null ? null : AIPlayerManager.INSTANCE.getByUuid(job.claimant())
                    .flatMap(AIPlayerManager.INSTANCE::ownerOf).orElse(null);
            if (owner != null) {
                migrated.add(new Job(job.id(), job.kind(), job.params(), job.role(), Job.Scope.OWNER, owner,
                        Job.Status.OPEN, null, null, null, "legacy_claim_reopened"));
            } else {
                migrated.add(new Job(job.id(), job.kind(), job.params(), job.role(), Job.Scope.GLOBAL_ADMIN, null,
                        Job.Status.FAILED, null, null, null, "legacy_scope_required"));
            }
        }
        return List.copyOf(migrated);
    }

    private void scheduleAsyncDrain() {
        if (asyncDrainScheduled.compareAndSet(false, true)) {
            writer.schedule(this::drainPendingWrites, 750, TimeUnit.MILLISECONDS);
        }
    }

    private void drainPendingWrites() {
        try {
            PendingWrite pending;
            while ((pending = pendingAsync.getAndSet(null)) != null) {
                writeSnapshot(pending.path(), pending.snapshot());
            }
        } finally {
            asyncDrainScheduled.set(false);
            if (pendingAsync.get() != null) {
                scheduleAsyncDrain();
            }
        }
    }

    private boolean writeSnapshot(Path file, RuntimeSnapshot snapshot) {
        try {
            boolean atomic = AtomicSnapshotFile.write(file, RuntimeSnapshotCodec.encode(snapshot));
            BotLog.lifecycle("runtime_persist_saved", "bots", snapshot.bots().size(),
                    "jobs", snapshot.jobs().size(), "path", file, "schema", snapshot.schemaVersion(),
                    "atomic_move", atomic);
            if (!atomic) {
                BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.LIFECYCLE, null,
                        "atomic_move_not_supported", "path", file);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            BotLog.error("runtime_persist_save_failed", exception, "path", file,
                    "bots", snapshot.bots().size(), "jobs", snapshot.jobs().size());
            return false;
        }
    }

    private void backupLegacyFiles(MinecraftServer server) {
        for (String name : List.of(LEGACY_BOTS_FILE, LEGACY_JOBS_FILE)) {
            Path source = aibotDir(server).resolve(name);
            Path backup = source.resolveSibling(name + ".migrated.bak");
            try {
                if (Files.exists(source) && !Files.exists(backup)) {
                    Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
                }
            } catch (IOException exception) {
                BotLog.error("legacy_backup_failed", exception, "path", source);
            }
        }
    }

    private RuntimeSnapshot emptySnapshot() {
        return new RuntimeSnapshot(RuntimeSnapshot.CURRENT_SCHEMA, Instant.now().toString(), buildVersion(),
                TaskBoard.INSTANCE.runtimeSessionId().toString(), List.of(), List.of());
    }

    private Path runtimeFile(MinecraftServer server) {
        return aibotDir(server).resolve(RUNTIME_FILE);
    }

    private Path aibotDir(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("aibot");
    }

    private static String buildVersion() {
        return FabricLoader.getInstance().getModContainer("aibot")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private record PendingWrite(Path path, RuntimeSnapshot snapshot) {
    }

    private record LoadOutcome(RuntimeSnapshot snapshot, boolean legacyMigrated) {
    }

    private record RestoredMission(AIPlayerEntity bot, MissionRuntimeRecord missions) {
    }
}
