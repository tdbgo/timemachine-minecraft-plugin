package dev.playcity.timemachine.backup;

import java.util.concurrent.atomic.AtomicReference;

final class OperationGate {
    enum Operation {
        IDLE,
        BACKUP,
        RECONCILE,
        VERIFY,
        PRUNE,
        RELOADING,
        SHUTTING_DOWN
    }

    private final AtomicReference<Operation> current = new AtomicReference<>(Operation.IDLE);

    boolean tryAcquire(Operation operation) {
        if (operation == Operation.IDLE || operation == Operation.SHUTTING_DOWN) {
            throw new IllegalArgumentException("Invalid acquirable operation: " + operation);
        }
        return current.compareAndSet(Operation.IDLE, operation);
    }

    void release(Operation operation) {
        current.compareAndSet(operation, Operation.IDLE);
    }

    Operation current() {
        return current.get();
    }

    void beginShutdown() {
        current.set(Operation.SHUTTING_DOWN);
    }
}
