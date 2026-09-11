package io.travelos.policy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.offer.v1.AirOffer;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.offer.v1.Journey;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.policy.v1.CandidateDecision;
import io.travelos.contracts.policy.v1.EvaluateActionRequest;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluateTripResponse;
import io.travelos.contracts.policy.v1.Outcome;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.policy.v1.PolicyServiceGrpc;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.events.EventCodec;
import io.travelos.events.Topics;
import io.travelos.events.testing.EventSchemas;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.spring.web.testing.TestTokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP for the admin API, real gRPC over TCP for evaluation, real Postgres and Kafka. Ordered:
 * the policy published in the first tests is the one the evaluations use.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestTokens.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PolicyIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  private static final String SEED = EventSchemas.resource("policies/acme-us-standard.json");
  private static final String TRIP = "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV";

  @Autowired Environment environment;
  @Autowired GrpcServerLifecycle grpc;
  @Autowired KafkaConnectionDetails kafkaConnection;

  private final JsonMapper json = JsonMapper.builder().build();
  private final EventCodec codec = new EventCodec();
  private RestClient http;
  private ManagedChannel channel;
  private PolicyServiceGrpc.PolicyServiceBlockingStub policy;
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
    channel = ManagedChannelBuilder.forAddress("localhost", grpc.port()).usePlaintext().build();
    policy = PolicyServiceGrpc.newBlockingStub(channel);
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnection.getBootstrapServers()));
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "policy-test-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of(Topics.POLICY));
  }

  @AfterAll
  void tearDown() {
    consumer.close();
    channel.shutdownNow();
  }

  // ------------------------------------------------------------------ admin API

  @Test
  @Order(1)
  void travelAdminPublishesVersionOneWhichBecomesTheDefault() {
    ResponseEntity<String> created = publish(TestTokens.carol(), SEED, "initial policy");
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    JsonNode body = json.readTree(created.getBody());
    assertThat(body.get("policyId").asString()).isEqualTo("US_STANDARD_TRAVEL");
    assertThat(body.get("version").asInt()).isEqualTo(1);
    assertThat(body.get("publishedBy").asString()).isEqualTo("human/carol");
    assertThat(body.get("documentHash").asString()).hasSize(64);
    assertThat(created.getHeaders().getLocation())
        .hasPath("/api/v1/policies/US_STANDARD_TRAVEL/versions/1");

    JsonNode list = json.readTree(get("/api/v1/policies", TestTokens.bob()).getBody());
    assertThat(list).hasSize(1);
    assertThat(list.get(0).get("isDefault").asBoolean()).isTrue();
  }

  @Test
  @Order(2)
  void onlyTravelAdminsPublish() {
    assertThat(publish(TestTokens.alice(), SEED, null).getStatusCode().value()).isEqualTo(403);
    assertThat(publish(TestTokens.bob(), SEED, null).getStatusCode().value()).isEqualTo(403);
    assertThat(get("/api/v1/policies", TestTokens.alice()).getStatusCode().value()).isEqualTo(403);
  }

  @Test
  @Order(3)
  void republishingTheSameDocumentIsIdempotentAndAChangeIsANewVersion() {
    ResponseEntity<String> same = publish(TestTokens.carol(), SEED, "retry");
    assertThat(same.getStatusCode().value()).isEqualTo(200);
    assertThat(json.readTree(same.getBody()).get("version").asInt()).isEqualTo(1);

    ResponseEntity<String> changed =
        publish(
            TestTokens.carol(),
            SEED.replace("\"nightlyLimit\": 24000", "\"nightlyLimit\": 26000"),
            "Q4 conferences");
    assertThat(changed.getStatusCode().value()).isEqualTo(201);
    assertThat(json.readTree(changed.getBody()).get("version").asInt()).isEqualTo(2);
    assertThat(json.readTree(changed.getBody()).get("note").asString()).isEqualTo("Q4 conferences");

    JsonNode current =
        json.readTree(get("/api/v1/policies/US_STANDARD_TRAVEL", TestTokens.carol()).getBody());
    assertThat(current.get("version").asInt()).isEqualTo(2);
    assertThat(current.get("document").get("hotel").get("nightlyLimit").asLong()).isEqualTo(26000);
    JsonNode v1 =
        json.readTree(
            get("/api/v1/policies/US_STANDARD_TRAVEL/versions/1", TestTokens.carol()).getBody());
    assertThat(v1.get("document").get("hotel").get("nightlyLimit").asLong()).isEqualTo(24000);
  }

  @Test
  @Order(4)
  void invalidDocumentsAre422WithEveryProblemListed() {
    ResponseEntity<String> response =
        publish(TestTokens.carol(), SEED.replace("\"maxStops\": 1", "\"maxStopz\": 1"), null);
    assertThat(response.getStatusCode().value()).isEqualTo(422);
    JsonNode problem = json.readTree(response.getBody());
    assertThat(problem.get("code").asString()).isEqualTo("POLICY_INVALID");
    assertThat(problem.get("problems").get(0).asString()).contains("maxStopz");
  }

  // ------------------------------------------------------------------ gRPC evaluation

  @Test
  @Order(10)
  void evaluatesEveryCandidateAgainstTheCurrentVersionAndRecordsEvidence() {
    EvaluateTripResponse response =
        policy.evaluateTrip(
            EvaluateTripRequest.newBuilder()
                .setCtx(ctx("acme", "agent/trip-planner/v1"))
                .setTripId(TRIP)
                .setTravelerId("emp_1001")
                .setIntent(TravelIntent.newBuilder().setOrigin("BOS").setDestination("SEA"))
                .addCandidates(bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FAA", 47500, Cabin.ECONOMY, 0))
                .addCandidates(bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FAC", 90000, Cabin.BUSINESS, 0))
                .addCandidates(bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FAE", 70000, Cabin.ECONOMY, 0))
                .build());

    assertThat(response.getEvaluationId()).startsWith("dec_");
    assertThat(response.getCandidatesList())
        .extracting(CandidateDecision::getBundleId)
        .containsExactly(
            "bdl_01ARZ3NDEKTSV4RRFFQ69G5FAA",
            "bdl_01ARZ3NDEKTSV4RRFFQ69G5FAC",
            "bdl_01ARZ3NDEKTSV4RRFFQ69G5FAE");
    PolicyDecision a = response.getCandidates(0).getDecision();
    PolicyDecision c = response.getCandidates(1).getDecision();
    PolicyDecision e = response.getCandidates(2).getDecision();
    assertThat(a.getOutcome()).isEqualTo(Outcome.ALLOW);
    assertThat(a.getPolicyId()).isEqualTo("US_STANDARD_TRAVEL");
    assertThat(a.getPolicyVersion()).as("the latest published version decides").isEqualTo(2);
    assertThat(a.getDecisionId()).startsWith("pd_");
    assertThat(a.getEconomics().getTravelerIncentive().getAmountMinor()).isEqualTo(3750);
    assertThat(a.getRulesEvaluatedList())
        .contains("CABIN_PERMITTED", "LOWEST_LOGICAL_FARE", "INCENTIVE_SHARE");
    assertThat(c.getOutcome()).isEqualTo(Outcome.DENY);
    assertThat(c.getReasonsList())
        .extracting(r -> r.getCode())
        .containsExactly("CABIN_NOT_PERMITTED", "FARE_ABOVE_POLICY_CEILING");
    assertThat(e.getOutcome()).isEqualTo(Outcome.ALLOW_WITH_TRAVELER_PAYMENT);
    assertThat(e.getEconomics().getTravelerPays().getAmountMinor()).isEqualTo(7500);

    // Evidence is readable: the traveler and tenant-wide roles see it, nobody else does.
    JsonNode mine =
        json.readTree(get("/api/v1/policy-decisions?tripId=" + TRIP, TestTokens.alice()).getBody());
    assertThat(mine).hasSize(3);
    assertThat(mine.get(1).get("outcome").asString()).isEqualTo("DENY");
    assertThat(mine.get(1).get("decision").get("reasons").get(0).get("code").asString())
        .isEqualTo("CABIN_NOT_PERMITTED");
    assertThat(mine.get(0).get("evaluatedFor").asString()).isEqualTo("agent/trip-planner/v1");
    assertThat(
            json.readTree(
                get("/api/v1/policy-decisions?tripId=" + TRIP, TestTokens.dan()).getBody()))
        .isEmpty();
    assertThat(
            get("/api/v1/policy-decisions/" + c.getDecisionId(), TestTokens.bob())
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            get("/api/v1/policy-decisions/" + c.getDecisionId(), TestTokens.zoe())
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            get("/api/v1/policy-decisions/" + c.getDecisionId(), TestTokens.dan())
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  @Test
  @Order(11)
  void aTenantWithoutAPolicyIsDeniedNotWavedThrough() {
    EvaluateTripResponse response =
        policy.evaluateTrip(
            EvaluateTripRequest.newBuilder()
                .setCtx(ctx("globex", "agent/trip-planner/v1"))
                .setTripId("trip_01ARZ3NDEKTSV4RRFFQ69G5FAW")
                .setTravelerId("emp_2001")
                .addCandidates(bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FAG", 30000, Cabin.ECONOMY, 0))
                .build());
    PolicyDecision decision = response.getCandidates(0).getDecision();
    assertThat(decision.getOutcome()).isEqualTo(Outcome.DENY);
    assertThat(decision.getReasons(0).getCode()).isEqualTo("NO_POLICY");
    assertThat(decision.getPolicyVersion()).isZero();
  }

  @Test
  @Order(12)
  void agentActionsAreJudgedByAutonomyLimits() {
    PolicyDecision within =
        policy.evaluateAction(
            EvaluateActionRequest.newBuilder()
                .setCtx(ctx("acme", "agent/disruption-recovery/v1"))
                .setTripId(TRIP)
                .setTravelerId("emp_1001")
                .setAction("order.change")
                .setIncrementalCost(usd(7300))
                .build());
    assertThat(within.getOutcome()).isEqualTo(Outcome.ALLOW);

    PolicyDecision above =
        policy.evaluateAction(
            EvaluateActionRequest.newBuilder()
                .setCtx(ctx("acme", "agent/disruption-recovery/v1"))
                .setTripId(TRIP)
                .setTravelerId("emp_1001")
                .setAction("order.change")
                .setIncrementalCost(usd(12000))
                .build());
    assertThat(above.getOutcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
    assertThat(above.getApprovers(0).getRole()).isEqualTo("TRAVELER");
  }

  @Test
  @Order(13)
  void requestsWithoutAContextAreRejected() {
    assertThatThrownBy(
            () -> policy.evaluateTrip(EvaluateTripRequest.newBuilder().setTripId(TRIP).build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  @Test
  @Order(20)
  void everyDecisionAndPublicationIsAContractValidEvent() {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              consumer.poll(Duration.ofMillis(250)).forEach(received::add);
              assertThat(
                      received.stream()
                          .map(r -> type(r.value()))
                          .filter("travel.policy.published"::equals))
                  .hasSize(2);
              assertThat(
                      received.stream()
                          .map(r -> type(r.value()))
                          .filter("travel.policy.evaluated"::equals)
                          .count())
                  .isGreaterThanOrEqualTo(6);
              assertThat(
                      received.stream()
                          .map(r -> type(r.value()))
                          .filter("travel.policy.violation"::equals)
                          .count())
                  .isGreaterThanOrEqualTo(2);
            });
    for (ConsumerRecord<String, String> record : received) {
      assertThat(EventSchemas.violations(record.value())).as(record.value()).isEmpty();
      assertThat(codec.fromJson(record.value()).producer()).isEqualTo("policy");
    }
    assertThat(
            received.stream()
                .filter(r -> type(r.value()).equals("travel.policy.evaluated"))
                .map(ConsumerRecord::key))
        .as("evaluation events are keyed by trip id")
        .contains(TRIP);
  }

  // ------------------------------------------------------------------ helpers

  private static RequestContext ctx(String tenant, String principal) {
    return RequestContext.newBuilder()
        .setTenantId(tenant)
        .setCorrelationId(TRIP)
        .setPrincipal(Principal.newBuilder().setKind(Principal.Kind.AGENT).setId(principal))
        .build();
  }

  private static Money usd(long cents) {
    return Money.newBuilder().setCurrency("USD").setAmountMinor(cents).build();
  }

  private static Bundle bundle(String id, long fareCents, Cabin cabin, int stops) {
    Journey.Builder outbound = Journey.newBuilder();
    String[] via = {"BOS", "ORD", "DEN", "SEA"};
    for (int i = 0; i <= stops; i++) {
      outbound.addSegments(
          FlightSegment.newBuilder()
              .setOrigin(i == 0 ? "BOS" : via[i])
              .setDestination(i == stops ? "SEA" : via[i + 1])
              .setCabin(cabin)
              .setCarrier("DL"));
    }
    Offer air =
        Offer.newBuilder()
            .setOfferId("off_" + id)
            .setProvider("sandbox-air")
            .setType(OfferType.AIR)
            .setTotal(usd(fareCents))
            .setAir(AirOffer.newBuilder().setOutbound(outbound))
            .build();
    return Bundle.newBuilder().setBundleId(id).addOffers(air).setTotal(usd(fareCents)).build();
  }

  private ResponseEntity<String> publish(String token, String document, String note) {
    String body =
        "{\"document\":" + document + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    return http.post()
        .uri("/api/v1/policies")
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

  private String type(String eventJson) {
    return json.readTree(eventJson).get("eventType").asString();
  }
}
