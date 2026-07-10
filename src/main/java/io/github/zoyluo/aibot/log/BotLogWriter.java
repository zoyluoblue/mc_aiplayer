package io.github.zoyluo.aibot.log;

import io.github.zoyluo.aibot.AIBotConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class BotLogWriter {
    public static final BotLogWriter INSTANCE = new BotLogWriter();

    private static final Logger MIRROR = LoggerFactory.getLogger("aibot");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final ArrayBlockingQueue<LogEntry> queue = new ArrayBlockingQueue<>(4096);
    private final ArrayDeque<LogEntry> bootstrapEntries = new ArrayDeque<>();
    private final AtomicLong droppedCount = new AtomicLong();
    private final Map<String, BufferedWriter> botWriters = new ConcurrentHashMap<>();

    private AIBotConfig.Logging config = AIBotConfig.defaults().logging();
    private Path baseDir;
    private BufferedWriter allWriter;
    private Thread workerThread;
    private volatile boolean stopRequested;
    private volatile boolean started;
    private LocalDate currentDate;
    private Map<LogCategory, Level> thresholds = defaultThresholds();

    private BotLogWriter() {
    }

    public synchronized void start(AIBotConfig config) {
        if (started) {
            return;
        }
        this.config = config.logging();
        thresholds = thresholds(this.config);
        if (!this.config.enabled()) {
            return;
        }
        baseDir = FabricLoader.getInstance().getGameDir().resolve(this.config.directory());
        try {
            Files.createDirectories(baseDir.resolve("by-bot"));
            Files.createDirectories(baseDir.resolve("archive"));
            currentDate = LocalDate.now();
            allWriter = Files.newBufferedWriter(baseDir.resolve("all.log"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            stopRequested = false;
            workerThread = new Thread(this::workerLoop, "AIBotLogWriter");
            workerThread.setDaemon(true);
            workerThread.start();
            started = true;
            flushBootstrapEntries();
        } catch (IOException exception) {
            MIRROR.warn("[AIBot] structured log disabled, failed to start writer", exception);
        }
    }

    public void submit(LogCategory category, Level level, String botName, String event, Map<String, String> fields, String humanMessage, Throwable throwable) {
        LogEntry entry = new LogEntry(System.currentTimeMillis(), category, level, botName, event, Map.copyOf(fields), humanMessage, throwable);
        boolean security = category == LogCategory.SECURITY;
        boolean bootstrapCritical = (category == LogCategory.CONFIG || category == LogCategory.ERROR)
                && (!started || !config.enabled());
        if (security || bootstrapCritical) {
            // Authorization denials and configuration/bootstrap failures must remain observable even
            // before the asynchronous writer starts, or when structured logging is disabled.
            mirror(entry);
        }
        if (!started && (category == LogCategory.CONFIG || category == LogCategory.ERROR)) {
            bufferBootstrapEntry(entry);
        }
        if (!started || !config.enabled() || level.toInt() < thresholds.getOrDefault(category, Level.INFO).toInt()) {
            return;
        }
        if (!queue.offer(entry)) {
            droppedCount.incrementAndGet();
            if (security) {
                MIRROR.error("[AIBot] SECURITY log queue full; denial remained mirrored but structured copy was dropped");
            }
        }
        if (!security && config.mirrorToSlf4j() && level.toInt() >= Level.INFO.toInt()) {
            mirror(entry);
        }
    }

    public synchronized void shutdown(long timeoutMs) {
        stopRequested = true;
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(timeoutMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        drainQueue();
        closeWriters();
        started = false;
    }

    public void forceRotateForTest() {
        if (started) {
            currentDate = currentDate.minusDays(1);
            maybeRotateForDate();
        }
    }

    public void forceOverflowForTest(int count) {
        if (!started || !config.enabled()) {
            return;
        }
        int attempts = Math.max(1, count);
        long droppedBefore = droppedCount.get();
        for (int index = 0; index < attempts; index++) {
            LogEntry entry = new LogEntry(
                    System.currentTimeMillis(),
                    LogCategory.ACTION,
                    Level.INFO,
                    "-",
                    "overflow_test_event",
                    Map.of("index", Integer.toString(index)),
                    "manual overflow validation",
                    null);
            if (!queue.offer(entry)) {
                droppedCount.incrementAndGet();
            }
        }
        if (droppedCount.get() == droppedBefore) {
            droppedCount.incrementAndGet();
            queue.offer(new LogEntry(
                    System.currentTimeMillis(),
                    LogCategory.ERROR,
                    Level.ERROR,
                    "-",
                    "overflow_test_marker",
                    Map.of("requested", Integer.toString(attempts)),
                    "manual overflow validation marker",
                    null));
        }
    }

    public boolean isStarted() {
        return started;
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    public int queueSize() {
        return queue.size();
    }

    public Path baseDir() {
        return baseDir;
    }

    private synchronized void bufferBootstrapEntry(LogEntry entry) {
        if (bootstrapEntries.size() >= 128) {
            bootstrapEntries.removeFirst();
            droppedCount.incrementAndGet();
        }
        bootstrapEntries.addLast(entry);
    }

    private void flushBootstrapEntries() {
        while (!bootstrapEntries.isEmpty()) {
            LogEntry entry = bootstrapEntries.removeFirst();
            if (entry.level().toInt() < thresholds.getOrDefault(entry.category(), Level.INFO).toInt()) {
                continue;
            }
            if (!queue.offer(entry)) {
                droppedCount.incrementAndGet();
            }
        }
    }

    private void workerLoop() {
        while (!stopRequested) {
            try {
                LogEntry entry = queue.poll(1, TimeUnit.SECONDS);
                if (entry == null) {
                    maybeRotateForDate();
                    continue;
                }
                writeEntry(entry);
                long dropped = droppedCount.getAndSet(0);
                if (dropped > 0) {
                    try {
                        writeRawWarn("LOG QUEUE OVERFLOW: dropped " + dropped + " entries");
                    } catch (IOException exception) {
                        MIRROR.warn("[AIBot] failed to write overflow warning", exception);
                    }
                }
            } catch (InterruptedException exception) {
                if (stopRequested) {
                    return;
                }
                Thread.currentThread().interrupt();
            }
        }
    }

    private void drainQueue() {
        LogEntry entry;
        while ((entry = queue.poll()) != null) {
            writeEntry(entry);
        }
    }

    private void writeEntry(LogEntry entry) {
        try {
            maybeRotateForDate();
            String line = format(entry);
            allWriter.write(line);
            allWriter.newLine();
            allWriter.flush();
            if (config.perBotFile()) {
                BufferedWriter writer = writerFor(entry.botName());
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
            maybeRotateForSize();
        } catch (IOException exception) {
            MIRROR.warn("[AIBot] failed to write structured log", exception);
        }
    }

    private BufferedWriter writerFor(String botName) throws IOException {
        String safe = "-".equals(botName) ? "_system" : botName.replaceAll("[^a-zA-Z0-9_.-]", "_");
        BufferedWriter existing = botWriters.get(safe);
        if (existing != null) {
            return existing;
        }
        BufferedWriter created = Files.newBufferedWriter(baseDir.resolve("by-bot").resolve(safe + ".log"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        botWriters.put(safe, created);
        return created;
    }

    private String format(LogEntry entry) {
        StringBuilder builder = new StringBuilder(256);
        builder.append(TS_FORMAT.format(Instant.ofEpochMilli(entry.timestamp())))
                .append(" [").append(pad(entry.level().name(), 5)).append("] ")
                .append("[").append(pad(entry.category().name(), 10)).append("] ")
                .append("bot=").append(entry.botName())
                .append(" event=").append(entry.event());
        for (Map.Entry<String, String> field : entry.fields().entrySet()) {
            builder.append(' ').append(field.getKey()).append('=').append(quote(field.getValue()));
        }
        if (entry.humanMessage() != null && !entry.humanMessage().isBlank()) {
            builder.append(" | ").append(entry.humanMessage());
        }
        if (entry.throwable() != null) {
            builder.append(" throwable=").append(quote(entry.throwable().getClass().getSimpleName() + ":" + entry.throwable().getMessage()));
        }
        return builder.toString();
    }

    private void maybeRotateForDate() {
        if (currentDate == null || baseDir == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            rotate("date");
            currentDate = today;
        }
    }

    private void maybeRotateForSize() throws IOException {
        long maxBytes = Math.max(1, config.maxFileSizeMb()) * 1024L * 1024L;
        if (Files.exists(baseDir.resolve("all.log")) && Files.size(baseDir.resolve("all.log")) > maxBytes) {
            rotate("size");
        }
    }

    private synchronized void rotate(String reason) {
        try {
            closeWriters();
            String suffix = currentDate == null ? LocalDate.now().toString() : currentDate.toString();
            moveIfExists(baseDir.resolve("all.log"), baseDir.resolve("archive").resolve("all-" + suffix + "-" + reason + ".log"));
            Path byBot = baseDir.resolve("by-bot");
            if (Files.exists(byBot)) {
                try (var stream = Files.list(byBot)) {
                    for (Path path : stream.filter(path -> path.toString().endsWith(".log")).toList()) {
                        moveIfExists(path, baseDir.resolve("archive").resolve(path.getFileName().toString().replace(".log", "-" + suffix + "-" + reason + ".log")));
                    }
                }
            }
            Files.createDirectories(baseDir.resolve("by-bot"));
            allWriter = Files.newBufferedWriter(baseDir.resolve("all.log"), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            cleanupArchives();
        } catch (IOException exception) {
            MIRROR.warn("[AIBot] failed to rotate structured logs", exception);
        }
    }

    private void cleanupArchives() throws IOException {
        Path archive = baseDir.resolve("archive");
        if (!Files.exists(archive)) {
            return;
        }
        Instant cutoff = Instant.now().minusSeconds(Math.max(1, config.maxBackups()) * 86_400L);
        try (var stream = Files.list(archive)) {
            for (Path path : stream.toList()) {
                if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void moveIfExists(Path from, Path to) throws IOException {
        if (Files.exists(from) && Files.size(from) > 0) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(from);
        }
    }

    private void writeRawWarn(String message) throws IOException {
        allWriter.write(TS_FORMAT.format(Instant.now()) + " [WARN ] [ERROR     ] bot=- event=log_queue_overflow message=" + quote(message));
        allWriter.newLine();
        allWriter.flush();
    }

    private void closeWriters() {
        close(allWriter);
        allWriter = null;
        for (BufferedWriter writer : botWriters.values()) {
            close(writer);
        }
        botWriters.clear();
    }

    private void close(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }

    private void mirror(LogEntry entry) {
        String line = "[AIBot] " + entry.category() + " event=" + entry.event() + " bot=" + entry.botName() + " " + entry.fields();
        switch (entry.level()) {
            case ERROR -> MIRROR.error(line, entry.throwable());
            case WARN -> MIRROR.warn(line);
            case INFO -> MIRROR.info(line);
            case DEBUG -> MIRROR.debug(line);
            case TRACE -> MIRROR.trace(line);
        }
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }

    private static Map<LogCategory, Level> defaultThresholds() {
        Map<LogCategory, Level> map = new EnumMap<>(LogCategory.class);
        for (LogCategory category : LogCategory.values()) {
            map.put(category, switch (category) {
                case PERCEPTION, PATH -> Level.DEBUG;
                case ERROR -> Level.ERROR;
                default -> Level.INFO;
            });
        }
        return map;
    }

    private static Map<LogCategory, Level> thresholds(AIBotConfig.Logging logging) {
        Map<LogCategory, Level> map = defaultThresholds();
        logging.categories().forEach((key, value) -> {
            try {
                map.put(LogCategory.valueOf(key), Level.valueOf(value));
            } catch (IllegalArgumentException ignored) {
            }
        });
        return map;
    }
}
