package dev.playcity.timemachine.backup;

import dev.playcity.timemachine.config.TimeMachineSettings;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BackupFilePipeline {
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final long MIB = 1024L * 1024L;

    private final TimeMachineSettings settings;
    private final ThreadPoolExecutor copyExecutor;
    private final ProgressTracker progressTracker;
    private final BackupManager.MessageSink sink;

    BackupFilePipeline(
            TimeMachineSettings settings,
            ThreadPoolExecutor copyExecutor,
            ProgressTracker progressTracker,
            BackupManager.MessageSink sink) {
        this.settings = settings;
        this.copyExecutor = copyExecutor;
        this.progressTracker = progressTracker;
        this.sink = sink;
    }

    ScanResult scanAndCopy(
            List<BackupTargetWorld> worlds,
            SnapshotKind kind,
            boolean copyAllFiles,
            Path filesRoot,
            Map<String, TrackedFileMetadata> previousIndex) throws IOException {
        Map<String, TrackedFileMetadata> currentState = new LinkedHashMap<>();
        List<CopyCandidate> candidates = new ArrayList<>();
        List<ObservedFile> observedFiles = new ArrayList<>();
        Set<String> scannedScopePrefixes = new java.util.LinkedHashSet<>();
        List<BackupScope> scopes = settings.scopes().stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();

        progressTracker.beginPhase(OperationProgress.Phase.SCANNING, 0, 0L, "Discovering region files");
        sink.accept("backup.scanning");
        for (BackupTargetWorld world : worlds) {
            for (BackupScope scope : scopes) {
                scanScope(world, scope, previousIndex, observedFiles, scannedScopePrefixes);
            }
        }
        observedFiles.sort(Comparator.comparing(file -> file.metadata().relativePath()));

        if (copyAllFiles) {
            for (ObservedFile observed : observedFiles) {
                currentState.put(observed.metadata().relativePath(), observed.metadata());
                candidates.add(new CopyCandidate(observed.source(), observed.metadata()));
            }
        } else if (settings.changeDetectionMode() == ChangeDetectionMode.SAFE) {
            hashAndSelectChangedFiles(observedFiles, previousIndex, currentState, candidates);
        } else {
            selectChangedFilesFast(observedFiles, previousIndex, currentState, candidates);
        }

        long estimatedCopyBytes = sumCopyBytes(candidates);
        ensureFreeSpace(estimatedCopyBytes);

        progressTracker.beginPhase(
                OperationProgress.Phase.COPYING,
                candidates.size(),
                estimatedCopyBytes,
                "Copying changed region files");
        sink.accept("backup.copying", candidates.size(), humanBytes(estimatedCopyBytes));
        List<TrackedFileMetadata> changedEntries = copyCandidates(candidates, filesRoot, currentState);

        List<TrackedFileMetadata> deletedEntries = kind.isFullBaseline()
                ? new ArrayList<>()
                : new ArrayList<>(BackupIndexPlanner.findDeletions(
                        previousIndex,
                        currentState,
                        scannedScopePrefixes));
        changedEntries.sort(Comparator.comparing(TrackedFileMetadata::relativePath));
        deletedEntries.sort(Comparator.comparing(TrackedFileMetadata::relativePath));
        return new ScanResult(
                Collections.unmodifiableMap(new LinkedHashMap<>(currentState)),
                List.copyOf(changedEntries),
                List.copyOf(deletedEntries),
                Set.copyOf(scannedScopePrefixes));
    }

    private List<TrackedFileMetadata> copyCandidates(
            List<CopyCandidate> candidates,
            Path filesRoot,
            Map<String, TrackedFileMetadata> currentState)
            throws IOException {
        List<TrackedFileMetadata> changedEntries = new ArrayList<>(candidates.size());
        int batchSize = Math.max(1, settings.copyThreads() * 2);
        for (int offset = 0; offset < candidates.size(); offset += batchSize) {
            int end = Math.min(candidates.size(), offset + batchSize);
            List<Future<TrackedFileMetadata>> futures = new ArrayList<>(end - offset);
            try {
                for (CopyCandidate candidate : candidates.subList(offset, end)) {
                    Path destination = filesRoot.resolve(candidate.metadata().relativePath()).normalize();
                    if (!destination.startsWith(filesRoot.normalize())) {
                        throw new IOException("Backup entry escapes the staging directory: "
                                + candidate.metadata().relativePath());
                    }
                    futures.add(copyExecutor.submit(
                            () -> StableFileCopier.copyStable(candidate.source(), destination, candidate.metadata())));
                }
                for (Future<TrackedFileMetadata> future : futures) {
                    TrackedFileMetadata copied = await(future, "copy");
                    currentState.put(copied.relativePath(), copied);
                    changedEntries.add(copied);
                    progressTracker.advance(copied.size(), copied.relativePath());
                }
            } catch (IOException | RuntimeException ex) {
                futures.forEach(future -> future.cancel(true));
                throw ex;
            }
        }
        return changedEntries;
    }

    private void scanScope(
            BackupTargetWorld world,
            BackupScope scope,
            Map<String, TrackedFileMetadata> previousIndex,
            List<ObservedFile> observedFiles,
            Set<String> scannedScopePrefixes) throws IOException {
        String scopePrefix = world.storagePath() + "/" + scope.directoryName() + "/";
        Path scopeDirectory = world.path().resolve(scope.directoryName());
        if (!Files.isDirectory(scopeDirectory)) {
            boolean previouslyTracked = previousIndex.keySet().stream().anyMatch(path -> path.startsWith(scopePrefix));
            if (previouslyTracked) {
                throw new IOException("Previously tracked scope directory is unavailable: " + scopeDirectory);
            }
            scannedScopePrefixes.add(scopePrefix);
            return;
        }
        scannedScopePrefixes.add(scopePrefix);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(scopeDirectory, "*.mca")) {
            for (Path file : stream) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Backup scan was interrupted.");
                }
                observedFiles.add(new ObservedFile(file, metadataForFile(world, scope, file)));
            }
        }
    }

    private void hashAndSelectChangedFiles(
            List<ObservedFile> observedFiles,
            Map<String, TrackedFileMetadata> previousIndex,
            Map<String, TrackedFileMetadata> currentState,
            List<CopyCandidate> candidates) throws IOException {
        long totalBytes = sumObservedBytes(observedFiles);
        progressTracker.beginPhase(
                OperationProgress.Phase.HASHING,
                observedFiles.size(),
                totalBytes,
                "Comparing SHA-256 fingerprints");
        sink.accept("backup.hashing", observedFiles.size(), humanBytes(totalBytes));

        int batchSize = Math.max(1, settings.copyThreads() * 2);
        for (int offset = 0; offset < observedFiles.size(); offset += batchSize) {
            int end = Math.min(observedFiles.size(), offset + batchSize);
            List<ObservedFile> batch = observedFiles.subList(offset, end);
            List<Future<TrackedFileMetadata>> futures = new ArrayList<>(batch.size());
            try {
                for (ObservedFile observed : batch) {
                    futures.add(copyExecutor.submit(() -> hashObservedFile(observed)));
                }
                for (int index = 0; index < futures.size(); index++) {
                    ObservedFile observed = batch.get(index);
                    TrackedFileMetadata hashed = await(futures.get(index), "hashing");
                    currentState.put(hashed.relativePath(), hashed);
                    if (BackupChangeDetector.changed(
                            ChangeDetectionMode.SAFE,
                            previousIndex.get(hashed.relativePath()),
                            hashed)) {
                        candidates.add(new CopyCandidate(observed.source(), hashed));
                    }
                    progressTracker.advance(hashed.size(), hashed.relativePath());
                }
            } catch (IOException | RuntimeException ex) {
                futures.forEach(future -> future.cancel(true));
                throw ex;
            }
        }
    }

    private void selectChangedFilesFast(
            List<ObservedFile> observedFiles,
            Map<String, TrackedFileMetadata> previousIndex,
            Map<String, TrackedFileMetadata> currentState,
            List<CopyCandidate> candidates) {
        for (ObservedFile observed : observedFiles) {
            TrackedFileMetadata metadata = observed.metadata();
            TrackedFileMetadata previous = previousIndex.get(metadata.relativePath());
            if (BackupChangeDetector.changed(ChangeDetectionMode.FAST, previous, metadata)) {
                currentState.put(metadata.relativePath(), metadata);
                candidates.add(new CopyCandidate(observed.source(), metadata));
            } else {
                currentState.put(
                        metadata.relativePath(),
                        metadata.withFileState(metadata.modifiedTime(), metadata.size(), previous.sha256()));
            }
        }
    }

    private TrackedFileMetadata metadataForFile(BackupTargetWorld world, BackupScope scope, Path file)
            throws IOException {
        Matcher matcher = REGION_FILE_PATTERN.matcher(file.getFileName().toString());
        if (!matcher.matches()) {
            throw new IOException("Unsupported region filename: " + file.getFileName());
        }
        BasicFileAttributes attributes = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Expected a regular region file: " + file);
        }

        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        String relativePath = world.storagePath() + "/" + scope.directoryName() + "/" + file.getFileName();
        return new TrackedFileMetadata(
                relativePath,
                world.name(),
                world.key(),
                world.uuid(),
                world.storagePath(),
                scope,
                regionX,
                regionZ,
                attributes.lastModifiedTime().toMillis(),
                attributes.size(),
                "");
    }

    private TrackedFileMetadata hashObservedFile(ObservedFile observed) throws IOException {
        FileHashes.HashedFile hashed = FileHashes.hashStable(observed.source());
        return observed.metadata().withFileState(hashed.modifiedTime(), hashed.size(), hashed.sha256());
    }

    private long sumObservedBytes(List<ObservedFile> observedFiles) throws IOException {
        long total = 0L;
        for (ObservedFile observed : observedFiles) {
            total = addSize(total, observed.metadata().size(), "Tracked file size overflowed the supported range.");
        }
        return total;
    }

    private long sumCopyBytes(List<CopyCandidate> candidates) throws IOException {
        long total = 0L;
        for (CopyCandidate candidate : candidates) {
            total = addSize(total, candidate.metadata().size(), "Estimated backup size overflowed the supported range.");
        }
        return total;
    }

    private long addSize(long total, long size, String message) throws IOException {
        try {
            return Math.addExact(total, size);
        } catch (ArithmeticException ex) {
            throw new IOException(message, ex);
        }
    }

    private TrackedFileMetadata await(Future<TrackedFileMetadata> future, String operation) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Backup " + operation + " was interrupted.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Backup " + operation + " failed: " + exceptionMessage(cause), cause);
        }
    }

    private void ensureFreeSpace(long estimatedCopyBytes) throws IOException {
        ensureFreeSpace(settings, estimatedCopyBytes);
    }

    static void ensureMinimumFreeSpace(TimeMachineSettings settings) throws IOException {
        ensureFreeSpace(settings, 0L);
    }

    private static void ensureFreeSpace(TimeMachineSettings settings, long estimatedCopyBytes) throws IOException {
        FileStore fileStore = Files.getFileStore(settings.storageRoot());
        long required;
        try {
            required = Math.addExact(settings.minimumFreeSpaceBytes(), estimatedCopyBytes);
        } catch (ArithmeticException ex) {
            throw new IOException("Required backup space overflowed the supported range.", ex);
        }
        long usable = fileStore.getUsableSpace();
        if (usable < required) {
            throw new IOException("Insufficient free space: usable=" + (usable / MIB)
                    + " MiB, required=" + (required / MIB) + " MiB.");
        }
    }

    private String humanBytes(long bytes) {
        if (bytes >= 1024L * MIB) {
            return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0D * MIB));
        }
        if (bytes >= MIB) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (double) MIB);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0D);
        }
        return bytes + " B";
    }

    private String exceptionMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    record ScanResult(
            Map<String, TrackedFileMetadata> currentState,
            List<TrackedFileMetadata> changedEntries,
            List<TrackedFileMetadata> deletedEntries,
            Set<String> scannedScopePrefixes) {
    }

    private record CopyCandidate(Path source, TrackedFileMetadata metadata) {
    }

    private record ObservedFile(Path source, TrackedFileMetadata metadata) {
    }
}
