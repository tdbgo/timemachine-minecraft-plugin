package dev.playcity.timemachine.io;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public final class FileTreeOperations {
    private FileTreeOperations() {
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                checkInterrupted();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                checkInterrupted();
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                checkInterrupted();
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }

            private void checkInterrupted() throws IOException {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Recursive deletion was interrupted.");
                }
            }
        });
    }
}
