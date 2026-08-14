package dev.playcity.timemachine.backup;

import dev.playcity.timemachine.i18n.LocalizedMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class SnapshotVerifier {
    private static final String FORMAT_VERSION = "2";
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

    private final List<Path> searchableRoots;

    SnapshotVerifier(List<Path> searchableRoots) {
        this.searchableRoots = List.copyOf(searchableRoots);
    }

    SnapshotStore.VerificationResult verify(String snapshotId) {
        List<LocalizedMessage> errors = new ArrayList<>();
        List<String> targetToBaseChain = new ArrayList<>();
        int snapshotsChecked = 0;
        int filesChecked = 0;
        try {
            Map<String, Path> locations = snapshotLocations();
            Path currentPath = locations.get(snapshotId);
            if (currentPath == null) {
                return new SnapshotStore.VerificationResult(
                        false, 0, 0, List.of(), List.of(issue("verify.issue.snapshot_not_found", snapshotId)));
            }

            String expectedBaseId = null;
            Set<String> visited = new LinkedHashSet<>();
            while (currentPath != null) {
                Properties properties = loadProperties(currentPath.resolve(PROPERTIES_FILE));
                String currentId = properties.getProperty("snapshot.id", "");
                if (currentId.isBlank()) {
                    errors.add(issue("verify.issue.metadata_missing_id", currentPath));
                    break;
                }
                if (!visited.add(currentId)) {
                    errors.add(issue("verify.issue.chain_cycle", currentId));
                    break;
                }
                targetToBaseChain.add(currentId);
                if (!FORMAT_VERSION.equals(properties.getProperty("format.version"))) {
                    errors.add(issue("verify.issue.legacy_format", currentId));
                    break;
                }

                snapshotsChecked++;
                FileVerification fileVerification = verifyFiles(currentPath, properties);
                filesChecked += fileVerification.filesChecked();
                errors.addAll(fileVerification.errors());

                String baseId = properties.getProperty("base.snapshot.id", "");
                if (expectedBaseId == null) {
                    expectedBaseId = baseId;
                } else if (!expectedBaseId.equals(baseId)) {
                    errors.add(issue("verify.issue.different_base", currentId, baseId));
                }

                SnapshotKind kind;
                try {
                    kind = SnapshotKind.valueOf(properties.getProperty("snapshot.kind", "INCREMENTAL"));
                } catch (IllegalArgumentException ex) {
                    errors.add(issue("verify.issue.invalid_kind", currentId));
                    break;
                }

                String parentId = properties.getProperty("parent.snapshot.id", "");
                if (kind.isFullBaseline()) {
                    if (!parentId.isBlank()) {
                        errors.add(issue("verify.issue.full_has_parent", currentId));
                    }
                    if (!currentId.equals(baseId)) {
                        errors.add(issue("verify.issue.full_wrong_base", currentId));
                    }
                    break;
                }
                if (parentId.isBlank()) {
                    errors.add(issue("verify.issue.parent_id_missing", currentId));
                    break;
                }
                currentPath = locations.get(parentId);
                if (currentPath == null) {
                    errors.add(issue("verify.issue.parent_not_found", parentId));
                    break;
                }
            }
        } catch (Exception ex) {
            errors.add(issue("verify.issue.failed", ex.getClass().getSimpleName()));
        }
        Collections.reverse(targetToBaseChain);
        return new SnapshotStore.VerificationResult(
                errors.isEmpty(),
                snapshotsChecked,
                filesChecked,
                List.copyOf(targetToBaseChain),
                List.copyOf(errors));
    }

    SnapshotChainHealth inspectActiveChain(CurrentIndexStore.IndexState state) {
        if (state.baselineRequired()) {
            return SnapshotChainHealth.baselineRequired(
                    state.lastSnapshotId(),
                    state.baseSnapshotId());
        }
        return inspectRecordedChain(state);
    }

    SnapshotChainHealth inspectRecordedChain(CurrentIndexStore.IndexState state) {
        String lastSnapshotId = state.lastSnapshotId();
        String expectedBaseId = state.baseSnapshotId();
        if (lastSnapshotId.isBlank() || expectedBaseId.isBlank()) {
            return SnapshotChainHealth.broken(
                    lastSnapshotId,
                    expectedBaseId,
                    0,
                    issue("chain.issue.index_metadata_missing"));
        }

        int snapshotsChecked = 0;
        try {
            Map<String, Path> locations = snapshotLocations();
            String expectedCurrentId = lastSnapshotId;
            Path currentPath = locations.get(expectedCurrentId);
            if (currentPath == null) {
                return SnapshotChainHealth.broken(
                        lastSnapshotId,
                        expectedBaseId,
                        snapshotsChecked,
                        issue("verify.issue.snapshot_not_found", expectedCurrentId));
            }

            Set<String> visited = new LinkedHashSet<>();
            while (currentPath != null) {
                Properties properties = loadProperties(currentPath.resolve(PROPERTIES_FILE));
                String currentId = properties.getProperty("snapshot.id", "");
                if (currentId.isBlank()) {
                    return SnapshotChainHealth.broken(
                            lastSnapshotId,
                            expectedBaseId,
                            snapshotsChecked,
                            issue("verify.issue.metadata_missing_id", currentPath));
                }
                if (!expectedCurrentId.equals(currentId)) {
                    return SnapshotChainHealth.broken(
                            lastSnapshotId,
                            expectedBaseId,
                            snapshotsChecked,
                            issue("chain.issue.snapshot_id_mismatch", expectedCurrentId, currentId));
                }
                if (!visited.add(currentId)) {
                    return SnapshotChainHealth.broken(
                            lastSnapshotId,
                            expectedBaseId,
                            snapshotsChecked,
                            issue("verify.issue.chain_cycle", currentId));
                }
                snapshotsChecked++;

                if (!FORMAT_VERSION.equals(properties.getProperty("format.version"))) {
                    return SnapshotChainHealth.broken(
                            lastSnapshotId,
                            expectedBaseId,
                            snapshotsChecked,
                            issue("verify.issue.legacy_format", currentId));
                }
                for (String requiredFile : List.of(
                        WORLDS_FILE,
                        ENTRIES_FILE,
                        DELETIONS_FILE,
                        CHECKSUMS_FILE,
                        NOTES_FILE)) {
                    if (!isRegularFile(currentPath.resolve(requiredFile))) {
                        return SnapshotChainHealth.broken(
                                lastSnapshotId,
                                expectedBaseId,
                                snapshotsChecked,
                                issue("verify.issue.missing_file", requiredFile, currentPath));
                    }
                }

                String baseId = properties.getProperty("base.snapshot.id", "");
                if (!expectedBaseId.equals(baseId)) {
                    return SnapshotChainHealth.broken(
                            lastSnapshotId,
                            expectedBaseId,
                            snapshotsChecked,
                            issue("verify.issue.different_base", currentId, baseId));
                }

                SnapshotKind kind;
                try {
                    kind = SnapshotKind.valueOf(properties.getProperty("snapshot.kind", "INCREMENTAL"));
                } catch (IllegalArgumentException ex) {
                    return SnapshotChainHealth.broken(
                            lastSnapshotId,
                            expectedBaseId,
                            snapshotsChecked,
                            issue("verify.issue.invalid_kind", currentId));
                }

                String parentId = properties.getProperty("parent.snapshot.id", "");
                if (kind.isFullBaseline()) {
                    if (!parentId.isBlank()) {
                        return SnapshotChainHealth.broken(
                                lastSnapshotId,
                                expectedBaseId,
                                snapshotsChecked,
                                issue("verify.issue.full_has_parent", currentId));
                    }
                    if (!currentId.equals(baseId)) {
                        return SnapshotChainHealth.broken(
                                lastSnapshotId,
                                expectedBaseId,
                                snapshotsChecked,
                                issue("verify.issue.full_wrong_base", currentId));
                    }
                    return SnapshotChainHealth.healthy(
                            lastSnapshotId,
                            expectedBaseId,
                            snapshotsChecked);
                }
                if (parentId.isBlank()) {
                    return SnapshotChainHealth.broken(
                            lastSnapshotId,
                            expectedBaseId,
                            snapshotsChecked,
                            issue("verify.issue.parent_id_missing", currentId));
                }
                expectedCurrentId = parentId;
                currentPath = locations.get(parentId);
                if (currentPath == null) {
                    return SnapshotChainHealth.broken(
                            lastSnapshotId,
                            expectedBaseId,
                            snapshotsChecked,
                            issue("verify.issue.parent_not_found", parentId));
                }
            }
        } catch (Exception ex) {
            return SnapshotChainHealth.broken(
                    lastSnapshotId,
                    expectedBaseId,
                    snapshotsChecked,
                    issue("verify.issue.failed", ex.getClass().getSimpleName()));
        }
        return SnapshotChainHealth.broken(
                lastSnapshotId,
                expectedBaseId,
                snapshotsChecked,
                issue("chain.issue.full_not_reached", expectedBaseId));
    }

    Map<String, Path> snapshotLocations() throws IOException {
        Map<String, Path> locations = new LinkedHashMap<>();
        for (Path root : searchableRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.find(
                    root,
                    MAX_SCAN_DEPTH,
                    (path, attributes) -> attributes.isRegularFile()
                            && path.getFileName().toString().equals(PROPERTIES_FILE))) {
                var iterator = stream.iterator();
                while (iterator.hasNext()) {
                    Path propertiesPath = iterator.next();
                    try {
                        Properties properties = loadProperties(propertiesPath);
                        String id = properties.getProperty("snapshot.id", "");
                        if (!id.isBlank()) {
                            locations.putIfAbsent(id, propertiesPath.getParent());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return locations;
    }

    Map<String, WorldMapping> readWorldMappings(Path snapshotPath, List<LocalizedMessage> errors) throws IOException {
        Path worldsPath = snapshotPath.resolve(WORLDS_FILE);
        if (!isRegularFile(worldsPath)) {
            errors.add(issue("verify.issue.missing_file", WORLDS_FILE, snapshotPath));
            return Map.of();
        }

        Map<String, WorldMapping> mappings = new LinkedHashMap<>();
        Set<String> storagePaths = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(worldsPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!"worldUuid\tworldKey\tworldName\tstoragePath\tsourcePath".equals(header)) {
                errors.add(issue("verify.issue.unsupported_format", WORLDS_FILE, snapshotPath));
                return Map.of();
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
                    errors.add(issue("verify.issue.malformed_line", WORLDS_FILE, lineNumber, snapshotPath));
                    continue;
                }
                String normalizedUuid;
                try {
                    normalizedUuid = UUID.fromString(parts[0]).toString();
                } catch (IllegalArgumentException ex) {
                    errors.add(issue("verify.issue.invalid_world_uuid_line", lineNumber, snapshotPath));
                    continue;
                }
                String expectedStoragePath = "worlds/" + normalizedUuid;
                if (!expectedStoragePath.equals(parts[3])) {
                    errors.add(issue("verify.issue.invalid_world_storage_line", lineNumber, snapshotPath));
                    continue;
                }
                WorldMapping mapping = new WorldMapping(parts[1], parts[2], parts[3], parts[4]);
                if (mappings.putIfAbsent(normalizedUuid, mapping) != null) {
                    errors.add(issue("verify.issue.duplicate_world_uuid", normalizedUuid));
                }
                if (!storagePaths.add(parts[3])) {
                    errors.add(issue("verify.issue.duplicate_world_storage", parts[3]));
                }
            }
        }
        return Map.copyOf(mappings);
    }

    private FileVerification verifyFiles(Path snapshotPath, Properties properties) throws IOException {
        Path entriesPath = snapshotPath.resolve(ENTRIES_FILE);
        if (!isRegularFile(entriesPath)) {
            return new FileVerification(
                    0, List.of(issue("verify.issue.missing_file", ENTRIES_FILE, snapshotPath)));
        }

        List<LocalizedMessage> errors = new ArrayList<>();
        int checked = 0;
        int entriesSeen = 0;
        Map<String, String> expectedChecksums = new LinkedHashMap<>();
        Map<String, WorldMapping> worldMappings = readWorldMappings(snapshotPath, errors);
        try (BufferedReader reader = Files.newBufferedReader(entriesPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!ENTRY_HEADER.equals(header)) {
                return new FileVerification(
                        0, List.of(issue("verify.issue.unsupported_format", ENTRIES_FILE, snapshotPath)));
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                entriesSeen++;
                String[] parts = line.split("\t", -1);
                if (parts.length != 11) {
                    errors.add(issue("verify.issue.malformed_line", ENTRIES_FILE, lineNumber, snapshotPath));
                    continue;
                }
                String relativePath = parts[0];
                validateEntryIdentity(parts, relativePath, worldMappings, errors);
                Path filesRoot = snapshotPath.resolve("files").toAbsolutePath().normalize();
                Path file = resolvePortableRelative(filesRoot, relativePath);
                if (file == null) {
                    errors.add(issue("verify.issue.entry_escapes", parts[0]));
                    continue;
                }
                String expectedHash = parts[10];
                if (!SHA256_PATTERN.matcher(expectedHash).matches()) {
                    errors.add(issue("verify.issue.invalid_sha256", relativePath));
                    continue;
                }
                if (expectedChecksums.putIfAbsent(relativePath, expectedHash.toLowerCase(Locale.ROOT)) != null) {
                    errors.add(issue("verify.issue.duplicate_entry", relativePath));
                    continue;
                }

                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException ex) {
                    errors.add(issue("verify.issue.snapshot_file_missing", parts[0]));
                    continue;
                }
                if (!attributes.isRegularFile()) {
                    errors.add(issue("verify.issue.not_regular_file", parts[0]));
                    continue;
                }
                try {
                    long expectedSize = Long.parseLong(parts[9]);
                    if (expectedSize < 0L) {
                        throw new NumberFormatException("negative size");
                    }
                    long actualSize = attributes.size();
                    if (expectedSize != actualSize) {
                        errors.add(issue("verify.issue.size_mismatch", parts[0], expectedSize, actualSize));
                        continue;
                    }
                    String actualHash = FileHashes.sha256(file);
                    if (!expectedHash.equalsIgnoreCase(actualHash)) {
                        errors.add(issue("verify.issue.sha256_mismatch", parts[0]));
                        continue;
                    }
                    checked++;
                } catch (NumberFormatException ex) {
                    errors.add(issue("verify.issue.invalid_size", parts[0]));
                }
            }
        }

        int expectedCount = readNonNegativeCount(properties, "changed.files", errors);
        if (expectedCount >= 0 && entriesSeen != expectedCount) {
            errors.add(issue("verify.issue.count_mismatch", ENTRIES_FILE, expectedCount, entriesSeen));
        }
        errors.addAll(verifyChecksumManifest(snapshotPath, expectedChecksums));
        errors.addAll(verifyDeletions(snapshotPath, properties, expectedChecksums.keySet(), worldMappings));
        if (!isRegularFile(snapshotPath.resolve(NOTES_FILE))) {
            errors.add(issue("verify.issue.missing_file", NOTES_FILE, snapshotPath));
        }
        return new FileVerification(checked, List.copyOf(errors));
    }

    private List<LocalizedMessage> verifyDeletions(
            Path snapshotPath,
            Properties properties,
            Set<String> changedPaths,
            Map<String, WorldMapping> worldMappings) throws IOException {
        Path deletionsPath = snapshotPath.resolve(DELETIONS_FILE);
        if (!isRegularFile(deletionsPath)) {
            return List.of(issue("verify.issue.missing_file", DELETIONS_FILE, snapshotPath));
        }
        List<LocalizedMessage> errors = new ArrayList<>();
        int entriesSeen = 0;
        Set<String> seenPaths = new LinkedHashSet<>();
        Path validationRoot = snapshotPath.resolve("deletion-path-validation").toAbsolutePath().normalize();
        try (BufferedReader reader = Files.newBufferedReader(deletionsPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!ENTRY_HEADER.equals(header)) {
                return List.of(issue("verify.issue.unsupported_format", DELETIONS_FILE, snapshotPath));
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                entriesSeen++;
                String[] parts = line.split("\t", -1);
                if (parts.length != 11) {
                    errors.add(issue("verify.issue.malformed_line", DELETIONS_FILE, lineNumber, snapshotPath));
                    continue;
                }
                String relativePath = parts[0];
                validateEntryIdentity(parts, relativePath, worldMappings, errors);
                if (resolvePortableRelative(validationRoot, relativePath) == null) {
                    errors.add(issue("verify.issue.deletion_escapes", parts[0]));
                }
                if (!seenPaths.add(relativePath)) {
                    errors.add(issue("verify.issue.duplicate_deletion", relativePath));
                }
                if (changedPaths.contains(relativePath)) {
                    errors.add(issue("verify.issue.changed_and_deleted", relativePath));
                }
                if (!SHA256_PATTERN.matcher(parts[10]).matches()) {
                    errors.add(issue("verify.issue.invalid_deletion_sha256", relativePath));
                }
            }
        }
        int expectedCount = readNonNegativeCount(properties, "deleted.files", errors);
        if (expectedCount >= 0 && entriesSeen != expectedCount) {
            errors.add(issue("verify.issue.count_mismatch", DELETIONS_FILE, expectedCount, entriesSeen));
        }
        return List.copyOf(errors);
    }

    private void validateEntryIdentity(
            String[] parts,
            String relativePath,
            Map<String, WorldMapping> worldMappings,
            List<LocalizedMessage> errors) {
        String normalizedUuid;
        try {
            normalizedUuid = UUID.fromString(parts[3]).toString();
        } catch (IllegalArgumentException ex) {
            errors.add(issue("verify.issue.invalid_world_uuid_entry", relativePath));
            return;
        }

        WorldMapping mapping = worldMappings.get(normalizedUuid);
        if (mapping == null) {
            errors.add(issue("verify.issue.unmapped_world_uuid", relativePath));
        } else if (!mapping.worldKey().equals(parts[2])
                || !mapping.worldName().equals(parts[1])
                || !mapping.storagePath().equals(parts[4])) {
            errors.add(issue("verify.issue.world_mapping_mismatch", relativePath));
        }

        try {
            BackupScope scope = BackupScope.valueOf(parts[5]);
            int regionX = Integer.parseInt(parts[6]);
            int regionZ = Integer.parseInt(parts[7]);
            long modifiedTime = Long.parseLong(parts[8]);
            if (modifiedTime < 0L) {
                throw new NumberFormatException("negative modified time");
            }
            String expectedPath = "worlds/" + normalizedUuid + "/" + scope.directoryName()
                    + "/r." + regionX + "." + regionZ + ".mca";
            if (!expectedPath.equals(relativePath)) {
                errors.add(issue("verify.issue.identity_path_mismatch", relativePath));
            }
        } catch (IllegalArgumentException ex) {
            errors.add(issue("verify.issue.invalid_entry_metadata", relativePath));
        }
    }

    private List<LocalizedMessage> verifyChecksumManifest(
            Path snapshotPath,
            Map<String, String> expectedChecksums)
            throws IOException {
        Path checksumPath = snapshotPath.resolve(CHECKSUMS_FILE);
        if (!isRegularFile(checksumPath)) {
            return List.of(issue("verify.issue.missing_file", CHECKSUMS_FILE, snapshotPath));
        }

        List<LocalizedMessage> errors = new ArrayList<>();
        Map<String, String> manifestChecksums = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(checksumPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (line.length() < 72
                        || line.charAt(64) != ' '
                        || line.charAt(65) != ' '
                        || !SHA256_PATTERN.matcher(line.substring(0, 64)).matches()
                        || !line.startsWith("files/", 66)) {
                    errors.add(issue("verify.issue.malformed_line", CHECKSUMS_FILE, lineNumber, snapshotPath));
                    continue;
                }
                String relativePath = line.substring(72);
                if (resolvePortableRelative(snapshotPath.resolve("files"), relativePath) == null) {
                    errors.add(issue("verify.issue.checksum_escapes", relativePath));
                    continue;
                }
                String previous = manifestChecksums.putIfAbsent(
                        relativePath, line.substring(0, 64).toLowerCase(Locale.ROOT));
                if (previous != null) {
                    errors.add(issue("verify.issue.duplicate_checksum", relativePath));
                }
            }
        }

        for (Map.Entry<String, String> expected : expectedChecksums.entrySet()) {
            String actual = manifestChecksums.get(expected.getKey());
            if (actual == null) {
                errors.add(issue("verify.issue.checksum_missing", expected.getKey()));
            } else if (!actual.equals(expected.getValue())) {
                errors.add(issue("verify.issue.checksum_mismatch", expected.getKey()));
            }
        }
        for (String manifestPath : manifestChecksums.keySet()) {
            if (!expectedChecksums.containsKey(manifestPath)) {
                errors.add(issue("verify.issue.checksum_unexpected", manifestPath));
            }
        }
        return List.copyOf(errors);
    }

    private int readNonNegativeCount(
            Properties properties,
            String key,
            List<LocalizedMessage> errors) {
        String rawValue = properties.getProperty(key, "0");
        try {
            int value = Integer.parseInt(rawValue);
            if (value < 0) {
                throw new NumberFormatException("negative count");
            }
            return value;
        } catch (NumberFormatException ex) {
            errors.add(issue("verify.issue.invalid_property", key, rawValue));
            return -1;
        }
    }

    private static boolean isRegularFile(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile();
        } catch (IOException ex) {
            return false;
        }
    }

    private static Path resolvePortableRelative(Path root, String relativePath) {
        if (relativePath == null
                || relativePath.isBlank()
                || relativePath.indexOf('\\') >= 0
                || relativePath.indexOf('\0') >= 0) {
            return null;
        }
        try {
            Path candidate = Path.of(relativePath).normalize();
            if (candidate.isAbsolute() || !candidate.toString().replace('\\', '/').equals(relativePath)) {
                return null;
            }
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path resolved = normalizedRoot.resolve(candidate).normalize();
            return resolved.startsWith(normalizedRoot) ? resolved : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(path)) {
            properties.load(inputStream);
        }
        return properties;
    }

    private static LocalizedMessage issue(String key, Object... arguments) {
        return LocalizedMessage.of(key, arguments);
    }

    record WorldMapping(String worldKey, String worldName, String storagePath, String sourcePath) {
    }

    private record FileVerification(int filesChecked, List<LocalizedMessage> errors) {
    }
}
