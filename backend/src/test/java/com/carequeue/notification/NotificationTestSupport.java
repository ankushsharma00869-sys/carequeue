package com.carequeue.notification;

import com.carequeue.entity.Patient;
import com.carequeue.entity.Role;
import com.carequeue.entity.User;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared helpers for notification tests. */
public class NotificationTestSupport {

    /** Recording SMS sender added alongside the real log senders, so tests can count actual sends. */
    public static class RecordingSender implements NotificationChannelSender {
        public final List<NotificationMessage> sent = new CopyOnWriteArrayList<>();
        public volatile boolean failOnSend = false;

        public Channel channel() { return Channel.SMS; }
        public boolean supports(NotificationMessage m) { return true; }
        public void send(NotificationMessage m) {
            if (failOnSend) throw new IllegalStateException("provider down");
            sent.add(m);
        }
    }

    @TestConfiguration
    public static class RecordingSenderConfig {
        @Bean
        public RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    public static User newPatientUser(UserRepository users, PatientRepository patients) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        User user = users.save(User.builder()
            .email("notif." + id + "@test.local")
            .password("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012")
            .fullName("Notif Patient " + id)
            .phoneNumber("98" + Math.abs(id.hashCode() % 100000000))
            .role(Role.PATIENT)
            .active(true)
            .build());
        patients.save(Patient.builder().user(user).build());
        return user;
    }
}
