package com.carequeue.notification;

import com.carequeue.entity.Notification;
import com.carequeue.entity.User;
import com.carequeue.repository.NotificationRepository;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import com.carequeue.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** A patient can only ever see or modify their own notifications. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class NotificationControllerSecurityTest {

    @Autowired private MockMvc mvc;
    @Autowired private NotificationWriter writer;
    @Autowired private NotificationRepository notifications;
    @Autowired private UserRepository users;
    @Autowired private PatientRepository patients;

    private User alice;
    private User bob;
    private Notification aliceNotification;
    private Notification bobNotification;

    @BeforeEach
    void setUp() {
        alice = NotificationTestSupport.newPatientUser(users, patients);
        bob = NotificationTestSupport.newPatientUser(users, patients);
        aliceNotification = writer.insertIfAbsent(UUID.randomUUID().toString(), alice.getId(), "TOKEN_CALLED", "Alice title", "ALICE-SECRET-MESSAGE");
        bobNotification = writer.insertIfAbsent(UUID.randomUUID().toString(), bob.getId(), "TOKEN_CALLED", "Bob title", "BOB-SECRET-MESSAGE");
    }

    private RequestPostProcessor as(User u) {
        UserPrincipal p = new UserPrincipal(u.getId(), u.getEmail(), u.getPassword(), u.getFullName(),
            List.of(new SimpleGrantedAuthority("ROLE_PATIENT")), null, 1L);
        return authentication(new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }

    @Test
    @DisplayName("Unauthenticated request is rejected")
    void unauthenticatedRejected() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Patient sees only their own notifications, never another patient's")
    void listIsScopedToCaller() throws Exception {
        mvc.perform(get("/api/notifications").with(as(alice)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("ALICE-SECRET-MESSAGE")))
            .andExpect(content().string(not(containsString("BOB-SECRET-MESSAGE"))))
            .andExpect(content().string(not(containsString("password"))));
    }

    @Test
    @DisplayName("Patient cannot mark another patient's notification as read (404) and it stays unread")
    void cannotMarkOthersRead() throws Exception {
        mvc.perform(patch("/api/notifications/" + bobNotification.getId() + "/read").with(as(alice)))
            .andExpect(status().isNotFound());

        assertFalse(notifications.findById(bobNotification.getId()).orElseThrow().isRead());
    }

    @Test
    @DisplayName("Patient can mark their own notification read; unread count reflects it")
    void markOwnRead() throws Exception {
        mvc.perform(get("/api/notifications/unread-count").with(as(alice)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(1));

        mvc.perform(patch("/api/notifications/" + aliceNotification.getId() + "/read").with(as(alice)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));

        mvc.perform(get("/api/notifications/unread-count").with(as(alice)))
            .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @DisplayName("read-all only touches the caller's own notifications")
    void readAllScoped() throws Exception {
        mvc.perform(patch("/api/notifications/read-all").with(as(alice))).andExpect(status().isOk());

        assertTrue(notifications.findById(aliceNotification.getId()).orElseThrow().isRead());
        assertFalse(notifications.findById(bobNotification.getId()).orElseThrow().isRead());
    }
}
