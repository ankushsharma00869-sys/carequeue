package com.carequeue.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** Structural guard: events must stay plain data with no entities and no credential/contact fields. */
class EventPayloadSafetyTest {

    private static final List<Class<?>> EVENTS = List.of(
        CareQueueEvents.AppointmentBookedEvent.class,
        CareQueueEvents.AppointmentCancelledEvent.class,
        CareQueueEvents.TokenCalledEvent.class,
        CareQueueEvents.QueueDelayedEvent.class,
        CareQueueEvents.EmergencyAlertEvent.class);

    @Test
    @DisplayName("No event field is a JPA entity or has a credential / contact-detail name")
    void noEntitiesOrSensitiveFields() {
        for (Class<?> type : EVENTS) {
            for (Field f : type.getDeclaredFields()) {
                assertFalse(f.getType().isAnnotationPresent(Entity.class), type.getSimpleName() + "." + f.getName() + " is a JPA entity");
                String n = f.getName().toLowerCase(Locale.ROOT);
                assertFalse(n.contains("password") || n.contains("hash") || n.contains("token") && n.contains("jwt"),
                    type.getSimpleName() + "." + f.getName() + " looks like a credential");
                assertFalse(n.equals("patientemail") || n.equals("patientphone") || n.equals("email") || n.equals("phone"),
                    type.getSimpleName() + "." + f.getName() + ": contact details must not travel in events");
            }
        }
    }

    @Test
    @DisplayName("Serialized JSON of a real event contains no password/hash text and round-trips")
    void serializedJsonIsClean() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var event = CareQueueEvents.TokenCalledEvent.builder()
            .queueEntryId(1L).doctorName("Dr X").roomNumber("104").patientId(2L).patientUserId(3L).tokenFormatted("A-001").build();

        String json = mapper.writeValueAsString(event);

        assertFalse(json.toLowerCase(Locale.ROOT).contains("password"));
        assertFalse(json.contains("$2a$") || json.contains("$2b$"));
        var back = mapper.readValue(json, CareQueueEvents.TokenCalledEvent.class);
        assertEquals(event.getEventId(), back.getEventId());
        assertEquals(3L, back.getPatientUserId());
    }
}
