package com.carequeue.dto;

import com.carequeue.entity.EmergencyRequest;
import lombok.*;

import java.time.LocalDateTime;

public class EmergencyDtos {

    /**
     * Emergency list item. Deliberately a DTO rather than the JPA entity: the entity reaches
     * Doctor -> User (password hash), and carries lazy hospital/department graphs. The patient
     * phone number is also omitted; the list only needs what the triage board displays.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EmergencyRequestResponse {
        private Long id;
        private String tokenFormatted;
        private String patientName;
        private String triageCondition;
        private String severity;
        private String status;
        private String departmentName;
        private String doctorName;
        private LocalDateTime createdAt;

        public static EmergencyRequestResponse from(EmergencyRequest e) {
            return EmergencyRequestResponse.builder()
                .id(e.getId())
                .tokenFormatted(e.getTokenFormatted())
                .patientName(e.getPatientName())
                .triageCondition(e.getTriageCondition())
                .severity(e.getSeverity() == null ? null : e.getSeverity().name())
                .status(e.getStatus())
                .departmentName(e.getDepartment() == null ? null : e.getDepartment().getName())
                .doctorName(e.getDoctor() == null || e.getDoctor().getUser() == null
                    ? null : e.getDoctor().getUser().getFullName())
                .createdAt(e.getCreatedAt())
                .build();
        }
    }
}
