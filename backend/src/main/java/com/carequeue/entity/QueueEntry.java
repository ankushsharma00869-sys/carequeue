package com.carequeue.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "queue_entries",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_doctor_date_token", columnNames = {"doctor_id", "schedule_date", "token_number"})
    },
    indexes = {
        @Index(name = "idx_qe_doc_date_stat", columnList = "doctor_id, schedule_date, status"),
        @Index(name = "idx_qe_pat_date", columnList = "patient_id, schedule_date"),
        @Index(name = "idx_qe_priority", columnList = "priority_rank, token_number")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueueEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(nullable = false)
    private LocalDate scheduleDate;

    @Column(nullable = false)
    private Integer tokenNumber;

    @Column(nullable = false, length = 20)
    private String tokenFormatted; // e.g. A-021 or E-001

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QueuePriority priority;

    @Column(nullable = false)
    private Integer priorityRank; // 1 for CRITICAL, 2 for PRIORITY, 3 for NORMAL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private QueueStatus status;

    private LocalDateTime calledAt;

    private LocalDateTime startedAt;

    /** When reception checked the patient in. Wait time is measured from here, not from booking. */
    private LocalDateTime checkedInAt;

    private LocalDateTime completedAt;

    @Builder.Default
    private Integer estimatedWaitMinutes = 0;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
