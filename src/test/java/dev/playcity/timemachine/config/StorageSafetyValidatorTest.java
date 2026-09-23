package dev.playcity.timemachine.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.playcity.timemachine.backup.BackupScope;
import dev.playcity.timemachine.backup.ChangeDetectionMode;
import dev.playcity.timemachine.i18n.LanguageMode;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageSafetyValidatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsBackupOrDatabasePathsThatContainAWorld() {
        Path world = temporaryDirectory.resolve("server/world");
        TimeMachineSettings settings = settings(
                temporaryDirectory.resolve("server"),
                temporaryDirectory.resolve("server/world/metadata.db"));

        List<String> issues = StorageSafetyValidator.validate(
                settings,
                List.of(new StorageSafetyValidator.WorldPath("world", world)));

        assertTrue(issues.stream().filter(issue -> issue.contains("overlaps world 'world'")).count() >= 2);
    }

    @Test
    void rejectsASqliteFileInsideTheBackupStore() {
        Path storage = temporaryDirectory.resolve("backups");
        TimeMachineSettings settings = settings(storage, storage.resolve("metadata.db"));

        List<String> issues = StorageSafetyValidator.validate(settings, List.of());

        assertTrue(issues.stream().anyMatch(issue -> issue.contains("SQLite path")
                && issue.contains("overlaps backup storage")));
    }

    private TimeMachineSettings settings(Path storageRoot, Path databaseFile) {
        return new TimeMachineSettings(
                LanguageMode.AUTO,
                storageRoot,
                List.of(),
                new TimeMachineSettings.DatabaseSettings(true, databaseFile, "tm_"),
                List.of(),
                Set.of(BackupScope.REGION),
                true,
                true,
                ChangeDetectionMode.SAFE,
                2,
                0L,
                false,
                0,
                List.of(),
                List.of(),
                List.of(),
                ZoneId.of("UTC"),
                false,
                new TimeMachineSettings.FullBackupScheduleSettings(false, List.of(), List.of(), false),
                new TimeMachineSettings.RetentionSettings(false, 8, 30, 2, List.of()));
    }
}
