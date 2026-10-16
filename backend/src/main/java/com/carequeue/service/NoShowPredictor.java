package com.carequeue.service;

import com.carequeue.entity.Patient;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface NoShowPredictor {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    class RiskAssessment {
        private int score; // 0 to 100
        private String riskLevel; // LOW, MEDIUM, HIGH
        private List<String> contributingFactors;
    }

    RiskAssessment assessRisk(Patient patient, LocalDate appointmentDate, LocalTime appointmentTime, LocalDate bookingDate);
}
