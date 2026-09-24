package io.travelos.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.common.tenant.TenantId;
import io.travelos.context.model.Employee;
import io.travelos.context.service.ProfileService;
import io.travelos.context.store.EmployeeRepository;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.context.v1.ArrangerAuthorization;
import io.travelos.contracts.context.v1.AuthorizeArrangerRequest;
import io.travelos.contracts.context.v1.EnterpriseContextServiceGrpc;
import io.travelos.contracts.context.v1.GetTravelerSnapshotRequest;
import io.travelos.contracts.context.v1.TravelerSnapshotResponse;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.spring.web.testing.TestTokens;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traveler profiles, documents, arranger grants, restricted projects, guests and offboarding
 * (ADR-0014): what is encrypted at rest, who may see what, that every disclosure is logged, and the
 * answers Travel Core gets over gRPC. Real Postgres; real REST and gRPC.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestTokens.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProfileIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  static final TenantId ACME = TenantId.of("acme");
  static final String ALICE = "emp_1001", BOB = "emp_1002", CAROL = "emp_1003", DAN = "emp_1004";
  static final String ERIN = "emp_1005", FRANK = "emp_1006";

  @Autowired Environment environment;
  @Autowired GrpcServerLifecycle grpc;
  @Autowired EmployeeRepository employees;
  @Autowired ProfileService profiles;
  @Autowired JdbcClient jdbc;

  private final JsonMapper json = JsonMapper.builder().build();
  private RestClient http;
  private ManagedChannel channel;
  private EnterpriseContextServiceGrpc.EnterpriseContextServiceBlockingStub context;
  private String passportId;
  private String erinGrant;
  private String projectId;
  private String guestId;

  static String erin() {
    return TestTokens.user("erin", "acme", ERIN, List.of("TRAVELER"));
  }

  /** A manager by realm role who manages nobody in the HRIS. */
  static String frank() {
    return TestTokens.user("frank", "acme", FRANK, List.of("TRAVELER", "MANAGER"));
  }

  @BeforeAll
  void setUp() {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (rq, rs) -> {})
            .build();
    channel = ManagedChannelBuilder.forAddress("localhost", grpc.port()).usePlaintext().build();
    context = EnterpriseContextServiceGrpc.newBlockingStub(channel);
    Instant now = Instant.parse("2026-09-01T00:00:00Z");
    ZoneId ny = ZoneId.of("America/New_York");
    employees.upsert(
        employee(ALICE, "alice@acme.example", "Alice Nguyen", BOB, now, ny, "dept_eng"));
    employees.upsert(employee(BOB, "bob@acme.example", "Bob Chen", null, now, ny, "dept_eng"));
    employees.upsert(employee(CAROL, "carol@acme.example", "Carol Diaz", null, now, ny, null));
    employees.upsert(employee(DAN, "dan@acme.example", "Dan Ola", BOB, now, ny, "dept_eng"));
    employees.upsert(
        employee(ERIN, "erin@acme.example", "Erin Assist", CAROL, now, ny, "dept_ops"));
    employees.upsert(
        employee(FRANK, "frank@acme.example", "Frank Other", null, now, ny, "dept_sales"));
  }

  private static Employee employee(
      String id, String email, String name, String manager, Instant now, ZoneId tz, String dept) {
    return new Employee(
        ACME, id, email, name, "BOS", tz, manager, true, 1, now, dept, null, null, null);
  }

  @AfterAll
  void tearDown() {
    channel.shutdownNow();
  }

  // ================================================================== 1. own profile

  @Test
  @Order(1)
  void ownProfileIsEncryptedAtRestAndRedactedByDefault() {
    ResponseEntity<String> updated =
        put(
            "/api/v1/travelers/me",
            TestTokens.alice(),
            """
            {"givenName":"Alice","familyName":"Nguyen","email":"alice@acme.example",
             "phone":"+16175550101","dateOfBirth":"1990-04-12","nationality":"US","homeAirport":"BOS",
             "loyalty":[{"program":"UA","memberNumber":"UA123456789"}],
             "emergencyContact":{"name":"Nora Nguyen","phone":"+16175550102","relation":"sister"},
             "preferences":{"seat":"aisle","meal":"VGML"}}
            """);
    assertThat(updated.getStatusCode().value()).as(updated.getBody()).isEqualTo(200);
    JsonNode v = json.readTree(updated.getBody());
    assertThat(v.get("version").asLong()).isEqualTo(1);
    assertThat(v.get("relation").asString()).isEqualTo("SELF");

    // redacted by default: no phone, no date of birth, loyalty numbers as last four only
    JsonNode me = json.readTree(get("/api/v1/travelers/me", TestTokens.alice()).getBody());
    assertThat(me.get("phone").isNull()).isTrue();
    assertThat(me.get("dateOfBirth").isNull()).isTrue();
    assertThat(me.get("emergencyContact").isNull()).isTrue();
    assertThat(me.get("loyalty").get(0).get("memberNumber").isNull()).isTrue();
    assertThat(me.get("loyalty").get(0).get("memberNumberLast4").asString()).isEqualTo("****6789");
    assertThat(me.get("preferences").get("seat").asString()).isEqualTo("aisle");

    // revealed on request, and logged
    JsonNode full =
        json.readTree(get("/api/v1/travelers/me?reveal=true", TestTokens.alice()).getBody());
    assertThat(full.get("phone").asString()).isEqualTo("+16175550101");
    assertThat(full.get("dateOfBirth").asString()).isEqualTo("1990-04-12");
    assertThat(full.get("loyalty").get(0).get("memberNumber").asString()).isEqualTo("UA123456789");
    assertThat(full.get("emergencyContact").get("name").asString()).isEqualTo("Nora Nguyen");
    JsonNode log =
        json.readTree(
            get("/api/v1/travelers/" + ALICE + "/access-log", TestTokens.alice()).getBody());
    assertThat(log).isNotEmpty();
    assertThat(log.get(0).get("principal").asString()).isEqualTo("human/alice");
    assertThat(log.get(0).get("purpose").asString()).isEqualTo("SELF_VIEW");

    // at rest: ciphertext only
    String phoneEnc =
        jdbc.sql("SELECT phone_enc FROM traveler_profile WHERE traveler_id = :id")
            .param("id", ALICE)
            .query(String.class)
            .single();
    assertThat(phoneEnc).startsWith("v1:").doesNotContain("6175550101");
    String loyaltyEnc =
        jdbc.sql("SELECT loyalty_enc FROM traveler_profile WHERE traveler_id = :id")
            .param("id", ALICE)
            .query(String.class)
            .single();
    assertThat(loyaltyEnc).startsWith("v1:").doesNotContain("UA123456789");

    // change history names the fields, never the values
    JsonNode changes =
        json.readTree(get("/api/v1/travelers/" + ALICE + "/changes", TestTokens.alice()).getBody());
    assertThat(changes.get(0).get("fields").toString())
        .contains("phone")
        .doesNotContain("6175550101");

    // a stale version is refused
    ResponseEntity<String> stale =
        put(
            "/api/v1/travelers/me",
            TestTokens.alice(),
            "{\"homeAirport\":\"JFK\",\"expectedVersion\":0}");
    assertThat(stale.getStatusCode().value()).isEqualTo(409);
    assertThat(stale.getBody()).contains("PROFILE_VERSION_STALE");
  }

  // ================================================================== 2. documents

  @Test
  @Order(2)
  void documentNumbersAreEncryptedAndRevealedOnlyToThoseAllowedWithALog() {
    ResponseEntity<String> added =
        post(
            "/api/v1/travelers/" + ALICE + "/documents",
            TestTokens.alice(),
            """
            {"type":"PASSPORT","number":"X12 3456789","issuingCountry":"US","expiresOn":"2031-01-15"}
            """);
    assertThat(added.getStatusCode().value()).as(added.getBody()).isEqualTo(201);
    JsonNode doc = json.readTree(added.getBody());
    passportId = doc.get("documentId").asString();
    assertThat(passportId).startsWith("doc_");
    assertThat(doc.get("number").isNull()).isTrue();
    assertThat(doc.get("numberLast4").asString()).isEqualTo("****6789");
    assertThat(doc.get("holderGivenName").asString()).isEqualTo("Alice");
    String enc =
        jdbc.sql("SELECT number_enc FROM travel_document WHERE document_id = :id")
            .param("id", passportId)
            .query(String.class)
            .single();
    assertThat(enc).startsWith("v1:").doesNotContain("123456789");

    // the list is masked for everyone; the number is a separate, logged read
    JsonNode listed =
        json.readTree(
            get("/api/v1/travelers/" + ALICE + "/documents", TestTokens.alice()).getBody());
    assertThat(listed.get(0).get("number").isNull()).isTrue();
    ResponseEntity<String> self =
        get(
            "/api/v1/travelers/documents/" + passportId + "/number?purpose=BOOKING",
            TestTokens.alice());
    assertThat(self.getStatusCode().value()).isEqualTo(200);
    assertThat(json.readTree(self.getBody()).get("number").asString()).isEqualTo("X123456789");
    // the HRIS manager sees the profile but never the document number
    ResponseEntity<String> manager =
        get("/api/v1/travelers/documents/" + passportId + "/number", TestTokens.bob());
    assertThat(manager.getStatusCode().value()).isEqualTo(403);
    assertThat(manager.getBody()).contains("DOCUMENT_ACCESS_DENIED");
    assertThat(get("/api/v1/travelers/" + ALICE, TestTokens.bob()).getStatusCode().value())
        .isEqualTo(200);
    assertThat(
            json.readTree(get("/api/v1/travelers/" + ALICE, TestTokens.bob()).getBody())
                .get("relation")
                .asString())
        .isEqualTo("MANAGER");
    // an unrelated manager-by-role learns nothing, not even that the traveler exists
    assertThat(get("/api/v1/travelers/" + ALICE, frank()).getStatusCode().value()).isEqualTo(404);
    assertThat(
            get("/api/v1/travelers/documents/" + passportId + "/number", frank())
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(get("/api/v1/travelers/" + ALICE, erin()).getStatusCode().value()).isEqualTo(404);
    JsonNode log =
        json.readTree(
            get("/api/v1/travelers/" + ALICE + "/access-log", TestTokens.alice()).getBody());
    assertThat(log.toString()).contains("DOCUMENT:" + passportId).contains("BOOKING");
  }

  // ================================================================== 3. arranger grants

  @Test
  @Order(3)
  void arrangerGrantsAreExplicitAndDocumentAccessIsASeparatePermission() {
    // nobody grants themselves
    ResponseEntity<String> selfGrant =
        post(
            "/api/v1/arrangers",
            erin(),
            "{\"arrangerEmployeeId\":\""
                + ERIN
                + "\",\"scope\":\"EMPLOYEE\",\"scopeId\":\""
                + ALICE
                + "\"}");
    assertThat(selfGrant.getStatusCode().value()).isEqualTo(403);
    // a travel admin grants Erin the right to arrange for Alice, without documents
    ResponseEntity<String> granted =
        post(
            "/api/v1/arrangers",
            TestTokens.carol(),
            "{\"arrangerEmployeeId\":\""
                + ERIN
                + "\",\"scope\":\"EMPLOYEE\",\"scopeId\":\""
                + ALICE
                + "\",\"mayReadDocuments\":false}");
    assertThat(granted.getStatusCode().value()).as(granted.getBody()).isEqualTo(201);
    erinGrant = json.readTree(granted.getBody()).get("grantId").asString();
    JsonNode view =
        json.readTree(
            get("/api/v1/travelers/" + ALICE + "?reveal=true&purpose=TRIP_ARRANGING", erin())
                .getBody());
    assertThat(view.get("relation").asString()).isEqualTo("GRANT");
    assertThat(view.get("phone").asString()).isEqualTo("+16175550101");
    ResponseEntity<String> number =
        get("/api/v1/travelers/documents/" + passportId + "/number?purpose=BOOKING", erin());
    assertThat(number.getStatusCode().value()).isEqualTo(403);

    // gRPC: the answer Travel Core gets
    ArrangerAuthorization erinForAlice = authorize(ERIN, List.of("TRAVELER"), ALICE, "");
    assertThat(erinForAlice.getAllowed()).isTrue();
    assertThat(erinForAlice.getBasis()).isEqualTo("GRANT");
    assertThat(erinForAlice.getMayReadDocuments()).isFalse();
    ArrangerAuthorization frankForAlice =
        authorize(FRANK, List.of("TRAVELER", "MANAGER"), ALICE, "");
    assertThat(frankForAlice.getAllowed()).isFalse();
    assertThat(frankForAlice.getReasonCode()).isEqualTo("NOT_AN_ARRANGER");
    assertThat(authorize(BOB, List.of("TRAVELER", "MANAGER"), ALICE, "").getBasis())
        .isEqualTo("MANAGER");
    assertThat(authorize(CAROL, List.of("TRAVEL_ADMIN"), ALICE, "").getBasis())
        .isEqualTo("TRAVEL_ADMIN");
    assertThat(authorize(ALICE, List.of("TRAVELER"), ALICE, "").getBasis()).isEqualTo("SELF");
    // roles in the request do not conjure a relationship: claiming TRAVEL_ADMIN in a request field
    // is still just a role the token would have to carry; a bare MANAGER claim opens nothing.
    assertThat(authorize(FRANK, List.of("MANAGER"), DAN, "").getAllowed()).isFalse();

    // a second grant with documents: the number becomes readable, and the read is logged
    ResponseEntity<String> withDocs =
        post(
            "/api/v1/arrangers",
            TestTokens.carol(),
            "{\"arrangerEmployeeId\":\""
                + ERIN
                + "\",\"scope\":\"ORG_UNIT\",\"scopeId\":\"dept_eng\",\"mayReadDocuments\":true}");
    // scopeId must be a known unit
    assertThat(withDocs.getStatusCode().value()).isEqualTo(422);
    ResponseEntity<String> unit =
        post(
            "/api/v1/org/units",
            TestTokens.carol(),
            "{\"kind\":\"DEPARTMENT\",\"code\":\"ENG\",\"name\":\"Engineering\"}");
    assertThat(unit.getStatusCode().value()).as(unit.getBody()).isEqualTo(201);
    String unitId = json.readTree(unit.getBody()).get("unitId").asString();
    jdbc.sql("UPDATE employee SET department_id = :u WHERE department_id = 'dept_eng'")
        .param("u", unitId)
        .update();
    withDocs =
        post(
            "/api/v1/arrangers",
            TestTokens.carol(),
            "{\"arrangerEmployeeId\":\""
                + ERIN
                + "\",\"scope\":\"ORG_UNIT\",\"scopeId\":\""
                + unitId
                + "\",\"mayReadDocuments\":true}");
    assertThat(withDocs.getStatusCode().value()).as(withDocs.getBody()).isEqualTo(201);
    number = get("/api/v1/travelers/documents/" + passportId + "/number?purpose=BOOKING", erin());
    assertThat(number.getStatusCode().value()).isEqualTo(200);
    assertThat(json.readTree(number.getBody()).get("number").asString()).isEqualTo("X123456789");
    JsonNode log =
        json.readTree(
            get("/api/v1/travelers/" + ALICE + "/access-log", TestTokens.alice()).getBody());
    assertThat(log.get(0).get("principal").asString()).isEqualTo("human/erin");
    // the org-unit grant covers Dan too (same department), documents included
    TravelerSnapshotResponse snap = snapshot(ERIN, List.of("TRAVELER"), DAN, true, "BOOKING", "");
    assertThat(snap.getPassenger().getGivenName()).isEqualTo("Dan");
    assertThat(snap.getPassenger().getFamilyName()).isEqualTo("Ola");
    assertThat(snap.getAllocation().getManagerEmployeeId()).isEqualTo(BOB);
    assertThat(snap.getAllocation().getDepartmentCode()).isEqualTo("ENG");
    // the snapshot of Alice for Erin carries the passport number; for Frank there is no Alice
    TravelerSnapshotResponse alice =
        snapshot(ERIN, List.of("TRAVELER"), ALICE, true, "BOOKING", "");
    assertThat(alice.getPassenger().getDocumentsList()).hasSize(1);
    assertThat(alice.getPassenger().getDocuments(0).getNumber()).isEqualTo("X123456789");
    assertThat(alice.getPassenger().getPhone()).isEqualTo("+16175550101");
    assertThat(alice.getPassenger().getLoyalty(0).getMemberNumber()).isEqualTo("UA123456789");
    Assertions.assertThatThrownBy(
            () -> snapshot(FRANK, List.of("TRAVELER", "MANAGER"), ALICE, true, "BOOKING", ""))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
        .isEqualTo(Status.Code.NOT_FOUND);
    // Bob, the manager, gets names and allocation but neither documents nor phone
    TravelerSnapshotResponse forBob =
        snapshot(BOB, List.of("TRAVELER", "MANAGER"), ALICE, true, "APPROVAL", "");
    assertThat(forBob.getPassenger().getPhone()).isEmpty();
    assertThat(forBob.getPassenger().getDocuments(0).getNumber()).isEmpty();
    assertThat(forBob.getPassenger().getDocuments(0).getNumberLast4()).isEqualTo("****6789");
  }

  // ================================================================== 3b. booking by the platform

  @Test
  @Order(4)
  void thePlatformsOwnAgentReadsThePassengerForBookingAndTheReadIsLogged() {
    // Phase 4: the trip-planner agent, executing an authorized booking, gets what a supplier needs
    TravelerSnapshotResponse forBooking =
        context.getTravelerSnapshot(
            GetTravelerSnapshotRequest.newBuilder()
                .setCtx(
                    RequestContext.newBuilder()
                        .setTenantId("acme")
                        .setCorrelationId(UUID.randomUUID().toString())
                        .setPrincipal(
                            Principal.newBuilder()
                                .setKind(Principal.Kind.AGENT)
                                .setId("agent/trip-planner/v1")))
                .setTravelerId(ALICE)
                .setIncludeDocuments(true)
                .setPurpose("BOOKING")
                .build());
    assertThat(forBooking.getPassenger().getDateOfBirth()).isEqualTo("1990-04-12");
    assertThat(forBooking.getPassenger().getPhone()).isEqualTo("+16175550101");
    assertThat(forBooking.getPassenger().getDocuments(0).getNumber()).isEqualTo("X123456789");
    JsonNode log =
        json.readTree(
            get("/api/v1/travelers/" + ALICE + "/access-log", TestTokens.alice()).getBody());
    assertThat(log.get(0).get("principal").asString()).isEqualTo("agent/trip-planner/v1");
    assertThat(log.get(0).get("purpose").asString()).isEqualTo("BOOKING");
    // the same agent asking for anything but a booking is nobody
    Assertions.assertThatThrownBy(
            () ->
                context.getTravelerSnapshot(
                    GetTravelerSnapshotRequest.newBuilder()
                        .setCtx(
                            RequestContext.newBuilder()
                                .setTenantId("acme")
                                .setCorrelationId(UUID.randomUUID().toString())
                                .setPrincipal(
                                    Principal.newBuilder()
                                        .setKind(Principal.Kind.AGENT)
                                        .setId("agent/trip-planner/v1")))
                        .setTravelerId(ALICE)
                        .setIncludeDocuments(true)
                        .setPurpose("CURIOSITY")
                        .build()))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
        .isEqualTo(Status.Code.NOT_FOUND);
  }

  // ================================================================== 4. restricted projects

  @Test
  @Order(5)
  void restrictedProjectTravelIsForMembersAndTheirManagersOnly() {
    ResponseEntity<String> created =
        post(
            "/api/v1/org/projects",
            TestTokens.carol(),
            "{\"code\":\"PRJ-APOLLO\",\"name\":\"Apollo\",\"client\":\"Acme Client\",\"restricted\":true}");
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
    projectId = json.readTree(created.getBody()).get("projectId").asString();
    assertThat(
            post("/api/v1/org/projects", TestTokens.alice(), "{\"code\":\"X\",\"name\":\"x\"}")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            post(
                    "/api/v1/org/projects/" + projectId + "/members",
                    TestTokens.carol(),
                    "{\"employeeId\":\"" + DAN + "\"}")
                .getStatusCode()
                .value())
        .isEqualTo(201);
    // members and admins see it; others do not know it exists
    assertThat(get("/api/v1/org/projects/" + projectId, TestTokens.dan()).getStatusCode().value())
        .isEqualTo(200);
    assertThat(get("/api/v1/org/projects/" + projectId, TestTokens.alice()).getStatusCode().value())
        .isEqualTo(404);
    assertThat(get("/api/v1/org/projects", TestTokens.alice()).getBody())
        .doesNotContain("PRJ-APOLLO");
    assertThat(get("/api/v1/org/projects", TestTokens.carol()).getBody()).contains("PRJ-APOLLO");

    // Dan travels for Apollo; his manager Bob may arrange it; Frank (MANAGER role) may not; Erin's
    // department-wide grant does not reach a restricted project; Alice is not a member at all.
    assertThat(authorize(DAN, List.of("TRAVELER"), DAN, projectId).getAllowed()).isTrue();
    assertThat(authorize(BOB, List.of("TRAVELER", "MANAGER"), DAN, projectId).getBasis())
        .isEqualTo("MANAGER");
    assertThat(authorize(FRANK, List.of("TRAVELER", "MANAGER"), DAN, projectId).getReasonCode())
        .isEqualTo("NOT_AN_ARRANGER");
    assertThat(authorize(ERIN, List.of("TRAVELER"), DAN, "").getAllowed()).isTrue();
    assertThat(authorize(ERIN, List.of("TRAVELER"), DAN, projectId).getReasonCode())
        .isEqualTo("PROJECT_RESTRICTED");
    assertThat(authorize(CAROL, List.of("TRAVEL_ADMIN"), ALICE, projectId).getReasonCode())
        .isEqualTo("PROJECT_RESTRICTED");
    // a grant scoped to the project itself does reach it
    ResponseEntity<String> projectGrant =
        post(
            "/api/v1/arrangers",
            TestTokens.carol(),
            "{\"arrangerEmployeeId\":\""
                + FRANK
                + "\",\"scope\":\"PROJECT\",\"scopeId\":\""
                + projectId
                + "\"}");
    assertThat(projectGrant.getStatusCode().value()).as(projectGrant.getBody()).isEqualTo(201);
    assertThat(authorize(FRANK, List.of("TRAVELER", "MANAGER"), DAN, projectId).getBasis())
        .isEqualTo("GRANT");
    TravelerSnapshotResponse snap =
        snapshot(DAN, List.of("TRAVELER"), DAN, false, "TRIP_CREATE", projectId);
    assertThat(snap.getAllocation().getProjectCode()).isEqualTo("PRJ-APOLLO");
    assertThat(snap.getAllocation().getProjectRestricted()).isTrue();
  }

  // ================================================================== 5. guests

  @Test
  @Order(6)
  void guestsAreSponsoredByWhoeverCreatesThem() {
    ResponseEntity<String> guest =
        post(
            "/api/v1/travelers/guests",
            TestTokens.alice(),
            """
            {"givenName":"Grace","familyName":"Visitor","email":"grace@partner.example",
             "phone":"+14155550199","dateOfBirth":"1985-08-01","nationality":"GB"}
            """);
    assertThat(guest.getStatusCode().value()).as(guest.getBody()).isEqualTo(201);
    guestId = json.readTree(guest.getBody()).get("travelerId").asString();
    assertThat(guestId).startsWith("gst_");
    assertThat(json.readTree(guest.getBody()).get("sponsorEmployeeId").asString()).isEqualTo(ALICE);
    assertThat(authorize(ALICE, List.of("TRAVELER"), guestId, "").getBasis()).isEqualTo("SPONSOR");
    assertThat(authorize(BOB, List.of("TRAVELER", "MANAGER"), guestId, "").getReasonCode())
        .isEqualTo("NOT_AN_ARRANGER");
    assertThat(authorize(CAROL, List.of("TRAVEL_ADMIN"), guestId, "").getBasis())
        .isEqualTo("TRAVEL_ADMIN");
    assertThat(get("/api/v1/travelers/guests", TestTokens.alice()).getBody()).contains(guestId);
    assertThat(get("/api/v1/travelers/guests", TestTokens.dan()).getBody()).doesNotContain(guestId);
    TravelerSnapshotResponse snap =
        snapshot(ALICE, List.of("TRAVELER"), guestId, true, "BOOKING", "");
    assertThat(snap.getPassenger().getKind()).isEqualTo("GUEST");
    assertThat(snap.getPassenger().getDateOfBirth()).isEqualTo("1985-08-01");
    assertThat(snap.getPassenger().getSponsorEmployeeId()).isEqualTo(ALICE);
  }

  // ================================================================== 6. offboarding & retention

  @Test
  @Order(7)
  void deactivationEndsArrangingTravelingAndStartsDocumentRetention() {
    ResponseEntity<String> notAdmin =
        post(
            "/api/v1/employees/" + ERIN + "/deactivation",
            TestTokens.alice(),
            UUID.randomUUID().toString(),
            "");
    assertThat(notAdmin.getStatusCode().value()).isEqualTo(403);
    ResponseEntity<String> erinOff =
        post(
            "/api/v1/employees/" + ERIN + "/deactivation",
            TestTokens.carol(),
            UUID.randomUUID().toString(),
            "");
    assertThat(erinOff.getStatusCode().value()).as(erinOff.getBody()).isEqualTo(200);
    assertThat(json.readTree(erinOff.getBody()).get("grantsRevoked").asInt()).isEqualTo(2);
    assertThat(authorize(ERIN, List.of("TRAVELER"), ALICE, "").getReasonCode())
        .isEqualTo("NOT_AN_ARRANGER");
    assertThat(
            get("/api/v1/travelers/documents/" + passportId + "/number", erin())
                .getStatusCode()
                .value())
        .isEqualTo(404);

    ResponseEntity<String> aliceOff =
        post(
            "/api/v1/employees/" + ALICE + "/deactivation",
            TestTokens.carol(),
            UUID.randomUUID().toString(),
            "");
    assertThat(aliceOff.getStatusCode().value()).isEqualTo(200);
    assertThat(json.readTree(aliceOff.getBody()).get("documentsRevoked").asInt()).isEqualTo(1);
    assertThat(authorize(ALICE, List.of("TRAVELER"), ALICE, "").getReasonCode())
        .isEqualTo("TRAVELER_INACTIVE");
    assertThat(authorize(BOB, List.of("TRAVELER", "MANAGER"), ALICE, "").getReasonCode())
        .isEqualTo("TRAVELER_INACTIVE");
    ResponseEntity<String> revoked =
        get("/api/v1/travelers/documents/" + passportId + "/number", TestTokens.carol());
    assertThat(revoked.getStatusCode().value()).isEqualTo(409);
    assertThat(revoked.getBody()).contains("DOCUMENT_REVOKED");
    // the row stays for the retention period (30 days in tests), then the purge deletes it
    assertThat(profiles.purgeExpiredDocumentsNow(LocalDate.now().plusDays(10))).isZero();
    assertThat(profiles.purgeExpiredDocumentsNow(LocalDate.now().plusDays(40))).isEqualTo(1);
    Long left =
        jdbc.sql("SELECT count(*) FROM travel_document WHERE document_id = :id")
            .param("id", passportId)
            .query(Long.class)
            .single();
    assertThat(left).isZero();
    // the access log outlives the document
    assertThat(get("/api/v1/travelers/" + ALICE + "/access-log", TestTokens.carol()).getBody())
        .contains("DOCUMENT:" + passportId);
  }

  // ================================================================== helpers

  private ArrangerAuthorization authorize(
      String arranger, List<String> roles, String traveler, String project) {
    return context.authorizeArranger(
        AuthorizeArrangerRequest.newBuilder()
            .setCtx(ctx(arranger))
            .setArrangerEmployeeId(arranger)
            .addAllArrangerRoles(roles)
            .setTravelerId(traveler)
            .setProjectId(project)
            .build());
  }

  private TravelerSnapshotResponse snapshot(
      String arranger,
      List<String> roles,
      String traveler,
      boolean documents,
      String purpose,
      String project) {
    return context.getTravelerSnapshot(
        GetTravelerSnapshotRequest.newBuilder()
            .setCtx(ctx(arranger))
            .setArrangerEmployeeId(arranger)
            .addAllArrangerRoles(roles)
            .setTravelerId(traveler)
            .setIncludeDocuments(documents)
            .setPurpose(purpose)
            .setProjectId(project)
            .build());
  }

  private static RequestContext ctx(String employeeId) {
    String name =
        switch (employeeId) {
          case ALICE -> "alice";
          case BOB -> "bob";
          case CAROL -> "carol";
          case DAN -> "dan";
          case ERIN -> "erin";
          default -> "frank";
        };
    return RequestContext.newBuilder()
        .setTenantId("acme")
        .setCorrelationId(UUID.randomUUID().toString())
        .setPrincipal(Principal.newBuilder().setKind(Principal.Kind.HUMAN).setId("human/" + name))
        .build();
  }

  private ResponseEntity<String> post(String path, String token, String body) {
    return post(path, token, null, body);
  }

  private ResponseEntity<String> post(
      String path, String token, String idempotencyKey, String body) {
    RestClient.RequestBodySpec spec =
        http.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }
    return spec.body(body).retrieve().toEntity(String.class);
  }

  private ResponseEntity<String> put(String path, String token, String body) {
    return http.method(HttpMethod.PUT)
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> get(String path, String token) {
    return http.get()
        .uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }
}
