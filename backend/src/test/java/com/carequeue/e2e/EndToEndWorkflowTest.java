package com.carequeue.e2e;

import com.carequeue.dto.HospitalDoctorDtos.CreateDoctorRequest;
import com.carequeue.dto.HospitalDoctorDtos.CreateReceptionistRequest;
import com.carequeue.dto.HospitalDoctorDtos.DoctorDto;
import com.carequeue.entity.Appointment;
import com.carequeue.entity.AppointmentStatus;
import com.carequeue.entity.Department;
import com.carequeue.entity.Hospital;
import com.carequeue.entity.QueueEntry;
import com.carequeue.entity.QueueStatus;
import com.carequeue.repository.AppointmentRepository;
import com.carequeue.repository.DepartmentRepository;
import com.carequeue.repository.HospitalRepository;
import com.carequeue.repository.NotificationRepository;
import com.carequeue.repository.QueueEntryRepository;
import com.carequeue.service.AdminStaffService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 15: the complete CareQueue workflow against a running server.
 *
 *   patient signup -> patient login -> book -> reception check-in -> doctor call-next
 *   -> patient receives STOMP update -> doctor start -> doctor complete -> analytics reflect it
 *
 * What is real here: the HTTP server, the Spring Security filter chain and JWT auth, every
 * service, the H2 database, the STOMP broker and WebSocket interceptor, and the in-process event
 * listener that persists notifications.
 *
 * What is not: Kafka (no broker; the Kafka publish fails and is logged, which the business flow
 * tolerates by design), external AI, and email/SMS (log-only senders).
 *
 * Time: appointments are booked for LocalDate.now(), because queue actions (call-next, check-in
 * queue lookup) operate on today's queue. A test that runs across midnight can see a date change
 * between steps; that risk is accepted and documented.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@org.springframework.context.annotation.Import({FixedClockTestConfig.class, StompProbe.Config.class})
class EndToEndWorkflowTest {

    private static final String PASSWORD = "secret123";
    private static final LocalTime SLOT_0900 = LocalTime.of(9, 0);
    private static final LocalTime SLOT_0930 = LocalTime.of(9, 30);

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper mapper;
    @Autowired private AdminStaffService adminStaffService;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private QueueEntryRepository queueEntryRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private StompProbe probe;
    @Autowired private PlatformTransactionManager txManager;

    private final List<StompSession> openSessions = new ArrayList<>();

    /**
     * Runs a read that touches lazy associations (Appointment.doctor / .patient) inside a transaction.
     * open-in-view is false, so the test thread has no session unless one is opened explicitly.
     */
    private <T> T inTx(Supplier<T> read) {
        return new TransactionTemplate(txManager).execute(status -> read.get());
    }

    /**
     * The JDK HttpURLConnection treats a 401 that carries WWW-Authenticate as an authentication
     * challenge. In streaming mode it cannot resend the body, so a POST with a bad token fails with
     * HttpRetryException instead of returning the 401. java.net.http does not run that challenge
     * logic, so the 401 comes back as an ordinary response, which is what these tests assert.
     */
    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build()));
    }

    @AfterEach
    void closeSockets() {
        for (StompSession s : openSessions) {
            if (s.isConnected()) s.disconnect();
        }
        openSessions.clear();
    }

    // =====================================================================
    // Fixtures and HTTP helpers
    // =====================================================================

    /** One isolated doctor and receptionist, both logged in through the real login endpoint. */
    private record Staff(long doctorId, String doctorToken, long doctorUserId,
                         String receptionistToken) {}

    /** One patient created through the real signup endpoint and logged in through the real login endpoint. */
    private record Patient(long userId, long patientId, String token, String email) {}

    private Staff newStaff() throws Exception {
        String id = shortId();
        Hospital hospital = hospitalRepository.findAll().get(0);
        Department department = departmentRepository.findAll().get(0);

        DoctorDto doctor = adminStaffService.createDoctor(CreateDoctorRequest.builder()
            .fullName("Dr E2E " + id).email("dr.e2e." + id + "@test.local").password(PASSWORD)
            .phoneNumber("7" + digits(id))
            .hospitalId(hospital.getId()).departmentId(department.getId())
            .initialSchedule(TestSchedules.fullWeek())
            .licenseNumber("LIC-E2E-" + id).specialization("Testing").build());

        String recEmail = "rc.e2e." + id + "@test.local";
        adminStaffService.createReceptionist(CreateReceptionistRequest.builder()
            .fullName("Reception E2E " + id).email(recEmail).password(PASSWORD)
            .phoneNumber("6" + digits(id)).hospitalId(hospital.getId()).build());

        JsonNode docLogin = login("dr.e2e." + id + "@test.local");
        JsonNode recLogin = login(recEmail);
        assertEquals("DOCTOR", docLogin.path("role").asText());
        assertEquals(doctor.getId(), docLogin.path("doctorId").asLong(), "doctor login must carry the doctor profile id");
        assertEquals("RECEPTIONIST", recLogin.path("role").asText());

        return new Staff(doctor.getId(), docLogin.path("token").asText(), docLogin.path("userId").asLong(),
            recLogin.path("token").asText());
    }

    /** Real signup, then real login, and checks that the two agree on identity. */
    private Patient signupPatient(String label) throws Exception {
        String id = shortId();
        String email = "pt.e2e." + label + "." + id + "@test.local";

        ResponseEntity<String> reg = call(HttpMethod.POST, "/api/auth/register", null,
            Map.of("fullName", "Patient " + label + " " + id, "email", email,
                   "password", PASSWORD, "phoneNumber", "5" + digits(id)));
        assertEquals(200, reg.getStatusCodeValue(), "signup failed: " + reg.getBody());
        JsonNode signup = json(reg);
        assertEquals("PATIENT", signup.path("role").asText(), "public signup must always create a PATIENT");
        long patientId = signup.path("patientId").asLong();
        assertTrue(patientId > 0, "signup must create a patient profile");

        JsonNode login = login(email);
        assertEquals(patientId, login.path("patientId").asLong(), "login must resolve to the same patient profile as signup");
        return new Patient(login.path("userId").asLong(), patientId, login.path("token").asText(), email);
    }

    private JsonNode login(String email) throws Exception {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/auth/login", null,
            Map.of("email", email, "password", PASSWORD));
        assertEquals(200, res.getStatusCodeValue(), "login failed for " + email + ": " + res.getBody());
        return json(res);
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        String payload;
        try {
            payload = body == null ? "" : mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(payload, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> res) throws Exception {
        return mapper.readTree(res.getBody());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String digits(String s) {
        return String.valueOf(Math.abs(s.hashCode() % 100000000));
    }

    /** The fixed test day (see FixedClockTestConfig): no dependence on the wall clock or midnight. */
    private static String today() {
        return FixedClockTestConfig.DAY.toString();
    }

    private Map<String, Object> bookingBody(Staff staff, Long patientIdOrNull, LocalTime time) {
        Hospital hospital = hospitalRepository.findAll().get(0);
        Department department = departmentRepository.findAll().get(0);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("hospitalId", hospital.getId());
        body.put("departmentId", department.getId());
        body.put("doctorId", staff.doctorId());
        body.put("appointmentDate", today());
        body.put("appointmentTime", time.toString());
        if (patientIdOrNull != null) body.put("patientId", patientIdOrNull);
        return body;
    }

    /** Analytics for today as seen by the receptionist. */
    private JsonNode analyticsToday(Staff staff) throws Exception {
        ResponseEntity<String> res = call(HttpMethod.GET, "/api/analytics/overview?date=" + today(),
            staff.receptionistToken(), null);
        assertEquals(200, res.getStatusCodeValue(), "analytics read failed: " + res.getBody());
        return json(res);
    }

    private JsonNode doctorRowIn(JsonNode analytics, long doctorId) {
        for (JsonNode row : analytics.path("doctorUtilization")) {
            if (row.path("doctorId").asLong() == doctorId) return row;
        }
        return null;
    }

    // =====================================================================
    // WebSocket (real STOMP over a real socket)
    // =====================================================================

    private StompSession connectStomp(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {})
            .get(10, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    /**
     * Subscribes and returns the inbox for that destination. The broker processes SUBSCRIBE on its
     * inbound channel, so callers pause briefly before acting. A rejected subscription produces no
     * messages, so the delivery assertions are what prove a subscription worked.
     */
    @SuppressWarnings("unchecked")
    private BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) throws InterruptedException {
        BlockingQueue<Map<String, Object>> inbox = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                inbox.add((Map<String, Object>) payload);
            }
        });
        // Readiness is observed on the server (SessionSubscribeEvent), not guessed with a sleep.
        probe.awaitSubscribed(destination);
        return inbox;
    }

    /** Subscribes where the interceptor must refuse; waits for the refusal, then returns the (empty) inbox. */
    @SuppressWarnings("unchecked")
    private BlockingQueue<Map<String, Object>> subscribeExpectingRejection(StompSession session, String destination) {
        BlockingQueue<Map<String, Object>> inbox = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                inbox.add((Map<String, Object>) payload);
            }
        });
        probe.awaitDenied(destination);
        return inbox;
    }

    private static Map<String, Object> awaitMessage(BlockingQueue<Map<String, Object>> inbox,
                                                    Predicate<Map<String, Object>> matches) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Map<String, Object> m = inbox.poll(500, TimeUnit.MILLISECONDS);
            if (m != null && matches.test(m)) return m;
        }
        fail("expected WebSocket message was not received within 10 seconds");
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean carriesToken(Map<String, Object> message, String token) {
        Object qe = message.get("queueEntry");
        return qe instanceof Map && token.equals(((Map<String, Object>) qe).get("tokenFormatted"));
    }

    // =====================================================================
    // 1. Full workflow (happy path)
    // =====================================================================

    @Test
    @DisplayName("E2E: signup -> login -> book -> check-in -> call-next -> WebSocket update -> start -> complete -> analytics")
    void completeWorkflowFromSignupToAnalytics() throws Exception {
        Staff staff = newStaff();
        Patient alice = signupPatient("alice");
        Patient bob = signupPatient("bob");

        // Baseline analytics. Seed data may already exist for today, so every check is a delta.
        JsonNode before = analyticsToday(staff);
        long totalBefore = before.path("totalAppointmentsToday").asLong();
        long completedBefore = before.path("completedToday").asLong();
        long waitingBefore = before.path("waitingNow").asLong();
        long cancelledBefore = before.path("cancelledToday").asLong();

        // Alice books. Bob is subscribed to Alice's private channel, which he must not receive from.
        ResponseEntity<String> booked = call(HttpMethod.POST, "/api/appointments", alice.token(),
            bookingBody(staff, null, SLOT_0900));
        assertEquals(200, booked.getStatusCodeValue(), "booking failed: " + booked.getBody());
        JsonNode appt = json(booked);
        long apptId = appt.path("id").asLong();
        String token = appt.path("tokenFormatted").asText();
        assertEquals("CONFIRMED", appt.path("status").asText());
        assertEquals(alice.patientId(), appt.path("patientId").asLong(), "booking must attach to the caller's own patient profile");
        assertEquals(staff.doctorId(), appt.path("doctorId").asLong());
        assertFalse(token.isBlank(), "booking must issue a queue token");

        assertEquals(AppointmentStatus.CONFIRMED, appointmentRepository.findById(apptId).orElseThrow().getStatus(),
            "CONFIRMED must be persisted");
        assertEquals(alice.patientId(), inTx(() -> appointmentRepository.findById(apptId).orElseThrow().getPatient().getId()),
            "the persisted appointment must belong to Alice");

        // Real WebSocket subscriptions: Alice on her own channel, Bob on Alice's channel.
        StompSession aliceSocket = connectStomp(alice.token());
        BlockingQueue<Map<String, Object>> aliceInbox = subscribe(aliceSocket, "/topic/patient/" + alice.patientId());

        // Bob subscribes to Alice's private channel. The interceptor must reject it, so nothing reaches him.
        StompSession bobSocket = connectStomp(bob.token());
        BlockingQueue<Map<String, Object>> bobInbox = subscribeExpectingRejection(bobSocket, "/topic/patient/" + alice.patientId());

        // Reception check-in (returns a DTO, which is the bug fixed in this phase).
        ResponseEntity<String> checkIn = call(HttpMethod.POST,
            "/api/queue/reception/check-in/" + apptId, staff.receptionistToken(), null);
        assertEquals(200, checkIn.getStatusCodeValue(), "check-in failed: " + checkIn.getBody());
        JsonNode checkedIn = json(checkIn);
        long entryId = checkedIn.path("id").asLong();
        assertEquals(token, checkedIn.path("tokenFormatted").asText(), "check-in must keep the token issued at booking");
        assertEquals("WAITING", checkedIn.path("status").asText());
        assertEquals(AppointmentStatus.CHECKED_IN, appointmentRepository.findById(apptId).orElseThrow().getStatus());
        assertEquals(QueueStatus.WAITING, queueEntryRepository.findById(entryId).orElseThrow().getStatus());

        // Doctor calls the next patient. This should push a live update to Alice.
        ResponseEntity<String> called = call(HttpMethod.POST,
            "/api/queue/doctor/" + staff.doctorId() + "/call-next", staff.doctorToken(), null);
        assertEquals(200, called.getStatusCodeValue(), "call-next failed: " + called.getBody());
        JsonNode current = json(called).path("currentPatient");
        assertEquals(entryId, current.path("id").asLong(), "the checked-in patient must be the one called");
        assertEquals(token, current.path("tokenFormatted").asText());

        QueueEntry afterCall = queueEntryRepository.findById(entryId).orElseThrow();
        assertEquals(QueueStatus.CALLED, afterCall.getStatus());
        assertEquals(AppointmentStatus.IN_QUEUE, appointmentRepository.findById(apptId).orElseThrow().getStatus());

        // Alice receives the update over the real socket.
        Map<String, Object> pushed = awaitMessage(aliceInbox, m -> carriesToken(m, token));
        assertEquals("YOUR TOKEN IS CALLED", pushed.get("message"));

        // Bob receives nothing about Alice's token.
        assertNull(bobInbox.poll(), "another patient must receive no queue data");

        // The in-app notification is persisted for Alice, and only for Alice.
        assertTrue(notificationRepository.countByRecipientUserIdAndReadFalse(alice.userId()) >= 1,
            "Alice must have a persisted unread notification after being called");
        assertEquals(0, notificationRepository.countByRecipientUserIdAndReadFalse(bob.userId()),
            "Bob must have no notification from Alice's call");

        // Doctor starts, then completes the consultation.
        ResponseEntity<String> started = call(HttpMethod.POST,
            "/api/queue/doctor/" + staff.doctorId() + "/start/" + entryId, staff.doctorToken(), null);
        assertEquals(200, started.getStatusCodeValue(), "start failed: " + started.getBody());
        assertEquals(QueueStatus.IN_CONSULTATION, queueEntryRepository.findById(entryId).orElseThrow().getStatus());

        ResponseEntity<String> completed = call(HttpMethod.POST,
            "/api/queue/doctor/" + staff.doctorId() + "/complete/" + entryId, staff.doctorToken(), Map.of());
        assertEquals(200, completed.getStatusCodeValue(), "complete failed: " + completed.getBody());
        assertEquals(QueueStatus.COMPLETED, queueEntryRepository.findById(entryId).orElseThrow().getStatus());
        assertEquals(AppointmentStatus.COMPLETED, appointmentRepository.findById(apptId).orElseThrow().getStatus(),
            "completing the consultation must complete the appointment too");

        // Analytics reflect the completed workflow (deltas against the baseline).
        JsonNode after = analyticsToday(staff);
        assertEquals(totalBefore + 1, after.path("totalAppointmentsToday").asLong(), "one more appointment today");
        assertEquals(completedBefore + 1, after.path("completedToday").asLong(), "one more completed today");
        assertEquals(waitingBefore, after.path("waitingNow").asLong(), "our appointment is no longer waiting");
        assertEquals(cancelledBefore, after.path("cancelledToday").asLong(), "nothing was cancelled");

        JsonNode docRow = doctorRowIn(after, staff.doctorId());
        assertNotNull(docRow, "the doctor who saw the patient must appear in doctor performance");
        assertEquals(1, docRow.path("totalAssigned").asLong());
        assertEquals(1, docRow.path("completedPatients").asLong());

        // Alice's own appointment list shows it as completed; Bob's does not contain it.
        JsonNode aliceList = json(call(HttpMethod.GET, "/api/appointments/my", alice.token(), null));
        boolean aliceSees = false;
        for (JsonNode a : aliceList) {
            if (a.path("id").asLong() == apptId) {
                aliceSees = true;
                assertEquals("COMPLETED", a.path("status").asText());
            }
        }
        assertTrue(aliceSees, "Alice must see her own appointment");

        JsonNode bobList = json(call(HttpMethod.GET, "/api/appointments/my", bob.token(), null));
        for (JsonNode a : bobList) {
            assertNotEquals(apptId, a.path("id").asLong(), "Bob must not see Alice's appointment");
        }
    }

    // =====================================================================
    // 2. Duplicate and unavailable slot
    // =====================================================================

    @Test
    @DisplayName("A second patient cannot book a slot that is already taken; the database keeps exactly one booking")
    void duplicateSlotRejectedAndDatabaseUnchanged() throws Exception {
        Staff staff = newStaff();
        Patient first = signupPatient("first");
        Patient second = signupPatient("second");

        ResponseEntity<String> ok = call(HttpMethod.POST, "/api/appointments", first.token(),
            bookingBody(staff, null, SLOT_0900));
        assertEquals(200, ok.getStatusCodeValue(), ok.getBody());

        ResponseEntity<String> clash = call(HttpMethod.POST, "/api/appointments", second.token(),
            bookingBody(staff, null, SLOT_0900));
        assertEquals(409, clash.getStatusCodeValue(), "a taken slot must be a conflict");
        assertTrue(clash.getBody().contains("already booked"), "message should explain the slot is taken: " + clash.getBody());

        long bookedForSlot = inTx(() -> appointmentRepository.findAll().stream()
            .filter(a -> a.getDoctor().getId().equals(staff.doctorId()))
            .filter(a -> a.getAppointmentDate().equals(FixedClockTestConfig.DAY))
            .filter(a -> a.getAppointmentTime().equals(SLOT_0900))
            .filter(a -> a.getStatus() != AppointmentStatus.CANCELLED)
            .count());
        assertEquals(1, bookedForSlot, "exactly one active booking may exist for the slot");
    }

    // =====================================================================
    // 3. Ownership on booking
    // =====================================================================

    @Test
    @DisplayName("A patient cannot book on behalf of another patient (403) and no row is created")
    void patientCannotBookForAnotherPatient() throws Exception {
        Staff staff = newStaff();
        Patient alice = signupPatient("alice-own");
        Patient mallory = signupPatient("mallory");

        ResponseEntity<String> res = call(HttpMethod.POST, "/api/appointments", mallory.token(),
            bookingBody(staff, alice.patientId(), SLOT_0930));
        assertEquals(403, res.getStatusCodeValue(), "booking for someone else must be forbidden: " + res.getBody());

        boolean created = inTx(() -> appointmentRepository.findAll().stream()
            .anyMatch(a -> a.getDoctor().getId().equals(staff.doctorId()) && a.getAppointmentTime().equals(SLOT_0930)));
        assertFalse(created, "a rejected booking must not leave a row behind");
    }

    // =====================================================================
    // 4. Unauthorized role actions must not change state
    // =====================================================================

    @Test
    @DisplayName("Wrong roles cannot check in or call the next patient; the queue and appointment are unchanged")
    void unauthorizedRoleActionsChangeNothing() throws Exception {
        Staff staff = newStaff();
        Patient alice = signupPatient("alice-roles");
        long apptId = bookAs(alice, staff, SLOT_0900);

        assertEquals(403, call(HttpMethod.POST, "/api/queue/reception/check-in/" + apptId,
            alice.token(), null).getStatusCodeValue(), "patient must not check in");
        assertEquals(403, call(HttpMethod.POST, "/api/queue/reception/check-in/" + apptId,
            staff.doctorToken(), null).getStatusCodeValue(), "doctor must not check in");
        assertEquals(403, call(HttpMethod.POST, "/api/queue/doctor/" + staff.doctorId() + "/call-next",
            alice.token(), null).getStatusCodeValue(), "patient must not call next");
        assertEquals(403, call(HttpMethod.POST, "/api/queue/doctor/" + staff.doctorId() + "/call-next",
            staff.receptionistToken(), null).getStatusCodeValue(), "reception must not call next");

        assertEquals(AppointmentStatus.CONFIRMED, appointmentRepository.findById(apptId).orElseThrow().getStatus(),
            "denied requests must leave the appointment CONFIRMED");
        // A booked patient's queue entry is BOOKED until check-in; denied requests must not advance it.
        assertEquals(QueueStatus.BOOKED, queueEntryRepository.findByAppointmentId(apptId).orElseThrow().getStatus(),
            "denied requests must leave the queue entry BOOKED");
    }

    // =====================================================================
    // 5. Invalid or missing credentials
    // =====================================================================

    @Test
    @DisplayName("Requests without a valid JWT are rejected with 401 before reaching any business logic")
    void invalidOrMissingJwtRejected() throws Exception {
        assertEquals(401, call(HttpMethod.GET, "/api/appointments/my", null, null).getStatusCodeValue(), "no token");
        assertEquals(401, call(HttpMethod.GET, "/api/appointments/my", "not.a.jwt", null).getStatusCodeValue(), "malformed token");
        assertEquals(401, call(HttpMethod.POST, "/api/queue/reception/check-in/1", "not.a.jwt", null).getStatusCodeValue(),
            "malformed token on a staff action");
    }

    // =====================================================================
    // 6. Business rule: nothing to call
    // =====================================================================

    @Test
    @DisplayName("Calling the next patient with an empty queue is refused and changes nothing")
    void callNextWithEmptyQueueRefused() throws Exception {
        Staff staff = newStaff();
        ResponseEntity<String> res = call(HttpMethod.POST,
            "/api/queue/doctor/" + staff.doctorId() + "/call-next", staff.doctorToken(), null);
        assertEquals(400, res.getStatusCodeValue(), "empty queue must be a bad request: " + res.getBody());
        assertTrue(res.getBody().contains("No waiting patients"), res.getBody());
    }

    // ---------------------------------------------------------------------

    /** Books for a patient through the real endpoint and returns the appointment id. */
    private long bookAs(Patient patient, Staff staff, LocalTime time) throws Exception {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/appointments", patient.token(),
            bookingBody(staff, null, time));
        assertEquals(200, res.getStatusCodeValue(), "booking failed: " + res.getBody());
        return json(res).path("id").asLong();
    }
}
