package com.carequeue.controller;

import com.carequeue.dto.HospitalDoctorDtos.*;
import com.carequeue.service.HospitalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HospitalController {

    private final HospitalService hospitalService;

    @GetMapping("/hospitals")
    public ResponseEntity<List<HospitalDto>> getHospitals() {
        return ResponseEntity.ok(hospitalService.getAllActiveHospitals());
    }

    @GetMapping("/hospitals/{id}")
    public ResponseEntity<HospitalDto> getHospitalById(@PathVariable Long id) {
        return ResponseEntity.ok(hospitalService.getHospitalById(id));
    }

    @GetMapping("/hospitals/{id}/departments")
    public ResponseEntity<List<DepartmentDto>> getDepartmentsByHospital(@PathVariable Long id) {
        return ResponseEntity.ok(hospitalService.getDepartmentsByHospital(id));
    }

    @GetMapping("/departments")
    public ResponseEntity<List<DepartmentDto>> getAllDepartments() {
        return ResponseEntity.ok(hospitalService.getAllDepartments());
    }
}
