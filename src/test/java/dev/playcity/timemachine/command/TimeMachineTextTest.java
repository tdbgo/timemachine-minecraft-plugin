package dev.playcity.timemachine.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TimeMachineTextTest {
    @Test
    void formatsOperatorFacingSizesAndDurations() {
        assertEquals("0 B", TimeMachineText.bytes(0L));
        assertEquals("1.0 KiB", TimeMachineText.bytes(1024L));
        assertEquals("1m 5s", TimeMachineText.duration(Duration.ofSeconds(65L)));
    }

    @Test
    void formatsNeverAndKnownTimes() {
        assertEquals("never", TimeMachineText.summaryTime(Instant.EPOCH, ZoneId.of("UTC")));
        assertEquals(
                "2026-07-22 12:34:56",
                TimeMachineText.time(Instant.parse("2026-07-22T12:34:56Z"), ZoneId.of("UTC")));
    }
}
