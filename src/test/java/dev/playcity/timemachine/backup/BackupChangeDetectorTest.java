package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BackupChangeDetectorTest {
    private static final String FIRST_HASH = "a".repeat(64);
    private static final String SECOND_HASH = "b".repeat(64);

    @Test
    void safeModeDetectsContentChangeWhenTimestampAndSizeArePreserved() {
        TrackedFileMetadata previous = metadata(100L, 8192L, FIRST_HASH);
        TrackedFileMetadata current = metadata(100L, 8192L, SECOND_HASH);

        assertTrue(BackupChangeDetector.changed(ChangeDetectionMode.SAFE, previous, current));
        assertFalse(BackupChangeDetector.changed(ChangeDetectionMode.FAST, previous, current));
    }

    @Test
    void safeModeSkipsFilesWhoseHashIsUnchanged() {
        TrackedFileMetadata previous = metadata(100L, 8192L, FIRST_HASH);
        TrackedFileMetadata current = metadata(200L, 8192L, FIRST_HASH);

        assertFalse(BackupChangeDetector.changed(ChangeDetectionMode.SAFE, previous, current));
        assertTrue(BackupChangeDetector.changed(ChangeDetectionMode.FAST, previous, current));
    }

    private TrackedFileMetadata metadata(long modifiedTime, long size, String sha256) {
        return new TrackedFileMetadata(
                "worlds/00000000-0000-0000-0000-000000000001/region/r.0.0.mca",
                "world",
                "minecraft:overworld",
                "00000000-0000-0000-0000-000000000001",
                "worlds/00000000-0000-0000-0000-000000000001",
                BackupScope.REGION,
                0,
                0,
                modifiedTime,
                size,
                sha256);
    }
}
