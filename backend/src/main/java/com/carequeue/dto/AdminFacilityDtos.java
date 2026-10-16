package com.carequeue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

/** Request bodies for admin-managed hospitals and departments. */
public class AdminFacilityDtos {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateHospitalRequest {
        @NotBlank(message = "Hospital name is required")
        @Size(max = 150)
        private String name;

        @NotBlank(message = "Hospital code is required")
        @Size(max = 20)
        private String code;

        @Size(max = 255)
        private String address;

        @Size(max = 80)
        private String city;

        @Size(max = 20)
        private String phone;

        @Size(max = 20)
        private String emergencyContact;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateDepartmentRequest {
        @NotNull(message = "Hospital is required")
        private Long hospitalId;

        @NotBlank(message = "Department name is required")
        @Size(max = 100)
        private String name;

        @NotBlank(message = "Department code is required")
        @Size(max = 20)
        private String code;

        @Size(max = 20)
        private String floorNumber;

        @Size(max = 300)
        private String description;
    }
}
