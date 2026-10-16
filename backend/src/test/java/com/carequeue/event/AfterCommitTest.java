package com.carequeue.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the real Spring synchronization API by hand: no database or Spring context needed. */
class AfterCommitTest {

    @BeforeEach
    void startTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clear() {
        // One test clears synchronization itself to simulate "no transaction"; only clear if still active.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void commit() {
        List<TransactionSynchronization> syncs = List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        syncs.forEach(TransactionSynchronization::afterCommit);
        syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    private void rollback() {
        List<TransactionSynchronization> syncs = List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }

    @Test
    @DisplayName("Action inside a transaction does not run until commit")
    void deferredUntilCommit() {
        AtomicInteger runs = new AtomicInteger();
        AfterCommit.run("test", runs::incrementAndGet);
        assertEquals(0, runs.get(), "must not run while the transaction is still open");
        commit();
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("Action is never run when the transaction rolls back")
    void droppedOnRollback() {
        AtomicInteger runs = new AtomicInteger();
        AfterCommit.run("test", runs::incrementAndGet);
        rollback();
        assertEquals(0, runs.get());
    }

    @Test
    @DisplayName("Without a transaction the action runs immediately")
    void immediateWithoutTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
        AtomicInteger runs = new AtomicInteger();
        AfterCommit.run("test", runs::incrementAndGet);
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("A failing post-commit action is contained and does not propagate to the caller")
    void failureContained() {
        AfterCommit.run("boom", () -> { throw new IllegalStateException("boom"); });
        assertDoesNotThrow(this::commit);
    }
}
