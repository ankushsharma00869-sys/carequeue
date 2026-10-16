package com.carequeue.controller;

import com.carequeue.service.EmailOtpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;

import com.carequeue.dto.AuthDtos.*;
import com.carequeue.entity.Patient;
import com.carequeue.entity.Role;
import com.carequeue.entity.User;
import com.carequeue.exception.BadRequestException;
import com.carequeue.repository.DoctorRepository;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import com.carequeue.security.JwtTokenProvider;
import com.carequeue.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = tokenProvider.generateToken(authentication);

        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        User user = userRepository.findByEmail(principal.getUsername()).orElseThrow();

        Long doctorId = doctorRepository.findByUserId(user.getId()).map(d -> d.getId()).orElse(null);
        Long patientId = patientRepository.findByUserId(user.getId()).map(p -> p.getId()).orElse(null);

        AuthResponse response = AuthResponse.builder()
            .token(jwt)
            .tokenType("Bearer")
            .userId(user.getId())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .role(user.getRole())
            .doctorId(doctorId)
            .patientId(patientId)
            .build();

        return ResponseEntity.ok(response);
    }

    // When true, online signup needs an email code verified within the last 15 minutes.
    // Turn on (CAREQUEUE_SIGNUP_REQUIRE_EMAIL_OTP=true) only after the signup OTP screen is deployed.
    @Value("${carequeue.signup.require-email-otp:false}")
    private boolean signupOtpRequired;

    @Autowired
    private EmailOtpService emailOtpService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        if (signupOtpRequired && !emailOtpService.wasRecentlyVerified(
                request.getEmail(), EmailOtpService.PURPOSE_SIGNUP, Duration.ofMinutes(15))) {
            throw new BadRequestException("Please verify your email address with the code we sent you before signing up.");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("An account with this email already exists.");
        }

        // SECURITY: this endpoint is public (permitAll) so it must NEVER trust a client-supplied
        // role. Self-service sign-up only ever creates PATIENT accounts. Staff accounts (Doctor /
        // Receptionist / Admin) can only be created by an authenticated Admin via
        // POST /api/admin/staff.
        Role assignedRole = Role.PATIENT;

        User user = User.builder()
            .fullName(request.getFullName())
            .email(request.getEmail())
            .password(passwordEncoder.encode(request.getPassword()))
            .phoneNumber(request.getPhoneNumber())
            .role(assignedRole)
            .active(true)
            .build();

        user = userRepository.save(user);

        Long patientId = null;
        if (assignedRole == Role.PATIENT) {
            Patient patient = Patient.builder()
                .user(user)
                .gender(request.getGender())
                .bloodGroup(request.getBloodGroup())
                .build();
            patient = patientRepository.save(patient);
            patientId = patient.getId();
        }

        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        String jwt = tokenProvider.generateToken(authentication);

        AuthResponse response = AuthResponse.builder()
            .token(jwt)
            .tokenType("Bearer")
            .userId(user.getId())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .role(user.getRole())
            .patientId(patientId)
            .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        User user = userRepository.findById(principal.getId()).orElseThrow();
        Long doctorId = doctorRepository.findByUserId(user.getId()).map(d -> d.getId()).orElse(null);
        Long patientId = patientRepository.findByUserId(user.getId()).map(p -> p.getId()).orElse(null);

        String deptName = null;
        String hospName = null;
        if (doctorId != null) {
            var doc = doctorRepository.findByIdWithDepartmentAndHospital(doctorId).orElse(null);
            if (doc != null) {
                deptName = doc.getDepartment() != null ? doc.getDepartment().getName() : null;
                hospName = doc.getHospital() != null ? doc.getHospital().getName() : null;
            }
        }

        UserProfileResponse response = UserProfileResponse.builder()
            .id(user.getId())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .phoneNumber(user.getPhoneNumber())
            .role(user.getRole())
            .doctorId(doctorId)
            .patientId(patientId)
            .departmentName(deptName)
            .hospitalName(hospName)
            .build();

        return ResponseEntity.ok(response);
    }
}
