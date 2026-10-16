package com.carequeue.service;

import com.carequeue.ai.RuleBasedSymptomAnalyzer;
import com.carequeue.ai.SpringAiSymptomAnalyzer;
import com.carequeue.ai.SymptomScreeningDisclaimer;
import com.carequeue.dto.AiDtos;
import com.carequeue.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * Orchestrates symptom screening across a real AI provider (when configured) and a
 * deterministic rule-based engine (always available, see RuleBasedSymptomAnalyzer).
 *
 * Two safety guarantees hold no matter which analyzer produced the response, and cannot be
 * bypassed by a misbehaving or manipulated AI provider:
 *  1. The red-flag emergency check (chest pain, can't breathe, stroke symptoms, etc.) is
 *     re-run here independently. If it fires, urgency/department/isEmergencyAlert are
 *     forced to the emergency values regardless of what the AI or rule engine said.
 *  2. medicalDisclaimer is always overwritten with the fixed, required text.
 *
 * Kept intentionally free of persistence: nothing about a screening is stored against a
 * patient, so there is no cross-patient screening data to leak (see PHASE 12 report).
 */
@Slf4j
@Service
public class SymptomScreeningService {

    /** Kept for any external reference to the exact wording; delegates to the single source of truth. */
    public static final String MEDICAL_DISCLAIMER = SymptomScreeningDisclaimer.TEXT;

    private static final int MAX_SYMPTOM_LENGTH = 2000;

    private static final String[] RED_FLAG_KEYWORDS = {
        "chest pain", "heart attack", "cannot breathe", "can't breathe", "difficulty breathing",
        "unconscious", "severe bleeding", "stroke", "paralysis", "cyanosis"
    };

    private final RuleBasedSymptomAnalyzer ruleBasedAnalyzer;
    private final Optional<SpringAiSymptomAnalyzer> aiAnalyzer;

    public SymptomScreeningService(
        RuleBasedSymptomAnalyzer ruleBasedAnalyzer,
        Optional<SpringAiSymptomAnalyzer> aiAnalyzer
    ) {
        this.ruleBasedAnalyzer = ruleBasedAnalyzer;
        this.aiAnalyzer = aiAnalyzer;
    }

    public AiDtos.SymptomScreeningResponse screenSymptoms(AiDtos.SymptomScreeningRequest request) {
        validate(request);

        AiDtos.SymptomScreeningResponse response = aiAnalyzer
            .flatMap(analyzer -> analyzer.analyze(request))
            .orElseGet(() -> ruleBasedAnalyzer.analyze(request)
                .orElseThrow(() -> new IllegalStateException(
                    "Rule-based symptom analyzer must always return a result — this indicates a bug, not a provider outage")));

        return applySafetyGuarantees(request, response);
    }

    /**
     * @NotBlank on the DTO already rejects null/empty/whitespace-only input at the
     * controller boundary; this adds the checks that annotation cannot express.
     */
    private void validate(AiDtos.SymptomScreeningRequest request) {
        String symptoms = request.getSymptoms();
        if (symptoms == null || symptoms.trim().isEmpty()) {
            throw new BadRequestException("Please describe your symptoms.");
        }
        if (symptoms.length() > MAX_SYMPTOM_LENGTH) {
            throw new BadRequestException("Symptom description is too long (max " + MAX_SYMPTOM_LENGTH + " characters).");
        }
        if (request.getPatientAge() != null && (request.getPatientAge() < 0 || request.getPatientAge() > 130)) {
            throw new BadRequestException("Patient age is out of range.");
        }
    }

    /**
     * Independent, deterministic re-check. This is the guard that stops an AI provider —
     * misconfigured, manipulated via prompt injection, or simply wrong — from ever being
     * able to downgrade a genuine emergency, and the single place that guarantees the
     * disclaimer text.
     */
    private AiDtos.SymptomScreeningResponse applySafetyGuarantees(
        AiDtos.SymptomScreeningRequest request, AiDtos.SymptomScreeningResponse response
    ) {
        boolean redFlag = containsRedFlag(request.getSymptoms());

        if (redFlag && !response.isEmergencyAlert()) {
            log.warn("Overriding non-emergency AI/rule-based triage result: red-flag symptom keywords were present in the input");
            response.setEmergencyAlert(true);
            response.setUrgencyLevel("Emergency");
            response.setSuggestedDepartment("Emergency & Critical Care");
        }

        response.setMedicalDisclaimer(SymptomScreeningDisclaimer.TEXT);
        return response;
    }

    private boolean containsRedFlag(String symptoms) {
        String lower = symptoms.toLowerCase(Locale.ROOT);
        for (String keyword : RED_FLAG_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
