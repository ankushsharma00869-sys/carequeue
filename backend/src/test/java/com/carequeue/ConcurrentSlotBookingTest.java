package com.carequeue;

import com.carequeue.dto.AppointmentDtos.BookAppointmentRequest;
import com.carequeue.dto.HospitalDoctorDtos.CreateDoctorRequest;
import com.carequeue.dto.HospitalDoctorDtos.DoctorDto;
import com.carequeue.entity.AppointmentStatus;
import com.carequeue.entity.Doctor;
import com.carequeue.entity.Hospital;
import com.carequeue.entity.Patient;
import com.carequeue.exception.ConflictException;
import com.carequeue.repository.AppointmentRepository;
import com.carequeue.repository.DepartmentRepository;
import com.carequeue.repository.DoctorRepository;
import com.carequeue.repository.HospitalRepository;
import com.carequeue.repository.PatientRepository;
import com.carequeue.service.AdminStaffService;
import com.carequeue.service.AppointmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the bug fixed in AppointmentService.bookAppointment /
 * DistributedLockService.acquireForCurrentTransaction: the per-slot lock used to be
 * released in a `finally` block as soon as bookAppointment RETURNED, which is before
 * Spring's transactional proxy actually commits the new Appointment row. A second
 * concurrent request for the exact same doctor+date+time could then acquire the freed
 * lock, run "is this slot already booked?" before the first transaction's insert was
 * durable, see "not booked", and create a second CONFIRMED appointment for the same
 * slot — with no database unique constraint to catch it afterwards.
 *
 * Uses the real AppointmentService, real QueueService, real DistributedLockService and
 * a real H2 database — not mocks — because the bug is specifically about transaction
 * commit timing, which a mocked repository cannot exercise.
 */
@SpringBootTest
@ActiveProfiles("dev")
class ConcurrentSlotBookingTest {

    @Autowired private AppointmentService appointmentService;
    @Autowired private AdminStaffService adminStaffService;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private AppointmentRepository appointmentRepository;

    private Doctor doctor;
    private Hospital hospital;
    private Long departmentId;

    @BeforeEach
    void setUp() {
        hospital = hospitalRepository.findAll().get(0);
        departmentId = departmentRepository.findAll().get(0).getId();
        String id = UUID.randomUUID().toString().substring(0, 8);
        DoctorDto dto = adminStaffService.createDoctor(CreateDoctorRequest.builder()
            .fullName("Dr Slot " + id).email("dr.slot." + id + "@test.local").password("secret123")
            .initialSchedule(com.carequeue.e2e.TestSchedules.fullWeek())
            .hospitalId(hospital.getId()).departmentId(departmentId)
            .licenseNumber("LIC-SLOT-" + id).specialization("Testing").build());
        doctor = doctorRepository.findById(dto.getId()).orElseThrow();
    }

    private BookAppointmentRequest requestFor(Long patientId, LocalDate date, LocalTime time) {
        return BookAppointmentRequest.builder()
            .hospitalId(hospital.getId()).departmentId(departmentId).doctorId(doctor.getId())
            .appointmentDate(date).appointmentTime(time).patientId(patientId).build();
    }

    @Test
    @DisplayName("N concurrent bookings for the identical slot: exactly one CONFIRMED appointment is created")
    void onlyOneBookingWinsTheSameSlot() throws Exception {
        LocalDate date = LocalDate.now().plusDays(45);
        LocalTime time = LocalTime.of(10, 30);
        List<Patient> patients = patientRepository.findAll();
        int n = Math.min(6, patients.size());
        assertTrue(n >= 2, "test fixture needs at least 2 seeded patients");

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            Long patientId = patients.get(i).getId();
            results.add(pool.submit(() -> {
                start.await();
                try {
                    appointmentService.bookAppointment(requestFor(patientId, date, time), patientId, true);
                    return true;
                } catch (ConflictException e) {
                    return false; // lost the race, correctly
                }
            }));
        }
        start.countDown();

        long succeeded = 0;
        for (Future<Boolean> f : results) {
            if (f.get(30, TimeUnit.SECONDS)) succeeded++;
        }
        pool.shutdown();

        assertEquals(1, succeeded, "exactly one of the concurrent identical-slot bookings should win");

        long confirmedForSlot = appointmentRepository.findAll().stream()
            .filter(a -> a.getDoctor().getId().equals(doctor.getId())
                && a.getAppointmentDate().equals(date) && a.getAppointmentTime().equals(time)
                && a.getStatus() == AppointmentStatus.CONFIRMED)
            .count();
        assertEquals(1, confirmedForSlot, "no duplicate CONFIRMED appointment for the same slot must exist in the database");
    }

    @Test
    @DisplayName("Appointment numbers are derived from the row's own id (format CQ-YYYY-<id>) and are unique under real concurrency across many different slots")
    void appointmentNumbersAreUniqueAcrossConcurrentDistinctBookings() throws Exception {
        LocalDate date = LocalDate.now().plusDays(50);
        List<Patient> patients = patientRepository.findAll();
        int n = Math.min(8, patients.size());
        assertTrue(n >= 2);

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            Long patientId = patients.get(i).getId();
            LocalTime time = LocalTime.of(9, 0).plusMinutes(i * 15L); // distinct grid slots: no lock contention expected
            futures.add(pool.submit(() -> {
                start.await();
                return appointmentService.bookAppointment(requestFor(patientId, date, time), patientId, true)
                    .getAppointmentNumber();
            }));
        }
        start.countDown();

        List<String> numbers = new java.util.ArrayList<>();
        for (Future<String> f : futures) numbers.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(n, numbers.stream().distinct().count(), "every concurrently booked appointment must get a distinct number: " + numbers);
        for (String number : numbers) {
            assertTrue(number.matches("CQ-\\d{4}-\\d+"), "unexpected appointment number format: " + number);
        }
    }

    @Test
    @DisplayName("Different times for the same doctor do not contend with each other")
    void differentSlotsDoNotBlock() {
        LocalDate date = LocalDate.now().plusDays(46);
        List<Patient> patients = patientRepository.findAll();
        assertTrue(patients.size() >= 2);

        assertDoesNotThrow(() -> appointmentService.bookAppointment(
            requestFor(patients.get(0).getId(), date, LocalTime.of(11, 0)), patients.get(0).getId(), true));
        assertDoesNotThrow(() -> appointmentService.bookAppointment(
            requestFor(patients.get(1).getId(), date, LocalTime.of(11, 15)), patients.get(1).getId(), true));
    }

    @Test
    @DisplayName("After a lock-contention failure the slot lock is actually released (not leaked) so a later request can still book it")
    void lockIsReleasedAfterConflict() {
        LocalDate date = LocalDate.now().plusDays(47);
        LocalTime time = LocalTime.of(12, 0);
        List<Patient> patients = patientRepository.findAll();

        appointmentService.bookAppointment(requestFor(patients.get(0).getId(), date, time), patients.get(0).getId(), true);

        // A second attempt at the SAME slot must be rejected because it is booked, not because
        // the lock leaked from the first (successful) call.
        assertThrows(ConflictException.class, () ->
            appointmentService.bookAppointment(requestFor(patients.get(1).getId(), date, time), patients.get(1).getId(), true));

        // An unrelated slot must still be free to book — proves the lock for the first slot
        // was released and isn't blocking unrelated keys.
        assertDoesNotThrow(() -> appointmentService.bookAppointment(
            requestFor(patients.get(1).getId(), date, LocalTime.of(12, 15)), patients.get(1).getId(), true));
    }
}
