package com.carequeue.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for a real failure observed in CI: AppointmentNumberGenerator used to
 * increment its per-year counter with SELECT ... FOR UPDATE followed by a separate
 * application-side read-modify-write. That relies on the database actually blocking a
 * second transaction's locked SELECT while the first holds it — a guarantee that is weaker
 * under H2 than PostgreSQL, and can also be undermined by ordinary scheduling/lock-timing
 * jitter under load. Two overlapping calls could read the same counter value, each compute
 * "value + 1" in Java, and each write back the SAME next value: a silent lost update, with
 * no unique constraint on the counter table itself to catch it — the duplicate only surfaced
 * later, as an unrelated appointment_number collision on the appointments table.
 *
 * The fix replaced that with a single atomic `UPDATE ... SET last_value = last_value + 1`
 * statement, which is atomic in virtually every SQL engine regardless of explicit
 * row-locking strength. This test hammers the generator directly (bypassing booking/locks
 * entirely) at higher concurrency than ConcurrentAppointmentNumberTest and additionally
 * checks the numeric sequence embedded in each result is gap-free and duplicate-free.
 */
@SpringBootTest
@ActiveProfiles("dev")
class AppointmentNumberGeneratorStressTest {

    @Autowired
    private AppointmentNumberGenerator generator;

    private static final Pattern NUMBER_PATTERN = Pattern.compile("^CQ-(\\d{4})-(\\d{5})$");

    @Test
    @DisplayName("50 concurrent calls produce 50 distinct, gap-free appointment numbers — no lost updates")
    void highConcurrencyProducesDistinctGapFreeNumbers() throws Exception {
        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return generator.nextAppointmentNumber();
            }));
        }
        start.countDown();

        List<Long> sequenceValues = new ArrayList<>();
        Set<String> allNumbers = new HashSet<>();
        for (Future<String> f : futures) {
            String number = f.get(30, TimeUnit.SECONDS);
            assertTrue(allNumbers.add(number), "duplicate appointment number generated: " + number);
            Matcher m = NUMBER_PATTERN.matcher(number);
            assertTrue(m.matches(), "unexpected format: " + number);
            sequenceValues.add(Long.parseLong(m.group(2)));
        }
        pool.shutdown();

        assertEquals(n, allNumbers.size(), "all 50 concurrently generated numbers must be unique");

        // Gap-free within this run: 50 calls must claim 50 consecutive integers, proving no
        // value was silently skipped or double-claimed by a lost update.
        Collections.sort(sequenceValues);
        for (int i = 1; i < sequenceValues.size(); i++) {
            assertEquals(sequenceValues.get(i - 1) + 1, sequenceValues.get(i),
                "gap or duplicate in sequence at index " + i + ": " + sequenceValues);
        }
    }
}
