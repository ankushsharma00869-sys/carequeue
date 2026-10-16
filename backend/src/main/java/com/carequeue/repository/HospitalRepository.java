package com.carequeue.repository;

import com.carequeue.entity.Hospital;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HospitalRepository extends JpaRepository<Hospital, Long> {
    List<Hospital> findByActiveTrue();
    Optional<Hospital> findByCode(String code);
}
