package dev.playcity.timemachine.backup;

public record TrackedFileMetadata(
        String relativePath,
        String worldName,
        String worldKey,
        String worldUuid,
        String worldStoragePath,
        BackupScope scope,
        int regionX,
        int regionZ,
        long modifiedTime,
        long size,
        String sha256) {

    public String regionKey() {
        return worldUuid + ":" + regionX + ":" + regionZ;
    }

    public TrackedFileMetadata withFileState(long newModifiedTime, long newSize, String newSha256) {
        return new TrackedFileMetadata(
                relativePath,
                worldName,
                worldKey,
                worldUuid,
                worldStoragePath,
                scope,
                regionX,
                regionZ,
                newModifiedTime,
                newSize,
                newSha256 == null ? "" : newSha256);
    }
}
