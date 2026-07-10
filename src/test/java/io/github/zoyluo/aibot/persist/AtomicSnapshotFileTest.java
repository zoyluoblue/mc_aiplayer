package io.github.zoyluo.aibot.persist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicSnapshotFileTest {
    @TempDir
    Path tempDir;

    @Test
    void replacesSnapshotAndLeavesNoTemporaryFile() throws Exception {
        Path destination = tempDir.resolve("nested/runtime.json");
        AtomicSnapshotFile.write(destination, "old");
        AtomicSnapshotFile.write(destination, "new");

        assertEquals("new", Files.readString(destination));
        try (var files = Files.list(destination.getParent())) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }
}
