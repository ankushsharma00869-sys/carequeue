package com.carequeue.event;

import com.carequeue.entity.OutboxEvent;
import com.carequeue.entity.User;
import com.carequeue.notification.NotificationTestSupport;
import com.carequeue.repository.NotificationRepository;
import com.carequeue.repository.OutboxEventRepository;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import com.carequeue.service.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Real transactions, real database, real outbox and real in-process listener. Only the Kafka client
 * is mocked (no broker here); KafkaEmbeddedIntegrationTest covers the broker.
 *
 * Other test classes may leave PENDING outbox rows behind in the shared database, so delivery
 * checks look for THIS event's row rather than assuming it is the only one.
 */
@SpringBootTest
@ActiveProfiles("dev")
class TransactionalEventIntegrationTest {

    @MockBean private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired private CareQueueEventPublisher publisher;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private NotificationRepository notifications;
    @Autowired private UserRepository users;
    @Autowired private PatientRepository patients;
    @Autowired private OutboxEventRepository outboxRepo;
    @Autowired private OutboxRelay relay;

    private User patientUser;

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        patientUser = NotificationTestSupport.newPatientUser(users, patients);
    }

    private CareQueueEvents.AppointmentCancelledEvent event() {
        return CareQueueEvents.AppointmentCancelledEvent.builder()
            .appointmentId(1L).appointmentNumber("CQ-TEST-" + System.nanoTime()).patientUserId(patientUser.getId())
            .doctorName("Asha Rao").appointmentDate("2091-03-15").appointmentTime("09:20").reason("test").build();
    }

    private OutboxEvent rowFor(String eventId) {
        return outboxRepo.findAll().stream()
            .filter(r -> r.getPayload().contains(eventId))
            .findFirst().orElse(null);
    }

    /** Relays oldest-first until this event's row is SENT (bounded), so older rows cannot starve the check. */
    private void relayUntilSent(String eventId) {
        for (int i = 0; i < 200; i++) {
            OutboxEvent row = rowFor(eventId);
            if (row != null && row.getStatus() == OutboxEvent.Status.SENT) return;
            relay.relayPending();
        }
        throw new AssertionError("event was never delivered: " + eventId);
    }

    @Test
    @DisplayName("Rolled-back transaction: no outbox row, no Kafka send, no notification")
    void rollbackProducesNothing() throws Exception {
        var event = event();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            publisher.publishAppointmentCancelled(event);
            throw new IllegalStateException("business failure after publish");
        }));

        assertNull(rowFor(event.getEventId()), "rolled-back event must not be in the outbox");
        assertEquals(0, notifications.countByEventId(event.getEventId()));
        relay.relayPending();
        verify(kafkaTemplate, never()).send(eq(CareQueueEventPublisher.TOPIC_APPOINTMENTS), eq(event.getAppointmentNumber()), any());
    }

    @Test
    @DisplayName("Committed transaction: outbox row PENDING before relay, notification present, delivered by the relay")
    void commitThenDelivery() {
        var event = event();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        tx.executeWithoutResult(status -> publisher.publishAppointmentCancelled(event));

        OutboxEvent row = rowFor(event.getEventId());
        assertNotNull(row, "committed event must be in the outbox");
        assertEquals(OutboxEvent.Status.PENDING, row.getStatus(), "delivery has not happened yet");
        assertEquals(1, notifications.countByEventId(event.getEventId()), "patient notified in-process after commit");

        relayUntilSent(event.getEventId());
        verify(kafkaTemplate).send(eq(CareQueueEventPublisher.TOPIC_APPOINTMENTS), eq(event.getAppointmentNumber()), any());
    }

    @Test
    @DisplayName("Kafka unavailable: the business commit and the patient notification still succeed; the event stays PENDING for retry")
    void kafkaDownKeepsEventForRetry() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenThrow(new RuntimeException("broker unavailable"));
        var event = event();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertDoesNotThrow(() -> tx.executeWithoutResult(status -> publisher.publishAppointmentCancelled(event)));

        assertEquals(1, notifications.countByEventId(event.getEventId()));
        OutboxEvent row = rowFor(event.getEventId());
        assertNotNull(row);
        assertEquals(OutboxEvent.Status.PENDING, row.getStatus(), "a failed delivery must not mark the event SENT");

        relay.relayPending(); // fails at the first send and stops; this row must remain PENDING
        assertEquals(OutboxEvent.Status.PENDING, rowFor(event.getEventId()).getStatus(),
            "an undelivered event must stay PENDING for retry, never be marked SENT");
    }
}
