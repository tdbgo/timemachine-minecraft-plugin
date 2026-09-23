package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotStoreTest {
    private static final String WORLD_UUID = "00000000-0000-0000-0000-000000000001";

    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesAFullAndIncrementalChainAndDetectsTampering() throws Exception {
        SnapshotStore store = new SnapshotStore(temporaryDirectory.resolve("backups"));
        SnapshotStore.SnapshotWorld world = new SnapshotStore.SnapshotWorld(
                WORLD_UUID,
                "minecraft:overworld",
                "world",
                "worlds/" + WORLD_UUID,
                temporaryDirectory.resolve("world").toString());
        BackupRequest request = new BackupRequest("test", true, null, "", "junit");

        Path fullStaging = store.createStagingDirectory("full-staging");
        TrackedFileMetadata fullFile = writeSnapshotFile(store, fullStaging, "full-data");
        store.commit(
                fullStaging,
                "2026/full",
                Path.of("2026", "full"),
                request,
                Instant.parse("2026-01-01T00:00:00Z"),
                SnapshotKind.FULL,
                "",
                "2026/full",
                List.of(world),
                Set.of(BackupScope.REGION),
                List.of(fullFile),
                List.of());

        Path incrementalStaging = store.createStagingDirectory("incremental-staging");
        TrackedFileMetadata incrementalFile = writeSnapshotFile(store, incrementalStaging, "new-data");
        store.commit(
                incrementalStaging,
                "2026/inc",
                Path.of("2026", "inc"),
                new BackupRequest("test", false, null, "", "junit"),
                Instant.parse("2026-01-02T00:00:00Z"),
                SnapshotKind.INCREMENTAL,
                "2026/full",
                "2026/full",
                List.of(world),
                Set.of(BackupScope.REGION),
                List.of(incrementalFile),
                List.of());

        SnapshotStore.VerificationResult valid = store.verifySnapshot("2026/inc");
        assertTrue(valid.valid(), () -> String.join("; ", valid.errors()));
        assertEquals(2, valid.snapshotsChecked());
        assertEquals(2, valid.filesChecked());
        assertEquals(
                world.sourcePath(),
                store.loadWorldSourcePaths("2026/inc").get(WORLD_UUID));
        var chainLocations = store.locateSnapshots(List.of("2026/full", "2026/inc", "2026/missing"));
        assertEquals(Set.of("2026/full", "2026/inc"), chainLocations.keySet());
        assertEquals(
                store.snapshotPath(Path.of("2026", "full")),
                chainLocations.get("2026/full"));

        Path checksumManifest = store.snapshotPath(Path.of("2026", "inc")).resolve("checksums.sha256");
        String originalManifest = Files.readString(checksumManifest, StandardCharsets.UTF_8);
        String tamperedManifest = (originalManifest.charAt(0) == '0' ? "1" : "0") + originalManifest.substring(1);
        Files.writeString(checksumManifest, tamperedManifest, StandardCharsets.UTF_8);

        SnapshotStore.VerificationResult manifestInvalid = store.verifySnapshot("2026/inc");
        assertFalse(manifestInvalid.valid());
        assertTrue(manifestInvalid.errors().stream()
                .anyMatch(error -> error.contains("Checksum manifest mismatch")));
        Files.writeString(checksumManifest, originalManifest, StandardCharsets.UTF_8);

        Path fullSnapshotFile = store.snapshotPath(Path.of("2026", "full"))
                .resolve("files/worlds/" + WORLD_UUID + "/region/r.0.0.mca");
        Files.writeString(fullSnapshotFile, "tampered", StandardCharsets.UTF_8);

        SnapshotStore.VerificationResult invalid = store.verifySnapshot("2026/inc");
        assertFalse(invalid.valid());
        assertTrue(invalid.errors().stream().anyMatch(error -> error.contains("mismatch")));
    }

    @Test
    void refusesToDiscardAnythingOutsideItsStagingRoot() throws Exception {
        SnapshotStore store = new SnapshotStore(temporaryDirectory.resolve("backups"));
        Path outsideFile = temporaryDirectory.resolve("keep.txt");
        Files.writeString(outsideFile, "keep", StandardCharsets.UTF_8);

        assertThrows(java.io.IOException.class, () -> store.discardStaging(outsideFile));
        assertTrue(Files.exists(outsideFile));
    }

    @Test
    void previewsAndCleansOnlyFailureMarkedStagingDirectories() throws Exception {
        SnapshotStore store = new SnapshotStore(temporaryDirectory.resolve("backups"));
        Path failed = store.createStagingDirectory("failed-copy");
        Files.writeString(store.filesDirectory(failed).resolve("partial.mca"), "partial", StandardCharsets.UTF_8);
        store.markFailed(failed, "Backup copy was interrupted.");

        Path active = store.createStagingDirectory("active-copy");
        Files.writeString(store.filesDirectory(active).resolve("copying.mca"), "active", StandardCharsets.UTF_8);

        SnapshotStore.FailedStagingPlan plan = store.planFailedStagingCleanup();
        assertEquals(1, plan.entries().size());
        assertEquals("failed-copy", plan.entries().getFirst().directoryName());
        assertEquals(2L, plan.files());
        assertFalse(plan.token().isBlank());

        SnapshotStore.FailedStagingCleanupResult result = store.cleanupFailedStaging(plan);
        assertEquals(1, result.directories());
        assertEquals(2L, result.files());
        assertFalse(Files.exists(failed));
        assertTrue(Files.isDirectory(active));
    }

    @Test
    void refusesCleanupWhenTheFailureInventoryChangedAfterPreview() throws Exception {
        SnapshotStore store = new SnapshotStore(temporaryDirectory.resolve("backups"));
        Path first = store.createStagingDirectory("first-failure");
        store.markFailed(first, "first");
        SnapshotStore.FailedStagingPlan plan = store.planFailedStagingCleanup();

        Path second = store.createStagingDirectory("second-failure");
        store.markFailed(second, "second");

        assertThrows(IOException.class, () -> store.cleanupFailedStaging(plan));
        assertTrue(Files.isDirectory(first));
        assertTrue(Files.isDirectory(second));
    }

    @Test
    void latestSnapshotIncludesSnapshotsMovedToAnArchiveRoot() throws Exception {
        Path storageRoot = temporaryDirectory.resolve("backups");
        Path archiveRoot = temporaryDirectory.resolve("archive");
        writeHistoryMetadata(
                storageRoot.resolve("snapshots/2026/local/snapshot.properties"),
                "2026/local",
                "2026-01-01T00:00:00Z",
                "INCREMENTAL");
        writeHistoryMetadata(
                archiveRoot.resolve("2026/archived/snapshot.properties"),
                "2026/archived",
                "2026-01-02T00:00:00Z",
                "FULL");
        SnapshotStore store = new SnapshotStore(storageRoot, List.of(archiveRoot));

        assertEquals("2026/archived", store.latestSnapshot().orElseThrow().snapshotId());
        assertEquals("2026/archived", store.latestSnapshot(true).orElseThrow().snapshotId());
    }

    @Test
    void rejectsTraversalAndBackslashPathsInSnapshotMetadata() throws Exception {
        SnapshotStore store = new SnapshotStore(temporaryDirectory.resolve("backups"));
        SnapshotStore.SnapshotWorld world = new SnapshotStore.SnapshotWorld(
                WORLD_UUID,
                "minecraft:overworld",
                "world",
                "worlds/" + WORLD_UUID,
                temporaryDirectory.resolve("world").toString());
        Path staging = store.createStagingDirectory("unsafe-path-staging");
        TrackedFileMetadata file = writeSnapshotFile(store, staging, "data");
        Path snapshot = store.commit(
                staging,
                "2026/path-test",
                Path.of("2026", "path-test"),
                new BackupRequest("test", true, null, "", "junit"),
                Instant.parse("2026-01-01T00:00:00Z"),
                SnapshotKind.FULL,
                "",
                "2026/path-test",
                List.of(world),
                Set.of(BackupScope.REGION),
                List.of(file),
                List.of());
        Path entries = snapshot.resolve("entries.tsv");
        String original = Files.readString(entries, StandardCharsets.UTF_8);

        Files.writeString(
                entries,
                original.replace(file.relativePath(), "../escape.mca"),
                StandardCharsets.UTF_8);
        SnapshotStore.VerificationResult traversal = store.verifySnapshot("2026/path-test");
        assertFalse(traversal.valid());
        assertTrue(traversal.errors().stream().anyMatch(error -> error.contains("escapes") || error.contains("identity")));

        Files.writeString(
                entries,
                original.replace(file.relativePath(), file.relativePath().replace('/', '\\')),
                StandardCharsets.UTF_8);
        SnapshotStore.VerificationResult backslash = store.verifySnapshot("2026/path-test");
        assertFalse(backslash.valid());
        assertTrue(backslash.errors().stream().anyMatch(error -> error.contains("escapes") || error.contains("identity")));
    }

    private TrackedFileMetadata writeSnapshotFile(SnapshotStore store, Path staging, String content) throws Exception {
        String relativePath = "worlds/" + WORLD_UUID + "/region/r.0.0.mca";
        Path file = store.filesDirectory(staging).resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return new TrackedFileMetadata(
                relativePath,
                "world",
                "minecraft:overworld",
                WORLD_UUID,
                "worlds/" + WORLD_UUID,
                BackupScope.REGION,
                0,
                0,
                Files.getLastModifiedTime(file).toMillis(),
                Files.size(file),
                FileHashes.sha256(file));
    }

    private void writeHistoryMetadata(Path propertiesPath, String snapshotId, String createdAt, String kind)
            throws IOException {
        Files.createDirectories(propertiesPath.getParent());
        Files.writeString(
                propertiesPath,
                "snapshot.id=" + snapshotId + "\n"
                        + "created.at=" + createdAt + "\n"
                        + "trigger=test\n"
                        + "snapshot.kind=" + kind + "\n"
                        + "changed.files=1\n"
                        + "changed.regionSets=1\n"
                        + "deleted.files=0\n",
                StandardCharsets.UTF_8);
    }
}
