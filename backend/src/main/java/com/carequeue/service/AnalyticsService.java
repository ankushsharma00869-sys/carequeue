package com.carequeue.service;

import com.carequeue.dto.AnalyticsDtos.*;
import com.carequeue.entity.AppointmentStatus;
import com.carequeue.repository.AnalyticsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Hospital analytics for a single calendar day.
 *
 * Every number is computed by the database for the requested date. There are no fallback
 * baselines: when no data exists for a metric the value is 0 (or an empty list), so the
 * dashboard reports the true state rather than a plausible-looking default.
 *
 * Date semantics:
 *  - "Today" means the requested date (defaults to LocalDate.now() in the JVM default zone,
 *    the same zone used when appointments and queue entries are stamped).
 *  - Appointment metrics filter on appointmentDate == date. Future-dated appointments are not
 *    counted on today's date; past-dated appointments are not counted on today's date either.
 *  - Emergency cases are counted by createdAt within [start-of-day, start-of-next-day).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsService {

    private final AnalyticsRepository analyticsRepository;
    private final java.time.Clock clock;

    public AnalyticsOverviewResponse getHospitalOverview(LocalDate requestedDate) {
        LocalDate date = requestedDate != null ? requestedDate : LocalDate.now(clock);

        Map<AppointmentStatus, Long> byStatus = toStatusMap(analyticsRepository.countAppointmentsByStatusForDate(date));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long completed = byStatus.getOrDefault(AppointmentStatus.COMPLETED, 0L);
        long cancelled = byStatus.getOrDefault(AppointmentStatus.CANCELLED, 0L);
        long noShows = byStatus.getOrDefault(AppointmentStatus.NO_SHOW, 0L);

        // "Waiting now" = appointments that have checked in or are in the queue, not yet finished.
        long waitingNow = byStatus.getOrDefault(AppointmentStatus.CHECKED_IN, 0L)
            + byStatus.getOrDefault(AppointmentStatus.IN_QUEUE, 0L);

        long emergencies = analyticsRepository.countEmergenciesBetween(
            date.atStartOfDay(), date.plusDays(1).atStartOfDay());

        return AnalyticsOverviewResponse.builder()
            .totalAppointmentsToday(total)
            .completedToday(completed)
            .waitingNow(waitingNow)
            .cancelledToday(cancelled)
            .noShowsToday(noShows)
            .emergencyCasesToday(emergencies)
            .averageWaitMinutes(round1(averageWaitMinutes(date)))
            // Utilisation needs doctor working-hours data, which the system does not record.
            // Null (not a guessed percentage) so the UI can say "Not tracked".
            .doctorUtilizationPercentage(null)
            .departmentDistribution(departmentDistribution(date, total))
            .hourlyCheckIns(hourlyDistribution(date))
            .doctorUtilization(doctorPerformance(date))
            .build();
    }

    private Map<AppointmentStatus, Long> toStatusMap(List<Object[]> rows) {
        Map<AppointmentStatus, Long> map = new EnumMap<>(AppointmentStatus.class);
        for (Object[] row : rows) {
            map.put((AppointmentStatus) row[0], ((Number) row[1]).longValue());
        }
        return map;
    }

    /**
     * Mean minutes from queue creation to consultation start, over entries that actually started on
     * {@code date}. Returns 0 when none did.
     */
    private double averageWaitMinutes(LocalDate date) {
        List<Object[]> pairs = analyticsRepository.waitTimestampPairsForDate(date);
        if (pairs.isEmpty()) {
            return 0.0;
        }
        long totalMinutes = 0;
        for (Object[] pair : pairs) {
            LocalDateTime created = (LocalDateTime) pair[0];
            LocalDateTime started = (LocalDateTime) pair[1];
            totalMinutes += Math.max(0, Duration.between(created, started).toMinutes());
        }
        return (double) totalMinutes / pairs.size();
    }

    private List<DepartmentStatDto> departmentDistribution(LocalDate date, long total) {
        List<DepartmentStatDto> out = new ArrayList<>();
        for (Object[] row : analyticsRepository.countAppointmentsByDepartmentForDate(date)) {
            long count = ((Number) row[1]).longValue();
            out.add(DepartmentStatDto.builder()
                .departmentName((String) row[0])
                .count(count)
                .percentage(total > 0 ? round1(count * 100.0 / total) : 0.0)
                .build());
        }
        out.sort(Comparator.comparingLong(DepartmentStatDto::getCount).reversed());
        return out;
    }

    /** Only hours that actually have appointments. No zero-padding, no invented "consultations". */
    private List<HourlyLoadDto> hourlyDistribution(LocalDate date) {
        List<HourlyLoadDto> out = new ArrayList<>();
        for (Object[] row : analyticsRepository.countAppointmentsByHourForDate(date)) {
            int hour = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            out.add(HourlyLoadDto.builder()
                .hourLabel(String.format("%02d:00", hour))
                .checkIns(count)
                .consultations(null)
                .build());
        }
        out.sort(Comparator.comparing(HourlyLoadDto::getHourLabel));
        return out;
    }

    /** Per-doctor totals for the date. Doctors with no appointments that day are absent. */
    /** Mean minutes from consultation start to completion, per doctor, for visits that finished on {@code date}. */
    private Map<Long, Double> averageConsultationByDoctor(LocalDate date) {
        Map<Long, long[]> totals = new HashMap<>();
        for (Object[] row : analyticsRepository.consultationTimestampPairsForDate(date)) {
            long doctorId = ((Number) row[0]).longValue();
            LocalDateTime started = (LocalDateTime) row[1];
            LocalDateTime completed = (LocalDateTime) row[2];
            long minutes = Math.max(0, Duration.between(started, completed).toMinutes());
            long[] acc = totals.computeIfAbsent(doctorId, k -> new long[2]);
            acc[0] += minutes;
            acc[1] += 1;
        }
        Map<Long, Double> out = new HashMap<>();
        totals.forEach((doctorId, acc) -> out.put(doctorId, round1((double) acc[0] / acc[1])));
        return out;
    }

    private List<DoctorPerformanceDto> doctorPerformance(LocalDate date) {
        List<DoctorPerformanceDto> out = new ArrayList<>();
        Map<Long, Double> avgByDoctor = averageConsultationByDoctor(date);
        for (Object[] row : analyticsRepository.doctorPerformanceForDate(date, AppointmentStatus.COMPLETED)) {
            long totalAssigned = ((Number) row[3]).longValue();
            long completedCount = row[4] == null ? 0L : ((Number) row[4]).longValue();
            long doctorId = ((Number) row[0]).longValue();
            out.add(DoctorPerformanceDto.builder()
                .doctorId(((Number) row[0]).longValue())
                .doctorName((String) row[1])
                .departmentName((String) row[2])
                .totalAssigned(totalAssigned)
                .completedPatients(completedCount)
                // Measured from the stored consultation start and completion times.
                .averageConsultationMinutes(avgByDoctor.getOrDefault(doctorId, 0.0))
                .build());
        }
        out.sort(Comparator.comparing(DoctorPerformanceDto::getDoctorName));
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
