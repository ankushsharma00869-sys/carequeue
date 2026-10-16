package com.carequeue.clock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** A clock the test can move: for midnight and cancellation-window boundaries. */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant now;

    public MutableClock(LocalDateTime start, ZoneId zone) {
        this.zone = zone;
        this.now = start.atZone(zone).toInstant();
    }

    public void setLocal(LocalDateTime t) {
        now = t.atZone(zone).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new MutableClock(LocalDateTime.ofInstant(now, other), other);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @TestConfiguration
    public static class Config {
        public static final LocalDateTime START = LocalDateTime.of(2092, 6, 1, 23, 59);

        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock(START, ZoneId.systemDefault());
        }
    }
}
