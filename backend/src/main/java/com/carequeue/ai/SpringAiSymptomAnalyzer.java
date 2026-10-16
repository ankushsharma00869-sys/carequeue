package com.carequeue.ai;

import com.carequeue.dto.AiDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Real LLM-backed symptom triage via Spring AI's ChatClient.
 *
 * Only instantiated when carequeue.ai.provider=openai — with the default "rule-based"
 * provider this bean does not exist, so nothing in this class runs and no request to an
 * AI provider is ever made (this is what keeps the automated test suite from needing a
 * real API key: RuleBasedSymptomAnalyzer.analyze() always succeeds, so
 * SymptomScreeningService works correctly whether or not this bean is present).
 *
 * Safety design:
 *  - The model is instructed to return ONLY a JSON object matching a fixed schema, and is
 *    explicitly told never to diagnose, prescribe, or state medical certainty.
 *  - The model's raw text is parsed and structurally validated (required fields present,
 *    urgencyLevel is one of the three allowed values). ANY parse failure, missing field,
 *    invalid enum value, timeout, or exception of any kind results in Optional.empty() —
 *    this analyzer never throws out of analyze() and never returns a half-formed response.
 *  - The API key is read only from configuration (never hardcoded) and is never included
 *    in any log line or exception message this class produces.
 *  - This analyzer does NOT get the final say on emergency status or the disclaimer text —
 *    SymptomScreeningService re-applies the deterministic red-flag check and always
 *    overwrites medicalDisclaimer, regardless of what this analyzer returns.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "carequeue.ai", name = "provider", havingValue = "openai")
public class SpringAiSymptomAnalyzer implements SymptomAnalyzer {

    private static final Set<String> ALLOWED_URGENCY = Set.of("ROUTINE", "PRIORITY", "EMERGENCY");
    private static final int MAX_QUESTIONS = 5;

    private static final String SYSTEM_PROMPT = """
        You are a clinical intake triage assistant for a hospital queue system. You are NOT a
        doctor. You must NEVER diagnose a disease, NEVER suggest or name a medication or
        dosage, and NEVER claim medical certainty. Your only job is to summarize the
        patient's reported symptoms, suggest which hospital department they should be routed
        to, estimate an urgency level, and list questions the doctor may want to ask.

        Respond with ONLY a single JSON object, no markdown fences, no other text, matching
        exactly this shape:
        {
          "symptomSummary": string,
          "suggestedDepartment": string,
          "urgencyLevel": "ROUTINE" | "PRIORITY" | "EMERGENCY",
          "questionsForDoctor": string[],
          "clinicalReasoning": string
        }

        Use "EMERGENCY" for any red-flag presentation (e.g. chest pain, difficulty
        breathing, stroke symptoms, severe bleeding, unconsciousness). Keep
        clinicalReasoning to one or two sentences and never include medication names or
        dosages anywhere in the response.
        """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-symptom-analyzer");
        t.setDaemon(true);
        return t;
    });

    public SpringAiSymptomAnalyzer(
        ChatClient.Builder chatClientBuilder,
        ObjectMapper objectMapper,
        @Value("${carequeue.ai.timeout-ms:8000}") long timeoutMs
    ) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Optional<AiDtos.SymptomScreeningResponse> analyze(AiDtos.SymptomScreeningRequest request) {
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> callModel(request), executor);
            String raw = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return parse(raw);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("AI symptom screening timed out after {}ms; falling back to rule-based triage", timeoutMs);
            return Optional.empty();
        } catch (Exception e) {
            // Deliberately logs only the exception type/message, never request/response
            // bodies or any configuration value (the API key is never touched here at all).
            log.warn("AI symptom screening failed ({}); falling back to rule-based triage", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private String callModel(AiDtos.SymptomScreeningRequest request) {
        String userPrompt = "Patient-reported symptoms: " + request.getSymptoms()
            + "\nDuration: " + (request.getDuration() != null ? request.getDuration() : "not specified")
            + (request.getPatientAge() != null ? "\nPatient age: " + request.getPatientAge() : "")
            + (request.getPatientGender() != null ? "\nPatient gender: " + request.getPatientGender() : "");

        return chatClient.prompt()
            .system(SYSTEM_PROMPT)
            .user(userPrompt)
            .call()
            .content();
    }

    private Optional<AiDtos.SymptomScreeningResponse> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            // Models sometimes wrap JSON in ```json fences despite instructions not to; strip if present.
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
            }

            JsonNode node = objectMapper.readTree(cleaned);

            String urgency = textOrNull(node, "urgencyLevel");
            if (urgency == null || !ALLOWED_URGENCY.contains(urgency.toUpperCase(Locale.ROOT))) {
                log.warn("AI symptom screening returned an invalid urgencyLevel; falling back to rule-based triage");
                return Optional.empty();
            }

            String summary = textOrNull(node, "symptomSummary");
            String department = textOrNull(node, "suggestedDepartment");
            String reasoning = textOrNull(node, "clinicalReasoning");
            if (summary == null || department == null || reasoning == null) {
                log.warn("AI symptom screening response missing required fields; falling back to rule-based triage");
                return Optional.empty();
            }

            List<String> questions = new ArrayList<>();
            JsonNode questionsNode = node.get("questionsForDoctor");
            if (questionsNode != null && questionsNode.isArray()) {
                for (JsonNode q : questionsNode) {
                    if (questions.size() >= MAX_QUESTIONS) break;
                    if (q.isTextual() && !q.asText().isBlank()) {
                        questions.add(q.asText());
                    }
                }
            }

            String normalizedUrgency = urgency.substring(0, 1).toUpperCase(Locale.ROOT)
                + urgency.substring(1).toLowerCase(Locale.ROOT);

            return Optional.of(AiDtos.SymptomScreeningResponse.builder()
                .symptomSummary(summary)
                .suggestedDepartment(department)
                .urgencyLevel(normalizedUrgency)
                .questionsForDoctor(questions)
                .clinicalReasoning(reasoning)
                .isEmergencyAlert("EMERGENCY".equalsIgnoreCase(urgency))
                .medicalDisclaimer(SymptomScreeningDisclaimer.TEXT) // overwritten again by the orchestrator regardless
                .build());
        } catch (Exception e) {
            log.warn("Could not parse AI symptom screening response ({}); falling back to rule-based triage",
                e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isTextual() && !v.asText().isBlank()) ? v.asText() : null;
    }
}
