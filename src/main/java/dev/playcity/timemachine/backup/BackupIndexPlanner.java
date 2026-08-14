package dev.playcity.timemachine.backup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BackupIndexPlanner {
    private BackupIndexPlanner() {
    }

    static List<TrackedFileMetadata> findDeletions(
            Map<String, TrackedFileMetadata> previous,
            Map<String, TrackedFileMetadata> current,
            Set<String> scannedScopePrefixes) {
        List<TrackedFileMetadata> deleted = new ArrayList<>();
        for (Map.Entry<String, TrackedFileMetadata> entry : previous.entrySet()) {
            if (matchesAnyPrefix(entry.getKey(), scannedScopePrefixes) && !current.containsKey(entry.getKey())) {
                deleted.add(entry.getValue());
            }
        }
        return deleted;
    }

    static Map<String, TrackedFileMetadata> merge(
            Map<String, TrackedFileMetadata> previous,
            Map<String, TrackedFileMetadata> current,
            Set<String> scannedScopePrefixes) {
        Map<String, TrackedFileMetadata> merged = new LinkedHashMap<>();
        for (Map.Entry<String, TrackedFileMetadata> entry : previous.entrySet()) {
            if (!matchesAnyPrefix(entry.getKey(), scannedScopePrefixes)) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        merged.putAll(current);
        return merged;
    }

    static boolean matchesAnyPrefix(String relativePath, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (relativePath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
