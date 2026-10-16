package com.carequeue.notification;

/** What a delivery channel needs to send. Plain data; contact fields are resolved at send time. */
public record NotificationMessage(Long recipientUserId, String email, String phone, String title, String body) {}
