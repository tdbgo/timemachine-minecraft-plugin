package dev.playcity.timemachine.db;

import dev.playcity.timemachine.backup.SnapshotStore;
import dev.playcity.timemachine.backup.SnapshotKind;
import dev.playcity.timemachine.config.TimeMachineSettings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class SnapshotMetadataIndex implements AutoCloseable {
    private static final String SNAPSHOT_PROPERTIES = "snapshot.properties";
    private static final int SCHEMA_VERSION = 2;
    private static final int MAX_SNAPSHOT_SCAN_DEPTH = 4;

    private final Logger logger;
    private final Path sqliteFile;
    private final Path primarySnapshotsRoot;
    private final List<Path> archiveRoots;
    private final String metaTable;
    private final String snapshotTable;
    private volatile boolean inventoryReconciled;

    public SnapshotMetadataIndex(
            Logger logger,
            TimeMachineSettings.DatabaseSettings settings,
            Path primarySnapshotsRoot,
            List<Path> archiveRoots) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.sqliteFile = settings.sqliteFile().toAbsolutePath().normalize();
        this.primarySnapshotsRoot = primarySnapshotsRoot.toAbsolutePath().normalize();
        this.archiveRoots = archiveRoots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        this.metaTable = settings.tablePrefix() + "meta";
        this.snapshotTable = settings.tablePrefix() + "snapshot";
    }

    public void initialize() throws IOException, SQLException {
        Files.createDirectories(sqliteFile.getParent());
        try (Connection connection = openConnection()) {
            createSchema(connection);
        }
    }

    public void recordSnapshot(
            String snapshotId,
            Instant createdAt,
            String trigger,
            SnapshotKind snapshotKind,
            Path snapshotPath,
            Path snapshotRelativePath,
            int changedFiles,
            int changedRegionSets,
            int deletedFiles,
            String message) throws SQLException {
        IndexedSnapshot snapshot = new IndexedSnapshot(
                snapshotId,
                createdAt,
                safe(trigger),
                snapshotKind.name(),
                SnapshotStatus.LOCAL,
                "primary",
                normalizePath(snapshotRelativePath),
                snapshotPath.toAbsolutePath().normalize().toString(),
                changedFiles,
                changedRegionSets,
                deletedFiles,
                safe(message),
                Instant.now());
        try (Connection connection = openConnection()) {
            upsertSnapshot(connection, snapshot);
        }
    }

    public List<SnapshotStore.SnapshotHistoryEntry> loadHistory(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        String sql = """
                SELECT snapshot_id, created_at, trigger, changed_files, changed_region_sets,
                       deleted_files, snapshot_kind, status, storage_name, resolved_path
                FROM %s
                ORDER BY COALESCE(created_at_epoch, 0) DESC
                LIMIT ?
                """.formatted(snapshotTable);
        List<SnapshotStore.SnapshotHistoryEntry> entries = new ArrayList<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    try {
                        Path resolvedPath = null;
                        String rawPath = resultSet.getString("resolved_path");
                        if (rawPath != null && !rawPath.isBlank()) {
                            resolvedPath = Path.of(rawPath);
                        }
                        entries.add(new SnapshotStore.SnapshotHistoryEntry(
                                resultSet.getString("snapshot_id"),
                                Instant.parse(resultSet.getString("created_at")),
                                resultSet.getString("trigger"),
                                resultSet.getInt("changed_files"),
                                resultSet.getInt("changed_region_sets"),
                                resultSet.getInt("deleted_files"),
                                "FULL".equalsIgnoreCase(resultSet.getString("snapshot_kind")),
                                resultSet.getString("status"),
                                resultSet.getString("storage_name"),
                                resolvedPath));
                    } catch (Exception ex) {
                        logger.warning("Ignoring invalid snapshot metadata row '"
                                + resultSet.getString("snapshot_id") + "': " + ex.getMessage());
                    }
                }
            }
        } catch (Exception ex) {
            logger.warning("Failed to load snapshot metadata history: " + ex.getMessage());
            return List.of();
        }
        return List.copyOf(entries);
    }

    public Optional<Instant> latestSnapshotTime() {
        if (!ensureInventoryReconciled()) {
            return Optional.empty();
        }
        return latestSnapshotTime(false);
    }

    public Optional<Instant> latestFullSnapshotTime() {
        if (!ensureInventoryReconciled()) {
            return Optional.empty();
        }
        return latestSnapshotTime(true);
    }

    public synchronized ReconcileReport reconcile() throws IOException, SQLException {
        LinkedHashMap<String, IndexedSnapshot> discovered = new LinkedHashMap<>();
        scanRoot(primarySnapshotsRoot, "primary", SnapshotStatus.LOCAL, discovered);
        for (int index = 0; index < archiveRoots.size(); index++) {
            scanRoot(archiveRoots.get(index), "archive-" + (index + 1), SnapshotStatus.ARCHIVED, discovered);
        }

        int localSnapshots = 0;
        int archivedSnapshots = 0;
        for (IndexedSnapshot snapshot : discovered.values()) {
            if (snapshot.status() == SnapshotStatus.LOCAL) {
                localSnapshots++;
            } else if (snapshot.status() == SnapshotStatus.ARCHIVED) {
                archivedSnapshots++;
            }
        }

        int discoveredSnapshots = 0;
        int newlyMissingSnapshots = 0;
        int missingSnapshots = 0;
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Set<String> existingSnapshotIds = loadSnapshotIds(connection);
                for (IndexedSnapshot snapshot : discovered.values()) {
                    if (!existingSnapshotIds.contains(snapshot.snapshotId())) {
                        discoveredSnapshots++;
                    }
                    upsertSnapshot(connection, snapshot);
                }
                newlyMissingSnapshots = markMissingSnapshots(connection, existingSnapshotIds, discovered.keySet());
                missingSnapshots = countSnapshotsByStatus(connection, SnapshotStatus.MISSING);
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                }
                throw ex;
            }
        }

        ReconcileReport report = new ReconcileReport(
                discovered.size(),
                localSnapshots,
                archivedSnapshots,
                discoveredSnapshots,
                newlyMissingSnapshots,
                missingSnapshots);
        inventoryReconciled = true;
        return report;
    }

    @Override
    public void close() {
    }

    private Optional<Instant> latestSnapshotTime(boolean fullOnly) {
        StringBuilder sql = new StringBuilder("SELECT created_at FROM " + snapshotTable + " WHERE status <> ?");
        if (fullOnly) {
            sql.append(" AND snapshot_kind = ?");
        }
        sql.append(" ORDER BY COALESCE(created_at_epoch, 0) DESC LIMIT 1");

        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, SnapshotStatus.MISSING.name());
            if (fullOnly) {
                statement.setString(2, "FULL");
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(Instant.parse(resultSet.getString("created_at")));
            }
        } catch (Exception ex) {
            logger.warning("Failed to read latest snapshot metadata: " + ex.getMessage());
            return Optional.empty();
        }
    }

    private synchronized boolean ensureInventoryReconciled() {
        if (inventoryReconciled) {
            return true;
        }
        try {
            reconcile();
            return true;
        } catch (IOException | SQLException ex) {
            logger.warning("Failed to reconcile snapshot metadata before reading the schedule baseline: "
                    + ex.getMessage());
            return false;
        }
    }

    private void scanRoot(
            Path root,
            String storageName,
            SnapshotStatus status,
            LinkedHashMap<String, IndexedSnapshot> discovered) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.find(
                root,
                MAX_SNAPSHOT_SCAN_DEPTH,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().equalsIgnoreCase(SNAPSHOT_PROPERTIES))) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path propertiesPath = iterator.next();
                IndexedSnapshot snapshot = readSnapshot(propertiesPath, root, storageName, status);
                if (snapshot == null) {
                    continue;
                }
                IndexedSnapshot current = discovered.get(snapshot.snapshotId());
                if (current == null || prefers(snapshot, current)) {
                    discovered.put(snapshot.snapshotId(), snapshot);
                }
            }
        }
    }

    private IndexedSnapshot readSnapshot(Path propertiesPath, Path root, String storageName, SnapshotStatus status) {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(propertiesPath)) {
            properties.load(inputStream);
            Path snapshotPath = propertiesPath.getParent().toAbsolutePath().normalize();
            Path relativePath;
            try {
                relativePath = root.toAbsolutePath().normalize().relativize(snapshotPath);
            } catch (IllegalArgumentException ignored) {
                relativePath = snapshotPath.getFileName();
            }
            String snapshotId = properties.getProperty("snapshot.id", normalizePath(relativePath));
            Instant createdAt = Instant.parse(properties.getProperty("created.at"));
            String kind = properties.getProperty("snapshot.kind");
            if (kind == null || kind.isBlank()) {
                kind = Boolean.parseBoolean(properties.getProperty("full.backup", "false"))
                        ? "FULL"
                        : "INCREMENTAL";
            }
            return new IndexedSnapshot(
                    snapshotId,
                    createdAt,
                    safe(properties.getProperty("trigger")),
                    kind,
                    status,
                    storageName,
                    normalizePath(relativePath),
                    snapshotPath.toString(),
                    Integer.parseInt(properties.getProperty("changed.files", "0")),
                    Integer.parseInt(properties.getProperty("changed.regionSets", "0")),
                    Integer.parseInt(properties.getProperty("deleted.files", "0")),
                    safe(properties.getProperty("message")),
                    Instant.now());
        } catch (Exception ex) {
            logger.warning("Ignoring unreadable snapshot metadata at " + propertiesPath + ": " + ex.getMessage());
            return null;
        }
    }

    private boolean prefers(IndexedSnapshot candidate, IndexedSnapshot current) {
        if (candidate.status() == SnapshotStatus.LOCAL && current.status() != SnapshotStatus.LOCAL) {
            return true;
        }
        return candidate.status() == current.status() && candidate.createdAt().isAfter(current.createdAt());
    }

    private Set<String> loadSnapshotIds(Connection connection) throws SQLException {
        Set<String> snapshotIds = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT snapshot_id FROM " + snapshotTable)) {
            while (resultSet.next()) {
                snapshotIds.add(resultSet.getString(1));
            }
        }
        return snapshotIds;
    }

    private int markMissingSnapshots(Connection connection, Set<String> existingSnapshotIds, Set<String> seenSnapshotIds)
            throws SQLException {
        String sql = "UPDATE " + snapshotTable
                + " SET status = ?, last_seen_at = ?, last_seen_at_epoch = ? WHERE snapshot_id = ? AND status <> ?";
        int count = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Instant seenAt = Instant.now();
            for (String snapshotId : existingSnapshotIds) {
                if (seenSnapshotIds.contains(snapshotId)) {
                    continue;
                }
                statement.setString(1, SnapshotStatus.MISSING.name());
                statement.setString(2, seenAt.toString());
                statement.setLong(3, seenAt.toEpochMilli());
                statement.setString(4, snapshotId);
                statement.setString(5, SnapshotStatus.MISSING.name());
                count += statement.executeUpdate();
            }
        }
        return count;
    }

    private int countSnapshotsByStatus(Connection connection, SnapshotStatus status) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + snapshotTable + " WHERE status = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private void upsertSnapshot(Connection connection, IndexedSnapshot snapshot) throws SQLException {
        String sql = """
                INSERT INTO %s (
                    snapshot_id, created_at, created_at_epoch, trigger, snapshot_kind, status, storage_name,
                    relative_path, resolved_path, changed_files, changed_region_sets,
                    deleted_files, message, last_seen_at, last_seen_at_epoch
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(snapshot_id) DO UPDATE SET
                    created_at = excluded.created_at,
                    created_at_epoch = excluded.created_at_epoch,
                    trigger = excluded.trigger,
                    snapshot_kind = excluded.snapshot_kind,
                    status = excluded.status,
                    storage_name = excluded.storage_name,
                    relative_path = excluded.relative_path,
                    resolved_path = excluded.resolved_path,
                    changed_files = excluded.changed_files,
                    changed_region_sets = excluded.changed_region_sets,
                    deleted_files = excluded.deleted_files,
                    message = excluded.message,
                    last_seen_at = excluded.last_seen_at,
                    last_seen_at_epoch = excluded.last_seen_at_epoch
                """.formatted(snapshotTable);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, snapshot.snapshotId());
            statement.setString(2, snapshot.createdAt().toString());
            statement.setLong(3, snapshot.createdAt().toEpochMilli());
            statement.setString(4, snapshot.trigger());
            statement.setString(5, snapshot.snapshotKind());
            statement.setString(6, snapshot.status().name());
            statement.setString(7, snapshot.storageName());
            statement.setString(8, snapshot.relativePath());
            statement.setString(9, snapshot.resolvedPath());
            statement.setInt(10, snapshot.changedFiles());
            statement.setInt(11, snapshot.changedRegionSets());
            statement.setInt(12, snapshot.deletedFiles());
            statement.setString(13, snapshot.message());
            statement.setString(14, snapshot.lastSeenAt().toString());
            statement.setLong(15, snapshot.lastSeenAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private void createSchema(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS %s (
                            meta_key TEXT PRIMARY KEY,
                            meta_value TEXT NOT NULL
                        )
                        """.formatted(metaTable));
            }

            int currentVersion = readSchemaVersion(connection);
            if (currentVersion < 0 || currentVersion > SCHEMA_VERSION) {
                throw new SQLException("Unsupported metadata schema version " + currentVersion
                        + "; this plugin supports up to version " + SCHEMA_VERSION + ".");
            }

            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS %s (
                            snapshot_id TEXT PRIMARY KEY,
                            created_at TEXT NOT NULL,
                            created_at_epoch INTEGER,
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
                            last_seen_at TEXT NOT NULL,
                            last_seen_at_epoch INTEGER
                        )
                        """.formatted(snapshotTable));
            }
            ensureColumn(connection, snapshotTable, "created_at_epoch", "INTEGER");
            ensureColumn(connection, snapshotTable, "last_seen_at_epoch", "INTEGER");
            backfillEpochColumns(connection);

            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO %s (meta_key, meta_value)
                        VALUES ('schema_version', '%s')
                        ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value
                        """.formatted(metaTable, SCHEMA_VERSION));
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS %s_created_epoch_idx
                        ON %s (created_at_epoch DESC)
                        """.formatted(snapshotTable, snapshotTable));
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS %s_status_kind_epoch_idx
                        ON %s (status, snapshot_kind, created_at_epoch DESC)
                        """.formatted(snapshotTable, snapshotTable));
            }
            connection.commit();
        } catch (SQLException | RuntimeException ex) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                ex.addSuppressed(rollbackFailure);
            }
            throw ex;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private int readSchemaVersion(Connection connection) throws SQLException {
        String sql = "SELECT meta_value FROM " + metaTable + " WHERE meta_key = 'schema_version'";
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return 0;
            }
            String rawVersion = resultSet.getString(1);
            try {
                return Integer.parseInt(rawVersion);
            } catch (NumberFormatException ex) {
                throw new SQLException("Invalid metadata schema version: " + rawVersion, ex);
            }
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String definition) throws SQLException {
        boolean found = false;
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    private void backfillEpochColumns(Connection connection) throws SQLException {
        String selectSql = "SELECT snapshot_id, created_at, last_seen_at FROM " + snapshotTable
                + " WHERE created_at_epoch IS NULL OR last_seen_at_epoch IS NULL";
        String updateSql = "UPDATE " + snapshotTable
                + " SET created_at_epoch = ?, last_seen_at_epoch = ? WHERE snapshot_id = ?";
        try (Statement select = connection.createStatement();
                ResultSet rows = select.executeQuery(selectSql);
                PreparedStatement update = connection.prepareStatement(updateSql)) {
            while (rows.next()) {
                try {
                    update.setLong(1, Instant.parse(rows.getString("created_at")).toEpochMilli());
                    update.setLong(2, Instant.parse(rows.getString("last_seen_at")).toEpochMilli());
                    update.setString(3, rows.getString("snapshot_id"));
                    update.addBatch();
                } catch (Exception ex) {
                    logger.warning("Could not migrate snapshot time for " + rows.getString("snapshot_id") + ": "
                            + ex.getMessage());
                }
            }
            update.executeBatch();
        }
    }

    private String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record IndexedSnapshot(
            String snapshotId,
            Instant createdAt,
            String trigger,
            String snapshotKind,
            SnapshotStatus status,
            String storageName,
            String relativePath,
            String resolvedPath,
            int changedFiles,
            int changedRegionSets,
            int deletedFiles,
            String message,
            Instant lastSeenAt) {
    }
}
