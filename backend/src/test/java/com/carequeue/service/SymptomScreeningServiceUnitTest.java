package com.carequeue.service;

import com.carequeue.ai.RuleBasedSymptomAnalyzer;
import com.carequeue.ai.SpringAiSymptomAnalyzer;
import com.carequeue.ai.SymptomScreeningDisclaimer;
import com.carequeue.dto.AiDtos.SymptomScreeningRequest;
import com.carequeue.dto.AiDtos.SymptomScreeningResponse;
import com.carequeue.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Orchestration logic in isolation — no Spring context, AI analyzer fully mocked. Proves
 * the fallback wiring and the two safety guarantees (red-flag override, forced disclaimer)
 * independently of whichever analyzer produced the input response.
 */
class SymptomScreeningServiceUnitTest {

    private final RuleBasedSymptomAnalyzer ruleBased = new RuleBasedSymptomAnalyzer();

    private SymptomScreeningRequest request(String symptoms) {
        return SymptomScreeningRequest.builder().symptoms(symptoms).duration("1 day").build();
    }

    @Test
    @DisplayName("When no AI analyzer bean exists (default provider), the rule-based analyzer is used")
    void fallsBackWhenAiAbsent() {
        SymptomScreeningService service = new SymptomScreeningService(ruleBased, Optional.empty());
        SymptomScreeningResponse r = service.screenSymptoms(request("itchy skin rash"));
        assertEquals("Dermatology", r.getSuggestedDepartment());
    }

    @Test
    @DisplayName("When the AI analyzer returns a result, it is used instead of the rule-based one")
    void usesAiResultWhenAvailable() {
        SpringAiSymptomAnalyzer mockAi = mock(SpringAiSymptomAnalyzer.class);
        when(mockAi.analyze(any())).thenReturn(Optional.of(SymptomScreeningResponse.builder()
            .symptomSummary("ai summary").suggestedDepartment("Neurology").urgencyLevel("Routine")
            .questionsForDoctor(List.of("q1")).clinicalReasoning("ai reasoning")
            .isEmergencyAlert(false).medicalDisclaimer("placeholder").build()));

        SymptomScreeningService service = new SymptomScreeningService(ruleBased, Optional.of(mockAi));
        SymptomScreeningResponse r = service.screenSymptoms(request("headache"));

        assertEquals("Neurology", r.getSuggestedDepartment());
        assertEquals("ai reasoning", r.getClinicalReasoning());
    }

    @Test
    @DisplayName("When the AI analyzer fails (returns empty), the rule-based analyzer is used instead")
    void fallsBackWhenAiFails() {
        SpringAiSymptomAnalyzer mockAi = mock(SpringAiSymptomAnalyzer.class);
        when(mockAi.analyze(any())).thenReturn(Optional.empty());

        SymptomScreeningService service = new SymptomScreeningService(ruleBased, Optional.of(mockAi));
        SymptomScreeningResponse r = service.screenSymptoms(request("itchy skin rash"));

        assertEquals("Dermatology", r.getSuggestedDepartment(), "should have used the rule-based fallback");
    }

    @Test
    @DisplayName("SAFETY: an AI response that wrongly marks a red-flag input as non-emergency is overridden")
    void redFlagOverridesIncorrectAiResult() {
        SpringAiSymptomAnalyzer mockAi = mock(SpringAiSymptomAnalyzer.class);
        // Simulates a misbehaving/manipulated AI response that understates a real emergency.
        when(mockAi.analyze(any())).thenReturn(Optional.of(SymptomScreeningResponse.builder()
            .symptomSummary("s").suggestedDepartment("General Medicine").urgencyLevel("Routine")
            .questionsForDoctor(List.of()).clinicalReasoning("r")
            .isEmergencyAlert(false).medicalDisclaimer("placeholder").build()));

        SymptomScreeningService service = new SymptomScreeningService(ruleBased, Optional.of(mockAi));
        SymptomScreeningResponse r = service.screenSymptoms(request("I have severe chest pain and can't breathe"));

        assertTrue(r.isEmergencyAlert(), "deterministic red-flag check must override an AI result that missed it");
        assertEquals("Emergency", r.getUrgencyLevel());
        assertEquals("Emergency & Critical Care", r.getSuggestedDepartment());
    }

    @Test
    @DisplayName("SAFETY: the disclaimer is always the required exact text, regardless of what the analyzer set")
    void disclaimerIsAlwaysEnforced() {
        SpringAiSymptomAnalyzer mockAi = mock(SpringAiSymptomAnalyzer.class);
        when(mockAi.analyze(any())).thenReturn(Optional.of(SymptomScreeningResponse.builder()
            .symptomSummary("s").suggestedDepartment("d").urgencyLevel("Routine")
            .questionsForDoctor(List.of()).clinicalReasoning("r")
            .isEmergencyAlert(false).medicalDisclaimer("some other text the AI made up").build()));

        SymptomScreeningService service = new SymptomScreeningService(ruleBased, Optional.of(mockAi));
        SymptomScreeningResponse r = service.screenSymptoms(request("headache"));

        assertEquals(SymptomScreeningDisclaimer.TEXT, r.getMedicalDisclaimer());
    }

    @Test
    @DisplayName("Blank symptoms are rejected")
    void rejectsBlankSymptoms() {
        SymptomScreeningService service = new SymptomScreeningService(ruleBased, Optional.empty());
        assertThrows(BadRequestException.class, () -> service.screenSymptoms(request("   ")));
    }

    @Test
    @DisplayName("Symptom text over the max length is rejected")
    void rejectsOverlongSymptoms() {
        SymptomScreeningService service = new SymptomScreeningService(ruleBased, Optional.empty());
        String tooLong = "a".repeat(2001);
        assertThrows(BadRequestException.class, () -> service.screenSymptoms(request(tooLong)));
    }

    @Test
    @DisplayName("Out-of-range patient age is rejected")
    void rejectsInvalidAge() {
        SymptomScreeningService service = new SymptomScreeningService(ruleBased, Optional.empty());
        SymptomScreeningRequest req = request("cough");
        req.setPatientAge(-5);
        assertThrows(BadRequestException.class, () -> service.screenSymptoms(req));
    }
}
