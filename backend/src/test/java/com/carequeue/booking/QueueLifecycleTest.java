package com.carequeue.booking;

import com.carequeue.dto.AppointmentDtos.BookAppointmentRequest;
import com.carequeue.dto.HospitalDoctorDtos.CreateDoctorRequest;
import com.carequeue.dto.HospitalDoctorDtos.DoctorDto;
import com.carequeue.dto.QueueDtos.EmergencyTriageRequest;
import com.carequeue.e2e.FixedClockTestConfig;
import com.carequeue.e2e.TestSchedules;
import com.carequeue.entity.*;
import com.carequeue.exception.BadRequestException;
import com.carequeue.repository.*;
import com.carequeue.service.AdminStaffService;
import com.carequeue.service.AppointmentService;
import com.carequeue.service.QueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle of a patient from booking to consultation, with the rule that a booked patient is not
 * callable until reception checks them in. Fixed clock: 08:00 on FixedClockTestConfig.DAY.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(FixedClockTestConfig.class)
class QueueLifecycleTest {

    private static final LocalDate DAY = FixedClockTestConfig.DAY;

    @Autowired private AppointmentService appointmentService;
    @Autowired private QueueService queueService;
    @Autowired private AdminStaffService adminStaffService;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private QueueEntryRepository queueEntryRepository;

    private Hospital hospital;
    private Department department;

    private Doctor doctor() {
        hospital = hospitalRepository.findAll().get(0);
        department = departmentRepository.findAll().get(0);
        String id = UUID.randomUUID().toString().substring(0, 8);
        DoctorDto dto = adminStaffService.createDoctor(CreateDoctorRequest.builder()
            .fullName("Dr Life " + id).email("dr.life." + id + "@test.local").password("secret123")
            .hospitalId(hospital.getId()).departmentId(department.getId())
            .licenseNumber("LIC-LIFE-" + id).specialization("Testing")
            .initialSchedule(TestSchedules.fullWeek()).build());
        return doctorRepository.findById(dto.getId()).orElseThrow();
    }

    private User patientUser() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder()
            .email("pt.life." + id + "@test.local").password("$2a$10$notarealhashjustfortest")
            .fullName("Life Patient " + id).phoneNumber("9" + Math.abs(id.hashCode() % 100000000))
            .role(Role.PATIENT).active(true).build());
        patientRepository.save(Patient.builder().user(u).build());
        return u;
    }

    private Long book(User patient, Doctor doctor, LocalDate date, LocalTime time) {
        return appointmentService.bookAppointment(BookAppointmentRequest.builder()
            .hospitalId(hospital.getId()).departmentId(department.getId()).doctorId(doctor.getId())
            .appointmentDate(date).appointmentTime(time).build(), patient.getId(), false).getId();
    }

    @Test
    @DisplayName("Pre-check-in: a booked patient is refused by call-next and stays BOOKED")
    void bookedPatientIsNotCallable() {
        Doctor doctor = doctor();
        Long apptId = book(patientUser(), doctor, DAY, LocalTime.of(9, 0));

        BadRequestException ex = assertThrows(BadRequestException.class,
            () -> queueService.callNextPatient(doctor.getId()));
        assertTrue(ex.getMessage().contains("No waiting patients"), ex.getMessage());

        assertEquals(QueueStatus.BOOKED, queueEntryRepository.findByAppointmentId(apptId).orElseThrow().getStatus());
        assertEquals(AppointmentStatus.CONFIRMED, appointmentRepository.findById(apptId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Post-check-in: the checked-in patient is called; appointment and queue move together")
    void checkedInPatientIsCalled() {
        Doctor doctor = doctor();
        Long apptId = book(patientUser(), doctor, DAY, LocalTime.of(9, 0));
        Long entryId = queueService.checkInPatient(apptId).getId();

        var overview = queueService.callNextPatient(doctor.getId());

        assertEquals(entryId, overview.getCurrentPatient().getId());
        assertEquals(QueueStatus.CALLED, queueEntryRepository.findById(entryId).orElseThrow().getStatus());
        assertEquals(AppointmentStatus.IN_QUEUE, appointmentRepository.findById(apptId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Check-in is one-time: a second check-in of the same appointment is refused")
    void doubleCheckInRefused() {
        Doctor doctor = doctor();
        Long apptId = book(patientUser(), doctor, DAY, LocalTime.of(9, 0));
        queueService.checkInPatient(apptId);

        assertThrows(BadRequestException.class, () -> queueService.checkInPatient(apptId));
    }

    @Test
    @DisplayName("Check-in is only possible on the appointment date (a future booking cannot be checked in today)")
    void checkInOnAnotherDateRefused() {
        Doctor doctor = doctor();
        Long futureApptId = book(patientUser(), doctor, DAY.plusDays(1), LocalTime.of(9, 0));

        BadRequestException ex = assertThrows(BadRequestException.class,
            () -> queueService.checkInPatient(futureApptId));
        assertTrue(ex.getMessage().contains("appointment date"), ex.getMessage());
    }

    @Test
    @DisplayName("Emergency triage is preserved: an emergency is WAITING immediately and callable without a booking or check-in")
    void emergencyBypassesBookingAndCheckIn() {
        Doctor doctor = doctor();
        queueService.triageEmergency(EmergencyTriageRequest.builder()
            .hospitalId(hospital.getId()).departmentId(department.getId()).doctorId(doctor.getId())
            .patientName("Walk In Emergency").patientPhone("9000000123")
            .triageCondition("collapsed").severity(EmergencySeverity.CRITICAL).build());

        var overview = queueService.callNextPatient(doctor.getId());

        assertNotNull(overview.getCurrentPatient(), "emergency patients are called without check-in");
        assertEquals("Walk In Emergency", overview.getCurrentPatient().getPatientName());
    }
}
