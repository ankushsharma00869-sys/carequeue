package com.carequeue.repository;

import com.carequeue.entity.Appointment;
import com.carequeue.entity.AppointmentStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Database-side aggregation for the hospital analytics dashboard.
 *
 * Every method filters by an explicit date (or date range) and aggregates in SQL. Nothing here
 * loads whole tables into Java. Entities are the existing ones (Appointment, QueueEntry,
 * EmergencyRequest, Doctor) — no new tables are introduced.
 */
@Repository
public interface AnalyticsRepository extends org.springframework.data.repository.Repository<Appointment, Long> {

    /** Count of appointments on a given date, grouped by status. Returns rows: [AppointmentStatus, Long]. */
    @Query("SELECT a.status, COUNT(a) FROM Appointment a WHERE a.appointmentDate = :date GROUP BY a.status")
    List<Object[]> countAppointmentsByStatusForDate(@Param("date") LocalDate date);

    /** Count of appointments on a given date, grouped by department name. Returns rows: [String, Long]. */
    @Query("SELECT a.department.name, COUNT(a) FROM Appointment a WHERE a.appointmentDate = :date GROUP BY a.department.name")
    List<Object[]> countAppointmentsByDepartmentForDate(@Param("date") LocalDate date);

    /** Count of appointments on a given date, grouped by hour of the scheduled time. Returns rows: [Integer hour, Long]. */
    @Query("SELECT HOUR(a.appointmentTime), COUNT(a) FROM Appointment a WHERE a.appointmentDate = :date GROUP BY HOUR(a.appointmentTime)")
    List<Object[]> countAppointmentsByHourForDate(@Param("date") LocalDate date);

    /** Per-doctor totals for a date: [doctorId, doctorFullName, departmentName, totalAssigned, completedCount]. */
    @Query("""
        SELECT d.id, u.fullName, dep.name, COUNT(a), SUM(CASE WHEN a.status = :completed THEN 1 ELSE 0 END)
        FROM Appointment a
        JOIN a.doctor d
        JOIN d.user u
        JOIN a.department dep
        WHERE a.appointmentDate = :date
        GROUP BY d.id, u.fullName, dep.name
        """)
    List<Object[]> doctorPerformanceForDate(@Param("date") LocalDate date,
                                            @Param("completed") AppointmentStatus completed);

    /** Emergency cases created within [from, to). */
    @Query("SELECT COUNT(e) FROM EmergencyRequest e WHERE e.createdAt >= :from AND e.createdAt < :to")
    long countEmergenciesBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Queue entries for a schedule date that are still active (not finished). */
    @Query("""
        SELECT COUNT(q) FROM QueueEntry q
        WHERE q.scheduleDate = :date
          AND q.status IN :activeStatuses
        """)
    long countQueueEntriesWithStatusesForDate(@Param("date") LocalDate date,
                                              @Param("activeStatuses") java.util.Collection<com.carequeue.entity.QueueStatus> activeStatuses);

    /**
     * (createdAt, startedAt) pairs for queue entries of the given schedule date that actually
     * started consultation. Only the two timestamps are selected — a narrow projection, not whole
     * entities — and the mean is computed in the service. Portable across H2 and PostgreSQL
     * (no vendor-specific DATEDIFF/TIMESTAMPDIFF functions).
     */
    @Query("""
        SELECT q.checkedInAt, q.startedAt FROM QueueEntry q
        WHERE q.scheduleDate = :date
          AND q.startedAt IS NOT NULL
          AND q.checkedInAt IS NOT NULL
        """)
    List<Object[]> waitTimestampPairsForDate(@Param("date") LocalDate date);

    /**
     * (doctorId, startedAt, completedAt) for queue entries of the given schedule date whose
     * consultation both started and finished. The duration is measured here, per visit.
     */
    @Query("""
        SELECT q.doctor.id, q.startedAt, q.completedAt FROM QueueEntry q
        WHERE q.scheduleDate = :date
          AND q.startedAt IS NOT NULL
          AND q.completedAt IS NOT NULL
        """)
    List<Object[]> consultationTimestampPairsForDate(@Param("date") LocalDate date);
}
