package io.travelos.travelcore.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.travelos.spring.web.testing.TestTokens;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trips with Enterprise Context in the loop (ADR-0014): an arranger books under the passenger's own
 * identity, nothing in the request body widens permissions, an unrelated manager sees nothing of a
 * restricted project's travel, and the platform degrades honestly when Enterprise Context is not
 * there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestTokens.class, TestClock.class, RecordingApprovalSignaler.class})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TripArrangerIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  static final FakeEnterpriseContext CONTEXT = new FakeEnterpriseContext();

  @DynamicPropertySource
  static void enterpriseContext(DynamicPropertyRegistry registry) throws IOException {
    int port = CONTEXT.start();
    registry.add("travelos.grpc.clients.enterprise-context.address", () -> "localhost:" + port);
  }

  private static final String INTENT =
      """
      {"origin":"BOS","destination":"SEA",
       "earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T17:00:00Z",
       "returnAfter":"2026-10-07T20:00:00Z","latestReturn":"2026-10-08T06:00:00Z",
       "purpose":"customer meeting","hotelRequired":false}
      """;

  @Autowired Environment environment;
  @Autowired JdbcClient jdbc;
  private final JsonMapper json = JsonMapper.builder().build();
  private RestClient http;

  static String erin() {
    return TestTokens.user("erin", "acme", "emp_1005", List.of("TRAVELER"));
  }

  static String frank() {
    return TestTokens.user("frank", "acme", "emp_1006", List.of("TRAVELER", "MANAGER"));
  }

  @BeforeAll
  void setUp() {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (rq, rs) -> {})
            .build();
  }

  @AfterAll
  void tearDown() {
    CONTEXT.stop();
  }

  @Test
  void anArrangerBooksUnderThePassengersOwnIdentityWhateverTheRequestSays() {
    ResponseEntity<String> created =
        post(
            "/api/v1/trips",
            erin(),
            "{\"travelerId\":\"emp_1001\",\"intent\":"
                + INTENT
                + ",\"traveler\":{\"givenName\":\"Mallory\",\"familyName\":\"Evil\",\"email\":\"mallory@evil.example\"}}");
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(202);
    JsonNode trip = json.readTree(created.getBody());
    assertThat(trip.get("travelerId").asString()).isEqualTo("emp_1001");
    assertThat(trip.get("traveler").get("givenName").asString()).isEqualTo("Alice");
    assertThat(trip.get("traveler").get("familyName").asString()).isEqualTo("Nguyen");
    assertThat(trip.get("traveler").get("email").asString()).isEqualTo("alice@acme.example");
    assertThat(trip.get("createdBy").asString()).isEqualTo("human/erin");
    String tripId = trip.get("tripId").asString();

    JsonNode read = json.readTree(get("/api/v1/trips/" + tripId, erin()).getBody());
    JsonNode allocation = read.get("allocation");
    assertThat(allocation.get("managerEmployeeId").asString()).isEqualTo("emp_1002");
    assertThat(allocation.get("arrangerEmployeeId").asString()).isEqualTo("emp_1005");
    assertThat(allocation.get("arrangerBasis").asString()).isEqualTo("GRANT");
    assertThat(allocation.get("departmentId").asString()).isEqualTo("dept_eng");
    // the identity was asked for with a stated purpose, by the arranger, not by Travel Core itself
    assertThat(CONTEXT.snapshots)
        .anySatisfy(
            s -> {
              assertThat(s.getTravelerId()).isEqualTo("emp_1001");
              assertThat(s.getArrangerEmployeeId()).isEqualTo("emp_1005");
              assertThat(s.getPurpose()).isEqualTo("TRIP_CREATE");
              assertThat(s.getIncludeDocuments()).isFalse();
            });

    // who sees it: the traveler, the arranger, the HRIS manager, travel admins; not a
    // manager-by-role
    assertThat(get("/api/v1/trips/" + tripId, TestTokens.alice()).getStatusCode().value())
        .isEqualTo(200);
    assertThat(get("/api/v1/trips/" + tripId, TestTokens.bob()).getStatusCode().value())
        .isEqualTo(200);
    assertThat(get("/api/v1/trips/" + tripId, TestTokens.carol()).getStatusCode().value())
        .isEqualTo(200);
    assertThat(get("/api/v1/trips/" + tripId, frank()).getStatusCode().value()).isEqualTo(404);
    assertThat(get("/api/v1/trips?scope=tenant", TestTokens.bob()).getBody()).contains(tripId);
    assertThat(get("/api/v1/trips?scope=tenant", frank()).getBody()).doesNotContain(tripId);
    assertThat(get("/api/v1/trips?scope=tenant", TestTokens.carol()).getBody()).contains(tripId);
    assertThat(get("/api/v1/trips?scope=arranged", erin()).getBody()).contains(tripId);
    assertThat(get("/api/v1/trips?scope=mine", erin()).getBody()).doesNotContain(tripId);
  }

  @Test
  void permissionsCannotBeWidenedByRequestFields() {
    // Frank holds MANAGER, manages nobody: the role alone arranges nothing
    ResponseEntity<String> frank =
        post("/api/v1/trips", frank(), "{\"travelerId\":\"emp_1001\",\"intent\":" + INTENT + "}");
    assertThat(frank.getStatusCode().value()).isEqualTo(403);
    assertThat(json.readTree(frank.getBody()).get("code").asString()).isEqualTo("NOT_AN_ARRANGER");
    // a plain traveler naming someone else, with a full identity block, is still refused
    ResponseEntity<String> dan =
        post(
            "/api/v1/trips",
            TestTokens.dan(),
            "{\"travelerId\":\"emp_1001\",\"intent\":"
                + INTENT
                + ",\"traveler\":{\"givenName\":\"Alice\",\"familyName\":\"Nguyen\",\"email\":\"alice@acme.example\"}}");
    assertThat(dan.getStatusCode().value()).isEqualTo(403);
    // naming an unknown traveler is refused too, and does not reveal anything
    ResponseEntity<String> unknown =
        post("/api/v1/trips", frank(), "{\"travelerId\":\"emp_9999\",\"intent\":" + INTENT + "}");
    assertThat(unknown.getStatusCode().value()).isEqualTo(403);
    assertThat(json.readTree(unknown.getBody()).get("code").asString())
        .isEqualTo("TRAVELER_UNKNOWN");
    // the same rules hold for demand conversion and every other entry point: they all go through
    // TripService.create; the gRPC path is exercised by EnterpriseContextIntegrationTest.
  }

  @Test
  void restrictedProjectTravelIsInvisibleToAnUnrelatedManager() {
    ResponseEntity<String> created =
        post(
            "/api/v1/trips",
            TestTokens.dan(),
            "{\"intent\":" + INTENT + ",\"projectId\":\"" + FakeEnterpriseContext.PROJECT + "\"}");
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(202);
    String tripId = json.readTree(created.getBody()).get("tripId").asString();
    JsonNode read = json.readTree(get("/api/v1/trips/" + tripId, TestTokens.dan()).getBody());
    assertThat(read.get("allocation").get("projectId").asString())
        .isEqualTo(FakeEnterpriseContext.PROJECT);
    assertThat(read.get("allocation").get("projectRestricted").asBoolean()).isTrue();
    assertThat(read.get("allocation").get("arrangerBasis").asString()).isEqualTo("SELF");
    // Bob manages Dan: sees it. Frank holds MANAGER and nothing else: 404, in the list too.
    assertThat(get("/api/v1/trips/" + tripId, TestTokens.bob()).getStatusCode().value())
        .isEqualTo(200);
    assertThat(get("/api/v1/trips/" + tripId, frank()).getStatusCode().value()).isEqualTo(404);
    assertThat(get("/api/v1/trips/" + tripId + "/history", frank()).getStatusCode().value())
        .isEqualTo(404);
    assertThat(get("/api/v1/trips?scope=tenant&status=SUBMITTED", frank()).getBody())
        .doesNotContain(tripId);
    // Alice is not a member: even her manager cannot put her on the project
    ResponseEntity<String> notMember =
        post(
            "/api/v1/trips",
            TestTokens.bob(),
            "{\"travelerId\":\"emp_1001\",\"intent\":"
                + INTENT
                + ",\"projectId\":\""
                + FakeEnterpriseContext.PROJECT
                + "\"}");
    assertThat(notMember.getStatusCode().value()).isEqualTo(403);
    assertThat(json.readTree(notMember.getBody()).get("code").asString())
        .isEqualTo("PROJECT_RESTRICTED");
    // an unknown project is refused before anything is stored
    ResponseEntity<String> unknown =
        post(
            "/api/v1/trips",
            TestTokens.dan(),
            "{\"intent\":" + INTENT + ",\"projectId\":\"prj_nope\"}");
    assertThat(unknown.getStatusCode().value()).isEqualTo(403);
    assertThat(json.readTree(unknown.getBody()).get("code").asString())
        .isEqualTo("PROJECT_UNKNOWN");
  }

  @Test
  void aTravelerUnknownToEnterpriseContextStillRequestsTheirOwnTravelOnTheirClaims() {
    ResponseEntity<String> created =
        post("/api/v1/trips", TestTokens.zoe(), "{\"intent\":" + INTENT + "}");
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(202);
    JsonNode trip = json.readTree(created.getBody());
    assertThat(trip.get("traveler").get("givenName").asString()).isEqualTo("Zoe");
    JsonNode read =
        json.readTree(
            get("/api/v1/trips/" + trip.get("tripId").asString(), TestTokens.zoe()).getBody());
    assertThat(read.has("allocation")).isFalse();
  }

  @Test
  void whenEnterpriseContextIsDownOwnTravelProceedsAndArrangingWaits() {
    CONTEXT.unavailable = true;
    try {
      ResponseEntity<String> own =
          post("/api/v1/trips", TestTokens.alice(), "{\"intent\":" + INTENT + "}");
      assertThat(own.getStatusCode().value()).as(own.getBody()).isEqualTo(202);
      ResponseEntity<String> arranged =
          post("/api/v1/trips", erin(), "{\"travelerId\":\"emp_1001\",\"intent\":" + INTENT + "}");
      assertThat(arranged.getStatusCode().value()).isEqualTo(503);
      assertThat(json.readTree(arranged.getBody()).get("code").asString())
          .isEqualTo("CONTEXT_UNAVAILABLE");
    } finally {
      CONTEXT.unavailable = false;
    }
  }

  @Test
  void idempotentReplayOfAnArrangedTripReturnsTheSameTrip() {
    String key = "arranged-" + UUID.randomUUID();
    String body = "{\"travelerId\":\"emp_1001\",\"intent\":" + INTENT + "}";
    ResponseEntity<String> first = post("/api/v1/trips", erin(), key, body);
    ResponseEntity<String> second = post("/api/v1/trips", erin(), key, body);
    assertThat(first.getStatusCode().value()).isEqualTo(202);
    assertThat(json.readTree(second.getBody()).get("tripId"))
        .isEqualTo(json.readTree(first.getBody()).get("tripId"));
    Long allocations =
        jdbc.sql("SELECT count(*) FROM trip_allocation WHERE trip_id = :id")
            .param("id", json.readTree(first.getBody()).get("tripId").asString())
            .query(Long.class)
            .single();
    assertThat(allocations).isEqualTo(1);
  }

  private ResponseEntity<String> post(String path, String token, String body) {
    return post(path, token, UUID.randomUUID().toString(), body);
  }

  private ResponseEntity<String> post(
      String path, String token, String idempotencyKey, String body) {
    return http.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", idempotencyKey)
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
