package io.travelos.travelcore.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.trip.v1.ComponentState;
import io.travelos.contracts.trip.v1.GetTripRequest;
import io.travelos.contracts.trip.v1.TransitionTripRequest;
import io.travelos.contracts.trip.v1.TravelCoreServiceGrpc;
import io.travelos.contracts.trip.v1.Trip;
import io.travelos.contracts.trip.v1.TripStatus;
import io.travelos.contracts.trip.v1.UpdateComponentsRequest;
import io.travelos.events.Topics;
import io.travelos.events.testing.EventSchemas;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.spring.web.testing.TestTokens;
import io.travelos.travelcore.approval.ApprovalSignaler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 3 through the public API and the workflow's door: a multi-city itinerary is accepted with
 * stable component ids, components are reported and readable, a stale approval goes back to a
 * person with a replanned event, and none of it leaks across tenants.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestTokens.class, ItineraryApiIntegrationTest.Signals.class})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItineraryApiIntegrationTest {

  @TestConfiguration
  static class Signals {
    @Bean
    @Primary
    ApprovalSignaler recordingSignaler() {
      return (tripId, decision) -> {};
    }
  }

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  private static final String ITINERARY =
      """
      {"intent": {"purpose": "roadshow", "itinerary": {
        "legs": [
          {"origin":"BOS","destination":"SEA","earliestDeparture":"2026-10-06T10:00:00Z","arrivalDeadline":"2026-10-06T23:00:00Z"},
          {"origin":"SEA","destination":"SFO","earliestDeparture":"2026-10-08T15:00:00Z","arrivalDeadline":"2026-10-08T22:00:00Z"},
          {"origin":"SFO","destination":"BOS","earliestDeparture":"2026-10-09T13:00:00Z","arrivalDeadline":"2026-10-10T04:00:00Z"}],
        "stays": [
          {"city":"SEA","checkInDate":"2026-10-06","checkOutDate":"2026-10-08"},
          {"city":"SFO","checkInDate":"2026-10-08","checkOutDate":"2026-10-09"}],
        "transfers": [
          {"kind":"AIRPORT_TO_HOTEL","city":"SEA"},
          {"kind":"HOTEL_TO_AIRPORT","city":"SFO","required":false}]
      }}}
      """;

  @Autowired Environment environment;
  @Autowired GrpcServerLifecycle grpc;
  @Autowired KafkaConnectionDetails kafkaConnection;

  private final JsonMapper json = JsonMapper.builder().build();
  private RestClient http;
  private ManagedChannel channel;
  private TravelCoreServiceGrpc.TravelCoreServiceBlockingStub core;
  private KafkaConsumer<String, String> consumer;
  private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

  @BeforeAll
  void setUp() {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (rq, rs) -> {})
            .build();
    channel = ManagedChannelBuilder.forAddress("localhost", grpc.port()).usePlaintext().build();
    core = TravelCoreServiceGrpc.newBlockingStub(channel);
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnection.getBootstrapServers()));
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "itinerary-test-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of(Topics.TRIP, Topics.APPROVAL));
  }

  @AfterAll
  void tearDown() {
    consumer.close();
    channel.shutdownNow();
  }

  @Test
  void aMultiCityItineraryIsFrozenWithStableComponentsAndReportedComponentStates() {
    ResponseEntity<String> created = post(TestTokens.alice(), ITINERARY);
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(202);
    JsonNode trip = json.readTree(created.getBody());
    String tripId = trip.get("tripId").asString();
    JsonNode intent = trip.get("intent");
    // the legacy fields describe the first leg and the return, for Slice 1/2 readers
    assertThat(intent.get("origin").asString()).isEqualTo("BOS");
    assertThat(intent.get("destination").asString()).isEqualTo("SEA");
    assertThat(intent.get("returnAfter").asString()).isEqualTo("2026-10-09T13:00:00Z");
    assertThat(intent.get("hotelRequired").asBoolean()).isTrue();
    JsonNode itinerary = intent.get("itinerary");
    assertThat(itinerary.get("legs")).hasSize(3);
    assertThat(itinerary.get("stays")).hasSize(2);
    assertThat(itinerary.get("transfers")).hasSize(2);
    assertThat(itinerary.get("currency").asString()).isEqualTo("USD");
    String leg1 = itinerary.get("legs").get(0).get("componentId").asString();
    assertThat(leg1).startsWith("cmp_");
    assertThat(trip.has("components")).isFalse();

    // the same request again is the same trip, same component ids (idempotent, stable)
    ResponseEntity<String> read = get("/api/v1/trips/" + tripId, TestTokens.alice());
    assertThat(
            json.readTree(read.getBody())
                .get("intent")
                .get("itinerary")
                .get("legs")
                .get(0)
                .get("componentId")
                .asString())
        .isEqualTo(leg1);

    // the workflow sees the itinerary with zones and dependencies over gRPC
    Trip proto =
        core.getTrip(GetTripRequest.newBuilder().setCtx(ctx("acme")).setTripId(tripId).build());
    assertThat(proto.getIntent().hasItinerary()).isTrue();
    assertThat(proto.getIntent().getItinerary().getLegs(0).getDestinationTimeZone())
        .isEqualTo("America/Los_Angeles");
    assertThat(proto.getIntent().getItinerary().getStays(0).getDependsOnList())
        .containsExactly(leg1, proto.getIntent().getItinerary().getLegs(1).getComponentId());
    assertThat(proto.getIntent().getItinerary().getTransfers(0).getDependsOn(0)).isEqualTo(leg1);
    assertThat(proto.getComponentsCount()).isZero();

    // the workflow reports component states; the same report twice is one row each
    String stay1 = proto.getIntent().getItinerary().getStays(0).getComponentId();
    UpdateComponentsRequest report =
        UpdateComponentsRequest.newBuilder()
            .setCtx(ctx("acme"))
            .setTripId(tripId)
            .addComponents(
                ComponentState.newBuilder()
                    .setComponentId(leg1)
                    .setType("AIR")
                    .setStatus("CONFIRMED")
                    .setProvider("sandbox-air")
                    .setExternalRef("SBX-1")
                    .setTotal(usd(31200))
                    .setSummary("DL240 BOS-SEA 06 Oct"))
            .addComponents(
                ComponentState.newBuilder()
                    .setComponentId(stay1)
                    .setType("HOTEL")
                    .setStatus("BOOKING")
                    .setProvider("sandbox-hotel")
                    .setTotal(usd(23800)))
            .build();
    core.updateComponents(report);
    Trip reported = core.updateComponents(report);
    assertThat(reported.getComponentsCount()).isEqualTo(2);
    assertThat(reported.getComponents(0).getStatus()).isEqualTo("CONFIRMED");
    JsonNode components =
        json.readTree(get("/api/v1/trips/" + tripId + "/components", TestTokens.alice()).getBody());
    assertThat(components).hasSize(2);
    assertThat(components.get(1).get("componentId").asString()).isEqualTo(stay1);
    assertThat(components.get(1).get("total").get("display").asString()).isEqualTo("USD 238.00");
    assertThat(
            json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody())
                .get("components"))
        .hasSize(2);

    // another tenant: 404 on the trip and on its components
    assertThat(
            get("/api/v1/trips/" + tripId + "/components", TestTokens.zoe())
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(get("/api/v1/trips/" + tripId, TestTokens.zoe()).getStatusCode().value())
        .isEqualTo(404);
  }

  @Test
  void aStaleApprovalGoesBackToAPersonWithAReplannedEvent() {
    String tripId =
        json.readTree(post(TestTokens.alice(), ITINERARY).getBody()).get("tripId").asString();
    transition(tripId, TripStatus.PLANNING, b -> {});
    Trip waiting =
        transition(
            tripId,
            TripStatus.AWAITING_APPROVAL,
            b ->
                b.setTotal(usd(124500))
                    .setApproverRole("MANAGER")
                    .setSelectedBundleId("bdl_01ARZ3NDEKTSV4RRFFQ69G5FAB"));
    String firstApproval = waiting.getApprovalId();
    // bob approves the first plan
    ResponseEntity<String> approved =
        http.post()
            .uri("/api/v1/trips/" + tripId + "/approval")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.bob())
            .header("Idempotency-Key", "approve-1-" + tripId)
            .body("{\"decision\":\"APPROVE\"}")
            .retrieve()
            .toEntity(String.class);
    assertThat(approved.getStatusCode().value()).as(approved.getBody()).isEqualTo(200);
    transition(tripId, TripStatus.APPROVED, b -> {});
    // revalidation before booking finds the hotel re-priced: the approved plan is stale
    Trip again =
        transition(
            tripId,
            TripStatus.AWAITING_APPROVAL,
            b ->
                b.setTotal(usd(131500))
                    .setApproverRole("MANAGER")
                    .setReplanReason("PRICE_CHANGED"));
    assertThat(again.getStatus()).isEqualTo(TripStatus.AWAITING_APPROVAL);
    assertThat(again.getApprovalId()).isNotEqualTo(firstApproval);
    JsonNode view = json.readTree(get("/api/v1/trips/" + tripId, TestTokens.alice()).getBody());
    assertThat(view.get("approval").get("status").asString()).isEqualTo("PENDING");
    assertThat(view.get("total").get("amountMinor").asLong()).isEqualTo(131500);
    ConsumerRecord<String, String> replanned = awaitEvent(tripId, "travel.trip.replanned");
    assertThat(EventSchemas.violations(replanned.value())).isEmpty();
    JsonNode data = json.readTree(replanned.value()).get("data");
    assertThat(data.get("reason").asString()).isEqualTo("PRICE_CHANGED");
    assertThat(data.get("previousTotal").get("amountMinor").asLong()).isEqualTo(124500);
    assertThat(data.get("newTotal").get("amountMinor").asLong()).isEqualTo(131500);
    assertThat(data.get("previousApprovalId").asString()).isEqualTo(firstApproval);
    // alice still cannot approve her own trip; bob can, and the trip proceeds
    assertThat(
            http.post()
                .uri("/api/v1/trips/" + tripId + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.alice())
                .header("Idempotency-Key", "self-" + tripId)
                .body("{\"decision\":\"APPROVE\"}")
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            http.post()
                .uri("/api/v1/trips/" + tripId + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.bob())
                .header("Idempotency-Key", "approve-2-" + tripId)
                .body("{\"decision\":\"APPROVE\"}")
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(transition(tripId, TripStatus.APPROVED, b -> {}).getStatus())
        .isEqualTo(TripStatus.APPROVED);
    List<String> history =
        json.readTree(get("/api/v1/trips/" + tripId + "/history", TestTokens.alice()).getBody())
            .valueStream()
            .map(n -> n.get("to").asString())
            .toList();
    assertThat(history)
        .containsExactly(
            "SUBMITTED",
            "PLANNING",
            "AWAITING_APPROVAL",
            "APPROVED",
            "AWAITING_APPROVAL",
            "APPROVED");
  }

  @Test
  void theSameItineraryWithTheSameKeyIsOneTripAndADifferentOneIsRefused() {
    String key = "retry-" + UUID.randomUUID();
    String first =
        json.readTree(post(TestTokens.alice(), ITINERARY, key).getBody()).get("tripId").asString();
    // the retry mints fresh component ids while parsing; the fingerprint must not see them
    ResponseEntity<String> again = post(TestTokens.alice(), ITINERARY, key);
    assertThat(again.getStatusCode().value()).isEqualTo(202);
    assertThat(json.readTree(again.getBody()).get("tripId").asString()).isEqualTo(first);
    ResponseEntity<String> other =
        post(
            TestTokens.alice(),
            ITINERARY.replace("\"purpose\": \"roadshow\"", "\"purpose\": \"other\""),
            key);
    assertThat(other.getStatusCode().value()).isEqualTo(422);
    assertThat(other.getBody()).contains("IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void aLegacyHotelRequestIsHonouredAsAStayOrRefusedBeforePlanning() {
    // Slice 1/2 shape with hotelRequired: the response echoes an itinerary with one required stay
    ResponseEntity<String> created =
        post(
            TestTokens.alice(),
            """
            {"intent": {"origin": "BOS", "destination": "SEA",
              "earliestDeparture": "2026-10-06T10:00:00Z", "arrivalDeadline": "2026-10-06T23:00:00Z",
              "returnAfter": "2026-10-08T13:00:00Z", "latestReturn": "2026-10-09T02:00:00Z",
              "hotelRequired": true, "purpose": "legacy hotel"}, "source": "API"}
            """);
    assertThat(created.getStatusCode().value()).isEqualTo(202);
    JsonNode intent = json.readTree(created.getBody()).get("intent");
    assertThat(intent.get("hotelRequired").asBoolean()).isTrue();
    assertThat(intent.get("origin").asString()).isEqualTo("BOS");
    JsonNode stays = intent.get("itinerary").get("stays");
    assertThat(stays).hasSize(1);
    assertThat(stays.get(0).get("city").asString()).isEqualTo("SEA");
    assertThat(stays.get(0).get("checkInDate").asString()).isEqualTo("2026-10-06");
    assertThat(stays.get(0).get("checkOutDate").asString()).isEqualTo("2026-10-08");
    assertThat(stays.get(0).get("required").asBoolean()).isTrue();
    assertThat(intent.get("itinerary").get("legs")).hasSize(2);
    // the same request without a return cannot say which nights: refused, actionably, up front
    ResponseEntity<String> refused =
        post(
            TestTokens.alice(),
            """
            {"intent": {"origin": "BOS", "destination": "SEA",
              "earliestDeparture": "2026-10-06T10:00:00Z", "arrivalDeadline": "2026-10-06T23:00:00Z",
              "hotelRequired": true}, "source": "API"}
            """);
    assertThat(refused.getStatusCode().value()).isEqualTo(422);
    assertThat(refused.getBody()).contains("HOTEL_DETAILS_INSUFFICIENT").contains("return window");
    // hotelRequired=false keeps the Slice 1 shape exactly
    ResponseEntity<String> plain =
        post(
            TestTokens.alice(),
            """
            {"intent": {"origin": "BOS", "destination": "SEA",
              "earliestDeparture": "2026-10-06T10:00:00Z", "arrivalDeadline": "2026-10-06T23:00:00Z",
              "hotelRequired": false}, "source": "API"}
            """);
    assertThat(plain.getStatusCode().value()).isEqualTo(202);
    JsonNode plainItinerary = json.readTree(plain.getBody()).get("intent").get("itinerary");
    assertThat(plainItinerary == null || plainItinerary.isNull()).isTrue();
  }

  @Test
  void malformedItinerariesAreRefusedExplicitly() {
    String[] bad = {
      // leg 2 does not depart where leg 1 lands
      "{\"intent\":{\"itinerary\":{\"legs\":[{\"origin\":\"BOS\",\"destination\":\"SEA\",\"earliestDeparture\":\"2026-10-06T10:00:00Z\",\"arrivalDeadline\":\"2026-10-06T23:00:00Z\"},{\"origin\":\"SFO\",\"destination\":\"BOS\",\"earliestDeparture\":\"2026-10-08T10:00:00Z\",\"arrivalDeadline\":\"2026-10-08T23:00:00Z\"}]}}}",
      // an unknown place
      "{\"intent\":{\"itinerary\":{\"legs\":[{\"origin\":\"BOS\",\"destination\":\"XXX\",\"earliestDeparture\":\"2026-10-06T10:00:00Z\",\"arrivalDeadline\":\"2026-10-06T23:00:00Z\"}]}}}",
      // a stay nobody flies to
      "{\"intent\":{\"itinerary\":{\"legs\":[{\"origin\":\"BOS\",\"destination\":\"SEA\",\"earliestDeparture\":\"2026-10-06T10:00:00Z\",\"arrivalDeadline\":\"2026-10-06T23:00:00Z\"}],\"stays\":[{\"city\":\"SFO\",\"checkInDate\":\"2026-10-06\",\"checkOutDate\":\"2026-10-07\"}]}}}",
      // both shapes at once
      "{\"intent\":{\"origin\":\"BOS\",\"destination\":\"SEA\",\"earliestDeparture\":\"2026-10-06T10:00:00Z\",\"arrivalDeadline\":\"2026-10-06T23:00:00Z\",\"itinerary\":{\"legs\":[{\"origin\":\"BOS\",\"destination\":\"SEA\",\"earliestDeparture\":\"2026-10-06T10:00:00Z\",\"arrivalDeadline\":\"2026-10-06T23:00:00Z\"}]}}}",
      // neither shape
      "{\"intent\":{\"purpose\":\"nothing\"}}",
    };
    for (String body : bad) {
      ResponseEntity<String> r = post(TestTokens.alice(), body);
      assertThat(r.getStatusCode().value()).as(body + " -> " + r.getBody()).isEqualTo(422);
      assertThat(r.getBody()).contains("INTENT_INVALID");
    }
  }

  // ------------------------------------------------------------------ helpers

  private ResponseEntity<String> post(String token, String body) {
    return post(token, body, UUID.randomUUID().toString());
  }

  private ResponseEntity<String> post(String token, String body, String idempotencyKey) {
    return http.post()
        .uri("/api/v1/trips")
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

  private Trip transition(
      String trip,
      TripStatus to,
      java.util.function.Consumer<TransitionTripRequest.Builder> customize) {
    TransitionTripRequest.Builder b =
        TransitionTripRequest.newBuilder().setCtx(ctx("acme")).setTripId(trip).setTo(to);
    customize.accept(b);
    return core.transitionTrip(b.build());
  }

  private ConsumerRecord<String, String> awaitEvent(String tripId, String type) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> {
              consumer.poll(Duration.ofMillis(250)).forEach(received::add);
              return received.stream()
                  .anyMatch(r -> r.key().equals(tripId) && r.value().contains("\"" + type + "\""));
            });
    return received.stream()
        .filter(r -> r.key().equals(tripId) && r.value().contains("\"" + type + "\""))
        .findFirst()
        .orElseThrow();
  }

  private static RequestContext ctx(String tenant) {
    return RequestContext.newBuilder()
        .setTenantId(tenant)
        .setCorrelationId("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setCausationId("cmd_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setPrincipal(
            Principal.newBuilder().setKind(Principal.Kind.AGENT).setId("agent/trip-planner/v1"))
        .build();
  }

  private static Money usd(long cents) {
    return Money.newBuilder().setCurrency("USD").setAmountMinor(cents).build();
  }

  @SuppressWarnings("unused")
  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).build();
  }
}
