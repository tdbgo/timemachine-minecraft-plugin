package dev.playcity.timemachine.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class MessageCatalog {
    public enum Language {
        ENGLISH("en"),
        KOREAN("ko");

        private final String code;

        Language(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Language fromTag(String tag) {
            return tag != null && tag.toLowerCase(Locale.ROOT).startsWith("ko") ? KOREAN : ENGLISH;
        }
    }

    private final LanguageMode mode;
    private final Properties english;
    private final Properties korean;

    public MessageCatalog(LanguageMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.english = load("messages_en.properties");
        this.korean = load("messages_ko.properties");
    }

    public LanguageMode mode() {
        return mode;
    }

    public void send(CommandSender sender, String key, Object... arguments) {
        sender.sendMessage(text(sender, key, arguments));
    }

    public String text(CommandSender sender, String key, Object... arguments) {
        return text(languageFor(sender), key, arguments);
    }

    public String system(String key, Object... arguments) {
        return text(languageFor(null), key, arguments);
    }

    public String english(String key, Object... arguments) {
        return text(Language.ENGLISH, key, arguments);
    }

    public String text(Language language, String key, Object... arguments) {
        Properties selected = language == Language.KOREAN ? korean : english;
        String template = selected.getProperty(key, english.getProperty(key, key));
        String result = template;
        for (int index = 0; index < arguments.length; index++) {
            result = result.replace("{" + index + "}", String.valueOf(arguments[index]));
        }
        return result;
    }

    public String progressDetail(CommandSender sender, String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        String key = switch (detail) {
            case "Saving loaded worlds" -> "progress.detail.saving";
            case "Discovering region files" -> "progress.detail.scanning";
            case "Comparing SHA-256 fingerprints" -> "progress.detail.hashing";
            case "Copying changed region files" -> "progress.detail.copying";
            case "Publishing snapshot metadata and index" -> "progress.detail.committing";
            case "SQLite metadata" -> "progress.detail.metadata";
            case "Scanning complete restore chains" -> "progress.detail.prune_scan";
            case "Verifying retained chains before deletion" -> "progress.detail.prune_verify";
            case "Verifying retained chains before automatic retention" -> "progress.detail.retention_verify";
            default -> null;
        };
        return key == null ? detail : text(sender, key);
    }

    public Language languageFor(CommandSender sender) {
        return switch (mode) {
            case ENGLISH -> Language.ENGLISH;
            case KOREAN -> Language.KOREAN;
            case AUTO -> sender instanceof Player player
                    ? Language.fromTag(player.locale().toLanguageTag())
                    : Language.fromTag(Locale.getDefault().toLanguageTag());
        };
    }

    private static Properties load(String resourceName) {
        Properties properties = new Properties();
        try (InputStream input = MessageCatalog.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing message resource: " + resourceName);
            }
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return properties;
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load message resource: " + resourceName, ex);
        }
    }
}
