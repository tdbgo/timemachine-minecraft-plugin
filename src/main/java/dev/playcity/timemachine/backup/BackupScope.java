package dev.playcity.timemachine.backup;

import java.util.Locale;

public enum BackupScope {
    REGION("region"),
    ENTITIES("entities"),
    POI("poi");

    private final String directoryName;

    BackupScope(String directoryName) {
        this.directoryName = directoryName;
    }

    public String directoryName() {
        return directoryName;
    }

    public static BackupScope fromConfigValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        for (BackupScope scope : values()) {
            if (scope.name().equals(normalized) || scope.directoryName.equalsIgnoreCase(trimmed)) {
                return scope;
            }
        }
        return null;
    }
}
