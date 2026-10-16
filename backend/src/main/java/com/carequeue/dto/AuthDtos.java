package com.carequeue.dto;

import com.carequeue.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

public class AuthDtos {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LoginRequest {
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @NotBlank(message = "Password is required")
        private String password;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RegisterRequest {
        @NotBlank(message = "Full name is required")
        private String fullName;

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @NotBlank(message = "Password is required")
        @Size(min = 6, message = "Password must be at least 6 characters")
        private String password;

        private String phoneNumber;
        private Role role; // Defaults to PATIENT if not provided
        
        // Optional patient profile data
        private String dateOfBirth;
        private String gender;
        private String bloodGroup;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AuthResponse {
        private String token;
        private String tokenType;
        private Long userId;
        private String email;
        private String fullName;
        private Role role;
        private Long doctorId; // null if not doctor
        private Long patientId; // null if not patient
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserProfileResponse {
        private Long id;
        private String email;
        private String fullName;
        private String phoneNumber;
        private Role role;
        private Long doctorId;
        private Long patientId;
        private String departmentName;
        private String hospitalName;
    }
}
