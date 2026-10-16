package com.carequeue.security;

import com.carequeue.dto.EmergencyDtos.EmergencyRequestResponse;
import com.carequeue.entity.Doctor;
import com.carequeue.entity.EmergencyRequest;
import com.carequeue.entity.EmergencySeverity;
import com.carequeue.entity.Role;
import com.carequeue.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No Spring context and no database: these fail or pass based only on how the classes serialize.
 * The hash below is a realistic BCrypt-format string used only to detect leakage.
 */
class CredentialSerializationTest {

    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private User user() {
        return User.builder()
            .email("doc@test.local").password(HASH).fullName("Dr Serialize")
            .phoneNumber("9000000000").role(Role.DOCTOR).active(true).build();
    }

    @Test
    @DisplayName("User entity serializes without its password field or hash")
    void userSerializationOmitsPassword() throws Exception {
        String json = mapper.writeValueAsString(user());

        assertFalse(json.contains("\"password\""), "password key must not be serialized: " + json);
        assertFalse(json.contains(HASH), "password hash must not be serialized: " + json);
        assertTrue(json.contains("doc@test.local"), "non-secret fields should still serialize");
    }

    @Test
    @DisplayName("Doctor -> User object graph does not leak the password hash")
    void doctorGraphOmitsPassword() throws Exception {
        Doctor doctor = Doctor.builder().user(user()).licenseNumber("LIC-1").specialization("Testing").build();

        String json = mapper.writeValueAsString(doctor);

        assertFalse(json.contains(HASH), "hash leaked through Doctor.user: " + json);
        assertFalse(json.contains("\"password\""), "password key leaked through Doctor.user: " + json);
    }

    @Test
    @DisplayName("EmergencyRequestResponse built from an entity with a doctor graph contains no credentials")
    void emergencyDtoOmitsCredentials() throws Exception {
        Doctor doctor = Doctor.builder().user(user()).licenseNumber("LIC-2").specialization("Testing").build();
        EmergencyRequest e = EmergencyRequest.builder()
            .patientName("Walk In").patientPhone("9111111111").triageCondition("chest pain")
            .severity(EmergencySeverity.CRITICAL).tokenFormatted("E-001").doctor(doctor).build();

        String json = mapper.writeValueAsString(EmergencyRequestResponse.from(e));

        assertFalse(json.contains(HASH), "hash leaked through emergency DTO: " + json);
        assertFalse(json.contains("\"password\""), "password key in emergency DTO: " + json);
        assertFalse(json.contains("9111111111"), "patient phone must not be in the emergency list DTO: " + json);
        assertTrue(json.contains("Dr Serialize"), "doctor name should still be shown");
    }
}
