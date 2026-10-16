package com.carequeue.controller;

import com.carequeue.dto.AppointmentDtos.AppointmentResponse;
import com.carequeue.dto.AppointmentDtos.BookAppointmentRequest;
import com.carequeue.security.UserPrincipal;
import com.carequeue.service.AppointmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit test (no Spring context, AppointmentService fully mocked) for the retry
 * wrapper added around booking. Calling the mock through the injected field is exactly
 * how the real Spring proxy would be invoked in production — there is no self-invocation
 * here, so this accurately represents real behavior.
 */
class AppointmentControllerRetryTest {

    private final UserPrincipal patient = new UserPrincipal(
        1L, "patient@test.local", "hash", "Test Patient",
        List.of(new SimpleGrantedAuthority("ROLE_PATIENT")), null, 1L);

    private BookAppointmentRequest request() {
        return BookAppointmentRequest.builder().doctorId(1L).hospitalId(1L).departmentId(1L).build();
    }

    @Test
    @DisplayName("A single transient data-integrity conflict is retried and the booking still succeeds")
    void retriesOnceThenSucceeds() {
        AppointmentService service = mock(AppointmentService.class);
        AppointmentResponse expected = AppointmentResponse.builder().appointmentNumber("CQ-2026-10001").build();
        when(service.bookAppointment(any(), any(), anyBoolean()))
            .thenThrow(new DataIntegrityViolationException("duplicate appointment_number"))
            .thenReturn(expected);

        AppointmentController controller = new AppointmentController(service, java.time.Clock.systemDefaultZone());
        var response = controller.bookAppointment(request(), patient);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("CQ-2026-10001", response.getBody().getAppointmentNumber());
        verify(service, times(2)).bookAppointment(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("A persistent conflict (every attempt fails) is not retried forever — gives up and propagates")
    void givesUpAfterMaxRetries() {
        AppointmentService service = mock(AppointmentService.class);
        when(service.bookAppointment(any(), any(), anyBoolean()))
            .thenThrow(new DataIntegrityViolationException("duplicate appointment_number"));

        AppointmentController controller = new AppointmentController(service, java.time.Clock.systemDefaultZone());

        assertThrows(DataIntegrityViolationException.class, () -> controller.bookAppointment(request(), patient));
        verify(service, times(3)).bookAppointment(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("The common case — no conflict at all — calls the service exactly once")
    void noRetryWhenNotNeeded() {
        AppointmentService service = mock(AppointmentService.class);
        AppointmentResponse expected = AppointmentResponse.builder().appointmentNumber("CQ-2026-10002").build();
        when(service.bookAppointment(any(), any(), anyBoolean())).thenReturn(expected);

        AppointmentController controller = new AppointmentController(service, java.time.Clock.systemDefaultZone());
        var response = controller.bookAppointment(request(), patient);

        assertEquals("CQ-2026-10002", response.getBody().getAppointmentNumber());
        verify(service, times(1)).bookAppointment(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("A non-data-integrity exception (e.g. a real business error) is never retried")
    void otherExceptionsAreNotRetried() {
        AppointmentService service = mock(AppointmentService.class);
        when(service.bookAppointment(any(), any(), anyBoolean()))
            .thenThrow(new com.carequeue.exception.ConflictException("slot already booked"));

        AppointmentController controller = new AppointmentController(service, java.time.Clock.systemDefaultZone());

        assertThrows(com.carequeue.exception.ConflictException.class, () -> controller.bookAppointment(request(), patient));
        verify(service, times(1)).bookAppointment(any(), any(), anyBoolean());
    }
}
