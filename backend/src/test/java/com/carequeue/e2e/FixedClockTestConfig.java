package com.carequeue.e2e;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Pins "now" to 08:00 on DAY, so booking windows, check-in eligibility and the day's queue are the
 * same on every run and at any wall-clock time. Import it on any test that depends on dates.
 */
@TestConfiguration
public class FixedClockTestConfig {

    public static final LocalDate DAY = LocalDate.of(2091, 3, 15);

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(DAY.atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    }
}
