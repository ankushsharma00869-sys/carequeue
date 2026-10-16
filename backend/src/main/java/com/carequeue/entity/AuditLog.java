package com.carequeue.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_actor", columnList = "actor_email"),
    @Index(name = "idx_audit_action", columnList = "action")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_email", nullable = false, length = 100)
    private String actorEmail;

    @Column(name = "actor_role", nullable = false, length = 30)
    private String actorRole;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(length = 50)
    private String resourceType;

    @Column(length = 50)
    private String resourceId;

    @Column(length = 1000)
    private String details;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime timestamp;
}
