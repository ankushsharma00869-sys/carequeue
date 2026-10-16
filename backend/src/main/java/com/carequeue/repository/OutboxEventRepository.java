package com.carequeue.repository;

import com.carequeue.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Ids of events that may be attempted now: PENDING, due for retry, and not leased by another
     * relay. Oldest first, so delivery follows the order in which business changes committed.
     */
    @Query("""
        select o.id from OutboxEvent o
        where o.status = :status
          and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
          and (o.leaseUntil is null or o.leaseUntil < :now)
        order by o.id asc
        """)
    List<Long> findDueIds(@Param("status") OutboxEvent.Status status,
                          @Param("now") LocalDateTime now,
                          Pageable page);

    /**
     * Atomically takes the lease on one event. A single conditional UPDATE: of two relays racing for
     * the same row, exactly one sees 1 updated row and the other sees 0. Works on H2 and PostgreSQL
     * without vendor-specific locking syntax.
     *
     * @return 1 if this caller now owns the event, 0 otherwise
     */
    @Modifying
    @Transactional
    @Query("""
        update OutboxEvent o
           set o.leaseOwner = :owner, o.leaseUntil = :leaseUntil
         where o.id = :id
           and o.status = :status
           and (o.leaseUntil is null or o.leaseUntil < :now)
        """)
    int claim(@Param("id") Long id,
              @Param("owner") String owner,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("now") LocalDateTime now,
              @Param("status") OutboxEvent.Status status);

    long countByStatus(OutboxEvent.Status status);
}
