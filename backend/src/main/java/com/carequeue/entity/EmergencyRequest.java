package com.carequeue.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "emergency_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmergencyRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    @Column(nullable = false, length = 120)
    private String patientName;

    @Column(length = 20)
    private String patientPhone;

    @Column(nullable = false, length = 300)
    private String triageCondition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmergencySeverity severity;

    @Column(nullable = false, length = 20)
    private String tokenFormatted; // E-001

    @Builder.Default
    @Column(nullable = false, length = 30)
    private String status = "ADMITTED";

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
