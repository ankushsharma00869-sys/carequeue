package com.carequeue.repository;

import com.carequeue.entity.EmergencyRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmergencyRequestRepository extends JpaRepository<EmergencyRequest, Long> {
    List<EmergencyRequest> findTop20ByOrderByCreatedAtDesc();

    /** Emergencies assigned to one doctor (doctor.id), newest first. */
    List<EmergencyRequest> findTop20ByDoctorIdOrderByCreatedAtDesc(Long doctorId);
    long countByStatus(String status);
}
