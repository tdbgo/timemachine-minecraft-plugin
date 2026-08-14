package dev.playcity.timemachine.backup;

import java.time.Duration;
import java.time.Instant;

public record OperationProgress(
        String operation,
        Phase phase,
        Instant startedAt,
        Instant updatedAt,
        int completedFiles,
        int totalFiles,
        long completedBytes,
        long totalBytes,
        String detail) {

    public static OperationProgress idle() {
        Instant now = Instant.now();
        return new OperationProgress("idle", Phase.IDLE, now, now, 0, 0, 0L, 0L, "");
    }

    public boolean active() {
        return switch (phase) {
            case PREPARING, SAVING, SCANNING, HASHING, COPYING, COMMITTING, VERIFYING, RECONCILING, PRUNING -> true;
            case IDLE, COMPLETE, FAILED, CANCELLED -> false;
        };
    }

    public double completionRatio() {
        if (totalBytes > 0L) {
            return Math.min(1.0D, (double) completedBytes / totalBytes);
        }
        if (totalFiles > 0) {
            return Math.min(1.0D, (double) completedFiles / totalFiles);
        }
        return 0.0D;
    }

    public Duration elapsed(Instant now) {
        Instant end = active() ? now : updatedAt;
        if (end.isBefore(startedAt)) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, end);
    }

    public enum Phase {
        IDLE,
        PREPARING,
        SAVING,
        SCANNING,
        HASHING,
        COPYING,
        COMMITTING,
        VERIFYING,
        RECONCILING,
        PRUNING,
        COMPLETE,
        FAILED,
        CANCELLED
    }
}
