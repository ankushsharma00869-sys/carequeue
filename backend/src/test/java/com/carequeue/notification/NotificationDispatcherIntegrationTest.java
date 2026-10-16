package com.carequeue.notification;

import com.carequeue.entity.Notification;
import com.carequeue.entity.User;
import com.carequeue.event.CareQueueEvents;
import com.carequeue.notification.NotificationTestSupport.RecordingSender;
import com.carequeue.repository.NotificationRepository;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real dispatcher, real writer, real H2 database, real repositories. No Kafka involved. */
@SpringBootTest
@ActiveProfiles("dev")
@Import(NotificationTestSupport.RecordingSenderConfig.class)
class NotificationDispatcherIntegrationTest {

    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private NotificationRepository notifications;
    @Autowired private UserRepository users;
    @Autowired private PatientRepository patients;
    @Autowired private RecordingSender recordingSender;

    private User patientUser;

    @BeforeEach
    void setUp() {
        recordingSender.sent.clear();
        recordingSender.failOnSend = false;
        patientUser = NotificationTestSupport.newPatientUser(users, patients);
    }

    private CareQueueEvents.TokenCalledEvent tokenCalled() {
        return CareQueueEvents.TokenCalledEvent.builder()
            .queueEntryId(1L).doctorId(1L).doctorName("Asha Rao").roomNumber("104")
            .patientId(1L).patientUserId(patientUser.getId()).tokenFormatted("A-021").build();
    }

    @Test
    @DisplayName("Event is processed: in-app notification is persisted and the SMS channel is invoked once")
    void processesEvent() {
        var event = tokenCalled();

        assertEquals(1, dispatcher.handle(event));

        List<Notification> saved = notifications.findByRecipientUserIdOrderByCreatedAtDesc(
            patientUser.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertEquals(1, saved.size());
        assertEquals("TOKEN_CALLED", saved.get(0).getType());
        assertTrue(saved.get(0).getMessage().contains("A-021"));
        assertFalse(saved.get(0).isRead());
        assertEquals(1, recordingSender.sent.size());
    }

    @Test
    @DisplayName("Duplicate event does NOT create a duplicate notification or a second SMS/email")
    void duplicateEventIgnored() {
        var event = tokenCalled();

        assertEquals(1, dispatcher.handle(event));
        assertEquals(0, dispatcher.handle(event));
        assertEquals(0, dispatcher.handle(event));

        assertEquals(1, notifications.countByEventId(event.getEventId()));
        assertEquals(1, recordingSender.sent.size(), "external channel must not fire again for a duplicate");
    }

    @Test
    @DisplayName("Concurrent delivery of the same event (bus + Kafka racing) still produces exactly one notification")
    void concurrentDuplicates() throws Exception {
        var event = tokenCalled();
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> { go.await(); return dispatcher.handle(event); }));
        }
        go.countDown();
        int created = 0;
        for (Future<Integer> f : results) created += f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, created);
        assertEquals(1, notifications.countByEventId(event.getEventId()));
        assertEquals(1, recordingSender.sent.size());
    }

    @Test
    @DisplayName("Event without a recipient is skipped safely")
    void noRecipient() {
        var event = tokenCalled();
        event.setPatientUserId(null);
        assertEquals(0, dispatcher.handle(event));
        assertEquals(0, notifications.countByEventId(event.getEventId()));
    }

    @Test
    @DisplayName("Unknown event type / null is handled without throwing")
    void unknownEvent() {
        assertEquals(0, dispatcher.handle("not an event"));
        assertEquals(0, dispatcher.handle(null));
    }

    @Test
    @DisplayName("A failing SMS/email provider does not lose the in-app notification or throw")
    void providerFailureDoesNotBreakNotification() {
        recordingSender.failOnSend = true;
        var event = tokenCalled();

        assertDoesNotThrow(() -> dispatcher.handle(event));

        assertEquals(1, notifications.countByEventId(event.getEventId()));
    }

    @Test
    @DisplayName("Emergency alert goes in-app to the doctor only (no email/SMS to staff)")
    void emergencyAlertInAppOnly() {
        User doctorUser = NotificationTestSupport.newPatientUser(users, patients); // any persisted user id works as recipient
        var event = CareQueueEvents.EmergencyAlertEvent.builder()
            .emergencyRequestId(1L).patientName("Walk In").triageCondition("chest pain")
            .severity("CRITICAL").tokenFormatted("E-001").departmentName("Cardiology")
            .doctorUserId(doctorUser.getId()).build();

        assertEquals(1, dispatcher.handle(event));
        assertEquals(0, recordingSender.sent.size());
    }

    @Test
    @DisplayName("Stored notification, its DTO JSON and the SMS text contain no password hash or other sensitive fields")
    void noSensitiveDataLeaks() throws Exception {
        var event = tokenCalled();
        dispatcher.handle(event);

        Notification saved = notifications.findByRecipientUserIdOrderByCreatedAtDesc(
            patientUser.getId(), org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0);
        String json = new ObjectMapper().registerModule(new JavaTimeModule())
            .writeValueAsString(com.carequeue.dto.NotificationDtos.NotificationResponse.from(saved));

        String hash = patientUser.getPassword();
        assertFalse(saved.getMessage().contains(hash));
        assertFalse(json.contains(hash));
        assertFalse(json.toLowerCase().contains("password"));
        assertFalse(json.contains("recipientUserId"), "DTO should not expose internal ids of the recipient");
        assertFalse(recordingSender.sent.get(0).body().contains(hash));
    }

    @Test
    @DisplayName("Different events for the same patient each create their own notification")
    void distinctEventsAreDistinct() {
        dispatcher.handle(tokenCalled());
        dispatcher.handle(tokenCalled());
        assertEquals(2, notifications.countByRecipientUserIdAndReadFalse(patientUser.getId()));
    }
}
