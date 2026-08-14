package dev.playcity.timemachine.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class TimeMachineCommandAccess {
    private static final Map<String, String> ROOT_PERMISSIONS;

    static {
        Map<String, String> permissions = new LinkedHashMap<>();
        permissions.put("backup", TimeMachinePermissions.BACKUP);
        permissions.put("status", TimeMachinePermissions.STATUS);
        permissions.put("doctor", TimeMachinePermissions.DOCTOR);
        permissions.put("history", TimeMachinePermissions.HISTORY);
        permissions.put("verify", TimeMachinePermissions.VERIFY);
        permissions.put("prune", TimeMachinePermissions.PRUNE);
        permissions.put("reconcile", TimeMachinePermissions.RECONCILE);
        permissions.put("reload", TimeMachinePermissions.RELOAD);
        ROOT_PERMISSIONS = Collections.unmodifiableMap(permissions);
    }

    private TimeMachineCommandAccess() {
    }

    static String permissionFor(String command) {
        return ROOT_PERMISSIONS.get(command);
    }

    static List<String> permittedRootCommands(Predicate<String> permissionCheck) {
        return ROOT_PERMISSIONS.entrySet().stream()
                .filter(entry -> permissionCheck.test(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }
}
