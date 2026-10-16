package com.carequeue.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a side effect (Kafka publish, WebSocket push, notification dispatch) only once the
 * surrounding database transaction has COMMITTED.
 *
 * Why: these side effects cannot be rolled back. Publishing from inside the transaction
 * means a later failure (constraint violation, exception, timeout) rolls the data back but
 * leaves the event already out in the world - a ghost event about a row that never existed
 * (this was observed in test logs: TokenCalled events for calls that had rolled back).
 *
 * Semantics:
 *  - transaction active  -> deferred to afterCommit; silently dropped on rollback.
 *  - no transaction      -> runs immediately (nothing to wait for).
 * The action must not throw: by afterCommit the data is already committed, so an exception
 * could only mislead the caller into thinking the business operation failed. Failures are
 * logged instead.
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(String description, Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    execute(description, action);
                }
            });
        } else {
            execute(description, action);
        }
    }

    private static void execute(String description, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.error("Post-commit side effect '{}' failed (data is already committed): {}", description, ex.toString());
        }
    }
}
