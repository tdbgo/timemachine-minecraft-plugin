package dev.playcity.timemachine.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DefaultConfigTest {
    @Test
    void keepsTheFirstRunConfigFocusedOnEverydayChoices() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(config.contains("change-detection: safe"));
            assertTrue(config.contains("language: auto"));
            assertTrue(config.contains("enabled: false"));
            assertTrue(config.contains("timezone: \"system\""));
            assertFalse(config.contains("table-prefix:"));
            assertFalse(config.contains("pinned-snapshots:"));
        }
    }
}
