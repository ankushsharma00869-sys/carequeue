package com.carequeue.ai;

import com.carequeue.dto.AiDtos.SymptomScreeningRequest;
import com.carequeue.dto.AiDtos.SymptomScreeningResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the AI-backed analyzer with the ChatClient fully mocked — NO real network
 * call and NO real API key is ever used or required. Exercises every fallback path: valid
 * JSON, malformed JSON, missing fields, invalid urgency value, provider exception, and
 * timeout.
 */
class SpringAiSymptomAnalyzerTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callResponseSpec;
    private SpringAiSymptomAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        analyzer = new SpringAiSymptomAnalyzer(builder, new ObjectMapper(), 2000);
    }

    private SymptomScreeningRequest request() {
        return SymptomScreeningRequest.builder().symptoms("persistent headache for 3 days").duration("3 days").build();
    }

    @Test
    @DisplayName("Well-formed JSON response is parsed into a valid result")
    void parsesValidJsonResponse() {
        when(callResponseSpec.content()).thenReturn("""
            {"symptomSummary":"Headache x3 days","suggestedDepartment":"Neurology",
             "urgencyLevel":"ROUTINE","questionsForDoctor":["Any visual disturbance?"],
             "clinicalReasoning":"Likely tension headache; further eval recommended."}
            """);

        Optional<SymptomScreeningResponse> result = analyzer.analyze(request());

        assertTrue(result.isPresent());
        assertEquals("Neurology", result.get().getSuggestedDepartment());
        assertEquals("Routine", result.get().getUrgencyLevel());
        assertFalse(result.get().isEmergencyAlert());
        assertEquals(1, result.get().getQuestionsForDoctor().size());
    }

    @Test
    @DisplayName("JSON wrapped in ```json markdown fences is still parsed")
    void handlesMarkdownFencedJson() {
        when(callResponseSpec.content()).thenReturn("""
            ```json
            {"symptomSummary":"s","suggestedDepartment":"General Medicine","urgencyLevel":"ROUTINE",
             "questionsForDoctor":[],"clinicalReasoning":"r"}
            ```
            """);

        assertTrue(analyzer.analyze(request()).isPresent());
    }

    @Test
    @DisplayName("EMERGENCY urgency sets isEmergencyAlert true")
    void emergencyUrgencyFlagsAlert() {
        when(callResponseSpec.content()).thenReturn("""
            {"symptomSummary":"s","suggestedDepartment":"Emergency & Critical Care","urgencyLevel":"EMERGENCY",
             "questionsForDoctor":[],"clinicalReasoning":"r"}
            """);

        assertTrue(analyzer.analyze(request()).get().isEmergencyAlert());
    }

    @Test
    @DisplayName("Malformed (non-JSON) response falls back safely to Optional.empty()")
    void malformedJsonFallsBack() {
        when(callResponseSpec.content()).thenReturn("Sorry, I cannot help with that.");
        assertTrue(analyzer.analyze(request()).isEmpty());
    }

    @Test
    @DisplayName("JSON missing required fields falls back safely")
    void missingFieldsFallsBack() {
        when(callResponseSpec.content()).thenReturn("{\"urgencyLevel\":\"ROUTINE\"}");
        assertTrue(analyzer.analyze(request()).isEmpty());
    }

    @Test
    @DisplayName("Invalid urgencyLevel value falls back safely")
    void invalidUrgencyFallsBack() {
        when(callResponseSpec.content()).thenReturn("""
            {"symptomSummary":"s","suggestedDepartment":"d","urgencyLevel":"SUPER_URGENT",
             "questionsForDoctor":[],"clinicalReasoning":"r"}
            """);
        assertTrue(analyzer.analyze(request()).isEmpty());
    }

    @Test
    @DisplayName("Blank response falls back safely")
    void blankResponseFallsBack() {
        when(callResponseSpec.content()).thenReturn("   ");
        assertTrue(analyzer.analyze(request()).isEmpty());
    }

    @Test
    @DisplayName("Provider exception is caught and never propagates out of analyze() — and its message never leaks")
    void providerExceptionFallsBackWithoutLeaking() {
        when(callResponseSpec.content()).thenThrow(new RuntimeException("Unauthorized: sk-FAKESECRETKEY1234 rejected"));

        Optional<SymptomScreeningResponse> result = assertDoesNotThrow(() -> analyzer.analyze(request()));

        assertTrue(result.isEmpty());
        // The only way the fake key text could reach a caller is via a thrown exception's message
        // or a returned response field; analyze() returns neither here.
    }

    @Test
    @DisplayName("A slow provider (exceeds configured timeout) falls back safely rather than hanging the request")
    void timeoutFallsBackSafely() {
        ChatClient slowClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec slowSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec slowCallSpec = mock(ChatClient.CallResponseSpec.class);
        when(slowClient.prompt()).thenReturn(slowSpec);
        when(slowSpec.system(anyString())).thenReturn(slowSpec);
        when(slowSpec.user(anyString())).thenReturn(slowSpec);
        when(slowSpec.call()).thenReturn(slowCallSpec);
        when(slowCallSpec.content()).thenAnswer(invocation -> {
            Thread.sleep(1500);
            return "{}";
        });
        ChatClient.Builder slowBuilder = mock(ChatClient.Builder.class);
        when(slowBuilder.build()).thenReturn(slowClient);

        SpringAiSymptomAnalyzer shortTimeoutAnalyzer = new SpringAiSymptomAnalyzer(slowBuilder, new ObjectMapper(), 200);

        long start = System.currentTimeMillis();
        Optional<SymptomScreeningResponse> result = shortTimeoutAnalyzer.analyze(request());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.isEmpty());
        assertTrue(elapsed < 1500, "should have returned around the 200ms timeout, not waited for the full 1500ms call: " + elapsed + "ms");
    }
}
