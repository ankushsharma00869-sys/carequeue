package com.carequeue.repository;

import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.Query;

import com.carequeue.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, Long> {
    List<Doctor> findByDepartmentIdAndActiveTrue(Long departmentId);
    List<Doctor> findByHospitalIdAndActiveTrue(Long hospitalId);
    Optional<Doctor> findByUserId(Long userId);
    List<Doctor> findByActiveTrue();
    boolean existsByLicenseNumber(String licenseNumber);

    /** Loads the doctor with department and hospital in one query, so the profile can be built outside a transaction. */
    @Query("select d from Doctor d left join fetch d.department left join fetch d.hospital where d.id = :id")
    Optional<Doctor> findByIdWithDepartmentAndHospital(@Param("id") Long id);
}
