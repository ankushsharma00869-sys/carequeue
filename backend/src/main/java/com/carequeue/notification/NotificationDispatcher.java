package com.carequeue.notification;

import com.carequeue.dto.NotificationDtos.NotificationResponse;
import com.carequeue.entity.Notification;
import com.carequeue.entity.Role;
import com.carequeue.entity.User;
import com.carequeue.event.CareQueueEvents.*;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import com.carequeue.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns a domain event into (a) one persisted in-app notification per recipient and (b)
 * best-effort Email/SMS through the configured channel senders.
 *
 * Idempotent on (eventId, recipientUserId): the same event may arrive twice (in-process bus
 * AND Kafka, or a Kafka redelivery) and must still notify the person once. The in-app row
 * insert is the gate - if it already exists, nothing else happens (no second email/SMS).
 *
 * Deliberately NOT @Transactional: the write happens in NotificationWriter (REQUIRES_NEW),
 * so this is safe to call from an afterCommit callback or a Kafka listener thread.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationWriter writer;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final WebSocketEventPublisher wsEventPublisher;
    private final List<NotificationChannelSender> senders;

    /** @return number of NEW notifications created (0 for a duplicate or an event with no recipient). */
    public int handle(Object event) {
        if (event instanceof AppointmentBookedEvent e) {
            return deliver(e.getEventId(), e.getPatientUserId(), "APPOINTMENT_BOOKED", "Appointment confirmed",
                "Your appointment " + e.getAppointmentNumber() + " with Dr. " + e.getDoctorName() + " (" + e.getDepartmentName()
                    + ") is confirmed for " + e.getAppointmentDate() + " at " + e.getAppointmentTime()
                    + ". Token " + e.getTokenFormatted() + ", estimated wait " + e.getEstimatedWaitMinutes() + " min.", true);
        }
        if (event instanceof AppointmentCancelledEvent e) {
            return deliver(e.getEventId(), e.getPatientUserId(), "APPOINTMENT_CANCELLED", "Appointment cancelled",
                "Your appointment " + e.getAppointmentNumber() + " on " + e.getAppointmentDate()
                    + " has been cancelled. The slot has been released.", true);
        }
        if (event instanceof TokenCalledEvent e) {
            return deliver(e.getEventId(), e.getPatientUserId(), "TOKEN_CALLED", "Your token has been called",
                "Token " + e.getTokenFormatted() + " has been called. Please proceed to Room " + e.getRoomNumber()
                    + " (Dr. " + e.getDoctorName() + ").", true);
        }
        if (event instanceof EmergencyAlertEvent e) {
            // Staff alert: in-app only for the assigned doctor. No email/SMS to staff by default.
            return deliver(e.getEventId(), e.getDoctorUserId(), "EMERGENCY_ALERT", "Emergency patient " + e.getTokenFormatted(),
                e.getSeverity() + " emergency: " + e.getPatientName() + " - " + e.getTriageCondition()
                    + " (" + e.getDepartmentName() + ").", false);
        }
        log.debug("No notification mapping for event type {}", event == null ? null : event.getClass().getSimpleName());
        return 0;
    }

    private int deliver(String eventId, Long recipientUserId, String type, String title, String body, boolean external) {
        if (recipientUserId == null || eventId == null) {
            log.warn("Skipping {} notification: event has no recipient user id / event id", type);
            return 0;
        }

        Notification saved;
        try {
            saved = writer.insertIfAbsent(eventId, recipientUserId, type, title, body);
        } catch (DataIntegrityViolationException raceDuplicate) {
            // Two threads inserted the same (event, recipient) at once; the unique constraint let one win.
            saved = null;
        }
        if (saved == null) {
            log.info("Duplicate event {} for user {} ignored (already notified)", eventId, recipientUserId);
            return 0;
        }

        User user = userRepository.findById(recipientUserId).orElse(null);
        if (user == null) {
            return 1;
        }

        pushInApp(user, saved);
        if (external) {
            NotificationMessage message = new NotificationMessage(user.getId(), user.getEmail(), user.getPhoneNumber(), title, body);
            for (NotificationChannelSender sender : senders) {
                try {
                    if (sender.supports(message)) {
                        sender.send(message);
                    }
                } catch (Exception ex) {
                    log.warn("{} delivery failed for user {}: {}", sender.channel(), user.getId(), ex.toString());
                }
            }
        }
        return 1;
    }

    /** Live push to the patient's own private channel (the WebSocket layer only lets that patient subscribe). */
    private void pushInApp(User user, Notification saved) {
        if (user.getRole() != Role.PATIENT) {
            return;
        }
        try {
            patientRepository.findByUserId(user.getId()).ifPresent(p ->
                wsEventPublisher.publishInAppNotification(p.getId(), NotificationResponse.from(saved)));
        } catch (Exception ex) {
            log.warn("In-app WebSocket push failed for user {}: {}", user.getId(), ex.toString());
        }
    }
}
