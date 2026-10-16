package com.carequeue.service;

import com.carequeue.entity.Patient;
import com.carequeue.entity.User;
import com.carequeue.exception.BadRequestException;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Lets a walk-in patient turn their reception-created record into an online account.
 *
 * Match rule: the walk-in record must have the same date of birth AND the same email address as the one
 * given. If a mobile number is also given, it must match too. A code is then sent to that email, so the
 * claimant must control it. The patient row, ID, appointments and history are kept.
 * Every failure gives the same generic message.
 */
@Service
@RequiredArgsConstructor
public class WalkInClaimService {

    public static final String PURPOSE_CLAIM = "CLAIM_WALKIN";
    private static final String GENERIC =
        "We could not activate this record. Check the details, or contact reception.";

    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final EmailOtpService emailOtpService;
    private final PasswordEncoder passwordEncoder;

    /** Sends a code only if exactly one unclaimed walk-in matches. Silent otherwise. */
    @Transactional
    public void requestCode(String email, String mobile, LocalDate dob) {
        String normalized = normalizeEmail(email);
        if (findSingleWalkIn(normalized, mobile, dob).isEmpty()) {
            return;
        }
        emailOtpService.requestCode(normalized, PURPOSE_CLAIM);
    }

    /** Checks the match, then the code, then activates the record. The OTP check commits on its own, so wrong codes still count. */
    @Transactional
    public void activate(String rawEmail, String mobile, LocalDate dob, String code, String newPassword) {
        String email = normalizeEmail(rawEmail);
        Patient patient = findSingleWalkIn(email, mobile, dob).orElseThrow(() -> new BadRequestException(GENERIC));
        Long ownUserId = patient.getUser().getId();
        // The walk-in's own email is fine (it is the record). Only a different account using it is a problem.
        boolean takenByOther = userRepository.findByEmail(email)
            .filter(u -> !u.getId().equals(ownUserId))
            .isPresent();
        if (takenByOther) {
            throw new BadRequestException(GENERIC);
        }
        // The OTP service commits its own attempt count, so wrong codes are counted even though we then refuse.
        if (!emailOtpService.verifyCode(email, PURPOSE_CLAIM, code)) {
            throw new BadRequestException(GENERIC);
        }
        try {
            persistActivation(patient.getId(), email, passwordEncoder.encode(newPassword));
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException(GENERIC); // email taken in a race
        }
    }

    @Transactional
    public void persistActivation(Long patientId, String email, String passwordHash) {
        Patient patient = patientRepository.findById(patientId).orElseThrow(() -> new BadRequestException(GENERIC));
        User user = patient.getUser();
        if (!user.isWalkIn()) {
            throw new BadRequestException(GENERIC); // already claimed
        }
        user.setEmail(email);
        user.setPassword(passwordHash);
        user.setWalkIn(false);
        userRepository.saveAndFlush(user);
    }

    private Optional<Patient> findSingleWalkIn(String email, String mobile, LocalDate dob) {
        if (email == null || dob == null) {
            return Optional.empty();
        }
        String phone = mobile == null ? "" : mobile.trim();
        List<Patient> matches = patientRepository.findWalkInsByDob(dob).stream()
            .filter(p -> email.equalsIgnoreCase(p.getUser().getEmail()))
            .filter(p -> phone.isEmpty() || phone.equals(p.getUser().getPhoneNumber()))
            .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private static String normalizeEmail(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }
}
