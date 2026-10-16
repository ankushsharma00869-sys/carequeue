package com.carequeue.service;

import com.carequeue.dto.HospitalDoctorDtos.*;
import com.carequeue.entity.*;
import com.carequeue.exception.BadRequestException;
import com.carequeue.exception.ResourceNotFoundException;
import com.carequeue.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Implements the "complete doctor provisioning flow" and receptionist provisioning flow
 * called for in the audit brief:
 *
 *   Admin -> Add Doctor -> User created -> Doctor profile created -> Hospital assigned
 *   -> Department assigned -> Schedule configured -> Doctor can log in and use their
 *   dashboard immediately.
 *
 * Previously POST /api/admin/staff created ONLY the User login row for a DOCTOR role —
 * there was no code path that also created the matching Doctor entity, so a newly
 * provisioned doctor could log in but every doctor-facing screen (which all key off
 * Doctor.id, not User.id) would be empty/broken until someone manually inserted a Doctor
 * row. This service creates User + Doctor + optional initial DoctorSchedule rows in a
 * single transaction, so that inconsistent state can no longer happen.
 */
@Service
@RequiredArgsConstructor
public class AdminStaffService {

    private final UserRepository userRepository;
    private final DoctorRepository doctorRepository;
    private final DoctorScheduleRepository doctorScheduleRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final HospitalService hospitalService;
    private final AuditLogService auditLogService;

    @Transactional
    public DoctorDto createDoctor(CreateDoctorRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("An account with this email already exists.");
        }
        if (doctorRepository.existsByLicenseNumber(request.getLicenseNumber())) {
            throw new BadRequestException("A doctor with this license number already exists.");
        }

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with id: " + request.getHospitalId()));
        Department department = departmentRepository.findById(request.getDepartmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Department not found with id: " + request.getDepartmentId()));

        User user = User.builder()
            .fullName(request.getFullName())
            .email(request.getEmail())
            .password(passwordEncoder.encode(request.getPassword()))
            .phoneNumber(request.getPhoneNumber())
            .role(Role.DOCTOR)
            .hospital(hospital)
            .active(true)
            .build();
        user = userRepository.save(user);

        Doctor doctor = Doctor.builder()
            .user(user)
            .hospital(hospital)
            .department(department)
            .licenseNumber(request.getLicenseNumber())
            .specialization(request.getSpecialization())
            .roomNumber(request.getRoomNumber())
            .consultationFee(request.getConsultationFee())
            .bio(request.getBio())
            .averageConsultationMinutes(
                request.getAverageConsultationMinutes() != null ? request.getAverageConsultationMinutes() : 15)
            .active(true)
            .build();
        doctor = doctorRepository.save(doctor);

        if (request.getInitialSchedule() != null) {
            Doctor finalDoctor = doctor;
            List<DoctorSchedule> schedules = request.getInitialSchedule().stream()
                .map(s -> DoctorSchedule.builder()
                    .doctor(finalDoctor)
                    .dayOfWeek(s.getDayOfWeek())
                    .startTime(s.getStartTime())
                    .endTime(s.getEndTime())
                    .maxPatients(s.getMaxPatients() != null ? s.getMaxPatients() : 40)
                    .slotDurationMinutes(s.getSlotDurationMinutes() != null ? s.getSlotDurationMinutes() : 15)
                    .active(true)
                    .build())
                .collect(Collectors.toList());
            doctorScheduleRepository.saveAll(schedules);
        }

        auditLogService.record(
            "ADMIN_CREATED_DOCTOR",
            "DOCTOR",
            String.valueOf(doctor.getId()),
            "Created doctor " + user.getFullName() + " (" + user.getEmail() + ") in " + department.getName()
                + " at " + hospital.getName()
        );

        return hospitalService.mapToDoctorDto(doctor);
    }

    @Transactional
    public void setDoctorActive(Long doctorId, boolean active) {
        Doctor doctor = doctorRepository.findById(doctorId)
            .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + doctorId));

        doctor.setActive(active);
        doctorRepository.save(doctor);

        // A deactivated doctor's login should not still work independently of their
        // clinical profile being turned off — otherwise a "deactivated" doctor could
        // still authenticate and hit doctor-only APIs even though their profile says
        // they're inactive.
        User user = doctor.getUser();
        user.setActive(active);
        userRepository.save(user);

        auditLogService.record(
            active ? "ADMIN_ACTIVATED_DOCTOR" : "ADMIN_DEACTIVATED_DOCTOR",
            "DOCTOR",
            String.valueOf(doctorId),
            (active ? "Activated" : "Deactivated") + " doctor " + user.getFullName()
        );
    }

    @Transactional
    public UserSummary createReceptionist(CreateReceptionistRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("An account with this email already exists.");
        }

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with id: " + request.getHospitalId()));

        User user = User.builder()
            .fullName(request.getFullName())
            .email(request.getEmail())
            .password(passwordEncoder.encode(request.getPassword()))
            .phoneNumber(request.getPhoneNumber())
            .role(Role.RECEPTIONIST)
            .hospital(hospital)
            .active(true)
            .build();
        user = userRepository.save(user);

        auditLogService.record(
            "ADMIN_CREATED_RECEPTIONIST",
            "USER",
            String.valueOf(user.getId()),
            "Created receptionist " + user.getFullName() + " (" + user.getEmail() + ") at " + hospital.getName()
        );

        return new UserSummary(user.getId(), user.getFullName(), user.getEmail(), hospital.getName());
    }

    public record UserSummary(Long id, String fullName, String email, String hospitalName) {}

    /** Active weekly schedule of one doctor (what the booking screen uses to show slots). */
    @Transactional(readOnly = true)
    public List<DoctorScheduleDto> getDoctorSchedule(Long doctorId) {
        if (!doctorRepository.existsById(doctorId)) {
            throw new ResourceNotFoundException("Doctor not found with id: " + doctorId);
        }
        return doctorScheduleRepository.findByDoctorIdAndActiveTrue(doctorId).stream()
            .map(s -> DoctorScheduleDto.builder()
                .id(s.getId())
                .doctorId(doctorId)
                .dayOfWeek(s.getDayOfWeek())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .maxPatients(s.getMaxPatients())
                .slotDurationMinutes(s.getSlotDurationMinutes())
                .active(s.isActive())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * Replaces the doctor's whole weekly schedule with the rows sent: one row per day, rows with
     * active=false are ignored, and an empty list makes the doctor off-duty every day.
     */
    @Transactional
    public List<DoctorScheduleDto> replaceDoctorSchedule(Long doctorId, List<DoctorScheduleDto> rows) {
        Doctor doctor = doctorRepository.findById(doctorId)
            .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + doctorId));
        List<DoctorScheduleDto> input = rows == null
            ? java.util.Collections.<DoctorScheduleDto>emptyList()
            : rows;

        java.util.Set<java.time.DayOfWeek> days = new java.util.HashSet<>();
        for (DoctorScheduleDto row : input) {
            if (row.getDayOfWeek() == null) {
                throw new BadRequestException("Every schedule row needs a day of the week.");
            }
            if (!days.add(row.getDayOfWeek())) {
                throw new BadRequestException(row.getDayOfWeek() + " is listed more than once. Use one row per day.");
            }
            if (row.getStartTime() == null || row.getEndTime() == null || !row.getStartTime().isBefore(row.getEndTime())) {
                throw new BadRequestException(row.getDayOfWeek() + ": start time must be before end time.");
            }
            if (row.getSlotDurationMinutes() != null && row.getSlotDurationMinutes() <= 0) {
                throw new BadRequestException(row.getDayOfWeek() + ": slot duration must be positive.");
            }
            if (row.getMaxPatients() != null && row.getMaxPatients() <= 0) {
                throw new BadRequestException(row.getDayOfWeek() + ": max patients must be positive.");
            }
        }

        // Delete first and flush, so the new rows never clash with the old ones.
        doctorScheduleRepository.deleteByDoctorId(doctorId);
        doctorScheduleRepository.flush();

        List<DoctorSchedule> toSave = input.stream()
            .filter(DoctorScheduleDto::isActive)
            .map(r -> DoctorSchedule.builder()
                .doctor(doctor)
                .dayOfWeek(r.getDayOfWeek())
                .startTime(r.getStartTime())
                .endTime(r.getEndTime())
                .maxPatients(r.getMaxPatients() != null ? r.getMaxPatients() : 40)
                .slotDurationMinutes(r.getSlotDurationMinutes() != null ? r.getSlotDurationMinutes() : 15)
                .active(true)
                .build())
            .collect(Collectors.toList());

        return doctorScheduleRepository.saveAll(toSave).stream()
            .map(s -> DoctorScheduleDto.builder()
                .id(s.getId())
                .doctorId(doctorId)
                .dayOfWeek(s.getDayOfWeek())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .maxPatients(s.getMaxPatients())
                .slotDurationMinutes(s.getSlotDurationMinutes())
                .active(s.isActive())
                .build())
            .collect(Collectors.toList());
    }
}
