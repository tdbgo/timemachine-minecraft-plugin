package dev.playcity.timemachine.command;

import dev.playcity.timemachine.TimeMachinePlugin;
import dev.playcity.timemachine.backup.BackupDiagnostics;
import dev.playcity.timemachine.backup.BackupManager;
import dev.playcity.timemachine.backup.BackupRequest;
import dev.playcity.timemachine.backup.BackupSummary;
import dev.playcity.timemachine.backup.ChangeDetectionMode;
import dev.playcity.timemachine.backup.OperationProgress;
import dev.playcity.timemachine.backup.SnapshotChainHealth;
import dev.playcity.timemachine.backup.SnapshotStore;
import dev.playcity.timemachine.config.TimeMachineSettings;
import dev.playcity.timemachine.i18n.MessageCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class TimeMachineCommand implements CommandExecutor, TabCompleter {
    private final TimeMachinePlugin plugin;

    public TimeMachineCommand(TimeMachinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            TimeMachineHelp.send(messages(), sender, "");
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        String requiredPermission = TimeMachineCommandAccess.permissionFor(action);
        if (requiredPermission != null && !requirePermission(sender, requiredPermission)) {
            return true;
        }

        return switch (action) {
            case "help" -> handleHelp(sender, args);
            case "backup" -> handleBackup(sender, args);
            case "status" -> handleStatus(sender);
            case "doctor" -> handleDoctor(sender);
            case "history" -> handleHistory(sender, args);
            case "verify" -> handleVerify(sender, args);
            case "prune" -> handlePrune(sender, args);
            case "cleanup" -> handleCleanup(sender, args);
            case "reconcile" -> handleReconcile(sender);
            case "reload" -> handleReload(sender);
            default -> {
                send(sender, "command.unknown", TimeMachineCommandReference.command("help"));
                yield true;
            }
        };
    }

    private boolean handleHelp(CommandSender sender, String[] args) {
        if (args.length > 2) {
            send(sender, "command.help_usage",
                    TimeMachineCommandReference.command("help [advanced|config|permissions]"));
            return true;
        }
        TimeMachineHelp.send(messages(), sender, args.length == 2 ? args[1] : "");
        return true;
    }

    private boolean handleBackup(CommandSender sender, String[] args) {
        BackupManager backupManager = requireActiveManager(sender);
        if (backupManager == null) {
            return true;
        }

        BackupCommandArguments options;
        try {
            options = BackupCommandArguments.parse(args);
        } catch (IllegalArgumentException ex) {
            sendBackupArgumentError(sender, ex.getMessage());
            send(sender, "command.backup_usage",
                    TimeMachineCommandReference.command("backup [world] [--full] [--message <text>]"));
            return true;
        }

        backupManager.startBackup(
                new BackupRequest(
                        "manual",
                        options.fullBackup(),
                        options.worldFilter(),
                        options.message(),
                        sender.getName()),
                sender);
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        BackupManager backupManager = requireActiveManager(sender);
        if (backupManager == null) {
            return true;
        }

        OperationProgress progress = backupManager.getProgress();
        BackupSummary summary = backupManager.getLastSummary();
        String runtimeState = backupManager.isBusy()
                ? text(sender, "label.busy", localizedValue(sender, "operation", backupManager.currentOperation()))
                : text(sender, "label.ready");
        send(sender, "status.header", runtimeState);
        if (progress.active()) {
            String phase = localizedValue(sender, "phase", progress.phase().name());
            String counts = TimeMachineText.progressCounts(progress, text(sender, "label.files"));
            send(sender, "status.progress", phase, counts,
                    TimeMachineText.duration(progress.elapsed(Instant.now())));
            if (!progress.detail().isBlank()) {
                send(sender, "status.detail", messages().progressDetail(sender, progress.detail()));
            }
        }
        String summaryTime = Instant.EPOCH.equals(summary.createdAt())
                ? text(sender, "label.never")
                : TimeMachineText.time(summary.createdAt(), plugin.getDisplayTimezone());
        send(sender, "status.last",
                localizedValue(sender, "state", summary.state().name()),
                localizedValue(sender, "trigger", summary.trigger()),
                summary.fullBackup() ? text(sender, "status.full_suffix") : "",
                summaryTime);
        send(sender, "status.snapshot",
                summary.snapshotId() == null ? "-" : summary.snapshotId(),
                summary.changedFiles(),
                summary.deletedFiles(),
                summary.noChanges() ? text(sender, "status.no_changes_suffix") : "");
        if (!summary.message().isBlank()) {
            send(sender, "status.message", summary.message());
        }
        plugin.getNextScheduledRun().ifPresentOrElse(
                run -> send(sender, "status.next",
                        TimeMachineText.time(run.at().toInstant(), plugin.getDisplayTimezone()),
                        localizedValue(sender, "trigger", run.trigger()),
                        run.fullBackup() ? text(sender, "status.full_suffix") : ""),
                () -> send(sender, "status.next_none"));
        return true;
    }

    private boolean handleDoctor(CommandSender sender) {
        BackupManager backupManager = requireActiveManager(sender);
        if (backupManager == null) {
            return true;
        }
        TimeMachineSettings settings = plugin.getActiveSettings().orElse(null);
        if (settings == null) {
            send(sender, "doctor.settings_inactive");
            return true;
        }
        send(sender, "doctor.running");
        backupManager.queryDiagnostics(
                diagnostics -> sendDiagnostics(sender, settings, diagnostics),
                (key, arguments) -> send(sender, key, arguments));
        return true;
    }

    private void sendDiagnostics(
            CommandSender sender,
            TimeMachineSettings settings,
            BackupDiagnostics diagnostics) {
        send(sender, "doctor.title");
        send(sender, "doctor.storage", diagnostics.storageRoot(),
                text(sender, diagnostics.storageWritable() ? "label.writable" : "label.not_writable"));
        send(sender, "doctor.space",
                TimeMachineText.bytes(diagnostics.usableSpaceBytes()),
                TimeMachineText.bytes(diagnostics.totalSpaceBytes()),
                TimeMachineText.bytes(settings.minimumFreeSpaceBytes()));
        send(sender, "doctor.change_detection",
                diagnostics.changeDetectionMode().name().toLowerCase(Locale.ROOT),
                diagnostics.trackedFiles());
        send(sender, "doctor.chain",
                TimeMachineText.valueOrDash(diagnostics.lastSnapshotId()),
                TimeMachineText.valueOrDash(diagnostics.baseSnapshotId()),
                TimeMachineText.nullableTime(diagnostics.latestSnapshotAt(), plugin.getDisplayTimezone()));
        SnapshotChainHealth chainHealth = diagnostics.chainHealth();
        send(sender, "doctor.chain_health",
                localizedValue(sender, "chain_status", chainHealth.status().name()),
                chainHealth.snapshotsChecked());
        send(sender, "doctor.database",
                text(sender, diagnostics.databaseEnabled() ? "label.enabled" : "label.disabled"),
                diagnostics.archiveRoots().size());
        send(sender, "doctor.retention",
                text(sender, settings.retention().enabled() ? "label.enabled" : "label.disabled"),
                settings.retention().minimumChains(),
                settings.retention().pinnedSnapshots().size());
        send(sender, "doctor.failed_staging",
                diagnostics.failedStagingDirectories(),
                diagnostics.failedStagingFiles(),
                TimeMachineText.bytes(diagnostics.failedStagingBytes()));
        String scopes = settings.scopes().stream()
                .map(scope -> scope.directoryName())
                .sorted()
                .toList()
                .toString();
        send(sender, "doctor.worlds",
                settings.includedWorlds().isEmpty()
                        ? text(sender, "label.all_worlds")
                        : String.join(", ", settings.includedWorlds()),
                scopes);

        List<String> warnings = new ArrayList<>();
        if (!diagnostics.storageWritable()) {
            warnings.add(text(sender, "doctor.warning.storage"));
        }
        if (diagnostics.usableSpaceBytes() < settings.minimumFreeSpaceBytes()) {
            warnings.add(text(sender, "doctor.warning.space"));
        }
        if (diagnostics.changeDetectionMode() == ChangeDetectionMode.FAST) {
            warnings.add(text(sender, "doctor.warning.fast"));
        }
        if (settings.database().enabled() && !diagnostics.databaseEnabled()) {
            warnings.add(text(sender, "doctor.warning.database"));
        }
        if (chainHealth.broken()) {
            String issue = text(
                    sender,
                    chainHealth.issue().key(),
                    chainHealth.issue().argumentsArray());
            warnings.add(text(
                    sender,
                    "doctor.warning.chain_broken",
                    issue,
                    TimeMachineCommandReference.command("backup")));
        } else if (diagnostics.baselineRequired()) {
            warnings.add(text(sender, "doctor.warning.baseline", TimeMachineCommandReference.command("backup")));
        }
        if (!settings.scheduleEnabled() || !settings.hasRegularSchedule()) {
            warnings.add(text(sender, "doctor.warning.schedule", TimeMachineCommandReference.command("backup")));
        }
        if (!settings.fullBackupSchedule().enabled()) {
            warnings.add(text(sender, "doctor.warning.full_schedule"));
        }
        if (settings.scopes().size() < 3) {
            warnings.add(text(sender, "doctor.warning.scopes", scopes));
        }
        if (diagnostics.failedStagingDirectories() > 0) {
            warnings.add(text(
                    sender,
                    "doctor.warning.failed_staging",
                    TimeMachineCommandReference.command("cleanup")));
        }
        if (warnings.isEmpty()) {
            send(sender, "doctor.ok");
            return;
        }
        send(sender, "doctor.warnings", warnings.size());
        warnings.forEach(warning -> sender.sendMessage("- " + warning));
    }

    private boolean handleHistory(CommandSender sender, String[] args) {
        BackupManager backupManager = requireActiveManager(sender);
        if (backupManager == null) {
            return true;
        }

        int count = 5;
        if (args.length >= 2) {
            try {
                count = Math.max(1, Math.min(20, Integer.parseInt(args[1])));
            } catch (NumberFormatException ex) {
                send(sender, "command.history_count");
                return true;
            }
        }

        int requestedCount = count;
        send(sender, "history.loading");
        backupManager.queryHistory(
                requestedCount,
                entries -> sendHistory(sender, entries),
                (key, arguments) -> send(sender, key, arguments));
        return true;
    }

    private void sendHistory(CommandSender sender, List<SnapshotStore.SnapshotHistoryEntry> entries) {
        if (entries.isEmpty()) {
            send(sender, "history.empty");
            return;
        }
        for (SnapshotStore.SnapshotHistoryEntry entry : entries) {
            send(sender, "history.entry",
                    entry.fullBackup() ? "[FULL] " : "[INC]  ",
                    entry.snapshotId(),
                    TimeMachineText.time(entry.createdAt(), plugin.getDisplayTimezone()),
                    localizedValue(sender, "trigger", entry.trigger()),
                    entry.changedFiles(),
                    entry.deletedFiles(),
                    localizedValue(sender, "snapshot_status", entry.status()),
                    entry.storageName());
        }
    }

    private boolean handleVerify(CommandSender sender, String[] args) {
        BackupManager backupManager = requireActiveManager(sender);
        if (backupManager == null) {
            return true;
        }
        if (args.length != 2) {
            send(sender, "command.verify_usage", TimeMachineCommandReference.command("verify <snapshotId>"));
            return true;
        }
        backupManager.startVerify(args[1], sender);
        return true;
    }

    private boolean handleReconcile(CommandSender sender) {
        BackupManager backupManager = requireActiveManager(sender);
        if (backupManager != null) {
            backupManager.startReconcile(sender);
        }
        return true;
    }

    private boolean handlePrune(CommandSender sender, String[] args) {
        BackupManager backupManager = requireActiveManager(sender);
        if (backupManager == null) {
            return true;
        }
        if (args.length == 1) {
            send(sender, "prune.preview_start");
            backupManager.startPrunePlan(sender);
            return true;
        }
        if (args.length == 3 && "confirm".equalsIgnoreCase(args[1])) {
            backupManager.startPruneConfirm(args[2], sender);
            return true;
        }
        send(sender, "command.prune_usage",
                TimeMachineCommandReference.command("prune [confirm <token>]"));
        return true;
    }

    private boolean handleCleanup(CommandSender sender, String[] args) {
        BackupManager backupManager = requireActiveManager(sender);
        if (backupManager == null) {
            return true;
        }
        if (args.length == 1) {
            send(sender, "cleanup.preview_start");
            backupManager.startCleanupPlan(sender);
            return true;
        }
        if (args.length == 3 && "confirm".equalsIgnoreCase(args[1])) {
            backupManager.startCleanupConfirm(args[2], sender);
            return true;
        }
        send(sender, "command.cleanup_usage",
                TimeMachineCommandReference.command("cleanup [confirm <token>]"));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        BackupManager backupManager = plugin.getBackupManager();
        if (backupManager != null && backupManager.isBusy()) {
            send(sender, "reload.busy", localizedValue(sender, "operation", backupManager.currentOperation()));
            return true;
        }
        send(sender, "reload.validating");
        plugin.reloadPlugin(success -> send(sender, success ? "reload.success" : "reload.rejected"));
        return true;
    }

    private BackupManager requireActiveManager(CommandSender sender) {
        BackupManager backupManager = plugin.getBackupManager();
        if (backupManager == null) {
            send(sender, "runtime.not_initialized", TimeMachineCommandReference.command("reload"));
            return null;
        }
        if (!plugin.isRuntimeReady()) {
            send(sender, "runtime.initializing");
            return null;
        }
        return backupManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            suggestions.add("help");
            suggestions.addAll(TimeMachineCommandAccess.permittedRootCommands(sender::hasPermission));
            return suggestions.stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && "help".equalsIgnoreCase(args[0])) {
            return TimeMachineHelp.topics().stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length >= 2
                && "backup".equalsIgnoreCase(args[0])
                && sender.hasPermission(TimeMachinePermissions.BACKUP)) {
            suggestions.add("--full");
            suggestions.add("--message");
            plugin.getServer().getWorlds().forEach(world -> {
                suggestions.add(world.getKey().toString());
                suggestions.add(world.getName());
            });
            return suggestions.stream()
                    .filter(value -> value.toLowerCase(Locale.ROOT)
                            .startsWith(args[args.length - 1].toLowerCase(Locale.ROOT)))
                    .distinct()
                    .toList();
        }
        if (args.length == 2
                && "prune".equalsIgnoreCase(args[0])
                && sender.hasPermission(TimeMachinePermissions.PRUNE)) {
            return "confirm".startsWith(args[1].toLowerCase(Locale.ROOT))
                    ? List.of("confirm")
                    : List.of();
        }
        if (args.length == 2
                && "cleanup".equalsIgnoreCase(args[0])
                && sender.hasPermission(TimeMachinePermissions.PRUNE)) {
            return "confirm".startsWith(args[1].toLowerCase(Locale.ROOT))
                    ? List.of("confirm")
                    : List.of();
        }
        return List.of();
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        send(sender, "command.permission", permission);
        return false;
    }

    private void sendBackupArgumentError(CommandSender sender, String message) {
        if ("Missing text after --message.".equals(message)) {
            send(sender, "command.missing_message");
        } else if ("Only one world can be selected.".equals(message)) {
            send(sender, "command.one_world");
        } else if (message != null && message.startsWith("Unknown option: ")) {
            send(sender, "command.unknown_option", message.substring("Unknown option: ".length()));
        } else {
            sender.sendMessage(message == null ? "" : message);
        }
    }

    private MessageCatalog messages() {
        return plugin.getMessages();
    }

    private void send(CommandSender sender, String key, Object... arguments) {
        messages().send(sender, key, arguments);
    }

    private String text(CommandSender sender, String key, Object... arguments) {
        return messages().text(sender, key, arguments);
    }

    private String localizedValue(CommandSender sender, String category, String raw) {
        String normalized = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace('-', '_');
        String key = category + "." + normalized;
        String localized = text(sender, key);
        return localized.equals(key) ? raw : localized;
    }
}
