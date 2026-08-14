package dev.playcity.timemachine.backup;

import dev.playcity.timemachine.config.TimeMachineSettings;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.World;

final class BackupPlanning {
    private BackupPlanning() {
    }

    static Plan create(CurrentIndexStore.IndexState state, BackupRequest request) {
        if (state.baselineRequired()) {
            if (hasWorldFilter(request)) {
                throw new IllegalStateException("A global FULL baseline is required before filtered backups.");
            }
            return new Plan(SnapshotKind.FULL, true);
        }
        if (request.fullBackup() && !hasWorldFilter(request)) {
            return new Plan(SnapshotKind.FULL, true);
        }
        if (request.fullBackup()) {
            return new Plan(SnapshotKind.SCOPED_FULL, true);
        }
        return new Plan(SnapshotKind.INCREMENTAL, false);
    }

    static Migration resolveWorldPathMigration(
            BackupRequest request,
            Plan plan,
            List<BackupTargetWorld> targets,
            CurrentIndexStore.IndexState previousState,
            Map<String, String> previousWorldSourcePaths) {
        if (plan.kind().isFullBaseline() || previousState.baselineRequired()) {
            return new Migration(plan, List.of());
        }
        Map<String, Path> currentWorldPaths = new LinkedHashMap<>();
        targets.forEach(target -> currentWorldPaths.put(target.uuid(), target.path()));
        Set<String> changedWorlds = WorldPathContinuity.findChangedWorlds(
                previousWorldSourcePaths,
                currentWorldPaths);
        if (changedWorlds.isEmpty()) {
            return new Migration(plan, List.of());
        }
        List<String> descriptions = targets.stream()
                .filter(target -> changedWorlds.contains(target.uuid()))
                .map(target -> target.name() + " (" + target.key() + ")")
                .sorted()
                .toList();
        WorldPathContinuity.MigrationAction action = WorldPathContinuity.migrationAction(
                changedWorlds,
                hasWorldFilter(request));
        if (action == WorldPathContinuity.MigrationAction.REJECT_FILTERED) {
            throw new IllegalStateException("World storage layout changed for " + String.join(", ", descriptions)
                    + "; a filtered backup cannot migrate the layout. Run an unfiltered backup, which will be "
                    + "automatically promoted to a global FULL baseline.");
        }
        if (action == WorldPathContinuity.MigrationAction.PROMOTE_TO_FULL) {
            return new Migration(new Plan(SnapshotKind.FULL, true), descriptions);
        }
        return new Migration(plan, List.of());
    }

    static void validateFullCoverage(
            TimeMachineSettings settings,
            Plan plan,
            List<World> selectedWorlds,
            List<BackupTargetWorld> targets,
            CurrentIndexStore.IndexState previousState) {
        if (!plan.kind().isFullBaseline()) {
            return;
        }
        for (String configuredWorld : settings.includedWorlds()) {
            boolean found = selectedWorlds.stream().anyMatch(world -> matchesWorld(world, configuredWorld));
            if (!found) {
                throw new IllegalStateException(
                        "Configured world is not loaded, so a global FULL snapshot cannot be created: "
                                + configuredWorld);
            }
        }
        if (settings.includedWorlds().isEmpty() && !previousState.baselineRequired()) {
            Set<String> selectedIds = targets.stream()
                    .map(BackupTargetWorld::uuid)
                    .collect(Collectors.toSet());
            for (TrackedFileMetadata previous : previousState.entries().values()) {
                if (!previous.worldUuid().isBlank() && !selectedIds.contains(previous.worldUuid())) {
                    boolean replacementIdentityPresent = targets.stream().anyMatch(target ->
                            previous.worldKey().equalsIgnoreCase(target.key())
                                    || previous.worldName().equalsIgnoreCase(target.name()));
                    if (replacementIdentityPresent) {
                        continue;
                    }
                    throw new IllegalStateException(
                            "Previously tracked world is not loaded, so a global FULL snapshot would be incomplete: "
                                    + previous.worldName() + " (" + previous.worldUuid()
                                    + "). Configure backup.include-worlds explicitly to confirm a changed world set.");
                }
            }
        }
    }

    static void validateIdentityContinuity(
            TimeMachineSettings settings,
            BackupRequest request,
            Plan plan,
            List<BackupTargetWorld> targets,
            CurrentIndexStore.IndexState previousState) {
        if (plan.kind().isFullBaseline() || previousState.baselineRequired()) {
            return;
        }
        for (BackupTargetWorld target : targets) {
            boolean identityChanged = previousState.entries().values().stream().anyMatch(previous ->
                    !previous.worldUuid().isBlank()
                            && !previous.worldUuid().equalsIgnoreCase(target.uuid())
                            && (previous.worldKey().equalsIgnoreCase(target.key())
                                    || previous.worldName().equalsIgnoreCase(target.name())));
            if (identityChanged) {
                throw new IllegalStateException("World identity changed for " + target.name()
                        + "; run an unfiltered --full backup to establish a new baseline.");
            }
        }

        if (!hasWorldFilter(request) && settings.includedWorlds().isEmpty()) {
            Set<String> selectedIds = targets.stream()
                    .map(BackupTargetWorld::uuid)
                    .collect(Collectors.toSet());
            for (TrackedFileMetadata previous : previousState.entries().values()) {
                if (!previous.worldUuid().isBlank() && !selectedIds.contains(previous.worldUuid())) {
                    throw new IllegalStateException("Previously tracked world is not loaded or changed identity: "
                            + previous.worldName() + " (" + previous.worldUuid()
                            + "). Run a global --full backup only after confirming the intended world set.");
                }
            }
        }
    }

    static boolean hasWorldFilter(BackupRequest request) {
        return request.worldFilter() != null && !request.worldFilter().isBlank();
    }

    static boolean matchesWorld(World world, String value) {
        return value != null && (world.getName().equalsIgnoreCase(value)
                || world.getKey().toString().equalsIgnoreCase(value)
                || world.getUID().toString().equalsIgnoreCase(value));
    }

    record Plan(SnapshotKind kind, boolean copyAllFiles) {
    }

    record Migration(Plan plan, List<String> changedWorlds) {
        Migration {
            changedWorlds = List.copyOf(changedWorlds);
        }
    }
}
