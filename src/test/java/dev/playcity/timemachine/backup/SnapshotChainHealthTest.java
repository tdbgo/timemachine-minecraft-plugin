package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotChainHealthTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsACompleteChainSplitBetweenPrimaryAndConfiguredArchiveStorage() throws Exception {
        Path storage = temporaryDirectory.resolve("storage");
        Path archive = temporaryDirectory.resolve("archive");
        writeSnapshot(archive, "2026/full", SnapshotKind.FULL, "", "2026/full");
        writeSnapshot(storage.resolve("snapshots"), "2026/inc", SnapshotKind.INCREMENTAL, "2026/full", "2026/full");
        SnapshotStore store = new SnapshotStore(storage, List.of(archive));
        CurrentIndexStore.IndexState state = CurrentIndexStore.IndexState.committed(
                Map.of(),
                "2026/inc",
                "2026/full");

        SnapshotChainHealth health = store.inspectActiveChain(state);

        assertTrue(health.healthy());
        assertEquals(2, health.snapshotsChecked());
        assertEquals("2026/full", health.baseSnapshotId());
    }

    @Test
    void marksTheChainBrokenWhenAnActiveParentWasDeleted() throws Exception {
        Path storage = temporaryDirectory.resolve("storage");
        writeSnapshot(storage.resolve("snapshots"), "2026/inc", SnapshotKind.INCREMENTAL, "2026/full", "2026/full");
        SnapshotStore store = new SnapshotStore(storage);
        CurrentIndexStore.IndexState state = CurrentIndexStore.IndexState.committed(
                Map.of(),
                "2026/inc",
                "2026/full");

        SnapshotChainHealth health = store.inspectActiveChain(state);

        assertTrue(health.broken());
        assertEquals(1, health.snapshotsChecked());
        assertEquals("verify.issue.parent_not_found", health.issue().key());
        assertEquals(List.of("2026/full"), health.issue().arguments());
    }

    @Test
    void marksTheChainBrokenWhenARequiredManifestFileWasDeleted() throws Exception {
        Path storage = temporaryDirectory.resolve("storage");
        Path full = writeSnapshot(storage.resolve("snapshots"), "2026/full", SnapshotKind.FULL, "", "2026/full");
        Files.delete(full.resolve("entries.tsv"));
        SnapshotStore store = new SnapshotStore(storage);
        CurrentIndexStore.IndexState state = CurrentIndexStore.IndexState.committed(
                Map.of(),
                "2026/full",
                "2026/full");

        SnapshotChainHealth health = store.inspectActiveChain(state);

        assertTrue(health.broken());
        assertEquals("verify.issue.missing_file", health.issue().key());
        assertEquals("entries.tsv", health.issue().arguments().getFirst());
    }

    @Test
    void canRevalidateARecordedChainAfterTheMissingSnapshotReturns() throws Exception {
        Path storage = temporaryDirectory.resolve("storage");
        writeSnapshot(storage.resolve("snapshots"), "2026/full", SnapshotKind.FULL, "", "2026/full");
        CurrentIndexStore.IndexState state = CurrentIndexStore.IndexState.committed(
                        Map.of(),
                        "2026/full",
                        "2026/full")
                .requiringNewBaseline();
        SnapshotStore store = new SnapshotStore(storage);

        assertFalse(store.inspectActiveChain(state).healthy());
        assertTrue(store.inspectRecordedChain(state).healthy());
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
}
