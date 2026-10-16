package com.carequeue.websocket;

/**
 * Published whenever the WebSocket layer refuses a CONNECT or a SUBSCRIBE. It makes a rejection
 * observable (tests, audit) rather than only a log line, so a test can wait for the real refusal
 * instead of sleeping and hoping nothing arrived.
 */
public record SubscriptionDeniedEvent(String command, String destination, String user, String reason) {}
