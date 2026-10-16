package com.carequeue.service;

import com.carequeue.entity.Patient;
import com.carequeue.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Explainable rule-based clinical no-show risk assessment engine.
 * Designed with a clean interface so an ML regression/classification model can plug in later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleBasedNoShowPredictor implements NoShowPredictor {

    private final AppointmentRepository appointmentRepository;

    @Override
    public RiskAssessment assessRisk(Patient patient, LocalDate appointmentDate, LocalTime appointmentTime, LocalDate bookingDate) {
        if (patient == null) {
            return RiskAssessment.builder()
                .score(25)
                .riskLevel("LOW")
                .contributingFactors(List.of("Walk-in patient without prior historical records"))
                .build();
        }

        int score = 15; // Base score
        List<String> factors = new ArrayList<>();

        long pastNoShows = appointmentRepository.countNoShowsByPatientId(patient.getId());
        long pastCancellations = appointmentRepository.countCancellationsByPatientId(patient.getId());
        long totalPriorAppts = appointmentRepository.countTotalAppointmentsByPatientId(patient.getId());

        // Factor 1: Prior No-Shows
        if (pastNoShows >= 2) {
            score += 40;
            factors.add(String.format("Patient has %d previous unexcused no-shows (+40 risk)", pastNoShows));
        } else if (pastNoShows == 1) {
            score += 20;
            factors.add("Patient has 1 previous no-show (+20 risk)");
        }

        // Factor 2: Prior Cancellations
        if (pastCancellations >= 3) {
            score += 15;
            factors.add(String.format("High prior cancellation frequency (%d cancellations, +15 risk)", pastCancellations));
        }

        // Factor 3: Booking Lead Time
        long leadTimeDays = ChronoUnit.DAYS.between(bookingDate, appointmentDate);
        if (leadTimeDays <= 0) {
            score -= 10;
            factors.add("Same-day booking strongly correlates with high attendance (-10 risk)");
        } else if (leadTimeDays >= 14) {
            score += 20;
            factors.add(String.format("Long booking lead time (%d days ahead, +20 risk of scheduling conflict)", leadTimeDays));
        } else if (leadTimeDays >= 7) {
            score += 10;
            factors.add("Lead time between 7-13 days (+10 risk)");
        }

        // Factor 4: Appointment Time of Day
        if (appointmentTime != null) {
            if (appointmentTime.isBefore(LocalTime.of(8, 30))) {
                score += 10;
                factors.add("Early morning slot before 08:30 has elevated transit delay risk (+10 risk)");
            } else if (appointmentTime.isAfter(LocalTime.of(18, 0))) {
                score += 10;
                factors.add("Late evening slot after 18:00 has elevated fatigue/work conflict risk (+10 risk)");
            }
        }

        // Factor 5: Patient Loyalty / Attendance History
        if (totalPriorAppts >= 3 && pastNoShows == 0) {
            score -= 15;
            factors.add("Established patient with consistent prior attendance history (-15 risk)");
        }

        // Bound score between 0 and 100
        score = Math.max(5, Math.min(95, score));

        String riskLevel;
        if (score >= 60) {
            riskLevel = "HIGH";
        } else if (score >= 35) {
            riskLevel = "MEDIUM";
        } else {
            riskLevel = "LOW";
        }

        return RiskAssessment.builder()
            .score(score)
            .riskLevel(riskLevel)
            .contributingFactors(factors)
            .build();
    }
}
