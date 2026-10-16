package com.carequeue.service;

import com.carequeue.dto.EmergencyDtos.EmergencyRequestResponse;
import com.carequeue.entity.EmergencyRequest;
import com.carequeue.repository.EmergencyRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side access to recent emergencies.
 *
 * The DTO mapping touches lazy associations (Department, Doctor -> User). It must run inside a
 * transaction: mapping in the controller, after the session has closed, fails with
 * "could not initialize proxy ... - no Session" (observed as HTTP 500 in RoleBoundarySecurityTest).
 * Doing the mapping here keeps every lazy load inside the read-only transaction.
 */
@Service
@RequiredArgsConstructor
public class EmergencyQueryService {

    private final EmergencyRequestRepository emergencyRequestRepository;

    /**
     * @param doctorId when non-null, only emergencies assigned to that doctor profile are returned.
     *                 when null, the hospital-wide recent list is returned.
     */
    @Transactional(readOnly = true)
    public List<EmergencyRequestResponse> recentEmergencies(Long doctorId) {
        List<EmergencyRequest> rows = doctorId == null
            ? emergencyRequestRepository.findTop20ByOrderByCreatedAtDesc()
            : emergencyRequestRepository.findTop20ByDoctorIdOrderByCreatedAtDesc(doctorId);
        return rows.stream().map(EmergencyRequestResponse::from).toList();
    }
}
