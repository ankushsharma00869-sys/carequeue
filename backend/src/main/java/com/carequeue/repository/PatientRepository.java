package com.carequeue.repository;

import com.carequeue.entity.Patient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PatientRepository extends JpaRepository<Patient, Long> {
    Optional<Patient> findByUserId(Long userId);

    /** Search by name, phone or email, used by reception to find a walk-in patient. */
    @Query("select p from Patient p join fetch p.user u "
        + "where lower(u.fullName) like lower(concat('%', :q, '%')) "
        + "or u.phoneNumber like concat('%', :q, '%') "
        + "or lower(u.email) like lower(concat('%', :q, '%'))")
    List<Patient> searchForStaff(@Param("q") String q, Pageable pageable);

    /** Walk-in records (no online login yet) matching a mobile number and date of birth. */
    @Query("select p from Patient p join fetch p.user u where u.walkIn = true and u.phoneNumber = :phone and p.dateOfBirth = :dob")
    List<Patient> findWalkInsByPhoneAndDob(@Param("phone") String phone, @Param("dob") LocalDate dob);

    /** Walk-in records with this date of birth. The claim service then checks email (and mobile, if given). */
    @Query("select p from Patient p join fetch p.user u where u.walkIn = true and p.dateOfBirth = :dob")
    List<Patient> findWalkInsByDob(@Param("dob") LocalDate dob);
}
