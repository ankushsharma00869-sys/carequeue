package com.carequeue.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Backs human-readable appointment numbers (e.g. CQ-2026-10027) with a real,
 * row-locked counter instead of appointmentRepository.count() + 1.
 *
 * One row per calendar year. incrementAndGet() is called from
 * AppointmentNumberGenerator inside a PESSIMISTIC_WRITE-locked, REQUIRES_NEW
 * transaction, so concurrent requests serialize on this single row and each
 * gets a distinct, gap-free value even under heavy concurrent booking load.
 */
@Entity
@Table(name = "appointment_sequences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentSequence {

    @Id
    @Column(name = "year_value")
    private Integer year;

    @Column(name = "last_value", nullable = false)
    private long lastValue;
}
