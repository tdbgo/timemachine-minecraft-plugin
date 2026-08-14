package dev.playcity.timemachine.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MessageCatalogTest {
    @Test
    void formatsEnglishAndKoreanMessages() {
        MessageCatalog english = new MessageCatalog(LanguageMode.ENGLISH);
        MessageCatalog korean = new MessageCatalog(LanguageMode.KOREAN);

        assertEquals("Unknown option: --quick", english.system("command.unknown_option", "--quick"));
        assertEquals("알 수 없는 옵션: --quick", korean.system("command.unknown_option", "--quick"));
        LocalizedMessage issue = LocalizedMessage.of("verify.issue.sha256_mismatch", "world/region/r.0.0.mca");
        assertEquals(
                "SHA-256 mismatch for world/region/r.0.0.mca",
                english.system(issue.key(), issue.argumentsArray()));
        assertEquals(
                "world/region/r.0.0.mca의 SHA-256이 일치하지 않습니다.",
                korean.system(issue.key(), issue.argumentsArray()));
    }

    @Test
    void keepsBothCatalogsOnTheSameKeySet() throws IOException {
        Properties english = load("messages_en.properties");
        Properties korean = load("messages_ko.properties");

        assertFalse(english.isEmpty());
        assertEquals(english.stringPropertyNames(), korean.stringPropertyNames());
        english.stringPropertyNames().stream()
                .filter(key -> key.startsWith("verify.issue.") || key.startsWith("retention.warning."))
                .forEach(key -> assertNotEquals(
                        english.getProperty(key),
                        korean.getProperty(key),
                        () -> "Operator-facing diagnostic was not translated: " + key));
    }

    @Test
    void acceptsOnlySupportedConfigurationValues() {
        assertEquals(LanguageMode.AUTO, LanguageMode.fromConfig("auto"));
        assertEquals(LanguageMode.ENGLISH, LanguageMode.fromConfig("en"));
        assertEquals(LanguageMode.KOREAN, LanguageMode.fromConfig("ko"));
        assertThrows(IllegalArgumentException.class, () -> LanguageMode.fromConfig("fr"));
    }

    private Properties load(String resourceName) throws IOException {
        Properties properties = new Properties();
        try (var input = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + resourceName);
            }
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }
}
