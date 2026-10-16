package com.carequeue.service;

import com.carequeue.entity.Patient;
import com.carequeue.exception.BadRequestException;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import com.carequeue.security.CustomUserDetailsService;
import com.carequeue.service.WalkInPatientService.WalkInPatientRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "carequeue.mail.mode=capture",
    "carequeue.otp.key=test-only-otp-key-not-a-secret-0123456789"
})
class WalkInClaimTest {

    private static final Pattern CODE = Pattern.compile("code is (\\d{6})\\.");

    @Autowired private WalkInPatientService walkIns;
    @Autowired private WalkInClaimService claims;
    @Autowired private CaptureEmailSender mail;
    @Autowired private CustomUserDetailsService userDetails;
    @Autowired private UserRepository users;
    @Autowired private PatientRepository patients;

    private static String uniqueEmail() {
        return "claim." + UUID.randomUUID() + "@example.com";
    }

    private static String randomPhone() {
        return "8" + String.format("%09d", Math.abs(UUID.randomUUID().hashCode()) % 1_000_000_000L);
    }

    private Long createWalkIn(String email, String phone, LocalDate dob) {
        Map<String, Object> created = walkIns.register(WalkInPatientRequest.builder()
            .fullName("Walk In " + UUID.randomUUID())
            .email(email)
            .phoneNumber(phone)
            .dateOfBirth(dob)
            .build());
        return ((Number) created.get("id")).longValue();
    }

    private String codeSentTo(String email) {
        var sent = mail.messagesTo(email);
        assertFalse(sent.isEmpty(), "no code was sent");
        Matcher m = CODE.matcher(sent.get(sent.size() - 1).body());
        assertTrue(m.find(), "code not found in email");
        return m.group(1);
    }

    @Test
    void emailAndDobClaimKeepsTheSameRecordAndAllowsLogin() {
        String email = uniqueEmail();
        LocalDate dob = LocalDate.of(1990, 5, 17);
        Long patientId = createWalkIn(email, null, dob);

        claims.requestCode(email, null, dob);
        claims.activate(email, null, dob, codeSentTo(email), "NewPassword#123");

        Patient patient = patients.findById(patientId).orElseThrow();
        assertEquals(patientId, patient.getId(), "patient ID must not change");
        com.carequeue.entity.User user = users.findByEmail(email).orElseThrow();
        assertFalse(user.isWalkIn());
        assertEquals(email, user.getEmail());
        assertTrue(userDetails.loadUserByUsername(email).isEnabled(), "claimed account must be able to log in");
    }

    @Test
    void mobileIsCheckedWhenProvided() {
        String email = uniqueEmail();
        LocalDate dob = LocalDate.of(1990, 5, 17);
        createWalkIn(email, randomPhone(), dob);

        claims.requestCode(email, "9999999999", dob);

        assertTrue(mail.messagesTo(email).isEmpty(), "a wrong mobile must send no code");
    }

    @Test
    void wrongDateOfBirthSendsNoCode() {
        String email = uniqueEmail();
        createWalkIn(email, null, LocalDate.of(1990, 5, 17));

        claims.requestCode(email, null, LocalDate.of(1991, 1, 1));

        assertTrue(mail.messagesTo(email).isEmpty());
    }

    @Test
    void differentEmailThanTheRecordSendsNoCode() {
        String recordEmail = uniqueEmail();
        String otherEmail = uniqueEmail();
        LocalDate dob = LocalDate.of(1990, 5, 17);
        createWalkIn(recordEmail, null, dob);

        claims.requestCode(otherEmail, null, dob);

        assertTrue(mail.messagesTo(otherEmail).isEmpty(), "only the email on the record can claim it");
    }

    @Test
    void wrongCodeIsRefused() {
        String email = uniqueEmail();
        LocalDate dob = LocalDate.of(1990, 5, 17);
        createWalkIn(email, null, dob);
        claims.requestCode(email, null, dob);
        String real = codeSentTo(email);
        String wrong = real.equals("000000") ? "111111" : "000000";

        assertThrows(BadRequestException.class,
            () -> claims.activate(email, null, dob, wrong, "NewPassword#123"));
    }

    @Test
    void claimedRecordCannotBeClaimedAgain() {
        String email = uniqueEmail();
        LocalDate dob = LocalDate.of(1990, 5, 17);
        createWalkIn(email, null, dob);
        claims.requestCode(email, null, dob);
        claims.activate(email, null, dob, codeSentTo(email), "NewPassword#123");

        assertThrows(BadRequestException.class,
            () -> claims.activate(email, null, dob, "123456", "AnotherPassword#1"));
    }
}
