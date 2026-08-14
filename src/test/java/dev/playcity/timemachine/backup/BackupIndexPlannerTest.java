package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BackupIndexPlannerTest {
    @Test
    void unscannedScopeIsNeitherDeletedNorRemovedFromIndex() {
        TrackedFileMetadata region = file("worlds/uuid/region/r.0.0.mca", BackupScope.REGION);
        TrackedFileMetadata entities = file("worlds/uuid/entities/r.0.0.mca", BackupScope.ENTITIES);
        Map<String, TrackedFileMetadata> previous = Map.of(
                region.relativePath(), region,
                entities.relativePath(), entities);
        Map<String, TrackedFileMetadata> current = Map.of(region.relativePath(), region);
        Set<String> scanned = Set.of("worlds/uuid/region/");

        assertTrue(BackupIndexPlanner.findDeletions(previous, current, scanned).isEmpty());
        Map<String, TrackedFileMetadata> merged = BackupIndexPlanner.merge(previous, current, scanned);
        assertEquals(2, merged.size());
        assertEquals(entities, merged.get(entities.relativePath()));
    }

    @Test
    void missingFileInScannedScopeBecomesADeletion() {
        TrackedFileMetadata region = file("worlds/uuid/region/r.0.0.mca", BackupScope.REGION);

        assertEquals(
                java.util.List.of(region),
                BackupIndexPlanner.findDeletions(
                        Map.of(region.relativePath(), region),
                        Map.of(),
                        Set.of("worlds/uuid/region/")));
    }

    private TrackedFileMetadata file(String relativePath, BackupScope scope) {
        return new TrackedFileMetadata(
                relativePath,
                "world",
                "minecraft:overworld",
                "uuid",
                "worlds/uuid",
                scope,
                0,
                0,
                100L,
                200L,
                "abcd");
    }
}
