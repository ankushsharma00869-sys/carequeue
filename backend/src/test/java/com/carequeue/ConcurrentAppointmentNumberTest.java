package com.carequeue;

import com.carequeue.service.AppointmentNumberGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against the exact bug that appointmentRepository.count() + 1 had:
 * concurrent booking requests (e.g. for two different doctors, so the
 * per-slot distributed lock in AppointmentService does not serialize them)
 * must never be able to mint the same human-readable appointment number.
 */
@SpringBootTest
@ActiveProfiles("dev")
class ConcurrentAppointmentNumberTest {

    @Autowired
    private AppointmentNumberGenerator appointmentNumberGenerator;

    @Test
    @DisplayName("Concurrent appointment number generation must never produce duplicates")
    void testConcurrentAppointmentNumberGeneration() throws InterruptedException {
        int threadCount = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<String> generatedNumbers = Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    String number = appointmentNumberGenerator.nextAppointmentNumber();
                    generatedNumbers.add(number);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "All generator calls should complete within timeout");
        executor.shutdown();

        assertEquals(threadCount, generatedNumbers.size(),
            "All concurrently generated appointment numbers must be unique without duplicates");

        generatedNumbers.forEach(n -> assertTrue(n.matches("CQ-\\d{4}-\\d{5}"),
            "Appointment number should match the CQ-YYYY-NNNNN format: " + n));
    }
}
