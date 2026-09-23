package dev.playcity.timemachine.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;

public final class StorageSafetyValidator {
    private StorageSafetyValidator() {
    }

    public static List<String> validate(TimeMachineSettings settings) {
        List<WorldPath> worlds = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            worlds.add(new WorldPath(world.getName(), world.getWorldPath()));
        }
        return validate(settings, worlds);
    }

    public static List<String> validate(TimeMachineSettings settings, Collection<WorldPath> worlds) {
        List<String> issues = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        roots.add(resolveCanonicalPath(settings.storageRoot()));
        for (Path archiveRoot : settings.archiveRoots()) {
            roots.add(resolveCanonicalPath(archiveRoot));
        }
        for (int leftIndex = 0; leftIndex < roots.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < roots.size(); rightIndex++) {
                Path left = roots.get(leftIndex);
                Path right = roots.get(rightIndex);
                if (overlaps(left, right)) {
                    issues.add("Configured primary/archive storage paths overlap: '" + left + "' and '" + right + "'.");
                }
            }
        }

        Path databasePath = null;
        if (settings.database().enabled()) {
            databasePath = resolveCanonicalPath(settings.database().sqliteFile());
            for (Path root : roots) {
                if (overlaps(root, databasePath)) {
                    issues.add("Configured SQLite path '" + databasePath
                            + "' overlaps backup storage '" + root + "'. Use a separate path.");
                }
            }
        }

        List<Path> writablePaths = new ArrayList<>(roots);
        if (databasePath != null) {
            writablePaths.add(databasePath);
        }

        for (WorldPath world : worlds) {
            Path worldPath = resolveCanonicalPath(world.path());
            for (Path root : writablePaths) {
                if (!overlaps(root, worldPath)) {
                    continue;
                }
                issues.add("Configured backup path '" + root + "' overlaps world '" + world.name()
                        + "' at '" + worldPath + "'. Use a path outside all world directories.");
            }
        }

        return issues;
    }

    private static boolean overlaps(Path left, Path right) {
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }

    private static Path resolveCanonicalPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Deque<Path> missingSegments = new ArrayDeque<>();
        Path existing = absolute;
        while (existing != null && !java.nio.file.Files.exists(existing)) {
            Path name = existing.getFileName();
            if (name != null) {
                missingSegments.addFirst(name);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            Path resolved = existing.toRealPath();
            for (Path segment : missingSegments) {
                resolved = resolved.resolve(segment);
            }
            return resolved.normalize();
        } catch (IOException ex) {
            return absolute;
        }
    }

    public record WorldPath(String name, Path path) {
    }
}
