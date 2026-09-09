package io.travelos.travelcore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import io.travelos.events.Topics;
import io.travelos.events.testing.EventSchemas;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP, real security filter chain, real Postgres (Flyway), real Kafka. If it passes here it
 * behaves the same way against the local platform — nothing is mocked around the boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestTokens.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TripApiIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  private static final String INTENT =
      """
      {"origin":"BOS","destination":"SEA",
       "earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T17:00:00Z",
       "returnAfter":"2026-10-07T20:00:00Z","latestReturn":"2026-10-08T06:00:00Z",
       "purpose":"customer meeting","hotelRequired":true}
      """;

  @Autowired Environment environment;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaConnectionDetails kafkaConnection;

  private final JsonMapper json = JsonMapper.builder().build();
  private final EventCodec codec = new EventCodec();
  private RestClient http;
  private KafkaConsumer<String, String> consumer;
  private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

  @BeforeAll
  void setUp() {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(status -> true, (request, response) -> {})
            .build();
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnection.getBootstrapServers()));
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "trip-api-test-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of(Topics.TRIP));
  }

  @AfterAll
  void tearDown() {
    consumer.close();
  }

  // ---------------------------------------------------------------- create + read

  @Test
  void createsAndReadsBackATrip() {
    String key = UUID.randomUUID().toString();
    ResponseEntity<String> created =
        post("/api/v1/trips", TestTokens.alice(), key, body(INTENT, null));

    assertThat(created.getStatusCode().value()).isEqualTo(202);
    JsonNode trip = json.readTree(created.getBody());
    String tripId = trip.get("tripId").asString();
    assertThat(tripId).startsWith("trip_");
    assertThat(created.getHeaders().getLocation()).hasPath("/api/v1/trips/" + tripId);
    assertThat(trip.get("status").asString()).isEqualTo("SUBMITTED");
    assertThat(trip.get("tenantId").asString()).isEqualTo("acme");
    assertThat(trip.get("travelerId").asString()).isEqualTo("emp_1001");
    assertThat(trip.get("createdBy").asString()).isEqualTo("human/alice");
    assertThat(trip.get("intent").get("destination").asString()).isEqualTo("SEA");
    assertThat(trip.get("intent").get("travelers").asInt()).isEqualTo(1);

    ResponseEntity<String> read = get("/api/v1/trips/" + tripId, TestTokens.alice());
    assertThat(read.getStatusCode().value()).isEqualTo(200);
    assertThat(json.readTree(read.getBody())).isEqualTo(trip);

    ResponseEntity<String> history =
        get("/api/v1/trips/" + tripId + "/history", TestTokens.alice());
    JsonNode changes = json.readTree(history.getBody());
    assertThat(changes).hasSize(1);
    assertThat(changes.get(0).get("to").asString()).isEqualTo("SUBMITTED");
    assertThat(changes.get(0).get("actor").asString()).isEqualTo("human/alice");
  }

  @Test
  void freeTextOnlyIsAcceptedAndKeptVerbatim() {
    ResponseEntity<String> created =
        post(
            "/api/v1/trips",
            TestTokens.alice(),
            UUID.randomUUID().toString(),
            body(null, "I need to be in Seattle before 9am Tuesday and back Wednesday evening."));
    assertThat(created.getStatusCode().value()).isEqualTo(202);
    JsonNode trip = json.readTree(created.getBody());
    assertThat(trip.get("request").asString()).startsWith("I need to be in Seattle");
    assertThat(trip.has("intent")).isFalse();
  }

  @Test
  void listsOnlyMyTrips() {
    post("/api/v1/trips", TestTokens.dan(), UUID.randomUUID().toString(), body(INTENT, null));
    ResponseEntity<String> mine = get("/api/v1/trips", TestTokens.dan());
    JsonNode trips = json.readTree(mine.getBody());
    assertThat(trips).isNotEmpty();
    trips.forEach(t -> assertThat(t.get("travelerId").asString()).isEqualTo("emp_1004"));
  }

  // ---------------------------------------------------------------- idempotency

  @Nested
  class Idempotency {

    @Test
    void replayingTheSameKeyReturnsTheSameTripAndCreatesOneRow() {
      String key = "retry-" + UUID.randomUUID();
      long before = tripCount();
      ResponseEntity<String> first =
          post("/api/v1/trips", TestTokens.alice(), key, body(INTENT, null));
      ResponseEntity<String> second =
          post("/api/v1/trips", TestTokens.alice(), key, body(INTENT, null));
      ResponseEntity<String> third =
          post("/api/v1/trips", TestTokens.alice(), key, body(INTENT, null));

      assertThat(List.of(first, second, third))
          .allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(202));
      String tripId = json.readTree(first.getBody()).get("tripId").asString();
      assertThat(json.readTree(second.getBody()).get("tripId").asString()).isEqualTo(tripId);
      assertThat(json.readTree(third.getBody()).get("tripId").asString()).isEqualTo(tripId);
      assertThat(tripCount()).isEqualTo(before + 1);
    }

    @Test
    void reusingAKeyWithADifferentBodyIsRejected() {
      String key = "reuse-" + UUID.randomUUID();
      post("/api/v1/trips", TestTokens.alice(), key, body(INTENT, null));
      ResponseEntity<String> different =
          post("/api/v1/trips", TestTokens.alice(), key, body(INTENT.replace("SEA", "SFO"), null));
      assertThat(different.getStatusCode().value()).isEqualTo(422);
      assertThat(json.readTree(different.getBody()).get("code").asString())
          .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void keysAreScopedByTenant() {
      String key = "shared-" + UUID.randomUUID();
      ResponseEntity<String> acme =
          post("/api/v1/trips", TestTokens.alice(), key, body(INTENT, null));
      ResponseEntity<String> globex =
          post("/api/v1/trips", TestTokens.zoe(), key, body(INTENT, null));
      assertThat(acme.getStatusCode().value()).isEqualTo(202);
      assertThat(globex.getStatusCode().value()).isEqualTo(202);
      assertThat(json.readTree(acme.getBody()).get("tripId").asString())
          .isNotEqualTo(json.readTree(globex.getBody()).get("tripId").asString());
    }

    @Test
    void missingKeyIsA400() {
      ResponseEntity<String> response =
          post("/api/v1/trips", TestTokens.alice(), null, body(INTENT, null));
      assertThat(response.getStatusCode().value()).isEqualTo(400);
      assertThat(json.readTree(response.getBody()).get("code").asString())
          .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }
  }

  // ---------------------------------------------------------------- validation

  @Nested
  class Validation {

    @Test
    void emptyRequestIsRejected() {
      ResponseEntity<String> response =
          post("/api/v1/trips", TestTokens.alice(), UUID.randomUUID().toString(), "{}");
      assertThat(response.getStatusCode().value()).isEqualTo(422);
      assertThat(json.readTree(response.getBody()).get("code").asString())
          .isEqualTo("INTENT_INVALID");
    }

    @Test
    void domainInvariantsSurfaceAs422WithTheReason() {
      ResponseEntity<String> response =
          post(
              "/api/v1/trips",
              TestTokens.alice(),
              UUID.randomUUID().toString(),
              body(INTENT.replace("\"destination\":\"SEA\"", "\"destination\":\"BOS\""), null));
      assertThat(response.getStatusCode().value()).isEqualTo(422);
      JsonNode problem = json.readTree(response.getBody());
      assertThat(problem.get("code").asString()).isEqualTo("INTENT_INVALID");
      assertThat(problem.get("detail").asString()).contains("differ");
    }

    @Test
    void beanValidationSurfacesAs400WithFieldErrors() {
      ResponseEntity<String> response =
          post(
              "/api/v1/trips",
              TestTokens.alice(),
              UUID.randomUUID().toString(),
              body(INTENT.replace("\"origin\":\"BOS\"", "\"origin\":\"boston\""), null));
      assertThat(response.getStatusCode().value()).isEqualTo(400);
      JsonNode problem = json.readTree(response.getBody());
      assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
      assertThat(problem.get("fields").get("intent.origin").asString()).contains("IATA");
    }

    @Test
    void sliceOneBooksOneTravelerAtATime() {
      ResponseEntity<String> response =
          post(
              "/api/v1/trips",
              TestTokens.alice(),
              UUID.randomUUID().toString(),
              body(
                  INTENT.replace(
                      "\"hotelRequired\":true", "\"hotelRequired\":true,\"travelers\":3"),
                  null));
      assertThat(response.getStatusCode().value()).isEqualTo(422);
      assertThat(json.readTree(response.getBody()).get("code").asString())
          .isEqualTo("SLICE_SCOPE_SINGLE_TRAVELER");
    }
  }

  // ---------------------------------------------------------------- authn / authz / tenancy

  @Nested
  class Security {

    @Test
    void noTokenIs401() {
      ResponseEntity<String> response = get("/api/v1/trips", null);
      assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void wrongAudienceIs401() {
      String token =
          TestTokens.token(
              TestTokens.ISSUER,
              "some-other-api",
              b -> b.subject("alice").claim("tenant_id", "acme"));
      assertThat(get("/api/v1/trips", token).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void wrongIssuerIs401() {
      String token =
          TestTokens.token(
              "http://evil/realms/travelos",
              TestTokens.AUDIENCE,
              b -> b.subject("alice").claim("tenant_id", "acme"));
      assertThat(get("/api/v1/trips", token).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void tokenWithoutTenantIs401() {
      String token = TestTokens.token(b -> b.subject("alice").claim("employee_id", "emp_1001"));
      assertThat(get("/api/v1/trips", token).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void anotherTenantCannotSeeTheTripEvenWithItsId() {
      String tripId = createAsAlice();
      ResponseEntity<String> response = get("/api/v1/trips/" + tripId, TestTokens.zoe());
      assertThat(response.getStatusCode().value())
          .as("404, never 403: existence is not disclosed")
          .isEqualTo(404);
    }

    @Test
    void anotherTravelerInTheSameTenantCannotSeeIt() {
      String tripId = createAsAlice();
      assertThat(get("/api/v1/trips/" + tripId, TestTokens.dan()).getStatusCode().value())
          .isEqualTo(404);
    }

    @Test
    void aManagerInTheSameTenantCanSeeIt() {
      String tripId = createAsAlice();
      assertThat(get("/api/v1/trips/" + tripId, TestTokens.bob()).getStatusCode().value())
          .isEqualTo(200);
    }

    @Test
    void onlyArrangersCreateTripsForOthers() {
      String forDan = "{\"travelerId\":\"emp_1004\",\"intent\":" + INTENT + "}";
      ResponseEntity<String> asTraveler =
          post("/api/v1/trips", TestTokens.alice(), UUID.randomUUID().toString(), forDan);
      assertThat(asTraveler.getStatusCode().value()).isEqualTo(403);
      assertThat(json.readTree(asTraveler.getBody()).get("code").asString())
          .isEqualTo("NOT_AN_ARRANGER");

      ResponseEntity<String> asManager =
          post("/api/v1/trips", TestTokens.bob(), UUID.randomUUID().toString(), forDan);
      assertThat(asManager.getStatusCode().value()).isEqualTo(202);
      assertThat(json.readTree(asManager.getBody()).get("travelerId").asString())
          .isEqualTo("emp_1004");
      assertThat(json.readTree(asManager.getBody()).get("createdBy").asString())
          .isEqualTo("human/bob");
    }

    @Test
    void healthIsOpenEverythingElseIsClosed() {
      assertThat(get("/actuator/health", null).getStatusCode().value()).isEqualTo(200);
      assertThat(get("/actuator/env", null).getStatusCode().value()).isIn(401, 403, 404);
      assertThat(get("/anything-else", TestTokens.alice()).getStatusCode().value()).isIn(403, 404);
    }
  }

  // ---------------------------------------------------------------- cancellation

  @Nested
  class Cancellation {

    @Test
    void ownerCancelsOnceAndRepeatsAreIdempotent() {
      String tripId = createAsAlice();
      ResponseEntity<String> first = cancel(tripId, TestTokens.alice(), "Meeting moved online");
      assertThat(first.getStatusCode().value()).isEqualTo(200);
      JsonNode cancelled = json.readTree(first.getBody());
      assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
      assertThat(cancelled.get("version").asLong()).isEqualTo(1);

      ResponseEntity<String> again = cancel(tripId, TestTokens.alice(), "Meeting moved online");
      assertThat(again.getStatusCode().value()).isEqualTo(200);
      assertThat(json.readTree(again.getBody())).isEqualTo(cancelled);

      JsonNode history =
          json.readTree(get("/api/v1/trips/" + tripId + "/history", TestTokens.alice()).getBody());
      assertThat(history).hasSize(2);
      assertThat(history.get(1).get("from").asString()).isEqualTo("SUBMITTED");
      assertThat(history.get(1).get("to").asString()).isEqualTo("CANCELLED");
      assertThat(history.get(1).get("reason").asString()).isEqualTo("Meeting moved online");
    }

    @Test
    void othersCannotCancel() {
      String tripId = createAsAlice();
      assertThat(cancel(tripId, TestTokens.dan(), "nope").getStatusCode().value()).isEqualTo(404);
      assertThat(cancel(tripId, TestTokens.zoe(), "nope").getStatusCode().value()).isEqualTo(404);
      assertThat(
              cancel(tripId, TestTokens.bob(), "manager cannot cancel, only see")
                  .getStatusCode()
                  .value())
          .isEqualTo(404);
    }
  }

  // ---------------------------------------------------------------- events

  @Test
  void publishesContractValidEventsKeyedByTripId() {
    String tripId = createAsAlice();
    cancel(tripId, TestTokens.alice(), "Plans changed");

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              consumer.poll(Duration.ofMillis(250)).forEach(received::add);
              assertThat(
                      received.stream()
                          .filter(r -> r.key().equals(tripId))
                          .map(r -> type(r.value())))
                  .containsExactly("travel.trip.created", "travel.trip.cancelled");
            });

    for (ConsumerRecord<String, String> record : received) {
      if (!record.key().equals(tripId)) {
        continue;
      }
      assertThat(EventSchemas.violations(record.value())).as(record.value()).isEmpty();
      EventEnvelope event = codec.fromJson(record.value());
      assertThat(event.tenantId()).isEqualTo("acme");
      assertThat(event.producer()).isEqualTo("travel-core");
      assertThat(event.correlationId()).isEqualTo(tripId);
    }
    Map<String, Object> created =
        received.stream()
            .filter(r -> r.key().equals(tripId))
            .map(r -> codec.fromJson(r.value()))
            .findFirst()
            .orElseThrow()
            .data();
    assertThat(created).containsEntry("requestedBy", "human/alice").containsEntry("source", "API");
  }

  // ---------------------------------------------------------------- helpers

  private String createAsAlice() {
    ResponseEntity<String> created =
        post("/api/v1/trips", TestTokens.alice(), UUID.randomUUID().toString(), body(INTENT, null));
    assertThat(created.getStatusCode().value()).isEqualTo(202);
    return json.readTree(created.getBody()).get("tripId").asString();
  }

  private ResponseEntity<String> cancel(String tripId, String token, String reason) {
    return post(
        "/api/v1/trips/" + tripId + "/cancellation",
        token,
        UUID.randomUUID().toString(),
        "{\"reason\":\"" + reason + "\"}");
  }

  private ResponseEntity<String> post(
      String path, String token, String idempotencyKey, String body) {
    RestClient.RequestBodySpec spec = http.post().uri(path).contentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }
    return spec.body(body).retrieve().toEntity(String.class);
  }

  private ResponseEntity<String> get(String path, String token) {
    RestClient.RequestHeadersSpec<?> spec = http.get().uri(path);
    if (token != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return spec.retrieve().toEntity(String.class);
  }

  private static String body(String intent, String request) {
    StringBuilder sb = new StringBuilder("{");
    if (request != null) {
      sb.append("\"request\":\"").append(request).append("\"");
    }
    if (intent != null) {
      if (request != null) {
        sb.append(',');
      }
      sb.append("\"intent\":").append(intent);
    }
    return sb.append('}').toString();
  }

  private String type(String eventJson) {
    return json.readTree(eventJson).get("eventType").asString();
  }

  private long tripCount() {
    Long count = jdbc.sql("SELECT count(*) FROM trip").query(Long.class).single();
    return count == null ? 0 : count;
  }
}
