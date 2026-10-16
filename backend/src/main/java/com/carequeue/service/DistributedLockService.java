package com.carequeue.service;

import com.carequeue.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Production-grade distributed lock abstraction.
 * Primary: Uses Redis SETNX with TTL for multi-instance cluster deployments.
 * Standalone/Local: Uses JVM-level Striped ReentrantLock per resource key for deterministic concurrency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ConcurrentHashMap<String, ReentrantLock> localLockMap = new ConcurrentHashMap<>();

    public boolean acquireLock(String lockKey, long timeoutSeconds) {
        // Attempt Redis distributed lock if available
        try {
            Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "LOCKED", Duration.ofSeconds(timeoutSeconds));

            // Redis answered definitively (true = we got the lock, false = someone else holds it).
            // Either way, Redis is reachable, so this is the authoritative answer — do NOT fall
            // through to the local JVM lock, or a concurrent request would just grab a fresh,
            // unrelated ReentrantLock and both callers would think they "won" the lock.
            if (acquired != null) {
                return acquired;
            }
        } catch (Exception e) {
            log.debug("Redis not available, applying striped ReentrantLock for key: {}", lockKey);
        }

        // JVM-level atomic reentrant lock fallback — only reached when Redis itself
        // is unreachable/erroring (single-instance/local-dev degradation path).
        ReentrantLock lock = localLockMap.computeIfAbsent(lockKey, k -> new ReentrantLock());
        try {
            return lock.tryLock(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void releaseLock(String lockKey) {
        try {
            redisTemplate.delete(lockKey);
        } catch (Exception ignored) {
        }

        ReentrantLock lock = localLockMap.get(lockKey);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /**
     * Acquires {@code lockKey} (retrying with brief backoff) and holds it for the lifetime of
     * the CURRENT database transaction rather than for this method's call stack: the release
     * is deferred to a TransactionSynchronization#afterCompletion callback, which fires when
     * the surrounding @Transactional method's transaction actually commits or rolls back —
     * whichever happens first, so a failed operation still frees the lock promptly instead of
     * waiting out the Redis TTL.
     *
     * This exists because two call sites (AppointmentService's per-slot booking lock and
     * QueueService's per-doctor token-generation lock) each used to acquire a lock and release
     * it in a plain {@code finally} block INSIDE an @Transactional method. Spring's
     * transactional proxy only commits AFTER the method returns, so that released the lock
     * before the row it protected was actually durable: a second concurrent request could
     * acquire the now-free lock and read the pre-write state (e.g. "is this slot still free?")
     * while the first transaction's write was still in flight, recreating exactly the race the
     * lock exists to prevent. AppointmentService's slot lock had no database-level backstop
     * for this (no unique constraint on doctor+date+time), so this was a live double-booking
     * risk, not just a theoretical one.
     *
     * @throws ConflictException if the lock could not be acquired after {@code maxAttempts}
     */
    public void acquireForCurrentTransaction(String lockKey, long timeoutSeconds, int maxAttempts, String conflictMessage) {
        boolean acquired = false;
        for (int attempt = 0; attempt < maxAttempts && !acquired; attempt++) {
            acquired = acquireLock(lockKey, timeoutSeconds);
            if (!acquired) {
                sleepBriefly(50L * (attempt + 1));
            }
        }
        if (!acquired) {
            throw new ConflictException(conflictMessage);
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    releaseLock(lockKey);
                }
            });
        } else {
            log.warn("No active transaction synchronization when acquiring lock {} — releasing immediately instead of at commit", lockKey);
            releaseLock(lockKey);
        }
    }

    private void sleepBriefly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}