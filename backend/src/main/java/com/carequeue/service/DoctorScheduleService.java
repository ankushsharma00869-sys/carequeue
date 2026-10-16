package com.carequeue.service;

import com.carequeue.dto.HospitalDoctorDtos.*;
import com.carequeue.entity.Appointment;
import com.carequeue.entity.AppointmentStatus;
import com.carequeue.entity.Doctor;
import com.carequeue.entity.DoctorSchedule;
import com.carequeue.exception.ResourceNotFoundException;
import com.carequeue.repository.AppointmentRepository;
import com.carequeue.repository.DoctorRepository;
import com.carequeue.repository.DoctorScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DoctorScheduleService {
    private final java.time.Clock clock;


    private final DoctorRepository doctorRepository;
    private final DoctorScheduleRepository doctorScheduleRepository;
    private final AppointmentRepository appointmentRepository;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm a");

    public DoctorAvailabilityResponse getDoctorAvailability(Long doctorId, LocalDate date) {
        Doctor doctor = doctorRepository.findById(doctorId)
            .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + doctorId));

        Optional<DoctorSchedule> scheduleOpt = doctorScheduleRepository
            .findByDoctorIdAndDayOfWeekAndActiveTrue(doctorId, date.getDayOfWeek());

        if (scheduleOpt.isEmpty()) {
            return DoctorAvailabilityResponse.builder()
                .doctorId(doctorId)
                .doctorName(doctor.getUser().getFullName())
                .departmentName(doctor.getDepartment().getName())
                .date(date)
                .dayOfWeek(date.getDayOfWeek())
                .onDuty(false)
                .availableSlots(Collections.emptyList())
                .totalSlots(0)
                .bookedSlots(0)
                .build();
        }

        DoctorSchedule schedule = scheduleOpt.get();
        int slotDuration = schedule.getSlotDurationMinutes();
        if (slotDuration <= 0) {
            throw new IllegalStateException("Doctor schedule has a non-positive slot duration: " + slotDuration);
        }
        // Minutes since midnight. LocalTime wraps at 24:00, so a loop written as
        // "while (time + slot <= end)" never ends for a shift ending at 23:45: 23:45 + 15 wraps to 00:00,
        // which is still before the end. That loop grew the slot list until the heap ran out.
        int startMin = schedule.getStartTime().toSecondOfDay() / 60;
        int endMin = schedule.getEndTime().toSecondOfDay() / 60;

        // Query existing non-cancelled appointments for this doctor on this date
        List<Appointment> bookedAppointments = appointmentRepository
            .findByDoctorIdAndAppointmentDateOrderByAppointmentTimeAsc(doctorId, date)
            .stream()
            .filter(a -> a.getStatus() != AppointmentStatus.CANCELLED)
            .collect(Collectors.toList());

        Set<LocalTime> bookedTimes = bookedAppointments.stream()
            .map(Appointment::getAppointmentTime)
            .collect(Collectors.toSet());

        List<TimeSlotDto> slots = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock);

        for (int m = startMin; m + slotDuration <= endMin; m += slotDuration) {
            LocalTime currentSlotTime = LocalTime.ofSecondOfDay(m * 60L);
            boolean isBooked = bookedTimes.contains(currentSlotTime);
            boolean isPast = date.isBefore(today) || (date.isEqual(today) && currentSlotTime.isBefore(now));

            String reason = "Available";
            boolean available = true;

            if (isBooked) {
                available = false;
                reason = "Booked";
            } else if (isPast) {
                available = false;
                reason = "Past Time";
            }

            slots.add(TimeSlotDto.builder()
                .time(currentSlotTime)
                .formattedTime(currentSlotTime.format(TIME_FORMATTER))
                .available(available)
                .reason(reason)
                .build());

        }

        int bookedCount = (int) slots.stream().filter(s -> !s.isAvailable() && "Booked".equals(s.getReason())).count();

        return DoctorAvailabilityResponse.builder()
            .doctorId(doctorId)
            .doctorName(doctor.getUser().getFullName())
            .departmentName(doctor.getDepartment().getName())
            .date(date)
            .dayOfWeek(date.getDayOfWeek())
            .onDuty(true)
            .shiftStart(schedule.getStartTime())
            .shiftEnd(schedule.getEndTime())
            .slotDurationMinutes(slotDuration)
            .availableSlots(slots)
            .totalSlots(slots.size())
            .bookedSlots(bookedCount)
            .build();
    }
}
