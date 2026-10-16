package com.carequeue.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Transactional outbox row. A domain event is written in the SAME transaction as the business
 * change that produced it, so the event exists if and only if the change committed. OutboxRelay
 * later delivers PENDING rows to Kafka and retries them, so a broker outage delays delivery but
 * never loses the event.
 */
@Entity
@Table(name = "outbox_events", indexes = @Index(name = "idx_outbox_status_id", columnList = "status, id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    public enum Status { PENDING, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String topic;

    @Column(name = "msg_key", length = 120)
    private String msgKey;

    @Column(name = "event_class", nullable = false, length = 255)
    private String eventClass;

    @Column(nullable = false, length = 4000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** Earliest time the relay may try again after a failure. NULL means "as soon as possible". */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /** Which relay instance currently owns the row, and until when. Expired leases are reclaimable. */
    @Column(name = "lease_owner", length = 80)
    private String leaseOwner;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;
}
