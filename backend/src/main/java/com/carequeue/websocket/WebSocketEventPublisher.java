package com.carequeue.websocket;

import com.carequeue.dto.QueueDtos;
import com.carequeue.event.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public void publishQueueUpdate(Long doctorId, QueueDtos.DoctorQueueOverviewResponse queueData) {
        // Full detail (patient names + phone numbers): staff-only channel — see
        // WebSocketAuthInterceptor, which rejects SUBSCRIBE to this topic for anyone who
        // isn't this doctor, a receptionist, or an admin.
        log.info("Pushing WebSocket live queue update for doctorId: {}", doctorId);
        AfterCommit.run("websocket push", () -> messagingTemplate.convertAndSend("/topic/queue/" + doctorId, queueData));
    }

    public void publishQueueBoardUpdate(Long doctorId, QueueDtos.QueueBoardResponse boardData) {
        // Sanitized (token numbers/status only): any authenticated user may subscribe.
        log.info("Pushing WebSocket queue board update for doctorId: {}", doctorId);
        AfterCommit.run("websocket push", () -> messagingTemplate.convertAndSend("/topic/queue/" + doctorId + "/board", boardData));
    }

    public void publishPatientNotification(Long patientId, String message, Object payload) {
        log.info("Pushing WebSocket notification to patientId: {}", patientId);
        AfterCommit.run("websocket push", () -> messagingTemplate.convertAndSend("/topic/patient/" + patientId, java.util.Map.of("message", message, "queueEntry", payload)));
    }

    public void publishEmergencyAlert(Object emergencyData) {
        log.warn("Broadcasting Emergency Alert via WebSocket /topic/hospital/emergency");
        AfterCommit.run("websocket push", () -> messagingTemplate.convertAndSend("/topic/hospital/emergency", emergencyData));
    }

    /** Persisted in-app notification for a patient, pushed on that patient's private channel. */
    public void publishInAppNotification(Long patientId, com.carequeue.dto.NotificationDtos.NotificationResponse notification) {
        AfterCommit.run("websocket in-app notification", () ->
            messagingTemplate.convertAndSend("/topic/patient/" + patientId, java.util.Map.of("message", notification.getTitle(), "notification", notification)));
    }
}
