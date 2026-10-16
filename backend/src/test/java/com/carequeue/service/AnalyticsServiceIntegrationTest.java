package com.carequeue.service;

import com.carequeue.dto.AnalyticsDtos.AnalyticsOverviewResponse;
import com.carequeue.dto.HospitalDoctorDtos.CreateDoctorRequest;
import com.carequeue.dto.HospitalDoctorDtos.DoctorDto;
import com.carequeue.entity.*;
import com.carequeue.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that each analytics metric is computed from real rows, for an explicit date.
 *
 * Test data is created directly through repositories with explicit statuses and dates, so every
 * assertion is deterministic and does not depend on wall-clock time, booking rules, or seed data.
 * The analytics date is always a dedicated far-past or far-future date unique to the test, so
 * counts are not contaminated by DataInitializer seed rows for "today".
 */
@SpringBootTest
@ActiveProfiles("dev")
class AnalyticsServiceIntegrationTest {

    @Autowired private AnalyticsService analyticsService;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private QueueEntryRepository queueEntryRepository;
    @Autowired private EmergencyRequestRepository emergencyRequestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AdminStaffService adminStaffService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Hospital hospital;
    private Department department;
    private Doctor doctor;
    private Patient patient;

    @BeforeEach
    void setUp() {
        hospital = hospitalRepository.findAll().get(0);
        department = departmentRepository.findAll().get(0);
        String id = UUID.randomUUID().toString().substring(0, 8);
        DoctorDto dto = adminStaffService.createDoctor(CreateDoctorRequest.builder()
            .fullName("Dr Analytics " + id).email("dr.an." + id + "@test.local").password("secret123")
            .hospitalId(hospital.getId()).departmentId(department.getId())
            .licenseNumber("LIC-AN-" + id).specialization("Testing").build());
        doctor = doctorRepository.findById(dto.getId()).orElseThrow();

        User u = userRepository.save(User.builder()
            .email("pt.an." + id + "@test.local").password("x").fullName("Analytics Patient " + id)
            .phoneNumber("9" + Math.abs(id.hashCode() % 100000000)).role(Role.PATIENT).active(true).build());
        patient = patientRepository.save(Patient.builder().user(u).build());
    }

    /** A far-future date unique to a test, so no seed or other test data lands on it. */
    private LocalDate freshDate() {
        return LocalDate.of(2090, 1, 1).plusDays(Math.abs(UUID.randomUUID().hashCode() % 100000));
    }


    /**
     * @CreationTimestamp on createdAt overwrites any value the test sets before save, so the
     * timestamps needed for date-scoped assertions are written with a direct UPDATE afterwards.
     */
    private void forceQueueTimestamps(Long id, LocalDateTime createdAt, LocalDateTime startedAt) {
        jdbc.update("UPDATE queue_entries SET created_at = ?, checked_in_at = ?, started_at = ? WHERE id = ?",
            createdAt, createdAt, startedAt, id);
    }

    private void forceEmergencyCreatedAt(Long id, LocalDateTime createdAt) {
        jdbc.update("UPDATE emergency_requests SET created_at = ? WHERE id = ?", createdAt, id);
    }

    private Appointment appt(LocalDate date, LocalTime time, AppointmentStatus status) {
        return appointmentRepository.save(Appointment.builder()
            .appointmentNumber("TST-" + UUID.randomUUID())
            .patient(patient).doctor(doctor).hospital(hospital).department(department)
            .appointmentDate(date).appointmentTime(time).status(status)
            .tokenFormatted("A-001").build());
    }

    // ---------- 1. Empty database ----------

    @Test
    @DisplayName("A date with no records returns zero counts and empty lists, never fabricated numbers")
    void emptyDateReturnsZeros() {
        AnalyticsOverviewResponse r = analyticsService.getHospitalOverview(freshDate());

        assertEquals(0, r.getTotalAppointmentsToday());
        assertEquals(0, r.getCompletedToday());
        assertEquals(0, r.getCancelledToday());
        assertEquals(0, r.getNoShowsToday());
        assertEquals(0, r.getWaitingNow());
        assertEquals(0, r.getEmergencyCasesToday());
        assertEquals(0.0, r.getAverageWaitMinutes(), "no fallback baseline (old code returned 22.5)");
        assertTrue(r.getDepartmentDistribution().isEmpty());
        assertTrue(r.getHourlyCheckIns().isEmpty(), "no zero-padded hours");
        assertNull(r.getDoctorUtilizationPercentage(), "utilisation is not tracked: must be null, never a guessed percentage");
    }

    // ---------- 2–4, 10. Status counts change with real data ----------

    @Test
    @DisplayName("Creating appointments increases the total; statuses are counted separately and correctly")
    void statusCountsReflectRealRows() {
        LocalDate d = freshDate();
        appt(d, LocalTime.of(9, 0), AppointmentStatus.CONFIRMED);
        appt(d, LocalTime.of(9, 15), AppointmentStatus.CONFIRMED);
        appt(d, LocalTime.of(9, 30), AppointmentStatus.COMPLETED);
        appt(d, LocalTime.of(9, 45), AppointmentStatus.CANCELLED);
        appt(d, LocalTime.of(10, 0), AppointmentStatus.NO_SHOW);
        appt(d, LocalTime.of(10, 15), AppointmentStatus.CHECKED_IN);
        appt(d, LocalTime.of(10, 30), AppointmentStatus.IN_QUEUE);

        AnalyticsOverviewResponse r = analyticsService.getHospitalOverview(d);

        assertEquals(7, r.getTotalAppointmentsToday());
        assertEquals(1, r.getCompletedToday());
        assertEquals(1, r.getCancelledToday());
        assertEquals(1, r.getNoShowsToday());
        assertEquals(2, r.getWaitingNow(), "CHECKED_IN + IN_QUEUE");
    }

    @Test
    @DisplayName("Completing an appointment moves it from the confirmed count to the completed count")
    void completingChangesCompletedCount() {
        LocalDate d = freshDate();
        Appointment a = appt(d, LocalTime.of(11, 0), AppointmentStatus.CONFIRMED);
        assertEquals(0, analyticsService.getHospitalOverview(d).getCompletedToday());

        a.setStatus(AppointmentStatus.COMPLETED);
        appointmentRepository.save(a);

        assertEquals(1, analyticsService.getHospitalOverview(d).getCompletedToday());
    }

    @Test
    @DisplayName("Cancelling an appointment changes the cancelled count")
    void cancellingChangesCancelledCount() {
        LocalDate d = freshDate();
        Appointment a = appt(d, LocalTime.of(11, 30), AppointmentStatus.CONFIRMED);
        a.setStatus(AppointmentStatus.CANCELLED);
        appointmentRepository.save(a);

        assertEquals(1, analyticsService.getHospitalOverview(d).getCancelledToday());
    }

    @Test
    @DisplayName("Marking no-show changes the no-show count")
    void noShowChangesNoShowCount() {
        LocalDate d = freshDate();
        Appointment a = appt(d, LocalTime.of(12, 0), AppointmentStatus.CONFIRMED);
        a.setStatus(AppointmentStatus.NO_SHOW);
        appointmentRepository.save(a);

        assertEquals(1, analyticsService.getHospitalOverview(d).getNoShowsToday());
    }

    // ---------- 5–6. Date correctness ----------

    @Test
    @DisplayName("Records on other dates are excluded from the requested date's counts")
    void otherDatesExcluded() {
        LocalDate d = freshDate();
        LocalDate other = d.plusDays(1);
        appt(d, LocalTime.of(9, 0), AppointmentStatus.COMPLETED);
        appt(other, LocalTime.of(9, 0), AppointmentStatus.COMPLETED);
        appt(other.plusDays(1), LocalTime.of(9, 0), AppointmentStatus.CONFIRMED);

        AnalyticsOverviewResponse r = analyticsService.getHospitalOverview(d);

        assertEquals(1, r.getTotalAppointmentsToday(), "future and next-day records must not count toward this date");
        assertEquals(1, r.getCompletedToday());
    }

    @Test
    @DisplayName("Future-dated appointments are not counted as today's appointments")
    void futureAppointmentsNotInToday() {
        LocalDate today = freshDate();
        LocalDate future = today.plusDays(7);
        appt(future, LocalTime.of(9, 0), AppointmentStatus.CONFIRMED);
        appt(future, LocalTime.of(9, 30), AppointmentStatus.CONFIRMED);

        assertEquals(0, analyticsService.getHospitalOverview(today).getTotalAppointmentsToday());
        assertEquals(2, analyticsService.getHospitalOverview(future).getTotalAppointmentsToday());
    }

    // ---------- 7. Emergency statistics ----------

    @Test
    @DisplayName("Emergency count reflects emergency records created on that day, not an all-time total")
    void emergencyCountIsDayScoped() {
        // An emergency created today (wall-clock) and one far in the past.
        EmergencyRequest today = emergencyRequestRepository.save(EmergencyRequest.builder()
            .patientName("Today Case").triageCondition("test").severity(EmergencySeverity.HIGH)
            .tokenFormatted("E-900").doctor(doctor).department(department).hospital(hospital)
            .createdAt(LocalDateTime.now()).build());
        EmergencyRequest old = emergencyRequestRepository.save(EmergencyRequest.builder()
            .patientName("Old Case").triageCondition("test").severity(EmergencySeverity.HIGH)
            .tokenFormatted("E-901").doctor(doctor).department(department).hospital(hospital).build());
        forceEmergencyCreatedAt(old.getId(), LocalDateTime.of(2001, 1, 1, 10, 0));

        AnalyticsOverviewResponse r = analyticsService.getHospitalOverview(LocalDate.now());
        assertTrue(r.getEmergencyCasesToday() >= 1, "today's emergency must be counted");

        AnalyticsOverviewResponse past = analyticsService.getHospitalOverview(LocalDate.of(2001, 1, 1));
        assertEquals(1, past.getEmergencyCasesToday(), "only the emergency created on 2001-01-01 counts for that date");

        emergencyRequestRepository.delete(today);
        emergencyRequestRepository.delete(old);
    }

    // ---------- 8. Queue / wait statistics ----------

    @Test
    @DisplayName("Average wait is the real mean of (startedAt - createdAt) for entries started on that date; zero when none")
    void averageWaitFromRealQueueTimestamps() {
        LocalDate d = freshDate();
        int tokenSeed = 1;
        // Entry A: waited 10 minutes. Entry B: waited 30 minutes. Mean = 20.
        QueueEntry a = queueEntryRepository.save(queueEntry(d, tokenSeed++, null, null));
        forceQueueTimestamps(a.getId(), d.atTime(9, 0), d.atTime(9, 10));
        QueueEntry b = queueEntryRepository.save(queueEntry(d, tokenSeed, null, null));
        forceQueueTimestamps(b.getId(), d.atTime(9, 0), d.atTime(9, 30));

        assertEquals(20.0, analyticsService.getHospitalOverview(d).getAverageWaitMinutes(), 0.01);
        assertEquals(0.0, analyticsService.getHospitalOverview(freshDate()).getAverageWaitMinutes());
    }

    private QueueEntry queueEntry(LocalDate scheduleDate, int token, LocalDateTime createdAt, LocalDateTime startedAt) {
        QueueEntry q = QueueEntry.builder()
            .doctor(doctor).patient(patient).scheduleDate(scheduleDate)
            .tokenNumber(token).tokenFormatted("A-" + String.format("%03d", token))
            .priority(QueuePriority.NORMAL).priorityRank(QueuePriority.NORMAL.getRank())
            .status(QueueStatus.COMPLETED).estimatedWaitMinutes(0)
            .build();
        q.setStartedAt(startedAt); // startedAt is not @CreationTimestamp; createdAt is forced via SQL
        return q;
    }

    @Test
    @DisplayName("Queue state: entries that are still waiting are not counted as completed or as wait-time samples")
    void activeQueueEntriesNotCountedAsStarted() {
        LocalDate d = freshDate();
        QueueEntry waiting = queueEntry(d, 1, null, null);
        waiting.setStatus(QueueStatus.WAITING);
        QueueEntry saved = queueEntryRepository.save(waiting);
        forceQueueTimestamps(saved.getId(), d.atTime(9, 0), null);

        assertEquals(0.0, analyticsService.getHospitalOverview(d).getAverageWaitMinutes(),
            "a waiting entry has no startedAt and must not contribute a wait sample");
    }

    // ---------- 9. Department and doctor breakdowns ----------

    @Test
    @DisplayName("Department distribution and percentages come from actual department counts")
    void departmentDistributionReal() {
        LocalDate d = freshDate();
        appt(d, LocalTime.of(9, 0), AppointmentStatus.CONFIRMED);
        appt(d, LocalTime.of(9, 15), AppointmentStatus.CONFIRMED);
        appt(d, LocalTime.of(9, 30), AppointmentStatus.CONFIRMED);
        appt(d, LocalTime.of(9, 45), AppointmentStatus.CONFIRMED);

        AnalyticsOverviewResponse r = analyticsService.getHospitalOverview(d);

        assertEquals(1, r.getDepartmentDistribution().size());
        assertEquals(4, r.getDepartmentDistribution().get(0).getCount());
        assertEquals(100.0, r.getDepartmentDistribution().get(0).getPercentage(), 0.01);
    }

    @Test
    @DisplayName("Doctor performance lists only doctors with appointments on that date, with real totals")
    void doctorPerformanceReal() {
        LocalDate d = freshDate();
        appt(d, LocalTime.of(9, 0), AppointmentStatus.COMPLETED);
        appt(d, LocalTime.of(9, 15), AppointmentStatus.CONFIRMED);

        AnalyticsOverviewResponse r = analyticsService.getHospitalOverview(d);

        assertEquals(1, r.getDoctorUtilization().size(), "only the doctor with activity that day appears");
        assertEquals(2, r.getDoctorUtilization().get(0).getTotalAssigned());
        assertEquals(1, r.getDoctorUtilization().get(0).getCompletedPatients());
    }

    @Test
    @DisplayName("Hourly load contains only hours that actually have appointments")
    void hourlyOnlyRealHours() {
        LocalDate d = freshDate();
        appt(d, LocalTime.of(9, 5), AppointmentStatus.CONFIRMED);
        appt(d, LocalTime.of(9, 50), AppointmentStatus.CONFIRMED);
        appt(d, LocalTime.of(14, 0), AppointmentStatus.CONFIRMED);

        AnalyticsOverviewResponse r = analyticsService.getHospitalOverview(d);

        assertEquals(2, r.getHourlyCheckIns().size());
        assertEquals("09:00", r.getHourlyCheckIns().get(0).getHourLabel());
        assertEquals(2, r.getHourlyCheckIns().get(0).getCheckIns());
        assertNull(r.getHourlyCheckIns().get(0).getConsultations(), "consultations is not tracked: must be null");
    }
}
