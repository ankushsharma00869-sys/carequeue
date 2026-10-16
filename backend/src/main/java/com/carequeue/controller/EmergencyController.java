package com.carequeue.controller;

import com.carequeue.dto.EmergencyDtos.EmergencyRequestResponse;
import com.carequeue.dto.QueueDtos.EmergencyTriageRequest;
import com.carequeue.dto.QueueDtos.QueueEntryResponse;
import com.carequeue.security.UserPrincipal;
import com.carequeue.service.EmergencyQueryService;
import com.carequeue.service.QueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/emergency")
@RequiredArgsConstructor
public class EmergencyController {

    private final QueueService queueService;
    private final EmergencyQueryService emergencyQueryService;

    @PostMapping
    @PreAuthorize("hasAnyRole('RECEPTIONIST', 'DOCTOR', 'ADMIN')")
    public ResponseEntity<QueueEntryResponse> submitEmergency(@Valid @RequestBody EmergencyTriageRequest request) {
        return ResponseEntity.ok(queueService.triageEmergency(request));
    }

    /**
     * Receptionist and admin see the hospital-wide recent emergencies. A doctor sees only the
     * emergencies assigned to their own doctor profile. Returns DTOs (see EmergencyQueryService),
     * never entities, so no password hash or lazy graph is serialized.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('RECEPTIONIST', 'DOCTOR', 'ADMIN')")
    public ResponseEntity<List<EmergencyRequestResponse>> getEmergencies(
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        boolean isDoctor = principal.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR"));
        if (isDoctor) {
            if (principal.getDoctorId() == null) {
                return ResponseEntity.ok(List.of());
            }
            return ResponseEntity.ok(emergencyQueryService.recentEmergencies(principal.getDoctorId()));
        }
        return ResponseEntity.ok(emergencyQueryService.recentEmergencies(null));
    }
}
