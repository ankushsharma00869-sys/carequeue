package com.carequeue.notification;

import com.carequeue.event.CareQueueEventPublisher;
import com.carequeue.event.CareQueueEvents.CareQueueEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for all CareQueue topics. Off by default (carequeue.kafka.consumer-enabled)
 * so local development without a broker does not spin a reconnect loop; docker-compose enables it.
 *
 * Failure handling (see KafkaConsumerConfig): undeserializable records are wrapped by
 * ErrorHandlingDeserializer and skipped without retry; processing errors are retried twice
 * (500 ms apart) and then logged and skipped, so one bad record never blocks the partition.
 * Duplicates are harmless: NotificationDispatcher is idempotent on eventId.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CareQueueEventConsumer {

    private final NotificationDispatcher dispatcher;

    @KafkaListener(
        topics = {CareQueueEventPublisher.TOPIC_APPOINTMENTS, CareQueueEventPublisher.TOPIC_QUEUE, CareQueueEventPublisher.TOPIC_EMERGENCY},
        groupId = "carequeue-notifications",
        autoStartup = "${carequeue.kafka.consumer-enabled:false}")
    public void onMessage(ConsumerRecord<String, Object> record) {
        Object value = record.value();
        if (!(value instanceof CareQueueEvent event)) {
            log.warn("Skipping record with unexpected payload on {}@{}: {}", record.topic(), record.offset(),
                value == null ? "null" : value.getClass().getName());
            return;
        }
        dispatcher.handle(event);
    }
}
