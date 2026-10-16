package com.carequeue.controller;

import com.carequeue.entity.Patient;
import com.carequeue.repository.PatientRepository;
import com.carequeue.service.WalkInPatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lets reception and admin find an existing patient, or register a walk-in patient, for a booking. */
@RestController
@RequestMapping("/api/staff/patients")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('RECEPTIONIST','ADMIN')")
public class StaffPatientController {

    private final PatientRepository patientRepository;
    private final WalkInPatientService walkInPatientService;

    @GetMapping
    public List<Map<String, Object>> search(@RequestParam(defaultValue = "") String q) {
        String term = q.trim();
        if (term.length() < 2) {
            return List.of();
        }
        return patientRepository.searchForStaff(term, PageRequest.of(0, 20)).stream()
            .map(this::toSummary)
            .toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> registerWalkIn(
            @Valid @RequestBody WalkInPatientService.WalkInPatientRequest request) {
        return ResponseEntity.ok(walkInPatientService.register(request));
    }

    private Map<String, Object> toSummary(Patient p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("fullName", p.getUser().getFullName());
        row.put("phone", p.getUser().getPhoneNumber() == null ? "" : p.getUser().getPhoneNumber());
        row.put("email", p.getUser().getEmail() == null ? "" : p.getUser().getEmail());
        return row;
    }
}
