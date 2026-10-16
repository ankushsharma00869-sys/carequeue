package com.carequeue;

import com.carequeue.dto.AppointmentDtos.CancelAppointmentRequest;
import com.carequeue.entity.*;
import com.carequeue.event.CareQueueEventPublisher;
import com.carequeue.exception.BadRequestException;
import com.carequeue.repository.AppointmentRepository;
import com.carequeue.service.AppointmentService;
import com.carequeue.service.NoShowPredictor;
import com.carequeue.service.QueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentCancellationTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private QueueService queueService;

    @Mock
    private CareQueueEventPublisher eventPublisher;

    @Mock
    private NoShowPredictor noShowPredictor;

    @InjectMocks
    private AppointmentService appointmentService;

    @Test
    @DisplayName("Cancellation must throw BadRequestException if scheduled within 30 minutes")
    void testCancellationWindowRestriction() {
        ReflectionTestUtils.setField(appointmentService, "cancellationThresholdMinutes", 30);

        // A fixed clock, so the test does not depend on the wall clock or on midnight.
        // The service's clock is supplied explicitly: @InjectMocks cannot create it.
        Clock fixed = Clock.fixed(LocalDateTime.of(2091, 3, 15, 9, 0).atZone(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());
        ReflectionTestUtils.setField(appointmentService, "clock", fixed);

        // Appointment scheduled 10 minutes after the fixed now (inside the 30-minute lock window)
        LocalDate apptDate = LocalDate.of(2091, 3, 15);
        LocalTime apptTime = LocalTime.of(9, 10);

        Appointment appt = Appointment.builder()
            .id(1L)
            .appointmentDate(apptDate)
            .appointmentTime(apptTime)
            .status(AppointmentStatus.CONFIRMED)
            .build();

        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(appt));

        BadRequestException ex = assertThrows(BadRequestException.class, () -> {
            appointmentService.cancelAppointment(1L, new CancelAppointmentRequest("Personal"), 99L, true);
        });

        assertTrue(ex.getMessage().contains("Cancellation policy restriction"));
    }
}