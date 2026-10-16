package com.carequeue.repository;

import com.carequeue.entity.QueueEntry;
import com.carequeue.entity.QueuePriority;
import com.carequeue.entity.QueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface QueueEntryRepository extends JpaRepository<QueueEntry, Long> {

    Optional<QueueEntry> findByDoctorIdAndScheduleDateAndTokenNumber(Long doctorId, LocalDate scheduleDate, Integer tokenNumber);

    Optional<QueueEntry> findByAppointmentId(Long appointmentId);

    List<QueueEntry> findByPatientIdAndScheduleDate(Long patientId, LocalDate scheduleDate);

    // Primary queue ordering: priority_rank ASC (CRITICAL=1, PRIORITY=2, NORMAL=3), then token_number ASC
    List<QueueEntry> findByDoctorIdAndScheduleDateOrderByPriorityRankAscTokenNumberAsc(Long doctorId, LocalDate scheduleDate);

    List<QueueEntry> findByDoctorIdAndScheduleDateAndStatusOrderByPriorityRankAscTokenNumberAsc(
        Long doctorId, LocalDate scheduleDate, QueueStatus status
    );

    Optional<QueueEntry> findFirstByDoctorIdAndScheduleDateAndStatus(
        Long doctorId, LocalDate scheduleDate, QueueStatus status
    );

    // Concurrency-safe atomic token sequence generator
    @Query("SELECT COALESCE(MAX(q.tokenNumber), 0) FROM QueueEntry q WHERE q.doctor.id = :doctorId AND q.scheduleDate = :scheduleDate")
    Integer findMaxTokenNumber(@Param("doctorId") Long doctorId, @Param("scheduleDate") LocalDate scheduleDate);

    long countByDoctorIdAndScheduleDate(Long doctorId, LocalDate scheduleDate);

    long countByDoctorIdAndScheduleDateAndStatus(Long doctorId, LocalDate scheduleDate, QueueStatus status);

    long countByDoctorIdAndScheduleDateAndPriority(Long doctorId, LocalDate scheduleDate, QueuePriority priority);

    @Query("SELECT q FROM QueueEntry q WHERE q.doctor.id = :doctorId AND q.scheduleDate = :scheduleDate AND q.status = 'WAITING' ORDER BY q.priorityRank ASC, q.tokenNumber ASC")
    List<QueueEntry> findWaitingQueue(@Param("doctorId") Long doctorId, @Param("scheduleDate") LocalDate scheduleDate);
}
