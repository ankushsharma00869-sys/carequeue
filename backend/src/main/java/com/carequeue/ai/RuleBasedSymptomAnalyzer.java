package com.carequeue.ai;

import com.carequeue.dto.AiDtos;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic keyword/rule-based triage. This is the ORIGINAL symptom-screening logic
 * (previously the entire contents of SymptomScreeningService, before this phase added a
 * real AI-backed path) — its behavior is unchanged, just moved behind the SymptomAnalyzer
 * interface so it can serve as the fallback when the AI provider is disabled, unavailable,
 * times out, or returns something unusable.
 *
 * Always returns Optional.of(...) — this analyzer cannot itself "fail" (no I/O, no
 * external calls), which is exactly why it is the safety net: SymptomScreeningService
 * uses it whenever the AI path does not produce a result.
 */
@Component
public class RuleBasedSymptomAnalyzer implements SymptomAnalyzer {

    @Override
    public Optional<AiDtos.SymptomScreeningResponse> analyze(AiDtos.SymptomScreeningRequest request) {
        String symptoms = withEnglishTerms(request.getSymptoms().toLowerCase(Locale.ROOT));
        String duration = request.getDuration() != null ? request.getDuration() : "unspecified duration";

        String department = "General Medicine";
        String urgency = "Routine";
        boolean isEmergency = false;
        List<String> questions = new ArrayList<>();
        String reasoning;

        // Emergency Triage Rule Check
        if (symptoms.contains("chest pain") || symptoms.contains("heart attack") ||
            symptoms.contains("cannot breathe") || symptoms.contains("difficulty breathing") ||
            symptoms.contains("unconscious") || symptoms.contains("severe bleeding") ||
            symptoms.contains("stroke") || symptoms.contains("paralysis") || symptoms.contains("cyanosis")) {
            department = "Emergency & Critical Care";
            urgency = "Emergency";
            isEmergency = true;
            reasoning = "Critical red-flag symptom detected involving acute cardiopulmonary or neurological compromise requiring immediate clinical evaluation.";
            questions.add("When did the acute onset of symptoms begin?");
            questions.add("Is there radiation of pain to the left arm, neck, or jaw?");
            questions.add("Do you have a personal or family history of cardiac or vascular events?");
        }
        // Cardiology Rule Check
        else if (symptoms.contains("palpitation") || symptoms.contains("heart racing") ||
                 symptoms.contains("high blood pressure") || symptoms.contains("hypertension") ||
                 symptoms.contains("irregular pulse")) {
            department = "Cardiology";
            urgency = "Priority";
            reasoning = "Cardiovascular related symptoms observed. Priority triage recommended for vital sign assessment and ECG evaluation.";
            questions.add("Are palpitations accompanied by dizziness, diaphoresis, or lightheadedness?");
            questions.add("What are your typical blood pressure readings at rest?");
            questions.add("Are you taking any antihypertensive or beta-blocker medications?");
        }
        // Orthopedics Rule Check
        else if (symptoms.contains("bone") || symptoms.contains("joint") || symptoms.contains("fracture") ||
                 symptoms.contains("knee") || symptoms.contains("back pain") || symptoms.contains("sprain") ||
                 symptoms.contains("ligament") || symptoms.contains("arthritis") || symptoms.contains("shoulder")) {
            department = "Orthopedics";
            urgency = symptoms.contains("fracture") || symptoms.contains("severe pain") ? "Priority" : "Routine";
            reasoning = "Musculoskeletal symptoms identified involving joints, bones, or axial spine. Clinical physical exam and potential radiography indicated.";
            questions.add("Was there a specific traumatic incident, fall, or sports injury?");
            questions.add("Does weight-bearing or joint motion exacerbate the discomfort?");
            questions.add("Is there associated morning stiffness, swelling, or numbness?");
        }
        // Dermatology Rule Check
        else if (symptoms.contains("skin") || symptoms.contains("rash") || symptoms.contains("itch") ||
                 symptoms.contains("eczema") || symptoms.contains("acne") || symptoms.contains("mole") ||
                 symptoms.contains("lesion") || symptoms.contains("psoriasis")) {
            department = "Dermatology";
            urgency = symptoms.contains("spreading quickly") ? "Priority" : "Routine";
            reasoning = "Dermatological presentation observed involving skin integrity, pruritus, or lesions.";
            questions.add("Has the rash or skin change changed in size, pigmentation, or shape?");
            questions.add("Have you introduced new topical products, medications, or allergens?");
            questions.add("Are systemic symptoms such as fever or lymph node swelling present?");
        }
        // ENT Rule Check
        else if (symptoms.contains("ear") || symptoms.contains("throat") || symptoms.contains("hearing") ||
                 symptoms.contains("sinus") || symptoms.contains("vertigo") || symptoms.contains("tonsil") ||
                 symptoms.contains("nasal") || symptoms.contains("tinnitus")) {
            department = "ENT (Ear, Nose & Throat)";
            urgency = "Routine";
            reasoning = "Otorhinolaryngology indications detected involving auditory, sinus, or pharyngeal structures.";
            questions.add("Do you experience ear fullness, hearing reduction, or discharge?");
            questions.add("Is there difficulty or pain when swallowing liquids or solid foods?");
            questions.add("Have you observed persistent facial pressure or nasal congestion?");
        }
        // Pediatrics Rule Check
        else if ((request.getPatientAge() != null && request.getPatientAge() < 16) ||
                 symptoms.contains("child") || symptoms.contains("infant") || symptoms.contains("baby")) {
            department = "Pediatrics";
            urgency = symptoms.contains("high fever") || symptoms.contains("lethargy") ? "Priority" : "Routine";
            reasoning = "Pediatric patient profile identified. Age-appropriate clinical evaluation recommended.";
            questions.add("Is the child feeding and maintaining hydration normally?");
            questions.add("Has the child displayed altered alertness or abnormal lethargy?");
            questions.add("Are vaccination schedules up to date?");
        }
        // General Medicine Default
        else {
            department = "General Medicine";
            urgency = (symptoms.contains("high fever") || symptoms.contains("vomiting")) ? "Priority" : "Routine";
            reasoning = "Constitutional or generalized systemic symptoms observed suitable for primary physician comprehensive review.";
            questions.add("How has your body temperature trended over the past " + duration + "?");
            questions.add("Have you experienced changes in appetite, weight, or energy levels?");
            questions.add("Do you have any known chronic conditions or drug allergies?");
        }

        String summary = String.format("Patient reported: \"%s\" (Duration: %s).", request.getSymptoms(), duration);

        return Optional.of(AiDtos.SymptomScreeningResponse.builder()
            .symptomSummary(summary)
            .suggestedDepartment(department)
            .urgencyLevel(urgency)
            .questionsForDoctor(questions)
            .clinicalReasoning(reasoning)
            .isEmergencyAlert(isEmergency)
            .medicalDisclaimer(SymptomScreeningDisclaimer.TEXT)
            .build());
    }

    /**
     * Maps common Hindi, Hinglish, Devanagari and misspelled words to the English terms the rules below check for.
     * Matching is a substring check on the lowercased text. Single words that could be ambiguous (like "saans")
     * are not mapped on their own; the phrases ("saans phoolna") are.
     */
    private static String withEnglishTerms(String text) {
        String[][] map = {
            {"bukhar", "fever"}, {"bukhaar", "fever"}, {"bukhr", "fever"}, {"bukar", "fever"}, {"jwar", "fever"}, {"jvar", "fever"},
            {"temperature", "fever"}, {"tez bukhar", "high fever"}, {"tej bukhar", "high fever"}, {"tez bukhaar", "high fever"},
            {"khansi", "cough"}, {"khasi", "cough"}, {"khaansi", "cough"}, {"khanshi", "cough"}, {"khasii", "cough"},
            {"sukhi khasi", "dry cough"}, {"sukhi khansi", "dry cough"}, {"sardi", "cold"}, {"zukam", "cold"}, {"jukam", "cold"},
            {"jukaam", "cold"}, {"zukaam", "cold"}, {"naak beh", "runny nose"}, {"nak beh", "runny nose"}, {"sir dard", "headache"},
            {"sirdard", "headache"}, {"sar dard", "headache"}, {"sir mein dard", "headache"}, {"sir me dard", "headache"},
            {"headache", "headache"}, {"seene mein dard", "chest pain"}, {"seene me dard", "chest pain"},
            {"chhati mein dard", "chest pain"}, {"chhati me dard", "chest pain"}, {"chati me dard", "chest pain"},
            {"saans phool", "difficulty breathing"}, {"saans phul", "difficulty breathing"}, {"saans ki takleef", "difficulty breathing"},
            {"saans lene mein takleef", "difficulty breathing"}, {"saans lene me takleef", "difficulty breathing"},
            {"saans lene mein dikkat", "difficulty breathing"}, {"shortness of breath", "difficulty breathing"},
            {"breathless", "difficulty breathing"}, {"pet dard", "stomach pain abdominal pain"},
            {"pait dard", "stomach pain abdominal pain"}, {"pet mein dard", "stomach pain abdominal pain"},
            {"pet me dard", "stomach pain abdominal pain"}, {"stomach ache", "stomach pain abdominal pain"},
            {"stomach pain", "stomach pain abdominal pain"}, {"abdominal pain", "abdominal pain"}, {"ulti", "vomiting"},
            {"ulti aa rahi", "vomiting"}, {"ulti ho rahi", "vomiting"}, {"vomit", "vomiting"}, {"ji machal", "nausea"},
            {"ji machla", "nausea"}, {"dast", "diarrhea"}, {"loose motion", "diarrhea"}, {"lose motion", "diarrhea"},
            {"badan dard", "body pain"}, {"badan me dard", "body pain"}, {"badan mein dard", "body pain"}, {"body pain", "body pain"},
            {"body ache", "body pain"}, {"kaan dard", "ear pain"}, {"kan dard", "ear pain"}, {"kaan me dard", "ear pain"},
            {"kaan mein dard", "ear pain"}, {"earache", "ear pain"}, {"ear pain", "ear pain"}, {"gale mein dard", "throat pain"},
            {"gale me dard", "throat pain"}, {"gala dard", "throat pain"}, {"gala kharab", "throat pain"}, {"sore throat", "throat pain"},
            {"chakkar", "vertigo"}, {"chakar", "vertigo"}, {"chakkar aa", "vertigo"}, {"dizziness", "vertigo"}, {"sar ghoom", "vertigo"},
            {"bachche", "child"}, {"bachcha", "child"}, {"bache", "child"}, {"baccha", "child"}, {"baby", "child"}, {"infant", "infant"},
            {"khujli", "itch"}, {"khujlee", "itch"}, {"khujli ho", "itch"}, {"daane", "rash"}, {"daane ho", "rash"}, {"chamdi", "skin"},
            {"skin", "skin"}, {"rash", "rash"}, {"haath dard", "joint pain"}, {"hath dard", "joint pain"}, {"pair dard", "joint pain"},
            {"pair mein dard", "joint pain"}, {"pair me dard", "joint pain"}, {"jodon ka dard", "joint pain"},
            {"joint pain", "joint pain"}, {"jodon mein dard", "joint pain"}, {"ghutne ka dard", "knee pain"}, {"ghutna dard", "knee pain"},
            {"ghutne mein dard", "knee pain"}, {"kamar dard", "back pain"}, {"kamar mein dard", "back pain"}, {"back pain", "back pain"},
            {"haddi tuti", "fracture"}, {"haddi toot", "fracture"}, {"fracture", "fracture"}, {"behosh", "unconscious"},
            {"lakwa", "paralysis"},
        };
        StringBuilder extra = new StringBuilder();
        for (String[] pair : map) {
            if (text.contains(pair[0])) {
                extra.append(' ').append(pair[1]);
            }
        }
        return extra.length() == 0 ? text : text + extra;
    }
}
