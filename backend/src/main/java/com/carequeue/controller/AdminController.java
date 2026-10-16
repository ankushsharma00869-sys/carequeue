package com.carequeue.controller;

import com.carequeue.dto.AuthDtos.RegisterRequest;
import com.carequeue.dto.AdminFacilityDtos.CreateDepartmentRequest;
import com.carequeue.dto.AdminFacilityDtos.CreateHospitalRequest;
import com.carequeue.dto.AuthDtos.UserProfileResponse;
import com.carequeue.dto.HospitalDoctorDtos.*;
import com.carequeue.entity.AuditLog;
import com.carequeue.entity.Patient;
import com.carequeue.entity.Role;
import com.carequeue.entity.User;
import com.carequeue.exception.BadRequestException;
import com.carequeue.repository.AuditLogRepository;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import com.carequeue.service.AdminFacilityService;
import com.carequeue.service.AdminStaffService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.carequeue.dto.HospitalDoctorDtos.DoctorScheduleDto;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminStaffService adminStaffService;
    private final AdminFacilityService adminFacilityService;

    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<AuditLog>> getAuditLogs(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(auditLogRepository.findAllByOrderByTimestampDesc(PageRequest.of(page, size)));
    }

    /**
     * The complete "Add Doctor" flow: creates the login (User), the Doctor profile
     * (hospital, department, license, specialization, room, fee), and optionally an
     * initial weekly schedule, all in one transaction (see AdminStaffService). Prefer
     * this over POST /api/admin/staff for doctors — that endpoint only ever created the
     * login row and could leave a DOCTOR user with no matching Doctor profile.
     */
    @PostMapping("/doctors")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DoctorDto> createDoctor(@Valid @RequestBody CreateDoctorRequest request) {
        return ResponseEntity.ok(adminStaffService.createDoctor(request));
    }

    /** Creates a hospital. Patients see it in the booking wizard immediately. */
    @PostMapping("/hospitals")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createHospital(@Valid @RequestBody CreateHospitalRequest request) {
        var hospital = adminFacilityService.createHospital(request);
        return ResponseEntity.ok(Map.of("id", hospital.getId(), "name", hospital.getName(), "code", hospital.getCode()));
    }

    /** Creates a department inside a hospital. Doctors can then be assigned to it. */
    @PostMapping("/departments")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createDepartment(@Valid @RequestBody CreateDepartmentRequest request) {
        var department = adminFacilityService.createDepartment(request);
        return ResponseEntity.ok(Map.of(
            "id", department.getId(),
            "name", department.getName(),
            "code", department.getCode(),
            "hospitalId", department.getHospital().getId()));
    }

    /** Weekly schedule of one doctor (active rows). */
    @GetMapping("/doctors/{doctorId}/schedule")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<DoctorScheduleDto>> getDoctorSchedule(@PathVariable Long doctorId) {
        return ResponseEntity.ok(adminStaffService.getDoctorSchedule(doctorId));
    }

    /** Replaces the doctor's whole weekly schedule (one row per day). */
    @PutMapping("/doctors/{doctorId}/schedule")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<DoctorScheduleDto>> replaceDoctorSchedule(@PathVariable Long doctorId,
                                                                          @RequestBody List<DoctorScheduleDto> schedules) {
        return ResponseEntity.ok(adminStaffService.replaceDoctorSchedule(doctorId, schedules));
    }

    @PatchMapping("/doctors/{doctorId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setDoctorStatus(
        @PathVariable Long doctorId,
        @Valid @RequestBody DoctorStatusUpdateRequest request
    ) {
        adminStaffService.setDoctorActive(doctorId, request.getActive());
        return ResponseEntity.noContent().build();
    }

    /** Creates a Receptionist login and assigns them to a hospital in one step. */
    @PostMapping("/receptionists")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminStaffService.UserSummary> createReceptionist(
        @Valid @RequestBody CreateReceptionistRequest request
    ) {
        return ResponseEntity.ok(adminStaffService.createReceptionist(request));
    }

    /**
     * The ONLY way to create a Doctor / Receptionist / Admin account.
     * Public self-registration (POST /api/auth/register) always creates a PATIENT and
     * ignores any role the client sends, precisely so this is the single, audited,
     * admin-only path for provisioning staff.
     *
     * Kept for ADMIN and generic-account creation; use POST /api/admin/doctors for
     * DOCTOR accounts and POST /api/admin/receptionists for RECEPTIONIST accounts, since
     * those roles need more than a bare login row to actually function.
     */
    @PostMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserProfileResponse> createStaffAccount(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("An account with this email already exists.");
        }

        Role assignedRole = request.getRole() != null ? request.getRole() : Role.PATIENT;
        if (assignedRole == Role.DOCTOR) {
            throw new BadRequestException(
                "Use POST /api/admin/doctors to create a Doctor account — it also requires " +
                "a hospital, department, license number and specialization for the Doctor profile."
            );
        }

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

        UserProfileResponse response = UserProfileResponse.builder()
            .id(user.getId())
            .email(user.getEmail())
            .fullName(user.getFullName())
            .phoneNumber(user.getPhoneNumber())
            .role(user.getRole())
            .patientId(patientId)
            .build();

        return ResponseEntity.ok(response);
    }
}
