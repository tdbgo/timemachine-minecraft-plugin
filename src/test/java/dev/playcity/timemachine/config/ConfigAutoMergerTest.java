package dev.playcity.timemachine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigAutoMergerTest {
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
}
