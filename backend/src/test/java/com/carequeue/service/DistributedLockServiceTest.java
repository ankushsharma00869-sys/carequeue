package com.carequeue.service;

import com.carequeue.exception.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Exercises the real DistributedLockService with a mocked RedisTemplate, driving Spring's
 * transaction-synchronization API by hand (no full Spring context / database needed).
 * See ConcurrentSlotBookingTest and ConcurrentTokenGenerationTest for the same fix proven
 * end-to-end against the real database.
 */
@SuppressWarnings("unchecked")
class DistributedLockServiceTest {

    private RedisTemplate<String, Object> redis;
    private ValueOperations<String, Object> valueOps;
    private DistributedLockService lockService;

    @BeforeEach
    void setUp() {
        redis = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        lockService = new DistributedLockService(redis);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void completeTransaction(int status) {
        List.copyOf(TransactionSynchronizationManager.getSynchronizations())
            .forEach(s -> s.afterCompletion(status));
    }

    @Test
    @DisplayName("Lock is NOT released when this method returns — only when the transaction completes")
    void releaseIsDeferredNotImmediate() {
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);

        lockService.acquireForCurrentTransaction("lock:test:1", 5, 3, "busy");

        verify(redis, never()).delete(anyString());

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(redis).delete("lock:test:1");
    }

    @Test
    @DisplayName("Lock is released on ROLLBACK too, not only on commit (afterCompletion, not afterCommit)")
    void releasedOnRollbackAsWellAsCommit() {
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);

        lockService.acquireForCurrentTransaction("lock:test:2", 5, 3, "busy");
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(redis).delete("lock:test:2");
    }

    @Test
    @DisplayName("Lock genuinely held by someone else: throws ConflictException after retrying, without ever registering a release")
    void contendedLockThrowsConflict() {
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(false);

        assertThrows(ConflictException.class, () ->
            lockService.acquireForCurrentTransaction("lock:test:3", 5, 3, "busy, try again"));

        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("Succeeds on a later attempt after initial contention (retry-with-backoff path)")
    void succeedsAfterRetry() {
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class)))
            .thenReturn(false).thenReturn(false).thenReturn(true);

        assertDoesNotThrow(() -> lockService.acquireForCurrentTransaction("lock:test:4", 5, 3, "busy"));
        verify(valueOps, times(3)).setIfAbsent(anyString(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("Without an active transaction, the lock is released immediately (fail-safe, should not normally happen)")
    void releasesImmediatelyOutsideTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(true);

        lockService.acquireForCurrentTransaction("lock:test:5", 5, 3, "busy");

        verify(redis).delete("lock:test:5");
    }
}
