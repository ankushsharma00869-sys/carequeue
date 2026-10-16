package com.carequeue.controller;

import com.carequeue.dto.NotificationDtos.NotificationResponse;
import com.carequeue.exception.ResourceNotFoundException;
import com.carequeue.repository.NotificationRepository;
import com.carequeue.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Every query is scoped to the authenticated user's id taken from the JWT principal - there is
 * no user/patient id in any URL or body, so there is nothing to tamper with. Marking another
 * user's notification as read returns 404 (ownership is part of the lookup).
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;

    @GetMapping
    public ResponseEntity<PagedModel<NotificationResponse>> myNotifications(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        // PagedModel gives a stable JSON shape ({content, page:{...}}); serializing a raw PageImpl is
        // explicitly unsupported by Spring Data (it logged a warning in the test run).
        Page<NotificationResponse> result = notificationRepository
            .findByRecipientUserIdOrderByCreatedAtDesc(principal.getId(), PageRequest.of(Math.max(page, 0), safeSize))
            .map(NotificationResponse::from);
        return ResponseEntity.ok(new PagedModel<>(result));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(Map.of("unread", notificationRepository.countByRecipientUserIdAndReadFalse(principal.getId())));
    }

    @PatchMapping("/{id}/read")
    @Transactional
    public ResponseEntity<NotificationResponse> markRead(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        var notification = notificationRepository.findByIdAndRecipientUserId(id, principal.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));
        notification.setRead(true);
        return ResponseEntity.ok(NotificationResponse.from(notificationRepository.save(notification)));
    }

    @PatchMapping("/read-all")
    @Transactional
    public ResponseEntity<Map<String, Integer>> markAllRead(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(Map.of("updated", notificationRepository.markAllReadForUser(principal.getId())));
    }
}
