package com.carequeue.config;

import com.carequeue.entity.*;
import com.carequeue.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final DoctorRepository doctorRepository;
    private final DoctorScheduleRepository doctorScheduleRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final EmergencyRequestRepository emergencyRequestRepository;
    private final PasswordEncoder passwordEncoder;

    // No hardcoded production password: if the operator sets BOOTSTRAP_ADMIN_PASSWORD,
    // that's used; otherwise a random one is generated and logged once at startup
    // (the same "generated initial admin password" pattern used by Jenkins, GitLab, etc.).
    @Value("${BOOTSTRAP_ADMIN_PASSWORD:}")
    private String bootstrapAdminPassword;

    /**
     * true (dev): seed hospitals, departments, demo staff/patients/appointments with well-known demo
     * passwords. false (prod): seed only the structure and the admin account, so no demo credential
     * ever exists in production.
     */
    @Value("${carequeue.seed.demo-data:true}")
    private boolean demoData;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Database already seeded. Skipping initialization.");
            return;
        }

        log.info("Initializing realistic CareQueue clinical dataset...");

        // 1. Create Hospitals
        Hospital cityCare = hospitalRepository.save(Hospital.builder()
            .name("CityCare Hospital")
            .code("CQ-CCH")
            .address("450 Medical Boulevard, Healthcare District")
            .city("Metro City")
            .phone("+1 (555) 019-2831")
            .emergencyContact("+1 (555) 911-0001")
            .active(true)
            .build());

        Hospital sunrise = hospitalRepository.save(Hospital.builder()
            .name("Sunrise Medical Center")
            .code("CQ-SMC")
            .address("880 Aurora Parkway")
            .city("Metro City")
            .phone("+1 (555) 018-9922")
            .emergencyContact("+1 (555) 911-0002")
            .active(true)
            .build());

        // 2. Create Departments for CityCare
        Department genMed = departmentRepository.save(Department.builder()
            .hospital(cityCare).name("General Medicine").code("GEN").floorNumber("1st Floor").icon("Activity").active(true).build());
        Department cardio = departmentRepository.save(Department.builder()
            .hospital(cityCare).name("Cardiology").code("CARD").floorNumber("3rd Floor").icon("HeartPulse").active(true).build());
        Department ortho = departmentRepository.save(Department.builder()
            .hospital(cityCare).name("Orthopedics").code("ORTHO").floorNumber("2nd Floor").icon("Bone").active(true).build());
        Department peds = departmentRepository.save(Department.builder()
            .hospital(cityCare).name("Pediatrics").code("PEDS").floorNumber("2nd Floor").icon("Baby").active(true).build());
        Department derm = departmentRepository.save(Department.builder()
            .hospital(cityCare).name("Dermatology").code("DERM").floorNumber("4th Floor").icon("Sparkles").active(true).build());
        Department ent = departmentRepository.save(Department.builder()
            .hospital(cityCare).name("ENT (Ear, Nose & Throat)").code("ENT").floorNumber("4th Floor").icon("Ear").active(true).build());

        // 3. Create Demo System Users
        boolean adminPasswordProvided = bootstrapAdminPassword != null && !bootstrapAdminPassword.isBlank();
        if (!demoData && !adminPasswordProvided) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must be set before the first production start.");
        }
        String adminPassword = adminPasswordProvided ? bootstrapAdminPassword : generateSecurePassword();

        User adminUser = userRepository.save(User.builder()
            .email("admin@carequeue.com")
            .password(passwordEncoder.encode(adminPassword))
            .fullName("Dr. Alistair Vance (Director)")
            .phoneNumber("+1 (555) 019-1000")
            .role(Role.ADMIN)
            .active(true)
            .build());

        if (!adminPasswordProvided) {
            // The generated password is deliberately NOT logged: log files are read by more people
            // than should hold admin credentials. Reset it via the admin tooling, or set
            // BOOTSTRAP_ADMIN_PASSWORD before the first start.
            log.warn("No BOOTSTRAP_ADMIN_PASSWORD was set: a random admin password was generated and is not shown. "
                + "Set BOOTSTRAP_ADMIN_PASSWORD before the first start to choose it.");
        }

        if (!demoData) {
            // Production: structure and admin only. Doctors, receptionists and patients are created
            // through the admin endpoints, with the operator's own passwords.
            log.info("Production seed complete: hospitals, departments and admin account.");
            return;
        }

        User receptionUser = userRepository.save(User.builder()
            .email("reception@carequeue.com")
            .password(passwordEncoder.encode("Reception@123"))
            .fullName("CityCare Reception Desk")
            .phoneNumber("+1 (555) 019-1001")
            .role(Role.RECEPTIONIST)
            .active(true)
            .build());

        // 4. Create Doctor Users & Profiles
        User docSmithUser = userRepository.save(User.builder()
            .email("doctor.smith@carequeue.com")
            .password(passwordEncoder.encode("Doctor@123"))
            .fullName("Dr. Robert Smith, MD")
            .phoneNumber("+1 (555) 019-2001")
            .role(Role.DOCTOR)
            .active(true)
            .build());

        Doctor docSmith = doctorRepository.save(Doctor.builder()
            .user(docSmithUser)
            .hospital(cityCare)
            .department(genMed)
            .licenseNumber("MD-88421")
            .specialization("Internal Medicine & Diagnostics")
            .roomNumber("Room 104")
            .consultationFee(new BigDecimal("120.00"))
            .bio("Board certified internist with 14 years of hospital clinical operations experience.")
            .averageConsultationMinutes(15)
            .active(true)
            .build());

        User docCardioUser = userRepository.save(User.builder()
            .email("doctor.jenkins@carequeue.com")
            .password(passwordEncoder.encode("Doctor@123"))
            .fullName("Dr. Sarah Jenkins, FACC")
            .phoneNumber("+1 (555) 019-2002")
            .role(Role.DOCTOR)
            .active(true)
            .build());

        Doctor docCardio = doctorRepository.save(Doctor.builder()
            .user(docCardioUser)
            .hospital(cityCare)
            .department(cardio)
            .licenseNumber("MD-99124")
            .specialization("Interventional Cardiology")
            .roomNumber("Room 302")
            .consultationFee(new BigDecimal("220.00"))
            .bio("Specialist in ischemic heart conditions, rhythm management, and acute cardiac care.")
            .averageConsultationMinutes(20)
            .active(true)
            .build());

        User docOrthoUser = userRepository.save(User.builder()
            .email("doctor.rostova@carequeue.com")
            .password(passwordEncoder.encode("Doctor@123"))
            .fullName("Dr. Elena Rostova, MS")
            .phoneNumber("+1 (555) 019-2003")
            .role(Role.DOCTOR)
            .active(true)
            .build());

        Doctor docOrtho = doctorRepository.save(Doctor.builder()
            .user(docOrthoUser)
            .hospital(cityCare)
            .department(ortho)
            .licenseNumber("MD-77190")
            .specialization("Orthopedic Trauma & Joint Reconstruction")
            .roomNumber("Room 210")
            .consultationFee(new BigDecimal("180.00"))
            .bio("Sub-specialized in arthroscopy, fracture repair, and degenerative joint therapies.")
            .averageConsultationMinutes(15)
            .active(true)
            .build());

        // 5. Create Schedules for Doctors
        for (DayOfWeek day : DayOfWeek.values()) {
            doctorScheduleRepository.save(DoctorSchedule.builder()
                .doctor(docSmith)
                .dayOfWeek(day)
                .startTime(LocalTime.of(8, 30))
                .endTime(LocalTime.of(17, 30))
                .maxPatients(50)
                .slotDurationMinutes(15)
                .active(true)
                .build());

            doctorScheduleRepository.save(DoctorSchedule.builder()
                .doctor(docCardio)
                .dayOfWeek(day)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(16, 0))
                .maxPatients(35)
                .slotDurationMinutes(20)
                .active(true)
                .build());

            doctorScheduleRepository.save(DoctorSchedule.builder()
                .doctor(docOrtho)
                .dayOfWeek(day)
                .startTime(LocalTime.of(8, 30))
                .endTime(LocalTime.of(17, 0))
                .maxPatients(40)
                .slotDurationMinutes(15)
                .active(true)
                .build());
        }

        // 6. Create Demo Primary Patient: John Doe (A-027)
        User patientUser = userRepository.save(User.builder()
            .email("patient@carequeue.com")
            .password(passwordEncoder.encode("Patient@123"))
            .fullName("John Doe")
            .phoneNumber("+1 (555) 441-2099")
            .role(Role.PATIENT)
            .active(true)
            .build());

        Patient primaryPatient = patientRepository.save(Patient.builder()
            .user(patientUser)
            .dateOfBirth(LocalDate.of(1989, 5, 14))
            .gender("Male")
            .bloodGroup("O+")
            .emergencyContactName("Mary Doe")
            .emergencyContactPhone("+1 (555) 441-9988")
            .medicalHistorySummary("No chronic conditions. Occasional seasonal allergies.")
            .build());

        // 7. Create Additional Realistic Queue Patients
        String[][] additionalPatients = {
            {"Arthur Pendelton", "arthur.p@example.com", "A-019", "COMPLETED"},
            {"Beatrice Miller", "beatrice.m@example.com", "A-020", "COMPLETED"},
            {"Carlos Gomez", "carlos.g@example.com", "A-021", "IN_CONSULTATION"},
            {"David Kim", "david.k@example.com", "A-022", "WAITING"},
            {"Evelyn Taylor", "evelyn.t@example.com", "A-023", "WAITING"},
            {"Franklin Moore", "franklin.m@example.com", "A-024", "WAITING"}
        };

        LocalDate today = LocalDate.now();

        int tokenNum = 19;
        for (String[] pData : additionalPatients) {
            String name = pData[0];
            String email = pData[1];
            String token = pData[2];
            String statusStr = pData[3];

            User u = userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode("Secret@123"))
                .fullName(name)
                .phoneNumber("+1 (555) 300-" + tokenNum)
                .role(Role.PATIENT)
                .active(true)
                .build());

            Patient p = patientRepository.save(Patient.builder()
                .user(u)
                .gender(tokenNum % 2 == 0 ? "Female" : "Male")
                .bloodGroup("A+")
                .build());

            AppointmentStatus apptStatus = "COMPLETED".equals(statusStr) ? AppointmentStatus.COMPLETED :
                "IN_CONSULTATION".equals(statusStr) ? AppointmentStatus.IN_CONSULTATION : AppointmentStatus.IN_QUEUE;

            LocalTime apptTime = LocalTime.of(9, 0).plusMinutes((tokenNum - 19) * 15L);

            Appointment appt = appointmentRepository.save(Appointment.builder()
                .appointmentNumber(String.format("CQ-2026-%05d", 10000 + tokenNum))
                .patient(p)
                .doctor(docSmith)
                .hospital(cityCare)
                .department(genMed)
                .appointmentDate(today)
                .appointmentTime(apptTime)
                .status(apptStatus)
                .tokenFormatted(token)
                .patientNotes("Routine consultation and follow-up")
                .build());

            QueueStatus qStatus = QueueStatus.valueOf(statusStr);
            LocalDateTime calledTime = (qStatus == QueueStatus.IN_CONSULTATION || qStatus == QueueStatus.COMPLETED) ?
                LocalDateTime.now().minusMinutes(20) : null;
            LocalDateTime startedTime = (qStatus == QueueStatus.IN_CONSULTATION || qStatus == QueueStatus.COMPLETED) ?
                LocalDateTime.now().minusMinutes(15) : null;
            LocalDateTime completedTime = (qStatus == QueueStatus.COMPLETED) ? LocalDateTime.now().minusMinutes(5) : null;

            queueEntryRepository.save(QueueEntry.builder()
                .appointment(appt)
                .doctor(docSmith)
                .patient(p)
                .scheduleDate(today)
                .tokenNumber(tokenNum)
                .tokenFormatted(token)
                .priority(QueuePriority.NORMAL)
                .priorityRank(QueuePriority.NORMAL.getRank())
                .status(qStatus)
                .calledAt(calledTime)
                .startedAt(startedTime)
                .completedAt(completedTime)
                .estimatedWaitMinutes((tokenNum - 21) > 0 ? (tokenNum - 21) * 15 : 0)
                .build());

            tokenNum++;
        }

        // Fill in token 25, 26 as dummy waiting
        for (int t = 25; t <= 26; t++) {
            User u = userRepository.save(User.builder()
                .email("patient" + t + "@example.com")
                .password(passwordEncoder.encode("Secret@123"))
                .fullName("Patient #" + t)
                .role(Role.PATIENT)
                .active(true)
                .build());
            Patient p = patientRepository.save(Patient.builder().user(u).build());
            Appointment appt = appointmentRepository.save(Appointment.builder()
                .appointmentNumber(String.format("CQ-2026-%05d", 10000 + t))
                .patient(p).doctor(docSmith).hospital(cityCare).department(genMed)
                .appointmentDate(today).appointmentTime(LocalTime.of(10, 30).plusMinutes((t - 25) * 15L))
                .status(AppointmentStatus.IN_QUEUE).tokenFormatted(String.format("A-%03d", t)).build());
            queueEntryRepository.save(QueueEntry.builder()
                .appointment(appt).doctor(docSmith).patient(p).scheduleDate(today)
                .tokenNumber(t).tokenFormatted(String.format("A-%03d", t))
                .priority(QueuePriority.NORMAL).priorityRank(QueuePriority.NORMAL.getRank())
                .status(QueueStatus.WAITING).estimatedWaitMinutes((t - 21) * 15).build());
        }

        // 8. Create Demo Patient's Appointment: Token A-027
        Appointment patientAppt = appointmentRepository.save(Appointment.builder()
            .appointmentNumber("CQ-2026-10482")
            .patient(primaryPatient)
            .doctor(docSmith)
            .hospital(cityCare)
            .department(genMed)
            .appointmentDate(today)
            .appointmentTime(LocalTime.of(11, 0))
            .status(AppointmentStatus.IN_QUEUE)
            .tokenFormatted("A-027")
            .patientNotes("Persistent cough and seasonal allergies check-up.")
            .build());

        queueEntryRepository.save(QueueEntry.builder()
            .appointment(patientAppt)
            .doctor(docSmith)
            .patient(primaryPatient)
            .scheduleDate(today)
            .tokenNumber(27)
            .tokenFormatted("A-027")
            .priority(QueuePriority.NORMAL)
            .priorityRank(QueuePriority.NORMAL.getRank())
            .status(QueueStatus.WAITING)
            .estimatedWaitMinutes(35)
            .build());

        // 9. Create 1 Realistic Emergency Request
        emergencyRequestRepository.save(EmergencyRequest.builder()
            .hospital(cityCare)
            .department(cardio)
            .doctor(docCardio)
            .patientName("Victor Stone")
            .patientPhone("+1 (555) 911-4432")
            .triageCondition("Acute substernal chest pressure, diaphoresis, ST-elevation suspected")
            .severity(EmergencySeverity.CRITICAL)
            .tokenFormatted("E-001")
            .status("ADMITTED")
            .build());

        log.info("CareQueue dataset initialized successfully with 4 realistic roles, doctors, schedules, and live queue!");
    }

    private String generateSecurePassword() {
        // UUID.randomUUID() is backed by SecureRandom — plenty strong for a one-time
        // bootstrap password that's shown once and should be changed after first login.
        return UUID.randomUUID().toString().replace("-", "");
    }
}
