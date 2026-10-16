package com.carequeue;

import com.carequeue.entity.Patient;
import com.carequeue.repository.AppointmentRepository;
import com.carequeue.service.NoShowPredictor;
import com.carequeue.service.RuleBasedNoShowPredictor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoShowRiskCalculatorTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @InjectMocks
    private RuleBasedNoShowPredictor predictor;

    @Test
    @DisplayName("Patient with >= 2 past no-shows and long lead time must trigger HIGH RISK")
    void testHighRiskPatient() {
        Patient patient = Patient.builder().id(99L).build();

        when(appointmentRepository.countNoShowsByPatientId(99L)).thenReturn(2L);
        when(appointmentRepository.countCancellationsByPatientId(99L)).thenReturn(1L);
        when(appointmentRepository.countTotalAppointmentsByPatientId(99L)).thenReturn(3L);

        LocalDate bookingDate = LocalDate.now();
        LocalDate apptDate = bookingDate.plusDays(15); // > 14 days lead time
        LocalTime apptTime = LocalTime.of(8, 0); // Early morning slot

        NoShowPredictor.RiskAssessment risk = predictor.assessRisk(patient, apptDate, apptTime, bookingDate);

        assertNotNull(risk);
        assertEquals("HIGH", risk.getRiskLevel());
        assertTrue(risk.getScore() >= 60, "High risk score must be >= 60");
        assertFalse(risk.getContributingFactors().isEmpty());
    }

    @Test
    @DisplayName("Reliable patient with zero no-shows and same-day booking must result in LOW RISK")
    void testLowRiskPatient() {
        Patient patient = Patient.builder().id(100L).build();

        when(appointmentRepository.countNoShowsByPatientId(100L)).thenReturn(0L);
        when(appointmentRepository.countCancellationsByPatientId(100L)).thenReturn(0L);
        when(appointmentRepository.countTotalAppointmentsByPatientId(100L)).thenReturn(5L);

        LocalDate bookingDate = LocalDate.now();
        LocalDate apptDate = bookingDate; // Same-day
        LocalTime apptTime = LocalTime.of(11, 0);

        NoShowPredictor.RiskAssessment risk = predictor.assessRisk(patient, apptDate, apptTime, bookingDate);

        assertNotNull(risk);
        assertEquals("LOW", risk.getRiskLevel());
        assertTrue(risk.getScore() < 35, "Low risk score must be < 35");
    }
}