package com.carequeue.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Production sender. Configure with SPRING_MAIL_* environment variables. The mail bean only exists when
 * SPRING_MAIL_HOST is set, so it is looked up at send time: the app starts fine without email configured,
 * and a send attempt fails with a clear message instead of a false "sent".
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "carequeue.mail.mode", havingValue = "smtp", matchIfMissing = true)
public class SmtpEmailSender implements EmailSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${carequeue.mail.from:no-reply@carequeue.invalid}")
    private String from;

    @Override
    public void send(String to, String subject, String body) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException("Email is not configured (set SPRING_MAIL_HOST and related SPRING_MAIL_* variables)");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
