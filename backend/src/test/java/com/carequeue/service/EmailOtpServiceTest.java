package com.carequeue.service;

import com.carequeue.entity.EmailOtp;
import com.carequeue.repository.EmailOtpRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
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
class EmailOtpServiceTest {

    private static final Pattern CODE = Pattern.compile("code is (\\d{6})\\.");

    @Autowired private EmailOtpService otp;
    @Autowired private CaptureEmailSender mail;
    @Autowired private EmailOtpRepository repo;

    private static String address() {
        return "otp." + UUID.randomUUID() + "@example.com";
    }

    /** The code from the most recent email sent to this address. */
    private String latestCode(String email) {
        List<CaptureEmailSender.Message> sent = mail.messagesTo(email);
        assertFalse(sent.isEmpty(), "no email was sent");
        Matcher m = CODE.matcher(sent.get(sent.size() - 1).body());
        assertTrue(m.find(), "code not found in email");
        return m.group(1);
    }

    private static String wrongCodeFor(String real) {
        return real.equals("000000") ? "111111" : "000000";
    }

    @Test
    void correctCodeVerifiesOnceOnly() {
        String email = address();
        otp.requestCode(email, EmailOtpService.PURPOSE_SIGNUP);
        String code = latestCode(email);

        assertTrue(otp.verifyCode(email, EmailOtpService.PURPOSE_SIGNUP, code));
        assertFalse(otp.verifyCode(email, EmailOtpService.PURPOSE_SIGNUP, code), "a used code must not work again");
    }

    @Test
    void wrongCodeFails() {
        String email = address();
        otp.requestCode(email, EmailOtpService.PURPOSE_SIGNUP);
        String real = latestCode(email);

        assertFalse(otp.verifyCode(email, EmailOtpService.PURPOSE_SIGNUP, wrongCodeFor(real)));
    }

    @Test
    void tooManyWrongAttemptsBlocksEvenTheRealCode() {
        String email = address();
        otp.requestCode(email, EmailOtpService.PURPOSE_SIGNUP);
        String real = latestCode(email);

        for (int i = 0; i < 6; i++) {
            assertFalse(otp.verifyCode(email, EmailOtpService.PURPOSE_SIGNUP, wrongCodeFor(real)));
        }
        assertFalse(otp.verifyCode(email, EmailOtpService.PURPOSE_SIGNUP, real));
    }

    @Test
    void cooldownSendsOnlyOneEmail() {
        String email = address();
        otp.requestCode(email, EmailOtpService.PURPOSE_SIGNUP);
        otp.requestCode(email, EmailOtpService.PURPOSE_SIGNUP);

        assertEquals(1, mail.messagesTo(email).size());
    }

    @Test
    void expiredCodeFails() {
        String email = address();
        otp.requestCode(email, EmailOtpService.PURPOSE_SIGNUP);
        String code = latestCode(email);

        EmailOtp latest = repo.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, EmailOtpService.PURPOSE_SIGNUP).orElseThrow();
        latest.setExpiresAt(latest.getExpiresAt().minus(Duration.ofHours(1)));
        repo.save(latest);

        assertFalse(otp.verifyCode(email, EmailOtpService.PURPOSE_SIGNUP, code));
    }

    @Test
    void placeholderAddressNeverGetsEmail() {
        String placeholder = "walkin." + UUID.randomUUID() + "@walkin.carequeue.local";
        otp.requestCode(placeholder, EmailOtpService.PURPOSE_SIGNUP);

        assertTrue(mail.messagesTo(placeholder).isEmpty());
    }
}
