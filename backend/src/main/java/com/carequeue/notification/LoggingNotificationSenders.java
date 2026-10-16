package com.carequeue.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Development senders. Log-only: NOTHING is actually sent. Contact details are masked. */
public class LoggingNotificationSenders {

    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "n/a";
        int at = email.indexOf('@');
        return (at <= 1 ? "*" : email.charAt(0) + "***") + email.substring(at);
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "n/a";
        return "******" + phone.substring(phone.length() - 4);
    }

    @Slf4j
    @Component
    @ConditionalOnProperty(name = "carequeue.notifications.email.provider", havingValue = "log", matchIfMissing = true)
    public static class LoggingEmailSender implements NotificationChannelSender {
        public Channel channel() { return Channel.EMAIL; }
        public boolean supports(NotificationMessage m) { return m.email() != null && !m.email().isBlank(); }
        public void send(NotificationMessage m) {
            log.info("[EMAIL - not actually sent, log provider] to={} subject=\"{}\"", maskEmail(m.email()), m.title());
        }
    }

    @Slf4j
    @Component
    @ConditionalOnProperty(name = "carequeue.notifications.sms.provider", havingValue = "log", matchIfMissing = true)
    public static class LoggingSmsSender implements NotificationChannelSender {
        public Channel channel() { return Channel.SMS; }
        public boolean supports(NotificationMessage m) { return m.phone() != null && !m.phone().isBlank(); }
        public void send(NotificationMessage m) {
            log.info("[SMS - not actually sent, log provider] to={} text=\"{}\"", maskPhone(m.phone()), m.body());
        }
    }
}
