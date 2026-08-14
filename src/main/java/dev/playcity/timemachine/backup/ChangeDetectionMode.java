package dev.playcity.timemachine.backup;

import java.util.Locale;

public enum ChangeDetectionMode {
    SAFE,
    FAST;

    public static ChangeDetectionMode fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            return SAFE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "backup.change-detection must be one of: safe, fast.",
                    ex);
        }
    }
}
