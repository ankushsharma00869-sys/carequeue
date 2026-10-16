package com.carequeue.repository;

import com.carequeue.entity.Role;
import com.carequeue.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    long countByRole(Role role);
    Optional<User> findFirstByPhoneNumberAndRole(String phoneNumber, Role role);
}
