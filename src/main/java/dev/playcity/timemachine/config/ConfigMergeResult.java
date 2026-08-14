package dev.playcity.timemachine.config;

import java.nio.file.Path;
import java.util.List;

public record ConfigMergeResult(
        boolean changed,
        Path backupPath,
        int fromVersion,
        int toVersion,
        List<String> warnings) {
}
