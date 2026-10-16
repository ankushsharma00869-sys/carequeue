package com.carequeue.controller;

import com.carequeue.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization for the hospital analytics endpoint, exercised through the real Spring Security
 * filter chain (MockMvc with security enabled — nothing is disabled or mocked out).
 *
 * Principals are built as UserPrincipal objects with the role authority, which is the same
 * convention NotificationControllerSecurityTest uses. Role checks in SecurityConfig
 * (/api/analytics/** -> ADMIN, RECEPTIONIST) and on the controller (@PreAuthorize) are both
 * exercised, because the request goes through both.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AnalyticsAuthorizationTest {

    private static final String ENDPOINT = "/api/analytics/overview";

    @Autowired private MockMvc mvc;

    private static RequestPostProcessor asRole(long id, String role) {
        UserPrincipal p = new UserPrincipal(
            id,
            role.toLowerCase() + id + "@test.local",
            "not-a-real-hash",
            role + " user " + id,
            List.of(new SimpleGrantedAuthority("ROLE_" + role)),
            role.equals("DOCTOR") ? 1000L + id : null,
            role.equals("PATIENT") ? 2000L + id : null);
        return authentication(new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }

    @Test
    @DisplayName("ADMIN can read hospital analytics (200)")
    void adminAllowed() throws Exception {
        mvc.perform(get(ENDPOINT).with(asRole(1L, "ADMIN")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("RECEPTIONIST can read hospital analytics (200)")
    void receptionistAllowed() throws Exception {
        mvc.perform(get(ENDPOINT).with(asRole(2L, "RECEPTIONIST")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATIENT is forbidden from hospital analytics (403)")
    void patientForbidden() throws Exception {
        mvc.perform(get(ENDPOINT).with(asRole(3L, "PATIENT")))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DOCTOR is forbidden from hospital analytics (403)")
    void doctorForbidden() throws Exception {
        mvc.perform(get(ENDPOINT).with(asRole(4L, "DOCTOR")))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request is rejected before reaching the controller (401)")
    void unauthenticatedRejected() throws Exception {
        mvc.perform(get(ENDPOINT))
            .andExpect(status().isUnauthorized());
    }
}
