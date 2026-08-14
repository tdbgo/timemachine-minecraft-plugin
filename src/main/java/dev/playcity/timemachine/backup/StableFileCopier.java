package dev.playcity.timemachine.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;

final class StableFileCopier {
    private static final int MAX_ATTEMPTS = 3;
    private static final int HASH_BUFFER_SIZE = 1024 * 1024;

    private StableFileCopier() {
    }

    static TrackedFileMetadata copyStable(
            Path source,
            Path destination,
            TrackedFileMetadata observedMetadata) throws IOException {
        Files.createDirectories(destination.getParent());
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Backup copy was interrupted.");
            }

            Path temporary = destination.resolveSibling(
                    "." + destination.getFileName() + "." + UUID.randomUUID() + ".part");
            try {
                BasicFileAttributes before = readAttributes(source);
                copyWithCancellation(source, temporary);
                Files.setLastModifiedTime(temporary, before.lastModifiedTime());
                forceFile(temporary);
                BasicFileAttributes after = readAttributes(source);
                BasicFileAttributes copied = readAttributes(temporary);

                if (!sameSourceState(before, after) || copied.size() != after.size()) {
                    lastFailure = new IOException("Source changed while it was being copied: " + source);
                    continue;
                }

                String sha256 = FileHashes.sha256(temporary);
                moveIntoPlace(temporary, destination);
                return observedMetadata.withFileState(
                        after.lastModifiedTime().toMillis(),
                        after.size(),
                        sha256);
            } catch (IOException ex) {
                lastFailure = ex;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        throw new IOException(
                "Could not obtain a stable copy after " + MAX_ATTEMPTS + " attempts: " + source,
                lastFailure);
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Expected a regular file: " + path);
        }
        return attributes;
    }

    private static void copyWithCancellation(Path source, Path destination) throws IOException {
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(source);
                OutputStream output = Files.newOutputStream(
                        destination,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Backup copy was interrupted: " + source);
                }
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
        }
    }

    private static boolean sameSourceState(BasicFileAttributes left, BasicFileAttributes right) {
        if (left.size() != right.size()
                || !left.lastModifiedTime().equals(right.lastModifiedTime())) {
            return false;
        }
        Object leftKey = left.fileKey();
        Object rightKey = right.fileKey();
        return leftKey == null || rightKey == null || Objects.equals(leftKey, rightKey);
    }

    private static void moveIntoPlace(Path temporary, Path destination) throws IOException {
        try {
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        forceDirectory(destination.getParent());
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is not supported on every filesystem, notably on Windows.
        }
    }
}
