package com.carequeue.repository;

import com.carequeue.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, Long> {
    List<Department> findByHospitalIdAndActiveTrue(Long hospitalId);
    List<Department> findByActiveTrue();
}
