package com.carequeue.security;

import com.carequeue.entity.Patient;
import com.carequeue.entity.Role;
import com.carequeue.entity.User;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpClient;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real HTTP through a running server. MockMvc does not perform the ERROR dispatch that Tomcat runs
 * after sendError(), so it cannot see this bug: an authenticated patient denied by a role rule got
 * 403 in unit tests but 401 over the real network. This test asserts the status a real client sees.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AccessDeniedHttpStatusTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private UserRepository users;
    @Autowired private PatientRepository patients;

    @BeforeEach
    void useJdkHttpClient() {
        // JDK HttpURLConnection treats a 401 with WWW-Authenticate as a challenge; java.net.http does not.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()));
    }

    private String patientToken() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        User u = users.save(User.builder()
            .email("pt.denied." + id + "@test.local").password("$2a$10$notarealhashjustfortest")
            .fullName("Denied Patient " + id).phoneNumber("9" + Math.abs(id.hashCode() % 100000000))
            .role(Role.PATIENT).active(true).build());
        Patient p = patients.save(Patient.builder().user(u).build());
        UserPrincipal principal = UserPrincipal.create(u, null, p.getId());
        return tokenProvider.generateToken(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("An authenticated patient denied by a role rule gets 403 over real HTTP, not 401")
    void authenticatedDeniedIsForbiddenOverHttp() {
        String token = patientToken();

        ResponseEntity<String> audit = get("/api/admin/audit-logs", token);
        assertEquals(403, audit.getStatusCodeValue(), "must be 403 (authenticated, not allowed), body: " + audit.getBody());
        assertFalse(audit.getBody() != null && audit.getBody().contains("Authentication is required"),
            "must not be the anonymous 401 body");

        ResponseEntity<String> analytics = get("/api/analytics/overview", token);
        assertEquals(403, analytics.getStatusCodeValue(), "analytics for a patient: body: " + analytics.getBody());
    }

    @Test
    @DisplayName("A request with no token is still 401 (the fix must not change anonymous handling)")
    void anonymousStillUnauthorized() {
        assertEquals(401, get("/api/admin/audit-logs", null).getStatusCodeValue());
    }

    @Test
    @DisplayName("A malformed token is still 401")
    void malformedTokenStillUnauthorized() {
        assertEquals(401, get("/api/admin/audit-logs", "not.a.jwt").getStatusCodeValue());
    }
}
