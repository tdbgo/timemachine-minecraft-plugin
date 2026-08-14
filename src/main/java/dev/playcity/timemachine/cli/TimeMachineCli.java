package dev.playcity.timemachine.cli;

import dev.playcity.timemachine.backup.SnapshotStore;
import dev.playcity.timemachine.i18n.LanguageMode;
import dev.playcity.timemachine.i18n.MessageCatalog;
import dev.playcity.timemachine.restore.RestoreService;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TimeMachineCli {
    private static final Path DEFAULT_STORE = Path.of("plugins", "Timemachine", "backups");
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private TimeMachineCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream output, PrintStream error) {
        LanguageSelection languageSelection;
        try {
            languageSelection = selectLanguage(args);
        } catch (UsageException ex) {
            MessageCatalog messages = new MessageCatalog(LanguageMode.AUTO);
            error.println(messages.system("cli.error", ex.getMessage()));
            error.println(messages.system("cli.usage_hint"));
            return 2;
        }
        MessageCatalog messages = new MessageCatalog(languageSelection.mode());
        String[] commandArgs = languageSelection.arguments();
        try {
            if (commandArgs.length == 0 || isHelp(commandArgs[0])) {
                printHelp(output, messages);
                return 0;
            }
            if (commandArgs.length == 1 && "version".equalsIgnoreCase(commandArgs[0])) {
                output.println("TimeMachine " + implementationVersion());
                return 0;
            }
            if (!"restore".equalsIgnoreCase(commandArgs[0])) {
                throw usage(messages, "cli.unknown_command", commandArgs[0]);
            }
            if (commandArgs.length < 2 || isHelp(commandArgs[1])) {
                printHelp(output, messages);
                return 0;
            }

            String action = commandArgs[1].toLowerCase(Locale.ROOT);
            Options options = parseOptions(commandArgs, 2, messages);
            return switch (action) {
                case "list" -> listSnapshots(options, output, messages);
                case "verify" -> verifySnapshot(options, output, error, messages);
                case "export" -> exportSnapshot(options, output, messages);
                default -> throw usage(messages, "cli.unknown_action", commandArgs[1]);
            };
        } catch (UsageException ex) {
            error.println(messages.system("cli.error", ex.getMessage()));
            error.println(messages.system("cli.usage_hint"));
            return 2;
        } catch (RestoreService.SnapshotVerificationException ex) {
            error.println(messages.system("cli.verify_failed"));
            ex.issues().forEach(issue -> error.println(
                    "- " + messages.system(issue.key(), issue.argumentsArray())));
            return 3;
        } catch (IOException ex) {
            error.println(messages.system("cli.error", ex.getMessage()));
            return 1;
        } catch (RuntimeException ex) {
            String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            error.println(messages.system("cli.error", detail));
            return 1;
        }
    }

    private static int listSnapshots(Options options, PrintStream output, MessageCatalog messages)
            throws UsageException {
        rejectOption(options.snapshotId != null, messages, "cli.snapshot_list");
        rejectOption(options.outputDirectory != null, messages, "cli.output_list");
        SnapshotStore store = new SnapshotStore(options.storageRoot, options.archiveRoots);
        List<SnapshotStore.SnapshotHistoryEntry> history = store.loadSearchableHistory(options.limit);
        if (history.isEmpty()) {
            output.println(messages.system("cli.no_snapshots"));
            return 0;
        }
        for (SnapshotStore.SnapshotHistoryEntry entry : history) {
            output.println(messages.system(
                    "cli.list_entry",
                    entry.fullBackup() ? "FULL " : "INC  ",
                    entry.snapshotId(),
                    TIME_FORMATTER.format(entry.createdAt()),
                    entry.changedFiles(),
                    entry.deletedFiles(),
                    entry.status(),
                    entry.storageName()));
        }
        return 0;
    }

    private static int verifySnapshot(
            Options options,
            PrintStream output,
            PrintStream error,
            MessageCatalog messages) throws UsageException {
        requireSnapshot(options, messages);
        rejectOption(options.outputDirectory != null, messages, "cli.output_verify");
        rejectOption(options.limitExplicit, messages, "cli.limit_list");
        RestoreService service = new RestoreService(options.storageRoot, options.archiveRoots);
        SnapshotStore.VerificationResult result = service.verify(options.snapshotId);
        if (!result.valid()) {
            error.println(messages.system("cli.verify_failed"));
            result.issues().forEach(issue -> error.println(
                    "- " + messages.system(issue.key(), issue.argumentsArray())));
            return 3;
        }
        output.println(messages.system("cli.verify_valid"));
        output.println(messages.system("cli.chain", String.join(" -> ", result.chain())));
        output.println(messages.system("cli.checked", result.snapshotsChecked(), result.filesChecked()));
        return 0;
    }

    private static int exportSnapshot(Options options, PrintStream output, MessageCatalog messages)
            throws IOException, UsageException {
        requireSnapshot(options, messages);
        if (options.outputDirectory == null) {
            throw usage(messages, "cli.output_required");
        }
        rejectOption(options.limitExplicit, messages, "cli.limit_list");
        RestoreService service = new RestoreService(options.storageRoot, options.archiveRoots);
        ExportProgressPrinter progressPrinter = new ExportProgressPrinter(output, messages);
        RestoreService.RestoreResult result = service.export(
                options.snapshotId,
                options.outputDirectory,
                progressPrinter::accept);
        output.println(messages.system("cli.export_complete", result.outputDirectory()));
        output.println(messages.system(
                "cli.exported",
                result.files(),
                result.worlds(),
                formatBytes(result.bytes()),
                result.snapshotsInChain()));
        output.println(messages.system("cli.live_untouched"));
        return 0;
    }

    private static Options parseOptions(String[] args, int startIndex, MessageCatalog messages)
            throws UsageException {
        Options options = new Options();
        for (int index = startIndex; index < args.length; index++) {
            String option = args[index];
            if (isHelp(option)) {
                throw usage(messages, "cli.help_position");
            }
            if (index + 1 >= args.length) {
                throw usage(messages, "cli.missing_value", option);
            }
            String value = args[++index];
            try {
                switch (option) {
                    case "--store" -> {
                        if (options.storeExplicit) {
                            throw usage(messages, "cli.store_once");
                        }
                        options.storageRoot = Path.of(value);
                        options.storeExplicit = true;
                    }
                    case "--archive" -> options.archiveRoots.add(Path.of(value));
                    case "--snapshot" -> {
                        if (options.snapshotId != null) {
                            throw usage(messages, "cli.snapshot_once");
                        }
                        options.snapshotId = value;
                    }
                    case "--output" -> {
                        if (options.outputDirectory != null) {
                            throw usage(messages, "cli.output_once");
                        }
                        options.outputDirectory = Path.of(value);
                    }
                    case "--limit" -> {
                        if (options.limitExplicit) {
                            throw usage(messages, "cli.limit_once");
                        }
                        options.limit = parseLimit(value, messages);
                        options.limitExplicit = true;
                    }
                    default -> throw usage(messages, "cli.unknown_option", option);
                }
            } catch (InvalidPathException ex) {
                throw usage(messages, "cli.invalid_path", option, value);
            }
        }
        return options;
    }

    private static LanguageSelection selectLanguage(String[] args) throws UsageException {
        List<String> cleaned = new ArrayList<>();
        LanguageMode selected = LanguageMode.AUTO;
        boolean explicit = false;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            String value = null;
            if ("--lang".equalsIgnoreCase(argument)) {
                if (index + 1 >= args.length) {
                    throw new UsageException("--lang requires en or ko");
                }
                value = args[++index];
            } else if (argument.toLowerCase(Locale.ROOT).startsWith("--lang=")) {
                value = argument.substring("--lang=".length());
            }
            if (value == null) {
                cleaned.add(argument);
                continue;
            }
            if (explicit) {
                throw new UsageException("--lang may only be specified once");
            }
            selected = switch (value.toLowerCase(Locale.ROOT)) {
                case "en" -> LanguageMode.ENGLISH;
                case "ko" -> LanguageMode.KOREAN;
                default -> throw new UsageException("--lang must be en or ko");
            };
            explicit = true;
        }
        return new LanguageSelection(selected, cleaned.toArray(String[]::new));
    }

    private static int parseLimit(String value, MessageCatalog messages) throws UsageException {
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > 1000) {
                throw usage(messages, "cli.limit_range");
            }
            return limit;
        } catch (NumberFormatException ex) {
            throw usage(messages, "cli.limit_number");
        }
    }

    private static void requireSnapshot(Options options, MessageCatalog messages) throws UsageException {
        if (options.snapshotId == null || options.snapshotId.isBlank()) {
            throw usage(messages, "cli.snapshot_required");
        }
    }

    private static void rejectOption(
            boolean condition,
            MessageCatalog messages,
            String key) throws UsageException {
        if (condition) {
            throw usage(messages, key);
        }
    }

    private static boolean isHelp(String value) {
        return "help".equalsIgnoreCase(value) || "--help".equalsIgnoreCase(value) || "-h".equalsIgnoreCase(value);
    }

    private static String implementationVersion() {
        String version = TimeMachineCli.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        int unitIndex = -1;
        do {
            value /= 1024.0D;
            unitIndex++;
        } while (value >= 1024.0D && unitIndex < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex]);
    }

    private static void printHelp(PrintStream output, MessageCatalog messages) {
        output.println(messages.system("cli.title"));
        output.println();
        output.println(messages.system("cli.usage"));
        output.println("  java -jar Timemachine.jar restore list [--store <dir>] [--archive <dir>] [--limit <n>]");
        output.println("  java -jar Timemachine.jar restore verify --snapshot <id> [--store <dir>] [--archive <dir>]");
        output.println("  java -jar Timemachine.jar restore export --snapshot <id> --output <dir> [--store <dir>] [--archive <dir>]");
        output.println("  java -jar Timemachine.jar version");
        output.println();
        output.println(messages.system("cli.default_store"));
        output.println(messages.system("cli.archive_help"));
        output.println(messages.system("cli.export_safety"));
        output.println(messages.system("cli.language_help"));
    }

    private static UsageException usage(MessageCatalog messages, String key, Object... arguments) {
        return new UsageException(messages.system(key, arguments));
    }

    private static final class Options {
        private Path storageRoot = DEFAULT_STORE;
        private final List<Path> archiveRoots = new ArrayList<>();
        private String snapshotId;
        private Path outputDirectory;
        private int limit = 20;
        private boolean storeExplicit;
        private boolean limitExplicit;
    }

    private static final class ExportProgressPrinter {
        private final PrintStream output;
        private final MessageCatalog messages;
        private RestoreService.ExportPhase lastPhase;
        private int lastCopyBucket = -1;

        private ExportProgressPrinter(PrintStream output, MessageCatalog messages) {
            this.output = output;
            this.messages = messages;
        }

        private void accept(RestoreService.ExportProgress progress) {
            if (progress.phase() != lastPhase) {
                lastPhase = progress.phase();
                switch (progress.phase()) {
                    case VERIFYING -> output.println(messages.system("cli.phase_verifying"));
                    case RECONSTRUCTING -> output.println(messages.system(
                            "cli.phase_reconstructing", progress.totalItems()));
                    case COPYING -> {
                        output.println(messages.system(
                                "cli.phase_copying", progress.totalItems(), formatBytes(progress.totalBytes())));
                        lastCopyBucket = 0;
                    }
                    case FINAL_VERIFICATION -> output.println(messages.system("cli.phase_final_verify"));
                    case PUBLISHING -> output.println(messages.system("cli.phase_publishing"));
                }
            }
            if (progress.phase() != RestoreService.ExportPhase.COPYING || progress.totalItems() <= 0) {
                return;
            }
            int bucket = Math.min(10, (int) ((long) progress.completedItems() * 10L / progress.totalItems()));
            if (bucket <= lastCopyBucket && progress.completedItems() < progress.totalItems()) {
                return;
            }
            lastCopyBucket = bucket;
            output.println(messages.system(
                    "cli.copy_progress",
                    bucket * 10,
                    progress.completedItems(),
                    progress.totalItems(),
                    formatBytes(progress.completedBytes()),
                    formatBytes(progress.totalBytes())));
        }
    }

    private record LanguageSelection(LanguageMode mode, String[] arguments) {
    }

    private static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        private UsageException(String message) {
            super(message);
        }
    }
}
