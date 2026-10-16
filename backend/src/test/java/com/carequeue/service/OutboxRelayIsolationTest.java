package com.carequeue.service;

import com.carequeue.e2e.FixedClockTestConfig;
import com.carequeue.entity.OutboxEvent;
import com.carequeue.event.CareQueueEventPublisher;
import com.carequeue.event.CareQueueEvents;
import com.carequeue.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Outbox relay behaviour under failure, using the real repository and database. The Kafka client is
 * mocked: a "broker outage" here is a send that fails, never a send that is reported as successful.
 * Time is explicit (relayAt with a chosen instant), so backoff and lease expiry are deterministic.
 *
 * The database is shared with other test classes, so each test first marks pre-existing PENDING rows
 * as handled; otherwise an earlier test's events would be attempted here.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(FixedClockTestConfig.class)
class OutboxRelayIsolationTest {

    private static final LocalDateTime T0 = FixedClockTestConfig.DAY.atTime(8, 0);

    @MockBean private KafkaTemplate<String, Object> kafka;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private OutboxRelay relay;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void isolate() {
        reset(kafka);
        succeedAlways();
        for (OutboxEvent row : outbox.findAll()) {
            if (row.getStatus() == OutboxEvent.Status.PENDING) {
                row.setStatus(OutboxEvent.Status.SENT);
                outbox.save(row);
            }
        }
    }

    // ------------------------------------------------------------ broker behaviour

    private void succeedAlways() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private void brokerDown() {
        when(kafka.send(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));
    }

    private void failOnlyFor(String key) {
        when(kafka.send(anyString(), eq(key), any()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker rejected this record")));
    }

    // ------------------------------------------------------------ fixtures

    private OutboxEvent pending(String key, int attempts) throws Exception {
        CareQueueEvents.AppointmentCancelledEvent event = CareQueueEvents.AppointmentCancelledEvent.builder()
            .appointmentId(1L).appointmentNumber(key).patientUserId(1L).doctorName("Dr Relay")
            .appointmentDate("2091-03-15").appointmentTime("09:00").reason("relay test").build();
        return outbox.save(OutboxEvent.builder()
            .topic(CareQueueEventPublisher.TOPIC_APPOINTMENTS)
            .msgKey(key)
            .eventClass(event.getClass().getName())
            .payload(objectMapper.writeValueAsString(event))
            .status(OutboxEvent.Status.PENDING)
            .attempts(attempts)
            .createdAt(T0)
            .build());
    }

    private static String key() {
        return "K-" + UUID.randomUUID();
    }

    private OutboxEvent reload(OutboxEvent row) {
        return outbox.findById(row.getId()).orElseThrow();
    }

    /** Relays at the given instant until the row is SENT. Bounded, so a stuck event fails the test. */
    private void relayUntilSent(OutboxEvent row, LocalDateTime now) {
        for (int i = 0; i < 200; i++) {
            if (reload(row).getStatus() == OutboxEvent.Status.SENT) return;
            relay.relayAt(now);
        }
        throw new AssertionError("event never delivered: " + row.getMsgKey());
    }

    // ------------------------------------------------------------ isolation

    @Test
    @DisplayName("A failing event does not block a later eligible event in the same pass")
    void failedEventDoesNotBlockLaterEvents() throws Exception {
        OutboxEvent bad = pending(key(), 0);   // older id: attempted first
        OutboxEvent good = pending(key(), 0);
        failOnlyFor(bad.getMsgKey());

        relayUntilSent(good, T0);

        OutboxEvent badAfter = reload(bad);
        assertEquals(OutboxEvent.Status.PENDING, badAfter.getStatus(), "a failed event stays retryable");
        assertEquals(1, badAfter.getAttempts());
        assertEquals(T0.plus(OutboxRelay.backoff(1)), badAfter.getNextAttemptAt(), "retry is scheduled with backoff");
        assertNull(badAfter.getLeaseUntil(), "a failed event must release its lease");
    }

    @Test
    @DisplayName("Backoff: a failed event is not retried before its due time, and the next delay doubles")
    void backoffHoldsRetryUntilDue() throws Exception {
        OutboxEvent row = pending(key(), 0);
        failOnlyFor(row.getMsgKey());

        relay.relayAt(T0);
        assertEquals(1, reload(row).getAttempts());

        relay.relayAt(T0); // same instant: not due yet
        assertEquals(1, reload(row).getAttempts(), "must not retry before nextAttemptAt");

        LocalDateTime due = reload(row).getNextAttemptAt();
        relay.relayAt(due);
        OutboxEvent second = reload(row);
        assertEquals(2, second.getAttempts());
        assertEquals(due.plus(OutboxRelay.backoff(2)), second.getNextAttemptAt(), "delay doubles between attempts");
    }

    @Test
    @DisplayName("Recovery: events that failed during an outage are delivered once the broker is back")
    void recoversWhenBrokerReturns() throws Exception {
        OutboxEvent row = pending(key(), 0);
        brokerDown();
        relay.relayAt(T0);
        assertEquals(OutboxEvent.Status.PENDING, reload(row).getStatus(), "not marked SENT during the outage");

        succeedAlways();
        LocalDateTime later = T0.plusDays(1);
        relayUntilSent(row, later);

        assertEquals(OutboxEvent.Status.SENT, reload(row).getStatus());
        assertNotNull(reload(row).getSentAt());
    }

    @Test
    @DisplayName("Outage: a dead broker stops the pass after a few failures, and no event is lost or marked SENT")
    void outageStopsPassWithoutLosingEvents() throws Exception {
        List<OutboxEvent> rows = List.of(pending(key(), 0), pending(key(), 0), pending(key(), 0), pending(key(), 0));
        brokerDown();

        int delivered = relay.relayAt(T0);

        assertEquals(0, delivered);
        assertEquals(OutboxRelay.BROKER_DOWN_THRESHOLD, rows.stream().filter(r -> reload(r).getAttempts() == 1).count(),
            "the pass stops after the threshold, so the fourth event is not attempted yet");
        rows.forEach(r -> assertEquals(OutboxEvent.Status.PENDING, reload(r).getStatus(), "nothing is lost"));

        succeedAlways();
        for (OutboxEvent r : rows) {
            relayUntilSent(r, T0.plusDays(1));
        }
    }

    @Test
    @DisplayName("Bounded retries: an event that exhausts its attempts is FAILED, and other events keep flowing")
    void exhaustedEventFailsWithoutBlockingOthers() throws Exception {
        OutboxEvent exhausted = pending(key(), OutboxRelay.MAX_ATTEMPTS - 1);
        OutboxEvent healthy = pending(key(), 0);
        failOnlyFor(exhausted.getMsgKey());

        relayUntilSent(healthy, T0);
        relay.relayAt(T0.plusDays(1));

        OutboxEvent after = reload(exhausted);
        assertEquals(OutboxEvent.Status.FAILED, after.getStatus(), "poison events stop being attempted");
        assertEquals(OutboxRelay.MAX_ATTEMPTS, after.getAttempts());
        assertEquals(OutboxEvent.Status.SENT, reload(healthy).getStatus());
    }

    // ------------------------------------------------------------ concurrency and recovery

    @Test
    @DisplayName("Two relays cannot hold the same event at once: the atomic claim admits exactly one")
    void concurrentClaimAdmitsOnlyOne() throws Exception {
        OutboxEvent row = pending(key(), 0);
        Long id = row.getId();

        int first = outbox.claim(id, "relay-A", T0.plus(OutboxRelay.LEASE), T0, OutboxEvent.Status.PENDING);
        int second = outbox.claim(id, "relay-B", T0.plus(OutboxRelay.LEASE), T0, OutboxEvent.Status.PENDING);

        assertEquals(1, first);
        assertEquals(0, second, "a live lease must not be taken over");
    }

    @Test
    @DisplayName("A relay that crashed mid-send does not strand the event: its lease expires and the event is delivered")
    void crashedRelayLeaseIsRecovered() throws Exception {
        OutboxEvent row = pending(key(), 0);
        outbox.claim(row.getId(), "crashed-relay", T0.plusSeconds(30), T0, OutboxEvent.Status.PENDING);

        relay.relayAt(T0);
        assertEquals(OutboxEvent.Status.PENDING, reload(row).getStatus(), "still leased by the crashed relay");

        relayUntilSent(row, T0.plusSeconds(31));
        assertEquals(OutboxEvent.Status.SENT, reload(row).getStatus());
    }

    @Test
    @DisplayName("Restart (simulated with a new relay instance): persisted pending events are recovered and delivered")
    void pendingEventsSurviveRestart() throws Exception {
        OutboxEvent row = pending(key(), 0);
        OutboxRelay restarted = new OutboxRelay(outbox, kafkaProxy(), objectMapper, Clock.systemUTC(), false);

        for (int i = 0; i < 200 && reload(row).getStatus() != OutboxEvent.Status.SENT; i++) {
            restarted.relayAt(T0);
        }
        assertEquals(OutboxEvent.Status.SENT, reload(row).getStatus());
    }

    /** The same mocked template, handed to a second relay object as a restarted process would have it. */
    private KafkaTemplate<String, Object> kafkaProxy() {
        return kafka;
    }
}
