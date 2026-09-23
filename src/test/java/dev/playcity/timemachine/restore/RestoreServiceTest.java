package dev.playcity.timemachine.restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.playcity.timemachine.backup.BackupRequest;
import dev.playcity.timemachine.backup.BackupScope;
import dev.playcity.timemachine.backup.FileHashes;
import dev.playcity.timemachine.backup.SnapshotKind;
import dev.playcity.timemachine.backup.SnapshotStore;
import dev.playcity.timemachine.backup.TrackedFileMetadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreServiceTest {
    private static final String WORLD_UUID = "00000000-0000-0000-0000-000000000001";

    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsTheFinalVerifiedStateWithoutOverwritingAWorld() throws Exception {
        SnapshotFixture fixture = createSnapshotChain();
        Path output = temporaryDirectory.resolve("restore-output");

        RestoreService.RestoreResult result = fixture.service().export("2026/inc", output);

        assertEquals(output.toRealPath(), result.outputDirectory().toRealPath());
        assertEquals(1, result.worlds());
        assertEquals(2, result.files());
        assertEquals(2, result.snapshotsInChain());
        assertEquals("new-region", Files.readString(
                output.resolve("world/region/r.0.0.mca"), StandardCharsets.UTF_8));
        assertEquals("new-entities", Files.readString(
                output.resolve("world/entities/r.2.-3.mca"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(output.resolve("world/region/r.1.0.mca")));
        assertTrue(Files.isRegularFile(output.resolve("restore-worlds.tsv")));
        assertTrue(Files.readString(output.resolve("RESTORE_README.txt"), StandardCharsets.UTF_8)
                .contains("did not modify any Minecraft world"));
    }

    @Test
    void rejectsARestoreThatCannotFitBeforeCopyingFiles() throws Exception {
        assertThrows(IOException.class, () -> RestoreService.ensureRestoreSpace(
                temporaryDirectory,
                Long.MAX_VALUE));
    }

    @Test
    void verifiesAndExportsAFullIncrementalChainIntoThePaper26DimensionLayout() throws Exception {
        Path paperWorldPath = temporaryDirectory.resolve(
                "server/world/dimensions/minecraft/overworld");
        SnapshotFixture fixture = createSnapshotChain(
                "world",
                "minecraft:overworld",
                paperWorldPath);
        Path output = temporaryDirectory.resolve("paper26-restore-output");

        SnapshotStore.VerificationResult verification = fixture.service().verify("2026/inc");
        assertTrue(verification.valid(), () -> String.join("; ", verification.errors()));
        assertEquals(List.of("2026/full", "2026/inc"), verification.chain());

        fixture.service().export("2026/inc", output);

        Path restoredDimension = output.resolve("world/dimensions/minecraft/overworld");
        assertEquals("new-region", Files.readString(
                restoredDimension.resolve("region/r.0.0.mca"), StandardCharsets.UTF_8));
        assertEquals("new-entities", Files.readString(
                restoredDimension.resolve("entities/r.2.-3.mca"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(restoredDimension.resolve("region/r.1.0.mca")));
        assertTrue(Files.readString(output.resolve("restore-worlds.tsv"), StandardCharsets.UTF_8)
                .contains("world/dimensions/minecraft/overworld"));
    }

    @Test
    void exportsNestedCustomPaperDimensionsWithoutPathTraversal() throws Exception {
        SnapshotFixture fixture = createSnapshotChain(
                "cbd",
                "playcity:district/cbd",
                temporaryDirectory.resolve("server/block/dimensions/playcity/district/cbd"));
        Path output = temporaryDirectory.resolve("custom-dimension-output");

        fixture.service().export("2026/inc", output);

        assertEquals("new-region", Files.readString(
                output.resolve("block/dimensions/playcity/district/cbd/region/r.0.0.mca"),
                StandardCharsets.UTF_8));
    }

    @Test
    void refusesExistingAndStorageOverlappingOutputs() throws Exception {
        SnapshotFixture fixture = createSnapshotChain();
        Path existing = temporaryDirectory.resolve("existing");
        Files.createDirectory(existing);

        assertThrows(IOException.class, () -> fixture.service().export("2026/inc", existing));
        assertThrows(IOException.class, () -> fixture.service().export(
                "2026/inc",
                fixture.storageRoot().resolve("restore-output")));
        assertTrue(Files.isDirectory(existing));
    }

    @Test
    void refusesToExportATamperedChain() throws Exception {
        SnapshotFixture fixture = createSnapshotChain();
        Path changedFile = fixture.store().snapshotPath(Path.of("2026", "inc"))
                .resolve("files/worlds/" + WORLD_UUID + "/region/r.0.0.mca");
        Files.writeString(changedFile, "tampered", StandardCharsets.UTF_8);
        Path output = temporaryDirectory.resolve("tampered-output");

        IOException failure = assertThrows(
                IOException.class,
                () -> fixture.service().export("2026/inc", output));

        assertTrue(failure.getMessage().contains("verification failed"));
        assertFalse(Files.exists(output));
    }

    @Test
    void refusesSymbolicLinksInTheOutputPathWhenSupported() throws Exception {
        SnapshotFixture fixture = createSnapshotChain();
        Path realParent = temporaryDirectory.resolve("real-parent");
        Path linkedParent = temporaryDirectory.resolve("linked-parent");
        Files.createDirectory(realParent);
        try {
            Files.createSymbolicLink(linkedParent, realParent);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable in this test environment");
        }

        IOException failure = assertThrows(
                IOException.class,
                () -> fixture.service().export("2026/inc", linkedParent.resolve("restore")));

        assertTrue(failure.getMessage().contains("Symbolic links"));
        assertFalse(Files.exists(realParent.resolve("restore")));
    }

    @Test
    void substitutesAUuidForNonPortableOrMetadataConflictingWorldNames() throws Exception {
        SnapshotFixture fixture = createSnapshotChain("RESTORE_README.txt");
        Path output = temporaryDirectory.resolve("portable-output");

        fixture.service().export("2026/inc", output);

        assertTrue(Files.isRegularFile(output.resolve("RESTORE_README.txt")));
        assertEquals("new-region", Files.readString(
                output.resolve(WORLD_UUID + "/region/r.0.0.mca"), StandardCharsets.UTF_8));
    }

    private SnapshotFixture createSnapshotChain() throws Exception {
        return createSnapshotChain("world");
    }

    private SnapshotFixture createSnapshotChain(String worldName) throws Exception {
        return createSnapshotChain(
                worldName,
                "minecraft:overworld",
                temporaryDirectory.resolve("server-world"));
    }

    private SnapshotFixture createSnapshotChain(
            String worldName,
            String worldKey,
            Path sourcePath) throws Exception {
        Path storageRoot = temporaryDirectory.resolve("backups");
        SnapshotStore store = new SnapshotStore(storageRoot);
        SnapshotStore.SnapshotWorld world = new SnapshotStore.SnapshotWorld(
                WORLD_UUID,
                worldKey,
                worldName,
                "worlds/" + WORLD_UUID,
                sourcePath.toAbsolutePath().normalize().toString());

        Path fullStaging = store.createStagingDirectory("full-staging");
        TrackedFileMetadata original = writeSnapshotFile(
                store, fullStaging, BackupScope.REGION, 0, 0, "old-region", worldName, worldKey);
        TrackedFileMetadata deleted = writeSnapshotFile(
                store, fullStaging, BackupScope.REGION, 1, 0, "deleted-region", worldName, worldKey);
        store.commit(
                fullStaging,
                "2026/full",
                Path.of("2026", "full"),
                new BackupRequest("test", true, null, "", "junit"),
                Instant.parse("2026-01-01T00:00:00Z"),
                SnapshotKind.FULL,
                "",
                "2026/full",
                List.of(world),
                Set.of(BackupScope.REGION, BackupScope.ENTITIES),
                List.of(original, deleted),
                List.of());

        Path incrementalStaging = store.createStagingDirectory("incremental-staging");
        TrackedFileMetadata replacement = writeSnapshotFile(
                store, incrementalStaging, BackupScope.REGION, 0, 0, "new-region", worldName, worldKey);
        TrackedFileMetadata entities = writeSnapshotFile(
                store, incrementalStaging, BackupScope.ENTITIES, 2, -3, "new-entities", worldName, worldKey);
        store.commit(
                incrementalStaging,
                "2026/inc",
                Path.of("2026", "inc"),
                new BackupRequest("test", false, null, "", "junit"),
                Instant.parse("2026-01-02T00:00:00Z"),
                SnapshotKind.INCREMENTAL,
                "2026/full",
                "2026/full",
                List.of(world),
                Set.of(BackupScope.REGION, BackupScope.ENTITIES),
                List.of(replacement, entities),
                List.of(deleted));

        return new SnapshotFixture(storageRoot, store, new RestoreService(storageRoot, List.of()));
    }

    private TrackedFileMetadata writeSnapshotFile(
            SnapshotStore store,
            Path staging,
            BackupScope scope,
            int regionX,
            int regionZ,
            String content,
            String worldName,
            String worldKey) throws Exception {
        String relativePath = "worlds/" + WORLD_UUID + "/" + scope.directoryName()
                + "/r." + regionX + "." + regionZ + ".mca";
        Path file = store.filesDirectory(staging).resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return new TrackedFileMetadata(
                relativePath,
                worldName,
                worldKey,
                WORLD_UUID,
                "worlds/" + WORLD_UUID,
                scope,
                regionX,
                regionZ,
                Files.getLastModifiedTime(file).toMillis(),
                Files.size(file),
                FileHashes.sha256(file));
    }

    private record SnapshotFixture(
            Path storageRoot,
            SnapshotStore store,
            RestoreService service) {
    }
}
