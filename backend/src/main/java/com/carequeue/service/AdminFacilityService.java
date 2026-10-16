package com.carequeue.service;

import com.carequeue.dto.AdminFacilityDtos.CreateDepartmentRequest;
import com.carequeue.dto.AdminFacilityDtos.CreateHospitalRequest;
import com.carequeue.entity.Department;
import com.carequeue.entity.Hospital;
import com.carequeue.exception.BadRequestException;
import com.carequeue.exception.ResourceNotFoundException;
import com.carequeue.repository.DepartmentRepository;
import com.carequeue.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates hospitals and departments. Doctors, schedules and booking then work for them with no code
 * change: the patient wizard reads /api/hospitals and /api/hospitals/{id}/departments, and the admin
 * doctor form reads the same lists.
 */
@Service
@RequiredArgsConstructor
public class AdminFacilityService {

    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;

    @Transactional
    public Hospital createHospital(CreateHospitalRequest request) {
        String code = request.getCode().trim();
        if (hospitalRepository.findByCode(code).isPresent()) {
            throw new BadRequestException("Hospital code '" + code + "' is already in use. Choose another code.");
        }
        return hospitalRepository.save(Hospital.builder()
            .name(request.getName().trim())
            .code(code)
            .address(blankToNull(request.getAddress()))
            .city(blankToNull(request.getCity()))
            .phone(blankToNull(request.getPhone()))
            .emergencyContact(blankToNull(request.getEmergencyContact()))
            .active(true)
            .build());
    }

    @Transactional
    public Department createDepartment(CreateDepartmentRequest request) {
        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with id: " + request.getHospitalId()));

        String code = request.getCode().trim();
        boolean codeTaken = departmentRepository.findByHospitalIdAndActiveTrue(hospital.getId()).stream()
            .anyMatch(d -> d.getCode() != null && d.getCode().equalsIgnoreCase(code));
        if (codeTaken) {
            throw new BadRequestException("Department code '" + code + "' is already used in " + hospital.getName() + ".");
        }

        return departmentRepository.save(Department.builder()
            .hospital(hospital)
            .name(request.getName().trim())
            .code(code)
            .floorNumber(blankToNull(request.getFloorNumber()))
            .description(blankToNull(request.getDescription()))
            .active(true)
            .build());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
