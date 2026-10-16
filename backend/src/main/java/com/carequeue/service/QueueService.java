package com.carequeue.service;

import com.carequeue.dto.QueueDtos.*;
import com.carequeue.entity.*;
import com.carequeue.event.CareQueueEventPublisher;
import com.carequeue.event.CareQueueEvents;
import com.carequeue.exception.BadRequestException;
import com.carequeue.exception.ResourceNotFoundException;
import com.carequeue.repository.*;
import com.carequeue.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final int LOCK_ACQUIRE_ATTEMPTS = 5;
    private static final long LOCK_TIMEOUT_SECONDS = 5;

    private final QueueEntryRepository queueEntryRepository;
    private final ConsultationRepository consultationRepository;
    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppointmentRepository appointmentRepository;
    private final EmergencyRequestRepository emergencyRequestRepository;
    private final java.time.Clock clock;
    private final DistributedLockService lockService;
    private final WebSocketEventPublisher wsEventPublisher;
    private final CareQueueEventPublisher kafkaEventPublisher;
    private final AuditLogService auditLogService;

    /**
     * Concurrency-safe atomic token generation mechanism.
     *
     * Uses distributed locking (Redis, with a local-JVM fallback — see
     * DistributedLockService) with the DB's uk_doctor_date_token unique constraint as an
     * ACID safety net. Convenience overload for the common case: priorityRank is derived
     * directly from the QueuePriority enum's own rank.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QueueEntry createQueueEntryForAppointment(Doctor doctor, Patient patient, LocalDate scheduleDate, QueuePriority priority) {
        return createQueueEntryForAppointment(doctor, patient, scheduleDate, priority, priority.getRank());
    }

    /**
     * Overload that accepts an explicit priorityRank, used by emergency triage so
     * different severities can be ordered relative to each other (see
     * resolveEmergencyPriorityRank) while every entry still carries priority=CRITICAL for
     * all the existing "is this an emergency" checks elsewhere in this class.
     *
     * Concurrency correctness, fixed from the previous implementation:
     *  1. The previous code captured `acquireLock(...)`'s return value but never checked
     *     it — if the lock was genuinely contended (acquireLock returns false), the method
     *     proceeded exactly as if it held the lock. That made the lock decorative in the
     *     one case it exists to handle.
     *  2. The previous code released the lock in a `finally` block *inside* this
     *     @Transactional method. Spring's transactional proxy commits the transaction
     *     only AFTER this method returns — so the lock was released before the INSERT was
     *     actually durable/visible to other transactions. Under READ_COMMITTED, a second
     *     caller could acquire the now-free lock, run findMaxTokenNumber(), and — because
     *     the first transaction hadn't committed yet — compute the exact same maxToken,
     *     producing a duplicate token_number that only failed at the DB unique-constraint
     *     level with an unhandled exception (a hard failure for one of the two callers,
     *     not safe serialization).
     *  3. The method was also marked `synchronized`. That does nothing across multiple
     *     JVM instances (this app is deployed with Docker Compose alongside Redis
     *     specifically to support more than one instance), and even within one instance it
     *     serialized token generation for EVERY doctor and date on one single global
     *     monitor — a needless throughput bottleneck — while still not actually closing
     *     the race in point 2, since the monitor is released before the transaction
     *     commits too.
     *
     * Fix: check `acquired` and fail fast (ConflictException, HTTP 409) instead of
     * silently proceeding unlocked; drop `synchronized`; and release the lock from a
     * transaction-completion callback (registerSynchronization#afterCompletion) instead of
     * a `finally` block, so the lock is held for the entire transaction's lifetime —
     * including commit — not just for this method's call stack.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QueueEntry createQueueEntryForAppointment(
        Doctor doctor, Patient patient, LocalDate scheduleDate, QueuePriority priority, int priorityRank
    ) {
        String tokenLockKey = String.format("lock:token-gen:%d:%s", doctor.getId(), scheduleDate);

        acquireTransactionScopedLock(tokenLockKey);

        int maxToken = queueEntryRepository.findMaxTokenNumber(doctor.getId(), scheduleDate);
        int nextToken = maxToken + 1;

        String prefix = (priority == QueuePriority.CRITICAL) ? "E" : "A";
        String formattedToken = String.format("%s-%03d", prefix, nextToken);

        QueueEntry queueEntry = QueueEntry.builder()
            .doctor(doctor)
            .patient(patient)
            .scheduleDate(scheduleDate)
            .tokenNumber(nextToken)
            .tokenFormatted(formattedToken)
            .priority(priority)
            .priorityRank(priorityRank)
            .status(QueueStatus.WAITING)
            .estimatedWaitMinutes(calculateEstimatedWaitMinutes(doctor.getId(), scheduleDate, nextToken, priorityRank))
            .build();

        return queueEntryRepository.saveAndFlush(queueEntry);
    }

    /**
     * Thin wrapper kept for call-site readability; the actual transaction-scoped acquire +
     * deferred-release logic now lives once, in DistributedLockService.acquireForCurrentTransaction,
     * shared with AppointmentService's slot lock so both places get the same fix instead of two
     * copies that could drift apart.
     */
    private void acquireTransactionScopedLock(String lockKey) {
        lockService.acquireForCurrentTransaction(lockKey, LOCK_TIMEOUT_SECONDS, LOCK_ACQUIRE_ATTEMPTS,
            "This doctor's queue is being updated by another request right now. Please try again.");
    }

    @Transactional
    public QueueEntry saveQueueEntry(QueueEntry qe) {
        return queueEntryRepository.save(qe);
    }

    public Optional<QueueEntry> findQueueEntryByAppointmentId(Long apptId) {
        return queueEntryRepository.findByAppointmentId(apptId);
    }

    @Transactional
    public void cancelQueueEntryForAppointment(Long apptId) {
        queueEntryRepository.findByAppointmentId(apptId).ifPresent(qe -> {
            qe.setStatus(QueueStatus.SKIPPED);
            queueEntryRepository.save(qe);
            broadcastDoctorQueue(qe.getDoctor().getId(), qe.getScheduleDate());
        });
    }

    /**
     * Doctor Action: Call Next Patient (Strictly honors backend priority: Emergency cases first!)
     */
    @Transactional
    public DoctorQueueOverviewResponse callNextPatient(Long doctorId) {
        LocalDate today = LocalDate.now(clock);
        Doctor doctor = doctorRepository.findById(doctorId)
            .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + doctorId));

        // Serialize call-next per doctor/day. Without this, a double-click or two terminals
        // could both read the same head-of-queue row and both "call" it (duplicate WebSocket
        // + Kafka events), or call two different patients at once.
        acquireTransactionScopedLock(String.format("lock:call-next:%d:%s", doctorId, today));

        boolean hasActivePatient =
            queueEntryRepository.countByDoctorIdAndScheduleDateAndStatus(doctorId, today, QueueStatus.CALLED) > 0
            || queueEntryRepository.countByDoctorIdAndScheduleDateAndStatus(doctorId, today, QueueStatus.IN_CONSULTATION) > 0;
        if (hasActivePatient) {
            throw new BadRequestException("A patient is already called or in consultation. Complete, skip or mark no-show before calling the next patient.");
        }

        List<QueueEntry> waitingQueue = queueEntryRepository.findWaitingQueue(doctorId, today);
        if (waitingQueue.isEmpty()) {
            throw new BadRequestException("No waiting patients currently in the queue for Dr. " + doctor.getUser().getFullName());
        }

        // Top candidate is the highest priority rank (Emergency = 1, Priority = 2, Normal = 3)
        QueueEntry nextCandidate = waitingQueue.get(0);
        nextCandidate.setStatus(QueueStatus.CALLED);
        nextCandidate.setCalledAt(LocalDateTime.now(clock));
        queueEntryRepository.save(nextCandidate);

        // Update appointment status if linked
        if (nextCandidate.getAppointment() != null) {
            Appointment appt = nextCandidate.getAppointment();
            appt.setStatus(AppointmentStatus.IN_QUEUE);
            appointmentRepository.save(appt);
        }

        // Trigger notifications and WebSocket updates
        kafkaEventPublisher.publishTokenCalled(CareQueueEvents.TokenCalledEvent.builder()
            .queueEntryId(nextCandidate.getId())
            .doctorId(doctorId)
            .doctorName(doctor.getUser().getFullName())
            .roomNumber(doctor.getRoomNumber())
            .patientId(nextCandidate.getPatient().getId())
            .patientUserId(nextCandidate.getPatient().getUser().getId())
            .tokenFormatted(nextCandidate.getTokenFormatted())
            .build());

        // Send a DTO, never the JPA entity: the entity graph holds uninitialized Hibernate proxies
        // (Jackson cannot serialize them, which made call-next throw and roll back) and, via
        // patient.user, the account's password hash.
        wsEventPublisher.publishPatientNotification(
            nextCandidate.getPatient().getId(), "YOUR TOKEN IS CALLED",
            mapToQueueEntryResponse(nextCandidate, 0, true, false));

        return broadcastDoctorQueue(doctorId, today);
    }

    /**
     * Doctor Action: Start Consultation
     */
    @Transactional
    public DoctorQueueOverviewResponse startConsultation(Long doctorId, Long queueEntryId) {
        QueueEntry qe = queueEntryRepository.findById(queueEntryId)
            .orElseThrow(() -> new ResourceNotFoundException("Queue entry not found: " + queueEntryId));
        assertBelongsToDoctor(qe, doctorId);

        qe.setStatus(QueueStatus.IN_CONSULTATION);
        qe.setStartedAt(LocalDateTime.now(clock));
        queueEntryRepository.save(qe);

        if (qe.getAppointment() != null) {
            Appointment appt = qe.getAppointment();
            appt.setStatus(AppointmentStatus.IN_CONSULTATION);
            appointmentRepository.save(appt);
        }

        return broadcastDoctorQueue(doctorId, qe.getScheduleDate());
    }

    /**
     * Doctor Action: Complete Consultation
     */
    @Transactional
    public DoctorQueueOverviewResponse completeConsultation(Long doctorId, Long queueEntryId, ConsultationActionRequest request) {
        QueueEntry qe = queueEntryRepository.findById(queueEntryId)
            .orElseThrow(() -> new ResourceNotFoundException("Queue entry not found: " + queueEntryId));
        assertBelongsToDoctor(qe, doctorId);

        qe.setStatus(QueueStatus.COMPLETED);
        qe.setCompletedAt(LocalDateTime.now(clock));
        queueEntryRepository.save(qe);

        if (qe.getAppointment() != null) {
            Appointment appt = qe.getAppointment();
            appt.setStatus(AppointmentStatus.COMPLETED);
            appointmentRepository.save(appt);
        }

        // Record clinical consultation record
        if (request != null) {
            Consultation consultation = Consultation.builder()
                .queueEntry(qe)
                .appointment(qe.getAppointment())
                .doctor(qe.getDoctor())
                .patient(qe.getPatient())
                .primaryDiagnosis(request.getPrimaryDiagnosis())
                .clinicalNotes(request.getClinicalNotes())
                .prescriptionSummary(request.getPrescriptionSummary())
                .followUpDate(request.getFollowUpDate())
                .build();
            consultationRepository.save(consultation);
        }

        auditLogService.record(
            "DOCTOR_COMPLETED_CONSULTATION",
            "QUEUE_ENTRY",
            String.valueOf(qe.getId()),
            "Token " + qe.getTokenFormatted() + " consultation completed by Dr. " + qe.getDoctor().getUser().getFullName()
        );

        return broadcastDoctorQueue(doctorId, qe.getScheduleDate());
    }

    /**
     * Doctor Action: Skip Patient
     */
    @Transactional
    public DoctorQueueOverviewResponse skipPatient(Long doctorId, Long queueEntryId) {
        QueueEntry qe = queueEntryRepository.findById(queueEntryId)
            .orElseThrow(() -> new ResourceNotFoundException("Queue entry not found: " + queueEntryId));
        assertBelongsToDoctor(qe, doctorId);

        qe.setStatus(QueueStatus.SKIPPED);
        queueEntryRepository.save(qe);

        return broadcastDoctorQueue(doctorId, qe.getScheduleDate());
    }

    /**
     * Doctor Action: Mark No-Show
     */
    @Transactional
    public DoctorQueueOverviewResponse markNoShow(Long doctorId, Long queueEntryId) {
        QueueEntry qe = queueEntryRepository.findById(queueEntryId)
            .orElseThrow(() -> new ResourceNotFoundException("Queue entry not found: " + queueEntryId));
        assertBelongsToDoctor(qe, doctorId);

        qe.setStatus(QueueStatus.NO_SHOW);
        queueEntryRepository.save(qe);

        if (qe.getAppointment() != null) {
            Appointment appt = qe.getAppointment();
            appt.setStatus(AppointmentStatus.NO_SHOW);
            appointmentRepository.save(appt);
        }

        auditLogService.record(
            "NO_SHOW_MARKED",
            "QUEUE_ENTRY",
            String.valueOf(qe.getId()),
            "Token " + qe.getTokenFormatted() + " marked no-show by Dr. " + qe.getDoctor().getUser().getFullName()
        );

        return broadcastDoctorQueue(doctorId, qe.getScheduleDate());
    }

    /**
     * HTTP-facing check-in. Returns a DTO built inside the transaction.
     *
     * Returning the QueueEntry entity from the controller fails with "could not initialize proxy
     * ... no Session": spring.jpa.open-in-view is false and doctor / patient / appointment are lazy,
     * so Jackson hits an uninitialized proxy after the transaction has closed. The same pattern was
     * fixed earlier for the emergency list.
     */
    @Transactional
    public QueueEntryResponse checkInPatientForResponse(Long appointmentId) {
        QueueEntry qe = checkInPatient(appointmentId);
        return mapToQueueEntryResponse(qe, 0, false, false);
    }

    /**
     * Receptionist Action: Check-in patient upon physical hospital arrival
     */
    @Transactional
    public QueueEntry checkInPatient(Long appointmentId) {
        Appointment appt = appointmentRepository.findById(appointmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + appointmentId));

        // Only a confirmed appointment for today can be checked in.
        if (appt.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new BadRequestException("Only a confirmed appointment can be checked in (current status: " + appt.getStatus() + ").");
        }
        LocalDate today = LocalDate.now(clock);
        if (!appt.getAppointmentDate().equals(today)) {
            throw new BadRequestException("Check-in is only possible on the appointment date (" + appt.getAppointmentDate() + ").");
        }
        LocalDateTime checkedInAt = LocalDateTime.now(clock);
        appt.setStatus(AppointmentStatus.CHECKED_IN);
        appt.setCheckedInAt(checkedInAt);
        appointmentRepository.save(appt);

        QueueEntry qe = queueEntryRepository.findByAppointmentId(appointmentId)
            .orElseGet(() -> createQueueEntryForAppointment(
                appt.getDoctor(), appt.getPatient(), appt.getAppointmentDate(), QueuePriority.NORMAL
            ));

        qe.setStatus(QueueStatus.WAITING);
        qe.setCheckedInAt(checkedInAt);
        qe = queueEntryRepository.save(qe);

        auditLogService.record(
            "PATIENT_CHECKED_IN",
            "APPOINTMENT",
            appt.getAppointmentNumber(),
            "Checked in " + appt.getPatient().getUser().getFullName() + ", token " + qe.getTokenFormatted()
        );

        broadcastDoctorQueue(appt.getDoctor().getId(), appt.getAppointmentDate());
        return qe;
    }

    /** Controller-facing variant: returns a DTO instead of the JPA entity graph. */
    @Transactional
    public QueueEntryResponse triageEmergency(EmergencyTriageRequest request) {
        return mapToQueueEntryResponse(triageEmergencyRequest(request), 0, false, false);
    }

    /**
     * Emergency Intake: generates an E-xxx token and inserts the patient ahead of every
     * non-emergency entry, ordered relative to other emergencies by severity
     * (CRITICAL, then HIGH, then MODERATE, then arrival/token order within a severity).
     *
     * Fixed here versus the previous implementation:
     *  - The walk-in User and Patient were built in memory but never saved, then handed to
     *    a QueueEntry whose patient_id is non-nullable with no cascade. Hibernate rejects
     *    that (transient instance referenced by a persistent one), so this flow could not
     *    complete. Both are now persisted before the queue entry is created.
     *  - The placeholder password was stored as a plain-text literal. It is now a random
     *    value run through the PasswordEncoder (this account is never meant to log in).
     *  - Every severity used to map to priorityRank 1, so severity had no effect on
     *    ordering. See resolveEmergencyPriorityRank.
     *  - hospitalId in the request was accepted but never checked against the doctor.
     */
    @Transactional
    public QueueEntry triageEmergencyRequest(EmergencyTriageRequest request) {
        Doctor doctor;
        if (request.getDoctorId() != null) {
            doctor = doctorRepository.findById(request.getDoctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found: " + request.getDoctorId()));
            if (!doctor.isActive()) {
                throw new BadRequestException("The selected doctor is not active and cannot receive emergency patients.");
            }
        } else {
            List<Doctor> deptDocs = doctorRepository.findByDepartmentIdAndActiveTrue(request.getDepartmentId());
            if (deptDocs.isEmpty()) {
                throw new BadRequestException("No available on-duty doctors in this department for emergency triage.");
            }
            doctor = deptDocs.get(0);
        }

        if (request.getHospitalId() != null && !request.getHospitalId().equals(doctor.getHospital().getId())) {
            throw new BadRequestException("The selected doctor does not belong to the given hospital.");
        }

        Patient patient = findOrCreateEmergencyPatient(request);

        // Same clock as call-next, so an emergency lands in the queue that call-next reads.
        LocalDate today = LocalDate.now(clock);

        int priorityRank = resolveEmergencyPriorityRank(request.getSeverity());
        QueueEntry emergencyEntry = createQueueEntryForAppointment(
            doctor, patient, today, QueuePriority.CRITICAL, priorityRank
        );

        // Record emergency request
        EmergencyRequest er = EmergencyRequest.builder()
            .hospital(doctor.getHospital())
            .department(doctor.getDepartment())
            .doctor(doctor)
            .patientName(request.getPatientName())
            .patientPhone(request.getPatientPhone())
            .triageCondition(request.getTriageCondition())
            .severity(request.getSeverity())
            .tokenFormatted(emergencyEntry.getTokenFormatted())
            .status("TRIAGED_PRIORITY_ONE")
            .build();
        emergencyRequestRepository.save(er);

        // Broadcast Red Alert
        kafkaEventPublisher.publishEmergencyAlert(CareQueueEvents.EmergencyAlertEvent.builder()
            .emergencyRequestId(er.getId())
            .patientName(request.getPatientName())
            .triageCondition(request.getTriageCondition())
            .severity(request.getSeverity().name())
            .tokenFormatted(emergencyEntry.getTokenFormatted())
            .departmentName(doctor.getDepartment().getName())
            .doctorUserId(doctor.getUser().getId())
            .build());

        wsEventPublisher.publishEmergencyAlert(mapToQueueEntryResponse(emergencyEntry, 0, false, false));
        broadcastDoctorQueue(doctor.getId(), today);

        return emergencyEntry;
    }

    /**
     * Live Queue Overview for Doctor Station
     */
    @Transactional(readOnly = true)
    public DoctorQueueOverviewResponse getDoctorQueueOverview(Long doctorId, LocalDate date) {
        Doctor doctor = doctorRepository.findById(doctorId)
            .orElseThrow(() -> new ResourceNotFoundException("Doctor not found: " + doctorId));

        List<QueueEntry> allToday = queueEntryRepository
            .findByDoctorIdAndScheduleDateOrderByPriorityRankAscTokenNumberAsc(doctorId, date);

        QueueEntry current = allToday.stream()
            .filter(q -> q.getStatus() == QueueStatus.IN_CONSULTATION || q.getStatus() == QueueStatus.CALLED)
            .findFirst().orElse(null);

        List<QueueEntry> waiting = allToday.stream()
            .filter(q -> q.getStatus() == QueueStatus.WAITING)
            .collect(Collectors.toList());

        QueueEntry next = waiting.isEmpty() ? null : waiting.get(0);

        List<QueueEntry> completed = allToday.stream()
            .filter(q -> q.getStatus() == QueueStatus.COMPLETED)
            .collect(Collectors.toList());

        int waitingCount = waiting.size();
        int inConsultationCount = (int) allToday.stream().filter(q -> q.getStatus() == QueueStatus.IN_CONSULTATION).count();
        int completedCount = completed.size();
        int skippedCount = (int) allToday.stream().filter(q -> q.getStatus() == QueueStatus.SKIPPED).count();
        int emergencyCount = (int) allToday.stream().filter(q -> q.getPriority() == QueuePriority.CRITICAL).count();
        int noShowCount = (int) allToday.stream().filter(q -> q.getStatus() == QueueStatus.NO_SHOW).count();

        return DoctorQueueOverviewResponse.builder()
            .doctorId(doctor.getId())
            .doctorName(doctor.getUser().getFullName())
            .specialization(doctor.getSpecialization())
            .roomNumber(doctor.getRoomNumber())
            .departmentName(doctor.getDepartment().getName())
            .date(date)
            .currentPatient(current != null ? mapToQueueEntryResponse(current, 0, true, false) : null)
            .nextPatient(next != null ? mapToQueueEntryResponse(next, 1, false, true) : null)
            .totalPatientsToday(allToday.size())
            .waitingCount(waitingCount)
            .inConsultationCount(inConsultationCount)
            .completedCount(completedCount)
            .skippedCount(skippedCount)
            .emergencyCount(emergencyCount)
            .noShowCount(noShowCount)
            .waitingQueue(java.util.stream.IntStream.range(0, waiting.size())
                .mapToObj(i -> mapToQueueEntryResponse(waiting.get(i), i + (current != null ? 1 : 0), false, i == 0))
                .collect(Collectors.toList()))
            .completedQueue(completed.stream().map(q -> mapToQueueEntryResponse(q, 0, false, false)).collect(Collectors.toList()))
            .build();
    }

    /**
     * Patient Live Queue Response with Timeline
     */
    @Transactional(readOnly = true)
    /**
     * Live queue status for one appointment, with explicit per-caller authorization:
     *  - operational staff (RECEPTIONIST / ADMIN) may view any appointment;
     *  - a DOCTOR may view only appointments assigned to their own doctor profile;
     *  - a PATIENT may view only their own appointment.
     * Previously any DOCTOR was treated as "staff" here, so any doctor could read any other
     * doctor's patient queue position and appointment number by changing the id in the URL.
     */
    public PatientLiveQueueResponse getPatientLiveQueue(Long appointmentId, Long callerPatientId,
                                                        Long callerDoctorId, boolean isOperationalStaff) {
        Appointment appt = appointmentRepository.findById(appointmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + appointmentId));

        boolean ownPatient = callerPatientId != null && callerPatientId.equals(appt.getPatient().getId());
        boolean assignedDoctor = callerDoctorId != null && callerDoctorId.equals(appt.getDoctor().getId());
        if (!isOperationalStaff && !ownPatient && !assignedDoctor) {
            throw new AccessDeniedException("You can only view queue status for your own appointments.");
        }

        QueueEntry myEntry = queueEntryRepository.findByAppointmentId(appointmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Queue entry not found for appointment: " + appointmentId));

        Doctor doctor = appt.getDoctor();
        LocalDate date = appt.getAppointmentDate();

        List<QueueEntry> allEntries = queueEntryRepository
            .findByDoctorIdAndScheduleDateOrderByPriorityRankAscTokenNumberAsc(doctor.getId(), date);

        QueueEntry current = allEntries.stream()
            .filter(q -> q.getStatus() == QueueStatus.IN_CONSULTATION || q.getStatus() == QueueStatus.CALLED)
            .findFirst().orElse(null);

        List<QueueEntry> waiting = allEntries.stream()
            .filter(q -> q.getStatus() == QueueStatus.WAITING)
            .collect(Collectors.toList());

        QueueEntry next = waiting.isEmpty() ? null : waiting.get(0);

        // Calculate people ahead
        int peopleAhead = 0;
        if (myEntry.getStatus() == QueueStatus.WAITING) {
            for (QueueEntry entry : waiting) {
                if (entry.getId().equals(myEntry.getId())) break;
                peopleAhead++;
            }
            if (current != null) {
                peopleAhead += 1;
            }
        }

        int avgMinutes = doctor.getAverageConsultationMinutes() != null ? doctor.getAverageConsultationMinutes() : 15;
        int estimatedWait = peopleAhead * avgMinutes;

        // Build clean timeline
        List<QueueTimelineStep> timeline = new ArrayList<>();
        for (QueueEntry entry : allEntries) {
            String label = "Waiting";
            if (entry.getStatus() == QueueStatus.COMPLETED) label = "Completed";
            else if (entry.getStatus() == QueueStatus.CALLED) label = "Called";
            else if (entry.getStatus() == QueueStatus.IN_CONSULTATION) label = "Current";
            else if (next != null && entry.getId().equals(next.getId())) label = "Next";

            if (entry.getId().equals(myEntry.getId())) {
                label = label + " (You)";
            }

            timeline.add(QueueTimelineStep.builder()
                .tokenFormatted(entry.getTokenFormatted())
                .label(label)
                .status(entry.getStatus())
                .isUserToken(entry.getId().equals(myEntry.getId()))
                .isEmergency(entry.getPriority() == QueuePriority.CRITICAL)
                .build());
        }

        return PatientLiveQueueResponse.builder()
            .appointmentId(appt.getId())
            .appointmentNumber(appt.getAppointmentNumber())
            .yourToken(myEntry.getTokenFormatted())
            .currentToken(current != null ? current.getTokenFormatted() : "--")
            .nextToken(next != null ? next.getTokenFormatted() : "--")
            .peopleAhead(peopleAhead)
            .estimatedWaitMinutes(estimatedWait)
            .status(myEntry.getStatus())
            .doctorName(doctor.getUser().getFullName())
            .departmentName(doctor.getDepartment().getName())
            .roomNumber(doctor.getRoomNumber())
            .appointmentDate(appt.getAppointmentDate())
            .appointmentTime(appt.getAppointmentTime())
            .timeline(timeline)
            .build();
    }

    /**
     * Estimated wait for a queue position identified by (priorityRank, tokenNumber).
     * Previously this counted waiting entries with a lower tokenNumber only, which
     * ignores priority: a newly triaged emergency (highest token, first in line) was
     * shown as waiting behind everyone, and normal patients ignored emergencies that
     * would be seen before them. Ordering now matches the real queue order
     * (priorityRank ASC, tokenNumber ASC).
     */
    public int calculateEstimatedWaitMinutes(Long doctorId, LocalDate scheduleDate, Integer tokenNumber, int priorityRank) {
        Doctor doc = doctorRepository.findById(doctorId).orElse(null);
        int avgMinutes = (doc != null && doc.getAverageConsultationMinutes() != null) ? doc.getAverageConsultationMinutes() : 15;
        long waitingAhead = queueEntryRepository.findByDoctorIdAndScheduleDateAndStatusOrderByPriorityRankAscTokenNumberAsc(
            doctorId, scheduleDate, QueueStatus.WAITING
        ).stream().filter(q -> q.getPriorityRank() < priorityRank
            || (q.getPriorityRank() == priorityRank && q.getTokenNumber() < tokenNumber)).count();

        return (int) waitingAhead * avgMinutes;
    }

    /** Back-compat overload for callers that book normal appointments (rank NORMAL). */
    public int calculateEstimatedWaitMinutes(Long doctorId, LocalDate scheduleDate, Integer tokenNumber) {
        return calculateEstimatedWaitMinutes(doctorId, scheduleDate, tokenNumber, QueuePriority.NORMAL.getRank());
    }

    private DoctorQueueOverviewResponse broadcastDoctorQueue(Long doctorId, LocalDate date) {
        DoctorQueueOverviewResponse overview = getDoctorQueueOverview(doctorId, date);
        // Full overview (patient names + phone numbers): staff-only topic, enforced by
        // WebSocketAuthInterceptor at SUBSCRIBE time, not just here at publish time.
        wsEventPublisher.publishQueueUpdate(doctorId, overview);
        // Sanitized board (token numbers/status only): safe for any authenticated
        // subscriber, including the patients actually waiting in this queue.
        wsEventPublisher.publishQueueBoardUpdate(doctorId, toBoardView(overview));
        return overview;
    }

    /**
     * Waiting-room "queue board" view for GET /api/queue/doctor/{doctorId}/board and the
     * matching WebSocket topic. See QueueBoardResponse for why patient names/phone are
     * intentionally absent.
     */
    @Transactional(readOnly = true)
    public QueueBoardResponse getDoctorQueueBoard(Long doctorId, LocalDate date) {
        return toBoardView(getDoctorQueueOverview(doctorId, date));
    }

    private QueueBoardResponse toBoardView(DoctorQueueOverviewResponse overview) {
        return QueueBoardResponse.builder()
            .doctorId(overview.getDoctorId())
            .doctorName(overview.getDoctorName())
            .departmentName(overview.getDepartmentName())
            .roomNumber(overview.getRoomNumber())
            .date(overview.getDate())
            .currentToken(overview.getCurrentPatient() != null ? overview.getCurrentPatient().getTokenFormatted() : null)
            .nextToken(overview.getNextPatient() != null ? overview.getNextPatient().getTokenFormatted() : null)
            .waitingCount(overview.getWaitingCount())
            .emergencyCount(overview.getEmergencyCount())
            .waitingTokens(overview.getWaitingQueue().stream()
                .map(q -> QueueBoardEntryResponse.builder()
                    .tokenFormatted(q.getTokenFormatted())
                    .status(q.getStatus())
                    .isEmergency(q.isEmergency())
                    .build())
                .collect(Collectors.toList()))
            .build();
    }

    /**
     * Ordering among emergencies. Every emergency keeps priority=CRITICAL (so token prefix
     * "E", emergency counts and isEmergency flags are unchanged) but gets a distinct
     * priorityRank by severity. Negative values sort ahead of PRIORITY(2) and NORMAL(3)
     * without renumbering existing data. Same severity falls back to token (arrival) order.
     */
    static int resolveEmergencyPriorityRank(EmergencySeverity severity) {
        if (severity == null) {
            return -1;
        }
        return switch (severity) {
            case CRITICAL -> -3;
            case HIGH -> -2;
            case MODERATE -> -1;
        };
    }

    /**
     * Reuses an existing PATIENT account matched by phone (so a repeat emergency does not
     * fragment the person's identity); otherwise creates and PERSISTS a walk-in user and
     * patient. The generated account cannot be logged into (random encoded password).
     */
    private Patient findOrCreateEmergencyPatient(EmergencyTriageRequest request) {
        if (StringUtils.hasText(request.getPatientPhone())) {
            Optional<User> existing = userRepository.findFirstByPhoneNumberAndRole(request.getPatientPhone(), Role.PATIENT);
            if (existing.isPresent()) {
                Optional<Patient> patient = patientRepository.findByUserId(existing.get().getId());
                if (patient.isPresent()) {
                    return patient.get();
                }
            }
        }
        User user = userRepository.save(User.builder()
            .email("emergency." + UUID.randomUUID() + "@carequeue.local")
            .password(passwordEncoder.encode(UUID.randomUUID().toString()))
            .fullName(request.getPatientName())
            .phoneNumber(request.getPatientPhone())
            .role(Role.PATIENT)
            .active(true)
            .build());
        return patientRepository.save(Patient.builder()
            .user(user)
            .medicalHistorySummary("Emergency triage: " + request.getTriageCondition())
            .build());
    }

    /**
     * Defense in depth: even if a caller is authorized to act on SOME doctor's queue
     * (e.g. the controller-level self-or-admin check), the queueEntryId in the URL
     * must actually belong to the doctorId also in the URL. Without this, a mismatched
     * doctorId/queueEntryId pair would silently mutate a random other doctor's patient
     * and broadcast the update to the wrong doctor's live dashboard.
     */
    private void assertBelongsToDoctor(QueueEntry qe, Long doctorId) {
        if (!qe.getDoctor().getId().equals(doctorId)) {
            throw new ResourceNotFoundException("Queue entry not found: " + qe.getId());
        }
    }

    private QueueEntryResponse mapToQueueEntryResponse(QueueEntry qe, int ahead, boolean isCurrent, boolean isNext) {
        return QueueEntryResponse.builder()
            .id(qe.getId())
            .appointmentId(qe.getAppointment() != null ? qe.getAppointment().getId() : null)
            .appointmentNumber(qe.getAppointment() != null ? qe.getAppointment().getAppointmentNumber() : "WALK-IN")
            .doctorId(qe.getDoctor().getId())
            .doctorName(qe.getDoctor().getUser().getFullName())
            .patientId(qe.getPatient().getId())
            .patientName(qe.getPatient().getUser() != null ? qe.getPatient().getUser().getFullName() : "Emergency Patient")
            .patientPhone(qe.getPatient().getUser() != null ? qe.getPatient().getUser().getPhoneNumber() : "")
            .scheduleDate(qe.getScheduleDate())
            .tokenNumber(qe.getTokenNumber())
            .tokenFormatted(qe.getTokenFormatted())
            .priority(qe.getPriority())
            .priorityRank(qe.getPriorityRank())
            .status(qe.getStatus())
            .peopleAhead(ahead)
            .estimatedWaitMinutes(qe.getEstimatedWaitMinutes())
            .isCurrent(isCurrent)
            .isNext(isNext)
            .isEmergency(qe.getPriority() == QueuePriority.CRITICAL)
            .calledAt(qe.getCalledAt())
            .startedAt(qe.getStartedAt())
            .completedAt(qe.getCompletedAt())
            .roomNumber(qe.getDoctor().getRoomNumber())
            .build();
    }
}
