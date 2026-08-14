package dev.playcity.timemachine.backup;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

final class ProgressTracker {
    private final AtomicReference<OperationProgress> current =
            new AtomicReference<>(OperationProgress.idle());

    OperationProgress snapshot() {
        return current.get();
    }

    void start(String operation, OperationProgress.Phase phase, String detail) {
        Instant now = Instant.now();
        current.set(new OperationProgress(
                operation,
                phase,
                now,
                now,
                0,
                0,
                0L,
                0L,
                safe(detail)));
    }

    void beginPhase(
            OperationProgress.Phase phase,
            int totalFiles,
            long totalBytes,
            String detail) {
        current.updateAndGet(previous -> new OperationProgress(
                previous.operation(),
                phase,
                previous.startedAt(),
                Instant.now(),
                0,
                Math.max(0, totalFiles),
                0L,
                Math.max(0L, totalBytes),
                safe(detail)));
    }

    void advance(long completedBytes, String detail) {
        current.updateAndGet(previous -> new OperationProgress(
                previous.operation(),
                previous.phase(),
                previous.startedAt(),
                Instant.now(),
                Math.min(previous.totalFiles(), previous.completedFiles() + 1),
                previous.totalFiles(),
                Math.min(previous.totalBytes(), addSaturated(previous.completedBytes(), completedBytes)),
                previous.totalBytes(),
                safe(detail)));
    }

    void complete(String detail) {
        finish(OperationProgress.Phase.COMPLETE, detail);
    }

    void fail(String detail) {
        finish(OperationProgress.Phase.FAILED, detail);
    }

    void cancel(String detail) {
        finish(OperationProgress.Phase.CANCELLED, detail);
    }

    private void finish(OperationProgress.Phase phase, String detail) {
        current.updateAndGet(previous -> new OperationProgress(
                previous.operation(),
                phase,
                previous.startedAt(),
                Instant.now(),
                previous.completedFiles(),
                previous.totalFiles(),
                previous.completedBytes(),
                previous.totalBytes(),
                safe(detail)));
    }

    private long addSaturated(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
