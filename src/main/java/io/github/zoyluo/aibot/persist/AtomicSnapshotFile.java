package io.github.zoyluo.aibot.persist;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Crash-conscious replace: durable unique temporary file followed by an atomic rename when supported. */
public final class AtomicSnapshotFile {
    private AtomicSnapshotFile() {
    }

    public static boolean write(Path destination, String content) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("snapshot_destination_has_no_parent");
        }
        Files.createDirectories(parent);
        Path temp = parent.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        boolean atomic = true;
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                atomic = false;
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(parent);
            return atomic;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is not available on every platform; the file itself was already forced.
        }
    }
}
