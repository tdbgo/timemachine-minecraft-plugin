package dev.playcity.timemachine.backup;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CurrentIndexStore {
    static final String IDENTITY_SCHEME = "world-uuid-v1";
    private static final String V1_HEADER = "# Timemachine current index v1";
    private static final String V2_HEADER = "# Timemachine current index v2";
    private static final Pattern ENTRY_PATH_PATTERN = Pattern.compile(
            "^worlds/([0-9a-fA-F-]{36})/(region|entities|poi)/r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final Path path;
    private final Path backupPath;
    private volatile boolean primaryHealthy = true;

    public CurrentIndexStore(Path path) {
        this.path = path;
        this.backupPath = path.resolveSibling(path.getFileName() + ".bak");
    }

    public IndexState load() throws IOException {
        if (!Files.exists(path)) {
            if (Files.exists(backupPath)) {
                primaryHealthy = false;
                return loadFrom(backupPath, true);
            }
            return IndexState.baselineRequired(false, false);
        }

        try {
            primaryHealthy = true;
            return loadFrom(path, false);
        } catch (IOException primaryFailure) {
            if (!Files.exists(backupPath)) {
                throw primaryFailure;
            }
            try {
                primaryHealthy = false;
                return loadFrom(backupPath, true);
            } catch (IOException backupFailure) {
                primaryFailure.addSuppressed(backupFailure);
                throw primaryFailure;
            }
        }
    }

    public void save(IndexState state) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);

        try {
            write(temporary, state);
            forceFile(temporary);

            if (primaryHealthy && Files.exists(path)) {
                Files.copy(
                        path,
                        backupPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                forceFile(backupPath);
            }

            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(path.getParent());
            primaryHealthy = true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private IndexState loadFrom(Path source, boolean recoveredFromBackup) throws IOException {
        Map<String, TrackedFileMetadata> entries = new LinkedHashMap<>();
        String identityScheme = "";
        String lastSnapshotId = "";
        String baseSnapshotId = "";
        boolean baselineComplete = false;
        boolean headerSeen = false;

        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (!headerSeen) {
                    headerSeen = true;
                    if (V1_HEADER.equals(line)) {
                        return IndexState.baselineRequired(true, recoveredFromBackup);
                    }
                    if (!V2_HEADER.equals(line)) {
                        throw new IOException("Unsupported current-index header in " + source + ": " + line);
                    }
                    continue;
                }
                if (line.startsWith("# ")) {
                    String metadata = line.substring(2);
                    int separator = metadata.indexOf('=');
                    if (separator <= 0) {
                        continue;
                    }
                    String key = metadata.substring(0, separator);
                    String value = metadata.substring(separator + 1);
                    switch (key) {
                        case "identity-scheme" -> identityScheme = value;
                        case "baseline-complete" -> baselineComplete = Boolean.parseBoolean(value);
                        case "last-snapshot" -> lastSnapshotId = value;
                        case "base-snapshot" -> baseSnapshotId = value;
                        default -> {
                        }
                    }
                    continue;
                }
                if (line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\t", -1);
                if (parts.length != 11) {
                    throw new IOException("Malformed current-index entry at " + source + ":" + lineNumber);
                }
                try {
                    TrackedFileMetadata file = new TrackedFileMetadata(
                            parts[0],
                            parts[1],
                            parts[2],
                            parts[3],
                            parts[4],
                            BackupScope.valueOf(parts[5]),
                            Integer.parseInt(parts[6]),
                            Integer.parseInt(parts[7]),
                            Long.parseLong(parts[8]),
                            Long.parseLong(parts[9]),
                            parts[10]);
                    validateEntry(file, source, lineNumber);
                    if (entries.putIfAbsent(file.relativePath(), file) != null) {
                        throw new IOException(
                                "Duplicate current-index entry at " + source + ":" + lineNumber + ": "
                                        + file.relativePath());
                    }
                } catch (RuntimeException ex) {
                    throw new IOException("Invalid current-index entry at " + source + ":" + lineNumber, ex);
                }
            }
        }

        if (!headerSeen) {
            throw new IOException("Empty current-index file: " + source);
        }
        if (!IDENTITY_SCHEME.equals(identityScheme)) {
            return IndexState.baselineRequired(true, recoveredFromBackup);
        }
        if (baselineComplete && (lastSnapshotId.isBlank() || baseSnapshotId.isBlank())) {
            throw new IOException("Completed current-index is missing snapshot chain metadata: " + source);
        }
        return new IndexState(
                entries,
                lastSnapshotId,
                baseSnapshotId,
                !baselineComplete,
                false,
                recoveredFromBackup);
    }

    private void write(Path target, IndexState state) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                target,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            writer.write(V2_HEADER);
            writer.newLine();
            writer.write("# identity-scheme=" + IDENTITY_SCHEME);
            writer.newLine();
            writer.write("# baseline-complete=" + !state.baselineRequired());
            writer.newLine();
            writer.write("# last-snapshot=" + safe(state.lastSnapshotId()));
            writer.newLine();
            writer.write("# base-snapshot=" + safe(state.baseSnapshotId()));
            writer.newLine();

            for (TrackedFileMetadata metadata : state.entries().values().stream()
                    .sorted(Comparator.comparing(TrackedFileMetadata::relativePath))
                    .toList()) {
                validateEntry(metadata, target, 0);
                writer.write(String.join("\t",
                        safe(metadata.relativePath()),
                        safe(metadata.worldName()),
                        safe(metadata.worldKey()),
                        safe(metadata.worldUuid()),
                        safe(metadata.worldStoragePath()),
                        metadata.scope().name(),
                        Integer.toString(metadata.regionX()),
                        Integer.toString(metadata.regionZ()),
                        Long.toString(metadata.modifiedTime()),
                        Long.toString(metadata.size()),
                        safe(metadata.sha256())));
                writer.newLine();
            }
        }
    }

    private void forceFile(Path target) throws IOException {
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is not supported on every filesystem, notably on Windows.
        }
    }

    private void validateEntry(TrackedFileMetadata file, Path source, int lineNumber) throws IOException {
        Matcher matcher = ENTRY_PATH_PATTERN.matcher(file.relativePath());
        if (!matcher.matches()) {
            throw new IOException("Invalid current-index path at " + source + ":" + lineNumber);
        }
        String normalizedUuid;
        try {
            normalizedUuid = UUID.fromString(file.worldUuid()).toString();
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid world UUID at " + source + ":" + lineNumber, ex);
        }
        if (!matcher.group(1).equalsIgnoreCase(normalizedUuid)
                || !file.worldStoragePath().equals("worlds/" + normalizedUuid)
                || !matcher.group(2).equals(file.scope().directoryName())
                || Integer.parseInt(matcher.group(3)) != file.regionX()
                || Integer.parseInt(matcher.group(4)) != file.regionZ()) {
            throw new IOException("Inconsistent current-index identity at " + source + ":" + lineNumber);
        }
        if (file.modifiedTime() < 0L || file.size() < 0L) {
            throw new IOException("Negative current-index file metadata at " + source + ":" + lineNumber);
        }
        if (file.sha256() == null || !SHA256_PATTERN.matcher(file.sha256()).matches()) {
            throw new IOException("Invalid current-index SHA-256 at " + source + ":" + lineNumber);
        }
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    public record IndexState(
            Map<String, TrackedFileMetadata> entries,
            String lastSnapshotId,
            String baseSnapshotId,
            boolean baselineRequired,
            boolean legacyFormat,
            boolean recoveredFromBackup) {

        public IndexState {
            entries = Map.copyOf(new LinkedHashMap<>(entries));
            lastSnapshotId = lastSnapshotId == null ? "" : lastSnapshotId;
            baseSnapshotId = baseSnapshotId == null ? "" : baseSnapshotId;
        }

        public static IndexState baselineRequired(boolean legacyFormat, boolean recoveredFromBackup) {
            return new IndexState(Map.of(), "", "", true, legacyFormat, recoveredFromBackup);
        }

        public static IndexState committed(
                Map<String, TrackedFileMetadata> entries,
                String lastSnapshotId,
                String baseSnapshotId) {
            return new IndexState(entries, lastSnapshotId, baseSnapshotId, false, false, false);
        }

        public IndexState requiringNewBaseline() {
            return new IndexState(
                    entries,
                    lastSnapshotId,
                    baseSnapshotId,
                    true,
                    legacyFormat,
                    recoveredFromBackup);
        }

        public IndexState withValidatedBaseline() {
            if (lastSnapshotId.isBlank() || baseSnapshotId.isBlank()) {
                throw new IllegalStateException("A validated baseline requires snapshot chain metadata.");
            }
            return new IndexState(
                    entries,
                    lastSnapshotId,
                    baseSnapshotId,
                    false,
                    false,
                    false);
        }
    }
}
