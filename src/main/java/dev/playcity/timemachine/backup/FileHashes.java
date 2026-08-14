package dev.playcity.timemachine.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class FileHashes {
    private static final int MAX_STABLE_HASH_ATTEMPTS = 3;
    private static final int HASH_BUFFER_SIZE = 1024 * 1024;

    private FileHashes() {
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }

        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("SHA-256 calculation was interrupted: " + path);
                }
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static HashedFile hashStable(Path path) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_STABLE_HASH_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("SHA-256 calculation was interrupted: " + path);
            }
            try {
                BasicFileAttributes before = readRegularFileAttributes(path);
                String sha256 = sha256(path);
                BasicFileAttributes after = readRegularFileAttributes(path);
                if (sameFileState(before, after)) {
                    return new HashedFile(after.lastModifiedTime().toMillis(), after.size(), sha256);
                }
                lastFailure = new IOException("File changed while it was being hashed: " + path);
            } catch (IOException ex) {
                lastFailure = ex;
            }
        }
        throw new IOException(
                "Could not obtain a stable SHA-256 after " + MAX_STABLE_HASH_ATTEMPTS + " attempts: " + path,
                lastFailure);
    }

    static BasicFileAttributes readRegularFileAttributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Expected a regular file: " + path);
        }
        return attributes;
    }

    private static boolean sameFileState(BasicFileAttributes left, BasicFileAttributes right) {
        if (left.size() != right.size()
                || !left.lastModifiedTime().equals(right.lastModifiedTime())) {
            return false;
        }
        Object leftKey = left.fileKey();
        Object rightKey = right.fileKey();
        return leftKey == null || rightKey == null || Objects.equals(leftKey, rightKey);
    }

    record HashedFile(long modifiedTime, long size, String sha256) {
    }
}
