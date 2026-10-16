package com.carequeue.booking;

import com.carequeue.dto.AppointmentDtos.BookAppointmentRequest;
import com.carequeue.dto.HospitalDoctorDtos.CreateDoctorRequest;
import com.carequeue.dto.HospitalDoctorDtos.DoctorDto;
import com.carequeue.dto.HospitalDoctorDtos.DoctorScheduleDto;
import com.carequeue.e2e.FixedClockTestConfig;
import com.carequeue.e2e.TestSchedules;
import com.carequeue.entity.*;
import com.carequeue.exception.BadRequestException;
import com.carequeue.exception.ConflictException;
import com.carequeue.repository.*;
import com.carequeue.service.AdminStaffService;
import com.carequeue.service.AppointmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Booking window rules, verified against real services and the real database with a fixed clock
 * (08:00 on FixedClockTestConfig.DAY). The rules mirror DoctorScheduleService's availability grid.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(FixedClockTestConfig.class)
class BookingRulesTest {

    private static final LocalDate DAY = FixedClockTestConfig.DAY;

    @Autowired private AppointmentService appointmentService;
    @Autowired private AdminStaffService adminStaffService;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private QueueEntryRepository queueEntryRepository;

    // ---------------------------------------------------------------- fixtures

    private Hospital hospital() { return hospitalRepository.findAll().get(0); }
    private Department department() { return departmentRepository.findAll().get(0); }

    private Doctor doctorWith(List<DoctorScheduleDto> schedule) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        DoctorDto dto = adminStaffService.createDoctor(CreateDoctorRequest.builder()
            .fullName("Dr Rules " + id).email("dr.rules." + id + "@test.local").password("secret123")
            .hospitalId(hospital().getId()).departmentId(department().getId())
            .licenseNumber("LIC-RULES-" + id).specialization("Testing")
            .initialSchedule(schedule).build());
        return doctorRepository.findById(dto.getId()).orElseThrow();
    }

    private Doctor doctor() { return doctorWith(TestSchedules.fullWeek()); }

    /** A patient user (booking is done on the patient's own behalf). */
    private User patientUser() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder()
            .email("pt.rules." + id + "@test.local").password("$2a$10$notarealhashjustfortest")
            .fullName("Rules Patient " + id).phoneNumber("9" + Math.abs(id.hashCode() % 100000000))
            .role(Role.PATIENT).active(true).build());
        patientRepository.save(Patient.builder().user(u).build());
        return u;
    }

    private BookAppointmentRequest request(Doctor doctor, LocalDate date, LocalTime time) {
        return BookAppointmentRequest.builder()
            .hospitalId(hospital().getId()).departmentId(department().getId()).doctorId(doctor.getId())
            .appointmentDate(date).appointmentTime(time).build();
    }

    private Long bookIdAs(User patient, Doctor doctor, LocalDate date, LocalTime time) {
        return appointmentService.bookAppointment(request(doctor, date, time), patient.getId(), false).getId();
    }

    // ---------------------------------------------------------------- valid booking

    @Test
    @DisplayName("A valid booking on a schedule slot is CONFIRMED, holds its slot key, and places the patient in BOOKED (not yet callable)")
    void validBookingPersistsWithBookedQueueState() {
        Doctor doctor = doctor();
        User patient = patientUser();

        Long id = bookIdAs(patient, doctor, DAY, LocalTime.of(9, 0));

        Appointment saved = appointmentRepository.findById(id).orElseThrow();
        assertEquals(AppointmentStatus.CONFIRMED, saved.getStatus());
        assertNotNull(saved.getActiveSlotKey(), "an occupying booking must hold its slot key");
        QueueEntry entry = queueEntryRepository.findByAppointmentId(id).orElseThrow();
        assertEquals(QueueStatus.BOOKED, entry.getStatus(), "a booked patient is not in the callable WAITING state until check-in");
    }

    // ---------------------------------------------------------------- past bookings

    @Test
    @DisplayName("A past date is rejected")
    void pastDateRejected() {
        Doctor doctor = doctor();
        BadRequestException ex = assertThrows(BadRequestException.class,
            () -> bookIdAs(patientUser(), doctor, DAY.minusDays(1), LocalTime.of(9, 0)));
        assertTrue(ex.getMessage().contains("past date"), ex.getMessage());
    }

    @Test
    @DisplayName("A time today that has already passed is rejected")
    void timePassedTodayRejected() {
        Doctor doctor = doctorWith(TestSchedules.week(DAY.getDayOfWeek()));
        // Clock is 08:00; 07:00 is in the past but would also be outside the shift. The past check comes first.
        BadRequestException ex = assertThrows(BadRequestException.class,
            () -> bookIdAs(patientUser(), doctor, DAY, LocalTime.of(7, 0)));
        assertTrue(ex.getMessage().contains("already passed"), ex.getMessage());
    }

    // ---------------------------------------------------------------- unavailable schedules

    @Test
    @DisplayName("A doctor with no schedule on that weekday cannot be booked")
    void noScheduleOnThatWeekdayRejected() {
        List<DoctorScheduleDto> everyDayButOne = TestSchedules.week(
            java.util.Arrays.stream(DayOfWeek.values()).filter(d -> d != DAY.getDayOfWeek()).toArray(DayOfWeek[]::new));
        Doctor doctor = doctorWith(everyDayButOne);

        BadRequestException ex = assertThrows(BadRequestException.class,
            () -> bookIdAs(patientUser(), doctor, DAY, LocalTime.of(9, 0)));
        assertTrue(ex.getMessage().contains("not on duty"), ex.getMessage());
    }

    @Test
    @DisplayName("A time that is not on the slot grid is rejected (09:05 with 15-minute slots from 08:00)")
    void offGridTimeRejected() {
        Doctor doctor = doctor();
        BadRequestException ex = assertThrows(BadRequestException.class,
            () -> bookIdAs(patientUser(), doctor, DAY, LocalTime.of(9, 5)));
        assertTrue(ex.getMessage().contains("not an available slot"), ex.getMessage());
    }

    @Test
    @DisplayName("A slot that would run past the end of the shift is rejected (18:00 with a 15-minute slot ending at 18:00)")
    void slotPastShiftEndRejected() {
        Doctor doctor = doctor();
        assertThrows(BadRequestException.class,
            () -> bookIdAs(patientUser(), doctor, DAY, LocalTime.of(18, 0)));
    }

    // ---------------------------------------------------------------- inactive doctor

    @Test
    @DisplayName("An inactive doctor cannot be booked")
    void inactiveDoctorRejected() {
        Doctor doctor = doctor();
        adminStaffService.setDoctorActive(doctor.getId(), false);

        BadRequestException ex = assertThrows(BadRequestException.class,
            () -> bookIdAs(patientUser(), doctor, DAY, LocalTime.of(9, 0)));
        assertTrue(ex.getMessage().contains("not accepting"), ex.getMessage());
    }

    // ---------------------------------------------------------------- capacity

    @Test
    @DisplayName("Capacity (maxPatients) is enforced: the second booking on a full day is rejected with 409")
    void capacityEnforced() {
        DoctorScheduleDto oneSeat = TestSchedules.week(DAY.getDayOfWeek()).get(0);
        oneSeat.setMaxPatients(1);
        Doctor doctor = doctorWith(List.of(oneSeat));

        bookIdAs(patientUser(), doctor, DAY, LocalTime.of(9, 0));
        assertThrows(ConflictException.class,
            () -> bookIdAs(patientUser(), doctor, DAY, LocalTime.of(9, 15)));
    }

    // ---------------------------------------------------------------- concurrency backstop

    @Test
    @DisplayName("The database refuses a second occupying booking for the same slot, even without the application checks")
    void databaseConstraintRefusesDuplicateSlotKey() {
        Doctor doctor = doctor();
        User patient = patientUser();
        Long first = bookIdAs(patient, doctor, DAY, LocalTime.of(10, 0));
        Appointment existing = appointmentRepository.findById(first).orElseThrow();

        Appointment duplicate = Appointment.builder()
            .appointmentNumber("DUP-" + UUID.randomUUID())
            .patient(existing.getPatient()).doctor(doctor).hospital(hospital()).department(department())
            .appointmentDate(DAY).appointmentTime(LocalTime.of(10, 0))
            .status(AppointmentStatus.CONFIRMED).tokenFormatted("A-999")
            .activeSlotKey(existing.getActiveSlotKey())
            .build();

        assertThrows(DataIntegrityViolationException.class, () -> appointmentRepository.saveAndFlush(duplicate));
    }

    // ---------------------------------------------------------------- cancellation frees the slot

    @Test
    @DisplayName("A cancelled booking releases its slot: another patient can book it")
    void cancelledSlotCanBeRebooked() {
        Doctor doctor = doctor();
        User first = patientUser();
        User second = patientUser();

        Long id = bookIdAs(first, doctor, DAY, LocalTime.of(11, 0));
        appointmentService.cancelAppointment(id, null, first.getId(), false);

        assertNull(appointmentRepository.findById(id).orElseThrow().getActiveSlotKey(), "cancelled rows release the slot key");
        assertDoesNotThrow(() -> bookIdAs(second, doctor, DAY, LocalTime.of(11, 0)));
    }
}
