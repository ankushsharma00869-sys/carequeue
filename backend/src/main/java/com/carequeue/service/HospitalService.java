package com.carequeue.service;

import com.carequeue.dto.HospitalDoctorDtos.*;
import com.carequeue.entity.Department;
import com.carequeue.entity.Doctor;
import com.carequeue.entity.Hospital;
import com.carequeue.exception.ResourceNotFoundException;
import com.carequeue.repository.DepartmentRepository;
import com.carequeue.repository.DoctorRepository;
import com.carequeue.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalService {

    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final DoctorRepository doctorRepository;

    public List<HospitalDto> getAllActiveHospitals() {
        return hospitalRepository.findByActiveTrue().stream()
            .map(this::mapToHospitalDto)
            .collect(Collectors.toList());
    }

    public HospitalDto getHospitalById(Long id) {
        Hospital hospital = hospitalRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with id: " + id));
        return mapToHospitalDto(hospital);
    }

    public List<DepartmentDto> getDepartmentsByHospital(Long hospitalId) {
        return departmentRepository.findByHospitalIdAndActiveTrue(hospitalId).stream()
            .map(this::mapToDepartmentDto)
            .collect(Collectors.toList());
    }

    public List<DepartmentDto> getAllDepartments() {
        return departmentRepository.findByActiveTrue().stream()
            .map(this::mapToDepartmentDto)
            .collect(Collectors.toList());
    }

    public List<DoctorDto> getDoctorsByDepartment(Long departmentId) {
        return doctorRepository.findByDepartmentIdAndActiveTrue(departmentId).stream()
            .map(this::mapToDoctorDto)
            .collect(Collectors.toList());
    }

    public List<DoctorDto> getAllActiveDoctors() {
        return doctorRepository.findByActiveTrue().stream()
            .map(this::mapToDoctorDto)
            .collect(Collectors.toList());
    }

    public DoctorDto getDoctorById(Long id) {
        Doctor doctor = doctorRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + id));
        return mapToDoctorDto(doctor);
    }

    public HospitalDto mapToHospitalDto(Hospital h) {
        return HospitalDto.builder()
            .id(h.getId())
            .name(h.getName())
            .code(h.getCode())
            .address(h.getAddress())
            .city(h.getCity())
            .phone(h.getPhone())
            .emergencyContact(h.getEmergencyContact())
            .active(h.isActive())
            .build();
    }

    public DepartmentDto mapToDepartmentDto(Department d) {
        return DepartmentDto.builder()
            .id(d.getId())
            .hospitalId(d.getHospital().getId())
            .hospitalName(d.getHospital().getName())
            .name(d.getName())
            .code(d.getCode())
            .description(d.getDescription())
            .floorNumber(d.getFloorNumber())
            .icon(d.getIcon())
            .active(d.isActive())
            .build();
    }

    public DoctorDto mapToDoctorDto(Doctor doc) {
        return DoctorDto.builder()
            .id(doc.getId())
            .userId(doc.getUser().getId())
            .fullName(doc.getUser().getFullName())
            // Staff contact details are NOT part of the public doctor directory, which is served
            // to anonymous users (GET /api/doctors is permitAll). Left null deliberately.
            .email(null)
            .phone(null)
            .hospitalId(doc.getHospital().getId())
            .hospitalName(doc.getHospital().getName())
            .departmentId(doc.getDepartment().getId())
            .departmentName(doc.getDepartment().getName())
            .licenseNumber(doc.getLicenseNumber())
            .specialization(doc.getSpecialization())
            .roomNumber(doc.getRoomNumber())
            .consultationFee(doc.getConsultationFee())
            .bio(doc.getBio())
            .averageConsultationMinutes(doc.getAverageConsultationMinutes())
            .active(doc.isActive())
            .build();
    }
}
