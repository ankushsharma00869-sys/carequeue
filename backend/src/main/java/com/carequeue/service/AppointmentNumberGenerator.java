package com.carequeue.service;

import com.carequeue.entity.AppointmentSequence;
import com.carequeue.repository.AppointmentSequenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Year;

/**
 * Generates human-readable, collision-free appointment numbers such as
 * CQ-2026-10027.
 *
 * NOTE: as of the fix for the intermittent ConcurrentSlotBookingTest failure,
 * AppointmentService.bookAppointment no longer calls this class — it derives the
 * appointment number from the row's own database-generated id instead (see the comment
 * there for why). This class is kept because it is independently correct (see
 * AppointmentNumberGeneratorStressTest and ConcurrentAppointmentNumberTest, both of
 * which call it directly and pass reliably) and may still be useful if a future need
 * wants a purely cosmetic, reset-per-year counter decoupled from row ids; it is simply
 * not on the critical path for booking any more.
 *
 * Previously AppointmentService computed this as
 * `10000 + appointmentRepository.count() + 1`, which is unsafe: two
 * concurrent bookings (for different doctors, so the slot lock doesn't
 * protect them) can both read the same count() and mint the same number,
 * and a cancelled/deleted row changes the count and can reissue an
 * already-used number.
 *
 * This replaces that with a dedicated per-year counter row, incremented
 * under a row-level PESSIMISTIC_WRITE lock in its own REQUIRES_NEW
 * transaction so the counter update commits (and releases its lock)
 * immediately, independent of the outer booking transaction. The
 * appointment_number column's existing unique constraint remains a
 * second line of defense, with a bounded retry here in case it is ever
 * tripped (e.g. manual data import, clock skew across instances).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentNumberGenerator {

    private static final int MAX_ATTEMPTS = 5;

    // Delegated to a separate bean (SequenceCounter) rather than an @Transactional
    // method on this same class: Spring's transactional proxy only intercepts calls
    // that come in through the bean from outside, so a self-invoked
    // this.incrementAndGet(...) call would silently run with NO transaction at all.
    // Calling out to another injected bean goes through its proxy correctly.
    private final SequenceCounter sequenceCounter;

    /** Public entry point: generate the next appointment number for the current year. */
    public String nextAppointmentNumber() {
        int year = Year.now().getValue();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                long next = sequenceCounter.incrementAndGet(year);
                return String.format("CQ-%d-%05d", year, next);
            } catch (DataIntegrityViolationException ex) {
                log.warn("Appointment sequence row for year {} collided on attempt {}, retrying", year, attempt);
            }
        }
        throw new IllegalStateException("Could not generate a unique appointment number for year " + year
            + " after " + MAX_ATTEMPTS + " attempts.");
    }

    /**
     * Isolated in its own bean so @Transactional(REQUIRES_NEW) is honored via
     * Spring's proxy, and so the row lock is held for the shortest possible
     * time — released as soon as the counter commits, instead of being held
     * for the whole booking transaction.
     */
    @Service
    @RequiredArgsConstructor
    public static class SequenceCounter {

        private static final long STARTING_VALUE = 10000L;

        private final AppointmentSequenceRepository sequenceRepository;

        @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
        public long incrementAndGet(int year) {
            int updated = sequenceRepository.incrementIfExists(year);

            if (updated == 0) {
                // First-ever call for this year: the row doesn't exist yet. If two callers
                // race here, one INSERT wins and the other hits the year_value primary-key
                // violation — caught by nextAppointmentNumber()'s retry loop, which calls
                // this method again and this time takes the (now atomic) increment branch
                // above.
                sequenceRepository.saveAndFlush(
                    AppointmentSequence.builder().year(year).lastValue(STARTING_VALUE).build());
                return STARTING_VALUE;
            }

            // Read-your-own-write: this SELECT runs in the same transaction as the UPDATE
            // above, on the same connection, so it is guaranteed to see that write.
            return sequenceRepository.findLastValue(year)
                .orElseThrow(() -> new IllegalStateException(
                    "Appointment sequence row for year " + year + " disappeared mid-transaction"));
        }
    }
}
