package com.carequeue.event;

import com.carequeue.entity.User;
import com.carequeue.notification.NotificationTestSupport;
import com.carequeue.repository.NotificationRepository;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs against a REAL in-JVM Kafka broker (spring-kafka-test @EmbeddedKafka): real
 * serialization, the real @KafkaListener consumer, the real error handler and the real
 * dispatcher + database. Nothing about Kafka is mocked here.
 */
@SpringBootTest(properties = {
    "carequeue.kafka.consumer-enabled=true",
    "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EmbeddedKafka(partitions = 1,
    topics = {"carequeue.appointments", "carequeue.queue", "carequeue.emergency"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ActiveProfiles("dev")
@DirtiesContext
class KafkaEmbeddedIntegrationTest {

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private CareQueueEventPublisher publisher;
    @Autowired private com.carequeue.repository.OutboxEventRepository outboxRepo;
    @Autowired private com.carequeue.service.OutboxRelay relay;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private NotificationRepository notifications;
    @Autowired private UserRepository users;
    @Autowired private PatientRepository patients;

    private User patientUser;

    @BeforeEach
    void setUp() {
        patientUser = NotificationTestSupport.newPatientUser(users, patients);
    }

    private CareQueueEvents.TokenCalledEvent tokenCalled() {
        return CareQueueEvents.TokenCalledEvent.builder()
            .queueEntryId(1L).doctorId(1L).doctorName("Asha Rao").roomNumber("104")
            .patientId(1L).patientUserId(patientUser.getId()).tokenFormatted("A-777").build();
    }

    private void awaitNotifications(String eventId, long expected) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertEquals(expected, notifications.countByEventId(eventId)));
    }

    @Test
    @DisplayName("Event sent through real Kafka is deserialized by the consumer and creates the notification")
    void consumerProcessesRealKafkaEvent() throws Exception {
        var event = tokenCalled();

        kafkaTemplate.send(CareQueueEventPublisher.TOPIC_QUEUE, event.getTokenFormatted(), event).get();

        awaitNotifications(event.getEventId(), 1);
    }

    @Test
    @DisplayName("Same event delivered twice through Kafka creates only one notification")
    void duplicateKafkaDeliveryIsIdempotent() throws Exception {
        var event = tokenCalled();

        kafkaTemplate.send(CareQueueEventPublisher.TOPIC_QUEUE, event.getTokenFormatted(), event).get();
        kafkaTemplate.send(CareQueueEventPublisher.TOPIC_QUEUE, event.getTokenFormatted(), event).get();
        // a later distinct event proves both duplicates were consumed before we assert
        var marker = tokenCalled();
        kafkaTemplate.send(CareQueueEventPublisher.TOPIC_QUEUE, marker.getTokenFormatted(), marker).get();

        awaitNotifications(marker.getEventId(), 1);
        assertEquals(1, notifications.countByEventId(event.getEventId()));
    }

    @Test
    @DisplayName("Malformed and untrusted-type records are skipped safely and the consumer keeps working")
    void malformedRecordsDoNotBlockConsumer() throws Exception {
        Map<String, Object> props = KafkaTestUtils.producerProps(broker);
        try (KafkaProducer<String, String> raw = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            // 1. not JSON at all
            raw.send(new ProducerRecord<>(CareQueueEventPublisher.TOPIC_QUEUE, "bad", "{ this is not json")).get();
            // 2. valid JSON but the type header names a class outside the trusted package
            ProducerRecord<String, String> untrusted = new ProducerRecord<>(CareQueueEventPublisher.TOPIC_QUEUE, "evil", "{}");
            untrusted.headers().add("__TypeId__", "java.lang.ProcessBuilder".getBytes(StandardCharsets.UTF_8));
            raw.send(untrusted).get();
        }

        var good = tokenCalled();
        kafkaTemplate.send(CareQueueEventPublisher.TOPIC_QUEUE, good.getTokenFormatted(), good).get();

        awaitNotifications(good.getEventId(), 1);
    }

    @Test
    @DisplayName("Publisher -> Kafka -> consumer end to end: appointment cancelled event notifies the patient exactly once")
    void publisherToConsumerEndToEnd() {
        // Published outside a transaction => sent immediately by the real publisher. The in-process
        // listener AND the Kafka consumer both deliver it; idempotency keeps it to one notification.
        var event = CareQueueEvents.AppointmentCancelledEvent.builder()
            .appointmentId(9L).appointmentNumber("CQ-E2E-" + System.nanoTime()).patientUserId(patientUser.getId())
            .doctorName("Asha Rao").appointmentDate("2026-10-01").appointmentTime("09:20").reason("e2e").build();
        publisher.publishAppointmentCancelled(event);
        relayUntilSent(event.getEventId());

        awaitNotifications(event.getEventId(), 1);
        // give a late Kafka copy time to arrive, then confirm it did not add a second row
        try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertEquals(1, notifications.countByEventId(event.getEventId()));
    }


    /** Relays oldest-first until this event's outbox row is SENT (bounded). Requires the real broker. */
    private void relayUntilSent(String eventId) {
        for (int i = 0; i < 200; i++) {
            boolean sent = outboxRepo.findAll().stream().anyMatch(r ->
                r.getPayload().contains(eventId) && r.getStatus() == com.carequeue.entity.OutboxEvent.Status.SENT);
            if (sent) return;
            relay.relayPending();
        }
        throw new AssertionError("outbox event never reached the broker: " + eventId);
    }
}
