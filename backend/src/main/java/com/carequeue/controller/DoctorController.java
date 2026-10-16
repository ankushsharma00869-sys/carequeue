package com.carequeue.controller;

import com.carequeue.dto.HospitalDoctorDtos.*;
import com.carequeue.service.DoctorScheduleService;
import com.carequeue.service.HospitalService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DoctorController {

    private final HospitalService hospitalService;
    private final DoctorScheduleService doctorScheduleService;
    private final java.time.Clock clock;

    @GetMapping("/doctors")
    public ResponseEntity<List<DoctorDto>> getAllDoctors() {
        return ResponseEntity.ok(hospitalService.getAllActiveDoctors());
    }

    @GetMapping("/doctors/{id}")
    public ResponseEntity<DoctorDto> getDoctorById(@PathVariable Long id) {
        return ResponseEntity.ok(hospitalService.getDoctorById(id));
    }

    @GetMapping("/departments/{id}/doctors")
    public ResponseEntity<List<DoctorDto>> getDoctorsByDepartment(@PathVariable Long id) {
        return ResponseEntity.ok(hospitalService.getDoctorsByDepartment(id));
    }

    @GetMapping("/doctors/{id}/availability")
    public ResponseEntity<DoctorAvailabilityResponse> getDoctorAvailability(
        @PathVariable Long id,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate targetDate = date != null ? date : LocalDate.now(clock);
        return ResponseEntity.ok(doctorScheduleService.getDoctorAvailability(id, targetDate));
    }
}
