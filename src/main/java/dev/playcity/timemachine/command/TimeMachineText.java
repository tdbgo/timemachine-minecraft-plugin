package dev.playcity.timemachine.command;

import dev.playcity.timemachine.backup.OperationProgress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class TimeMachineText {
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeMachineText() {
    }

    static String time(Instant instant, ZoneId timezone) {
        return TIME_FORMATTER.withZone(timezone).format(instant);
    }

    static String summaryTime(Instant instant, ZoneId timezone) {
        return Instant.EPOCH.equals(instant) ? "never" : time(instant, timezone);
    }

    static String nullableTime(Instant instant, ZoneId timezone) {
        return instant == null ? "-" : time(instant, timezone);
    }

    static String phase(OperationProgress.Phase phase) {
        return phase.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    static String progressCounts(OperationProgress progress, String filesLabel) {
        if (progress.totalFiles() <= 0 && progress.totalBytes() <= 0L) {
            return "";
        }
        int percentage = (int) Math.round(progress.completionRatio() * 100.0D);
        StringBuilder result = new StringBuilder(" | ").append(percentage).append('%');
        if (progress.totalFiles() > 0) {
            result.append(" | ").append(filesLabel).append(' ')
                    .append(progress.completedFiles())
                    .append('/')
                    .append(progress.totalFiles());
        }
        if (progress.totalBytes() > 0L) {
            result.append(" | ")
                    .append(bytes(progress.completedBytes()))
                    .append('/')
                    .append(bytes(progress.totalBytes()));
        }
        return result.toString();
    }

    static String duration(Duration duration) {
        long seconds = Math.max(0L, duration.toSeconds());
        if (seconds < 60L) {
            return seconds + "s";
        }
        return seconds / 60L + "m " + seconds % 60L + "s";
    }

    static String bytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        int unitIndex = -1;
        do {
            value /= 1024.0D;
            unitIndex++;
        } while (value >= 1024.0D && unitIndex < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex]);
    }

    static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
