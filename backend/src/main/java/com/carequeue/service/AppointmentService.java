package com.carequeue.service;

import com.carequeue.dto.AppointmentDtos.*;
import com.carequeue.entity.*;
import com.carequeue.event.CareQueueEventPublisher;
import com.carequeue.event.CareQueueEvents;
import com.carequeue.exception.BadRequestException;
import com.carequeue.exception.ConflictException;
import com.carequeue.exception.ResourceNotFoundException;
import com.carequeue.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final QueueService queueService;
    private final DistributedLockService lockService;
    private final CareQueueEventPublisher eventPublisher;
    private final NoShowPredictor noShowPredictor;
    private final AuditLogService auditLogService;
    private final DoctorScheduleRepository doctorScheduleRepository;
    private final Clock clock;

    @Value("${carequeue.rules.cancellation-threshold-minutes:30}")
    private int cancellationThresholdMinutes;

    /**
     * Booking window rules. They mirror DoctorScheduleService.getDoctorAvailability, so a slot shown
     * as available is exactly a slot that can be booked:
     *  - the doctor and their login must be active;
     *  - the date must not be in the past, and a time today must not have passed (clock-based);
     *  - the doctor needs an active schedule for that weekday;
     *  - the time must be a slot start on the schedule grid, within the shift;
     *  - the day's capacity (maxPatients) must not be exhausted.
     */
    private void validateBookingWindow(Doctor doctor, LocalDate date, LocalTime time) {
        if (!doctor.isActive() || !doctor.getUser().isActive()) {
            throw new BadRequestException("This doctor is not accepting appointments at the moment.");
        }
        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today)) {
            throw new BadRequestException("Appointments cannot be booked for a past date.");
        }
        if (date.equals(today) && time.isBefore(LocalTime.now(clock))) {
            throw new BadRequestException("This time has already passed today.");
        }
        DoctorSchedule schedule = doctorScheduleRepository
            .findByDoctorIdAndDayOfWeekAndActiveTrue(doctor.getId(), date.getDayOfWeek())
            .orElseThrow(() -> new BadRequestException(
                "Dr. " + doctor.getUser().getFullName() + " is not on duty on " + date.getDayOfWeek() + "."));

        int slot = schedule.getSlotDurationMinutes();
        // Minutes since midnight, so a slot can never wrap past the end of the shift at 24:00.
        int startMin = schedule.getStartTime().toSecondOfDay() / 60;
        int endMin = schedule.getEndTime().toSecondOfDay() / 60;
        int timeMin = time.toSecondOfDay() / 60;
        boolean withinShift = timeMin >= startMin && timeMin + slot <= endMin;
        if (!withinShift || (timeMin - startMin) % slot != 0) {
            throw new BadRequestException("That time is not an available slot. Dr. " + doctor.getUser().getFullName()
                + " sees patients " + schedule.getStartTime() + " to " + schedule.getEndTime()
                + ", every " + slot + " minutes.");
        }

        long occupying = appointmentRepository.countByDoctorIdAndAppointmentDateAndStatusNot(
            doctor.getId(), date, AppointmentStatus.CANCELLED);
        if (occupying >= schedule.getMaxPatients()) {
            throw new ConflictException("Dr. " + doctor.getUser().getFullName() + " is fully booked on " + date + ".");
        }
    }

    @Transactional
    public AppointmentResponse bookAppointment(BookAppointmentRequest request, Long currentUserId, boolean isStaff) {
        // Distributed Slot Locking (prevents race condition between concurrent bookings).
        // Held for the lifetime of THIS transaction (see acquireForCurrentTransaction) —
        // previously released in a `finally` block as soon as this method returned, i.e.
        // before Spring's transactional proxy actually committed the new Appointment row.
        // A second request for the identical doctor+date+time could acquire the freed lock,
        // re-run the "is this slot already booked?" check before the first transaction's
        // insert was durable, see "not booked", and create a second CONFIRMED appointment
        // for the same slot. There is no database unique constraint on
        // (doctor, date, time) to catch that after the fact — this lock is the only thing
        // preventing a duplicate booking, so its timing matters.
        String lockKey = String.format("lock:slot:%d:%s:%s",
            request.getDoctorId(), request.getAppointmentDate(), request.getAppointmentTime());

        lockService.acquireForCurrentTransaction(lockKey, 10, 3,
            "The selected time slot is currently being locked by another booking request. Please select a different slot or retry in a few seconds.");

        // Step 1b: Business rules (schedule, past time, active doctor, capacity)
        Doctor scheduledDoctor = doctorRepository.findById(request.getDoctorId())
            .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + request.getDoctorId()));
        validateBookingWindow(scheduledDoctor, request.getAppointmentDate(), request.getAppointmentTime());

        // Step 2: Database concurrency collision check
        boolean alreadyBooked = appointmentRepository.existsByDoctorIdAndAppointmentDateAndAppointmentTimeAndStatusNot(
            request.getDoctorId(),
            request.getAppointmentDate(),
            request.getAppointmentTime(),
            AppointmentStatus.CANCELLED
        );

        if (alreadyBooked) {
            throw new ConflictException("This appointment slot is already booked by another patient. Please choose another available time slot.");
        }

        // Step 3: Validate Doctor, Hospital, Department
        Doctor doctor = doctorRepository.findById(request.getDoctorId())
            .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + request.getDoctorId()));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with id: " + request.getHospitalId()));

        Department department = departmentRepository.findById(request.getDepartmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Department not found with id: " + request.getDepartmentId()));

        // Step 4: Resolve Patient
        Patient patient;
        if (request.getPatientId() != null) {
            patient = patientRepository.findById(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + request.getPatientId()));

            // A Patient/Doctor account may only ever book for themselves. Only staff
            // (Receptionist/Admin) may book on behalf of a different named patient.
            if (!isStaff && !patient.getUser().getId().equals(currentUserId)) {
                throw new AccessDeniedException("You can only book appointments for yourself.");
            }
        } else {
            patient = patientRepository.findByUserId(currentUserId)
                .orElseThrow(() -> new BadRequestException("Current authenticated user is not registered as a patient. Please supply patientId."));
        }

        // Step 6: Concurrency-Safe Token Generation
        QueueEntry queueEntry = queueService.createQueueEntryForAppointment(
            doctor, patient, request.getAppointmentDate(), QueuePriority.NORMAL
        );

        // Step 7: Persist Appointment
        //
        // appointmentNumber (e.g. CQ-2026-10482) is derived from the row's own
        // database-generated IDENTITY id, not from a separately-managed counter.
        //
        // This replaces an earlier design (AppointmentNumberGenerator, a dedicated
        // per-year counter row incremented via a REQUIRES_NEW nested transaction) that
        // was intended to be concurrency-safe and had its own passing dedicated tests,
        // but intermittently produced a duplicate appointment_number specifically when
        // called from within bookAppointment's enclosing transaction under concurrent
        // load (reproduced in ~2 of 3 runs of ConcurrentSlotBookingTest; never
        // reproduced calling the generator directly). The exact interaction was not
        // conclusively isolated — plausible contributors include the extra DB
        // connection each REQUIRES_NEW call demands under connection-pool contention —
        // but rather than ship a mechanism with an unexplained intermittent failure,
        // this uses a simpler one with a categorically stronger guarantee: a database
        // IDENTITY column cannot assign the same value twice to concurrent inserts,
        // by construction, with no custom locking of any kind required.
        //
        // Mechanics: insert with a throwaway placeholder that trivially satisfies the
        // NOT NULL + UNIQUE constraint on appointment_number (a random UUID can never
        // collide with the "CQ-YYYY-NNNNN" format or with another placeholder), let the
        // database assign the real id, then overwrite appointmentNumber using that id
        // and save again — both statements in this same transaction, so a rollback
        // undoes both and no half-numbered row is ever visible to another transaction.
        String slotKey = request.getDoctorId() + "|" + request.getAppointmentDate() + "|" + request.getAppointmentTime();
        Appointment appointment = Appointment.builder()
            // appointment_number is VARCHAR(40); "P-" + a UUID is 38 chars, safely under that.
            .appointmentNumber("P-" + java.util.UUID.randomUUID())
            .activeSlotKey(slotKey)
            .patient(patient)
            .doctor(doctor)
            .hospital(hospital)
            .department(department)
            .appointmentDate(request.getAppointmentDate())
            .appointmentTime(request.getAppointmentTime())
            .status(AppointmentStatus.CONFIRMED)
            .tokenFormatted(queueEntry.getTokenFormatted())
            .patientNotes(request.getPatientNotes())
            .build();

        try {
            appointment = appointmentRepository.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException raced) {
            // The database unique constraint on active_slot_key caught a concurrent duplicate.
            throw new ConflictException("This appointment slot is already booked by another patient. Please choose another available time slot.");
        }
        String appointmentNumber = String.format("CQ-%d-%05d", Year.now().getValue(), appointment.getId());
        appointment.setAppointmentNumber(appointmentNumber);
        appointment = appointmentRepository.save(appointment);

        // Link appointment in queue entry
        // Booked, not yet arrived: not eligible for call-next until reception checks the patient in.
        queueEntry.setStatus(QueueStatus.BOOKED);
        queueEntry.setAppointment(appointment);
        queueService.saveQueueEntry(queueEntry);

        // Step 8: Calculate Estimated Wait Time
        int estimatedWait = queueService.calculateEstimatedWaitMinutes(doctor.getId(), request.getAppointmentDate(), queueEntry.getTokenNumber());

        // Step 9: Publish Kafka Event
        eventPublisher.publishAppointmentBooked(CareQueueEvents.AppointmentBookedEvent.builder()
            .appointmentId(appointment.getId())
            .appointmentNumber(appointment.getAppointmentNumber())
            .patientId(patient.getId())
            .patientUserId(patient.getUser().getId())
            .doctorId(doctor.getId())
            .doctorName(doctor.getUser().getFullName())
            .departmentName(department.getName())
            .appointmentDate(appointment.getAppointmentDate().toString())
            .appointmentTime(appointment.getAppointmentTime().toString())
            .tokenFormatted(appointment.getTokenFormatted())
            .estimatedWaitMinutes(estimatedWait)
            .build());

        return mapToAppointmentResponse(appointment, queueEntry, estimatedWait);
    }

    @Transactional
    public AppointmentResponse cancelAppointment(Long appointmentId, CancelAppointmentRequest request, Long currentUserId, boolean isStaff) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Appointment not found with id: " + appointmentId));

        if (!isStaff && !appointment.getPatient().getUser().getId().equals(currentUserId)) {
            throw new AccessDeniedException("You can only cancel your own appointments.");
        }

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new BadRequestException("This appointment is already cancelled.");
        }

        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new BadRequestException("Cannot cancel a completed consultation.");
        }

        // Rule Check: Must be at least 30 minutes before appointment time
        LocalDateTime scheduledDateTime = LocalDateTime.of(appointment.getAppointmentDate(), appointment.getAppointmentTime());
        LocalDateTime cancellationDeadline = LocalDateTime.now(clock).plusMinutes(cancellationThresholdMinutes);

        if (scheduledDateTime.isBefore(cancellationDeadline)) {
            throw new BadRequestException(String.format(
                "Cancellation policy restriction: Appointments can only be cancelled at least %d minutes prior to the scheduled time.",
                cancellationThresholdMinutes
            ));
        }

        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setActiveSlotKey(null); // frees the slot for rebooking
        appointment.setCancellationReason(request != null && request.getReason() != null ? request.getReason() : "Cancelled by patient");
        appointment.setCancelledAt(LocalDateTime.now(clock));
        appointment = appointmentRepository.save(appointment);

        // Remove from active queue
        queueService.cancelQueueEntryForAppointment(appointment.getId());

        // Publish Event
        eventPublisher.publishAppointmentCancelled(CareQueueEvents.AppointmentCancelledEvent.builder()
            .appointmentId(appointment.getId())
            .appointmentNumber(appointment.getAppointmentNumber())
            .patientUserId(appointment.getPatient().getUser().getId())
            .doctorName(appointment.getDoctor().getUser().getFullName())
            .appointmentDate(appointment.getAppointmentDate().toString())
            .appointmentTime(appointment.getAppointmentTime().toString())
            .reason(appointment.getCancellationReason())
            .build());

        auditLogService.record(
            "APPOINTMENT_CANCELLED",
            "APPOINTMENT",
            appointment.getAppointmentNumber(),
            "Cancelled by " + (isStaff ? "staff" : "patient") + ". Reason: " + appointment.getCancellationReason()
        );

        return mapToAppointmentResponse(appointment, null, 0);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getMyAppointments(Long currentUserId) {
        Patient patient = patientRepository.findByUserId(currentUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient profile not found for user: " + currentUserId));

        return appointmentRepository.findByPatientIdOrderByAppointmentDateDescAppointmentTimeDesc(patient.getId())
            .stream()
            .map(a -> {
                QueueEntry qe = queueService.findQueueEntryByAppointmentId(a.getId()).orElse(null);
                int wait = qe != null ? qe.getEstimatedWaitMinutes() : 0;
                return mapToAppointmentResponse(a, qe, wait);
            })
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getDoctorAppointmentsForToday(Long doctorId, LocalDate date) {
        return appointmentRepository.findByDoctorIdAndAppointmentDateOrderByAppointmentTimeAsc(doctorId, date)
            .stream()
            .map(a -> {
                QueueEntry qe = queueService.findQueueEntryByAppointmentId(a.getId()).orElse(null);
                int wait = qe != null ? qe.getEstimatedWaitMinutes() : 0;
                return mapToAppointmentResponse(a, qe, wait);
            })
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getAllAppointmentsForDate(LocalDate date) {
        return appointmentRepository.findByAppointmentDateOrderByAppointmentTimeAsc(date)
            .stream()
            .map(a -> {
                QueueEntry qe = queueService.findQueueEntryByAppointmentId(a.getId()).orElse(null);
                int wait = qe != null ? qe.getEstimatedWaitMinutes() : 0;
                return mapToAppointmentResponse(a, qe, wait);
            })
            .collect(Collectors.toList());
    }

    public AppointmentResponse mapToAppointmentResponse(Appointment a, QueueEntry qe, int waitMinutes) {
        LocalDateTime scheduledDateTime = LocalDateTime.of(a.getAppointmentDate(), a.getAppointmentTime());
        boolean canCancel = a.getStatus() != AppointmentStatus.CANCELLED &&
                            a.getStatus() != AppointmentStatus.COMPLETED &&
                            scheduledDateTime.isAfter(LocalDateTime.now(clock).plusMinutes(cancellationThresholdMinutes));

        // Evaluate No-Show Risk
        NoShowPredictor.RiskAssessment risk = noShowPredictor.assessRisk(
            a.getPatient(), a.getAppointmentDate(), a.getAppointmentTime(),
            a.getCreatedAt() != null ? a.getCreatedAt().toLocalDate() : LocalDate.now(clock)
        );

        return AppointmentResponse.builder()
            .id(a.getId())
            .appointmentNumber(a.getAppointmentNumber())
            .patientId(a.getPatient().getId())
            .patientName(a.getPatient().getUser().getFullName())
            .patientPhone(a.getPatient().getUser().getPhoneNumber())
            .doctorId(a.getDoctor().getId())
            .doctorName(a.getDoctor().getUser().getFullName())
            .doctorSpecialization(a.getDoctor().getSpecialization())
            .doctorRoom(a.getDoctor().getRoomNumber())
            .hospitalId(a.getHospital().getId())
            .hospitalName(a.getHospital().getName())
            .departmentId(a.getDepartment().getId())
            .departmentName(a.getDepartment().getName())
            .appointmentDate(a.getAppointmentDate())
            .appointmentTime(a.getAppointmentTime())
            .status(a.getStatus())
            .tokenFormatted(a.getTokenFormatted())
            .estimatedWaitMinutes(waitMinutes)
            .patientNotes(a.getPatientNotes())
            .cancellationReason(a.getCancellationReason())
            .canCancel(canCancel)
            .noShowRiskLevel(risk.getRiskLevel())
            .noShowRiskScore(risk.getScore())
            .createdAt(a.getCreatedAt())
            .build();
    }
}
