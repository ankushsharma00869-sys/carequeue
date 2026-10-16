package com.carequeue.dto;

import com.carequeue.entity.Notification;
import lombok.*;

import java.time.LocalDateTime;

public class NotificationDtos {

    /** API/WebSocket shape. Never the entity; never contains other users' data. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class NotificationResponse {
        private Long id;
        private String type;
        private String title;
        private String message;
        private boolean read;
        private LocalDateTime createdAt;

        public static NotificationResponse from(Notification n) {
            return NotificationResponse.builder()
                .id(n.getId()).type(n.getType()).title(n.getTitle())
                .message(n.getMessage()).read(n.isRead()).createdAt(n.getCreatedAt())
                .build();
        }
    }
}
