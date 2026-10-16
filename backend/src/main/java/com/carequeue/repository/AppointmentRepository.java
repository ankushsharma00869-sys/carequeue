package com.carequeue.repository;

import com.carequeue.entity.Appointment;
import com.carequeue.entity.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    Optional<Appointment> findByAppointmentNumber(String appointmentNumber);

    List<Appointment> findByPatientIdOrderByAppointmentDateDescAppointmentTimeDesc(Long patientId);

    List<Appointment> findByDoctorIdAndAppointmentDateOrderByAppointmentTimeAsc(Long doctorId, LocalDate appointmentDate);

    List<Appointment> findByAppointmentDateOrderByAppointmentTimeAsc(LocalDate appointmentDate);

    boolean existsByDoctorIdAndAppointmentDateAndAppointmentTimeAndStatusNot(
        Long doctorId, LocalDate date, LocalTime time, AppointmentStatus status
    );

    @Query("SELECT COUNT(a) FROM Appointment a WHERE a.patient.id = :patientId AND a.status = 'NO_SHOW'")
    long countNoShowsByPatientId(@Param("patientId") Long patientId);

    @Query("SELECT COUNT(a) FROM Appointment a WHERE a.patient.id = :patientId AND a.status = 'CANCELLED'")
    long countCancellationsByPatientId(@Param("patientId") Long patientId);

    @Query("SELECT COUNT(a) FROM Appointment a WHERE a.patient.id = :patientId")
    long countTotalAppointmentsByPatientId(@Param("patientId") Long patientId);

    long countByAppointmentDate(LocalDate date);

    long countByAppointmentDateAndStatus(LocalDate date, AppointmentStatus status);

    @Query("SELECT a.department.name, COUNT(a) FROM Appointment a WHERE a.appointmentDate = :date GROUP BY a.department.name")
    List<Object[]> countAppointmentsByDepartmentForDate(@Param("date") LocalDate date);

    /** Bookings that still occupy a slot for this doctor on this date (capacity check). */
    long countByDoctorIdAndAppointmentDateAndStatusNot(Long doctorId, LocalDate appointmentDate, AppointmentStatus status);
}
