package com.carequeue.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Slf4j
@Configuration
public class KafkaConsumerConfig {

    /** Picked up automatically by Spring Boot's listener container factory. */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(
            (record, ex) -> log.error("Giving up on Kafka record topic={} partition={} offset={}: {}",
                record.topic(), record.partition(), record.offset(), ex.toString()),
            new FixedBackOff(500L, 2L));
    }

    /** Explicit topics (only when enabled: creating them blocks startup if the broker is unreachable). */
    @Configuration
    @ConditionalOnProperty(name = "carequeue.kafka.create-topics", havingValue = "true")
    static class TopicConfig {
        @Bean NewTopic appointmentsTopic() { return TopicBuilder.name("carequeue.appointments").partitions(1).replicas(1).build(); }
        @Bean NewTopic queueTopic() { return TopicBuilder.name("carequeue.queue").partitions(1).replicas(1).build(); }
        @Bean NewTopic emergencyTopic() { return TopicBuilder.name("carequeue.emergency").partitions(1).replicas(1).build(); }
    }
}
