package com.carequeue.service;

import com.carequeue.entity.Patient;
import com.carequeue.entity.Role;
import com.carequeue.entity.User;
import com.carequeue.exception.ConflictException;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Reception registers a patient who has no online account. Duplicates are refused by email or mobile,
 * so the same person is never registered twice. Walk-in accounts cannot log in (see UserPrincipal).
 */
@Service
@RequiredArgsConstructor
public class WalkInPatientService {

    private static final String PLACEHOLDER_DOMAIN = "walkin.carequeue.local";

    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WalkInPatientRequest {
        @NotBlank
        @Size(max = 120)
        private String fullName;

        @Email
        @Size(max = 100)
        private String email;

        @Size(max = 20)
        private String phoneNumber;

        @Past
        private LocalDate dateOfBirth;

        @Size(max = 20)
        private String gender;
    }

    @Transactional
    public Map<String, Object> register(WalkInPatientRequest request) {
        String email = blankToNull(request.getEmail());
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            userRepository.findByEmail(email).ifPresent(existing -> {
                throw duplicate(existing);
            });
        }
        String phone = blankToNull(request.getPhoneNumber());
        if (phone != null) {
            userRepository.findFirstByPhoneNumberAndRole(phone, Role.PATIENT).ifPresent(existing -> {
                throw duplicate(existing);
            });
        }

        boolean placeholder = email == null;
        String loginEmail = placeholder
            ? "walkin." + UUID.randomUUID() + "@" + PLACEHOLDER_DOMAIN
            : email;

        // Random password, stored only as a hash. Nobody ever learns it, and walk-in accounts cannot log in anyway.
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String hash = passwordEncoder.encode(Base64.getEncoder().encodeToString(secret));

        User user;
        try {
            user = userRepository.saveAndFlush(User.builder()
                .email(loginEmail)
                .password(hash)
                .fullName(request.getFullName().trim())
                .phoneNumber(phone)
                .role(Role.PATIENT)
                .active(true)
                .walkIn(true)
                .build());
        } catch (DataIntegrityViolationException e) {
            // Two reception desks registered the same person at the same moment.
            throw new ConflictException("This patient was just registered. Search for them and select the existing record.");
        }

        Patient patient = patientRepository.save(Patient.builder()
            .user(user)
            .dateOfBirth(request.getDateOfBirth())
            .gender(blankToNull(request.getGender()))
            .build());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", patient.getId());
        result.put("fullName", user.getFullName());
        result.put("phone", phone == null ? "" : phone);
        // The placeholder address is internal only, so do not show it to staff as if it were real.
        result.put("email", placeholder ? "" : loginEmail);
        return result;
    }

    private ConflictException duplicate(User existing) {
        if (existing.getRole() == Role.PATIENT) {
            Long patientId = patientRepository.findByUserId(existing.getId()).map(Patient::getId).orElse(null);
            if (patientId != null) {
                return new ConflictException("A patient with this email or mobile already exists (patient ID "
                    + patientId + "). Search for them and select the existing record.");
            }
        }
        return new ConflictException("An account with this email or mobile already exists.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
