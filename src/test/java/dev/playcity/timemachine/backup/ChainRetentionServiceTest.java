package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChainRetentionServiceTest {
    private static final String WORLD_UUID = "00000000-0000-0000-0000-000000000001";

    @TempDir
    Path temporaryDirectory;

    @Test
    void prunesOnlyAnEntireOldChainAndKeepsTwoVerifiedChains() throws Exception {
        Fixture fixture = createThreeChains();
        ChainRetentionService.Policy policy = new ChainRetentionService.Policy(2, 0, 2, Set.of());

        ChainRetentionService.PrunePlan plan = fixture.service().plan(
                policy,
                "2026/c-full",
                Instant.parse("2026-04-01T00:00:00Z"));

        assertEquals(1, plan.deleteChains().size());
        assertEquals("2026/a-full", plan.deleteChains().getFirst().baseSnapshotId());
        assertEquals(2, plan.snapshotsToDelete());
        ChainRetentionService.PruneResult result = fixture.service().apply(plan, policy, "2026/c-full");
        assertEquals(1, result.deletedChains());
        assertEquals(2, result.deletedSnapshots());
        assertNull(result.recoverableTrash());
        assertFalse(fixture.store().containsSnapshot("2026/a-full"));
        assertFalse(fixture.store().containsSnapshot("2026/a-inc"));
        assertTrue(fixture.store().verifySnapshot("2026/b-inc").valid());
        assertTrue(fixture.store().verifySnapshot("2026/c-inc").valid());
    }

    @Test
    void pinningAnySnapshotProtectsItsWholeChain() throws Exception {
        Fixture fixture = createThreeChains();
        ChainRetentionService.Policy policy = new ChainRetentionService.Policy(
                2,
                0,
                2,
                Set.of("2026/a-inc"));

        ChainRetentionService.PrunePlan plan = fixture.service().plan(
                policy,
                "2026/c-full",
                Instant.parse("2026-04-01T00:00:00Z"));

        assertEquals(List.of("2026/b-full"), plan.deleteChains().stream()
                .map(ChainRetentionService.ChainPlan::baseSnapshotId)
                .toList());
        assertTrue(plan.retainedChains().stream()
                .filter(chain -> chain.baseSnapshotId().equals("2026/a-full"))
                .allMatch(ChainRetentionService.ChainPlan::protectedChain));
    }

    @Test
    void refusesConfirmationWhenInventoryChangedAfterPlanning() throws Exception {
        Fixture fixture = createThreeChains();
        ChainRetentionService.Policy policy = new ChainRetentionService.Policy(2, 0, 2, Set.of());
        ChainRetentionService.PrunePlan plan = fixture.service().plan(
                policy,
                "2026/c-full",
                Instant.parse("2026-04-01T00:00:00Z"));
        createChain(
                fixture.store(),
                "d",
                Instant.parse("2026-04-10T00:00:00Z"));

        IOException failure = assertThrows(
                IOException.class,
                () -> fixture.service().apply(plan, policy, "2026/c-full"));

        assertTrue(failure.getMessage().contains("inventory changed"));
        assertTrue(fixture.store().containsSnapshot("2026/a-full"));
    }

    @Test
    void incompleteChainsDisablePruningInsteadOfDeletingAroundThem() throws Exception {
        Fixture fixture = createThreeChains();
        Path missingBase = fixture.store().snapshotPath(Path.of("2026", "a-full"));
        try (var paths = Files.walk(missingBase)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }

        assertThrows(
                IOException.class,
                () -> fixture.service().plan(
                        new ChainRetentionService.Policy(2, 0, 2, Set.of()),
                        "2026/c-full",
                        Instant.parse("2026-04-01T00:00:00Z")));
        assertTrue(fixture.store().containsSnapshot("2026/a-inc"));
        assertTrue(fixture.store().containsSnapshot("2026/b-full"));
    }

    private Fixture createThreeChains() throws Exception {
        Path storageRoot = temporaryDirectory.resolve("backups");
        SnapshotStore store = new SnapshotStore(storageRoot);
        createChain(store, "a", Instant.parse("2026-01-01T00:00:00Z"));
        createChain(store, "b", Instant.parse("2026-02-01T00:00:00Z"));
        createChain(store, "c", Instant.parse("2026-03-01T00:00:00Z"));
        return new Fixture(store, new ChainRetentionService(storageRoot, List.of()));
    }

    private void createChain(SnapshotStore store, String name, Instant createdAt) throws Exception {
        SnapshotStore.SnapshotWorld world = new SnapshotStore.SnapshotWorld(
                WORLD_UUID,
                "minecraft:overworld",
                "world",
                "worlds/" + WORLD_UUID,
                temporaryDirectory.resolve("world").toString());
        String fullId = "2026/" + name + "-full";
        String incrementalId = "2026/" + name + "-inc";

        Path fullStaging = store.createStagingDirectory(name + "-full-staging");
        TrackedFileMetadata fullFile = writeFile(store, fullStaging, name + "-full");
        store.commit(
                fullStaging,
                fullId,
                Path.of("2026", name + "-full"),
                new BackupRequest("test", true, null, "", "junit"),
                createdAt,
                SnapshotKind.FULL,
                "",
                fullId,
                List.of(world),
                Set.of(BackupScope.REGION),
                List.of(fullFile),
                List.of());

        Path incrementalStaging = store.createStagingDirectory(name + "-inc-staging");
        TrackedFileMetadata incrementalFile = writeFile(store, incrementalStaging, name + "-inc");
        store.commit(
                incrementalStaging,
                incrementalId,
                Path.of("2026", name + "-inc"),
                new BackupRequest("test", false, null, "", "junit"),
                createdAt.plus(1, ChronoUnit.DAYS),
                SnapshotKind.INCREMENTAL,
                fullId,
                fullId,
                List.of(world),
                Set.of(BackupScope.REGION),
                List.of(incrementalFile),
                List.of());
    }

    private TrackedFileMetadata writeFile(SnapshotStore store, Path staging, String content) throws Exception {
        String relativePath = "worlds/" + WORLD_UUID + "/region/r.0.0.mca";
        Path file = store.filesDirectory(staging).resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return new TrackedFileMetadata(
                relativePath,
                "world",
                "minecraft:overworld",
                WORLD_UUID,
                "worlds/" + WORLD_UUID,
                BackupScope.REGION,
                0,
                0,
                Files.getLastModifiedTime(file).toMillis(),
                Files.size(file),
                FileHashes.sha256(file));
    }

    private record Fixture(SnapshotStore store, ChainRetentionService service) {
    }
}
