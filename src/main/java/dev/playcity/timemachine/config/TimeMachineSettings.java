package dev.playcity.timemachine.config;

import dev.playcity.timemachine.backup.BackupScope;
import dev.playcity.timemachine.backup.ChangeDetectionMode;
import dev.playcity.timemachine.i18n.LanguageMode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public record TimeMachineSettings(
        LanguageMode language,
        Path storageRoot,
        List<Path> archiveRoots,
        DatabaseSettings database,
        List<String> includedWorlds,
        Set<BackupScope> scopes,
        boolean pauseAutosave,
        boolean skipIfNoChange,
        ChangeDetectionMode changeDetectionMode,
        int copyThreads,
        long minimumFreeSpaceBytes,
        boolean scheduleEnabled,
        int intervalMinutes,
        List<LocalTime> dailyTimes,
        List<Integer> monthlyDays,
        List<LocalTime> monthlyTimes,
        ZoneId timezone,
        boolean catchUpOnStartup,
        FullBackupScheduleSettings fullBackupSchedule,
        RetentionSettings retention) {

    public static TimeMachineSettings load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        LanguageMode language = LanguageMode.fromConfig(config.getString("language", "auto"));
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path pluginsFolder = dataFolder.getParent();
        Path serverRoot = pluginsFolder == null ? null : pluginsFolder.getParent();
        if (serverRoot == null) {
            throw new IllegalArgumentException("Could not resolve the server root from the plugin data folder.");
        }

        String storageRootValue = config.getString("storage.root", "plugins/Timemachine/backups");
        requireNonBlank(storageRootValue, "storage.root");
        Path configuredStorageRoot = Paths.get(storageRootValue);
        Path storageRoot = configuredStorageRoot.isAbsolute()
                ? configuredStorageRoot.normalize()
                : serverRoot.resolve(configuredStorageRoot).normalize();
        List<Path> archiveRoots = new ArrayList<>();
        for (String rawPath : config.getStringList("storage.archive-roots")) {
            if (rawPath == null || rawPath.isBlank()) {
                continue;
            }
            Path path = Paths.get(rawPath.trim());
            archiveRoots.add(path.isAbsolute() ? path.normalize() : serverRoot.resolve(path).normalize());
        }

        ConfigurationSection databaseSection = config.getConfigurationSection("database");
        boolean databaseEnabled = databaseSection == null || databaseSection.getBoolean("enabled", true);
        String tablePrefix = normalizeTablePrefix(databaseSection == null
                ? "tm_"
                : databaseSection.getString("table-prefix", "tm_"));
        String sqliteFileValue = databaseSection == null
                ? "plugins/Timemachine/timemachine-meta.db"
                : databaseSection.getString("sqlite.file", "plugins/Timemachine/timemachine-meta.db");
        Path sqliteFilePath = resolveAgainst(serverRoot, sqliteFileValue);
        DatabaseSettings database = new DatabaseSettings(databaseEnabled, sqliteFilePath, tablePrefix);

        List<String> includedWorlds = normalizeWorldSelectors(config.getStringList("backup.include-worlds"));

        EnumSet<BackupScope> scopes = EnumSet.allOf(BackupScope.class);
        if (config.contains("backup.scopes")) {
            scopes.clear();
            List<String> configuredScopes = config.getStringList("backup.scopes");
            if (configuredScopes.isEmpty()) {
                throw new IllegalArgumentException(
                        "backup.scopes must contain at least one of: region, entities, poi.");
            }
            for (String rawScope : configuredScopes) {
                BackupScope scope = BackupScope.fromConfigValue(rawScope);
                if (scope == null) {
                    throw new IllegalArgumentException("Invalid backup.scopes entry: " + rawScope);
                }
                scopes.add(scope);
            }
        }

        boolean pauseAutosave = config.getBoolean("backup.pause-autosave", true);
        boolean skipIfNoChange = config.getBoolean("backup.skip-if-no-change", true);
        ChangeDetectionMode changeDetectionMode = ChangeDetectionMode.fromConfigValue(
                config.getString("backup.change-detection", "safe"));
        int copyThreads = config.getInt("backup.copy-threads", 2);
        if (copyThreads < 1 || copyThreads > 8) {
            throw new IllegalArgumentException("backup.copy-threads must be between 1 and 8.");
        }
        long minimumFreeSpaceMib = config.getLong("backup.minimum-free-space-mib", 1024L);
        if (minimumFreeSpaceMib < 0L) {
            throw new IllegalArgumentException("backup.minimum-free-space-mib must be zero or greater.");
        }
        long minimumFreeSpaceBytes;
        try {
            minimumFreeSpaceBytes = Math.multiplyExact(minimumFreeSpaceMib, 1024L * 1024L);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("backup.minimum-free-space-mib is too large.", ex);
        }

        ConfigurationSection scheduleSection = config.getConfigurationSection("schedule");
        boolean scheduleEnabled = scheduleSection != null && scheduleSection.getBoolean("enabled", false);
        int intervalMinutes = scheduleSection == null ? 0 : scheduleSection.getInt("interval-minutes", 0);
        if (intervalMinutes < 0) {
            throw new IllegalArgumentException("schedule.interval-minutes must be zero or greater.");
        }

        List<LocalTime> dailyTimes = scheduleSection == null
                ? List.of()
                : parseTimes(scheduleSection.getStringList("daily-times"), "schedule.daily-times");

        List<Integer> monthlyDays = scheduleSection == null
                ? List.of()
                : parseMonthDays(scheduleSection.getIntegerList("monthly-days"), "schedule.monthly-days");

        List<LocalTime> monthlyTimes = scheduleSection == null
                ? List.of()
                : parseTimes(scheduleSection.getStringList("monthly-times"), "schedule.monthly-times");
        validatePairedSchedule(monthlyDays, monthlyTimes, "schedule.monthly-days", "schedule.monthly-times");
        validateRegularSchedule(scheduleEnabled, intervalMinutes, dailyTimes, monthlyDays, monthlyTimes);

        String timezoneValue = scheduleSection == null
                ? "system"
                : scheduleSection.getString("timezone", "system");
        ZoneId zoneId = parseTimezone(timezoneValue);

        boolean catchUpOnStartup = scheduleSection == null || scheduleSection.getBoolean("catch-up-on-startup", true);

        ConfigurationSection fullBackupSection = config.getConfigurationSection("full-backup");
        boolean fullBackupEnabled = fullBackupSection != null && fullBackupSection.getBoolean("enabled", false);
        List<Integer> fullMonthlyDays = fullBackupSection == null
                ? List.of()
                : parseMonthDays(fullBackupSection.getIntegerList("monthly-days"), "full-backup.monthly-days");

        List<LocalTime> fullMonthlyTimes = fullBackupSection == null
                ? List.of()
                : parseTimes(fullBackupSection.getStringList("monthly-times"), "full-backup.monthly-times");
        validatePairedSchedule(
                fullMonthlyDays,
                fullMonthlyTimes,
                "full-backup.monthly-days",
                "full-backup.monthly-times");
        if (fullBackupEnabled && fullMonthlyDays.isEmpty()) {
            throw new IllegalArgumentException(
                    "full-backup.enabled is true, but no monthly days and times are configured.");
        }
        boolean fullCatchUpOnStartup = fullBackupSection == null
                || fullBackupSection.getBoolean("catch-up-on-startup", true);

        ConfigurationSection retentionSection = config.getConfigurationSection("retention");
        boolean retentionEnabled = retentionSection != null && retentionSection.getBoolean("enabled", false);
        int retentionMaxChains = retentionSection == null ? 8 : retentionSection.getInt("max-chains", 8);
        int retentionMaxAgeDays = retentionSection == null ? 30 : retentionSection.getInt("max-age-days", 30);
        int retentionMinimumChains = retentionSection == null ? 2 : retentionSection.getInt("minimum-chains", 2);
        if (retentionMinimumChains < 2 || retentionMinimumChains > 1000) {
            throw new IllegalArgumentException("retention.minimum-chains must be between 2 and 1000.");
        }
        if (retentionMaxChains < 0 || retentionMaxChains > 10000) {
            throw new IllegalArgumentException("retention.max-chains must be between 0 and 10000.");
        }
        if (retentionMaxChains > 0 && retentionMaxChains < retentionMinimumChains) {
            throw new IllegalArgumentException(
                    "retention.max-chains must be zero or at least retention.minimum-chains.");
        }
        if (retentionMaxAgeDays < 0 || retentionMaxAgeDays > 36500) {
            throw new IllegalArgumentException("retention.max-age-days must be between 0 and 36500.");
        }
        if (retentionEnabled && retentionMaxChains == 0 && retentionMaxAgeDays == 0) {
            throw new IllegalArgumentException(
                    "retention.enabled requires max-chains or max-age-days to be greater than zero.");
        }
        List<String> pinnedSnapshots = normalizePinnedSnapshots(retentionSection == null
                ? List.of()
                : retentionSection.getStringList("pinned-snapshots"));

        return new TimeMachineSettings(
                language,
                storageRoot,
                List.copyOf(archiveRoots),
                database,
                List.copyOf(includedWorlds),
                Set.copyOf(scopes),
                pauseAutosave,
                skipIfNoChange,
                changeDetectionMode,
                copyThreads,
                minimumFreeSpaceBytes,
                scheduleEnabled,
                intervalMinutes,
                List.copyOf(dailyTimes),
                List.copyOf(monthlyDays),
                List.copyOf(monthlyTimes),
                zoneId,
                catchUpOnStartup,
                new FullBackupScheduleSettings(
                        fullBackupEnabled,
                        List.copyOf(fullMonthlyDays),
                        List.copyOf(fullMonthlyTimes),
                        fullCatchUpOnStartup),
                new RetentionSettings(
                        retentionEnabled,
                        retentionMaxChains,
                        retentionMaxAgeDays,
                        retentionMinimumChains,
                        pinnedSnapshots));
    }

    private static Path resolveAgainst(Path serverRoot, String rawPath) {
        requireNonBlank(rawPath, "database.sqlite.file");
        Path path = Paths.get(rawPath);
        return path.isAbsolute() ? path.normalize() : serverRoot.resolve(path).normalize();
    }

    private static String normalizeTablePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return "tm_";
        }
        String value = raw.trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "database.table-prefix must start with a letter or underscore and contain only ASCII letters, digits, and underscores.");
        }
        if (!value.endsWith("_")) {
            value += "_";
        }
        return value;
    }

    private static List<String> normalizeWorldSelectors(List<String> configuredWorlds) {
        List<String> worlds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String configuredWorld : configuredWorlds) {
            if (configuredWorld == null || configuredWorld.isBlank()) {
                continue;
            }
            String value = configuredWorld.trim();
            if (seen.add(value.toLowerCase(Locale.ROOT))) {
                worlds.add(value);
            }
        }
        return List.copyOf(worlds);
    }

    private static List<String> normalizePinnedSnapshots(List<String> configuredSnapshots) {
        Set<String> snapshots = new LinkedHashSet<>();
        for (String configuredSnapshot : configuredSnapshots) {
            if (configuredSnapshot == null || configuredSnapshot.isBlank()) {
                continue;
            }
            String value = configuredSnapshot.trim();
            if (value.length() > 256
                    || value.indexOf('\0') >= 0
                    || value.indexOf('\t') >= 0
                    || value.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("retention.pinned-snapshots contains an invalid snapshot ID.");
            }
            snapshots.add(value);
        }
        return List.copyOf(snapshots);
    }

    static List<LocalTime> parseTimes(List<String> configuredTimes, String configPath) {
        Set<LocalTime> times = new LinkedHashSet<>();
        for (String rawTime : configuredTimes) {
            String normalized = rawTime == null ? "" : rawTime.trim();
            if (!normalized.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
                throw new IllegalArgumentException(
                        "Invalid " + configPath + " entry; expected HH:mm: " + rawTime);
            }
            try {
                times.add(LocalTime.parse(normalized));
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Invalid " + configPath + " entry: " + rawTime, ex);
            }
        }
        return times.stream().sorted().toList();
    }

    private static List<Integer> parseMonthDays(List<Integer> configuredDays, String configPath) {
        Set<Integer> days = new LinkedHashSet<>();
        for (int day : configuredDays) {
            if (day < 1 || day > 31) {
                throw new IllegalArgumentException(configPath + " entries must be between 1 and 31: " + day);
            }
            days.add(day);
        }
        return days.stream().sorted(Comparator.naturalOrder()).toList();
    }

    static ZoneId parseTimezone(String configuredTimezone) {
        String value = configuredTimezone == null ? "" : configuredTimezone.trim();
        if (value.equalsIgnoreCase("system")) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid schedule.timezone: " + configuredTimezone, ex);
        }
    }

    private static void validatePairedSchedule(
            List<Integer> days,
            List<LocalTime> times,
            String daysPath,
            String timesPath) {
        if (days.isEmpty() != times.isEmpty()) {
            throw new IllegalArgumentException(daysPath + " and " + timesPath + " must either both be empty or both contain values.");
        }
    }

    static void validateRegularSchedule(
            boolean enabled,
            int intervalMinutes,
            List<LocalTime> dailyTimes,
            List<Integer> monthlyDays,
            List<LocalTime> monthlyTimes) {
        if (enabled
                && intervalMinutes == 0
                && dailyTimes.isEmpty()
                && (monthlyDays.isEmpty() || monthlyTimes.isEmpty())) {
            throw new IllegalArgumentException(
                    "schedule.enabled is true, but no interval, daily time, or monthly schedule is configured.");
        }
    }

    public boolean hasRegularSchedule() {
        return intervalMinutes > 0
                || !dailyTimes.isEmpty()
                || (!monthlyDays.isEmpty() && !monthlyTimes.isEmpty());
    }

    private static void requireNonBlank(String value, String configPath) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(configPath + " must not be blank.");
        }
    }

    public record DatabaseSettings(
            boolean enabled,
            Path sqliteFile,
            String tablePrefix) {
    }

    public record FullBackupScheduleSettings(
            boolean enabled,
            List<Integer> monthlyDays,
            List<LocalTime> monthlyTimes,
            boolean catchUpOnStartup) {
    }

    public record RetentionSettings(
            boolean enabled,
            int maxChains,
            int maxAgeDays,
            int minimumChains,
            List<String> pinnedSnapshots) {
    }
}
