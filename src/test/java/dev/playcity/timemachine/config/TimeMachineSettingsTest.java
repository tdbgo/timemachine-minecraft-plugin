package dev.playcity.timemachine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimeMachineSettingsTest {
    @Test
    void acceptsSystemAndIanaTimezones() {
        assertEquals(ZoneId.systemDefault(), TimeMachineSettings.parseTimezone("system"));
        assertEquals(ZoneId.of("Asia/Seoul"), TimeMachineSettings.parseTimezone("Asia/Seoul"));
    }

    @Test
    void rejectsInvalidTimezones() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TimeMachineSettings.parseTimezone("local-time"));
    }

    @Test
    void rejectsAnEnabledScheduleWithoutAnyTrigger() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TimeMachineSettings.validateRegularSchedule(
                        true, 0, List.of(), List.of(), List.of()));
    }

    @Test
    void acceptsEachSupportedRegularScheduleType() {
        TimeMachineSettings.validateRegularSchedule(true, 30, List.of(), List.of(), List.of());
        TimeMachineSettings.validateRegularSchedule(
                true, 0, List.of(LocalTime.of(4, 0)), List.of(), List.of());
        TimeMachineSettings.validateRegularSchedule(
                true, 0, List.of(), List.of(1), List.of(LocalTime.of(4, 0)));
    }

    @Test
    void scheduleTimesUseTheDocumentedHourAndMinuteFormat() {
        assertEquals(
                List.of(LocalTime.of(4, 0)),
                TimeMachineSettings.parseTimes(List.of("04:00"), "schedule.daily-times"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TimeMachineSettings.parseTimes(
                        List.of("04:00:30"), "schedule.daily-times"));
    }
}
