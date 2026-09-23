package dev.playcity.timemachine.backup;

import dev.playcity.timemachine.config.TimeMachineSettings;
import dev.playcity.timemachine.db.ReconcileReport;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

final class BackupMaintenanceService {
    private static final long MIB = 1024L * 1024L;

    private final Logger logger;
    private final ProgressTracker progressTracker;
    private final AtomicReference<ChainRetentionService.PrunePlan> pendingPrunePlan = new AtomicReference<>();
    private final AtomicReference<SnapshotStore.FailedStagingPlan> pendingCleanupPlan = new AtomicReference<>();

    BackupMaintenanceService(Logger logger, ProgressTracker progressTracker) {
        this.logger = logger;
        this.progressTracker = progressTracker;
    }

    ChainRetentionService.PrunePlan pendingPrunePlan() {
        return pendingPrunePlan.get();
    }

    SnapshotStore.FailedStagingPlan pendingCleanupPlan() {
        return pendingCleanupPlan.get();
    }

    void clearPendingPlans() {
        pendingPrunePlan.set(null);
        pendingCleanupPlan.set(null);
    }

    void executeReconcile(BackupRuntimeContext context, BackupManager.MessageSink sink) {
        try {
            ChainRefresh chainRefresh = refreshActiveChain(context);
            ReconcileReport report = context.metadataIndex.reconcile();
            progressTracker.complete("Indexed " + report.indexedSnapshots() + " snapshot(s)");
            sink.accept(
                    "backup.reconcile_completed",
                    report.indexedSnapshots(),
                    report.localSnapshots(),
                    report.archivedSnapshots(),
                    report.discoveredSnapshots(),
                    report.newlyMissingSnapshots(),
                    report.missingSnapshots());
            reportChainRefresh(chainRefresh, sink);
        } catch (Exception ex) {
            logger.warning("Reconcile failed: " + exceptionMessage(ex));
            progressTracker.fail(exceptionMessage(ex));
            sink.accept("backup.reconcile_failed", exceptionMessage(ex));
        }
    }

    void executeVerify(
            BackupRuntimeContext context,
            String snapshotId,
            BackupManager.MessageSink sink) {
        SnapshotStore.VerificationResult result = context.snapshotStore.verifySnapshot(snapshotId);
        if (result.valid()) {
            progressTracker.complete(snapshotId);
            sink.accept("backup.verify_passed", result.snapshotsChecked(), result.filesChecked());
            if (result.chain().size() <= 10) {
                sink.accept("backup.restore_chain", String.join(" -> ", result.chain()));
            } else {
                sink.accept(
                        "backup.restore_chain_long",
                        result.chain().size(),
                        result.chain().getFirst(),
                        result.chain().getLast());
            }
            return;
        }
        progressTracker.fail("Snapshot verification failed: " + snapshotId);
        sink.accept(
                "backup.verify_failed",
                result.snapshotsChecked(),
                result.filesChecked(),
                result.issues().size());
        result.issues().stream()
                .limit(10)
                .forEach(issue -> sink.accept(issue.key(), issue.argumentsArray()));
        if (result.issues().size() > 10) {
            sink.accept("backup.additional_errors", result.issues().size() - 10);
        }
    }

    void executePrunePlan(BackupRuntimeContext context, BackupManager.MessageSink sink) {
        try {
            ChainRetentionService service = retentionService(context);
            ChainRetentionService.PrunePlan plan = service.plan(
                    retentionPolicy(context.settings),
                    context.indexState.get().baseSnapshotId(),
                    Instant.now());
            plan.warnings().forEach(warning -> sink.accept(warning.key(), warning.argumentsArray()));
            if (plan.deleteChains().isEmpty()) {
                pendingPrunePlan.set(null);
                progressTracker.complete("No complete chains are eligible");
                sink.accept("backup.prune_none");
                return;
            }
            pendingPrunePlan.set(plan);
            progressTracker.complete("Awaiting confirmation " + plan.token());
            sink.accept(
                    "backup.prune_preview",
                    plan.deleteChains().size(),
                    plan.snapshotsToDelete(),
                    humanBytes(plan.reclaimableBytes()));
            plan.deleteChains().stream().limit(5).forEach(chain -> sink.accept(
                    pruneChainMessageKey(chain.reason()),
                    chain.baseSnapshotId(),
                    chain.snapshots()));
            if (plan.deleteChains().size() > 5) {
                sink.accept("backup.additional_chains", plan.deleteChains().size() - 5);
            }
            sink.accept("backup.prune_confirm", plan.token());
        } catch (Exception ex) {
            pendingPrunePlan.set(null);
            progressTracker.fail(exceptionMessage(ex));
            sink.accept("backup.prune_plan_failed", exceptionMessage(ex));
        }
    }

    void executePruneConfirm(
            BackupRuntimeContext context,
            ChainRetentionService.PrunePlan plan,
            BackupManager.MessageSink sink) {
        try {
            ChainRetentionService.PruneResult result = retentionService(context).apply(
                    plan,
                    retentionPolicy(context.settings),
                    context.indexState.get().baseSnapshotId());
            pendingPrunePlan.compareAndSet(plan, null);
            reconcileAfterPrune(context);
            progressTracker.complete("Deleted " + result.deletedChains() + " complete chain(s)");
            sink.accept(
                    "backup.prune_completed",
                    result.deletedChains(),
                    result.deletedSnapshots(),
                    humanBytes(result.reclaimedBytes()));
            if (result.recoverableTrash() != null) {
                sink.accept("backup.prune_trash", result.recoverableTrash());
            }
        } catch (Exception ex) {
            pendingPrunePlan.compareAndSet(plan, null);
            progressTracker.fail(exceptionMessage(ex));
            sink.accept("backup.prune_failed", exceptionMessage(ex));
        }
    }

    void executeCleanupPlan(BackupRuntimeContext context, BackupManager.MessageSink sink) {
        try {
            SnapshotStore.FailedStagingPlan plan = context.snapshotStore.planFailedStagingCleanup();
            if (plan.entries().isEmpty()) {
                pendingCleanupPlan.set(null);
                progressTracker.complete("No failed staging directories were found");
                sink.accept("backup.cleanup_none");
                return;
            }
            pendingCleanupPlan.set(plan);
            progressTracker.complete("Awaiting cleanup confirmation " + plan.token());
            sink.accept(
                    "backup.cleanup_preview",
                    plan.entries().size(),
                    plan.files(),
                    humanBytes(plan.bytes()));
            plan.entries().stream().limit(5).forEach(entry -> sink.accept(
                    "backup.cleanup_entry",
                    entry.directoryName(),
                    entry.files(),
                    humanBytes(entry.bytes())));
            if (plan.entries().size() > 5) {
                sink.accept("backup.additional_staging", plan.entries().size() - 5);
            }
            sink.accept("backup.cleanup_confirm", plan.token());
        } catch (Exception ex) {
            pendingCleanupPlan.set(null);
            progressTracker.fail(exceptionMessage(ex));
            sink.accept("backup.cleanup_plan_failed", exceptionMessage(ex));
        }
    }

    void executeCleanupConfirm(
            BackupRuntimeContext context,
            SnapshotStore.FailedStagingPlan plan,
            BackupManager.MessageSink sink) {
        try {
            SnapshotStore.FailedStagingCleanupResult result =
                    context.snapshotStore.cleanupFailedStaging(plan);
            pendingCleanupPlan.compareAndSet(plan, null);
            progressTracker.complete("Deleted " + result.directories() + " failed staging directories");
            sink.accept(
                    "backup.cleanup_completed",
                    result.directories(),
                    result.files(),
                    humanBytes(result.bytes()));
        } catch (Exception ex) {
            pendingCleanupPlan.compareAndSet(plan, null);
            progressTracker.fail(exceptionMessage(ex));
            sink.accept("backup.cleanup_failed", exceptionMessage(ex));
        }
    }

    void runAutomaticRetention(BackupRuntimeContext context, BackupManager.MessageSink sink) {
        if (!context.settings.retention().enabled()) {
            return;
        }
        try {
            ChainRetentionService service = retentionService(context);
            ChainRetentionService.PrunePlan plan = service.plan(
                    retentionPolicy(context.settings),
                    context.indexState.get().baseSnapshotId(),
                    Instant.now());
            if (plan.deleteChains().isEmpty()) {
                return;
            }
            progressTracker.beginPhase(
                    OperationProgress.Phase.PRUNING,
                    plan.snapshotsToDelete(),
                    plan.reclaimableBytes(),
                    "Verifying retained chains before automatic retention");
            ChainRetentionService.PruneResult result = service.apply(
                    plan,
                    retentionPolicy(context.settings),
                    context.indexState.get().baseSnapshotId());
            reconcileAfterPrune(context);
            sink.accept(
                    "backup.retention_pruned",
                    result.deletedChains(),
                    result.deletedSnapshots(),
                    humanBytes(result.reclaimedBytes()));
            if (result.recoverableTrash() != null) {
                logger.warning("Retention trash cleanup was incomplete; data remains at "
                        + result.recoverableTrash());
            }
        } catch (Exception ex) {
            logger.warning("Automatic retention skipped without affecting the backup: "
                    + exceptionMessage(ex));
            sink.accept("backup.retention_warning");
        }
    }

    void recordSnapshotMetadata(
            BackupRuntimeContext context,
            String snapshotId,
            Instant createdAt,
            BackupRequest request,
            SnapshotKind kind,
            Path snapshotDirectory,
            Path snapshotRelativePath,
            int changedFiles,
            int changedRegionSets,
            int deletedFiles) {
        if (context.metadataIndex == null) {
            return;
        }
        try {
            context.metadataIndex.recordSnapshot(
                    snapshotId,
                    createdAt,
                    request.trigger(),
                    kind,
                    snapshotDirectory,
                    snapshotRelativePath,
                    changedFiles,
                    changedRegionSets,
                    deletedFiles,
                    request.message());
        } catch (Exception ex) {
            logger.warning("Snapshot metadata index update failed for " + snapshotId + ": "
                    + exceptionMessage(ex));
        }
    }

    List<SnapshotStore.SnapshotHistoryEntry> loadHistory(BackupRuntimeContext context, int limit) {
        if (context.metadataIndex != null) {
            List<SnapshotStore.SnapshotHistoryEntry> history = context.metadataIndex.loadHistory(limit);
            if (!history.isEmpty()) {
                return history;
            }
        }
        return context.snapshotStore.loadSearchableHistory(limit);
    }

    private ChainRetentionService retentionService(BackupRuntimeContext context) {
        return new ChainRetentionService(context.settings.storageRoot(), context.settings.archiveRoots());
    }

    ChainRefresh refreshActiveChain(BackupRuntimeContext context) {
        CurrentIndexStore.IndexState state = context.indexState.get();
        SnapshotChainHealth previous = context.chainHealth.get();

        if (state.baselineRequired()) {
            if (!previous.broken()
                    || state.lastSnapshotId().isBlank()
                    || state.baseSnapshotId().isBlank()
                    || state.legacyFormat()
                    || state.recoveredFromBackup()) {
                return new ChainRefresh(previous, false, false);
            }

            SnapshotChainHealth inspected = context.snapshotStore.inspectRecordedChain(state);
            if (inspected.healthy()) {
                context.indexState.set(state.withValidatedBaseline());
                context.chainHealth.set(inspected);
                return new ChainRefresh(inspected, true, false);
            }
            context.chainHealth.set(inspected);
            return new ChainRefresh(inspected, false, false);
        }

        SnapshotChainHealth inspected = context.snapshotStore.inspectRecordedChain(state);
        boolean newlyBroken = inspected.broken();
        if (newlyBroken) {
            context.indexState.set(state.requiringNewBaseline());
        }
        context.chainHealth.set(inspected);
        return new ChainRefresh(inspected, false, newlyBroken);
    }

    private void reportChainRefresh(ChainRefresh refresh, BackupManager.MessageSink sink) {
        SnapshotChainHealth health = refresh.health();
        if (health.healthy()) {
            sink.accept(
                    refresh.restored() ? "backup.reconcile_chain_restored" : "backup.reconcile_chain_healthy",
                    health.snapshotsChecked(),
                    health.baseSnapshotId());
            return;
        }
        if (health.broken()) {
            sink.accept("backup.reconcile_chain_broken", health.snapshotsChecked());
            sink.accept(health.issue().key(), health.issue().argumentsArray());
            sink.accept("backup.reconcile_baseline_required");
            if (refresh.newlyBroken()) {
                logger.warning("Reconcile detected an incomplete active snapshot chain; incremental backups are blocked "
                        + "until the chain is restored or a new FULL baseline succeeds.");
            }
            return;
        }
        sink.accept("backup.reconcile_chain_pending");
    }

    private ChainRetentionService.Policy retentionPolicy(TimeMachineSettings settings) {
        TimeMachineSettings.RetentionSettings retention = settings.retention();
        return new ChainRetentionService.Policy(
                retention.maxChains(),
                retention.maxAgeDays(),
                retention.minimumChains(),
                Set.copyOf(retention.pinnedSnapshots()));
    }

    private void reconcileAfterPrune(BackupRuntimeContext context) {
        if (context.metadataIndex == null) {
            return;
        }
        try {
            context.metadataIndex.reconcile();
        } catch (Exception ex) {
            logger.warning("Metadata reconcile after retention failed: " + exceptionMessage(ex));
        }
    }

    private String pruneChainMessageKey(String reason) {
        return switch (reason) {
            case "count" -> "backup.prune_chain_count";
            case "age" -> "backup.prune_chain_age";
            case "count+age" -> "backup.prune_chain_count_age";
            default -> "backup.prune_chain_other";
        };
    }

    private String humanBytes(long bytes) {
        if (bytes >= 1024L * MIB) {
            return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0D * MIB));
        }
        if (bytes >= MIB) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (double) MIB);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0D);
        }
        return bytes + " B";
    }

    private String exceptionMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    record ChainRefresh(
            SnapshotChainHealth health,
            boolean restored,
            boolean newlyBroken) {
    }
}
