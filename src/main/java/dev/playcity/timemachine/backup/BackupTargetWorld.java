package dev.playcity.timemachine.backup;

import java.nio.file.Path;

record BackupTargetWorld(
        String name,
        String key,
        String uuid,
        String storagePath,
        Path path,
        SnapshotStore.SnapshotWorld snapshotWorld) {
}
