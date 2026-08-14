package dev.playcity.timemachine.backup;

import dev.playcity.timemachine.config.TimeMachineSettings;
import dev.playcity.timemachine.db.SnapshotMetadataIndex;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class BackupRuntimeContext {
    final TimeMachineSettings settings;
    final CurrentIndexStore indexStore;
    final SnapshotStore snapshotStore;
    final SnapshotMetadataIndex metadataIndex;
    final ThreadPoolExecutor copyExecutor;
    final AtomicReference<CurrentIndexStore.IndexState> indexState;
    final AtomicReference<SnapshotChainHealth> chainHealth;

    BackupRuntimeContext(
            TimeMachineSettings settings,
            CurrentIndexStore indexStore,
            SnapshotStore snapshotStore,
            SnapshotMetadataIndex metadataIndex,
            ThreadPoolExecutor copyExecutor,
            CurrentIndexStore.IndexState indexState,
            SnapshotChainHealth chainHealth) {
        this.settings = settings;
        this.indexStore = indexStore;
        this.snapshotStore = snapshotStore;
        this.metadataIndex = metadataIndex;
        this.copyExecutor = copyExecutor;
        this.indexState = new AtomicReference<>(indexState);
        this.chainHealth = new AtomicReference<>(chainHealth);
    }

    void stopCopyNow() {
        copyExecutor.shutdownNow();
    }

    void close(boolean immediate) {
        if (immediate) {
            copyExecutor.shutdownNow();
        } else {
            copyExecutor.shutdown();
        }
        try {
            copyExecutor.awaitTermination(immediate ? 2 : 5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (metadataIndex != null) {
            metadataIndex.close();
        }
    }
}
