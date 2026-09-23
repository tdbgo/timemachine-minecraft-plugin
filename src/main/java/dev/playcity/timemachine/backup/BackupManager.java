package dev.playcity.timemachine.backup;

import dev.playcity.timemachine.TimeMachinePlugin;
import dev.playcity.timemachine.config.StorageSafetyValidator;
import dev.playcity.timemachine.config.TimeMachineSettings;
import dev.playcity.timemachine.db.SnapshotMetadataIndex;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

public final class BackupManager {
    private static final DateTimeFormatter SNAPSHOT_ID_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    private final TimeMachinePlugin plugin;
    private final Object lifecycleLock = new Object();
    private final OperationGate operationGate = new OperationGate();
    private final ExecutorService operationExecutor =
            Executors.newSingleThreadExecutor(new NamedThreadFactory("timemachine-operation"));
    private final ExecutorService queryExecutor =
            Executors.newSingleThreadExecutor(new NamedThreadFactory("timemachine-query"));
    private final AtomicReference<Future<?>> activeOperation = new AtomicReference<>();
    private final AtomicReference<BackupSummary> lastSummary = new AtomicReference<>(
            new BackupSummary(BackupState.IDLE, null, "none", Instant.EPOCH, false, 0, 0, 0, false, null, ""));
    private final ProgressTracker progressTracker = new ProgressTracker();
    private final AutosaveSessionCoordinator autosaveSession = new AutosaveSessionCoordinator();
    private final BackupMaintenanceService maintenance;
    private final AtomicReference<BackupRuntimeContext> runtime = new AtomicReference<>();

    public BackupManager(TimeMachinePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.maintenance = new BackupMaintenanceService(plugin.getLogger(), progressTracker);
    }

    public boolean reloadAsync(TimeMachineSettings settings, Consumer<Boolean> completion) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(completion, "completion");
        if (!operationGate.tryAcquire(OperationGate.Operation.RELOADING)) {
            return false;
        }
        try {
            Future<?> future = operationExecutor.submit(() -> {
                BackupRuntimeContext candidate = null;
                boolean success = false;
                try {
                    candidate = createRuntimeContext(settings);
                    BackupRuntimeContext previous;
                    synchronized (lifecycleLock) {
                        if (operationGate.current() != OperationGate.Operation.RELOADING) {
                            return;
                        }
                        previous = runtime.getAndSet(candidate);
                        maintenance.clearPendingPlans();
                        candidate = null;
                    }
                    if (previous != null) {
                        previous.close(false);
                    }
                    success = true;
                } catch (Exception ex) {
                    plugin.getLogger().severe("TimeMachine runtime update rejected; the previous runtime remains active: "
                            + exceptionMessage(ex));
                } finally {
                    if (candidate != null) {
                        candidate.close(true);
                    }
                    boolean completedSuccessfully = success;
                    runOnMainThread(() -> {
                        try {
                            completion.accept(completedSuccessfully);
                        } finally {
                            operationGate.release(OperationGate.Operation.RELOADING);
                            if (completedSuccessfully) {
                                startBackgroundReconcile();
                            }
                        }
                    });
                }
            });
            activeOperation.set(future);
            return true;
        } catch (RejectedExecutionException ex) {
            operationGate.release(OperationGate.Operation.RELOADING);
            return false;
        }
    }

    public boolean startBackup(BackupRequest request, CommandSender sender) {
        return startBackup(request, (key, arguments) -> sendMessage(sender, key, arguments));
    }

    public boolean startScheduledBackup(BackupRequest request) {
        return startBackup(request,
                (key, arguments) -> plugin.getLogger().info(plugin.getMessages().english(key, arguments)));
    }

    public boolean startBackup(BackupRequest request, MessageSink sink) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        BackupRuntimeContext context = runtime.get();
        if (operationGate.current() == OperationGate.Operation.SHUTTING_DOWN) {
            sink.accept("backup.shutting_down");
            return false;
        }
        if (context == null) {
            sink.accept("backup.initializing");
            return false;
        }
        if (context.indexState.get().baselineRequired() && BackupPlanning.hasWorldFilter(request)) {
            sink.accept("backup.global_full_required");
            return false;
        }
        if (!operationGate.tryAcquire(OperationGate.Operation.BACKUP)) {
            sink.accept("backup.busy", currentOperation());
            return false;
        }

        lastSummary.set(BackupSummary.running(request));
        progressTracker.start("backup", OperationProgress.Phase.PREPARING, request.trigger());
        sink.accept("backup.started", request.trigger());
        if (!submitOperation(
                OperationGate.Operation.BACKUP,
                () -> executeBackup(context, request, sink),
                sink)) {
            lastSummary.set(BackupSummary.failed(request, "Backup executor is unavailable."));
            progressTracker.fail("Backup executor is unavailable.");
            return false;
        }
        return true;
    }

    public BackupSummary getLastSummary() {
        return lastSummary.get();
    }

    public OperationProgress getProgress() {
        return progressTracker.snapshot();
    }

    public boolean isInitialized() {
        return runtime.get() != null;
    }

    public boolean isBackupRunning() {
        return operationGate.current() == OperationGate.Operation.BACKUP;
    }

    public boolean isBusy() {
        return operationGate.current() != OperationGate.Operation.IDLE;
    }

    public String currentOperation() {
        return operationGate.current().name().toLowerCase(Locale.ROOT);
    }

    public void queryHistory(
            int limit,
            Consumer<List<SnapshotStore.SnapshotHistoryEntry>> resultConsumer,
            MessageSink sink) {
        BackupRuntimeContext context = runtime.get();
        if (context == null) {
            sink.accept("runtime.not_initialized", "/tmb reload");
            return;
        }
        try {
            queryExecutor.execute(() -> {
                try {
                    List<SnapshotStore.SnapshotHistoryEntry> history = maintenance.loadHistory(context, limit);
                    runOnMainThread(() -> resultConsumer.accept(history));
                } catch (Exception ex) {
                    runOnMainThread(() -> sink.accept("backup.query_history_failed", exceptionMessage(ex)));
                }
            });
        } catch (RejectedExecutionException ex) {
            sink.accept("backup.query_history_unavailable");
        }
    }

    public void queryDiagnostics(
            Consumer<BackupDiagnostics> resultConsumer,
            MessageSink sink) {
        Objects.requireNonNull(resultConsumer, "resultConsumer");
        Objects.requireNonNull(sink, "sink");
        BackupRuntimeContext context = runtime.get();
        if (context == null) {
            sink.accept("runtime.not_initialized", "/tmb reload");
            return;
        }
        try {
            queryExecutor.execute(() -> {
                try {
                    FileStore fileStore = Files.getFileStore(context.settings.storageRoot());
                    CurrentIndexStore.IndexState indexState = context.indexState.get();
                    SnapshotStore.FailedStagingPlan failedStaging =
                            context.snapshotStore.planFailedStagingCleanup();
                    BackupDiagnostics diagnostics = new BackupDiagnostics(
                            context.settings.storageRoot(),
                            context.settings.archiveRoots(),
                            Files.isWritable(context.settings.storageRoot()),
                            fileStore.getUsableSpace(),
                            fileStore.getTotalSpace(),
                            context.metadataIndex != null,
                            context.settings.changeDetectionMode(),
                            indexState.baselineRequired(),
                            indexState.entries().size(),
                            indexState.lastSnapshotId(),
                            indexState.baseSnapshotId(),
                            context.chainHealth.get(),
                            getLatestSnapshotTime().orElse(null),
                            failedStaging.entries().size(),
                            failedStaging.files(),
                            failedStaging.bytes());
                    runOnMainThread(() -> resultConsumer.accept(diagnostics));
                } catch (Exception ex) {
                    runOnMainThread(() -> sink.accept("backup.diagnostics_failed", exceptionMessage(ex)));
                }
            });
        } catch (RejectedExecutionException ex) {
            sink.accept("backup.diagnostics_unavailable");
        }
    }

    public Optional<Instant> getLatestSnapshotTime() {
        BackupRuntimeContext context = runtime.get();
        if (context == null) {
            return Optional.empty();
        }
        if (context.metadataIndex != null) {
            Optional<Instant> latest = context.metadataIndex.latestSnapshotTime();
            if (latest.isPresent()) {
                return latest;
            }
        }
        return context.snapshotStore.latestSnapshot().map(SnapshotStore.SnapshotHistoryEntry::createdAt);
    }

    public Optional<Instant> getLatestFullSnapshotTime() {
        BackupRuntimeContext context = runtime.get();
        if (context == null) {
            return Optional.empty();
        }
        if (context.metadataIndex != null) {
            Optional<Instant> latest = context.metadataIndex.latestFullSnapshotTime();
            if (latest.isPresent()) {
                return latest;
            }
        }
        return context.snapshotStore.latestSnapshot(true).map(SnapshotStore.SnapshotHistoryEntry::createdAt);
    }

    public boolean startReconcile(CommandSender sender) {
        return startReconcile((key, arguments) -> sendMessage(sender, key, arguments));
    }

    public boolean startReconcile(MessageSink sink) {
        Objects.requireNonNull(sink, "sink");
        BackupRuntimeContext context = runtime.get();
        if (context == null || context.metadataIndex == null) {
            sink.accept("backup.sqlite_disabled");
            return false;
        }
        if (!operationGate.tryAcquire(OperationGate.Operation.RECONCILE)) {
            sink.accept("backup.busy", currentOperation());
            return false;
        }

        sink.accept("backup.reconcile_started");
        progressTracker.start("reconcile", OperationProgress.Phase.RECONCILING, "SQLite metadata");
        boolean submitted = submitOperation(
                OperationGate.Operation.RECONCILE,
                () -> maintenance.executeReconcile(context, sink),
                sink);
        if (!submitted) {
            progressTracker.fail("Reconcile executor is unavailable.");
        }
        return submitted;
    }

    public void startBackgroundReconcile() {
        BackupRuntimeContext context = runtime.get();
        if (context == null || context.metadataIndex == null) {
            return;
        }
        startReconcile((key, arguments) -> plugin.getLogger().info(
                "[reconcile] " + plugin.getMessages().english(key, arguments)));
    }

    public boolean startVerify(String snapshotId, CommandSender sender) {
        MessageSink sink = (key, arguments) -> sendMessage(sender, key, arguments);
        if (snapshotId == null || snapshotId.isBlank() || snapshotId.length() > 256) {
            sink.accept("backup.snapshot_id_required");
            return false;
        }
        BackupRuntimeContext context = runtime.get();
        if (context == null) {
            sink.accept("runtime.not_initialized", "/tmb reload");
            return false;
        }
        if (!operationGate.tryAcquire(OperationGate.Operation.VERIFY)) {
            sink.accept("backup.busy", currentOperation());
            return false;
        }

        sink.accept("backup.verify_started", snapshotId);
        progressTracker.start("verify", OperationProgress.Phase.VERIFYING, snapshotId);
        boolean submitted = submitOperation(
                OperationGate.Operation.VERIFY,
                () -> maintenance.executeVerify(context, snapshotId, sink),
                sink);
        if (!submitted) {
            progressTracker.fail("Verification executor is unavailable.");
        }
        return submitted;
    }

    public boolean startPrunePlan(CommandSender sender) {
        MessageSink sink = (key, arguments) -> sendMessage(sender, key, arguments);
        BackupRuntimeContext context = runtime.get();
        if (context == null) {
            sink.accept("runtime.not_initialized", "/tmb reload");
            return false;
        }
        if (!context.settings.retention().enabled()) {
            sink.accept("backup.retention_disabled");
            return false;
        }
        if (!operationGate.tryAcquire(OperationGate.Operation.PRUNE)) {
            sink.accept("backup.busy", currentOperation());
            return false;
        }
        progressTracker.start("prune-plan", OperationProgress.Phase.PRUNING, "Scanning complete restore chains");
        boolean submitted = submitOperation(
                OperationGate.Operation.PRUNE,
                () -> maintenance.executePrunePlan(context, sink),
                sink);
        if (!submitted) {
            progressTracker.fail("Prune planner is unavailable.");
        }
        return submitted;
    }

    public boolean startPruneConfirm(String token, CommandSender sender) {
        MessageSink sink = (key, arguments) -> sendMessage(sender, key, arguments);
        ChainRetentionService.PrunePlan plan = maintenance.pendingPrunePlan();
        if (plan == null) {
            sink.accept("backup.no_prune_plan");
            return false;
        }
        if (token == null || !plan.token().equalsIgnoreCase(token)) {
            sink.accept("backup.prune_token_mismatch");
            return false;
        }
        BackupRuntimeContext context = runtime.get();
        if (context == null) {
            sink.accept("runtime.not_initialized", "/tmb reload");
            return false;
        }
        if (!operationGate.tryAcquire(OperationGate.Operation.PRUNE)) {
            sink.accept("backup.busy", currentOperation());
            return false;
        }
        progressTracker.start("prune", OperationProgress.Phase.PRUNING, "Verifying retained chains before deletion");
        boolean submitted = submitOperation(
                OperationGate.Operation.PRUNE,
                () -> maintenance.executePruneConfirm(context, plan, sink),
                sink);
        if (!submitted) {
            progressTracker.fail("Prune executor is unavailable.");
        }
        return submitted;
    }

    public boolean startCleanupPlan(CommandSender sender) {
        MessageSink sink = (key, arguments) -> sendMessage(sender, key, arguments);
        BackupRuntimeContext context = runtime.get();
        if (context == null) {
            sink.accept("runtime.not_initialized", "/tmb reload");
            return false;
        }
        if (!operationGate.tryAcquire(OperationGate.Operation.CLEANUP)) {
            sink.accept("backup.busy", currentOperation());
            return false;
        }
        progressTracker.start(
                "cleanup-plan",
                OperationProgress.Phase.CLEANING,
                "Scanning failed staging directories");
        boolean submitted = submitOperation(
                OperationGate.Operation.CLEANUP,
                () -> maintenance.executeCleanupPlan(context, sink),
                sink);
        if (!submitted) {
            progressTracker.fail("Cleanup planner is unavailable.");
        }
        return submitted;
    }

    public boolean startCleanupConfirm(String token, CommandSender sender) {
        MessageSink sink = (key, arguments) -> sendMessage(sender, key, arguments);
        SnapshotStore.FailedStagingPlan plan = maintenance.pendingCleanupPlan();
        if (plan == null) {
            sink.accept("backup.no_cleanup_plan");
            return false;
        }
        if (token == null || !plan.token().equalsIgnoreCase(token)) {
            sink.accept("backup.cleanup_token_mismatch");
            return false;
        }
        BackupRuntimeContext context = runtime.get();
        if (context == null) {
            sink.accept("runtime.not_initialized", "/tmb reload");
            return false;
        }
        if (!operationGate.tryAcquire(OperationGate.Operation.CLEANUP)) {
            sink.accept("backup.busy", currentOperation());
            return false;
        }
        progressTracker.start(
                "cleanup",
                OperationProgress.Phase.CLEANING,
                "Deleting confirmed failed staging directories");
        boolean submitted = submitOperation(
                OperationGate.Operation.CLEANUP,
                () -> maintenance.executeCleanupConfirm(context, plan, sink),
                sink);
        if (!submitted) {
            progressTracker.fail("Cleanup executor is unavailable.");
        }
        return submitted;
    }

    public void shutdown() {
        BackupRuntimeContext context;
        synchronized (lifecycleLock) {
            operationGate.beginShutdown();
            context = runtime.getAndSet(null);
        }
        Future<?> operation = activeOperation.getAndSet(null);
        if (operation != null) {
            operation.cancel(true);
            progressTracker.cancel("Plugin shutdown");
        }

        if (context != null) {
            context.stopCopyNow();
        }
        restoreAutosaveIfNeeded();

        operationExecutor.shutdownNow();
        queryExecutor.shutdownNow();
        awaitTermination(operationExecutor, 5);
        awaitTermination(queryExecutor, 2);
        if (context != null) {
            context.close(true);
        }
    }

    private void executeBackup(BackupRuntimeContext context, BackupRequest request, MessageSink sink) {
        Instant now = Instant.now();
        SnapshotDescriptor snapshot = createSnapshotDescriptor(context.settings, now, request.trigger());
        Path stagingDirectory = null;
        Path committedDirectory = null;
        boolean indexCommitted = false;
        try {
            CurrentIndexStore.IndexState previousState = context.indexState.get();
            BackupPlanning.Plan requestedPlan = BackupPlanning.create(previousState, request);
            BackupPlanning.Plan plan = requestedPlan;
            if (previousState.baselineRequired()) {
                sink.accept("backup.promoted_full");
            }

            PreparedBackup preparedBackup;
            BackupFilePipeline.ScanResult scanResult;
            try {
                BackupFilePipeline.ensureMinimumFreeSpace(context.settings);
                progressTracker.beginPhase(OperationProgress.Phase.SAVING, 0, 0L, "Saving loaded worlds");
                Map<String, String> previousWorldSourcePaths = loadPreviousWorldSourcePaths(
                        context,
                        requestedPlan,
                        previousState);
                preparedBackup = runSync(() -> prepareBackup(
                        context,
                        request,
                        requestedPlan,
                        previousState,
                        previousWorldSourcePaths)).join();
                plan = preparedBackup.plan();
                if (!preparedBackup.layoutChangedWorlds().isEmpty()) {
                    sink.accept("backup.layout_promoted", String.join(", ", preparedBackup.layoutChangedWorlds()));
                }
                stagingDirectory = context.snapshotStore.createStagingDirectory(snapshot.stagingName());
                scanResult = new BackupFilePipeline(
                        context.settings,
                        context.copyExecutor,
                        progressTracker,
                        sink).scanAndCopy(
                        preparedBackup.worlds(),
                        plan.kind(),
                        plan.copyAllFiles(),
                        context.snapshotStore.filesDirectory(stagingDirectory),
                        previousState.entries());
            } finally {
                restoreAutosaveIfNeeded();
            }

            if (plan.kind() == SnapshotKind.INCREMENTAL
                    && scanResult.changedEntries().isEmpty()
                    && scanResult.deletedEntries().isEmpty()
                    && context.settings.skipIfNoChange()) {
                context.snapshotStore.discardStaging(stagingDirectory);
                BackupSummary summary = new BackupSummary(
                        BackupState.SUCCESS,
                        null,
                        request.trigger(),
                        now,
                        false,
                        0,
                        0,
                        0,
                        true,
                        null,
                        request.message());
                lastSummary.set(summary);
                progressTracker.complete("No changed .mca files were found.");
                sink.accept("backup.no_changes");
                return;
            }

            String parentSnapshotId = plan.kind().isFullBaseline() ? "" : previousState.lastSnapshotId();
            String baseSnapshotId = plan.kind().isFullBaseline()
                    ? snapshot.snapshotId()
                    : previousState.baseSnapshotId();
            if (!plan.kind().isFullBaseline()
                    && (parentSnapshotId.isBlank() || baseSnapshotId.isBlank())) {
                throw new IllegalStateException("Incremental snapshot has no valid parent/base chain.");
            }

            progressTracker.beginPhase(
                    OperationProgress.Phase.COMMITTING,
                    scanResult.changedEntries().size(),
                    0L,
                    "Publishing snapshot metadata and index");
            sink.accept("backup.publishing");
            committedDirectory = context.snapshotStore.commit(
                    stagingDirectory,
                    snapshot.snapshotId(),
                    snapshot.relativePath(),
                    request,
                    now,
                    plan.kind(),
                    parentSnapshotId,
                    baseSnapshotId,
                    preparedBackup.worlds().stream().map(BackupTargetWorld::snapshotWorld).toList(),
                    context.settings.scopes(),
                    scanResult.changedEntries(),
                    scanResult.deletedEntries());

            Map<String, TrackedFileMetadata> mergedIndex = plan.kind().isFullBaseline()
                    ? new LinkedHashMap<>(scanResult.currentState())
                    : BackupIndexPlanner.merge(
                            previousState.entries(),
                            scanResult.currentState(),
                            scanResult.scannedScopePrefixes());
            CurrentIndexStore.IndexState committedState = CurrentIndexStore.IndexState.committed(
                    mergedIndex,
                    snapshot.snapshotId(),
                    baseSnapshotId);
            context.indexStore.save(committedState);
            indexCommitted = true;
            context.indexState.set(committedState);
            int chainDepth = plan.kind().isFullBaseline()
                    ? 1
                    : Math.max(1, context.chainHealth.get().snapshotsChecked() + 1);
            context.chainHealth.set(SnapshotChainHealth.healthy(
                    snapshot.snapshotId(),
                    baseSnapshotId,
                    chainDepth));

            BackupSummary summary = new BackupSummary(
                    BackupState.SUCCESS,
                    snapshot.snapshotId(),
                    request.trigger(),
                    now,
                    plan.kind().isFullBaseline(),
                    scanResult.changedEntries().size(),
                    uniqueRegionSetCount(scanResult.changedEntries(), scanResult.deletedEntries()),
                    scanResult.deletedEntries().size(),
                    false,
                    committedDirectory,
                    request.message());
            lastSummary.set(summary);
            maintenance.recordSnapshotMetadata(
                    context,
                    snapshot.snapshotId(),
                    now,
                    request,
                    plan.kind(),
                    committedDirectory,
                    snapshot.relativePath(),
                    scanResult.changedEntries().size(),
                    uniqueRegionSetCount(scanResult.changedEntries(), scanResult.deletedEntries()),
                    scanResult.deletedEntries().size());
            maintenance.clearPendingPlans();
            maintenance.runAutomaticRetention(context, sink);
            progressTracker.complete(snapshot.snapshotId());
            sink.accept(
                    "backup.completed",
                    snapshot.snapshotId(),
                    plan.kind(),
                    summary.changedFiles(),
                    summary.changedRegionSets());
        } catch (Exception ex) {
            restoreAutosaveIfNeeded();
            String failure = exceptionMessage(ex);
            if (indexCommitted) {
                plugin.getLogger().warning(
                        "Backup data and index were committed, but post-commit reporting failed for "
                                + snapshot.snapshotId() + ": " + failure);
                progressTracker.complete(snapshot.snapshotId() + " (reporting warning)");
                return;
            }
            if (committedDirectory != null) {
                if (!context.snapshotStore.quarantineCommitted(committedDirectory, failure)) {
                    CurrentIndexStore.IndexState uncertainState =
                            context.indexState.get().requiringNewBaseline();
                    context.indexState.set(uncertainState);
                    context.chainHealth.set(SnapshotChainHealth.baselineRequired(
                            uncertainState.lastSnapshotId(),
                            uncertainState.baseSnapshotId()));
                    plugin.getLogger().severe(
                            "Could not quarantine a snapshot whose index commit failed; a new FULL baseline is now required: "
                                    + committedDirectory);
                }
            } else if (stagingDirectory != null) {
                context.snapshotStore.markFailed(stagingDirectory, failure);
            }
            plugin.getLogger().warning("Backup failed: " + failure);
            lastSummary.set(BackupSummary.failed(request, failure));
            progressTracker.fail(failure);
            sink.accept("backup.failed", failure);
        }
    }

    private PreparedBackup prepareBackup(
            BackupRuntimeContext context,
            BackupRequest request,
            BackupPlanning.Plan plan,
            CurrentIndexStore.IndexState previousState,
            Map<String, String> previousWorldSourcePaths) {
        List<World> worlds = resolveWorlds(context.settings, request.worldFilter());
        if (worlds.isEmpty()) {
            throw new IllegalStateException("No loaded worlds matched the backup request.");
        }

        List<BackupTargetWorld> targets = worlds.stream().map(this::targetWorld).toList();
        BackupPlanning.Migration layoutMigration = BackupPlanning.resolveWorldPathMigration(
                request,
                plan,
                targets,
                previousState,
                previousWorldSourcePaths);
        BackupPlanning.Plan effectivePlan = layoutMigration.plan();
        BackupPlanning.validateFullCoverage(context.settings, effectivePlan, worlds, targets, previousState);
        BackupPlanning.validateIdentityContinuity(
                context.settings,
                request,
                effectivePlan,
                targets,
                previousState);
        List<StorageSafetyValidator.WorldPath> worldPaths = targets.stream()
                .map(world -> new StorageSafetyValidator.WorldPath(world.name(), world.path()))
                .toList();
        List<String> storageIssues = StorageSafetyValidator.validate(context.settings, worldPaths);
        if (!storageIssues.isEmpty()) {
            throw new IllegalStateException(storageIssues.getFirst());
        }

        Map<UUID, Boolean> autosaveStates = new HashMap<>();
        for (int index = 0; index < worlds.size(); index++) {
            World world = worlds.get(index);
            autosaveStates.put(world.getUID(), world.isAutoSave());
            autosaveSession.update(autosaveStates);
            if (context.settings.pauseAutosave()) {
                world.setAutoSave(false);
            }
            world.save(true);
        }
        return new PreparedBackup(List.copyOf(targets), effectivePlan, layoutMigration.changedWorlds());
    }

    private Map<String, String> loadPreviousWorldSourcePaths(
            BackupRuntimeContext context,
            BackupPlanning.Plan plan,
            CurrentIndexStore.IndexState previousState) throws IOException {
        if (plan.kind().isFullBaseline() || previousState.baselineRequired()) {
            return Map.of();
        }
        return context.snapshotStore.loadWorldSourcePaths(previousState.lastSnapshotId());
    }

    private int uniqueRegionSetCount(
            List<TrackedFileMetadata> changedEntries,
            List<TrackedFileMetadata> deletedEntries) {
        return (int) java.util.stream.Stream.concat(changedEntries.stream(), deletedEntries.stream())
                .map(TrackedFileMetadata::regionKey)
                .distinct()
                .count();
    }

    private SnapshotDescriptor createSnapshotDescriptor(
            TimeMachineSettings settings,
            Instant instant,
            String trigger) {
        ZonedDateTime localTime = instant.atZone(settings.timezone());
        String safeTrigger = trigger == null || trigger.isBlank()
                ? "manual"
                : trigger.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String folderName = SNAPSHOT_ID_FORMATTER.format(localTime) + "_" + safeTrigger + "_" + uniqueSuffix;
        Path relativePath = Path.of(String.format("%04d", localTime.getYear()), folderName);
        String snapshotId = relativePath.toString().replace('\\', '/');
        return new SnapshotDescriptor(snapshotId, relativePath, folderName);
    }

    private List<World> resolveWorlds(TimeMachineSettings settings, String worldFilter) {
        Collection<World> loadedWorlds = Bukkit.getWorlds();
        List<World> selected = new ArrayList<>();
        for (World world : loadedWorlds) {
            if (!isWorldIncluded(settings, world)) {
                continue;
            }
            if (worldFilter != null
                    && !worldFilter.isBlank()
                    && !BackupPlanning.matchesWorld(world, worldFilter)) {
                continue;
            }
            selected.add(world);
        }
        return selected;
    }

    private boolean isWorldIncluded(TimeMachineSettings settings, World world) {
        if (settings.includedWorlds().isEmpty()) {
            return true;
        }
        return settings.includedWorlds().stream().anyMatch(value -> BackupPlanning.matchesWorld(world, value));
    }

    private BackupTargetWorld targetWorld(World world) {
        String uuid = world.getUID().toString().toLowerCase(Locale.ROOT);
        String storagePath = "worlds/" + uuid;
        Path worldPath = world.getWorldPath().toAbsolutePath().normalize();
        return new BackupTargetWorld(
                world.getName(),
                world.getKey().toString(),
                uuid,
                storagePath,
                worldPath,
                new SnapshotStore.SnapshotWorld(
                        uuid,
                        world.getKey().toString(),
                        world.getName(),
                        storagePath,
                        worldPath.toString()));
    }

    private void restoreAutosave(Map<UUID, Boolean> autosaveStates) {
        for (World world : Bukkit.getWorlds()) {
            Boolean enabled = autosaveStates.get(world.getUID());
            if (enabled != null) {
                world.setAutoSave(enabled);
            }
        }
    }

    private void restoreAutosaveIfNeeded() {
        AutosaveSessionCoordinator.Restoration restoration =
                autosaveSession.restorationTask(this::restoreAutosave);
        if (!restoration.pending()) {
            return;
        }
        try {
            if (Bukkit.isPrimaryThread()) {
                restoration.run();
            } else {
                CompletableFuture<Void> scheduled = runSync(() -> {
                    restoration.run();
                    return null;
                });
                CompletableFuture.anyOf(restoration.completion(), scheduled).join();
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to restore autosave flags: " + exceptionMessage(ex));
        }
    }

    private <T> CompletableFuture<T> runSync(SyncCallable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (Bukkit.isPrimaryThread()) {
            try {
                future.complete(callable.call());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
            return future;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    future.complete(callable.call());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    private void runOnMainThread(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, runnable);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not schedule main-thread callback: " + exceptionMessage(throwable));
        }
    }

    private void sendMessage(CommandSender sender, String key, Object... arguments) {
        if (sender == null) {
            plugin.getLogger().info(plugin.getMessages().english(key, arguments));
            return;
        }
        Object[] localizedArguments = arguments.clone();
        if (localizedArguments.length > 0 && ("backup.busy".equals(key) || "backup.started".equals(key))) {
            String category = "backup.busy".equals(key) ? "operation" : "trigger";
            String raw = String.valueOf(localizedArguments[0]);
            String valueKey = category + "." + raw.toLowerCase(Locale.ROOT).replace('-', '_');
            String localized = plugin.getMessages().text(sender, valueKey);
            if (!localized.equals(valueKey)) {
                localizedArguments[0] = localized;
            }
        }
        runOnMainThread(() -> sender.sendMessage(
                "[TimeMachine] " + plugin.getMessages().text(sender, key, localizedArguments)));
    }

    private boolean submitOperation(OperationGate.Operation operation, Runnable runnable, MessageSink sink) {
        try {
            Future<?> future = operationExecutor.submit(() -> {
                try {
                    runnable.run();
                } finally {
                    operationGate.release(operation);
                }
            });
            activeOperation.set(future);
            return true;
        } catch (RejectedExecutionException ex) {
            operationGate.release(operation);
            sink.accept("backup.executor_unavailable");
            return false;
        }
    }

    private BackupRuntimeContext createRuntimeContext(TimeMachineSettings settings) throws Exception {
        Files.createDirectories(settings.storageRoot());
        CurrentIndexStore indexStore = new CurrentIndexStore(
                settings.storageRoot().resolve("state").resolve("current-index.tsv"));
        CurrentIndexStore.IndexState indexState = indexStore.load();
        SnapshotStore snapshotStore = new SnapshotStore(settings.storageRoot(), settings.archiveRoots());
        SnapshotChainHealth chainHealth = snapshotStore.inspectActiveChain(indexState);

        if (indexState.legacyFormat()) {
            plugin.getLogger().warning(
                    "Legacy current-index v1 detected. The next unfiltered backup will create a new FULL baseline.");
        }
        if (indexState.recoveredFromBackup()) {
            plugin.getLogger().warning(
                    "Recovered current-index from its backup copy. A new FULL baseline is required for chain safety.");
            indexState = indexState.requiringNewBaseline();
            chainHealth = SnapshotChainHealth.baselineRequired(
                    indexState.lastSnapshotId(),
                    indexState.baseSnapshotId());
        }
        if (chainHealth.broken()) {
            String issue = plugin.getMessages().english(
                    chainHealth.issue().key(),
                    chainHealth.issue().argumentsArray());
            plugin.getLogger().warning("The active snapshot chain is incomplete: "
                    + issue
                    + " The next unfiltered backup will create a new FULL baseline.");
            indexState = indexState.requiringNewBaseline();
        }

        ThreadPoolExecutor copyExecutor = new ThreadPoolExecutor(
                settings.copyThreads(),
                settings.copyThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(2, settings.copyThreads() * 2)),
                new NamedThreadFactory("timemachine-copy"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        SnapshotMetadataIndex metadataIndex = null;
        try {
            if (settings.database().enabled()) {
                SnapshotMetadataIndex candidate = new SnapshotMetadataIndex(
                        plugin.getLogger(),
                        settings.database(),
                        settings.storageRoot().resolve("snapshots"),
                        settings.archiveRoots());
                try {
                    candidate.initialize();
                    metadataIndex = candidate;
                } catch (Exception ex) {
                    candidate.close();
                    plugin.getLogger().warning(
                            "SQLite metadata index is unavailable; backups will continue with filesystem history, "
                                    + "but reconcile is disabled until the database is repaired: "
                                    + exceptionMessage(ex));
                }
            }
            return new BackupRuntimeContext(
                    settings,
                    indexStore,
                    snapshotStore,
                    metadataIndex,
                    copyExecutor,
                    indexState,
                    chainHealth);
        } catch (Exception ex) {
            copyExecutor.shutdownNow();
            if (metadataIndex != null) {
                metadataIndex.close();
            }
            throw ex;
        }
    }

    private String exceptionMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private void awaitTermination(ExecutorService executor, int seconds) {
        try {
            if (!executor.awaitTermination(seconds, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("TimeMachine worker did not terminate within " + seconds + " seconds.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record PreparedBackup(
            List<BackupTargetWorld> worlds,
            BackupPlanning.Plan plan,
            List<String> layoutChangedWorlds) {
    }

    private record SnapshotDescriptor(String snapshotId, Path relativePath, String stagingName) {
    }

    @FunctionalInterface
    public interface MessageSink {
        void accept(String key, Object... arguments);
    }

    @FunctionalInterface
    private interface SyncCallable<T> {
        T call() throws Exception;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private int sequence;

        private NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, namePrefix + "-" + (++sequence));
            thread.setDaemon(true);
            return thread;
        }
    }
}
