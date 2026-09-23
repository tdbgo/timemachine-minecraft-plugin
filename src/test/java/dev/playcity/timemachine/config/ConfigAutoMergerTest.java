package dev.playcity.timemachine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigAutoMergerTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsAConfigurationFromANewerPluginVersion() {
        assertThrows(
                InvalidConfigurationException.class,
                () -> ConfigAutoMerger.validateConfigVersion(8, 7));
    }

    @Test
    void addsMissingDefaultsWithoutReplacingOperatorValuesOrUnknownKeys() throws Exception {
        YamlConfiguration current = new YamlConfiguration();
        current.loadFromString("schedule:\n  enabled: true\ncustom-key: keep\n");
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString("schedule:\n  enabled: false\n  timezone: system\n");
        List<String> warnings = new ArrayList<>();

        assertTrue(ConfigAutoMerger.mergeSection(current, defaults, "", warnings));

        assertTrue(current.getBoolean("schedule.enabled"));
        assertEquals("system", current.getString("schedule.timezone"));
        assertEquals("keep", current.getString("custom-key"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void migratesLegacyFileWithBackupAndIsIdempotent() throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        String original = """
                # Keep this operator note.
                config-version: 6
                language: ko
                backup:
                  change-detection: fast
                  max-copy-threads: 3
                schedule:
                  enabled: true
                  clock-times:
                    - "02:30"
                  run-on-startup-if-missed: false
                custom-key: keep
                """;
        Files.writeString(configPath, original, StandardCharsets.UTF_8);
        YamlConfiguration defaults = defaults();

        ConfigMergeResult result = ConfigAutoMerger.merge(configPath, defaults);

        assertTrue(result.changed());
        assertEquals(6, result.fromVersion());
        assertEquals(7, result.toVersion());
        assertNotNull(result.backupPath());
        assertEquals(original, Files.readString(result.backupPath(), StandardCharsets.UTF_8));
        assertEquals(3, result.warnings().size());
        String mergedText = Files.readString(configPath, StandardCharsets.UTF_8);
        assertTrue(mergedText.contains("# Keep this operator note."));
        YamlConfiguration merged = YamlConfiguration.loadConfiguration(configPath.toFile());
        assertEquals(7, merged.getInt("config-version"));
        assertEquals("ko", merged.getString("language"));
        assertEquals("fast", merged.getString("backup.change-detection"));
        assertEquals(3, merged.getInt("backup.copy-threads"));
        assertEquals(List.of("02:30"), merged.getStringList("schedule.daily-times"));
        assertFalse(merged.getBoolean("schedule.catch-up-on-startup"));
        assertEquals("system", merged.getString("schedule.timezone"));
        assertEquals("keep", merged.getString("custom-key"));

        ConfigMergeResult second = ConfigAutoMerger.merge(configPath, defaults);
        assertFalse(second.changed());
        assertEquals(mergedText, Files.readString(configPath, StandardCharsets.UTF_8));
        try (var files = Files.list(tempDir)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void migratesVersionlessLegacyConfig() throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        Files.writeString(configPath, "language: ko\n", StandardCharsets.UTF_8);

        ConfigMergeResult result = ConfigAutoMerger.merge(configPath, defaults());

        assertTrue(result.changed());
        assertEquals(0, result.fromVersion());
        assertEquals(7, result.toVersion());
        assertEquals("ko", YamlConfiguration.loadConfiguration(configPath.toFile()).getString("language"));
    }

    @Test
    void keepsModernValueWhenLegacyAndModernKeysCoexist() throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        Files.writeString(configPath, """
                config-version: 6
                schedule:
                  clock-times: ["02:30"]
                  daily-times: ["05:00"]
                """, StandardCharsets.UTF_8);

        ConfigAutoMerger.merge(configPath, defaults());

        YamlConfiguration merged = YamlConfiguration.loadConfiguration(configPath.toFile());
        assertEquals(List.of("05:00"), merged.getStringList("schedule.daily-times"));
        assertEquals(7, merged.getInt("config-version"));
    }

    @Test
    void rejectsNewerConfigWithoutChangingOrBackingUpItsFile() throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        String original = "config-version: 8\nschedule:\n  enabled: true\n";
        Files.writeString(configPath, original, StandardCharsets.UTF_8);

        assertThrows(InvalidConfigurationException.class, () -> ConfigAutoMerger.merge(configPath, defaults()));

        assertEquals(original, Files.readString(configPath, StandardCharsets.UTF_8));
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void rejectsMalformedConfigWithoutChangingOrBackingUpItsFile() throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        String original = "schedule: [unterminated\n";
        Files.writeString(configPath, original, StandardCharsets.UTF_8);

        assertThrows(InvalidConfigurationException.class, () -> ConfigAutoMerger.merge(configPath, defaults()));

        assertEquals(original, Files.readString(configPath, StandardCharsets.UTF_8));
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void rejectsNonNumericVersionWithoutRewritingConfig() throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        String original = "config-version: \"8\"\nschedule:\n  enabled: true\n";
        Files.writeString(configPath, original, StandardCharsets.UTF_8);

        assertThrows(InvalidConfigurationException.class, () -> ConfigAutoMerger.merge(configPath, defaults()));

        assertEquals(original, Files.readString(configPath, StandardCharsets.UTF_8));
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void rejectsSectionConflictWithoutRewritingConfig() throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        String original = "config-version: 6\nschedule: disabled\n";
        Files.writeString(configPath, original, StandardCharsets.UTF_8);

        assertThrows(InvalidConfigurationException.class, () -> ConfigAutoMerger.merge(configPath, defaults()));

        assertEquals(original, Files.readString(configPath, StandardCharsets.UTF_8));
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count());
        }
    }

    private static YamlConfiguration defaults() throws IOException, InvalidConfigurationException {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.options().parseComments(true);
        InputStream stream = ConfigAutoMergerTest.class.getClassLoader().getResourceAsStream("config.yml");
        if (stream == null) {
            throw new IOException("Missing bundled config.yml resource.");
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            defaults.load(reader);
        }
        return defaults;
    }
}
