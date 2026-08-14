package dev.playcity.timemachine.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigAutoMerger {
    private static final DateTimeFormatter BACKUP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    private ConfigAutoMerger() {
    }

    public static ConfigMergeResult merge(JavaPlugin plugin, Path configPath) throws IOException, InvalidConfigurationException {
        if (Files.notExists(configPath)) {
            return new ConfigMergeResult(false, null, 0, 0, List.of());
        }

        YamlConfiguration current = new YamlConfiguration();
        current.options().parseComments(true);
        current.load(configPath.toFile());

        YamlConfiguration defaults = loadDefaultConfig(plugin);
        int defaultVersion = Math.max(1, defaults.getInt("config-version", 1));
        int currentVersion = Math.max(0, current.getInt("config-version", 0));
        validateConfigVersion(currentVersion, defaultVersion);
        List<String> warnings = new ArrayList<>();

        boolean changed = applyLegacyMigrations(current, warnings);
        if (mergeSection(current, defaults, "", warnings)) {
            changed = true;
        }
        if (currentVersion < defaultVersion) {
            current.set("config-version", defaultVersion);
            changed = true;
        }

        if (!changed) {
            return new ConfigMergeResult(false, null, currentVersion, defaultVersion, List.copyOf(warnings));
        }

        Files.createDirectories(configPath.getParent());
        Path backupPath = createBackup(configPath);
        Files.copy(configPath, backupPath);
        forceFile(backupPath);
        Path temporaryPath = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        Files.deleteIfExists(temporaryPath);
        try {
            current.save(temporaryPath.toFile());
            try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(
                        temporaryPath,
                        configPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporaryPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            forceFile(configPath);
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
        return new ConfigMergeResult(true, backupPath, currentVersion, defaultVersion, List.copyOf(warnings));
    }

    private static boolean applyLegacyMigrations(YamlConfiguration current, List<String> warnings) {
        boolean changed = false;
        changed |= migrateKey(current, "schedule.clock-times", "schedule.daily-times", warnings);
        changed |= migrateKey(
                current,
                "schedule.run-on-startup-if-missed",
                "schedule.catch-up-on-startup",
                warnings);
        changed |= migrateKey(current, "backup.max-copy-threads", "backup.copy-threads", warnings);
        return changed;
    }

    static void validateConfigVersion(int currentVersion, int supportedVersion)
            throws InvalidConfigurationException {
        if (currentVersion > supportedVersion) {
            throw new InvalidConfigurationException(
                    "config.yml version " + currentVersion
                            + " is newer than this plugin supports (" + supportedVersion + ").");
        }
    }

    private static boolean migrateKey(
            YamlConfiguration current,
            String legacyPath,
            String replacementPath,
            List<String> warnings) {
        if (!current.contains(legacyPath) || current.contains(replacementPath)) {
            return false;
        }
        current.set(replacementPath, current.get(legacyPath));
        warnings.add("Migrated legacy config key '" + legacyPath + "' to '" + replacementPath + "'.");
        return true;
    }

    private static YamlConfiguration loadDefaultConfig(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        InputStream inputStream = plugin.getResource("config.yml");
        if (inputStream == null) {
            throw new IOException("Missing bundled config.yml resource.");
        }

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.options().parseComments(true);
        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            defaults.load(reader);
        }
        return defaults;
    }

    static boolean mergeSection(
            ConfigurationSection current,
            ConfigurationSection defaults,
            String pathPrefix,
            List<String> warnings) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            String path = pathPrefix.isBlank() ? key : pathPrefix + "." + key;
            ConfigurationSection defaultChild = defaults.getConfigurationSection(key);
            if (defaultChild != null) {
                ConfigurationSection currentChild = current.getConfigurationSection(key);
                if (currentChild == null) {
                    if (current.contains(key)) {
                        warnings.add("Skipped auto-merge for '" + path + "' because the existing value is not a section.");
                        continue;
                    }
                    currentChild = current.createSection(key);
                    changed = true;
                }
                if (mergeSection(currentChild, defaultChild, path, warnings)) {
                    changed = true;
                }
                continue;
            }

            if (!current.contains(key)) {
                current.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }

    private static Path createBackup(Path configPath) {
        String stem = "config.backup-" + BACKUP_FORMATTER.format(LocalDateTime.now());
        Path candidate = configPath.getParent().resolve(stem + ".yml");
        for (int suffix = 1; Files.exists(candidate); suffix++) {
            candidate = configPath.getParent().resolve(stem + "-" + suffix + ".yml");
        }
        return candidate;
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }
}
