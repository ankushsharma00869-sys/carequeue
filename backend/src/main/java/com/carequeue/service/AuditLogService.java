package com.carequeue.service;

import com.carequeue.entity.AuditLog;
import com.carequeue.repository.AuditLogRepository;
import com.carequeue.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes administrative/operational audit trail entries.
 *
 * Before this class existed, AuditLogRepository was only ever read from
 * (GET /api/admin/audit-logs) — nothing in the codebase ever called .save()
 * on it, so the audit_logs table was permanently empty regardless of what
 * actions actually happened. This wires real writes in for the actions the
 * audit spec calls out by name: ADMIN_CREATED_DOCTOR, ADMIN_CREATED_RECEPTIONIST,
 * ADMIN_DEACTIVATED_DOCTOR, APPOINTMENT_CANCELLED, PATIENT_CHECKED_IN,
 * DOCTOR_COMPLETED_CONSULTATION, and so on.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    /**
     * REQUIRES_NEW so an audit write is never rolled back just because the
     * *business* transaction it's describing later fails for an unrelated
     * reason after this point, and so a failure here (e.g. transient DB
     * issue) can be caught and logged rather than aborting the action.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, String details) {
        String actorEmail = "system";
        String actorRole = "SYSTEM";

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            actorEmail = principal.getEmail();
            actorRole = principal.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .orElse("UNKNOWN");
        }

        AuditLog log = AuditLog.builder()
            .actorEmail(actorEmail)
            .actorRole(actorRole)
            .action(action)
            .resourceType(resourceType)
            .resourceId(resourceId)
            .details(details)
            .build();

        auditLogRepository.save(log);
    }
}
