package com.carequeue.notification;

import com.carequeue.entity.Notification;
import com.carequeue.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate bean so REQUIRES_NEW is honoured through the Spring proxy.
 *
 * REQUIRES_NEW matters twice over: (1) notifications are dispatched from an afterCommit
 * callback, where the original transaction is finished but its resources are still bound to
 * the thread - joining it (REQUIRED) would silently never commit this insert; (2) a unique-
 * constraint violation from a duplicate event must roll back only this tiny transaction and
 * surface to the caller, rather than poisoning a larger one.
 */
@Component
@RequiredArgsConstructor
public class NotificationWriter {

    private final NotificationRepository notificationRepository;

    /** @return true if a new notification row was written, false if this (eventId, recipient) already existed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification insertIfAbsent(String eventId, Long recipientUserId, String type, String title, String message) {
        if (notificationRepository.existsByEventIdAndRecipientUserId(eventId, recipientUserId)) {
            return null;
        }
        return notificationRepository.saveAndFlush(Notification.builder()
            .eventId(eventId)
            .recipientUserId(recipientUserId)
            .type(type)
            .title(title)
            .message(message)
            .build());
    }
}
