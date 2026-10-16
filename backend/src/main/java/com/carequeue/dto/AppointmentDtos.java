package com.carequeue.dto;

import com.carequeue.entity.AppointmentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class AppointmentDtos {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BookAppointmentRequest {
        @NotNull(message = "Hospital ID is required")
        private Long hospitalId;

        @NotNull(message = "Department ID is required")
        private Long departmentId;

        @NotNull(message = "Doctor ID is required")
        private Long doctorId;

        @NotNull(message = "Appointment date is required")
        private LocalDate appointmentDate;

        @NotNull(message = "Appointment time is required")
        private LocalTime appointmentTime;

        private String patientNotes;
        
        // For receptionist booking on behalf of patient
        private Long patientId;
        private String walkInPatientName;
        private String walkInPatientPhone;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CancelAppointmentRequest {
        private String reason;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AppointmentResponse {
        private Long id;
        private String appointmentNumber;
        private Long patientId;
        private String patientName;
        private String patientPhone;
        private Long doctorId;
        private String doctorName;
        private String doctorSpecialization;
        private String doctorRoom;
        private Long hospitalId;
        private String hospitalName;
        private Long departmentId;
        private String departmentName;
        private LocalDate appointmentDate;
        private LocalTime appointmentTime;
        private AppointmentStatus status;
        private String tokenFormatted;
        private Integer estimatedWaitMinutes;
        private String patientNotes;
        private String cancellationReason;
        private boolean canCancel; // evaluated based on >30 mins rule
        private String noShowRiskLevel; // LOW, MEDIUM, HIGH
        private Integer noShowRiskScore; // 0 - 100
        private LocalDateTime createdAt;
    }
}
