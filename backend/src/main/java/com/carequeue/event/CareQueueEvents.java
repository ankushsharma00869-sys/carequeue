package com.carequeue.event;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain events published to Kafka and the in-process event bus.
 *
 * Rules for everything in this file:
 *  - Plain data only: no JPA entities, no User/Patient objects. (An entity graph carries
 *    lazy proxies that Jackson cannot serialize, and via patient.user the password hash.)
 *  - Every event has an {@code eventId}. Kafka delivery is at-least-once, so consumers
 *    de-duplicate on it (see NotificationDispatcher).
 *  - Events identify recipients by user id. Contact details (email/phone) are deliberately
 *    NOT in the payload; the notification layer looks them up when it actually sends, so
 *    they are not copied onto a shared Kafka topic.
 */
public class CareQueueEvents {

    public interface CareQueueEvent {
        String getEventId();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AppointmentBookedEvent implements Serializable, CareQueueEvent {
        @Builder.Default
        private String eventId = UUID.randomUUID().toString();
        private Long appointmentId;
        private String appointmentNumber;
        private Long patientId;
        private Long patientUserId;
        private Long doctorId;
        private String doctorName;
        private String departmentName;
        private String appointmentDate;
        private String appointmentTime;
        private String tokenFormatted;
        private Integer estimatedWaitMinutes;
        @Builder.Default
        private LocalDateTime timestamp = LocalDateTime.now();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AppointmentCancelledEvent implements Serializable, CareQueueEvent {
        @Builder.Default
        private String eventId = UUID.randomUUID().toString();
        private Long appointmentId;
        private String appointmentNumber;
        private Long patientUserId;
        private String doctorName;
        private String appointmentDate;
        private String appointmentTime;
        private String reason;
        @Builder.Default
        private LocalDateTime timestamp = LocalDateTime.now();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TokenCalledEvent implements Serializable, CareQueueEvent {
        @Builder.Default
        private String eventId = UUID.randomUUID().toString();
        private Long queueEntryId;
        private Long doctorId;
        private String doctorName;
        private String roomNumber;
        private Long patientId;
        private Long patientUserId;
        private String tokenFormatted;
        @Builder.Default
        private LocalDateTime timestamp = LocalDateTime.now();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QueueDelayedEvent implements Serializable, CareQueueEvent {
        @Builder.Default
        private String eventId = UUID.randomUUID().toString();
        private Long doctorId;
        private String doctorName;
        private int delayMinutes;
        private String reason;
        @Builder.Default
        private LocalDateTime timestamp = LocalDateTime.now();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EmergencyAlertEvent implements Serializable, CareQueueEvent {
        @Builder.Default
        private String eventId = UUID.randomUUID().toString();
        private Long emergencyRequestId;
        // Staff-only clinical alert: the assigned doctor needs to know who and why.
        private String patientName;
        private String triageCondition;
        private String severity;
        private String tokenFormatted;
        private String departmentName;
        private Long doctorUserId;
        @Builder.Default
        private LocalDateTime timestamp = LocalDateTime.now();
    }
}
