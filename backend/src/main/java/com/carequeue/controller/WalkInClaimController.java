package com.carequeue.controller;

import com.carequeue.service.WalkInClaimService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
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

import java.time.LocalDate;
import java.util.Map;

/** Activating a reception-created walk-in record as an online account. Public, generic responses. */
@RestController
@RequestMapping("/api/otp/claim-walkin")
@RequiredArgsConstructor
public class WalkInClaimController {

    private final WalkInClaimService claimService;

    @Getter
    @Setter
    public static class ClaimRequest {
        @NotBlank
        @Email
        @Size(max = 100)
        private String email;

        @Size(max = 20)
        private String mobile;

        @NotNull
        @Past
        private LocalDate dateOfBirth;
    }

    @Getter
    @Setter
    public static class ActivateRequest {
        @NotBlank
        @Email
        @Size(max = 100)
        private String email;

        @Size(max = 20)
        private String mobile;

        @NotNull
        @Past
        private LocalDate dateOfBirth;

        @NotBlank
        @Pattern(regexp = "\\d{6}")
        private String code;

        @NotBlank
        @Size(min = 8, max = 100)
        private String newPassword;
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, String>> request(@Valid @RequestBody ClaimRequest body) {
        try {
            claimService.requestCode(body.getEmail(), body.getMobile(), body.getDateOfBirth());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "We could not send the verification email right now. Please try again later."));
        }
        return ResponseEntity.accepted()
            .body(Map.of("message", "If a matching record exists, a verification code has been sent to that email address."));
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate(@Valid @RequestBody ActivateRequest body) {
        claimService.activate(body.getEmail(), body.getMobile(), body.getDateOfBirth(), body.getCode(), body.getNewPassword());
        return ResponseEntity.ok(Map.of(
            "activated", true,
            "message", "Your account is active. You can now log in with your email and new password."));
    }
}
