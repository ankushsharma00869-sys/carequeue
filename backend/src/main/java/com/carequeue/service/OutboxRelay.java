package com.carequeue.service;

import com.carequeue.entity.OutboxEvent;
import com.carequeue.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Delivers PENDING outbox rows to Kafka.
 *
 * Guarantees:
 *  - Failure isolation: a failing event is rescheduled with exponential backoff and the pass moves
 *    on to the next due event. One bad event never blocks the others.
 *  - Broker outage: after BROKER_DOWN_THRESHOLD consecutive failures in one pass the pass stops, so
 *    a dead broker costs a few send timeouts per pass instead of one per event. Nothing is marked
 *    SENT, so nothing is lost; the rows stay PENDING and are retried when they are due.
 *  - Bounded retries: after MAX_ATTEMPTS an event is marked FAILED (logged at ERROR) and no longer
 *    attempted, so a poison event cannot retry forever.
 *  - Concurrent relays: each attempt first takes a lease with an atomic conditional UPDATE, so two
 *    instances never send the same event in parallel. A relay that crashes mid-send releases its
 *    lease when it expires, and the event is then sent again.
 *
 * Delivery is AT-LEAST-ONCE. A crash after the broker acknowledged a send but before the row was
 * marked SENT causes a second send. Consumers must be idempotent, and every payload carries an
 * eventId for that. Exactly-once delivery is NOT claimed.
 *
 * The scheduled pass is controlled by carequeue.outbox.scheduled-relay (off by default so tests stay
 * deterministic; enabled in docker-compose). relayPending() can always be called directly.
 */
@Slf4j
@Component
public class OutboxRelay {

    static final int MAX_ATTEMPTS = 10;
    static final int BROKER_DOWN_THRESHOLD = 3;
    static final int BATCH_SIZE = 50;
    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean scheduledEnabled;
    private final String instanceId;

    public OutboxRelay(OutboxEventRepository outbox,
                       KafkaTemplate<String, Object> kafka,
                       ObjectMapper objectMapper,
                       Clock clock,
                       @Value("${carequeue.outbox.scheduled-relay:false}") boolean scheduledEnabled) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.scheduledEnabled = scheduledEnabled;
        this.instanceId = hostName() + "-" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${carequeue.outbox.relay-delay-ms:5000}")
    public void scheduledRelay() {
        if (scheduledEnabled) {
            relayPending();
        }
    }

    /** @return number of events acknowledged by the broker in this pass */
    public int relayPending() {
        return relayAt(LocalDateTime.now(clock));
    }

    /** Explicit "now" so tests can exercise backoff and lease expiry deterministically. */
    int relayAt(LocalDateTime now) {
        int delivered = 0;
        int consecutiveFailures = 0;
        for (Long id : outbox.findDueIds(OutboxEvent.Status.PENDING, now, PageRequest.of(0, BATCH_SIZE))) {
            if (outbox.claim(id, instanceId, now.plus(LEASE), now, OutboxEvent.Status.PENDING) == 0) {
                continue; // leased by another relay, or no longer pending
            }
            OutboxEvent row = outbox.findById(id).orElse(null);
            if (row == null) {
                continue;
            }
            try {
                Object event = objectMapper.readValue(row.getPayload(), Class.forName(row.getEventClass()));
                kafka.send(row.getTopic(), row.getMsgKey(), event).get(SEND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                row.setStatus(OutboxEvent.Status.SENT);
                row.setSentAt(now);
                row.setLeaseOwner(null);
                row.setLeaseUntil(null);
                outbox.save(row);
                delivered++;
                consecutiveFailures = 0;
            } catch (Exception ex) {
                recordFailure(row, ex, now);
                consecutiveFailures++;
                if (consecutiveFailures >= BROKER_DOWN_THRESHOLD) {
                    log.warn("Stopping this outbox pass after {} consecutive failures; the broker looks unavailable",
                        consecutiveFailures);
                    break;
                }
            }
        }
        return delivered;
    }

    private void recordFailure(OutboxEvent row, Exception ex, LocalDateTime now) {
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        // Only the exception class is stored: exception messages can echo payload fragments, and the
        // payload may contain patient data.
        row.setLastError(ex.getClass().getSimpleName());
        row.setLeaseOwner(null);
        row.setLeaseUntil(null);
        if (attempts >= MAX_ATTEMPTS) {
            row.setStatus(OutboxEvent.Status.FAILED);
            log.error("Outbox event id={} type={} FAILED after {} attempts (last error: {})",
                row.getId(), row.getEventClass(), attempts, row.getLastError());
        } else {
            row.setNextAttemptAt(now.plus(backoff(attempts)));
            log.warn("Outbox event id={} type={} not delivered (attempt {}, error {}); retry after {}",
                row.getId(), row.getEventClass(), attempts, row.getLastError(), row.getNextAttemptAt());
        }
        outbox.save(row);
    }

    /** 2s, 4s, 8s, ... capped at five minutes. */
    static Duration backoff(int attempts) {
        long seconds = Math.min(MAX_BACKOFF.toSeconds(), 1L << Math.min(attempts, 20));
        return Duration.ofSeconds(seconds);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            return "relay";
        }
    }
}
