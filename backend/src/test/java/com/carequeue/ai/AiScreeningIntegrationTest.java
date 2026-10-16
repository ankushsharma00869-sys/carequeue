package com.carequeue.ai;

import com.carequeue.entity.Role;
import com.carequeue.entity.User;
import com.carequeue.notification.NotificationTestSupport;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import com.carequeue.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end through the real Spring context, real security filter chain, and the real
 * SymptomScreeningService -> RuleBasedSymptomAnalyzer path (the "dev"/test profile does not
 * set carequeue.ai.provider=openai, so SpringAiSymptomAnalyzer is never instantiated and no
 * AI provider or API key is involved anywhere in this test class).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AiScreeningIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private PatientRepository patients;
    @Autowired private ApplicationContext applicationContext;

    private User patientUser;

    @BeforeEach
    void setUp() {
        patientUser = NotificationTestSupport.newPatientUser(users, patients);
    }

    private RequestPostProcessor asPatient() {
        UserPrincipal p = new UserPrincipal(patientUser.getId(), patientUser.getEmail(), patientUser.getPassword(),
            patientUser.getFullName(), List.of(new SimpleGrantedAuthority("ROLE_PATIENT")), null, 1L);
        return authentication(new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }

    private RequestPostProcessor asDoctor() {
        UserPrincipal p = new UserPrincipal(999L, "doc." + UUID.randomUUID() + "@test.local", "hash",
            "Dr Test", List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")), 1L, null);
        return authentication(new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }

    @Test
    @DisplayName("The real application context does not create an AI provider bean by default (no API key needed to run this app/tests)")
    void aiProviderBeanAbsentByDefault() {
        assertEquals(0, applicationContext.getBeanNamesForType(SpringAiSymptomAnalyzer.class).length,
            "SpringAiSymptomAnalyzer must not be created unless carequeue.ai.provider=openai");
        assertEquals(1, applicationContext.getBeanNamesForType(RuleBasedSymptomAnalyzer.class).length);
    }

    @Test
    @DisplayName("Unauthenticated request is rejected")
    void unauthenticatedRejected() throws Exception {
        mvc.perform(post("/api/ai/symptom-screening")
                .contentType("application/json")
                .content("{\"symptoms\":\"headache\"}"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Authenticated patient receives a 200 with a structured response")
    void authenticatedPatientReceivesScreening() throws Exception {
        mvc.perform(post("/api/ai/symptom-screening").with(asPatient())
                .contentType("application/json")
                .content("{\"symptoms\":\"mild headache for 2 days\",\"duration\":\"2 days\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.suggestedDepartment").exists())
            .andExpect(jsonPath("$.urgencyLevel").exists())
            .andExpect(jsonPath("$.emergencyAlert").value(false));
    }

    @Test
    @DisplayName("Response always contains the exact required medical disclaimer")
    void responseContainsDisclaimer() throws Exception {
        mvc.perform(post("/api/ai/symptom-screening").with(asPatient())
                .contentType("application/json")
                .content("{\"symptoms\":\"sore throat\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.medicalDisclaimer").value(SymptomScreeningDisclaimer.TEXT))
            .andExpect(content().string(containsString("not a medical diagnosis")));
    }

    @Test
    @DisplayName("A doctor (not just a patient) can also use the screening tool — not role-restricted")
    void doctorCanAlsoScreen() throws Exception {
        mvc.perform(post("/api/ai/symptom-screening").with(asDoctor())
                .contentType("application/json")
                .content("{\"symptoms\":\"patient reports chest discomfort\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Blank symptoms are rejected with 400, not a server error")
    void blankSymptomsRejected() throws Exception {
        mvc.perform(post("/api/ai/symptom-screening").with(asPatient())
                .contentType("application/json")
                .content("{\"symptoms\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Missing symptoms field is rejected with 400")
    void missingFieldRejected() throws Exception {
        mvc.perform(post("/api/ai/symptom-screening").with(asPatient())
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Overlong symptom text is rejected with 400")
    void overlongSymptomsRejected() throws Exception {
        String tooLong = "a".repeat(2001);
        mvc.perform(post("/api/ai/symptom-screening").with(asPatient())
                .contentType("application/json")
                .content("{\"symptoms\":\"" + tooLong + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Red-flag symptoms produce an emergency-flagged response end to end")
    void emergencySymptomsFlagged() throws Exception {
        mvc.perform(post("/api/ai/symptom-screening").with(asPatient())
                .contentType("application/json")
                .content("{\"symptoms\":\"severe chest pain and difficulty breathing\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emergencyAlert").value(true))
            .andExpect(jsonPath("$.urgencyLevel").value("Emergency"));
    }

    @Test
    @DisplayName("Response never contains a dosage pattern (defense-in-depth check on the live endpoint)")
    void noDosageInLiveResponse() throws Exception {
        mvc.perform(post("/api/ai/symptom-screening").with(asPatient())
                .contentType("application/json")
                .content("{\"symptoms\":\"fever and body ache\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString(" mg"))));
    }
}
