package dev.playcity.timemachine.db;

public record ReconcileReport(
        int indexedSnapshots,
        int localSnapshots,
        int archivedSnapshots,
        int discoveredSnapshots,
        int newlyMissingSnapshots,
        int missingSnapshots) {
}
