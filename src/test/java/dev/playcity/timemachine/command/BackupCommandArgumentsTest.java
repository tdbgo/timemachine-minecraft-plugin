package dev.playcity.timemachine.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BackupCommandArgumentsTest {
    @Test
    void acceptsTheSimpleBackupCommand() {
        BackupCommandArguments result = BackupCommandArguments.parse(new String[] {"backup"});

        assertFalse(result.fullBackup());
        assertNull(result.worldFilter());
        assertEquals("", result.message());
    }

    @Test
    void parsesWorldFullAndGreedyMessage() {
        BackupCommandArguments result = BackupCommandArguments.parse(
                new String[] {"backup", "world", "--full", "--message", "before", "upgrade"});

        assertTrue(result.fullBackup());
        assertEquals("world", result.worldFilter());
        assertEquals("before upgrade", result.message());
    }

    @Test
    void rejectsUnknownOptionsAndMultipleWorlds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackupCommandArguments.parse(new String[] {"backup", "--quick"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> BackupCommandArguments.parse(new String[] {"backup", "world", "world_nether"}));
    }
}
