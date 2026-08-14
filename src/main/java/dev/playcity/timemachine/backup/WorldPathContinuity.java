package dev.playcity.timemachine.backup;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class WorldPathContinuity {
    enum MigrationAction {
        NONE,
        PROMOTE_TO_FULL,
        REJECT_FILTERED
    }

    private WorldPathContinuity() {
    }

    static Set<String> findChangedWorlds(
            Map<String, String> recordedSourcePaths,
            Map<String, Path> currentSourcePaths) {
        Set<String> changed = new LinkedHashSet<>();
        currentSourcePaths.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String recorded = recordedSourcePaths.get(entry.getKey());
                    if (recorded != null && !recorded.isBlank() && !samePath(recorded, entry.getValue())) {
                        changed.add(entry.getKey());
                    }
                });
        return Set.copyOf(changed);
    }

    static MigrationAction migrationAction(Set<String> changedWorlds, boolean filteredRequest) {
        if (changedWorlds.isEmpty()) {
            return MigrationAction.NONE;
        }
        return filteredRequest ? MigrationAction.REJECT_FILTERED : MigrationAction.PROMOTE_TO_FULL;
    }

    private static boolean samePath(String recorded, Path current) {
        try {
            Path recordedPath = Path.of(recorded);
            if (!recordedPath.isAbsolute()) {
                return false;
            }
            return recordedPath.normalize().equals(current.toAbsolutePath().normalize());
        } catch (InvalidPathException ex) {
            return false;
        }
    }
}
