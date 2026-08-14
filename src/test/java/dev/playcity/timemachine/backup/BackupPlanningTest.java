package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BackupPlanningTest {
    @Test
    void initialUnfilteredBackupBecomesAGlobalFullBaseline() {
        BackupPlanning.Plan plan = BackupPlanning.create(
                CurrentIndexStore.IndexState.baselineRequired(false, false),
                request(false, null));

        assertEquals(SnapshotKind.FULL, plan.kind());
        assertTrue(plan.copyAllFiles());
    }

    @Test
    void refusesAFilteredBackupUntilTheGlobalBaselineExists() {
        assertThrows(
                IllegalStateException.class,
                () -> BackupPlanning.create(
                        CurrentIndexStore.IndexState.baselineRequired(false, false),
                        request(false, "world")));
    }

    @Test
    void distinguishesGlobalScopedFullAndIncrementalRequests() {
        CurrentIndexStore.IndexState committed = CurrentIndexStore.IndexState.committed(
                Map.of(), "2026/full", "2026/full");

        assertEquals(SnapshotKind.FULL, BackupPlanning.create(committed, request(true, null)).kind());
        assertEquals(SnapshotKind.SCOPED_FULL, BackupPlanning.create(committed, request(true, "world")).kind());
        assertEquals(SnapshotKind.INCREMENTAL, BackupPlanning.create(committed, request(false, null)).kind());
    }

    private BackupRequest request(boolean full, String world) {
        return new BackupRequest("test", full, world, "", "junit");
    }
}
