package com.carequeue.repository;

import com.carequeue.entity.AppointmentSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppointmentSequenceRepository extends JpaRepository<AppointmentSequence, Integer> {

    /**
     * Atomically increments the counter for {@code year} in ONE statement and returns the
     * number of rows touched (0 if the row doesn't exist yet, 1 if it was incremented).
     *
     * This replaces a SELECT ... FOR UPDATE followed by a separate application-side
     * read-modify-write, which relied on the database actually blocking a second
     * transaction's SELECT FOR UPDATE while the first held it. Under H2 that guarantee is
     * weaker than under PostgreSQL (and can also be undermined by ordinary lock-timeout /
     * scheduling jitter under load even on Postgres): two overlapping transactions could
     * each read the same last_value, each add 1 in application code, and each write back
     * the SAME next value — a silent lost update with no constraint to catch it, which is
     * exactly what produced a duplicate appointment_number in testing. A single
     * `SET last_value = last_value + 1` statement is atomic at the database level in every
     * mainstream SQL engine (the UPDATE itself holds the row's write lock for the
     * statement's duration), so it cannot lose an update regardless of how strictly a given
     * database enforces explicit row-locking reads.
     */
    @Modifying
    @Query("UPDATE AppointmentSequence s SET s.lastValue = s.lastValue + 1 WHERE s.year = :year")
    int incrementIfExists(@Param("year") Integer year);

    @Query("SELECT s.lastValue FROM AppointmentSequence s WHERE s.year = :year")
    Optional<Long> findLastValue(@Param("year") Integer year);
}
