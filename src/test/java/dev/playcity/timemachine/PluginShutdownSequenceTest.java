package dev.playcity.timemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginShutdownSequenceTest {
    @Test
    void stopsSchedulingBeforeShuttingDownTheRuntime() {
        List<String> events = new ArrayList<>();

        PluginShutdownSequence.run(
                () -> events.add("scheduler"),
                () -> events.add("manager"));

        assertEquals(List.of("scheduler", "manager"), events);
    }

    @Test
    void stillShutsDownTheRuntimeWhenSchedulerCancellationFails() {
        List<String> events = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> PluginShutdownSequence.run(
                () -> {
                    events.add("scheduler");
                    throw new IllegalStateException("scheduler failure");
                },
                () -> events.add("manager")));

        assertEquals(List.of("scheduler", "manager"), events);
    }
}
