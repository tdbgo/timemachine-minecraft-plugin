package dev.playcity.timemachine.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PluginDescriptorTest {
    @Test
    void registersTheOfficialAndLegacyCommandAliases() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(input);
            String descriptor = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(descriptor.contains("usage: /tmb "));
            assertTrue(descriptor.contains("aliases: [tmb, tm]"));
            assertTrue(descriptor.contains("timemachine.viewer:\n"
                    + "    description: Read-only TimeMachine status and history access\n"
                    + "    default: false\n"
                    + "    children:\n"
                    + "      timemachine.status: true\n"
                    + "      timemachine.doctor: true\n"
                    + "      timemachine.history: true"));
            assertTrue(descriptor.contains("timemachine.operator:\n"
                    + "    description: Routine TimeMachine backup and verification access\n"
                    + "    default: false\n"
                    + "    children:\n"
                    + "      timemachine.viewer: true\n"
                    + "      timemachine.backup: true\n"
                    + "      timemachine.verify: true"));
            assertTrue(descriptor.contains("timemachine.admin:\n"
                    + "    description: Grant all TimeMachine administrative permissions\n"
                    + "    default: op"));
        }
    }
}
