package com.carequeue.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Persisted in-app notification. The unique (event_id, recipient_user_id) constraint is
 * what makes Kafka's at-least-once delivery safe: a redelivered/duplicated event tries to
 * insert the same pair again and is rejected, so no duplicate notification (and no second
 * email/SMS) is produced.
 */
@Entity
@Table(name = "notifications",
    uniqueConstraints = @UniqueConstraint(name = "uk_notification_event_recipient",
        columnNames = {"event_id", "recipient_user_id"}),
    indexes = @Index(name = "idx_notification_recipient", columnList = "recipient_user_id, is_read, created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 600)
    private String message;

    @Builder.Default
    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
