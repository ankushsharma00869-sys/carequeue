package com.carequeue.websocket;

import com.carequeue.security.JwtTokenProvider;
import com.carequeue.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Executes the real WebSocketAuthInterceptor. Covers who may subscribe to which STOMP
 * destination, i.e. who can receive which notifications / queue data over WebSocket.
 */
class WebSocketAuthInterceptorTest {

    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthInterceptor(mock(JwtTokenProvider.class), mock(UserDetailsService.class),
            mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    private static UserPrincipal principal(long id, String role, Long doctorId, Long patientId) {
        return new UserPrincipal(id, role.toLowerCase() + id + "@test.local", "hash", role + id,
            List.of(new SimpleGrantedAuthority("ROLE_" + role)), doctorId, patientId);
    }

    private Message<byte[]> subscribe(String destination, UserPrincipal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        if (user != null) {
            accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private void assertAllowed(String destination, UserPrincipal user) {
        assertDoesNotThrow(() -> interceptor.preSend(subscribe(destination, user), null), user.getFullName() + " -> " + destination);
    }

    private void assertDenied(String destination, UserPrincipal user) {
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(subscribe(destination, user), null),
            user.getFullName() + " -> " + destination);
    }

    private final UserPrincipal patientA = principal(1, "PATIENT", null, 10L);
    private final UserPrincipal patientB = principal(2, "PATIENT", null, 20L);
    private final UserPrincipal doctorA = principal(3, "DOCTOR", 100L, null);
    private final UserPrincipal doctorB = principal(4, "DOCTOR", 200L, null);
    private final UserPrincipal reception = principal(5, "RECEPTIONIST", null, null);
    private final UserPrincipal admin = principal(6, "ADMIN", null, null);

    @Test
    @DisplayName("A patient can subscribe to their own private channel, and NOT to another patient's")
    void patientPrivateChannel() {
        assertAllowed("/topic/patient/10", patientA);
        assertDenied("/topic/patient/20", patientA);
        assertDenied("/topic/patient/10", patientB);
    }

    @Test
    @DisplayName("Doctors cannot read patients' private notification channels")
    void doctorCannotSubscribeToPatientChannel() {
        assertDenied("/topic/patient/10", doctorA);
    }

    @Test
    @DisplayName("Reception and admin may subscribe to patient channels (operational follow-up)")
    void staffPatientChannel() {
        assertAllowed("/topic/patient/10", reception);
        assertAllowed("/topic/patient/10", admin);
    }

    @Test
    @DisplayName("Full queue channel (names + phones): own doctor, reception, admin only")
    void fullQueueChannel() {
        assertAllowed("/topic/queue/100", doctorA);
        assertDenied("/topic/queue/200", doctorA);
        assertDenied("/topic/queue/100", doctorB);
        assertDenied("/topic/queue/100", patientA);
        assertAllowed("/topic/queue/100", reception);
        assertAllowed("/topic/queue/100", admin);
    }

    @Test
    @DisplayName("Sanitized queue board (tokens only) is open to any authenticated user, including patients")
    void boardChannelOpen() {
        assertAllowed("/topic/queue/100/board", patientA);
        assertAllowed("/topic/queue/200/board", doctorA);
    }

    @Test
    @DisplayName("Hospital emergency channel: staff only, never patients")
    void emergencyChannel() {
        assertDenied("/topic/hospital/emergency", patientA);
        assertAllowed("/topic/hospital/emergency", doctorA);
        assertAllowed("/topic/hospital/emergency", reception);
        assertAllowed("/topic/hospital/emergency", admin);
    }

    @Test
    @DisplayName("SUBSCRIBE with no authenticated principal on the session is rejected")
    void unauthenticatedSubscribeRejected() {
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(subscribe("/topic/queue/100/board", null), null));
    }

    @Test
    @DisplayName("CONNECT without a JWT is rejected")
    void connectWithoutTokenRejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        Message<byte[]> connect = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThrows(BadCredentialsException.class, () -> interceptor.preSend(connect, null));
    }
}
