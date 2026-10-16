package com.carequeue.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only sender (carequeue.mail.mode=capture). Keeps messages in memory and sends nothing to the internet.
 * Never enable this in production: the codes would be unreachable by the patient.
 */
@Component
@ConditionalOnProperty(name = "carequeue.mail.mode", havingValue = "capture")
public class CaptureEmailSender implements EmailSender {

    public record Message(String to, String subject, String body) {}

    private final List<Message> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String to, String subject, String body) {
        sent.add(new Message(to, subject, body));
    }

    public List<Message> messagesTo(String to) {
        return sent.stream().filter(m -> m.to().equals(to)).toList();
    }
}
