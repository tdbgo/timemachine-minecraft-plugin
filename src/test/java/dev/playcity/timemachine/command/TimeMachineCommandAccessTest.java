package dev.playcity.timemachine.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TimeMachineCommandAccessTest {
    @Test
    void mapsEveryRootCommandToItsExistingPermissionNode() {
        assertEquals(TimeMachinePermissions.BACKUP, TimeMachineCommandAccess.permissionFor("backup"));
        assertEquals(TimeMachinePermissions.STATUS, TimeMachineCommandAccess.permissionFor("status"));
        assertEquals(TimeMachinePermissions.DOCTOR, TimeMachineCommandAccess.permissionFor("doctor"));
        assertEquals(TimeMachinePermissions.HISTORY, TimeMachineCommandAccess.permissionFor("history"));
        assertEquals(TimeMachinePermissions.VERIFY, TimeMachineCommandAccess.permissionFor("verify"));
        assertEquals(TimeMachinePermissions.PRUNE, TimeMachineCommandAccess.permissionFor("prune"));
        assertEquals(TimeMachinePermissions.RECONCILE, TimeMachineCommandAccess.permissionFor("reconcile"));
        assertEquals(TimeMachinePermissions.RELOAD, TimeMachineCommandAccess.permissionFor("reload"));
    }

    @Test
    void suggestionsExposeOnlyCommandsGrantedToTheSender() {
        Set<String> granted = Set.of(TimeMachinePermissions.STATUS, TimeMachinePermissions.HISTORY);

        assertEquals(
                List.of("status", "history"),
                TimeMachineCommandAccess.permittedRootCommands(granted::contains));
    }
}
