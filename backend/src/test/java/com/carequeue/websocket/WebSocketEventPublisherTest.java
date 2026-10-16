package com.carequeue.websocket;

import com.carequeue.dto.NotificationDtos.NotificationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebSocketEventPublisherTest {

    private SimpMessagingTemplate template;
    private WebSocketEventPublisher publisher;

    @BeforeEach
    void setUp() {
        template = mock(SimpMessagingTemplate.class);
        publisher = new WebSocketEventPublisher(template);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("Patient notification is pushed only after commit")
    void pushedAfterCommit() {
        publisher.publishPatientNotification(7L, "YOUR TOKEN IS CALLED", "payload");
        verifyNoInteractions(template);

        List.copyOf(TransactionSynchronizationManager.getSynchronizations()).forEach(TransactionSynchronization::afterCommit);

        verify(template).convertAndSend(eq("/topic/patient/7"), any(Object.class));
    }

    @Test
    @DisplayName("Rolled-back transaction pushes nothing over WebSocket (queue, board, patient, emergency, in-app)")
    void nothingPushedOnRollback() {
        publisher.publishQueueUpdate(1L, null);
        publisher.publishQueueBoardUpdate(1L, null);
        publisher.publishPatientNotification(7L, "m", "p");
        publisher.publishEmergencyAlert("alert");
        publisher.publishInAppNotification(7L, NotificationResponse.builder().title("t").build());

        TransactionSynchronizationManager.getSynchronizations()
            .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("A WebSocket send failure after commit is contained (data is already committed)")
    void sendFailureContained() {
        doThrow(new RuntimeException("serialization")).when(template).convertAndSend(anyString(), any(Object.class));
        publisher.publishPatientNotification(7L, "m", "p");

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
            List.copyOf(TransactionSynchronizationManager.getSynchronizations()).forEach(TransactionSynchronization::afterCommit));
    }
}
