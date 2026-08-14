package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentIndexStoreTest {
    private static final String WORLD_UUID = "00000000-0000-0000-0000-000000000001";

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsVersionTwoAtomically() throws Exception {
        Path indexPath = temporaryDirectory.resolve("state/current-index.tsv");
        CurrentIndexStore store = new CurrentIndexStore(indexPath);
        TrackedFileMetadata file = file("worlds/" + WORLD_UUID + "/region/r.0.0.mca", BackupScope.REGION);
        store.save(CurrentIndexStore.IndexState.committed(Map.of(file.relativePath(), file), "2026/s1", "2026/s0"));

        CurrentIndexStore.IndexState loaded = store.load();

        assertFalse(loaded.baselineRequired());
        assertFalse(loaded.legacyFormat());
        assertEquals("2026/s1", loaded.lastSnapshotId());
        assertEquals("2026/s0", loaded.baseSnapshotId());
        assertEquals(file, loaded.entries().get(file.relativePath()));
    }

    @Test
    void fallsBackToPreviousAtomicCopyWhenPrimaryIsCorrupt() throws Exception {
        Path indexPath = temporaryDirectory.resolve("state/current-index.tsv");
        CurrentIndexStore store = new CurrentIndexStore(indexPath);
        TrackedFileMetadata first = file("worlds/" + WORLD_UUID + "/region/r.0.0.mca", BackupScope.REGION);
        TrackedFileMetadata second = file("worlds/" + WORLD_UUID + "/poi/r.0.0.mca", BackupScope.POI);
        store.save(CurrentIndexStore.IndexState.committed(Map.of(first.relativePath(), first), "2026/s1", "2026/s1"));
        store.save(CurrentIndexStore.IndexState.committed(Map.of(second.relativePath(), second), "2026/s2", "2026/s1"));
        Files.writeString(indexPath, "corrupt", StandardCharsets.UTF_8);

        CurrentIndexStore.IndexState recovered = store.load();

        assertTrue(recovered.recoveredFromBackup());
        assertEquals("2026/s1", recovered.lastSnapshotId());
        assertEquals(first, recovered.entries().get(first.relativePath()));
    }

    @Test
    void legacyIndexRequiresANewFullBaseline() throws Exception {
        Path indexPath = temporaryDirectory.resolve("state/current-index.tsv");
        Files.createDirectories(indexPath.getParent());
        Files.writeString(indexPath, "# Timemachine current index v1\n", StandardCharsets.UTF_8);

        CurrentIndexStore.IndexState loaded = new CurrentIndexStore(indexPath).load();

        assertTrue(loaded.baselineRequired());
        assertTrue(loaded.legacyFormat());
        assertTrue(loaded.entries().isEmpty());
    }

    @Test
    void rejectsInvalidEntryIdentityBeforePublishingTheIndex() {
        Path indexPath = temporaryDirectory.resolve("state/current-index.tsv");
        CurrentIndexStore store = new CurrentIndexStore(indexPath);
        TrackedFileMetadata invalid = file("worlds/" + WORLD_UUID + "/region/r.1.0.mca", BackupScope.REGION);

        assertThrows(
                java.io.IOException.class,
                () -> store.save(CurrentIndexStore.IndexState.committed(
                        Map.of(invalid.relativePath(), invalid),
                        "2026/s1",
                        "2026/s1")));
        assertFalse(Files.exists(indexPath));
    }

    @Test
    void preservesRecordedChainMetadataWhileTemporarilyRequiringANewBaseline() {
        TrackedFileMetadata tracked = file(
                "worlds/" + WORLD_UUID + "/region/r.0.0.mca",
                BackupScope.REGION);
        CurrentIndexStore.IndexState committed = CurrentIndexStore.IndexState.committed(
                Map.of(tracked.relativePath(), tracked),
                "2026/inc",
                "2026/full");

        CurrentIndexStore.IndexState blocked = committed.requiringNewBaseline();
        CurrentIndexStore.IndexState restored = blocked.withValidatedBaseline();

        assertTrue(blocked.baselineRequired());
        assertEquals("2026/inc", blocked.lastSnapshotId());
        assertEquals("2026/full", blocked.baseSnapshotId());
        assertEquals(tracked, blocked.entries().get(tracked.relativePath()));
        assertFalse(restored.baselineRequired());
        assertEquals(blocked.entries(), restored.entries());
    }

    private TrackedFileMetadata file(String relativePath, BackupScope scope) {
        return new TrackedFileMetadata(
                relativePath,
                "world",
                "minecraft:overworld",
                WORLD_UUID,
                "worlds/" + WORLD_UUID,
                scope,
                0,
                0,
                100L,
                200L,
                "a".repeat(64));
    }
}
