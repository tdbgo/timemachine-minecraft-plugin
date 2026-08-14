package dev.playcity.timemachine.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimeMachineCommandReferenceTest {
    @Test
    void usesTheConflictFreeOfficialShortAlias() {
        assertEquals("/tmb", TimeMachineCommandReference.PRIMARY_COMMAND);
        assertEquals("/tmb backup", TimeMachineCommandReference.command("backup"));
    }

    @Test
    void formatsAnEmptyCommandWithoutTrailingWhitespace() {
        assertEquals("/tmb", TimeMachineCommandReference.command(""));
        assertEquals("/tmb", TimeMachineCommandReference.command(null));
    }
}
