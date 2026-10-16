package com.carequeue.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * The application's single source of "now" for business decisions: booking windows, the
 * day's queue, check-in eligibility. Tests override this bean with a fixed clock so results do
 * not depend on the wall clock or on crossing midnight during a run.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${carequeue.clock.zone:}") String zone) {
        ZoneId id = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
        return Clock.system(id);
    }
}
