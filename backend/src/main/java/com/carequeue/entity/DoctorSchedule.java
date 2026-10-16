package com.carequeue.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalTime;

@Entity
@Table(name = "doctor_schedules", indexes = {
    @Index(name = "idx_sched_doc_day", columnList = "doctor_id, day_of_week")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Builder.Default
    @Column(nullable = false)
    private Integer maxPatients = 40;

    @Builder.Default
    @Column(nullable = false)
    private Integer slotDurationMinutes = 15;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
