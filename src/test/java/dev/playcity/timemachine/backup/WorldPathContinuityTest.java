package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldPathContinuityTest {
    private static final String WORLD_UUID = "00000000-0000-0000-0000-000000000001";

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsTheSameNormalizedAbsoluteWorldPath() {
        Path current = temporaryDirectory.resolve("world/dimensions/minecraft/overworld").toAbsolutePath();
        String recorded = current.resolve("..").resolve("overworld").normalize().toString();

        Set<String> changed = WorldPathContinuity.findChangedWorlds(
                Map.of(WORLD_UUID, recorded),
                Map.of(WORLD_UUID, current));

        assertTrue(changed.isEmpty());
    }

    @Test
    void requiresANewBaselineForMovedOrInvalidRecordedWorldPaths() {
        Path current = temporaryDirectory.resolve("world/dimensions/minecraft/overworld").toAbsolutePath();

        assertEquals(
                Set.of(WORLD_UUID),
                WorldPathContinuity.findChangedWorlds(
                        Map.of(WORLD_UUID, temporaryDirectory.resolve("world").toAbsolutePath().toString()),
                        Map.of(WORLD_UUID, current)));
        assertEquals(
                Set.of(WORLD_UUID),
                WorldPathContinuity.findChangedWorlds(
                        Map.of(WORLD_UUID, "relative/world"),
                        Map.of(WORLD_UUID, current)));
    }

    @Test
    void promotesAnUnfilteredBackupWhenTheLayoutChanged() {
        assertEquals(
                WorldPathContinuity.MigrationAction.PROMOTE_TO_FULL,
                WorldPathContinuity.migrationAction(Set.of(WORLD_UUID), false));
        assertEquals(
                WorldPathContinuity.MigrationAction.NONE,
                WorldPathContinuity.migrationAction(Set.of(), false));
    }

    @Test
    void rejectsAFilteredLayoutMigration() {
        assertEquals(
                WorldPathContinuity.MigrationAction.REJECT_FILTERED,
                WorldPathContinuity.migrationAction(Set.of(WORLD_UUID), true));
    }
}
