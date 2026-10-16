package com.carequeue;

import com.carequeue.entity.*;
import com.carequeue.repository.DoctorRepository;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.QueueEntryRepository;
import com.carequeue.service.QueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("dev")
class ConcurrentTokenGenerationTest {

    @Autowired
    private QueueService queueService;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private QueueEntryRepository queueEntryRepository;

    @Test
    @DisplayName("Concurrent requests for same doctor/date must never produce duplicate token numbers")
    void testConcurrentTokenGeneration() throws InterruptedException {
        Doctor doctor = doctorRepository.findAll().get(0);
        Patient patient = patientRepository.findAll().get(0);
        LocalDate futureDate = LocalDate.now().plusDays(30); // Clean date

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<Integer> generatedTokens = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    QueueEntry entry = queueService.createQueueEntryForAppointment(
                        doctor, patient, futureDate, QueuePriority.NORMAL
                    );
                    generatedTokens.add(entry.getTokenNumber());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // If 10 requests ran, we MUST have exactly 10 distinct token numbers
        assertEquals(threadCount, generatedTokens.size(),
            "All concurrently generated tokens must be unique without duplicates");
    }
}