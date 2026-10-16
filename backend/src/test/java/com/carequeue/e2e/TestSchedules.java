package com.carequeue.e2e;

import com.carequeue.dto.HospitalDoctorDtos.DoctorScheduleDto;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Doctor schedules used by fixtures. Booking is only allowed inside a schedule, so fixtures need one. */
public final class TestSchedules {

    private TestSchedules() {}

    /** 08:00-18:00, 15-minute slots, every day of the week. */
    public static List<DoctorScheduleDto> fullWeek() {
        return week(DayOfWeek.values());
    }

    public static List<DoctorScheduleDto> week(DayOfWeek... days) {
        List<DoctorScheduleDto> out = new ArrayList<>();
        for (DayOfWeek d : days) {
            out.add(DoctorScheduleDto.builder()
                .dayOfWeek(d)
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(18, 0))
                .slotDurationMinutes(15)
                .maxPatients(40)
                .active(true)
                .build());
        }
        return out;
    }
}
