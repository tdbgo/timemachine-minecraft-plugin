package dev.playcity.timemachine.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OperationGateTest {
    @Test
    void admitsOnlyOneOperationAndNeverReopensAfterShutdown() {
        OperationGate gate = new OperationGate();

        assertTrue(gate.tryAcquire(OperationGate.Operation.BACKUP));
        assertFalse(gate.tryAcquire(OperationGate.Operation.RECONCILE));
        gate.release(OperationGate.Operation.BACKUP);
        assertTrue(gate.tryAcquire(OperationGate.Operation.RECONCILE));
        gate.beginShutdown();
        gate.release(OperationGate.Operation.RECONCILE);

        assertEquals(OperationGate.Operation.SHUTTING_DOWN, gate.current());
        assertFalse(gate.tryAcquire(OperationGate.Operation.BACKUP));
    }
}
