package com.carequeue.websocket;

import com.carequeue.security.JwtTokenProvider;
import com.carequeue.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Requires a valid JWT on every STOMP CONNECT frame, the same token the REST API already
 * requires. Before this, /ws accepted any connection with no credentials at all, so anyone
 * with a WebSocket client (no CareQueue account needed) could subscribe to
 * /topic/queue/{doctorId} and receive every waiting patient's name and phone number in
 * real time, regardless of what the REST endpoints required.
 *
 * The frontend must send the token as a STOMP CONNECT header, e.g. with @stomp/stompjs:
 *   new Client({ connectHeaders: { Authorization: `Bearer ${token}` }, ... })
 *
 * This ALSO enforces per-destination authorization on every STOMP SUBSCRIBE, not just on
 * CONNECT. CONNECT only proves the socket belongs to *some* authenticated CareQueue user —
 * without a SUBSCRIBE-time check, that same user (including a PATIENT) could subscribe to
 * ANY /topic/queue/{doctorId} (full names + phone numbers of every patient in that doctor's
 * queue, for any doctor) or ANY /topic/patient/{patientId} (another patient's private "your
 * token has been called" notifications) just by knowing/guessing an id. That mirrors the
 * exact requirement in the audit brief: "A patient must not subscribe to another patient's
 * private queue data."
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsService userDetailsService;
    private final org.springframework.context.ApplicationEventPublisher events;

    private static final Pattern PATIENT_TOPIC = Pattern.compile("^/topic/patient/(\\d+)$");
    private static final Pattern QUEUE_BOARD_TOPIC = Pattern.compile("^/topic/queue/(\\d+)/board$");
    private static final Pattern QUEUE_FULL_TOPIC = Pattern.compile("^/topic/queue/(\\d+)$");
    private static final String EMERGENCY_TOPIC = "/topic/hospital/emergency";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscribe(accessor);
        }

        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String token = extractToken(accessor);

        if (!StringUtils.hasText(token) || !tokenProvider.validateToken(token)) {
            log.warn("Rejected WebSocket CONNECT: missing or invalid token");
            events.publishEvent(new SubscriptionDeniedEvent("CONNECT", null, null, "missing or invalid token"));
            throw new BadCredentialsException("Missing or invalid authentication token");
        }

        String username = tokenProvider.getUsernameFromToken(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        accessor.setUser(authentication);
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }

        UserPrincipal principal = resolvePrincipal(accessor.getUser());
        if (principal == null) {
            log.warn("Rejected WebSocket SUBSCRIBE to {}: no authenticated principal on session", destination);
            events.publishEvent(new SubscriptionDeniedEvent("SUBSCRIBE", destination, null, "not authenticated"));
            throw new AccessDeniedException("Not authenticated");
        }

        boolean isAdmin = hasRole(principal, "ROLE_ADMIN");
        boolean isReceptionist = hasRole(principal, "ROLE_RECEPTIONIST");
        boolean isDoctor = hasRole(principal, "ROLE_DOCTOR");

        Matcher patientMatch = PATIENT_TOPIC.matcher(destination);
        Matcher boardMatch = QUEUE_BOARD_TOPIC.matcher(destination);
        Matcher fullQueueMatch = QUEUE_FULL_TOPIC.matcher(destination);

        if (patientMatch.matches()) {
            // Private per-patient channel: only that patient, or staff who legitimately
            // need it for reception/administrative follow-up.
            Long patientId = Long.valueOf(patientMatch.group(1));
            boolean isOwnPatientChannel = principal.getPatientId() != null && principal.getPatientId().equals(patientId);
            if (!(isOwnPatientChannel || isAdmin || isReceptionist)) {
                deny(destination, principal);
            }
            return;
        }

        if (boardMatch.matches()) {
            // Sanitized, PII-free waiting-room board (token numbers/status only — no
            // patient names or phone numbers). Any authenticated CareQueue user may view it.
            return;
        }

        if (fullQueueMatch.matches()) {
            // Full queue overview includes patient names + phone numbers: restricted to the
            // assigned doctor's own queue, or reception/admin staff.
            Long doctorId = Long.valueOf(fullQueueMatch.group(1));
            boolean isOwnDoctorChannel = isDoctor && principal.getDoctorId() != null && principal.getDoctorId().equals(doctorId);
            if (!(isOwnDoctorChannel || isAdmin || isReceptionist)) {
                deny(destination, principal);
            }
            return;
        }

        if (EMERGENCY_TOPIC.equals(destination)) {
            // Contains details of active emergency cases hospital-wide: operational staff only.
            if (!(isDoctor || isAdmin || isReceptionist)) {
                deny(destination, principal);
            }
        }
    }

    private void deny(String destination, UserPrincipal principal) {
        log.warn("Rejected WebSocket SUBSCRIBE to {} for user {}: not authorized for this channel",
            destination, principal.getUsername());
        events.publishEvent(new SubscriptionDeniedEvent("SUBSCRIBE", destination, principal.getUsername(), "not authorized for this channel"));
        throw new AccessDeniedException("You are not authorized to subscribe to " + destination);
    }

    private boolean hasRole(UserPrincipal principal, String role) {
        return principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(role::equals);
    }

    private UserPrincipal resolvePrincipal(Principal user) {
        if (user instanceof Authentication authentication && authentication.getPrincipal() instanceof UserPrincipal userPrincipal) {
            return userPrincipal;
        }
        return null;
    }

    private String extractToken(StompHeaderAccessor accessor) {
        String bearer = accessor.getFirstNativeHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
