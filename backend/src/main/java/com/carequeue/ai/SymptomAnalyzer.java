package com.carequeue.ai;

import com.carequeue.dto.AiDtos;

import java.util.Optional;

/**
 * A pluggable symptom-triage engine. Implementations must NEVER diagnose a disease,
 * prescribe medication/dosage, or claim medical certainty — see RuleBasedSymptomAnalyzer
 * and SpringAiSymptomAnalyzer for how each enforces that.
 *
 * Returns Optional.empty() to mean "could not produce a safe, well-formed result right
 * now" (provider unavailable, timeout, malformed output) — the caller
 * (SymptomScreeningService) is responsible for falling back, never the analyzer itself.
 */
public interface SymptomAnalyzer {
    Optional<AiDtos.SymptomScreeningResponse> analyze(AiDtos.SymptomScreeningRequest request);
}
