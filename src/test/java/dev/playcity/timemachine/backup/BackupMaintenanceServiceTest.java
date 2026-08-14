package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupMaintenanceServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reconcileBlocksIncrementalBackupsWhenTheActiveChainBreaks() throws Exception {
        Path storage = temporaryDirectory.resolve("storage");
        Path full = writeSnapshot(storage.resolve("snapshots"), "2026/full", SnapshotKind.FULL, "", "2026/full");
        writeSnapshot(storage.resolve("snapshots"), "2026/inc", SnapshotKind.INCREMENTAL, "2026/full", "2026/full");
        SnapshotStore store = new SnapshotStore(storage);
        CurrentIndexStore.IndexState state = CurrentIndexStore.IndexState.committed(
                Map.of(),
                "2026/inc",
                "2026/full");
        BackupRuntimeContext context = context(store, state, store.inspectActiveChain(state));
        try {
            deleteSnapshot(full);

            BackupMaintenanceService.ChainRefresh refresh = service().refreshActiveChain(context);

            assertTrue(refresh.newlyBroken());
            assertTrue(refresh.health().broken());
            assertTrue(context.indexState.get().baselineRequired());
        } finally {
            context.close(true);
        }
    }

    @Test
    void reconcileResumesTheRecordedChainWhenItsArchivedBaseReturns() throws Exception {
        Path storage = temporaryDirectory.resolve("storage");
        Path archive = temporaryDirectory.resolve("archive");
        writeSnapshot(storage.resolve("snapshots"), "2026/inc", SnapshotKind.INCREMENTAL, "2026/full", "2026/full");
        SnapshotStore store = new SnapshotStore(storage, List.of(archive));
        CurrentIndexStore.IndexState recorded = CurrentIndexStore.IndexState.committed(
                Map.of(),
                "2026/inc",
                "2026/full");
        SnapshotChainHealth broken = store.inspectActiveChain(recorded);
        BackupRuntimeContext context = context(store, recorded.requiringNewBaseline(), broken);
        try {
            writeSnapshot(archive, "2026/full", SnapshotKind.FULL, "", "2026/full");

            BackupMaintenanceService.ChainRefresh refresh = service().refreshActiveChain(context);

            assertTrue(refresh.restored());
            assertTrue(refresh.health().healthy());
            assertFalse(context.indexState.get().baselineRequired());
        } finally {
            context.close(true);
        }
    }

    private BackupMaintenanceService service() {
        return new BackupMaintenanceService(Logger.getAnonymousLogger(), new ProgressTracker());
    }

    private BackupRuntimeContext context(
            SnapshotStore store,
            CurrentIndexStore.IndexState state,
            SnapshotChainHealth health) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        return new BackupRuntimeContext(
                null,
                null,
                store,
                null,
                executor,
                state,
                health);
    }

    private Path writeSnapshot(
            Path root,
            String snapshotId,
            SnapshotKind kind,
            String parentId,
            String baseId) throws Exception {
        Path directory = root.resolve(Path.of(snapshotId));
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("snapshot.properties"),
                "format.version=2\n"
                        + "snapshot.id=" + snapshotId + "\n"
                        + "snapshot.kind=" + kind.name() + "\n"
                        + "base.snapshot.id=" + baseId + "\n"
                        + "parent.snapshot.id=" + parentId + "\n"
                        + "created.at=2026-01-01T00:00:00Z\n",
                StandardCharsets.UTF_8);
        for (String required : List.of(
                "worlds.tsv",
                "entries.tsv",
                "deletions.tsv",
                "checksums.sha256",
                "restore-notes.txt")) {
            Files.writeString(directory.resolve(required), "test\n", StandardCharsets.UTF_8);
        }
        return directory;
    }

    private static void deleteSnapshot(Path snapshotDirectory) throws Exception {
        try (var paths = Files.walk(snapshotDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
