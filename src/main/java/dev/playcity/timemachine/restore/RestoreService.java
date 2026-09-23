package dev.playcity.timemachine.restore;

import dev.playcity.timemachine.backup.FileHashes;
import dev.playcity.timemachine.backup.SnapshotStore;
import dev.playcity.timemachine.i18n.LocalizedMessage;
import dev.playcity.timemachine.io.FileTreeOperations;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class RestoreService {
    private static final String ENTRY_HEADER =
            "relativePath\tworldName\tworldKey\tworldUuid\tworldStoragePath\tscope\tregionX\tregionZ\tmtime\tsize\tsha256";
    private static final String WORLD_HEADER = "worldUuid\tworldKey\tworldName\tstoragePath\tsourcePath";
    private static final Pattern RESTORABLE_PATH = Pattern.compile(
            "^worlds/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/"
                    + "(region|entities|poi)/(r\\.(-?\\d+)\\.(-?\\d+)\\.mca)$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern PORTABLE_WORLD_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern RESOURCE_NAMESPACE = Pattern.compile("^[a-z0-9._-]+$");
    private static final Pattern RESOURCE_PATH = Pattern.compile("^[a-z0-9._/-]+$");
    private static final Pattern WINDOWS_RESERVED_NAME = Pattern.compile(
            "(?i)^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$");
    private static final Set<String> RESERVED_OUTPUT_NAMES = Set.of(
            "restore_readme.txt",
            "restore-worlds.tsv");
    private static final String STAGING_PREFIX = ".timemachine-restore-";

    private final Path storageRoot;
    private final List<Path> archiveRoots;
    private final SnapshotStore snapshotStore;

    public RestoreService(Path storageRoot, List<Path> archiveRoots) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.archiveRoots = archiveRoots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        this.snapshotStore = new SnapshotStore(this.storageRoot, this.archiveRoots);
    }

    public SnapshotStore.VerificationResult verify(String snapshotId) {
        return snapshotStore.verifySnapshot(snapshotId);
    }

    public RestoreResult export(String snapshotId, Path requestedOutput) throws IOException {
        return export(snapshotId, requestedOutput, ignored -> {
        });
    }

    public RestoreResult export(
            String snapshotId,
            Path requestedOutput,
            Consumer<ExportProgress> progressConsumer) throws IOException {
        Objects.requireNonNull(progressConsumer, "progressConsumer");
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IOException("A snapshot ID is required.");
        }
        progressConsumer.accept(new ExportProgress(ExportPhase.VERIFYING, 0, 0, 0L, 0L));
        SnapshotStore.VerificationResult verification = snapshotStore.verifySnapshot(snapshotId);
        if (!verification.valid()) {
            throw new SnapshotVerificationException(verification.issues(), verification.errors());
        }
        Map<String, Path> chainLocations = snapshotStore.locateSnapshots(verification.chain());
        if (chainLocations.size() != verification.chain().size()) {
            throw new IOException("A verified snapshot disappeared before export started.");
        }

        Path output = prepareOutputPath(requestedOutput);
        Path parent = output.getParent();
        if (parent == null) {
            throw new IOException("The restore output must have a parent directory: " + requestedOutput);
        }
        Path staging = parent.resolve(STAGING_PREFIX + UUID.randomUUID() + ".staging");
        Files.createDirectory(staging);

        try {
            progressConsumer.accept(new ExportProgress(
                    ExportPhase.RECONSTRUCTING,
                    0,
                    verification.chain().size(),
                    0L,
                    0L));
            Map<String, WorldInfo> worlds = new LinkedHashMap<>();
            Map<String, RestoreEntry> restoredState = new LinkedHashMap<>();
            for (int chainIndex = 0; chainIndex < verification.chain().size(); chainIndex++) {
                String chainSnapshotId = verification.chain().get(chainIndex);
                Path snapshot = chainLocations.get(chainSnapshotId);
                if (snapshot == null) {
                    throw new IOException("Verified snapshot disappeared: " + chainSnapshotId);
                }
                rejectSymbolicLinks(snapshot, snapshot);
                for (WorldInfo world : readWorlds(snapshot)) {
                    worlds.put(world.uuid(), world);
                }
                applyEntries(snapshot, restoredState);
                applyDeletions(snapshot, restoredState);
                progressConsumer.accept(new ExportProgress(
                        ExportPhase.RECONSTRUCTING,
                        chainIndex + 1,
                        verification.chain().size(),
                        0L,
                        0L));
            }

            Map<String, String> outputDirectories = chooseOutputDirectories(worlds);
            for (String outputDirectory : outputDirectories.values()) {
                Files.createDirectories(staging.resolve(outputDirectory));
            }

            long copiedBytes = 0L;
            List<RestoreEntry> entries = restoredState.values().stream()
                    .sorted(Comparator.comparing(RestoreEntry::relativePath))
                    .toList();
            long totalBytes = 0L;
            for (RestoreEntry entry : entries) {
                totalBytes = safeAdd(totalBytes, entry.size());
            }
            ensureRestoreSpace(staging, totalBytes);
            progressConsumer.accept(new ExportProgress(ExportPhase.COPYING, 0, entries.size(), 0L, totalBytes));
            for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
                RestoreEntry entry = entries.get(entryIndex);
                String worldDirectory = outputDirectories.get(entry.path().worldUuid());
                if (worldDirectory == null) {
                    throw new IOException("Snapshot entry has no world mapping: " + entry.relativePath());
                }
                Path destination = staging.resolve(worldDirectory)
                        .resolve(entry.path().scope())
                        .resolve(entry.path().fileName());
                Files.createDirectories(destination.getParent());
                copyAndVerify(entry, destination);
                copiedBytes = safeAdd(copiedBytes, entry.size());
                progressConsumer.accept(new ExportProgress(
                        ExportPhase.COPYING,
                        entryIndex + 1,
                        entries.size(),
                        copiedBytes,
                        totalBytes));
            }

            progressConsumer.accept(new ExportProgress(
                    ExportPhase.FINAL_VERIFICATION,
                    entries.size(),
                    entries.size(),
                    copiedBytes,
                    totalBytes));
            SnapshotStore.VerificationResult finalVerification = snapshotStore.verifySnapshot(snapshotId);
            if (!finalVerification.valid() || !verification.chain().equals(finalVerification.chain())) {
                throw new IOException("Snapshot chain changed during export; no output was published.");
            }

            writeRestoreMetadata(
                    staging,
                    snapshotId,
                    verification.chain(),
                    worlds,
                    outputDirectories,
                    entries.size(),
                    copiedBytes);
            RestoreResult result = new RestoreResult(
                    snapshotId,
                    output,
                    worlds.size(),
                    entries.size(),
                    copiedBytes,
                    verification.chain().size());
            progressConsumer.accept(new ExportProgress(
                    ExportPhase.PUBLISHING,
                    entries.size(),
                    entries.size(),
                    copiedBytes,
                    totalBytes));
            moveCompletedExport(staging, output);
            return result;
        } catch (IOException | RuntimeException ex) {
            deleteOwnedStaging(staging);
            throw ex;
        }
    }

    private Path prepareOutputPath(Path requestedOutput) throws IOException {
        if (requestedOutput == null) {
            throw new IOException("A restore output directory is required.");
        }
        Path normalized = requestedOutput.toAbsolutePath().normalize();
        if (normalized.getFileName() == null) {
            throw new IOException("The filesystem root cannot be used as a restore output.");
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Restore output already exists and will not be overwritten: " + normalized);
        }
        rejectLexicalStorageOverlap(normalized, storageRoot);
        for (Path archiveRoot : archiveRoots) {
            rejectLexicalStorageOverlap(normalized, archiveRoot);
        }
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("The restore output must have a parent directory: " + normalized);
        }
        Path existingAncestor = parent;
        while (existingAncestor != null && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            throw new IOException("Could not resolve an existing parent for restore output: " + requestedOutput);
        }
        rejectSymbolicLinkComponents(existingAncestor);
        Files.createDirectories(parent);
        rejectSymbolicLinkComponents(parent);
        Path realParent = parent.toRealPath();
        Path output = realParent.resolve(normalized.getFileName()).normalize();
        if (!output.getParent().equals(realParent)) {
            throw new IOException("Invalid restore output path: " + requestedOutput);
        }
        rejectStorageOverlap(output, storageRoot);
        for (Path archiveRoot : archiveRoots) {
            rejectStorageOverlap(output, archiveRoot);
        }
        return output;
    }

    private void rejectStorageOverlap(Path output, Path configuredRoot) throws IOException {
        Path root = canonicalPath(configuredRoot);
        if (output.startsWith(root) || root.startsWith(output)) {
            throw new IOException("Restore output overlaps backup storage: " + configuredRoot);
        }
    }

    private void rejectLexicalStorageOverlap(Path output, Path configuredRoot) throws IOException {
        Path root = configuredRoot.toAbsolutePath().normalize();
        if (output.startsWith(root) || root.startsWith(output)) {
            throw new IOException("Restore output overlaps backup storage: " + configuredRoot);
        }
    }

    private Path canonicalPath(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return path.toRealPath();
        }
        return path.toAbsolutePath().normalize();
    }

    private void rejectSymbolicLinkComponents(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            throw new IOException("Could not resolve output path: " + path);
        }
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not allowed in the restore output path: " + current);
            }
        }
    }

    private List<WorldInfo> readWorlds(Path snapshot) throws IOException {
        Path worldsFile = snapshot.resolve("worlds.tsv");
        List<WorldInfo> worlds = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(worldsFile, StandardCharsets.UTF_8)) {
            if (!WORLD_HEADER.equals(reader.readLine())) {
                throw new IOException("Unsupported worlds.tsv format in " + snapshot);
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length != 5) {
                    throw new IOException("Malformed worlds.tsv line " + lineNumber + " in " + snapshot);
                }
                String uuid = canonicalUuid(parts[0], "worlds.tsv line " + lineNumber);
                if (!("worlds/" + uuid).equals(parts[3])) {
                    throw new IOException("Invalid world storage path in " + snapshot + ": " + parts[3]);
                }
                worlds.add(new WorldInfo(uuid, parts[1], parts[2], parts[4]));
            }
        }
        return List.copyOf(worlds);
    }

    private void applyEntries(Path snapshot, Map<String, RestoreEntry> restoredState) throws IOException {
        Path entriesFile = snapshot.resolve("entries.tsv");
        try (BufferedReader reader = Files.newBufferedReader(entriesFile, StandardCharsets.UTF_8)) {
            requireEntryHeader(reader, entriesFile);
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = requireEntryParts(line, lineNumber, entriesFile);
                RestorePath restorePath = parseRestorePath(parts[0]);
                long size = parseNonNegativeLong(parts[9], "size", parts[0]);
                if (!SHA256.matcher(parts[10]).matches()) {
                    throw new IOException("Invalid SHA-256 in " + entriesFile + " for " + parts[0]);
                }
                Path filesRoot = snapshot.resolve("files").toAbsolutePath().normalize();
                Path source = filesRoot;
                for (String segment : parts[0].split("/")) {
                    source = source.resolve(segment);
                }
                source = source.normalize();
                if (!source.startsWith(filesRoot)) {
                    throw new IOException("Snapshot entry escapes files directory: " + parts[0]);
                }
                rejectSymbolicLinks(snapshot, source);
                BasicFileAttributes attributes = Files.readAttributes(
                        source,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) {
                    throw new IOException("Snapshot entry is not a regular file: " + parts[0]);
                }
                restoredState.put(parts[0], new RestoreEntry(
                        parts[0],
                        restorePath,
                        source,
                        size,
                        parts[10].toLowerCase(Locale.ROOT)));
            }
        }
    }

    private void applyDeletions(Path snapshot, Map<String, RestoreEntry> restoredState) throws IOException {
        Path deletionsFile = snapshot.resolve("deletions.tsv");
        try (BufferedReader reader = Files.newBufferedReader(deletionsFile, StandardCharsets.UTF_8)) {
            requireEntryHeader(reader, deletionsFile);
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = requireEntryParts(line, lineNumber, deletionsFile);
                parseRestorePath(parts[0]);
                restoredState.remove(parts[0]);
            }
        }
    }

    private void requireEntryHeader(BufferedReader reader, Path path) throws IOException {
        if (!ENTRY_HEADER.equals(reader.readLine())) {
            throw new IOException("Unsupported snapshot entry format in " + path);
        }
    }

    private String[] requireEntryParts(String line, int lineNumber, Path path) throws IOException {
        String[] parts = line.split("\t", -1);
        if (parts.length != 11) {
            throw new IOException("Malformed snapshot entry line " + lineNumber + " in " + path);
        }
        return parts;
    }

    private RestorePath parseRestorePath(String relativePath) throws IOException {
        Matcher matcher = RESTORABLE_PATH.matcher(relativePath);
        if (!matcher.matches()) {
            throw new IOException("Snapshot path is not restorable: " + relativePath);
        }
        String uuid = canonicalUuid(matcher.group(1), "snapshot path");
        return new RestorePath(uuid, matcher.group(2), matcher.group(3));
    }

    private String canonicalUuid(String value, String description) throws IOException {
        try {
            String normalized = UUID.fromString(value).toString();
            if (!normalized.equals(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid world UUID in " + description + ": " + value, ex);
        }
    }

    private long parseNonNegativeLong(String raw, String field, String path) throws IOException {
        try {
            long value = Long.parseLong(raw);
            if (value < 0L) {
                throw new NumberFormatException("negative value");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid " + field + " for " + path + ": " + raw, ex);
        }
    }

    private Map<String, String> chooseOutputDirectories(Map<String, WorldInfo> worlds) throws IOException {
        Map<String, PaperDimensionSource> paperSources = new LinkedHashMap<>();
        for (WorldInfo world : worlds.values()) {
            PaperDimensionSource source = parsePaperDimensionSource(world);
            if (source != null) {
                paperSources.put(world.uuid(), source);
            }
        }

        Map<String, List<WorldInfo>> worldsByPaperRoot = new LinkedHashMap<>();
        for (WorldInfo world : worlds.values()) {
            PaperDimensionSource source = paperSources.get(world.uuid());
            if (source != null) {
                worldsByPaperRoot.computeIfAbsent(source.rootIdentity(), ignored -> new ArrayList<>()).add(world);
            }
        }
        Map<String, Long> paperRootNameCounts = worldsByPaperRoot.entrySet().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        entry -> paperSources.get(entry.getValue().getFirst().uuid()).rootName().toLowerCase(Locale.ROOT),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));

        Map<String, String> paperRootOutputs = new LinkedHashMap<>();
        Set<String> usedTopLevelNames = new LinkedHashSet<>();
        worldsByPaperRoot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    WorldInfo firstWorld = entry.getValue().stream()
                            .min(Comparator.comparing(WorldInfo::uuid))
                            .orElseThrow();
                    String candidate = paperSources.get(firstWorld.uuid()).rootName();
                    if (!isPortableWorldName(candidate)
                            || paperRootNameCounts.getOrDefault(candidate.toLowerCase(Locale.ROOT), 0L) != 1L
                            || !usedTopLevelNames.add(candidate.toLowerCase(Locale.ROOT))) {
                        candidate = uniqueTopLevelName("paper-world-" + firstWorld.uuid(), usedTopLevelNames);
                    }
                    paperRootOutputs.put(entry.getKey(), candidate);
                });

        Map<String, Long> legacyNameCounts = worlds.values().stream()
                .filter(world -> !paperSources.containsKey(world.uuid()))
                .filter(world -> isPortableWorldName(world.name()))
                .collect(java.util.stream.Collectors.groupingBy(
                        world -> world.name().toLowerCase(Locale.ROOT),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));

        Map<String, String> outputDirectories = new LinkedHashMap<>();
        Set<String> usedFullPaths = new LinkedHashSet<>();
        for (WorldInfo world : worlds.values().stream().sorted(Comparator.comparing(WorldInfo::uuid)).toList()) {
            PaperDimensionSource source = paperSources.get(world.uuid());
            String outputDirectory;
            if (source != null) {
                outputDirectory = paperRootOutputs.get(source.rootIdentity())
                        + "/dimensions/" + source.namespace() + "/" + source.dimensionPath();
            } else {
                String candidate = isPortableWorldName(world.name())
                                && legacyNameCounts.getOrDefault(world.name().toLowerCase(Locale.ROOT), 0L) == 1L
                                && !usedTopLevelNames.contains(world.name().toLowerCase(Locale.ROOT))
                        ? world.name()
                        : world.uuid();
                outputDirectory = uniqueTopLevelName(candidate, usedTopLevelNames);
            }
            if (!usedFullPaths.add(outputDirectory.toLowerCase(Locale.ROOT))) {
                throw new IOException("Multiple snapshot worlds map to the same restore directory: " + outputDirectory);
            }
            outputDirectories.put(world.uuid(), outputDirectory);
        }
        return Map.copyOf(outputDirectories);
    }

    private PaperDimensionSource parsePaperDimensionSource(WorldInfo world) {
        int separator = world.key() == null ? -1 : world.key().indexOf(':');
        if (separator <= 0 || separator == world.key().length() - 1) {
            return null;
        }
        String namespace = world.key().substring(0, separator);
        String dimensionPath = world.key().substring(separator + 1);
        if (!RESOURCE_NAMESPACE.matcher(namespace).matches()
                || !RESOURCE_PATH.matcher(dimensionPath).matches()
                || dimensionPath.startsWith("/")
                || dimensionPath.endsWith("/")
                || dimensionPath.contains("//")
                || java.util.Arrays.stream(dimensionPath.split("/"))
                        .anyMatch(segment -> ".".equals(segment) || "..".equals(segment))) {
            return null;
        }

        String normalizedSource = normalizePortablePath(world.sourcePath());
        String suffix = "/dimensions/" + namespace + "/" + dimensionPath;
        if (!normalizedSource.endsWith(suffix)) {
            return null;
        }
        String rootIdentity = normalizedSource.substring(0, normalizedSource.length() - suffix.length());
        int rootNameStart = rootIdentity.lastIndexOf('/');
        String rootName = rootIdentity.substring(rootNameStart + 1);
        if (rootIdentity.isBlank() || rootName.isBlank()) {
            return null;
        }
        return new PaperDimensionSource(rootIdentity, rootName, namespace, dimensionPath);
    }

    private String normalizePortablePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace('\\', '/');
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String uniqueTopLevelName(String candidate, Set<String> usedNames) {
        String unique = candidate;
        int suffix = 2;
        while (!usedNames.add(unique.toLowerCase(Locale.ROOT))) {
            unique = candidate + "-" + suffix;
            suffix++;
        }
        return unique;
    }

    private boolean isPortableWorldName(String name) {
        return name != null
                && PORTABLE_WORLD_NAME.matcher(name).matches()
                && !".".equals(name)
                && !"..".equals(name)
                && !name.endsWith(".")
                && !WINDOWS_RESERVED_NAME.matcher(name).matches()
                && !RESERVED_OUTPUT_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    private void copyAndVerify(RestoreEntry entry, Path destination) throws IOException {
        CopyOption[] options = {StandardCopyOption.COPY_ATTRIBUTES};
        Files.copy(entry.source(), destination, options);
        BasicFileAttributes attributes = Files.readAttributes(
                destination,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != entry.size()) {
            throw new IOException("Restored file size mismatch: " + entry.relativePath());
        }
        String actualHash = FileHashes.sha256(destination);
        if (!entry.sha256().equals(actualHash)) {
            throw new IOException("Restored file SHA-256 mismatch: " + entry.relativePath());
        }
    }

    static void ensureRestoreSpace(Path destination, long requiredBytes) throws IOException {
        FileStore fileStore = Files.getFileStore(destination);
        long usableBytes = fileStore.getUsableSpace();
        if (usableBytes < requiredBytes) {
            throw new IOException("Insufficient free space for restore export: usable="
                    + usableBytes + " bytes, required=" + requiredBytes + " bytes.");
        }
    }

    private void writeRestoreMetadata(
            Path staging,
            String snapshotId,
            List<String> chain,
            Map<String, WorldInfo> worlds,
            Map<String, String> outputDirectories,
            int files,
            long bytes) throws IOException {
        List<String> mappingLines = new ArrayList<>();
        mappingLines.add("worldUuid\tworldKey\tworldName\toutputDirectory");
        worlds.values().stream()
                .sorted(Comparator.comparing(WorldInfo::uuid))
                .forEach(world -> mappingLines.add(String.join("\t",
                        world.uuid(),
                        safeTsv(world.key()),
                        safeTsv(world.name()),
                        outputDirectories.get(world.uuid()))));
        Files.write(staging.resolve("restore-worlds.tsv"), mappingLines, StandardCharsets.UTF_8);

        String notes = """
                TimeMachine offline restore export

                Snapshot: %s
                Verified chain: %s
                Exported at: %s
                Files: %d
                Bytes: %d

                This export did not modify any Minecraft world.
                Keep the server fully stopped while installing restored world directories.
                Review restore-worlds.tsv before replacing or renaming any existing world directory.
                Keep the original world directories until the restored server has been validated.
                """.formatted(snapshotId, String.join(" -> ", chain), Instant.now(), files, bytes);
        Files.writeString(staging.resolve("RESTORE_README.txt"), notes, StandardCharsets.UTF_8);
    }

    private String safeTsv(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private void rejectSymbolicLinks(Path snapshot, Path source) throws IOException {
        Path normalizedSnapshot = snapshot.toAbsolutePath().normalize();
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!normalizedSource.startsWith(normalizedSnapshot)) {
            throw new IOException("Snapshot source escapes its snapshot directory: " + source);
        }
        rejectSymbolicLinkComponents(normalizedSnapshot);
        Path current = normalizedSnapshot;
        if (Files.isSymbolicLink(current)) {
            throw new IOException("Symbolic links are not allowed in snapshot content: " + current);
        }
        for (Path component : normalizedSnapshot.relativize(normalizedSource)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not allowed in snapshot content: " + current);
            }
        }
    }

    private void moveCompletedExport(Path staging, Path output) throws IOException {
        try {
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(staging, output);
        }
    }

    private void deleteOwnedStaging(Path staging) {
        try {
            Path parent = staging.getParent();
            if (parent == null
                    || !staging.getFileName().toString().startsWith(STAGING_PREFIX)
                    || !staging.toAbsolutePath().normalize().getParent().equals(parent.toAbsolutePath().normalize())
                    || !Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            FileTreeOperations.deleteRecursively(staging);
        } catch (IOException ignored) {
        }
    }

    private long safeAdd(long left, long right) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            throw new IOException("Restore size exceeds the supported range.", ex);
        }
    }

    public record RestoreResult(
            String snapshotId,
            Path outputDirectory,
            int worlds,
            int files,
            long bytes,
            int snapshotsInChain) {
    }

    public static final class SnapshotVerificationException extends IOException {
        private static final long serialVersionUID = 1L;
        private final transient List<LocalizedMessage> issues;

        private SnapshotVerificationException(
                List<LocalizedMessage> issues,
                List<String> englishErrors) {
            super("Snapshot chain verification failed: " + String.join("; ", englishErrors));
            this.issues = List.copyOf(issues);
        }

        public List<LocalizedMessage> issues() {
            return issues;
        }
    }

    public record ExportProgress(
            ExportPhase phase,
            int completedItems,
            int totalItems,
            long completedBytes,
            long totalBytes) {
    }

    public enum ExportPhase {
        VERIFYING,
        RECONSTRUCTING,
        COPYING,
        FINAL_VERIFICATION,
        PUBLISHING
    }

    private record WorldInfo(String uuid, String key, String name, String sourcePath) {
    }

    private record PaperDimensionSource(
            String rootIdentity,
            String rootName,
            String namespace,
            String dimensionPath) {
    }

    private record RestorePath(String worldUuid, String scope, String fileName) {
    }

    private record RestoreEntry(
            String relativePath,
            RestorePath path,
            Path source,
            long size,
            String sha256) {
    }

}
