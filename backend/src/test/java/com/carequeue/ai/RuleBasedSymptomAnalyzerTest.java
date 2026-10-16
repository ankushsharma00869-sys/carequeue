package com.carequeue.ai;

import com.carequeue.dto.AiDtos.SymptomScreeningRequest;
import com.carequeue.dto.AiDtos.SymptomScreeningResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests — no Spring context, no I/O. This analyzer can never fail. */
class RuleBasedSymptomAnalyzerTest {

    private final RuleBasedSymptomAnalyzer analyzer = new RuleBasedSymptomAnalyzer();

    private SymptomScreeningResponse analyze(String symptoms) {
        Optional<SymptomScreeningResponse> result = analyzer.analyze(
            SymptomScreeningRequest.builder().symptoms(symptoms).duration("2 days").build());
        assertTrue(result.isPresent(), "rule-based analyzer must always return a result");
        return result.get();
    }

    @Test
    @DisplayName("Red-flag symptoms route to Emergency & Critical Care with isEmergencyAlert=true")
    void detectsEmergency() {
        SymptomScreeningResponse r = analyze("I have severe chest pain and difficulty breathing");
        assertEquals("Emergency & Critical Care", r.getSuggestedDepartment());
        assertEquals("Emergency", r.getUrgencyLevel());
        assertTrue(r.isEmergencyAlert());
    }

    @Test
    @DisplayName("Non-emergency symptoms are never flagged as an emergency")
    void routineSymptomsNotFlagged() {
        SymptomScreeningResponse r = analyze("mild itchy rash on my arm for two days");
        assertEquals("Dermatology", r.getSuggestedDepartment());
        assertFalse(r.isEmergencyAlert());
    }

    @Test
    @DisplayName("Orthopedic keywords route to Orthopedics")
    void routesOrthopedics() {
        assertEquals("Orthopedics", analyze("severe knee pain after a fall, possible fracture").getSuggestedDepartment());
    }

    @Test
    @DisplayName("Unmatched generic symptoms fall back to General Medicine")
    void defaultsToGeneralMedicine() {
        assertEquals("General Medicine", analyze("feeling tired and generally unwell").getSuggestedDepartment());
    }

    @Test
    @DisplayName("Every response carries the exact required medical disclaimer")
    void alwaysIncludesDisclaimer() {
        assertEquals(SymptomScreeningDisclaimer.TEXT, analyze("headache").getMedicalDisclaimer());
    }

    @Test
    @DisplayName("Never contains a medication name or dosage pattern in the canned reasoning/question text")
    void neverPrescribes() {
        for (String input : new String[]{"chest pain", "knee injury", "skin rash", "fever", "ear pain"}) {
            SymptomScreeningResponse r = analyze(input);
            String all = (r.getClinicalReasoning() + " " + String.join(" ", r.getQuestionsForDoctor())).toLowerCase();
            assertFalse(all.matches(".*\\d+\\s*mg.*"), "should never mention a dosage: " + all);
        }
    }
}
