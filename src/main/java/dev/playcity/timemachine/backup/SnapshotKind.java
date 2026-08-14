package dev.playcity.timemachine.backup;

public enum SnapshotKind {
    FULL,
    INCREMENTAL,
    SCOPED_FULL;

    public boolean isFullBaseline() {
        return this == FULL;
    }
}
