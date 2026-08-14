package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutosaveSessionCoordinatorTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void aQueuedWorkerRestoreDoesNotStealStateBeforeTheMainThreadRunsIt() {
        AutosaveSessionCoordinator coordinator = new AutosaveSessionCoordinator();
        List<Map<UUID, Boolean>> restored = new ArrayList<>();
        coordinator.update(Map.of(WORLD_ID, true));

        AutosaveSessionCoordinator.Restoration queuedWorkerRestore =
                coordinator.restorationTask(restored::add);

        assertTrue(coordinator.hasPendingState());
        coordinator.restorationTask(restored::add).run();
        queuedWorkerRestore.run();

        assertEquals(List.of(Map.of(WORLD_ID, true)), restored);
        assertTrue(queuedWorkerRestore.completion().isDone());
        assertFalse(coordinator.hasPendingState());
    }

    @Test
    void theLatestPartialCaptureCanStillBeRestoredAfterPreparationFails() {
        AutosaveSessionCoordinator coordinator = new AutosaveSessionCoordinator();
        UUID secondWorld = UUID.fromString("00000000-0000-0000-0000-000000000002");
        List<Map<UUID, Boolean>> restored = new ArrayList<>();

        coordinator.update(Map.of(WORLD_ID, true));
        coordinator.update(Map.of(WORLD_ID, true, secondWorld, false));
        coordinator.restorationTask(restored::add).run();

        assertEquals(Map.of(WORLD_ID, true, secondWorld, false), restored.getFirst());
    }
}
