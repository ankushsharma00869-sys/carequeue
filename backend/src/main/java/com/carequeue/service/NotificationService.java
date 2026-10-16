package com.carequeue.service;

import com.carequeue.event.CareQueueEvents.CareQueueEvent;
import com.carequeue.notification.NotificationDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * In-process listener: notifications are produced right after commit even when Kafka is
 * down or the consumer is disabled. The Kafka consumer (CareQueueEventConsumer) feeds the
 * same dispatcher; NotificationDispatcher de-duplicates on eventId, so having both paths is
 * safe. (Previously this class only wrote banner lines to the log.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationDispatcher dispatcher;

    @EventListener
    public void onEvent(CareQueueEvent event) {
        dispatcher.handle(event);
    }
}
