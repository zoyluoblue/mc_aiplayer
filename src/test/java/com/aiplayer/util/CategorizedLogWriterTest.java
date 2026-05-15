package com.aiplayer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategorizedLogWriterTest {
    private static final long MAX_LOG_BYTES = 10L * 1024L * 1024L;

    @TempDir
    Path tempDir;

    @Test
    void trimsOldestRecordsWhenActiveFileExceedsLimit() throws Exception {
        Path logFile = tempDir.resolve("mining.log");
        Files.writeString(logFile, "old-record\n", StandardCharsets.UTF_8);
        byte[] filler = new byte[(int) MAX_LOG_BYTES + 1024];
        Arrays.fill(filler, (byte) 'x');
        Files.write(logFile, filler, StandardOpenOption.APPEND);
        Files.writeString(logFile, "\nnew-record\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Method method = CategorizedLogWriter.class.getDeclaredMethod("trimOldestRecords", Path.class);
        method.setAccessible(true);
        method.invoke(null, logFile);

        String content = Files.readString(logFile, StandardCharsets.UTF_8);
        assertTrue(Files.size(logFile) <= MAX_LOG_BYTES);
        assertFalse(content.contains("old-record"));
        assertTrue(content.contains("new-record"));
    }

    @Test
    void deletesLegacyRotatedFilesForCategory() throws Exception {
        Path active = tempDir.resolve("action.log");
        Path oldOne = tempDir.resolve("action-1.log");
        Path oldTwo = tempDir.resolve("action-2.log");
        Path otherCategory = tempDir.resolve("mining-1.log");
        Files.writeString(active, "active", StandardCharsets.UTF_8);
        Files.writeString(oldOne, "old", StandardCharsets.UTF_8);
        Files.writeString(oldTwo, "old", StandardCharsets.UTF_8);
        Files.writeString(otherCategory, "other", StandardCharsets.UTF_8);

        Method method = CategorizedLogWriter.class.getDeclaredMethod("deleteLegacyRotatedFiles", Path.class, String.class);
        method.setAccessible(true);
        method.invoke(null, tempDir, "action");

        assertTrue(Files.exists(active));
        assertFalse(Files.exists(oldOne));
        assertFalse(Files.exists(oldTwo));
        assertTrue(Files.exists(otherCategory));
    }
}
