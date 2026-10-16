package com.carequeue.service;

import com.carequeue.dto.HospitalDoctorDtos.CreateDoctorRequest;
import com.carequeue.dto.HospitalDoctorDtos.DoctorAvailabilityResponse;
import com.carequeue.dto.HospitalDoctorDtos.DoctorDto;
import com.carequeue.dto.HospitalDoctorDtos.DoctorScheduleDto;
import com.carequeue.entity.Department;
import com.carequeue.entity.Hospital;
import com.carequeue.repository.DepartmentRepository;
import com.carequeue.repository.HospitalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the availability endpoint hanging and exhausting the heap.
 *
 * A shift that ends at 23:45 used to loop forever: 23:45 + 15 minutes wraps to 00:00, which is
 * still before the end, so slots were added without limit. The test uses a far-future date (no
 * "past" flags) and a hard time limit, so a regression fails quickly instead of hanging the run.
 */
@SpringBootTest
@ActiveProfiles("dev")
class DoctorAvailabilityWrapAroundTest {

    private static final LocalDate DAY = LocalDate.of(2091, 3, 15);

    @Autowired private AdminStaffService adminStaffService;
    @Autowired private DoctorScheduleService doctorScheduleService;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private Long doctorWithSchedule(LocalTime start, LocalTime end, int slotMinutes) {
        Hospital hospital = hospitalRepository.findAll().get(0);
        Department department = departmentRepository.findAll().get(0);
        List<DoctorScheduleDto> schedule = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            schedule.add(DoctorScheduleDto.builder()
                .dayOfWeek(d).startTime(start).endTime(end)
                .slotDurationMinutes(slotMinutes).maxPatients(40).active(true).build());
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        DoctorDto dto = adminStaffService.createDoctor(CreateDoctorRequest.builder()
            .fullName("Dr Wrap " + id).email("dr.wrap." + id + "@test.local").password("secret123")
            .phoneNumber("9" + Math.abs(id.hashCode() % 100000000))
            .hospitalId(hospital.getId()).departmentId(department.getId())
            .licenseNumber("LIC-WRAP-" + id).specialization("Testing")
            .initialSchedule(schedule).build());
        return dto.getId();
    }

    @Test
    @DisplayName("A shift ending at 23:45 returns 95 slots (00:00 to 23:30) and terminates")
    void fullDayShiftEndingAt2345Terminates() {
        Long doctorId = doctorWithSchedule(LocalTime.MIDNIGHT, LocalTime.of(23, 45), 15);

        DoctorAvailabilityResponse r = assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> doctorScheduleService.getDoctorAvailability(doctorId, DAY));

        assertTrue(r.isOnDuty());
        assertEquals(95, r.getTotalSlots(), "00:00 through 23:30 inclusive, 15-minute slots");
        assertEquals(95, r.getAvailableSlots().size());
        assertEquals(LocalTime.MIDNIGHT, r.getAvailableSlots().get(0).getTime());
        assertEquals(LocalTime.of(23, 30), r.getAvailableSlots().get(94).getTime(),
            "the last slot must start at 23:30 (23:45 would end after the shift)");
    }

    @Test
    @DisplayName("A normal 08:00-18:00 shift is unchanged: 40 slots of 15 minutes")
    void normalShiftUnchanged() {
        Long doctorId = doctorWithSchedule(LocalTime.of(8, 0), LocalTime.of(18, 0), 15);

        DoctorAvailabilityResponse r = doctorScheduleService.getDoctorAvailability(doctorId, DAY);

        assertEquals(40, r.getTotalSlots());
        assertEquals(LocalTime.of(8, 0), r.getAvailableSlots().get(0).getTime());
        assertEquals(LocalTime.of(17, 45), r.getAvailableSlots().get(39).getTime());
    }
}
