package io.travelos.disruption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.disruption.v1.Disruption;
import io.travelos.contracts.disruption.v1.DisruptionServiceGrpc;
import io.travelos.contracts.disruption.v1.DisruptionStatus;
import io.travelos.contracts.disruption.v1.GetDisruptionRequest;
import io.travelos.contracts.disruption.v1.RecordRecoveryDecisionRequest;
import io.travelos.contracts.disruption.v1.RecordRecoveryOutcomeRequest;
import io.travelos.contracts.disruption.v1.RecoveryDecision;
import io.travelos.contracts.disruption.v1.RecoveryOutcome;
import io.travelos.contracts.disruption.v1.RecoveryState;
import io.travelos.contracts.disruption.v1.RejectedCandidate;
import io.travelos.contracts.disruption.v1.SupplierResult;
import io.travelos.contracts.disruption.v1.TransitionDisruptionRequest;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.optimization.v1.RankedCandidate;
import io.travelos.contracts.policy.v1.ApproverRequirement;
import io.travelos.contracts.policy.v1.Outcome;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.policy.v1.ReasonCode;
import io.travelos.disruption.service.RecoverySignaler;
import io.travelos.events.Topics;
import io.travelos.events.testing.EventSchemas;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.spring.web.testing.TestTokens;
import io.travelos.workflows.DisruptionRecovery;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
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
 * Real Kafka in (a detected event, delivered twice), a scripted Order service, real Postgres, real
 * gRPC for the workflow's transitions, real REST for people. Every event out is contract-checked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestTokens.class, DisruptionServiceIntegrationTest.Recording.class})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DisruptionServiceIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  static final FakeOrderService ORDERS = new FakeOrderService();
  static final String TRIP = "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV";
  static final String ORDER = "ord_01ARZ3NDEKTSV4RRFFQ69G5FAV";
  static final String DISRUPTION = "dsr_01ARZ3NDEKTSV4RRFFQ69G5FAV";
  static final String UNKNOWN = "dsr_01ARZ3NDEKTSV4RRFFQ69G5FAZ";

  @TestConfiguration
  static class Recording {
    static final List<DisruptionRecovery.ApprovalDecision> signals = new CopyOnWriteArrayList<>();

    @Bean
    @org.springframework.context.annotation.Primary
    RecoverySignaler recordingSignaler() {
      return (id, decision) -> signals.add(decision);
    }
  }

  @DynamicPropertySource
  static void orderAddress(DynamicPropertyRegistry registry) throws IOException {
    int port = ORDERS.start();
    registry.add("travelos.grpc.clients.order.address", () -> "localhost:" + port);
  }

  @Autowired Environment environment;
  @Autowired GrpcServerLifecycle grpc;
  @Autowired JdbcClient jdbc;
  @Autowired KafkaConnectionDetails kafkaConnection;

  private final JsonMapper json = JsonMapper.builder().build();
  private ManagedChannel channel;
  private DisruptionServiceGrpc.DisruptionServiceBlockingStub disruptions;
  private RestClient http;
  private KafkaProducer<String, String> producer;
  private KafkaConsumer<String, String> consumer;
  private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

  @BeforeAll
  void setUp() {
    ORDERS.know("acme", "SBX-known", TRIP, ORDER, "emp_1001");
    channel = ManagedChannelBuilder.forAddress("localhost", grpc.port()).usePlaintext().build();
    disruptions = DisruptionServiceGrpc.newBlockingStub(channel);
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (rq, rs) -> {})
            .build();
    String bootstrap = String.join(",", kafkaConnection.getBootstrapServers());
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    producer = new KafkaProducer<>(p);
    Properties c = new Properties();
    c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    c.put(ConsumerConfig.GROUP_ID_CONFIG, "disruption-test-" + System.nanoTime());
    c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    c.put(
        ConsumerConfig.METADATA_MAX_AGE_CONFIG,
        "1000"); // topics are auto-created after we subscribe
    consumer = new KafkaConsumer<>(c);
    consumer.subscribe(List.of(Topics.DISRUPTION, Topics.APPROVAL));
  }

  @AfterAll
  void tearDown() {
    consumer.close();
    producer.close();
    channel.shutdownNow();
    ORDERS.stop();
  }

  @Test
  @org.junit.jupiter.api.Order(1)
  void aDetectedEventDeliveredTwiceBecomesOneDisruptionTiedToItsOrder() throws Exception {
    String detected =
        detectedEvent("evt_01ARZ3NDEKTSV4RRFFQ69G5FD1", DISRUPTION, "SBX-known", "sbx-evt-1");
    producer.send(new ProducerRecord<>(Topics.DISRUPTION, TRIP, detected)).get();
    producer.send(new ProducerRecord<>(Topics.DISRUPTION, TRIP, detected)).get(); // Kafka dup
    // and a second supplier webhook for the same supplier event, under a new event id
    producer
        .send(
            new ProducerRecord<>(
                Topics.DISRUPTION,
                TRIP,
                detectedEvent(
                    "evt_01ARZ3NDEKTSV4RRFFQ69G5FD2",
                    "dsr_01ARZ3NDEKTSV4RRFFQ69G5FAX",
                    "SBX-known",
                    "sbx-evt-1")))
        .get();

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.sql("SELECT status FROM disruption WHERE disruption_id = :id")
                            .param("id", DISRUPTION)
                            .query(String.class)
                            .optional())
                    .contains("IMPACT_CONFIRMED"));
    assertThat(jdbc.sql("SELECT count(*) FROM disruption").query(Long.class).single())
        .isEqualTo(1L);
    Disruption d = get(DISRUPTION);
    assertThat(d.getTripId()).isEqualTo(TRIP);
    assertThat(d.getOrderId()).isEqualTo(ORDER);
    assertThat(d.getTravelerId()).isEqualTo("emp_1001");
    assertThat(d.getAffected().getFlightNumber()).isEqualTo("DL240");
    assertThat(d.getReason()).isEqualTo("crew availability");
    assertThat(prometheus())
        .contains("duplicate_disruption_events_total 2.0")
        .contains("disruptions_detected_total 1.0");
  }

  @Test
  @org.junit.jupiter.api.Order(2)
  void aReferenceNobodyBookedNeedsAPersonNotARecovery() throws Exception {
    producer
        .send(
            new ProducerRecord<>(
                Topics.DISRUPTION,
                "trip_x",
                detectedEvent(
                    "evt_01ARZ3NDEKTSV4RRFFQ69G5FD3", UNKNOWN, "SBX-stranger", "sbx-evt-9")))
        .get();
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(get(UNKNOWN).getStatus())
                    .isEqualTo(DisruptionStatus.MANUAL_INTERVENTION_REQUIRED));
    Disruption d = get(UNKNOWN);
    assertThat(d.getRecovery().getFailureCode()).isEqualTo("ORDER_NOT_FOUND");
    assertThat(d.hasOutcome()).isTrue();
    assertThat(d.getOutcome().getStatus()).isEqualTo(DisruptionStatus.MANUAL_INTERVENTION_REQUIRED);
  }

  /** A dependency outage delays impact confirmation; it never drops the notice or blocks Kafka. */
  @Test
  @org.junit.jupiter.api.Order(2)
  void anOrderServiceOutageDefersImpactConfirmationInsteadOfLosingTheNotice() throws Exception {
    String id = "dsr_01ARZ3NDEKTSV4RRFFQ69G5FAY";
    ORDERS.know("acme", "SBX-later", TRIP, "ord_01ARZ3NDEKTSV4RRFFQ69G5FAY", "emp_1001");
    ORDERS.down = true;
    try {
      producer
          .send(
              new ProducerRecord<>(
                  Topics.DISRUPTION,
                  TRIP,
                  detectedEvent("evt_01ARZ3NDEKTSV4RRFFQ69G5FD4", id, "SBX-later", "sbx-evt-4")))
          .get();
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () -> assertThat(get(id).getStatus()).isEqualTo(DisruptionStatus.DETECTED));
      Thread.sleep(6_000); // a scheduler pass while the Order service is still down
      assertThat(get(id).getStatus()).isEqualTo(DisruptionStatus.DETECTED);
    } finally {
      ORDERS.down = false;
    }
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(get(id).getStatus()).isEqualTo(DisruptionStatus.IMPACT_CONFIRMED));
    assertThat(get(id).getOrderId()).isEqualTo("ord_01ARZ3NDEKTSV4RRFFQ69G5FAY");
  }

  @Test
  @org.junit.jupiter.api.Order(3)
  void theWorkflowDrivesTheStateMachineAndTheRecordsAreImmutable() {
    transition(DisruptionStatus.SEARCHING_ALTERNATIVES, null, null);
    transition(DisruptionStatus.SEARCHING_ALTERNATIVES, null, null); // idempotent by state
    transition(DisruptionStatus.OPTIMIZING, null, null);
    assertThatThrownBy(() -> transition(DisruptionStatus.RESOLVED, null, null))
        .as("terminal states go through RecordRecoveryOutcome")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
    assertThatThrownBy(() -> transition(DisruptionStatus.CHANGING, null, null))
        .as("OPTIMIZING cannot jump to CHANGING")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getDescription()).startsWith("ILLEGAL_TRANSITION"));

    RecoveryDecision decision =
        RecoveryDecision.newBuilder()
            .setTrigger("FLIGHT_CANCELLED DL240 BOS-SEA")
            .setCandidatesSearched(17)
            .setCandidatesPermitted(11)
            .setCandidatesFeasible(9)
            .addRejected(
                RejectedCandidate.newBuilder()
                    .setBundleId("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA9")
                    .setStage("POLICY")
                    .addReasonCodes("CABIN_NOT_PERMITTED"))
            .setSelected(
                Bundle.newBuilder()
                    .setBundleId("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA1")
                    .setTotal(usd(56858)))
            .setSelectedRanking(
                RankedCandidate.newBuilder()
                    .setBundleId("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA1")
                    .setScore(88.2)
                    .setFeasible(true)
                    .setRank(1))
            .setOptimizationRunId("opt_01ARZ3NDEKTSV4RRFFQ69G5FA1")
            .setOriginalTotal(usd(49558))
            .setReplacementTotal(usd(56858))
            .setIncrementalCost(usd(7300))
            .setPolicyDecision(
                PolicyDecision.newBuilder()
                    .setDecisionId("pd_01ARZ3NDEKTSV4RRFFQ69G5FA1")
                    .setPolicyId("US_STANDARD_TRAVEL")
                    .setPolicyVersion(2)
                    .setOutcome(Outcome.ALLOW_WITH_APPROVAL)
                    .setRequiresApproval(true)
                    .addApprovers(ApproverRequirement.newBuilder().setRole("MANAGER"))
                    .addReasons(
                        ReasonCode.newBuilder().setCode("INCREMENTAL_COST_ABOVE_AUTONOMY_LIMIT")))
            .setAutonomyOutcome("ALLOW_WITH_APPROVAL")
            .setAgentPrincipal(DisruptionRecovery.AGENT)
            .setWorkflowType(DisruptionRecovery.WORKFLOW_TYPE)
            .setWorkflowVersion("1")
            .build();
    Disruption ready =
        disruptions.recordRecoveryDecision(
            RecordRecoveryDecisionRequest.newBuilder()
                .setCtx(ctx())
                .setDisruptionId(DISRUPTION)
                .setDecision(decision)
                .build());
    assertThat(ready.getStatus()).isEqualTo(DisruptionStatus.DECISION_READY);
    assertThat(ready.getDecision().getDecisionId()).startsWith("rcd_");
    assertThat(ready.getRecovery().getIncrementalCost().getAmountMinor()).isEqualTo(7300);
    Disruption again =
        disruptions.recordRecoveryDecision(
            RecordRecoveryDecisionRequest.newBuilder()
                .setCtx(ctx())
                .setDisruptionId(DISRUPTION)
                .setDecision(decision.toBuilder().setCandidatesSearched(999))
                .build());
    assertThat(again.getDecision().getCandidatesSearched()).as("written once").isEqualTo(17);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "UPDATE recovery_decision SET record = '{}'::jsonb WHERE disruption_id = :id")
                    .param("id", DISRUPTION)
                    .update())
        .as("the database itself refuses")
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM recovery_decision WHERE disruption_id = :id")
                    .param("id", DISRUPTION)
                    .update())
        .hasMessageContaining("immutable");

    Disruption waiting =
        transition(
            DisruptionStatus.HUMAN_REQUIRED,
            "MANAGER",
            ready.getRecovery().toBuilder()
                .setExplanation("DL242 replaces DL240 for USD 73.00 more.")
                .build());
    assertThat(waiting.getRecovery().getApprovalId()).startsWith("apr_");
    assertThat(waiting.getRecovery().getApprovalStatus()).isEqualTo("PENDING");
    assertThat(prometheus())
        .contains("human_escalation_total 1.0")
        .contains("recovery_attempts_total 1.0");
  }

  @Test
  @org.junit.jupiter.api.Order(4)
  void peopleSeeTheirDisruptionAndOnlyAManagerMayApproveIt() {
    ResponseEntity<String> mine = get("/api/v1/trips/" + TRIP + "/disruptions", TestTokens.alice());
    assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode list = json.readTree(mine.getBody());
    assertThat(list).hasSize(2); // the one under test and the deferred one
    JsonNode view = null;
    for (JsonNode n : list) {
      if (DISRUPTION.equals(n.get("disruptionId").asString())) {
        view = n;
      }
    }
    assertThat(view).isNotNull();
    assertThat(view.get("status").asString()).isEqualTo("HUMAN_REQUIRED");
    assertThat(view.get("decision").get("candidatesSearched").asInt()).isEqualTo(17);
    assertThat(view.get("decision").get("rejected").get(0).get("reasonCodes").get(0).asString())
        .isEqualTo("CABIN_NOT_PERMITTED");
    assertThat(view.get("decision").get("selected").get("bundleId").asString())
        .isEqualTo("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA1");
    assertThat(view.get("recovery").get("explanation").asString()).contains("DL242");
    assertThat(view.get("approval").get("status").asString()).isEqualTo("PENDING");
    assertThat(view.get("history").size()).isGreaterThanOrEqualTo(4);
    // another tenant, and a traveler who is not the one hit, see nothing
    assertThat(
            json.readTree(
                get("/api/v1/trips/" + TRIP + "/disruptions", TestTokens.zoe()).getBody()))
        .isEmpty();
    assertThat(
            json.readTree(
                get("/api/v1/trips/" + TRIP + "/disruptions", TestTokens.dan()).getBody()))
        .isEmpty();
    assertThat(get("/api/v1/disruptions/" + DISRUPTION, TestTokens.zoe()).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/disruptions/" + DISRUPTION, TestTokens.bob()).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    // the operations inbox: a manager lists what needs a person; a traveler sees only their own;
    // another tenant sees nothing
    JsonNode inbox =
        json.readTree(get("/api/v1/disruptions?status=HUMAN_REQUIRED", TestTokens.bob()).getBody());
    assertThat(inbox).extracting(n -> n.get("disruptionId").asString()).contains(DISRUPTION);
    assertThat(json.readTree(get("/api/v1/disruptions", TestTokens.alice()).getBody()))
        .extracting(n -> n.get("disruptionId").asString())
        .contains(DISRUPTION);
    assertThat(json.readTree(get("/api/v1/disruptions", TestTokens.dan()).getBody())).isEmpty();
    assertThat(json.readTree(get("/api/v1/disruptions", TestTokens.zoe()).getBody())).isEmpty();
    assertThat(get("/api/v1/disruptions?status=NOPE", TestTokens.bob()).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

    // the traveler cannot approve her own recovery; a manager can, once
    assertThat(decide(TestTokens.alice(), "APPROVE", "k-1").getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    ResponseEntity<String> approved = decide(TestTokens.bob(), "APPROVE", "k-2");
    assertThat(approved.getStatusCode()).as(approved.getBody()).isEqualTo(HttpStatus.OK);
    assertThat(json.readTree(approved.getBody()).get("status").asString()).isEqualTo("APPROVED");
    assertThat(decide(TestTokens.bob(), "APPROVE", "k-2").getStatusCode())
        .as("same key replays")
        .isEqualTo(HttpStatus.OK);
    assertThat(decide(TestTokens.bob(), "REJECT", "k-3").getStatusCode())
        .as("already decided")
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(Recording.signals)
        .singleElement()
        .satisfies(s -> assertThat(s.decision()).isEqualTo("APPROVED"));
    assertThat(get(DISRUPTION).getRecovery().getApprovalStatus()).isEqualTo("APPROVED");
  }

  // ------------------------------------------------------------------ Phase 6

  @Test
  @org.junit.jupiter.api.Order(6)
  void aTravelerAsksToMoveHerFlightAndTheRequestBecomesARecoveryWithHerAsTheActor() {
    String body =
        "{\"tripId\":\""
            + TRIP
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"componentId\":\"cmp_01ARZ3NDEKTSV4RRFFQ69G5FA0\","
            + "\"notBefore\":\"2026-10-07T08:00:00Z\",\"notAfter\":\"2026-10-07T20:00:00Z\",\"reason\":\"the meeting moved to Wednesday\"}";
    // dan is not the traveler of that order: he learns nothing
    assertThat(request(TestTokens.dan(), body, "cr-dan").getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<String> created = request(TestTokens.alice(), body, "cr-1");
    assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
    JsonNode view = json.readTree(created.getBody());
    String id = view.get("disruptionId").asString();
    assertThat(id).startsWith("dsr_");
    assertThat(view.get("type").asString()).isEqualTo("TRAVELER_REQUEST");
    assertThat(view.get("status").asString()).isEqualTo("IMPACT_CONFIRMED");
    assertThat(view.get("tripId").asString()).isEqualTo(TRIP);
    assertThat(view.get("orderId").asString()).isEqualTo(ORDER);
    assertThat(view.get("travelerId").asString()).isEqualTo("emp_1001");
    assertThat(view.get("severity").asString()).isEqualTo("LOW");
    assertThat(view.get("reason").asString()).isEqualTo("the meeting moved to Wednesday");
    JsonNode affected = view.get("affected");
    assertThat(affected.get("flightNumber").asString()).isEqualTo("DL240");
    assertThat(affected.get("componentId").asString()).isEqualTo("cmp_01ARZ3NDEKTSV4RRFFQ69G5FA0");
    assertThat(affected.get("requestedNotBefore").asString()).isEqualTo("2026-10-07T08:00:00Z");
    assertThat(affected.get("requestedNotAfter").asString()).isEqualTo("2026-10-07T20:00:00Z");
    assertThat(affected.get("requestedBy").asString()).isEqualTo("human/alice");
    // the same key again is the same disruption, not a second recovery
    assertThat(
            json.readTree(request(TestTokens.alice(), body, "cr-1").getBody())
                .get("disruptionId")
                .asString())
        .isEqualTo(id);
    assertThat(get(id).getType())
        .isEqualTo(io.travelos.contracts.disruption.v1.DisruptionType.TRAVELER_REQUEST);
    assertThat(get(id).getAffected().getRequestedBy()).isEqualTo("human/alice");
    // a window in the past, an unknown component: refused explicitly
    assertThat(
            request(
                    TestTokens.alice(),
                    body.replace("2026-10-07T08:00:00Z", "2020-01-01T08:00:00Z")
                        .replace("2026-10-07T20:00:00Z", "2020-01-02T08:00:00Z"),
                    "cr-2")
                .getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(
            request(
                    TestTokens.alice(),
                    body.replace(
                        "cmp_01ARZ3NDEKTSV4RRFFQ69G5FA0", "cmp_01ARZ3NDEKTSV4RRFFQ69G5FZZ"),
                    "cr-3")
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    // the recovery starts from the same event every supplier disruption starts from
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> {
              consumer.poll(Duration.ofMillis(250)).forEach(received::add);
              return received.stream()
                  .anyMatch(
                      r ->
                          r.value().contains(id)
                              && r.value().contains("travel.disruption.impact-confirmed"));
            });
    ConsumerRecord<String, String> confirmed =
        received.stream()
            .filter(
                r ->
                    r.value().contains(id)
                        && r.value().contains("travel.disruption.impact-confirmed"))
            .findFirst()
            .orElseThrow();
    assertThat(EventSchemas.violations(confirmed.value())).as(confirmed.value()).isEmpty();
    JsonNode data = json.readTree(confirmed.value()).get("data");
    assertThat(data.get("type").asString()).isEqualTo("TRAVELER_REQUEST");
    assertThat(data.get("affected").get("requestedBy").asString()).isEqualTo("human/alice");
    assertThat(confirmed.key()).isEqualTo(TRIP);
  }

  private ResponseEntity<String> request(String token, String body, String key) {
    return http.post()
        .uri("/api/v1/disruptions/requests")
        .header("Authorization", "Bearer " + token)
        .header("Idempotency-Key", key)
        .header("Content-Type", "application/json")
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  @Test
  @org.junit.jupiter.api.Order(5)
  void theOutcomeIsWrittenOnceAndTheWholeStoryIsOnTheBroker() {
    transition(DisruptionStatus.CHANGING, null, null);
    RecoveryOutcome outcome =
        RecoveryOutcome.newBuilder()
            .setStatus(DisruptionStatus.RESOLVED)
            .setApprovalId(get(DISRUPTION).getRecovery().getApprovalId())
            .setApprovedBy("human/bob")
            .setChangedOrderId(ORDER)
            .setSupplierResult(
                SupplierResult.newBuilder()
                    .setExternalOrderId("SBX-known")
                    .setStatus("CHANGED")
                    .setRecordLocator("TM3KA6")
                    .setCharged(usd(56858)))
            .build();
    Disruption resolved =
        disruptions.recordRecoveryOutcome(
            RecordRecoveryOutcomeRequest.newBuilder()
                .setCtx(ctx())
                .setDisruptionId(DISRUPTION)
                .setOutcome(outcome)
                .build());
    assertThat(resolved.getStatus()).isEqualTo(DisruptionStatus.RESOLVED);
    assertThat(resolved.getOutcome().getOutcomeId()).startsWith("rco_");
    Disruption again =
        disruptions.recordRecoveryOutcome(
            RecordRecoveryOutcomeRequest.newBuilder()
                .setCtx(ctx())
                .setDisruptionId(DISRUPTION)
                .setOutcome(outcome.toBuilder().setStatus(DisruptionStatus.FAILED))
                .build());
    assertThat(again.getStatus()).as("written once").isEqualTo(DisruptionStatus.RESOLVED);
    assertThat(prometheus())
        .contains("recovery_success_total 1.0")
        .contains("incremental_rebooking_cost_sum 7300.0");

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              consumer.poll(Duration.ofMillis(250)).forEach(received::add);
              List<String> types =
                  received.stream()
                      .map(r -> json.readTree(r.value()).get("eventType").asString())
                      .toList();
              assertThat(types)
                  .contains(
                      "travel.disruption.impact-confirmed",
                      "travel.disruption.recovery-started",
                      "travel.disruption.decision-ready",
                      "travel.disruption.approval-required",
                      "travel.approval.approved",
                      "travel.disruption.resolved",
                      "travel.disruption.recovery-failed");
            });
    for (ConsumerRecord<String, String> record : received) {
      JsonNode e = json.readTree(record.value());
      if (!"disruption".equals(e.get("producer").asString())) {
        continue;
      }
      assertThat(EventSchemas.violations(record.value())).as(record.value()).isEmpty();
      if (DISRUPTION.equals(e.get("data").get("disruptionId").asString())) {
        assertThat(record.key()).as("every event of the trip is keyed by the trip").isEqualTo(TRIP);
        assertThat(e.get("correlationId").asString()).isEqualTo(TRIP);
      }
    }
    JsonNode resolvedEvent =
        received.stream()
            .map(r -> json.readTree(r.value()))
            .filter(e -> "travel.disruption.resolved".equals(e.get("eventType").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(resolvedEvent.get("data").get("incrementalCost").get("amountMinor").asLong())
        .isEqualTo(7300);
    assertThat(resolvedEvent.get("data").get("approvedBy").asString()).isEqualTo("human/bob");
    assertThat(resolvedEvent.get("data").get("autonomyOutcome").asString())
        .isEqualTo("ALLOW_WITH_APPROVAL");
  }

  // ------------------------------------------------------------------ helpers

  private Disruption get(String id) {
    return disruptions.getDisruption(
        GetDisruptionRequest.newBuilder().setCtx(ctx()).setDisruptionId(id).build());
  }

  private Disruption transition(DisruptionStatus to, String role, RecoveryState recovery) {
    TransitionDisruptionRequest.Builder b =
        TransitionDisruptionRequest.newBuilder()
            .setCtx(ctx())
            .setDisruptionId(DISRUPTION)
            .setTo(to);
    if (role != null) {
      b.setApproverRole(role);
    }
    if (recovery != null) {
      b.setRecovery(recovery);
    }
    return disruptions.transitionDisruption(b.build());
  }

  private ResponseEntity<String> get(String path, String token) {
    return http.get()
        .uri(path)
        .header("Authorization", "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> decide(String token, String decision, String key) {
    return http.post()
        .uri("/api/v1/disruptions/" + DISRUPTION + "/approval")
        .header("Authorization", "Bearer " + token)
        .header("Idempotency-Key", key)
        .header("Content-Type", "application/json")
        .body("{\"decision\":\"" + decision + "\",\"comment\":\"ok\"}")
        .retrieve()
        .toEntity(String.class);
  }

  private String prometheus() {
    return http.get().uri("/actuator/prometheus").retrieve().body(String.class);
  }

  private static RequestContext ctx() {
    return RequestContext.newBuilder()
        .setTenantId("acme")
        .setCorrelationId(TRIP)
        .setCausationId(DISRUPTION)
        .setPrincipal(
            Principal.newBuilder().setKind(Principal.Kind.AGENT).setId(DisruptionRecovery.AGENT))
        .build();
  }

  private static Money usd(long cents) {
    return Money.newBuilder().setCurrency("USD").setAmountMinor(cents).build();
  }

  private static String detectedEvent(
      String eventId, String disruptionId, String externalOrderId, String supplierEventId) {
    return """
        {"eventId":"%s","eventType":"travel.disruption.detected","eventVersion":1,"occurredAt":"2026-09-13T14:05:12Z",
         "tenantId":"acme","correlationId":"%s","causationId":"%s","producer":"supplier-gateway",
         "data":{"disruptionId":"%s","type":"FLIGHT_CANCELLED","supplier":"sandbox-air","supplierEventId":"%s",
                 "externalOrderId":"%s","recordLocator":"TM3KA6","detectedAt":"2026-09-13T14:05:11Z","severity":"HIGH",
                 "rawReference":"sandbox-air/notices/%s","reason":"crew availability",
                 "affected":{"segmentId":"DL240-BOS","carrier":"DL","flightNumber":"DL240","origin":"BOS","destination":"SEA",
                             "scheduledDeparture":"2026-10-06T10:00:00Z","scheduledArrival":"2026-10-06T16:20:00Z"}}}
        """
        .formatted(
            eventId,
            TRIP,
            supplierEventId,
            disruptionId,
            supplierEventId,
            externalOrderId,
            supplierEventId);
  }
}
