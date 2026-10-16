package com.carequeue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

public class AiDtos {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SymptomScreeningRequest {
        @NotBlank(message = "Symptoms description is required")
        @Size(max = 2000, message = "Symptom description is too long (max 2000 characters)")
        private String symptoms;

        private Integer patientAge;
        private String patientGender;
        private String duration; // e.g. "2 days"
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SymptomScreeningResponse {
        private String symptomSummary;
        private String suggestedDepartment;
        private String urgencyLevel; // ROUTINE, PRIORITY, EMERGENCY
        private List<String> questionsForDoctor;
        private String clinicalReasoning;
        private boolean isEmergencyAlert;
        private String medicalDisclaimer;
    }
}
