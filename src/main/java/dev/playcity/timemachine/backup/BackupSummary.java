package dev.playcity.timemachine.backup;

import java.nio.file.Path;
import java.time.Instant;

public record BackupSummary(
        BackupState state,
        String snapshotId,
        String trigger,
        Instant createdAt,
        boolean fullBackup,
        int changedFiles,
        int changedRegionSets,
        int deletedFiles,
        boolean noChanges,
        Path snapshotDirectory,
        String message) {

    public static BackupSummary running(BackupRequest request) {
        return new BackupSummary(
                BackupState.RUNNING,
                null,
                request.trigger(),
                Instant.now(),
                request.fullBackup(),
                0,
                0,
                0,
                false,
                null,
                request.message());
    }

    public static BackupSummary failed(BackupRequest request, String failureMessage) {
        return new BackupSummary(
                BackupState.FAILED,
                null,
                request.trigger(),
                Instant.now(),
                request.fullBackup(),
                0,
                0,
                0,
                false,
                null,
                failureMessage);
    }
}
