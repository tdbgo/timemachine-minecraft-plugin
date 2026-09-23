package dev.playcity.timemachine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.playcity.timemachine.backup.SnapshotStore;
import dev.playcity.timemachine.config.TimeMachineSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotMetadataIndexTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesTextTimestampsToEpochAndSortsChronologically() throws Exception {
        Path database = temporaryDirectory.resolve("metadata.db");
        createVersionOneDatabase(database);
        Path snapshots = temporaryDirectory.resolve("snapshots");
        writeSnapshotMetadata(snapshots, "earlier", "2026-01-01T00:00:00Z", "INCREMENTAL");
        writeSnapshotMetadata(snapshots, "later", "2026-01-01T00:00:00.100Z", "INCREMENTAL");
        SnapshotMetadataIndex index = new SnapshotMetadataIndex(
                Logger.getAnonymousLogger(),
                new TimeMachineSettings.DatabaseSettings(true, database, "tm_"),
                snapshots,
                List.of());

        index.initialize();
        List<SnapshotStore.SnapshotHistoryEntry> history = index.loadHistory(2);

        assertEquals(List.of("later", "earlier"), history.stream()
                .map(SnapshotStore.SnapshotHistoryEntry::snapshotId)
                .toList());
        assertEquals(Instant.parse("2026-01-01T00:00:00.100Z"), index.latestSnapshotTime().orElseThrow());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT meta_value FROM tm_meta WHERE meta_key = 'schema_version'")) {
            assertEquals("2", result.getString(1));
        }
    }

    @Test
    void refusesToDowngradeANewerMetadataSchema() throws Exception {
        Path database = temporaryDirectory.resolve("future.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tm_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");
            statement.execute("INSERT INTO tm_meta VALUES ('schema_version', '999')");
        }
        SnapshotMetadataIndex index = new SnapshotMetadataIndex(
                Logger.getAnonymousLogger(),
                new TimeMachineSettings.DatabaseSettings(true, database, "tm_"),
                temporaryDirectory.resolve("snapshots"),
                List.of());

        assertThrows(java.sql.SQLException.class, index::initialize);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT meta_value FROM tm_meta WHERE meta_key = 'schema_version'")) {
            assertEquals("999", result.getString(1));
        }
    }

    @Test
    void skipsOneMalformedHistoryRowWithoutHidingValidRows() throws Exception {
        Path database = temporaryDirectory.resolve("malformed-row.db");
        createVersionOneDatabase(database);
        SnapshotMetadataIndex index = new SnapshotMetadataIndex(
                Logger.getAnonymousLogger(),
                new TimeMachineSettings.DatabaseSettings(true, database, "tm_"),
                temporaryDirectory.resolve("snapshots"),
                List.of());
        index.initialize();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("UPDATE tm_snapshot SET created_at = 'not-an-instant', created_at_epoch = 9999999999999 "
                    + "WHERE snapshot_id = 'later'");
        }

        List<SnapshotStore.SnapshotHistoryEntry> history = index.loadHistory(2);

        assertEquals(List.of("earlier"), history.stream()
                .map(SnapshotStore.SnapshotHistoryEntry::snapshotId)
                .toList());
    }

    @Test
    void reconcileTracksLocalArchivedAndDeletedSnapshotsWithoutPurgingHistory() throws Exception {
        Path database = temporaryDirectory.resolve("inventory.db");
        Path primary = temporaryDirectory.resolve("primary");
        Path archive = temporaryDirectory.resolve("archive");
        Path localSnapshot = writeSnapshotMetadata(primary, "2026/full");
        SnapshotMetadataIndex index = new SnapshotMetadataIndex(
                Logger.getAnonymousLogger(),
                new TimeMachineSettings.DatabaseSettings(true, database, "tm_"),
                primary,
                List.of(archive));
        index.initialize();

        ReconcileReport local = index.reconcile();
        assertEquals(1, local.localSnapshots());
        assertEquals(1, local.discoveredSnapshots());
        assertEquals("LOCAL", index.loadHistory(1).getFirst().status());

        Path archivedSnapshot = archive.resolve("2026/full");
        Files.createDirectories(archivedSnapshot.getParent());
        Files.move(localSnapshot, archivedSnapshot);
        ReconcileReport archived = index.reconcile();
        SnapshotStore.SnapshotHistoryEntry archivedEntry = index.loadHistory(1).getFirst();
        assertEquals(1, archived.archivedSnapshots());
        assertEquals(0, archived.newlyMissingSnapshots());
        assertEquals(0, archived.missingSnapshots());
        assertEquals("ARCHIVED", archivedEntry.status());
        assertEquals(archivedSnapshot.toAbsolutePath().normalize(), archivedEntry.snapshotPath());

        Files.delete(archivedSnapshot.resolve("snapshot.properties"));
        Files.delete(archivedSnapshot);
        ReconcileReport missing = index.reconcile();
        assertEquals(1, missing.newlyMissingSnapshots());
        assertEquals(1, missing.missingSnapshots());
        assertEquals("MISSING", index.loadHistory(1).getFirst().status());

        ReconcileReport stillMissing = index.reconcile();
        assertEquals(0, stillMissing.newlyMissingSnapshots());
        assertEquals(1, stillMissing.missingSnapshots());
    }

    @Test
    void latestTimeReconcilesDiskBeforeTrustingPersistedRows() throws Exception {
        Path database = temporaryDirectory.resolve("startup-inventory.db");
        Path primary = temporaryDirectory.resolve("primary");
        Path snapshot = writeSnapshotMetadata(primary, "2026/full");
        SnapshotMetadataIndex firstRuntime = new SnapshotMetadataIndex(
                Logger.getAnonymousLogger(),
                new TimeMachineSettings.DatabaseSettings(true, database, "tm_"),
                primary,
                List.of());
        firstRuntime.initialize();

        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), firstRuntime.latestSnapshotTime().orElseThrow());

        Files.delete(snapshot.resolve("snapshot.properties"));
        Files.delete(snapshot);
        SnapshotMetadataIndex restartedRuntime = new SnapshotMetadataIndex(
                Logger.getAnonymousLogger(),
                new TimeMachineSettings.DatabaseSettings(true, database, "tm_"),
                primary,
                List.of());
        restartedRuntime.initialize();

        assertEquals(Optional.empty(), restartedRuntime.latestSnapshotTime());
    }

    private void createVersionOneDatabase(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tm_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");
            statement.execute("INSERT INTO tm_meta VALUES ('schema_version', '1')");
            statement.execute("""
                    CREATE TABLE tm_snapshot (
                        snapshot_id TEXT PRIMARY KEY,
                        created_at TEXT NOT NULL,
                        trigger TEXT NOT NULL,
                        snapshot_kind TEXT NOT NULL,
                        status TEXT NOT NULL,
                        storage_name TEXT NOT NULL,
                        relative_path TEXT NOT NULL,
                        resolved_path TEXT NOT NULL,
                        changed_files INTEGER NOT NULL,
                        changed_region_sets INTEGER NOT NULL,
                        deleted_files INTEGER NOT NULL,
                        message TEXT NOT NULL,
                        last_seen_at TEXT NOT NULL
                    )
                    """);
            insertSnapshot(statement, "earlier", "2026-01-01T00:00:00Z");
            insertSnapshot(statement, "later", "2026-01-01T00:00:00.100Z");
        }
    }

    private void insertSnapshot(Statement statement, String id, String createdAt) throws Exception {
        statement.execute("""
                INSERT INTO tm_snapshot VALUES (
                    '%s', '%s', 'test', 'INCREMENTAL', 'LOCAL', 'primary',
                    '2026/%s', '%s', 1, 1, 0, '', '%s'
                )
                """.formatted(
                id,
                createdAt,
                id,
                temporaryDirectory.resolve(id).toString().replace("'", "''"),
                createdAt));
    }

    private Path writeSnapshotMetadata(Path root, String snapshotId) throws Exception {
        return writeSnapshotMetadata(root, snapshotId, "2026-01-01T00:00:00Z", "FULL");
    }

    private Path writeSnapshotMetadata(Path root, String snapshotId, String createdAt, String kind) throws Exception {
        Path directory = root.resolve(Path.of(snapshotId));
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("snapshot.properties"),
                "snapshot.id=" + snapshotId + "\n"
                        + "created.at=" + createdAt + "\n"
                        + "trigger=test\n"
                        + "snapshot.kind=" + kind + "\n"
                        + "changed.files=1\n"
                        + "changed.regionSets=1\n"
                        + "deleted.files=0\n",
                StandardCharsets.UTF_8);
        return directory;
    }
}
