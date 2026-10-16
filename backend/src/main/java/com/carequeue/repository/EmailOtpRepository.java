package com.carequeue.repository;

import com.carequeue.entity.EmailOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface EmailOtpRepository extends JpaRepository<EmailOtp, Long> {

    Optional<EmailOtp> findFirstByEmailAndPurposeAndConsumedFalseOrderByCreatedAtDesc(String email, String purpose);

    Optional<EmailOtp> findFirstByEmailAndPurposeOrderByCreatedAtDesc(String email, String purpose);

    Optional<EmailOtp> findFirstByEmailAndPurposeAndVerifiedTrueOrderByCreatedAtDesc(String email, String purpose);

    long countByEmailAndPurposeAndCreatedAtAfter(String email, String purpose, LocalDateTime after);
}
