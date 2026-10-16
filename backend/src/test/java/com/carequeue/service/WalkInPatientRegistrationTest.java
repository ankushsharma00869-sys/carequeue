package com.carequeue.service;

import com.carequeue.entity.Patient;
import com.carequeue.entity.User;
import com.carequeue.exception.ConflictException;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import com.carequeue.security.CustomUserDetailsService;
import com.carequeue.service.WalkInPatientService.WalkInPatientRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class WalkInPatientRegistrationTest {

    @Autowired private WalkInPatientService walkIns;
    @Autowired private CustomUserDetailsService userDetails;
    @Autowired private UserRepository users;
    @Autowired private PatientRepository patients;

    private static WalkInPatientRequest request(String name, String email, String phone) {
        return WalkInPatientRequest.builder().fullName(name).email(email).phoneNumber(phone).build();
    }

    @Test
    void walkInWithoutEmailGetsPlaceholderAndCannotLogIn() {
        Map<String, Object> created = walkIns.register(request("Walk In " + UUID.randomUUID(), null, null));
        Patient patient = patients.findById(((Number) created.get("id")).longValue()).orElseThrow();
        User user = patient.getUser();

        assertTrue(user.isWalkIn());
        assertTrue(user.getEmail().endsWith("@walkin.carequeue.local"));
        assertFalse(userDetails.loadUserByUsername(user.getEmail()).isEnabled(),
            "walk-in accounts must not be able to log in");
    }

    @Test
    void duplicateEmailIsRefusedAndNoSecondRecordIsCreated() {
        String email = "walkin.dup." + UUID.randomUUID() + "@example.com";
        walkIns.register(request("First " + UUID.randomUUID(), email, null));
        long before = users.count();

        assertThrows(ConflictException.class,
            () -> walkIns.register(request("Second", email.toUpperCase(), null)));
        assertEquals(before, users.count());
    }

    @Test
    void duplicateMobileIsRefused() {
        String phone = "9" + (100000000L + Math.abs(UUID.randomUUID().hashCode()) % 899999999L);
        walkIns.register(request("Mobile One " + UUID.randomUUID(), null, phone));

        assertThrows(ConflictException.class,
            () -> walkIns.register(request("Mobile Two", null, phone)));
    }
}
