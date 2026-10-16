package com.carequeue.security;

import com.carequeue.dto.HospitalDoctorDtos.CreateDoctorRequest;
import com.carequeue.dto.HospitalDoctorDtos.DoctorDto;
import com.carequeue.entity.*;
import com.carequeue.repository.*;
import com.carequeue.service.AdminStaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Phase 14: role boundaries and ownership, end to end through the real security filter chain.
 *
 * Nothing in the security configuration is disabled or mocked. Status codes asserted here are the
 * ones the application produces:
 *   401  unauthenticated (JwtAuthenticationEntryPoint)
 *   403  authenticated but role or ownership denied
 *   404  a queue entry that exists but belongs to another doctor. assertBelongsToDoctor reports
 *        "not found" on purpose, so one doctor cannot confirm that another doctor's entry exists.
 *
 * "Allowed" assertions check only that authorization did not reject the request (not 401/403).
 * They do not assert business success: these tests are about who may call what.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RoleBoundarySecurityTest {

    private static final LocalDate DAY = LocalDate.of(2091, 3, 15);

    /** Valid emergency body, so only the method-level role check can reject a PATIENT. */
    private static final String VALID_EMERGENCY_JSON =
        "{\"hospitalId\":1,\"departmentId\":1,\"patientName\":\"Role Test\"," +
        "\"triageCondition\":\"sec\",\"severity\":\"HIGH\"}";

    @Autowired private MockMvc mvc;
    @Autowired private AdminStaffService adminStaffService;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private QueueEntryRepository queueEntryRepository;
    @Autowired private EmergencyRequestRepository emergencyRequestRepository;

    private Hospital hospital;
    private Department department;

    private Doctor docA;
    private Doctor docB;
    private User docAUser;
    private User docBUser;
    private User userA;
    private User userB;
    private Patient patientA;
    private Patient patientB;
    private Appointment apptA;   // patient A with doctor A
    private Appointment apptB;   // patient B with doctor B
    private QueueEntry entryB;   // doctor B's queue entry for apptB

    @BeforeEach
    void setUp() {
        hospital = hospitalRepository.findAll().get(0);
        department = departmentRepository.findAll().get(0);

        DoctorDto dtoA = createDoctor();
        DoctorDto dtoB = createDoctor();
        docA = doctorRepository.findById(dtoA.getId()).orElseThrow();
        docB = doctorRepository.findById(dtoB.getId()).orElseThrow();
        // Load users by id, not through Doctor.getUser(), which is a lazy proxy outside a transaction.
        docAUser = userRepository.findById(dtoA.getUserId()).orElseThrow();
        docBUser = userRepository.findById(dtoB.getUserId()).orElseThrow();

        userA = newPatientUser();
        userB = newPatientUser();
        patientA = patientRepository.findByUserId(userA.getId()).orElseThrow();
        patientB = patientRepository.findByUserId(userB.getId()).orElseThrow();

        apptA = appointment(patientA, docA, LocalTime.of(9, 0));
        apptB = appointment(patientB, docB, LocalTime.of(9, 30));

        entryB = queueEntryRepository.save(QueueEntry.builder()
            .doctor(docB).patient(patientB).appointment(apptB).scheduleDate(DAY)
            .tokenNumber(1).tokenFormatted("A-001")
            .priority(QueuePriority.NORMAL).priorityRank(QueuePriority.NORMAL.getRank())
            .status(QueueStatus.WAITING).estimatedWaitMinutes(0).build());
    }

    // ------------------------------------------------------------------ principals

    private static UserPrincipal principal(long userId, String email, String role, Long doctorId, Long patientId) {
        return new UserPrincipal(userId, email, "not-used-in-tests", role + " user",
            List.of(new SimpleGrantedAuthority("ROLE_" + role)), doctorId, patientId);
    }

    private static UserPrincipal admin() {
        return principal(9_000_001L, "admin@test.local", "ADMIN", null, null);
    }

    private static UserPrincipal receptionist() {
        return principal(9_000_002L, "reception@test.local", "RECEPTIONIST", null, null);
    }

    private UserPrincipal doctorA() {
        return principal(docAUser.getId(), docAUser.getEmail(), "DOCTOR", docA.getId(), null);
    }

    private UserPrincipal doctorB() {
        return principal(docBUser.getId(), docBUser.getEmail(), "DOCTOR", docB.getId(), null);
    }

    private UserPrincipal patientA() {
        return principal(userA.getId(), userA.getEmail(), "PATIENT", null, patientA.getId());
    }

    private UserPrincipal patientB() {
        return principal(userB.getId(), userB.getEmail(), "PATIENT", null, patientB.getId());
    }

    private static RequestPostProcessor as(UserPrincipal who) {
        return authentication(new UsernamePasswordAuthenticationToken(who, null, who.getAuthorities()));
    }

    // ------------------------------------------------------------------ request helpers

    private int status(MockHttpServletRequestBuilder req, UserPrincipal who) throws Exception {
        return mvc.perform(req.with(as(who))).andReturn().getResponse().getStatus();
    }

    private String body(MockHttpServletRequestBuilder req, UserPrincipal who) throws Exception {
        return mvc.perform(req.with(as(who))).andReturn().getResponse().getContentAsString();
    }

    private int anonStatus(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder postJson(String url) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private void assertForbidden(MockHttpServletRequestBuilder req, UserPrincipal who, String why) throws Exception {
        assertEquals(403, status(req, who), why);
    }

    private void assertAuthorized(MockHttpServletRequestBuilder req, UserPrincipal who, String why) throws Exception {
        int s = status(req, who);
        assertNotEquals(401, s, why + " (rejected as unauthenticated)");
        assertNotEquals(403, s, why + " (rejected as forbidden)");
    }

    // ------------------------------------------------------------------ fixtures

    private DoctorDto createDoctor() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return adminStaffService.createDoctor(CreateDoctorRequest.builder()
            .fullName("Dr Sec " + id).email("dr.sec." + id + "@test.local").password("secret123")
            .phoneNumber("8" + Math.abs(id.hashCode() % 100000000))
            .hospitalId(hospital.getId()).departmentId(department.getId())
            .licenseNumber("LIC-SEC-" + id).specialization("Testing").build());
    }

    private User newPatientUser() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder()
            .email("pt.sec." + id + "@test.local").password("$2a$10$notarealhashjustfortest")
            .fullName("Sec Patient " + id).phoneNumber("9" + Math.abs(id.hashCode() % 100000000))
            .role(Role.PATIENT).active(true).build());
        patientRepository.save(Patient.builder().user(u).build());
        return u;
    }

    private Appointment appointment(Patient p, Doctor d, LocalTime time) {
        return appointmentRepository.save(Appointment.builder()
            .appointmentNumber("SEC-" + UUID.randomUUID())
            .patient(p).doctor(d).hospital(hospital).department(department)
            .appointmentDate(DAY).appointmentTime(time).status(AppointmentStatus.CONFIRMED)
            .tokenFormatted("A-001").build());
    }

    private EmergencyRequest emergency(Doctor d, String patientName, String token) {
        return emergencyRequestRepository.save(EmergencyRequest.builder()
            .patientName(patientName).triageCondition("sec test")
            .severity(EmergencySeverity.HIGH).tokenFormatted(token)
            .doctor(d).department(department).hospital(hospital).build());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    // ================================================================== A. role boundaries

    @Test
    @DisplayName("A1 Admin-only endpoints reject PATIENT, DOCTOR and RECEPTIONIST with 403")
    void adminOnlyEndpointsRejectNonAdmins() throws Exception {
        for (UserPrincipal p : List.of(patientA(), doctorA(), receptionist())) {
            String role = p.getAuthorities().iterator().next().getAuthority();
            assertForbidden(get("/api/admin/audit-logs"), p, "audit logs as " + role);
            assertForbidden(postJson("/api/admin/doctors"), p, "create doctor as " + role);
            assertForbidden(postJson("/api/admin/receptionists"), p, "create receptionist as " + role);
            assertForbidden(postJson("/api/admin/staff"), p, "create staff as " + role);
            assertForbidden(patch("/api/admin/doctors/" + docA.getId() + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"),
                p, "deactivate doctor as " + role);
        }
    }

    @Test
    @DisplayName("A2 Admin passes authorization on admin endpoints")
    void adminAllowedOnAdminEndpoints() throws Exception {
        assertAuthorized(get("/api/admin/audit-logs"), admin(), "admin reads audit logs");
        assertAuthorized(postJson("/api/admin/doctors"), admin(), "admin reaches doctor provisioning");
    }

    @Test
    @DisplayName("A3 Reception check-in: PATIENT and DOCTOR forbidden; RECEPTIONIST and ADMIN allowed")
    void checkInIsReceptionOnly() throws Exception {
        String url = "/api/queue/reception/check-in/" + apptA.getId();
        assertForbidden(postJson(url), patientA(), "patient check-in");
        assertForbidden(postJson(url), doctorA(), "doctor check-in");
        assertAuthorized(postJson(url), receptionist(), "reception check-in");
        assertAuthorized(postJson(url), admin(), "admin check-in");
    }

    @Test
    @DisplayName("A4 Call-next: PATIENT and RECEPTIONIST forbidden; the doctor (own queue) and ADMIN allowed")
    void callNextIsDoctorOrAdmin() throws Exception {
        String url = "/api/queue/doctor/" + docA.getId() + "/call-next";
        assertForbidden(postJson(url), patientA(), "patient call-next");
        assertForbidden(postJson(url), receptionist(), "receptionist call-next");
        assertAuthorized(postJson(url), doctorA(), "doctor call-next on own queue");
        assertAuthorized(postJson(url), admin(), "admin call-next");
    }

    @Test
    @DisplayName("A5 Full doctor queue (with patient names and phones): PATIENT forbidden; own doctor and reception allowed")
    void fullQueueViewRestricted() throws Exception {
        String url = "/api/queue/doctor/" + docA.getId();
        assertForbidden(get(url), patientA(), "patient reads full queue");
        assertAuthorized(get(url), doctorA(), "doctor reads own queue");
        assertAuthorized(get(url), receptionist(), "receptionist reads queue");
    }

    @Test
    @DisplayName("A6 Today's appointments and per-doctor appointment lists are staff-only")
    void appointmentListsAreStaffOnly() throws Exception {
        assertForbidden(get("/api/appointments/today"), patientA(), "patient reads all appointments today");
        assertForbidden(get("/api/appointments/today"), doctorA(), "doctor reads all appointments today");
        assertAuthorized(get("/api/appointments/today"), receptionist(), "reception reads today");

        assertForbidden(get("/api/appointments/doctor/" + docA.getId()), patientA(), "patient reads a doctor's list");
        assertAuthorized(get("/api/appointments/doctor/" + docA.getId()), doctorA(), "doctor reads own list");
    }

    @Test
    @DisplayName("A7 Emergency: PATIENT can neither triage nor list emergencies")
    void emergencyNotForPatients() throws Exception {
        MockHttpServletRequestBuilder submit = post("/api/emergency")
            .contentType(MediaType.APPLICATION_JSON).content(VALID_EMERGENCY_JSON);
        assertForbidden(submit, patientA(), "patient submits emergency (valid body, so only the role check can deny)");
        assertForbidden(get("/api/emergency"), patientA(), "patient lists emergencies");
    }

    @Test
    @DisplayName("A8 Analytics: PATIENT and DOCTOR forbidden; ADMIN and RECEPTIONIST allowed")
    void analyticsRestricted() throws Exception {
        assertForbidden(get("/api/analytics/overview"), patientA(), "patient analytics");
        assertForbidden(get("/api/analytics/overview"), doctorA(), "doctor analytics");
        assertAuthorized(get("/api/analytics/overview"), admin(), "admin analytics");
        assertAuthorized(get("/api/analytics/overview"), receptionist(), "reception analytics");
    }

    @Test
    @DisplayName("A9 Anonymous requests to protected endpoints are rejected with 401")
    void anonymousIsUnauthenticated() throws Exception {
        assertEquals(401, anonStatus(get("/api/admin/audit-logs")));
        assertEquals(401, anonStatus(get("/api/queue/doctor/" + docA.getId())));
        assertEquals(401, anonStatus(get("/api/appointments/my")));
        assertEquals(401, anonStatus(get("/api/notifications")));
        assertEquals(401, anonStatus(postJson("/api/emergency")));
    }

    @Test
    @DisplayName("A10 Public doctor directory is readable anonymously and exposes no contact details for the listed doctor")
    void publicDirectoryHasNoContactDetails() throws Exception {
        String json = mvc.perform(get("/api/doctors")).andReturn().getResponse().getContentAsString();
        assertTrue(json.contains(docAUser.getFullName()), "directory should list the active doctor");
        // Specific to this doctor's contact details. A generic "@test.local" check would also match
        // staff created by other tests in the same database.
        assertFalse(json.contains(docAUser.getEmail()), "doctor's login email must not be public");
        assertFalse(json.contains(docAUser.getPhoneNumber()), "doctor's phone must not be public");
    }

    // ================================================================== B. ownership

    @Test
    @DisplayName("B1 A patient cannot read another patient's live queue (403); can read their own")
    void patientLiveQueueOwnership() throws Exception {
        assertForbidden(get("/api/queue/patient/appointment/" + apptB.getId()), patientA(),
            "patient A reads patient B's live queue");
        assertAuthorized(get("/api/queue/patient/appointment/" + apptB.getId()), patientB(),
            "patient B reads own live queue");
    }

    @Test
    @DisplayName("B2 A doctor sees live queue only for their own assigned appointment (403 for another doctor's)")
    void doctorLiveQueueOwnership() throws Exception {
        assertForbidden(get("/api/queue/patient/appointment/" + apptB.getId()), doctorA(),
            "doctor A reads doctor B's patient's live queue");
        assertEquals(200, status(get("/api/queue/patient/appointment/" + apptB.getId()), doctorB()),
            "doctor B reads own patient's live queue");
    }

    @Test
    @DisplayName("B3 Receptionist can read live queue for any appointment")
    void receptionLiveQueueAnyAppointment() throws Exception {
        assertEquals(200, status(get("/api/queue/patient/appointment/" + apptB.getId()), receptionist()));
    }

    @Test
    @DisplayName("B4 A doctor cannot view another doctor's queue (403)")
    void doctorCannotViewOtherQueue() throws Exception {
        assertForbidden(get("/api/queue/doctor/" + docB.getId()), doctorA(), "doctor A views doctor B's queue");
    }

    @Test
    @DisplayName("B5 A doctor cannot call next patient on another doctor's queue (403)")
    void doctorCannotCallNextOnOtherQueue() throws Exception {
        assertForbidden(postJson("/api/queue/doctor/" + docB.getId() + "/call-next"), doctorA(),
            "doctor A calls next on doctor B's queue");
    }

    @Test
    @DisplayName("B6 A doctor cannot act on another doctor's queue entry even with their own id in the URL (404), and the entry is unchanged")
    void doctorCannotActOnOtherQueueEntry() throws Exception {
        // The URL carries doctor A's id, so the controller's self-check passes. The entry belongs to
        // doctor B, so the service's assertBelongsToDoctor must reject it.
        String base = "/api/queue/doctor/" + docA.getId();
        assertEquals(404, status(postJson(base + "/start/" + entryB.getId()), doctorA()), "start");
        assertEquals(404, status(postJson(base + "/complete/" + entryB.getId()), doctorA()), "complete");
        assertEquals(404, status(postJson(base + "/skip/" + entryB.getId()), doctorA()), "skip");
        assertEquals(404, status(postJson(base + "/no-show/" + entryB.getId()), doctorA()), "no-show");

        QueueEntry reloaded = queueEntryRepository.findById(entryB.getId()).orElseThrow();
        assertEquals(QueueStatus.WAITING, reloaded.getStatus(), "another doctor's entry must not be mutated");
    }

    @Test
    @DisplayName("B7 A doctor cannot list another doctor's appointments (403)")
    void doctorCannotListOtherAppointments() throws Exception {
        assertForbidden(get("/api/appointments/doctor/" + docB.getId()), doctorA(),
            "doctor A lists doctor B's appointments");
    }

    @Test
    @DisplayName("B8 A patient cannot cancel another patient's appointment (403), and it stays CONFIRMED")
    void patientCannotCancelOthersAppointment() throws Exception {
        assertForbidden(patch("/api/appointments/" + apptB.getId() + "/cancel")
            .contentType(MediaType.APPLICATION_JSON).content("{}"), patientA(),
            "patient A cancels patient B's appointment");

        Appointment reloaded = appointmentRepository.findById(apptB.getId()).orElseThrow();
        assertEquals(AppointmentStatus.CONFIRMED, reloaded.getStatus());
    }

    @Test
    @DisplayName("B9 A doctor sees only emergencies assigned to them; reception sees all")
    void emergencyVisibilityFollowsAssignment() throws Exception {
        String nameA = "EmergA-" + shortId();
        String nameB = "EmergB-" + shortId();
        emergency(docA, nameA, "E-" + shortId());
        emergency(docB, nameB, "E-" + shortId());

        // Diagnostic form: on failure the message carries the HTTP status and the response body,
        // so a 403/500 is distinguishable from "the row is missing from the list".
        var doctorResult = mvc.perform(get("/api/emergency").with(as(doctorA()))).andReturn();
        String asDoctorA = doctorResult.getResponse().getContentAsString();
        assertEquals(200, doctorResult.getResponse().getStatus(),
            "doctor A emergency list status; body=" + asDoctorA);
        assertTrue(asDoctorA.contains(nameA),
            "doctor A should see their own emergency " + nameA + "; body=" + asDoctorA);
        assertFalse(asDoctorA.contains(nameB), "doctor A must not see doctor B's emergency");

        String asReception = body(get("/api/emergency"), receptionist());
        assertTrue(asReception.contains(nameA) && asReception.contains(nameB),
            "receptionist should see emergencies for all doctors; body=" + asReception);
    }

    @Test
    @DisplayName("B11 The shared queue board is readable by a patient but contains no other patient's name or phone")
    void queueBoardHasNoPersonalData() throws Exception {
        // Board is patient-visible by design; it must carry tokens and status only.
        // Queried on the entry's date so the test does not depend on "today".
        String url = "/api/queue/doctor/" + docB.getId() + "/board?date=" + DAY;

        int code = status(get(url), patientA());
        assertEquals(200, code, "patient should be able to read the sanitized board");

        String json = body(get(url), patientA());
        assertTrue(json.contains("A-001"), "board should show the token numbers of the queue");
        assertFalse(json.contains(userB.getFullName()), "another patient's name must not appear on the board");
        assertFalse(json.contains(userB.getPhoneNumber()), "another patient's phone must not appear on the board");
        assertFalse(json.contains("patientName") || json.contains("patientPhone"),
            "board DTO must not carry patient identity fields");
    }

    @Test
    @DisplayName("B10 The emergency list never serializes credentials or patient phone numbers, for any permitted role")
    void emergencyResponseHasNoCredentials() throws Exception {
        emergency(docA, "CredCheck-" + shortId(), "E-CRED1");
        for (UserPrincipal p : List.of(admin(), receptionist(), doctorA())) {
            String json = body(get("/api/emergency"), p);
            String role = p.getAuthorities().iterator().next().getAuthority();
            assertFalse(json.contains("\"password\""), "password field serialized for " + role);
            assertFalse(json.contains("$2a$") || json.contains("$2b$"), "bcrypt hash serialized for " + role);
            assertFalse(json.contains("\"patientPhone\""), "patient phone must not be listed for " + role);
        }
    }
}
