package dev.playcity.timemachine.schedule;

import dev.playcity.timemachine.backup.BackupRequest;
import java.time.Instant;
import java.util.Optional;

interface BackupScheduleOperations {
    Optional<Instant> latestSnapshotTime();

    Optional<Instant> latestFullSnapshotTime();

    boolean startScheduledBackup(BackupRequest request);
}
