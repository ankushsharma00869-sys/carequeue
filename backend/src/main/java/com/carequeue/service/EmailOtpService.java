package com.carequeue.service;

import com.carequeue.entity.EmailOtp;
import com.carequeue.repository.EmailOtpRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Email one-time codes. Rules: 6 digits from a CSPRNG, 5 minute expiry, 5 wrong attempts, 60 second
 * cooldown, at most 5 codes per address per hour, one successful use only. Placeholder (.local) addresses
 * never receive mail. Requests are silent when throttled, so the response never reveals anything.
 */
@Service
@RequiredArgsConstructor
public class EmailOtpService {

    public static final String PURPOSE_SIGNUP = "SIGNUP";

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_CODES_PER_HOUR = 5;

    private final EmailOtpRepository otps;
    private final EmailSender sender;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    // Key for the code hash, taken from the app secret, so a database dump alone is not enough to recover codes.
    @Value("${carequeue.otp.key:${JWT_SECRET:}}")
    private String otpKey;

    /** Issues a code and emails it. Throws if the mail provider fails, so no false "sent" is reported. */
    @Transactional
    public void requestCode(String rawEmail, String purpose) {
        String email = normalize(rawEmail);
        if (email == null || email.endsWith(".local")) {
            return; // malformed or placeholder address: never send, and say nothing about it
        }
        LocalDateTime now = LocalDateTime.now(clock);

        Optional<EmailOtp> last = otps.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, purpose);
        if (last.isPresent() && last.get().getCreatedAt().plus(COOLDOWN).isAfter(now)) {
            return; // cooldown: silent
        }
        if (otps.countByEmailAndPurposeAndCreatedAtAfter(email, purpose, now.minusHours(1)) >= MAX_CODES_PER_HOUR) {
            return; // hourly cap: silent
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        otps.save(EmailOtp.builder()
            .email(email)
            .purpose(purpose)
            .codeHash(hash(email, purpose, code))
            .expiresAt(now.plus(TTL))
            .attempts(0)
            .consumed(false)
            .verified(false)
            .createdAt(now)
            .build());

        // If this throws, the transaction rolls back and the stored code is discarded.
        sender.send(email, "Your CareQueue verification code",
            "Your CareQueue verification code is " + code + ". It expires in 5 minutes.\n\n"
            + "If you did not ask for this, you can ignore this email.");
    }

    /** Checks a code. A correct code is used up immediately. Wrong codes count toward the attempt limit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean verifyCode(String rawEmail, String purpose, String code) {
        String email = normalize(rawEmail);
        if (email == null || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<EmailOtp> found = otps.findFirstByEmailAndPurposeAndConsumedFalseOrderByCreatedAtDesc(email, purpose);
        if (found.isEmpty()) {
            return false;
        }
        EmailOtp otp = found.get();
        if (otp.getExpiresAt().isBefore(now)) {
            otp.setConsumed(true);
            return false;
        }
        otp.setAttempts(otp.getAttempts() + 1);
        if (otp.getAttempts() > MAX_ATTEMPTS) {
            otp.setConsumed(true);
            return false;
        }
        boolean matches = MessageDigest.isEqual(
            otp.getCodeHash().getBytes(StandardCharsets.UTF_8),
            hash(email, purpose, code).getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            return false;
        }
        otp.setConsumed(true);
        otp.setVerified(true);
        return true;
    }

    /** True if this address was verified for the purpose within the window. Used by later phases. */
    @Transactional(readOnly = true)
    public boolean wasRecentlyVerified(String rawEmail, String purpose, Duration window) {
        String email = normalize(rawEmail);
        if (email == null) {
            return false;
        }
        return otps.findFirstByEmailAndPurposeAndVerifiedTrueOrderByCreatedAtDesc(email, purpose)
            .map(o -> o.getCreatedAt().plus(window).isAfter(LocalDateTime.now(clock)))
            .orElse(false);
    }

    private String hash(String email, String purpose, String code) {
        if (otpKey == null || otpKey.isBlank()) {
            throw new IllegalStateException("OTP key is not configured (set JWT_SECRET or carequeue.otp.key)");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(otpKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((purpose + "|" + email + "|" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        return email.length() <= 100 && email.contains("@") ? email : null;
    }
}
