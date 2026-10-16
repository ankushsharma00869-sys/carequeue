package com.carequeue.clock;

import com.carequeue.dto.AppointmentDtos.BookAppointmentRequest;
import com.carequeue.dto.HospitalDoctorDtos.CreateDoctorRequest;
import com.carequeue.dto.HospitalDoctorDtos.DoctorDto;
import com.carequeue.e2e.TestSchedules;
import com.carequeue.entity.*;
import com.carequeue.exception.BadRequestException;
import com.carequeue.repository.*;
import com.carequeue.service.AdminStaffService;
import com.carequeue.service.AnalyticsService;
import com.carequeue.service.AppointmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business decisions that depend on "now", checked at the boundaries that matter: midnight, and the
 * 30-minute cancellation window. Time is moved with MutableClock, so no test depends on the wall clock.
 * Dates are 2092-06-01/02, used by no other test class, so the counts below are exact.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(MutableClock.Config.class)
class ClockBoundaryTest {

    private static final LocalDate D1 = LocalDate.of(2092, 6, 1);
    private static final LocalDate D2 = D1.plusDays(1);

    @Autowired private MutableClock clock;
    @Autowired private AnalyticsService analyticsService;
    @Autowired private AppointmentService appointmentService;
    @Autowired private AdminStaffService adminStaffService;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private AppointmentRepository appointmentRepository;

    private Hospital hospital;
    private Department department;
    private Doctor doctor;
    private User patient;

    @BeforeEach
    void setUp() {
        clock.setLocal(MutableClock.Config.START);
        hospital = hospitalRepository.findAll().get(0);
        department = departmentRepository.findAll().get(0);
        String id = UUID.randomUUID().toString().substring(0, 8);
        DoctorDto dto = adminStaffService.createDoctor(CreateDoctorRequest.builder()
            .fullName("Dr Clock " + id).email("dr.clock." + id + "@test.local").password("secret123")
            .hospitalId(hospital.getId()).departmentId(department.getId())
            .licenseNumber("LIC-CLOCK-" + id).specialization("Testing")
            .initialSchedule(TestSchedules.fullWeek()).build());
        doctor = doctorRepository.findById(dto.getId()).orElseThrow();
        patient = userRepository.save(User.builder()
            .email("pt.clock." + id + "@test.local").password("$2a$10$notarealhashjustfortest")
            .fullName("Clock Patient " + id).phoneNumber("9" + Math.abs(id.hashCode() % 100000000))
            .role(Role.PATIENT).active(true).build());
        patientRepository.save(Patient.builder().user(patient).build());
    }

    private Long book(LocalDate date, LocalTime time) {
        return appointmentService.bookAppointment(BookAppointmentRequest.builder()
            .hospitalId(hospital.getId()).departmentId(department.getId()).doctorId(doctor.getId())
            .appointmentDate(date).appointmentTime(time).build(), patient.getId(), false).getId();
    }

    private Appointment seed(LocalDate date, LocalTime time) {
        return appointmentRepository.save(Appointment.builder()
            .appointmentNumber("CLK-" + UUID.randomUUID())
            .patient(patientRepository.findByUserId(patient.getId()).orElseThrow())
            .doctor(doctor).hospital(hospital).department(department)
            .appointmentDate(date).appointmentTime(time).status(AppointmentStatus.CONFIRMED)
            .tokenFormatted("A-001").build());
    }

    // ------------------------------------------------------------ midnight

    @Test
    @DisplayName("Midnight: the analytics default date follows the clock across the day boundary")
    void analyticsDefaultDateRollsOverAtMidnight() {
        // Dates used only by this test: other tests in this class book on D1/D2 and would skew the counts.
        LocalDate a = LocalDate.of(2092, 7, 1);
        LocalDate b = a.plusDays(1);
        seed(a, LocalTime.of(9, 0));                  // 1 appointment on a
        seed(b, LocalTime.of(9, 0));                  // 2 appointments on b
        seed(b, LocalTime.of(9, 30));

        clock.setLocal(a.atTime(23, 59));
        assertEquals(1, analyticsService.getHospitalOverview(null).getTotalAppointmentsToday());

        clock.setLocal(b.atTime(0, 1));               // one minute past midnight
        assertEquals(2, analyticsService.getHospitalOverview(null).getTotalAppointmentsToday(),
            "after midnight, 'today' is b");
    }

    @Test
    @DisplayName("Midnight: a same-day slot that has passed is refused; the same slot tomorrow is allowed")
    void bookingRespectsMidnight() {
        // 23:59 on D1: 09:00 today has passed, 09:00 tomorrow has not.
        BadRequestException passed = assertThrows(BadRequestException.class, () -> book(D1, LocalTime.of(9, 0)));
        assertTrue(passed.getMessage().contains("already passed"), passed.getMessage());
        assertNotNull(book(D2, LocalTime.of(9, 0)));

        // 00:01 on D2: yesterday is a past date.
        clock.setLocal(D2.atTime(0, 1));
        BadRequestException past = assertThrows(BadRequestException.class, () -> book(D1, LocalTime.of(18, 0)));
        assertTrue(past.getMessage().contains("past date"), past.getMessage());
    }

    // ------------------------------------------------------------ cancellation window

    @Test
    @DisplayName("Cancellation window: allowed more than 30 minutes before the slot, refused within it")
    void cancellationWindowFollowsTheClock() {
        // Booked at 23:00 on D1 for 10:00 on D2: 11 hours ahead, so cancellation is allowed.
        clock.setLocal(D1.atTime(23, 0));
        Long early = book(D2, LocalTime.of(10, 0));
        appointmentService.cancelAppointment(early, null, patient.getId(), false);
        assertEquals(AppointmentStatus.CANCELLED, appointmentRepository.findById(early).orElseThrow().getStatus());

        // Another booking for 10:15 D2. Policy: cancel at least 30 minutes before. At 09:50 only 25 minutes remain: refused.
        Long late = book(D2, LocalTime.of(10, 15));
        clock.setLocal(D2.atTime(9, 50));
        BadRequestException refused = assertThrows(BadRequestException.class,
            () -> appointmentService.cancelAppointment(late, null, patient.getId(), false));
        assertTrue(refused.getMessage().contains("Cancellation policy"), refused.getMessage());
        assertEquals(AppointmentStatus.CONFIRMED, appointmentRepository.findById(late).orElseThrow().getStatus());
    }
}
