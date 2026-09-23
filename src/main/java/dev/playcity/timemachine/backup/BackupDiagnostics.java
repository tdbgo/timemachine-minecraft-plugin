package dev.playcity.timemachine.backup;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record BackupDiagnostics(
        Path storageRoot,
        List<Path> archiveRoots,
        boolean storageWritable,
        long usableSpaceBytes,
        long totalSpaceBytes,
        boolean databaseEnabled,
        ChangeDetectionMode changeDetectionMode,
        boolean baselineRequired,
        int trackedFiles,
        String lastSnapshotId,
        String baseSnapshotId,
        SnapshotChainHealth chainHealth,
        Instant latestSnapshotAt,
        int failedStagingDirectories,
        long failedStagingFiles,
        long failedStagingBytes) {
}
