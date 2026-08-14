package dev.playcity.timemachine.i18n;

import java.util.Locale;

public enum LanguageMode {
    AUTO,
    ENGLISH,
    KOREAN;

    public static LanguageMode fromConfig(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "auto", "" -> AUTO;
            case "en", "english" -> ENGLISH;
            case "ko", "korean" -> KOREAN;
            default -> throw new IllegalArgumentException("language must be auto, en, or ko.");
        };
    }
}
