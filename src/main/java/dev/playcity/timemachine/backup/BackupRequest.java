package dev.playcity.timemachine.backup;

public record BackupRequest(
        String trigger,
        boolean fullBackup,
        String worldFilter,
        String message,
        String initiator) {
}
