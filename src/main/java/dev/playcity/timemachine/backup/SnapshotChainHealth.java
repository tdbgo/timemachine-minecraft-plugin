package dev.playcity.timemachine.backup;

import dev.playcity.timemachine.i18n.LocalizedMessage;
import java.util.Objects;

public record SnapshotChainHealth(
        Status status,
        String lastSnapshotId,
        String baseSnapshotId,
        int snapshotsChecked,
        LocalizedMessage issue) {

    public SnapshotChainHealth {
        Objects.requireNonNull(status, "status");
        lastSnapshotId = safe(lastSnapshotId);
        baseSnapshotId = safe(baseSnapshotId);
        if (snapshotsChecked < 0) {
            throw new IllegalArgumentException("snapshotsChecked must not be negative");
        }
        if (status == Status.BROKEN && issue == null) {
            throw new IllegalArgumentException("A broken chain must include an issue");
        }
    }

    public static SnapshotChainHealth healthy(
            String lastSnapshotId,
            String baseSnapshotId,
            int snapshotsChecked) {
        return new SnapshotChainHealth(
                Status.HEALTHY,
                lastSnapshotId,
                baseSnapshotId,
                snapshotsChecked,
                null);
    }

    public static SnapshotChainHealth baselineRequired(
            String lastSnapshotId,
            String baseSnapshotId) {
        return new SnapshotChainHealth(
                Status.BASELINE_REQUIRED,
                lastSnapshotId,
                baseSnapshotId,
                0,
                null);
    }

    public static SnapshotChainHealth broken(
            String lastSnapshotId,
            String baseSnapshotId,
            int snapshotsChecked,
            LocalizedMessage issue) {
        return new SnapshotChainHealth(
                Status.BROKEN,
                lastSnapshotId,
                baseSnapshotId,
                snapshotsChecked,
                issue);
    }

    public boolean healthy() {
        return status == Status.HEALTHY;
    }

    public boolean broken() {
        return status == Status.BROKEN;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public enum Status {
        HEALTHY,
        BASELINE_REQUIRED,
        BROKEN
    }
}
