package com.carequeue.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "doctors", indexes = {
    @Index(name = "idx_doctor_user", columnList = "user_id"),
    @Index(name = "idx_doctor_dept", columnList = "department_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(nullable = false, unique = true, length = 50)
    private String licenseNumber;

    @Column(nullable = false, length = 100)
    private String specialization;

    @Column(length = 20)
    private String roomNumber;

    @Column(precision = 10, scale = 2)
    private BigDecimal consultationFee;

    @Column(length = 500)
    private String bio;

    @Builder.Default
    @Column(nullable = false)
    private Integer averageConsultationMinutes = 15;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
