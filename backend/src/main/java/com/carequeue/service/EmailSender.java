package com.carequeue.service;

/** Sends one plain-text email. Implementations throw a RuntimeException when delivery fails. */
public interface EmailSender {
    void send(String to, String subject, String body);
}
