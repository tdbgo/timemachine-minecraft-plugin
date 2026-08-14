package dev.playcity.timemachine.backup;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class AutosaveSessionCoordinator {
    private final AtomicReference<Session> pendingSession = new AtomicReference<>();

    void update(Map<UUID, Boolean> autosaveStates) {
        Map<UUID, Boolean> states = Map.copyOf(autosaveStates);
        pendingSession.updateAndGet(current -> new Session(
                states,
                current == null ? new CompletableFuture<>() : current.completion()));
    }

    Restoration restorationTask(Consumer<Map<UUID, Boolean>> restorer) {
        Objects.requireNonNull(restorer, "restorer");
        Session observed = pendingSession.get();
        if (observed == null) {
            return new Restoration(() -> {
            }, CompletableFuture.completedFuture(null), false);
        }
        Runnable task = () -> {
            Session session = pendingSession.getAndSet(null);
            if (session == null) {
                return;
            }
            try {
                restorer.accept(session.autosaveStates());
                session.completion().complete(null);
            } catch (RuntimeException | Error throwable) {
                session.completion().completeExceptionally(throwable);
                throw throwable;
            }
        };
        return new Restoration(task, observed.completion(), true);
    }

    boolean hasPendingState() {
        return pendingSession.get() != null;
    }

    record Restoration(Runnable task, CompletableFuture<Void> completion, boolean pending) {
        Restoration {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(completion, "completion");
        }

        void run() {
            task.run();
        }
    }

    private record Session(
            Map<UUID, Boolean> autosaveStates,
            CompletableFuture<Void> completion) {
    }
}
