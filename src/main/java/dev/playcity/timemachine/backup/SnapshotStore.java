package dev.playcity.timemachine.backup;

import dev.playcity.timemachine.i18n.LanguageMode;
import dev.playcity.timemachine.i18n.LocalizedMessage;
import dev.playcity.timemachine.i18n.MessageCatalog;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SnapshotStore {
    private static final MessageCatalog ENGLISH_MESSAGES = new MessageCatalog(LanguageMode.ENGLISH);
    static final String FORMAT_VERSION = "2";
    private static final String PROPERTIES_FILE = "snapshot.properties";
    private static final String ENTRIES_FILE = "entries.tsv";
    private static final String DELETIONS_FILE = "deletions.tsv";
    private static final String WORLDS_FILE = "worlds.tsv";
    private static final String CHECKSUMS_FILE = "checksums.sha256";
    private static final String NOTES_FILE = "restore-notes.txt";
    private static final String ENTRY_HEADER =
            "relativePath\tworldName\tworldKey\tworldUuid\tworldStoragePath\tscope\tregionX\tregionZ\tmtime\tsize\tsha256";
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final int MAX_SCAN_DEPTH = 4;

    private final Path snapshotsRoot;
    private final Path stagingRoot;
    private final List<Path> searchableRoots;
    private final SnapshotVerifier verifier;

    public SnapshotStore(Path storageRoot) {
        this(storageRoot, List.of());
    }

    public SnapshotStore(Path storageRoot, List<Path> archiveRoots) {
        this.snapshotsRoot = storageRoot.resolve("snapshots").toAbsolutePath().normalize();
        this.stagingRoot = storageRoot.resolve("staging").toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>();
        roots.add(snapshotsRoot);
        archiveRoots.stream().map(path -> path.toAbsolutePath().normalize()).forEach(roots::add);
        this.searchableRoots = List.copyOf(roots);
        this.verifier = new SnapshotVerifier(searchableRoots);
    }

    public Path createStagingDirectory(String stagingName) throws IOException {
        Files.createDirectories(stagingRoot);
        Path path = stagingRoot.resolve(stagingName).normalize();
        if (!path.getParent().equals(stagingRoot)) {
            throw new IOException("Invalid staging directory name: " + stagingName);
        }
        Files.createDirectory(path);
        Files.createDirectory(path.resolve("files"));
        return path;
    }

    public Path filesDirectory(Path root) {
        return root.resolve("files");
    }

    public Path commit(
            Path stagingDirectory,
            String snapshotId,
            Path snapshotRelativePath,
            BackupRequest request,
            Instant createdAt,
            SnapshotKind snapshotKind,
            String parentSnapshotId,
            String baseSnapshotId,
            List<SnapshotWorld> worlds,
            Set<BackupScope> scopes,
            List<TrackedFileMetadata> changedEntries,
            List<TrackedFileMetadata> deletedEntries) throws IOException {
        Path validatedStagingDirectory = requireDirectChild(
                stagingRoot,
                stagingDirectory,
                "staging directory");
        writeMetadataFiles(
                validatedStagingDirectory,
                snapshotId,
                request,
                createdAt,
                snapshotKind,
                parentSnapshotId,
                baseSnapshotId,
                worlds,
                scopes,
                changedEntries,
                deletedEntries);

        Path committedPath = snapshotsRoot.resolve(snapshotRelativePath).normalize();
        if (!committedPath.startsWith(snapshotsRoot)) {
            throw new IOException("Snapshot path escapes the snapshot root: " + snapshotRelativePath);
        }
        Files.createDirectories(committedPath.getParent());
        if (Files.exists(committedPath)) {
            throw new IOException("Snapshot already exists and will not be overwritten: " + committedPath);
        }
        try {
            Files.move(validatedStagingDirectory, committedPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(validatedStagingDirectory, committedPath);
        }
        forceDirectory(committedPath.getParent());
        return committedPath;
    }

    public void discardStaging(Path stagingDirectory) throws IOException {
        Path validated = requireDirectChild(stagingRoot, stagingDirectory, "staging directory");
        if (!Files.exists(validated, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(validated)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public void markFailed(Path stagingDirectory, String message) {
        try {
            Path validated = requireDirectChild(stagingRoot, stagingDirectory, "staging directory");
            if (!Files.exists(validated, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Files.writeString(
                    validated.resolve("failure.txt"),
                    safe(message) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException ignored) {
        }
    }

    public boolean quarantineCommitted(Path committedDirectory, String message) {
        try {
            Path validated = requireDescendant(snapshotsRoot, committedDirectory, "committed snapshot");
            if (!Files.isDirectory(validated, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            Files.createDirectories(stagingRoot);
            Path target = stagingRoot.resolve("failed-" + validated.getFileName() + "-" + System.nanoTime());
            try {
                Files.move(validated, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(validated, target);
            }
            markFailed(target, message);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    public List<SnapshotHistoryEntry> loadHistory(int limit) {
        if (limit <= 0 || !Files.isDirectory(snapshotsRoot)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.find(
                snapshotsRoot,
                3,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().equals(PROPERTIES_FILE))) {
            return stream
                    .map(this::readHistoryEntry)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(SnapshotHistoryEntry::createdAt).reversed())
                    .limit(limit)
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    public List<SnapshotHistoryEntry> loadSearchableHistory(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<String, SnapshotHistoryEntry> entriesById = new LinkedHashMap<>();
        for (int rootIndex = 0; rootIndex < searchableRoots.size(); rootIndex++) {
            Path root = searchableRoots.get(rootIndex);
            if (!Files.isDirectory(root)) {
                continue;
            }
            String status = rootIndex == 0 ? "LOCAL" : "ARCHIVED";
            String storageName = rootIndex == 0 ? "primary" : "archive-" + rootIndex;
            try (Stream<Path> stream = Files.find(
                    root,
                    MAX_SCAN_DEPTH,
                    (path, attributes) -> attributes.isRegularFile()
                            && path.getFileName().toString().equals(PROPERTIES_FILE))) {
                stream.map(path -> readHistoryEntry(path, status, storageName))
                        .flatMap(Optional::stream)
                        .forEach(entry -> entriesById.putIfAbsent(entry.snapshotId(), entry));
            } catch (IOException ignored) {
            }
        }
        return entriesById.values().stream()
                .sorted(Comparator.comparing(SnapshotHistoryEntry::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    public Path snapshotPath(Path snapshotRelativePath) {
        return snapshotsRoot.resolve(snapshotRelativePath).normalize();
    }

    public Optional<SnapshotHistoryEntry> latestSnapshot() {
        return findLatest(entry -> true);
    }

    public Optional<SnapshotHistoryEntry> latestSnapshot(boolean fullBackup) {
        return findLatest(entry -> entry.fullBackup() == fullBackup);
    }

    public boolean containsSnapshot(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            return false;
        }
        for (Path root : searchableRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            Path directPath = resolvePortableRelative(root, snapshotId);
            if (directPath != null && hasSnapshotId(directPath.resolve(PROPERTIES_FILE), snapshotId)) {
                return true;
            }
            try (Stream<Path> stream = Files.find(
                    root,
                    MAX_SCAN_DEPTH,
                    (path, attributes) -> attributes.isRegularFile()
                            && path.getFileName().toString().equals(PROPERTIES_FILE))) {
                var iterator = stream.iterator();
                while (iterator.hasNext()) {
                    if (hasSnapshotId(iterator.next(), snapshotId)) {
                        return true;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    public Optional<Path> locateSnapshot(String snapshotId) throws IOException {
        if (snapshotId == null || snapshotId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshotLocations().get(snapshotId));
    }

    public Map<String, Path> locateSnapshots(Iterable<String> snapshotIds) throws IOException {
        Map<String, Path> available = snapshotLocations();
        Map<String, Path> requested = new LinkedHashMap<>();
        for (String snapshotId : snapshotIds) {
            if (snapshotId == null || snapshotId.isBlank()) {
                continue;
            }
            Path location = available.get(snapshotId);
            if (location != null) {
                requested.put(snapshotId, location);
            }
        }
        return Map.copyOf(requested);
    }

    public Map<String, String> loadWorldSourcePaths(String snapshotId) throws IOException {
        Path snapshot = locateSnapshot(snapshotId)
                .orElseThrow(() -> new IOException("Snapshot was not found: " + snapshotId));
        List<LocalizedMessage> errors = new ArrayList<>();
        Map<String, SnapshotVerifier.WorldMapping> mappings = readWorldMappings(snapshot, errors);
        if (!errors.isEmpty()) {
            String details = errors.stream()
                    .map(issue -> ENGLISH_MESSAGES.system(issue.key(), issue.argumentsArray()))
                    .collect(Collectors.joining("; "));
            throw new IOException("Could not read snapshot world paths: " + details);
        }
        Map<String, String> sourcePaths = new LinkedHashMap<>();
        mappings.forEach((uuid, mapping) -> sourcePaths.put(uuid, mapping.sourcePath()));
        return Map.copyOf(sourcePaths);
    }

    public VerificationResult verifySnapshot(String snapshotId) {
        return verifier.verify(snapshotId);
    }

    public SnapshotChainHealth inspectActiveChain(CurrentIndexStore.IndexState state) {
        return verifier.inspectActiveChain(state);
    }

    public SnapshotChainHealth inspectRecordedChain(CurrentIndexStore.IndexState state) {
        return verifier.inspectRecordedChain(state);
    }

    private Optional<SnapshotHistoryEntry> readHistoryEntry(Path propertiesPath) {
        return readHistoryEntry(propertiesPath, "LOCAL", "primary");
    }

    private Optional<SnapshotHistoryEntry> readHistoryEntry(
            Path propertiesPath,
            String status,
            String storageName) {
        try {
            Properties properties = loadProperties(propertiesPath);
            Path snapshotPath = propertiesPath.getParent();
            String kind = properties.getProperty("snapshot.kind", "");
            boolean fullBackup = "FULL".equalsIgnoreCase(kind)
                    || Boolean.parseBoolean(properties.getProperty("full.backup", "false"));
            return Optional.of(new SnapshotHistoryEntry(
                    properties.getProperty("snapshot.id", snapshotPath.getFileName().toString()),
                    Instant.parse(properties.getProperty("created.at")),
                    properties.getProperty("trigger", "manual"),
                    Integer.parseInt(properties.getProperty("changed.files", "0")),
                    Integer.parseInt(properties.getProperty("changed.regionSets", "0")),
                    Integer.parseInt(properties.getProperty("deleted.files", "0")),
                    fullBackup,
                    status,
                    storageName,
                    snapshotPath));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Optional<SnapshotHistoryEntry> findLatest(Predicate<SnapshotHistoryEntry> predicate) {
        if (!Files.isDirectory(snapshotsRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.find(
                snapshotsRoot,
                3,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().equals(PROPERTIES_FILE))) {
            return stream
                    .map(this::readHistoryEntry)
                    .flatMap(Optional::stream)
                    .filter(predicate)
                    .max(Comparator.comparing(SnapshotHistoryEntry::createdAt));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private void writeMetadataFiles(
            Path root,
            String snapshotId,
            BackupRequest request,
            Instant createdAt,
            SnapshotKind snapshotKind,
            String parentSnapshotId,
            String baseSnapshotId,
            List<SnapshotWorld> worlds,
            Set<BackupScope> scopes,
            List<TrackedFileMetadata> changedEntries,
            List<TrackedFileMetadata> deletedEntries) throws IOException {
        validateSnapshotData(worlds, changedEntries, deletedEntries);
        Properties properties = new Properties();
        properties.setProperty("format.version", FORMAT_VERSION);
        properties.setProperty("identity.scheme", CurrentIndexStore.IDENTITY_SCHEME);
        properties.setProperty("snapshot.id", snapshotId);
        properties.setProperty("parent.snapshot.id", safe(parentSnapshotId));
        properties.setProperty("base.snapshot.id", safe(baseSnapshotId));
        properties.setProperty("created.at", createdAt.toString());
        properties.setProperty("trigger", safe(request.trigger()));
        properties.setProperty("initiator", safe(request.initiator()));
        properties.setProperty("message", safe(request.message()));
        properties.setProperty("full.backup", Boolean.toString(snapshotKind.isFullBaseline()));
        properties.setProperty("snapshot.kind", snapshotKind.name());
        properties.setProperty("copy.mode", snapshotKind == SnapshotKind.INCREMENTAL ? "INCREMENTAL" : "FULL");
        properties.setProperty("scope.complete", Boolean.toString(snapshotKind.isFullBaseline()));
        properties.setProperty("changed.files", Integer.toString(changedEntries.size()));
        properties.setProperty("changed.regionSets", Integer.toString(uniqueRegionSets(changedEntries, deletedEntries)));
        properties.setProperty("deleted.files", Integer.toString(deletedEntries.size()));
        properties.setProperty("worlds", worlds.stream()
                .map(SnapshotWorld::worldUuid)
                .collect(Collectors.joining(",")));
        properties.setProperty("scopes", scopes.stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(scope -> scope.name().toLowerCase())
                .collect(Collectors.joining(",")));
        try (OutputStream outputStream = Files.newOutputStream(root.resolve(PROPERTIES_FILE))) {
            properties.store(outputStream, "TimeMachine snapshot");
        }

        writeWorldsFile(root.resolve(WORLDS_FILE), worlds);
        writeEntriesFile(root.resolve(ENTRIES_FILE), changedEntries);
        writeEntriesFile(root.resolve(DELETIONS_FILE), deletedEntries);
        writeChecksumsFile(root.resolve(CHECKSUMS_FILE), changedEntries);
        Files.writeString(
                root.resolve(NOTES_FILE),
                restoreNotes(snapshotId, parentSnapshotId, baseSnapshotId),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        for (String metadataFile : List.of(
                PROPERTIES_FILE,
                WORLDS_FILE,
                ENTRIES_FILE,
                DELETIONS_FILE,
                CHECKSUMS_FILE,
                NOTES_FILE)) {
            forceFile(root.resolve(metadataFile));
        }
        forceDirectory(root);
    }

    private void writeWorldsFile(Path path, List<SnapshotWorld> worlds) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("worldUuid\tworldKey\tworldName\tstoragePath\tsourcePath");
            writer.newLine();
            for (SnapshotWorld world : worlds) {
                writer.write(String.join("\t",
                        safeTsv(world.worldUuid()),
                        safeTsv(world.worldKey()),
                        safeTsv(world.worldName()),
                        safeTsv(world.storagePath()),
                        safeTsv(world.sourcePath())));
                writer.newLine();
            }
        }
    }

    private void validateSnapshotData(
            List<SnapshotWorld> worlds,
            List<TrackedFileMetadata> changedEntries,
            List<TrackedFileMetadata> deletedEntries) throws IOException {
        Map<String, SnapshotWorld> worldsByUuid = new LinkedHashMap<>();
        Set<String> worldStoragePaths = new LinkedHashSet<>();
        for (SnapshotWorld world : worlds) {
            String normalizedUuid;
            try {
                normalizedUuid = UUID.fromString(world.worldUuid()).toString();
            } catch (IllegalArgumentException ex) {
                throw new IOException("Invalid snapshot world UUID: " + world.worldUuid(), ex);
            }
            if (!normalizedUuid.equals(world.worldUuid())
                    || !("worlds/" + normalizedUuid).equals(world.storagePath())
                    || worldsByUuid.putIfAbsent(normalizedUuid, world) != null
                    || !worldStoragePaths.add(world.storagePath())) {
                throw new IOException("Invalid or duplicate snapshot world mapping: " + world.worldUuid());
            }
        }

        Set<String> changedPaths = validateTrackedEntries(changedEntries, worldsByUuid, "changed");
        Set<String> deletedPaths = validateTrackedEntries(deletedEntries, worldsByUuid, "deleted");
        for (String deletedPath : deletedPaths) {
            if (changedPaths.contains(deletedPath)) {
                throw new IOException("Snapshot path cannot be both changed and deleted: " + deletedPath);
            }
        }
    }

    private Set<String> validateTrackedEntries(
            List<TrackedFileMetadata> entries,
            Map<String, SnapshotWorld> worldsByUuid,
            String entryType) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        for (TrackedFileMetadata metadata : entries) {
            SnapshotWorld world = worldsByUuid.get(metadata.worldUuid());
            String expectedPath = metadata.worldStoragePath() + "/" + metadata.scope().directoryName()
                    + "/r." + metadata.regionX() + "." + metadata.regionZ() + ".mca";
            if (world == null
                    || !world.worldKey().equals(metadata.worldKey())
                    || !world.worldName().equals(metadata.worldName())
                    || !world.storagePath().equals(metadata.worldStoragePath())
                    || !expectedPath.equals(metadata.relativePath())
                    || metadata.modifiedTime() < 0L
                    || metadata.size() < 0L
                    || metadata.sha256() == null
                    || !SHA256_PATTERN.matcher(metadata.sha256()).matches()) {
                throw new IOException("Invalid " + entryType + " snapshot entry: " + metadata.relativePath());
            }
            if (!paths.add(metadata.relativePath())) {
                throw new IOException("Duplicate " + entryType + " snapshot entry: " + metadata.relativePath());
            }
        }
        return paths;
    }

    private void writeEntriesFile(Path path, List<TrackedFileMetadata> entries) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(ENTRY_HEADER);
            writer.newLine();
            for (TrackedFileMetadata metadata : entries) {
                if (resolvePortableRelative(path.getParent(), metadata.relativePath()) == null) {
                    throw new IOException("Invalid snapshot entry path: " + metadata.relativePath());
                }
                if (metadata.sha256() == null || !SHA256_PATTERN.matcher(metadata.sha256()).matches()) {
                    throw new IOException("Invalid SHA-256 for snapshot entry: " + metadata.relativePath());
                }
                writer.write(String.join("\t",
                        safeTsv(metadata.relativePath()),
                        safeTsv(metadata.worldName()),
                        safeTsv(metadata.worldKey()),
                        safeTsv(metadata.worldUuid()),
                        safeTsv(metadata.worldStoragePath()),
                        metadata.scope().name(),
                        Integer.toString(metadata.regionX()),
                        Integer.toString(metadata.regionZ()),
                        Long.toString(metadata.modifiedTime()),
                        Long.toString(metadata.size()),
                        safeTsv(metadata.sha256())));
                writer.newLine();
            }
        }
    }

    private void writeChecksumsFile(Path path, List<TrackedFileMetadata> entries) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (TrackedFileMetadata metadata : entries) {
                if (metadata.sha256() == null || !SHA256_PATTERN.matcher(metadata.sha256()).matches()) {
                    throw new IOException("Missing SHA-256 for copied file: " + metadata.relativePath());
                }
                writer.write(metadata.sha256() + "  files/" + metadata.relativePath());
                writer.newLine();
            }
        }
    }

    private Map<String, SnapshotVerifier.WorldMapping> readWorldMappings(
            Path snapshotPath,
            List<LocalizedMessage> errors) throws IOException {
        return verifier.readWorldMappings(snapshotPath, errors);
    }

    private Map<String, Path> snapshotLocations() throws IOException {
        return verifier.snapshotLocations();
    }

    private boolean hasSnapshotId(Path propertiesPath, String expectedId) {
        if (!isRegularFile(propertiesPath)) {
            return false;
        }
        try {
            return expectedId.equals(loadProperties(propertiesPath).getProperty("snapshot.id"));
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean isRegularFile(Path path) {
        try {
            return Files.readAttributes(
                            path,
                            BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS)
                    .isRegularFile();
        } catch (IOException ex) {
            return false;
        }
    }

    private Path resolvePortableRelative(Path root, String relativePath) {
        if (relativePath == null
                || relativePath.isBlank()
                || relativePath.indexOf('\\') >= 0
                || relativePath.indexOf('\0') >= 0) {
            return null;
        }
        try {
            Path candidate = Path.of(relativePath).normalize();
            if (candidate.isAbsolute()
                    || !candidate.toString().replace('\\', '/').equals(relativePath)) {
                return null;
            }
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path resolved = normalizedRoot.resolve(candidate).normalize();
            return resolved.startsWith(normalizedRoot) ? resolved : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Path requireDirectChild(Path root, Path candidate, String description) throws IOException {
        Path validated = requireDescendant(root, candidate, description);
        if (!validated.getParent().equals(root.toAbsolutePath().normalize())) {
            throw new IOException("Invalid " + description + ": " + candidate);
        }
        return validated;
    }

    private Path requireDescendant(Path root, Path candidate, String description) throws IOException {
        if (candidate == null) {
            throw new IOException("Missing " + description + ".");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path validated = candidate.toAbsolutePath().normalize();
        if (validated.equals(normalizedRoot) || !validated.startsWith(normalizedRoot)) {
            throw new IOException("Invalid " + description + ": " + candidate);
        }
        return validated;
    }

    private void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void forceDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is not supported on every filesystem, notably on Windows.
        }
    }

    private Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(path)) {
            properties.load(inputStream);
        }
        return properties;
    }

    private int uniqueRegionSets(List<TrackedFileMetadata> changedEntries, List<TrackedFileMetadata> deletedEntries) {
        return (int) Stream.concat(changedEntries.stream(), deletedEntries.stream())
                .map(TrackedFileMetadata::regionKey)
                .distinct()
                .count();
    }

    private String restoreNotes(String snapshotId, String parentSnapshotId, String baseSnapshotId) {
        return """
                TimeMachine restore notes
                - Restore only while the Minecraft server is fully stopped.
                - Target snapshot: %s
                - Base FULL snapshot: %s
                - Parent snapshot: %s
                - Start with the base FULL snapshot, then apply every child snapshot in chain order.
                - For each snapshot, copy entries.tsv files from ./files and then remove deletions.tsv paths.
                - worlds.tsv maps stable world UUID storage paths to the world key, name, and source path.
                - Verify the complete chain before restore with /timemachine verify <snapshotId>.
                - Terrain-only restore applies region files only; consistent restore applies region, entities, and poi.
                """.formatted(snapshotId, safe(baseSnapshotId), safe(parentSnapshotId));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeTsv(String value) {
        return safe(value).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    public record SnapshotWorld(
            String worldUuid,
            String worldKey,
            String worldName,
            String storagePath,
            String sourcePath) {
    }

    public record SnapshotHistoryEntry(
            String snapshotId,
            Instant createdAt,
            String trigger,
            int changedFiles,
            int changedRegionSets,
            int deletedFiles,
            boolean fullBackup,
            String status,
            String storageName,
            Path snapshotPath) {
    }

    public record VerificationResult(
            boolean valid,
            int snapshotsChecked,
            int filesChecked,
            List<String> chain,
            List<LocalizedMessage> issues) {
        public VerificationResult {
            chain = List.copyOf(chain);
            issues = List.copyOf(issues);
        }

        public List<String> errors() {
            return issues.stream()
                    .map(issue -> ENGLISH_MESSAGES.system(issue.key(), issue.argumentsArray()))
                    .toList();
        }
    }

}
