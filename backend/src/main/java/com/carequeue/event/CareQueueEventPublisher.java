package com.carequeue.event;

import com.carequeue.entity.OutboxEvent;
import com.carequeue.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Publishes domain events, classified by how much their loss matters:
 *
 *  REQUIRED (Kafka copy, durable): every event published here is written to the transactional
 *  outbox in the caller's transaction and delivered by OutboxRelay with retries. Integration
 *  consumers must not miss a booking, a cancellation, a called token or an emergency alert. If the
 *  outbox write fails, the business operation fails with it, because an unrecorded required event
 *  must not be silently dropped.
 *
 *  REQUIRED for the patient (in-process): notifications are persisted and pushed by the in-process
 *  listener, which runs only after commit (AfterCommit). This path does not depend on Kafka, so a
 *  Kafka outage cannot stop a patient from being told their token was called.
 *
 * Because the Kafka copy is written inside the transaction, a rolled-back operation leaves no
 * outbox row, which is how ghost events are prevented on the Kafka side as well.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CareQueueEventPublisher {

    public static final String TOPIC_APPOINTMENTS = "carequeue.appointments";
    public static final String TOPIC_QUEUE = "carequeue.queue";
    public static final String TOPIC_EMERGENCY = "carequeue.emergency";

    private final ApplicationEventPublisher internalEventPublisher;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    public void publishAppointmentBooked(CareQueueEvents.AppointmentBookedEvent event) {
        publish(TOPIC_APPOINTMENTS, event.getAppointmentNumber(), event);
    }

    public void publishAppointmentCancelled(CareQueueEvents.AppointmentCancelledEvent event) {
        publish(TOPIC_APPOINTMENTS, event.getAppointmentNumber(), event);
    }

    public void publishTokenCalled(CareQueueEvents.TokenCalledEvent event) {
        publish(TOPIC_QUEUE, event.getTokenFormatted(), event);
    }

    public void publishEmergencyAlert(CareQueueEvents.EmergencyAlertEvent event) {
        publish(TOPIC_EMERGENCY, event.getTokenFormatted(), event);
    }

    private void publish(String topic, String key, CareQueueEvents.CareQueueEvent event) {
        // 1. Required, durable: inside the caller's transaction.
        outbox.save(OutboxEvent.builder()
            .topic(topic)
            .msgKey(key)
            .eventClass(event.getClass().getName())
            .payload(serialize(event))
            .status(OutboxEvent.Status.PENDING)
            .attempts(0)
            .createdAt(LocalDateTime.now())
            .nextAttemptAt(null)
            .build());

        // 2. In-process notifications: only after commit, failures contained.
        AfterCommit.run("in-process " + event.getClass().getSimpleName(), () -> {
            log.info("[Event] {} id={} key={}", event.getClass().getSimpleName(), event.getEventId(), key);
            try {
                internalEventPublisher.publishEvent(event);
            } catch (Exception ex) {
                log.error("In-process listener failed for event {}: {}", event.getEventId(), ex.toString());
            }
        });
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize domain event for the outbox", ex);
        }
    }
}
