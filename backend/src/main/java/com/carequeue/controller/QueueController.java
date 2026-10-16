package com.carequeue.controller;

import com.carequeue.dto.QueueDtos.*;
import com.carequeue.entity.QueueEntry;
import com.carequeue.security.UserPrincipal;
import com.carequeue.service.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;
    private final java.time.Clock clock;

    /**
     * Full-detail queue overview: includes every waiting patient's name and phone number.
     * Previously had NO role restriction at all, so any authenticated user — including a
     * PATIENT account, or a different doctor entirely — could read any doctor's full
     * queue simply by changing {doctorId} in the URL. Restricted to the assigned doctor
     * (checked below, same pattern as the other doctor-scoped endpoints in this
     * controller), receptionists and admins.
     */
    @GetMapping("/doctor/{doctorId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'RECEPTIONIST', 'ADMIN')")
    public ResponseEntity<DoctorQueueOverviewResponse> getDoctorQueue(
        @PathVariable Long doctorId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        assertDoctorSelfOrAdmin(doctorId, principal);
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        return ResponseEntity.ok(queueService.getDoctorQueueOverview(doctorId, targetDate));
    }

    /**
     * Sanitized waiting-room "queue board" view — token numbers and status only, no
     * patient names or phone numbers. Any authenticated CareQueue user (patients
     * included) may call this; it is what the shared /queue-board page should use.
     */
    @GetMapping("/doctor/{doctorId}/board")
    public ResponseEntity<QueueBoardResponse> getDoctorQueueBoard(
        @PathVariable Long doctorId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        return ResponseEntity.ok(queueService.getDoctorQueueBoard(doctorId, targetDate));
    }

    @PostMapping("/doctor/{doctorId}/call-next")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ResponseEntity<DoctorQueueOverviewResponse> callNextPatient(
        @PathVariable Long doctorId,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        assertDoctorSelfOrAdmin(doctorId, principal);
        return ResponseEntity.ok(queueService.callNextPatient(doctorId));
    }

    @PostMapping("/doctor/{doctorId}/start/{queueEntryId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ResponseEntity<DoctorQueueOverviewResponse> startConsultation(
        @PathVariable Long doctorId,
        @PathVariable Long queueEntryId,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        assertDoctorSelfOrAdmin(doctorId, principal);
        return ResponseEntity.ok(queueService.startConsultation(doctorId, queueEntryId));
    }

    @PostMapping("/doctor/{doctorId}/complete/{queueEntryId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ResponseEntity<DoctorQueueOverviewResponse> completeConsultation(
        @PathVariable Long doctorId,
        @PathVariable Long queueEntryId,
        @RequestBody(required = false) ConsultationActionRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        assertDoctorSelfOrAdmin(doctorId, principal);
        return ResponseEntity.ok(queueService.completeConsultation(doctorId, queueEntryId, request));
    }

    @PostMapping("/doctor/{doctorId}/skip/{queueEntryId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ResponseEntity<DoctorQueueOverviewResponse> skipPatient(
        @PathVariable Long doctorId,
        @PathVariable Long queueEntryId,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        assertDoctorSelfOrAdmin(doctorId, principal);
        return ResponseEntity.ok(queueService.skipPatient(doctorId, queueEntryId));
    }

    @PostMapping("/doctor/{doctorId}/no-show/{queueEntryId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ResponseEntity<DoctorQueueOverviewResponse> markNoShow(
        @PathVariable Long doctorId,
        @PathVariable Long queueEntryId,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        assertDoctorSelfOrAdmin(doctorId, principal);
        return ResponseEntity.ok(queueService.markNoShow(doctorId, queueEntryId));
    }

    @GetMapping("/patient/appointment/{appointmentId}")
    public ResponseEntity<PatientLiveQueueResponse> getPatientLiveQueue(
        @PathVariable Long appointmentId,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        // Operational staff only. DOCTOR is deliberately NOT here: a doctor is authorized by the
        // service for their own assigned appointments (via principal.getDoctorId()), not globally.
        boolean isOperationalStaff = principal.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_RECEPTIONIST")
                || a.getAuthority().equals("ROLE_ADMIN"));
        return ResponseEntity.ok(
            queueService.getPatientLiveQueue(appointmentId, principal.getPatientId(),
                principal.getDoctorId(), isOperationalStaff)
        );
    }

    @PostMapping("/reception/check-in/{appointmentId}")
    @PreAuthorize("hasAnyRole('RECEPTIONIST', 'ADMIN')")
    public ResponseEntity<QueueEntryResponse> checkInPatient(@PathVariable Long appointmentId) {
        return ResponseEntity.ok(queueService.checkInPatientForResponse(appointmentId));
    }

    /**
     * A Doctor may only operate on/view their own queue; Receptionist and Admin can
     * operate on any doctor's queue for front-desk/operational purposes. Without this,
     * any authenticated Doctor account could call-next / start / complete / skip /
     * no-show — or, for getDoctorQueue, simply read — a DIFFERENT doctor's patients
     * just by changing the doctorId in the URL.
     */
    private void assertDoctorSelfOrAdmin(Long doctorId, UserPrincipal principal) {
        boolean isAdminOrReceptionist = principal.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_RECEPTIONIST"));
        if (isAdminOrReceptionist) {
            return;
        }
        if (principal.getDoctorId() == null || !principal.getDoctorId().equals(doctorId)) {
            throw new AccessDeniedException("You can only manage your own queue.");
        }
    }
}
