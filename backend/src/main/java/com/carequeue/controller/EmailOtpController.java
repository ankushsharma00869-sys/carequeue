package com.carequeue.controller;

import com.carequeue.exception.BadRequestException;
import com.carequeue.service.EmailOtpService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Email verification codes for signup. Public endpoints, so responses never reveal whether an address exists. */
@RestController
@RequestMapping("/api/otp/email")
@RequiredArgsConstructor
public class EmailOtpController {

    private final EmailOtpService otpService;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EmailOtpController.class);

    @Getter
    @Setter
    public static class RequestCodeBody {
        @NotBlank
        @Email
        @Size(max = 100)
        private String email;
    }

    @Getter
    @Setter
    public static class VerifyCodeBody {
        @NotBlank
        @Email
        @Size(max = 100)
        private String email;

        @NotBlank
        @Pattern(regexp = "\\d{6}")
        private String code;
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, String>> requestCode(@Valid @RequestBody RequestCodeBody body) {
        try {
            otpService.requestCode(body.getEmail(), EmailOtpService.PURPOSE_SIGNUP);
        } catch (RuntimeException e) {
            // Log the exception type and root cause only: never the address, the code, or the password.
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            log.warn("OTP email send failed: {} / root cause: {}", e.getClass().getSimpleName(), root.toString().replaceAll("[^\\s]+@[^\\s]+", "<address>"));
            // Mail provider down or not configured. Say so honestly; do not claim a code was sent.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "We could not send the verification email right now. Please try again later."));
        }
        return ResponseEntity.accepted()
            .body(Map.of("message", "If this address can receive email, a verification code has been sent."));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyCode(@Valid @RequestBody VerifyCodeBody body) {
        boolean ok = otpService.verifyCode(body.getEmail(), EmailOtpService.PURPOSE_SIGNUP, body.getCode());
        if (!ok) {
            throw new BadRequestException("The code is invalid or has expired.");
        }
        return ResponseEntity.ok(Map.of("verified", true));
    }
}
