package dev.playcity.timemachine.backup;

import java.util.Objects;

final class BackupChangeDetector {
    private BackupChangeDetector() {
    }

    static boolean changed(
            ChangeDetectionMode mode,
            TrackedFileMetadata previous,
            TrackedFileMetadata current) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(current, "current");
        if (previous == null || previous.sha256() == null || previous.sha256().isBlank()) {
            return true;
        }
        if (mode == ChangeDetectionMode.SAFE) {
            return current.sha256() == null
                    || current.sha256().isBlank()
                    || !previous.sha256().equalsIgnoreCase(current.sha256());
        }
        return previous.modifiedTime() != current.modifiedTime()
                || previous.size() != current.size();
    }
}
