package com.carequeue.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public class HospitalDoctorDtos {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HospitalDto {
        private Long id;
        private String name;
        private String code;
        private String address;
        private String city;
        private String phone;
        private String emergencyContact;
        private boolean active;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DepartmentDto {
        private Long id;
        private Long hospitalId;
        private String hospitalName;
        private String name;
        private String code;
        private String description;
        private String floorNumber;
        private String icon;
        private boolean active;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DoctorDto {
        private Long id;
        private Long userId;
        private String fullName;
        private String email;
        private String phone;
        private Long hospitalId;
        private String hospitalName;
        private Long departmentId;
        private String departmentName;
        private String licenseNumber;
        private String specialization;
        private String roomNumber;
        private BigDecimal consultationFee;
        private String bio;
        private Integer averageConsultationMinutes;
        private boolean active;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DoctorScheduleDto {
        private Long id;
        private Long doctorId;
        private DayOfWeek dayOfWeek;
        private LocalTime startTime;
        private LocalTime endTime;
        private Integer maxPatients;
        private Integer slotDurationMinutes;
        private boolean active;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimeSlotDto {
        private LocalTime time;
        private String formattedTime;
        private boolean available;
        private String reason; // e.g. "Available", "Booked", "Past Time"
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DoctorAvailabilityResponse {
        private Long doctorId;
        private String doctorName;
        private String departmentName;
        private LocalDate date;
        private DayOfWeek dayOfWeek;
        private boolean onDuty;
        private LocalTime shiftStart;
        private LocalTime shiftEnd;
        private Integer slotDurationMinutes;
        private List<TimeSlotDto> availableSlots;
        private int totalSlots;
        private int bookedSlots;
    }

    /**
     * Full doctor provisioning request for POST /api/admin/doctors — the "complete doctor
     * provisioning flow" the audit brief calls for. Unlike the older POST /api/admin/staff
     * (which only ever created a login User row), this creates the User + Doctor profile +
     * hospital/department assignment + optional initial weekly schedule together in one
     * transaction, so a DOCTOR user can never exist without a matching Doctor profile.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateDoctorRequest {
        @jakarta.validation.constraints.NotBlank(message = "Full name is required")
        private String fullName;

        @jakarta.validation.constraints.NotBlank(message = "Email is required")
        @jakarta.validation.constraints.Email(message = "Invalid email format")
        private String email;

        @jakarta.validation.constraints.NotBlank(message = "Password is required")
        @jakarta.validation.constraints.Size(min = 6, message = "Password must be at least 6 characters")
        private String password;

        private String phoneNumber;

        @jakarta.validation.constraints.NotNull(message = "Hospital is required")
        private Long hospitalId;

        @jakarta.validation.constraints.NotNull(message = "Department is required")
        private Long departmentId;

        @jakarta.validation.constraints.NotBlank(message = "License number is required")
        private String licenseNumber;

        @jakarta.validation.constraints.NotBlank(message = "Specialization is required")
        private String specialization;

        private String roomNumber;
        private BigDecimal consultationFee;
        private String bio;
        private Integer averageConsultationMinutes;

        // Optional: provision the doctor's initial weekly schedule at the same time.
        private List<DoctorScheduleDto> initialSchedule;
    }

    /**
     * Receptionist provisioning request for POST /api/admin/receptionists. Creates the
     * User account with the RECEPTIONIST role and assigns it to a hospital, which the
     * older generic POST /api/admin/staff endpoint had no field for at all.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateReceptionistRequest {
        @jakarta.validation.constraints.NotBlank(message = "Full name is required")
        private String fullName;

        @jakarta.validation.constraints.NotBlank(message = "Email is required")
        @jakarta.validation.constraints.Email(message = "Invalid email format")
        private String email;

        @jakarta.validation.constraints.NotBlank(message = "Password is required")
        @jakarta.validation.constraints.Size(min = 6, message = "Password must be at least 6 characters")
        private String password;

        private String phoneNumber;

        @jakarta.validation.constraints.NotNull(message = "Hospital is required")
        private Long hospitalId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DoctorStatusUpdateRequest {
        @jakarta.validation.constraints.NotNull(message = "active flag is required")
        private Boolean active;
    }
}
