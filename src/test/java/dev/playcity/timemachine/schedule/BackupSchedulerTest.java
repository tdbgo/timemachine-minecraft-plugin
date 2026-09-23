package dev.playcity.timemachine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.playcity.timemachine.backup.BackupRequest;
import dev.playcity.timemachine.backup.BackupScope;
import dev.playcity.timemachine.backup.ChangeDetectionMode;
import dev.playcity.timemachine.config.TimeMachineSettings;
import dev.playcity.timemachine.i18n.LanguageMode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BackupSchedulerTest {
    @Test
    void startupCatchupUsesTheLatestSnapshotEvenWhenItIsFull() {
        FakeOperations operations = new FakeOperations(
                Optional.of(Instant.parse("2026-01-01T05:00:00Z")),
                Optional.of(Instant.parse("2026-01-01T05:00:00Z")));
        BackupScheduler scheduler = new BackupScheduler(null, operations, settings(ZoneId.of("UTC")));
        ZonedDateTime now = ZonedDateTime.parse("2026-01-02T05:00:00Z");
        scheduler.initializeAt(now);

        scheduler.tickAt(now);

        assertEquals(1, operations.requests.size());
        assertEquals("catchup", operations.requests.getFirst().trigger());
        assertTrue(operations.requests.getFirst().message().contains("daily"));
    }

    @Test
    void startupCatchupQueuesOnlyTheLatestMissedRegularSlot() {
        FakeOperations operations = new FakeOperations(
                Optional.of(Instant.parse("2026-01-01T00:00:00Z")),
                Optional.empty());
        BackupScheduler scheduler = new BackupScheduler(null, operations, settings(ZoneId.of("UTC")));
        ZonedDateTime now = ZonedDateTime.parse("2026-01-05T05:00:00Z");
        scheduler.initializeAt(now);

        scheduler.tickAt(now);

        assertEquals(1, operations.requests.size());
        assertEquals("catchup", operations.requests.getFirst().trigger());
    }

    @Test
    void aLongRuntimePauseQueuesOnlyTheLatestRegularSlot() {
        FakeOperations operations = new FakeOperations(Optional.empty(), Optional.empty());
        BackupScheduler scheduler = new BackupScheduler(null, operations, settings(ZoneId.of("UTC")));

        scheduler.initializeAt(ZonedDateTime.parse("2026-01-01T00:00:00Z"));
        scheduler.tickAt(ZonedDateTime.parse("2026-01-05T05:00:00Z"));

        assertEquals(1, operations.requests.size());
        assertEquals("daily", operations.requests.getFirst().trigger());
    }

    @Test
    void nextDailyRunRemainsValidAcrossADaylightSavingGap() {
        ZoneId zone = ZoneId.of("America/New_York");
        BackupScheduler scheduler = new BackupScheduler(
                null,
                new FakeOperations(Optional.empty(), Optional.empty()),
                settings(zone, LocalTime.of(2, 30)));

        BackupScheduler.ScheduledRun next = scheduler.nextRunAt(
                        ZonedDateTime.of(2026, 3, 8, 1, 55, 0, 0, zone))
                .orElseThrow();

        assertTrue(next.at().isAfter(ZonedDateTime.of(2026, 3, 8, 1, 55, 0, 0, zone)));
        assertEquals(3, next.at().getHour());
    }

    @Test
    void stopPreventsACatchupFromStartingAfterShutdownBegins() {
        FakeOperations operations = new FakeOperations(
                Optional.of(Instant.parse("2026-01-01T00:00:00Z")),
                Optional.empty());
        BackupScheduler scheduler = new BackupScheduler(null, operations, settings(ZoneId.of("UTC")));
        ZonedDateTime now = ZonedDateTime.parse("2026-01-05T05:00:00Z");
        scheduler.initializeAt(now);

        scheduler.stop();
        scheduler.tickAt(now);

        assertTrue(operations.requests.isEmpty());
    }

    private TimeMachineSettings settings(ZoneId zone) {
        return settings(zone, LocalTime.of(4, 0));
    }

    private TimeMachineSettings settings(ZoneId zone, LocalTime dailyTime) {
        return new TimeMachineSettings(
                LanguageMode.AUTO,
                Path.of("backups"),
                List.of(),
                new TimeMachineSettings.DatabaseSettings(false, Path.of("metadata.db"), "tm_"),
                List.of(),
                Set.of(BackupScope.REGION, BackupScope.ENTITIES, BackupScope.POI),
                true,
                true,
                ChangeDetectionMode.SAFE,
                2,
                0L,
                true,
                0,
                List.of(dailyTime),
                List.of(),
                List.of(),
                zone,
                true,
                new TimeMachineSettings.FullBackupScheduleSettings(false, List.of(), List.of(), true),
                new TimeMachineSettings.RetentionSettings(false, 8, 30, 2, List.of()));
    }

    private static final class FakeOperations implements BackupScheduleOperations {
        private final Optional<Instant> latestSnapshot;
        private final Optional<Instant> latestFull;
        private final List<BackupRequest> requests = new ArrayList<>();

        private FakeOperations(Optional<Instant> latestSnapshot, Optional<Instant> latestFull) {
            this.latestSnapshot = latestSnapshot;
            this.latestFull = latestFull;
        }

        @Override
        public Optional<Instant> latestSnapshotTime() {
            return latestSnapshot;
        }

        @Override
        public Optional<Instant> latestFullSnapshotTime() {
            return latestFull;
        }

        @Override
        public boolean startScheduledBackup(BackupRequest request) {
            requests.add(request);
            return true;
        }
    }
}
