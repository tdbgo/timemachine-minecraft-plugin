package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StableFileCopierTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesAndHashesAStableSource() throws Exception {
        Path source = temporaryDirectory.resolve("source.mca");
        Path destination = temporaryDirectory.resolve("snapshot/worlds/uuid/region/r.0.0.mca");
        Files.writeString(source, "region-data", StandardCharsets.UTF_8);
        TrackedFileMetadata observed = new TrackedFileMetadata(
                "worlds/uuid/region/r.0.0.mca",
                "world",
                "minecraft:overworld",
                "uuid",
                "worlds/uuid",
                BackupScope.REGION,
                0,
                0,
                Files.getLastModifiedTime(source).toMillis(),
                Files.size(source),
                "");

        TrackedFileMetadata copied = StableFileCopier.copyStable(source, destination, observed);

        assertEquals("region-data", Files.readString(destination, StandardCharsets.UTF_8));
        assertEquals(Files.size(destination), copied.size());
        assertEquals(FileHashes.sha256(destination), copied.sha256());
        assertTrue(copied.sha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void interruptedCopyDoesNotPublishAPartialDestination() throws Exception {
        Path source = temporaryDirectory.resolve("interrupted-source.mca");
        Path destination = temporaryDirectory.resolve("interrupted/r.0.0.mca");
        Files.writeString(source, "region-data", StandardCharsets.UTF_8);
        TrackedFileMetadata observed = new TrackedFileMetadata(
                "worlds/uuid/region/r.0.0.mca",
                "world",
                "minecraft:overworld",
                "uuid",
                "worlds/uuid",
                BackupScope.REGION,
                0,
                0,
                Files.getLastModifiedTime(source).toMillis(),
                Files.size(source),
                "");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                StableFileCopier.copyStable(source, destination, observed);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        worker.start();
        worker.join(5_000L);

        assertFalse(worker.isAlive());
        assertTrue(failure.get() instanceof java.io.IOException);
        assertFalse(Files.exists(destination));
    }
}
