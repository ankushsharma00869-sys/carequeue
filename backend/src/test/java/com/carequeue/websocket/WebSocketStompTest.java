package com.carequeue.websocket;

import com.carequeue.dto.AppointmentDtos.BookAppointmentRequest;
import com.carequeue.dto.HospitalDoctorDtos.CreateDoctorRequest;
import com.carequeue.dto.HospitalDoctorDtos.DoctorDto;
import com.carequeue.e2e.FixedClockTestConfig;
import com.carequeue.e2e.StompProbe;
import com.carequeue.e2e.TestSchedules;
import com.carequeue.entity.*;
import com.carequeue.repository.*;
import com.carequeue.security.JwtTokenProvider;
import com.carequeue.security.UserPrincipal;
import com.carequeue.service.AdminStaffService;
import com.carequeue.service.AppointmentService;
import com.carequeue.service.QueueService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real STOMP over a real socket against a running server. Covers token validation at CONNECT,
 * per-topic authorization at SUBSCRIBE, and the content of the updates each topic carries.
 *
 * No sleeps: readiness is observed on the server (StompProbe), and refusals are observed as
 * SubscriptionDeniedEvent. Clock is fixed (FixedClockTestConfig).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import({FixedClockTestConfig.class, StompProbe.Config.class})
class WebSocketStompTest {

    private static final java.time.LocalDate DAY = FixedClockTestConfig.DAY;
    private static final java.time.LocalTime NINE = java.time.LocalTime.of(9, 0);

    @LocalServerPort private int port;
    @Autowired private StompProbe probe;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private AdminStaffService adminStaffService;
    @Autowired private AppointmentService appointmentService;
    @Autowired private QueueService queueService;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;

    private final List<StompSession> sessions = new ArrayList<>();

    @AfterEach
    void closeSockets() {
        for (StompSession s : sessions) {
            if (s.isConnected()) s.disconnect();
        }
        sessions.clear();
    }

    // ------------------------------------------------------------ fixtures

    private record Fixture(Doctor doctor, User doctorUser, User patientUser, Patient patient, String token, String doctorToken, long apptId, String ticket) {}

    private String tokenFor(User user, String role, Long doctorId, Long patientId) {
        UserPrincipal p = UserPrincipal.create(user, doctorId, patientId);
        return tokenProvider.generateToken(new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }

    /** A doctor with a schedule, one patient with a CONFIRMED appointment for 09:00 and a token. */
    private Fixture fixture() {
        var hospital = hospitalRepository.findAll().get(0);
        var department = departmentRepository.findAll().get(0);
        String id = UUID.randomUUID().toString().substring(0, 8);
        DoctorDto dto = adminStaffService.createDoctor(CreateDoctorRequest.builder()
            .fullName("Dr WS " + id).email("dr.ws." + id + "@test.local").password("secret123")
            .hospitalId(hospital.getId()).departmentId(department.getId())
            .licenseNumber("LIC-WS-" + id).specialization("Testing")
            .initialSchedule(TestSchedules.fullWeek()).build());
        Doctor doctor = doctorRepository.findById(dto.getId()).orElseThrow();
        User doctorUser = userRepository.findById(dto.getUserId()).orElseThrow();

        User patientUser = userRepository.save(User.builder()
            .email("pt.ws." + id + "@test.local").password("$2a$10$notarealhashjustfortest")
            .fullName("WS Patient " + id).phoneNumber("9" + Math.abs(id.hashCode() % 100000000))
            .role(Role.PATIENT).active(true).build());
        Patient patient = patientRepository.save(Patient.builder().user(patientUser).build());

        var booked = appointmentService.bookAppointment(BookAppointmentRequest.builder()
            .hospitalId(hospital.getId()).departmentId(department.getId()).doctorId(doctor.getId())
            .appointmentDate(DAY).appointmentTime(NINE).build(), patientUser.getId(), false);

        return new Fixture(doctor, doctorUser, patientUser, patient,
            tokenFor(patientUser, "PATIENT", null, patient.getId()),
            tokenFor(doctorUser, "DOCTOR", doctor.getId(), null),
            booked.getId(), booked.getTokenFormatted());
    }

    // ------------------------------------------------------------ socket helpers

    /** Connects with the given token. Returns the session, or null when CONNECT was refused. */
    private StompSession connect(String token) throws InterruptedException {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        try {
            StompSession s = client.connectAsync("ws://localhost:" + port + "/ws",
                    new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
            sessions.add(s);
            return s;
        } catch (ExecutionException | java.util.concurrent.TimeoutException refused) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
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
        probe.awaitSubscribed(destination);
        return inbox;
    }

    private Map<String, Object> next(BlockingQueue<Map<String, Object>> inbox) throws InterruptedException {
        Map<String, Object> m = inbox.poll(10, TimeUnit.SECONDS);
        assertNotNull(m, "expected a WebSocket message within 10 seconds");
        return m;
    }

    // ------------------------------------------------------------ CONNECT: token validation

    @Test
    @DisplayName("A malformed JWT is refused at CONNECT; the refusal is observed, not guessed")
    void invalidJwtRefusedAtConnect() throws Exception {
        int before = probe.connectDenials();

        StompSession session = connect("not.a.jwt");

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> probe.connectDenials() > before);
        assertTrue(session == null || !session.isConnected(), "a session with an invalid token must not stay connected");
    }

    @Test
    @DisplayName("A valid JWT connects")
    void validJwtConnects() throws Exception {
        Fixture f = fixture();
        StompSession session = connect(f.token());
        assertNotNull(session, "a valid token must be accepted");
        assertTrue(session.isConnected());
    }

    // ------------------------------------------------------------ SUBSCRIBE: authorization per topic

    @Test
    @DisplayName("Patient receives the call on their own channel: real push over the socket with the token")
    void patientReceivesCallOnOwnChannel() throws Exception {
        Fixture f = fixture();
        StompSession patient = connect(f.token());
        BlockingQueue<Map<String, Object>> inbox = subscribe(patient, "/topic/patient/" + f.patient().getId());

        queueService.checkInPatient(f.apptId());
        queueService.callNextPatient(f.doctor().getId());

        Map<String, Object> msg = next(inbox);
        assertEquals("YOUR TOKEN IS CALLED", msg.get("message"));
        assertTrue(msg.toString().contains(f.ticket()), "the call must carry this patient's token");
    }

    @Test
    @DisplayName("Doctor receives the full queue for their own topic when a patient checks in")
    void doctorReceivesOwnQueueUpdate() throws Exception {
        Fixture f = fixture();
        StompSession doctor = connect(f.doctorToken());
        BlockingQueue<Map<String, Object>> inbox = subscribe(doctor, "/topic/queue/" + f.doctor().getId());

        queueService.checkInPatient(f.apptId());

        Map<String, Object> msg = next(inbox);
        assertTrue(msg.toString().contains(f.ticket()), "the queue update must list the checked-in token");
        assertTrue(msg.containsKey("waitingQueue"), "the full queue topic carries the doctor's queue view");
    }

    @Test
    @DisplayName("Patient receives the sanitized board: tokens and status, never another patient's name")
    void boardCarriesTokensButNoNames() throws Exception {
        Fixture f = fixture();
        StompSession patient = connect(f.token());
        BlockingQueue<Map<String, Object>> inbox = subscribe(patient, "/topic/queue/" + f.doctor().getId() + "/board");

        queueService.checkInPatient(f.apptId());

        Map<String, Object> board = next(inbox);
        assertTrue(board.toString().contains(f.ticket()), "the board lists waiting tokens");
        assertFalse(board.toString().contains(f.patientUser().getFullName()), "the board must not expose patient names");
        assertFalse(board.containsKey("waitingQueue"), "the board is not the full queue view");
    }

    @Test
    @DisplayName("Patient is refused the doctor's full queue topic (explicit SubscriptionDenied; no subscription registered)")
    void patientRefusedDoctorQueueTopic() throws Exception {
        Fixture f = fixture();
        StompSession patient = connect(f.token());
        String topic = "/topic/queue/" + f.doctor().getId();

        patient.subscribe(topic, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { }
        });

        probe.awaitDenied(topic);
        assertFalse(probe.isSubscribed(topic), "a refused subscription must not be registered by the broker");
    }

    @Test
    @DisplayName("Patient is refused another patient's private channel (explicit SubscriptionDenied)")
    void patientRefusedOtherPatientChannel() throws Exception {
        Fixture alice = fixture();
        Fixture bob = fixture();
        StompSession bobSocket = connect(bob.token());
        String aliceChannel = "/topic/patient/" + alice.patient().getId();

        bobSocket.subscribe(aliceChannel, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { }
        });

        probe.awaitDenied(aliceChannel);
        assertFalse(probe.isSubscribed(aliceChannel));
    }
}
