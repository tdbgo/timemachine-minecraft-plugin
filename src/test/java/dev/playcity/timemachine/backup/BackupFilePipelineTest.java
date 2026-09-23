package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.playcity.timemachine.config.TimeMachineSettings;
import dev.playcity.timemachine.i18n.LanguageMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupFilePipelineTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAFullCopyAndReusesItsHashInFastMode() throws Exception {
        Path worldPath = temporaryDirectory.resolve("world");
        Path regionPath = worldPath.resolve("region");
        Files.createDirectories(regionPath);
        Path source = regionPath.resolve("r.0.0.mca");
        Files.write(source, new byte[] {1, 2, 3, 4});

        TimeMachineSettings settings = settings();
        Files.createDirectories(settings.storageRoot());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            List<String> messages = new ArrayList<>();
            BackupFilePipeline first = new BackupFilePipeline(
                    settings,
                    executor,
                    new ProgressTracker(),
                    (key, arguments) -> messages.add(key));
            BackupTargetWorld target = new BackupTargetWorld(
                    "world",
                    "minecraft:overworld",
                    "00000000-0000-0000-0000-000000000001",
                    "worlds/00000000-0000-0000-0000-000000000001",
                    worldPath,
                    new SnapshotStore.SnapshotWorld(
                            "00000000-0000-0000-0000-000000000001",
                            "minecraft:overworld",
                            "world",
                            "worlds/00000000-0000-0000-0000-000000000001",
                            worldPath.toString()));

            Path firstFiles = temporaryDirectory.resolve("first-files");
            Files.createDirectories(firstFiles);
            BackupFilePipeline.ScanResult full = first.scanAndCopy(
                    List.of(target), SnapshotKind.FULL, true, firstFiles, java.util.Map.of());

            assertEquals(1, full.changedEntries().size());
            assertFalse(full.changedEntries().getFirst().sha256().isBlank());
            assertTrue(Files.isRegularFile(firstFiles.resolve(full.changedEntries().getFirst().relativePath())));
            assertTrue(messages.contains("backup.scanning"));
            assertTrue(messages.contains("backup.copying"));

            Path secondFiles = temporaryDirectory.resolve("second-files");
            Files.createDirectories(secondFiles);
            BackupFilePipeline second = new BackupFilePipeline(
                    settings,
                    executor,
                    new ProgressTracker(),
                    (key, arguments) -> messages.add(key));
            BackupFilePipeline.ScanResult incremental = second.scanAndCopy(
                    List.of(target),
                    SnapshotKind.INCREMENTAL,
                    false,
                    secondFiles,
                    full.currentState());

            assertTrue(incremental.changedEntries().isEmpty());
            assertEquals(
                    full.changedEntries().getFirst().sha256(),
                    incremental.currentState().values().iterator().next().sha256());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsAnUnavailableReserveBeforeScanningOrHashing() throws Exception {
        TimeMachineSettings settings = settings(Long.MAX_VALUE);
        Files.createDirectories(settings.storageRoot());

        assertThrows(java.io.IOException.class, () -> BackupFilePipeline.ensureMinimumFreeSpace(settings));
    }

    private TimeMachineSettings settings() {
        return settings(0L);
    }

    private TimeMachineSettings settings(long minimumFreeSpaceBytes) {
        return new TimeMachineSettings(
                LanguageMode.AUTO,
                temporaryDirectory.resolve("backups"),
                List.of(),
                new TimeMachineSettings.DatabaseSettings(
                        false,
                        temporaryDirectory.resolve("metadata.db"),
                        "tm_"),
                List.of(),
                Set.of(BackupScope.REGION),
                true,
                true,
                ChangeDetectionMode.FAST,
                2,
                minimumFreeSpaceBytes,
                false,
                0,
                List.of(),
                List.of(),
                List.of(),
                ZoneId.of("UTC"),
                false,
                new TimeMachineSettings.FullBackupScheduleSettings(false, List.of(), List.of(), false),
                new TimeMachineSettings.RetentionSettings(false, 8, 30, 2, List.of()));
    }
}
