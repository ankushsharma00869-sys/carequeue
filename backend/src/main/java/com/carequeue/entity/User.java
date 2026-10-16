package com.carequeue.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_email", columnList = "email", unique = true),
    @Index(name = "idx_user_role", columnList = "role")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    /**
     * Never serialized. Any entity graph that reaches a User (Doctor.user, Patient.user,
     * EmergencyRequest.doctor.user, ...) would otherwise expose the BCrypt hash in JSON.
     * Spring Security reads this field through UserDetails in-process, which Jackson does not touch.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    // Which hospital this staff member (Receptionist, or a Doctor's login) belongs to.
    // Null for PATIENT accounts. Used to scope a Receptionist's reception-desk views to
    // their own hospital (see AdminStaffService.createReceptionist).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id")
    private Hospital hospital;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    // Walk-in accounts are created by reception for a patient with no online account.
    // They can never log in; the patient must activate the record later (Phase 3).
    // Nullable on purpose: existing rows get NULL, which means "not walk-in".
    @Column(name = "walk_in")
    private Boolean walkIn;

    public boolean isWalkIn() {
        return Boolean.TRUE.equals(walkIn);
    }

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
