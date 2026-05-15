package com.aiplayer.util;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.core.LogEvent;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

public final class CategorizedLogWriter {
    public static final String CATEGORY_CONTEXT_KEY = "aiplayerCategory";

    private static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private CategorizedLogWriter() {
    }

    public static Path logDir() {
        return FabricLoader.getInstance().getGameDir().resolve("log");
    }

    public static boolean shouldWrite(LogEvent event) {
        if (event == null) {
            return false;
        }
        String category = event.getContextData().getValue(CATEGORY_CONTEXT_KEY);
        String loggerName = event.getLoggerName();
        return category != null || (loggerName != null && loggerName.startsWith("com.aiplayer"));
    }

    public static synchronized void write(LogEvent event) throws IOException {
        String category = categoryFor(event);
        Path categoryDir = logDir().resolve(category);
        Files.createDirectories(categoryDir);

        Path activeFile = categoryDir.resolve(category + ".log");
        byte[] entry = format(event, category).getBytes(StandardCharsets.UTF_8);
        Files.write(
            activeFile,
            entry,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE
        );
        trimOldestRecords(activeFile);
        deleteLegacyRotatedFiles(categoryDir, category);
    }

    private static void trimOldestRecords(Path activeFile) throws IOException {
        if (Files.notExists(activeFile)) {
            return;
        }
        long currentSize = Files.size(activeFile);
        if (currentSize <= MAX_FILE_BYTES) {
            return;
        }

        int keepBytes = (int) Math.min(MAX_FILE_BYTES, currentSize);
        byte[] latestBytes = new byte[keepBytes];
        try (SeekableByteChannel channel = Files.newByteChannel(activeFile, StandardOpenOption.READ)) {
            channel.position(currentSize - keepBytes);
            ByteBuffer buffer = ByteBuffer.wrap(latestBytes);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            }
        }

        int offset = firstCompleteRecordOffset(latestBytes);
        byte[] trimmed = offset <= 0 ? latestBytes : Arrays.copyOfRange(latestBytes, offset, latestBytes.length);
        Path tempFile = activeFile.resolveSibling(activeFile.getFileName() + ".tmp");
        Files.write(tempFile, trimmed, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.move(tempFile, activeFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private static int firstCompleteRecordOffset(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return Math.min(i + 1, bytes.length);
            }
        }
        return 0;
    }

    private static void deleteLegacyRotatedFiles(Path categoryDir, String category) throws IOException {
        Pattern legacyName = Pattern.compile(Pattern.quote(category) + "-\\d+\\.log");
        try (var stream = Files.list(categoryDir)) {
            for (Path path : stream.toList()) {
                if (legacyName.matcher(path.getFileName().toString()).matches()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static String categoryFor(LogEvent event) {
        String explicitCategory = event.getContextData().getValue(CATEGORY_CONTEXT_KEY);
        if (explicitCategory != null && !explicitCategory.isBlank()) {
            return sanitizeCategory(explicitCategory);
        }

        String loggerName = event.getLoggerName();
        if (loggerName == null || loggerName.isBlank()) {
            return "general";
        }
        if (loggerName.startsWith("com.aiplayer.action.actions.MakeItemAction")) {
            return "make_item";
        }
        if (loggerName.startsWith("com.aiplayer.action")
            || loggerName.startsWith("com.aiplayer.execution")) {
            return "action";
        }
        if (loggerName.startsWith("com.aiplayer.llm")
            || loggerName.startsWith("com.aiplayer.planning")) {
            return "llm";
        }
        if (loggerName.startsWith("com.aiplayer.recipe")) {
            return "recipe";
        }
        if (loggerName.startsWith("com.aiplayer.snapshot")) {
            return "snapshot";
        }
        if (loggerName.startsWith("com.aiplayer.entity")) {
            return "player";
        }
        if (loggerName.startsWith("com.aiplayer.plugin")) {
            return "plugin";
        }
        if (loggerName.startsWith("com.aiplayer.config")) {
            return "config";
        }
        if (loggerName.startsWith("com.aiplayer.event")) {
            return "event";
        }
        if (loggerName.startsWith("com.aiplayer.memory")
            || loggerName.startsWith("com.aiplayer.structure")) {
            return "structure";
        }
        return "system";
    }

    private static String format(LogEvent event, String category) {
        StringBuilder builder = new StringBuilder(256);
        builder.append(TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(event.getTimeMillis())))
            .append(" [")
            .append(event.getThreadName())
            .append('/')
            .append(event.getLevel())
            .append("] [")
            .append(category)
            .append("] ")
            .append(event.getLoggerName())
            .append(" - ")
            .append(event.getMessage().getFormattedMessage())
            .append(System.lineSeparator());

        Throwable thrown = event.getThrown();
        if (thrown != null) {
            StringWriter stringWriter = new StringWriter();
            thrown.printStackTrace(new PrintWriter(stringWriter));
            builder.append(stringWriter);
        }
        return builder.toString();
    }

    private static String sanitizeCategory(String category) {
        String value = category == null || category.isBlank() ? "general" : category;
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
        return normalized.isBlank() ? "general" : normalized;
    }
}
