package com.carequeue.controller;

import com.carequeue.dto.AppointmentDtos.*;
import com.carequeue.security.UserPrincipal;
import com.carequeue.service.AppointmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    /**
     * Defense-in-depth against a duplicate appointment_number occasionally surfacing as a
     * raw DataIntegrityViolationException under concurrent load (observed intermittently
     * in ConcurrentSlotBookingTest: 2 of 3 runs hit this on the same unique index, even
     * though AppointmentNumberGenerator's own direct-call stress test — 50 concurrent
     * calls, no enclosing transaction — never reproduces it). The exact trigger is not
     * fully isolated; this does not replace finding it, but it is correct, safe hardening
     * regardless of the cause: each retry goes through this bean's Spring proxy again
     * (NOT a same-class self-call), so it is a genuinely fresh @Transactional attempt with
     * its own session each time, matching the pattern already used in
     * AppointmentNumberGenerator.nextAppointmentNumber().
     */
    private static final int MAX_BOOKING_RETRIES = 3;

    private final AppointmentService appointmentService;
    private final java.time.Clock clock;

    @PostMapping
    public ResponseEntity<AppointmentResponse> bookAppointment(
        @Valid @RequestBody BookAppointmentRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return ResponseEntity.ok(
                    appointmentService.bookAppointment(request, principal.getId(), isStaff(principal))
                );
            } catch (DataIntegrityViolationException ex) {
                if (attempt >= MAX_BOOKING_RETRIES) {
                    log.error("Booking failed after {} attempts due to a persistent data integrity conflict", attempt);
                    throw ex;
                }
                log.warn("Booking attempt {} hit a data integrity conflict (likely a duplicate appointment number under concurrent load); retrying", attempt);
            }
        }
    }

    @GetMapping("/my")
    public ResponseEntity<List<AppointmentResponse>> getMyAppointments(
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(appointmentService.getMyAppointments(principal.getId()));
    }

    @GetMapping("/doctor/{doctorId}")
    @PreAuthorize("hasAnyRole('DOCTOR', 'RECEPTIONIST', 'ADMIN')")
    public ResponseEntity<List<AppointmentResponse>> getDoctorAppointments(
        @PathVariable Long doctorId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        boolean isDoctorRole = principal.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR"));
        if (isDoctorRole && (principal.getDoctorId() == null || !principal.getDoctorId().equals(doctorId))) {
            throw new AccessDeniedException("You can only view your own appointments.");
        }
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        return ResponseEntity.ok(appointmentService.getDoctorAppointmentsForToday(doctorId, targetDate));
    }

    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('RECEPTIONIST', 'ADMIN')")
    public ResponseEntity<List<AppointmentResponse>> getAllAppointmentsToday(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        return ResponseEntity.ok(appointmentService.getAllAppointmentsForDate(targetDate));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancelAppointment(
        @PathVariable Long id,
        @RequestBody(required = false) CancelAppointmentRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(
            appointmentService.cancelAppointment(id, request, principal.getId(), isStaff(principal))
        );
    }

    private boolean isStaff(UserPrincipal principal) {
        return principal.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_RECEPTIONIST") || a.getAuthority().equals("ROLE_ADMIN"));
    }
}
