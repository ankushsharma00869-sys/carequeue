package com.carequeue;

import com.carequeue.dto.HospitalDoctorDtos.CreateDoctorRequest;
import com.carequeue.dto.HospitalDoctorDtos.DoctorDto;
import com.carequeue.dto.QueueDtos.EmergencyTriageRequest;
import com.carequeue.entity.*;
import com.carequeue.exception.BadRequestException;
import com.carequeue.repository.*;
import com.carequeue.service.AdminStaffService;
import com.carequeue.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Emergency-queue behaviour against the real service + database. Each test provisions its
 * OWN doctor (via AdminStaffService) so today's queue for that doctor starts empty and
 * cannot be polluted by seed data or other tests. Not @Transactional on purpose: the
 * concurrency tests need real, separately-committed transactions.
 */
@SpringBootTest
@ActiveProfiles("dev")
class EmergencyQueueIntegrationTest {

    @Autowired private QueueService queueService;
    @Autowired private AdminStaffService adminStaffService;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private QueueEntryRepository queueEntryRepository;

    private Doctor doctor;
    private Hospital hospital;
    private Department department;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void isolatedDoctor() {
        hospital = hospitalRepository.findAll().get(0);
        department = departmentRepository.findAll().get(0);
        String id = UUID.randomUUID().toString().substring(0, 8);
        DoctorDto dto = adminStaffService.createDoctor(CreateDoctorRequest.builder()
            .fullName("Dr Test " + id).email("dr." + id + "@test.local").password("secret123")
            .hospitalId(hospital.getId()).departmentId(department.getId())
            .licenseNumber("LIC-" + id).specialization("Testing").build());
        doctor = doctorRepository.findById(dto.getId()).orElseThrow();
    }

    private EmergencyTriageRequest triage(String name, String phone, EmergencySeverity severity) {
        return EmergencyTriageRequest.builder()
            .hospitalId(hospital.getId()).departmentId(department.getId()).doctorId(doctor.getId())
            .patientName(name).patientPhone(phone).triageCondition("test condition").severity(severity).build();
    }

    private Patient anyPatient() {
        return patientRepository.findAll().get(0);
    }

    private List<String> waitingTokensInServeOrder() {
        return queueEntryRepository.findWaitingQueue(doctor.getId(), today).stream()
            .map(QueueEntry::getTokenFormatted).collect(Collectors.toList());
    }

    @Test
    @DisplayName("Emergency triage persists patient + queue entry and issues an E- token (previously threw on transient entities)")
    void triagePersistsEverything() {
        var res = queueService.triageEmergency(triage("Walk In One", "9000000001", EmergencySeverity.CRITICAL));

        assertNotNull(res.getId());
        assertTrue(res.getTokenFormatted().startsWith("E-"));
        assertTrue(res.isEmergency());
        assertTrue(queueEntryRepository.findById(res.getId()).isPresent());
        assertTrue(patientRepository.findById(res.getPatientId()).isPresent());
        // Read the user through its own repository (the patient's user is a lazy proxy and this
        // test runs outside a session). Password must be an encoded value, not the old literal.
        User walkIn = userRepository.findFirstByPhoneNumberAndRole("9000000001", Role.PATIENT).orElseThrow();
        assertNotEquals("EMERGENCY_TEMP_PASS", walkIn.getPassword());
        assertTrue(walkIn.getPassword().startsWith("$2"), "expected a BCrypt hash");
    }

    @Test
    @DisplayName("Serve order: CRITICAL, HIGH, MODERATE emergencies, then normal patients by token, regardless of arrival order")
    void emergencyOrderingBySeverityThenNormal() {
        Patient p = anyPatient();
        queueService.createQueueEntryForAppointment(doctor, p, today, QueuePriority.NORMAL);   // A-001
        queueService.createQueueEntryForAppointment(doctor, p, today, QueuePriority.NORMAL);   // A-002
        queueService.triageEmergency(triage("Moderate", "9000000002", EmergencySeverity.MODERATE)); // E-003
        queueService.triageEmergency(triage("High", "9000000003", EmergencySeverity.HIGH));         // E-004
        queueService.triageEmergency(triage("Critical", "9000000004", EmergencySeverity.CRITICAL)); // E-005

        assertEquals(List.of("E-005", "E-004", "E-003", "A-001", "A-002"), waitingTokensInServeOrder());
    }

    @Test
    @DisplayName("Same severity is served in arrival (token) order")
    void sameSeverityFifo() {
        queueService.triageEmergency(triage("First", "9000000005", EmergencySeverity.CRITICAL));
        queueService.triageEmergency(triage("Second", "9000000006", EmergencySeverity.CRITICAL));
        assertEquals(List.of("E-001", "E-002"), waitingTokensInServeOrder());
    }

    @Test
    @DisplayName("Call next serves the emergency ahead of earlier normal patients, and marks it CALLED")
    void callNextPicksEmergency() {
        queueService.createQueueEntryForAppointment(doctor, anyPatient(), today, QueuePriority.NORMAL);
        var er = queueService.triageEmergency(triage("Urgent", "9000000007", EmergencySeverity.HIGH));

        var overview = queueService.callNextPatient(doctor.getId());

        assertEquals(er.getTokenFormatted(), overview.getCurrentPatient().getTokenFormatted());
        assertEquals(QueueStatus.CALLED, queueEntryRepository.findById(er.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Cannot call another patient while one is already CALLED / in consultation")
    void callNextRejectedWhileActive() {
        queueService.triageEmergency(triage("A", "9000000008", EmergencySeverity.CRITICAL));
        queueService.triageEmergency(triage("B", "9000000009", EmergencySeverity.CRITICAL));
        queueService.callNextPatient(doctor.getId());

        assertThrows(BadRequestException.class, () -> queueService.callNextPatient(doctor.getId()));
    }

    @Test
    @DisplayName("Emergency's estimated wait ignores lower-priority patients; normal patient's wait counts the emergency ahead of it")
    void waitEstimateRespectsPriority() {
        Patient p = anyPatient();
        queueService.createQueueEntryForAppointment(doctor, p, today, QueuePriority.NORMAL);
        queueService.createQueueEntryForAppointment(doctor, p, today, QueuePriority.NORMAL);
        var er = queueService.triageEmergency(triage("Urgent", "9000000010", EmergencySeverity.CRITICAL));
        assertEquals(0, er.getEstimatedWaitMinutes());

        QueueEntry normalAfter = queueService.createQueueEntryForAppointment(doctor, p, today, QueuePriority.NORMAL);
        int avg = doctor.getAverageConsultationMinutes() != null ? doctor.getAverageConsultationMinutes() : 15;
        assertEquals(3 * avg, normalAfter.getEstimatedWaitMinutes()); // 2 earlier normals + the emergency
    }

    @Test
    @DisplayName("Concurrent emergencies for the same doctor get unique tokens and no request fails")
    void concurrentEmergenciesUniqueTokens() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> queueService.triageEmergency(
                triage("Concurrent " + idx, "91000000" + idx, EmergencySeverity.values()[idx % 3])).getTokenFormatted()));
        }
        Set<String> tokens = new HashSet<>();
        for (Future<String> f : futures) {
            tokens.add(f.get(30, TimeUnit.SECONDS)); // surfaces any exception thrown in a worker
        }
        pool.shutdown();

        assertEquals(n, tokens.size(), "duplicate tokens issued: " + tokens);
        assertEquals(n, queueEntryRepository.countByDoctorIdAndScheduleDate(doctor.getId(), today));
    }

    @Test
    @DisplayName("Concurrent call-next: exactly one caller succeeds, exactly one patient ends up CALLED")
    void concurrentCallNextCallsOnlyOne() throws Exception {
        for (int i = 0; i < 3; i++) {
            queueService.triageEmergency(triage("P" + i, "92000000" + i, EmergencySeverity.HIGH));
        }
        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    queueService.callNextPatient(doctor.getId());
                    return true;
                } catch (BadRequestException | com.carequeue.exception.ConflictException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        long succeeded = 0;
        for (Future<Boolean> f : results) {
            if (f.get(30, TimeUnit.SECONDS)) succeeded++;
        }
        pool.shutdown();

        assertEquals(1, succeeded);
        assertEquals(1, queueEntryRepository.countByDoctorIdAndScheduleDateAndStatus(doctor.getId(), today, QueueStatus.CALLED));
    }

    @Test
    @DisplayName("Repeat emergency with the same phone reuses the existing patient instead of creating a new identity")
    void repeatPhoneReusesPatient() {
        var first = queueService.triageEmergency(triage("Same Person", "9000000099", EmergencySeverity.HIGH));
        var second = queueService.triageEmergency(triage("Same Person", "9000000099", EmergencySeverity.CRITICAL));
        assertEquals(first.getPatientId(), second.getPatientId());
    }

    @Test
    @DisplayName("Emergency for an inactive doctor or a mismatched hospital is rejected")
    void invalidDoctorRejected() {
        EmergencyTriageRequest wrongHospital = triage("X", "9000000011", EmergencySeverity.HIGH);
        wrongHospital.setHospitalId(hospital.getId() + 9999);
        assertThrows(BadRequestException.class, () -> queueService.triageEmergency(wrongHospital));

        adminStaffService.setDoctorActive(doctor.getId(), false);
        assertThrows(BadRequestException.class,
            () -> queueService.triageEmergency(triage("Y", "9000000012", EmergencySeverity.HIGH)));
    }
}
