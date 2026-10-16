package com.carequeue.dto;

import com.carequeue.entity.EmergencySeverity;
import com.carequeue.entity.QueuePriority;
import com.carequeue.entity.QueueStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public class QueueDtos {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QueueEntryResponse {
        private Long id;
        private Long appointmentId;
        private String appointmentNumber;
        private Long doctorId;
        private String doctorName;
        private Long patientId;
        private String patientName;
        private String patientPhone;
        private LocalDate scheduleDate;
        private Integer tokenNumber;
        private String tokenFormatted; // A-021 or E-001
        private QueuePriority priority;
        private Integer priorityRank;
        private QueueStatus status;
        private Integer peopleAhead;
        private Integer estimatedWaitMinutes;
        private boolean isCurrent;
        private boolean isNext;
        private boolean isEmergency;
        private LocalDateTime calledAt;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private String roomNumber;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DoctorQueueOverviewResponse {
        private Long doctorId;
        private String doctorName;
        private String specialization;
        private String roomNumber;
        private String departmentName;
        private LocalDate date;
        private QueueEntryResponse currentPatient;
        private QueueEntryResponse nextPatient;
        private int totalPatientsToday;
        private int waitingCount;
        private int inConsultationCount;
        private int completedCount;
        private int skippedCount;
        private int emergencyCount;
        private int noShowCount;
        private List<QueueEntryResponse> waitingQueue;
        private List<QueueEntryResponse> completedQueue;
    }

    /**
     * Sanitized waiting-room "queue board" view — token numbers and status only.
     * Deliberately has NO patientName / patientPhone fields: this is broadcast on
     * /topic/queue/{doctorId}/board and returned from the board REST endpoint, both of
     * which any authenticated user (including other patients) may subscribe to/call, so
     * it must never carry another patient's personal information. Full-detail overview
     * (DoctorQueueOverviewResponse, with names/phone) stays restricted to the assigned
     * doctor, receptionists and admins.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QueueBoardEntryResponse {
        private String tokenFormatted;
        private QueueStatus status;
        private boolean isEmergency;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QueueBoardResponse {
        private Long doctorId;
        private String doctorName;
        private String departmentName;
        private String roomNumber;
        private LocalDate date;
        private String currentToken;
        private String nextToken;
        private int waitingCount;
        private int emergencyCount;
        private List<QueueBoardEntryResponse> waitingTokens;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PatientLiveQueueResponse {
        private Long appointmentId;
        private String appointmentNumber;
        private String yourToken;
        private String currentToken;
        private String nextToken;
        private int peopleAhead;
        private int estimatedWaitMinutes;
        private QueueStatus status;
        private String doctorName;
        private String departmentName;
        private String roomNumber;
        private LocalDate appointmentDate;
        private LocalTime appointmentTime;
        private List<QueueTimelineStep> timeline;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class QueueTimelineStep {
        private String tokenFormatted;
        private String label; // "Completed", "Current", "Next", "You", "Waiting"
        private QueueStatus status;
        private boolean isUserToken;
        private boolean isEmergency;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EmergencyTriageRequest {
        @NotNull(message = "Hospital ID is required")
        private Long hospitalId;

        @NotNull(message = "Department ID is required")
        private Long departmentId;

        private Long doctorId; // Optional; will assign to primary on-duty doctor if null

        @NotBlank(message = "Patient name is required")
        private String patientName;

        private String patientPhone;

        @NotBlank(message = "Triage condition description is required")
        private String triageCondition;

        @NotNull(message = "Severity is required")
        private EmergencySeverity severity;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ConsultationActionRequest {
        private String primaryDiagnosis;
        private String clinicalNotes;
        private String prescriptionSummary;
        private LocalDate followUpDate;
    }
}
