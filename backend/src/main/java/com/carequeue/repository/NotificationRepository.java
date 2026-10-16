package com.carequeue.repository;

import com.carequeue.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByEventIdAndRecipientUserId(String eventId, Long recipientUserId);

    long countByEventId(String eventId);

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(Long recipientUserId, Pageable pageable);

    long countByRecipientUserIdAndReadFalse(Long recipientUserId);

    /** Ownership is part of the lookup: another user's id simply yields "not found". */
    Optional<Notification> findByIdAndRecipientUserId(Long id, Long recipientUserId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipientUserId = :userId AND n.read = false")
    int markAllReadForUser(@Param("userId") Long userId);
}
