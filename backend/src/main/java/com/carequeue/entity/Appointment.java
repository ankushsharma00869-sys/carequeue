package com.carequeue.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "appointments", indexes = {
    @Index(name = "idx_appt_num", columnList = "appointment_number", unique = true),
    @Index(name = "idx_appt_pat", columnList = "patient_id"),
    @Index(name = "idx_appt_doc_date", columnList = "doctor_id, appointment_date"),
    @Index(name = "idx_appt_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_number", nullable = false, unique = true, length = 40)
    private String appointmentNumber;

    /**
     * "doctorId|date|time" while the appointment occupies its slot, null once cancelled. The unique
     * constraint makes the database refuse a second active booking for the same slot, even when two
     * transactions race past the application-level checks. NULLs do not collide, so cancelled rows
     * and rows created before this column existed are unaffected.
     */
    @Column(name = "active_slot_key", length = 80, unique = true)
    private String activeSlotKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(nullable = false)
    private LocalDate appointmentDate;

    @Column(nullable = false)
    private LocalTime appointmentTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AppointmentStatus status;

    @Column(length = 255)
    private String cancellationReason;

    private LocalDateTime cancelledAt;

    private LocalDateTime checkedInAt;

    @Column(length = 20)
    private String tokenFormatted;

    @Column(length = 500)
    private String patientNotes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
