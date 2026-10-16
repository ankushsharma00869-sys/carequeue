package com.carequeue.ai;

/**
 * The exact disclaimer every symptom-screening response must carry. SymptomScreeningService
 * overwrites the response's medicalDisclaimer field with this constant unconditionally, so
 * no analyzer implementation — including a future AI provider that might omit, shorten, or
 * reword it — can cause a response to go out without it.
 */
public final class SymptomScreeningDisclaimer {

    public static final String TEXT =
        "This AI screening is for informational purposes only and is not a medical diagnosis. " +
        "A qualified doctor must make the final medical decision.";

    private SymptomScreeningDisclaimer() {}
}
