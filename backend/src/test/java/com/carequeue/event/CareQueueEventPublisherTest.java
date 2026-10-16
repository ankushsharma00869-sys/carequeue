package com.carequeue.event;

import com.carequeue.entity.OutboxEvent;
import com.carequeue.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the publisher's contract, with the outbox repository mocked:
 *  - the required Kafka copy is written immediately, inside the caller's transaction;
 *  - in-process notifications wait for commit and never run on rollback;
 *  - a failing outbox write fails the business operation (a required event cannot be dropped).
 * Real rollback and real delivery are covered by TransactionalEventIntegrationTest.
 */
class CareQueueEventPublisherTest {

    private ApplicationEventPublisher internalBus;
    private OutboxEventRepository outbox;
    private CareQueueEventPublisher publisher;

    @BeforeEach
    void setUp() {
        internalBus = mock(ApplicationEventPublisher.class);
        outbox = mock(OutboxEventRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        publisher = new CareQueueEventPublisher(internalBus, outbox, mapper);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private CareQueueEvents.TokenCalledEvent event() {
        return CareQueueEvents.TokenCalledEvent.builder().tokenFormatted("A-001").patientUserId(1L).build();
    }

    private List<TransactionSynchronization> pending() {
        return List.copyOf(TransactionSynchronizationManager.getSynchronizations());
    }

    @Test
    @DisplayName("The required Kafka copy is written to the outbox immediately, as PENDING, with topic and key")
    void outboxRowWrittenInTransaction() {
        publisher.publishTokenCalled(event());

        verify(outbox).save(argThat((OutboxEvent row) -> 
            row.getStatus() == OutboxEvent.Status.PENDING
                && CareQueueEventPublisher.TOPIC_QUEUE.equals(row.getTopic())
                && "A-001".equals(row.getMsgKey())
                && row.getEventClass().endsWith("TokenCalledEvent")
                && row.getPayload().contains("\"tokenFormatted\":\"A-001\"")));
    }

    @Test
    @DisplayName("In-process notifications run only after commit")
    void inProcessDeferredUntilCommit() {
        publisher.publishTokenCalled(event());
        verify(internalBus, never()).publishEvent(any());

        pending().forEach(TransactionSynchronization::afterCommit);

        verify(internalBus).publishEvent(any(CareQueueEvents.TokenCalledEvent.class));
    }

    @Test
    @DisplayName("Rolled-back operation: no in-process notification")
    void rollbackSuppressesInProcessEvent() {
        publisher.publishTokenCalled(event());
        pending().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(internalBus);
    }

    @Test
    @DisplayName("A failing outbox write fails the business operation: a required event is never silently dropped")
    void outboxFailureIsNotSwallowed() {
        doThrow(new IllegalStateException("db down")).when(outbox).save(any());

        assertThrows(IllegalStateException.class, () -> publisher.publishTokenCalled(event()));
    }
}
