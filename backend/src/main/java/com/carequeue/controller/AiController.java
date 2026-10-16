package com.carequeue.controller;

import com.carequeue.dto.AiDtos.*;
import com.carequeue.service.SymptomScreeningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final SymptomScreeningService symptomScreeningService;

    @PostMapping("/symptom-screening")
    public ResponseEntity<SymptomScreeningResponse> screenSymptoms(
        @Valid @RequestBody SymptomScreeningRequest request
    ) {
        return ResponseEntity.ok(symptomScreeningService.screenSymptoms(request));
    }
}
