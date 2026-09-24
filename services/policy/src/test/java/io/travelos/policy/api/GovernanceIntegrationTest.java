package io.travelos.policy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.travelos.common.tenant.TenantId;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.offer.v1.AirOffer;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.offer.v1.HotelOffer;
import io.travelos.contracts.offer.v1.Journey;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.policy.v1.BudgetReservation;
import io.travelos.contracts.policy.v1.BudgetStatus;
import io.travelos.contracts.policy.v1.EvaluateTripRequest;
import io.travelos.contracts.policy.v1.EvaluationScope;
import io.travelos.contracts.policy.v1.GetGovernanceRequest;
import io.travelos.contracts.policy.v1.Governance;
import io.travelos.contracts.policy.v1.Outcome;
import io.travelos.contracts.policy.v1.PolicyDecision;
import io.travelos.contracts.policy.v1.PolicyServiceGrpc;
import io.travelos.contracts.policy.v1.ReserveBudgetRequest;
import io.travelos.contracts.trip.v1.TravelIntent;
import io.travelos.events.EventCodec;
import io.travelos.events.EventEnvelope;
import io.travelos.events.Topics;
import io.travelos.events.testing.EventSchemas;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.spring.web.testing.TestTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 7: governance through the admin API and the gRPC door. Scoped policies resolve most
 * specific first; approval chains and expiry come from the document; budgets reserve under a row
 * lock (two trips racing for the last of it: exactly one gets it) and settle from the trip's own
 * events; supplier agreements steer the search and, when the policy says so, the verdict.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestTokens.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GovernanceIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  private static final String SEED = EventSchemas.resource("policies/acme-us-standard.json");
  private static final String TRIP = "trip_01ARZ3NDEKTSV4RRFFQ69G5FB1";

  @Autowired Environment environment;
  @Autowired GrpcServerLifecycle grpc;
  @Autowired KafkaTemplate<String, String> kafka;

  private final JsonMapper json = JsonMapper.builder().build();
  private final EventCodec codec = new EventCodec();
  private RestClient http;
  private ManagedChannel channel;
  private PolicyServiceGrpc.PolicyServiceBlockingStub policy;
  private String budgetId;

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
  }

  @AfterAll
  void tearDown() {
    channel.shutdownNow();
  }

  @Test
  @Order(1)
  void theMostSpecificScopeDecidesWhichPolicyApplies() {
    assertThat(publish(TestTokens.carol(), SEED).getStatusCode().value()).isEqualTo(201);
    String apollo =
        SEED.replace("\"policyId\": \"US_STANDARD_TRAVEL\"", "\"policyId\": \"PROJECT_APOLLO\"")
            .replace(
                "\"maxTotal\": 400000,\n    \"onViolation\": \"REQUIRE_APPROVAL\"",
                "\"maxTotal\": 50000,\n    \"onViolation\": \"DENY\"");
    assertThat(apollo).contains("\"maxTotal\": 50000");
    assertThat(publish(TestTokens.carol(), apollo).getStatusCode().value()).isEqualTo(201);
    String chain =
        SEED.replace("\"policyId\": \"US_STANDARD_TRAVEL\"", "\"policyId\": \"CHAIN_POLICY\"")
            .replace(
                "\"approval\": {\n    \"managerRequiredAbove\": 120000\n  }",
                "\"approval\": {\n    \"managerRequiredAbove\": 120000,\n    \"chain\": [{\"role\": \"MANAGER\"}, {\"role\": \"FINANCE\", \"above\": 200000}],\n    \"expiresAfterHours\": 24,\n    \"escalateTo\": \"TRAVEL_ADMIN\"\n  },\n  \"suppliers\": {\"onNonPreferred\": \"REQUIRE_APPROVAL\"}");
    assertThat(chain).contains("\"chain\"");
    ResponseEntity<String> chainPublished = publish(TestTokens.carol(), chain);
    assertThat(chainPublished.getStatusCode().value()).as(chainPublished.getBody()).isEqualTo(201);

    // only a travel admin assigns scopes; an unknown policy is refused with the reason
    assertThat(
            put(
                    "/api/v1/policies/scopes",
                    TestTokens.bob(),
                    "{\"scopeKind\":\"PROJECT\",\"scopeRef\":\"prj_apollo\",\"policyId\":\"PROJECT_APOLLO\"}")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            put(
                    "/api/v1/policies/scopes",
                    TestTokens.carol(),
                    "{\"scopeKind\":\"PROJECT\",\"scopeRef\":\"prj_apollo\",\"policyId\":\"NOPE\"}")
                .getStatusCode()
                .value())
        .isEqualTo(422);
    assertThat(
            put(
                    "/api/v1/policies/scopes",
                    TestTokens.carol(),
                    "{\"scopeKind\":\"PROJECT\",\"scopeRef\":\"prj_apollo\",\"policyId\":\"PROJECT_APOLLO\"}")
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            put(
                    "/api/v1/policies/scopes",
                    TestTokens.carol(),
                    "{\"scopeKind\":\"COST_CENTER\",\"scopeRef\":\"cc_9\",\"policyId\":\"CHAIN_POLICY\"}")
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(json.readTree(get("/api/v1/policies/scopes", TestTokens.bob()).getBody()))
        .hasSize(2);

    // the same candidate: fine under the tenant default, denied under the project's policy
    PolicyDecision byDefault =
        evaluate(scope("", "", ""), air("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB2", 70000));
    assertThat(byDefault.getPolicyId()).isEqualTo("US_STANDARD_TRAVEL");
    assertThat(byDefault.getOutcome()).isEqualTo(Outcome.ALLOW);
    PolicyDecision byProject =
        evaluate(scope("", "cc_9", "prj_apollo"), air("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB3", 70000));
    assertThat(byProject.getPolicyId()).as("project beats cost center").isEqualTo("PROJECT_APOLLO");
    assertThat(byProject.getOutcome()).isEqualTo(Outcome.DENY);
    Governance g =
        policy.getGovernance(
            GetGovernanceRequest.newBuilder()
                .setCtx(ctx("acme"))
                .setTravelerId("emp_1001")
                .setScope(scope("dep_1", "cc_9", ""))
                .build());
    assertThat(g.getPolicyId()).isEqualTo("CHAIN_POLICY");
    assertThat(g.getPolicyScopeKind()).isEqualTo("COST_CENTER");
    // an unassigned scope falls back to the default
    assertThat(
            http.delete()
                .uri("/api/v1/policies/scopes/PROJECT/prj_apollo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.carol())
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(204);
    assertThat(
            evaluate(scope("", "", "prj_apollo"), air("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB4", 70000))
                .getPolicyId())
        .isEqualTo("US_STANDARD_TRAVEL");
  }

  @Test
  @Order(2)
  void theApprovalChainComesFromTheDocumentWithItsExpiry() {
    PolicyDecision big =
        evaluate(scope("", "cc_9", ""), air("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB5", 250000));
    assertThat(big.getOutcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
    assertThat(big.getApprovers(0).getRole())
        .as("older readers still see the first step")
        .isEqualTo("MANAGER");
    assertThat(big.getApprovalChainList())
        .extracting(a -> a.getRole())
        .containsExactly("MANAGER", "FINANCE");
    assertThat(big.getApprovalExpiresAfterSeconds()).isEqualTo(86_400);
    PolicyDecision medium =
        evaluate(scope("", "cc_9", ""), air("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB6", 150000));
    assertThat(medium.getApprovalChainList())
        .extracting(a -> a.getRole())
        .as("Finance only above its threshold")
        .containsExactly("MANAGER");
    PolicyDecision small =
        evaluate(scope("", "cc_9", ""), air("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB7", 47500));
    assertThat(small.getOutcome()).isEqualTo(Outcome.ALLOW);
    assertThat(small.getApprovalChainList()).isEmpty();
  }

  @Test
  @Order(3)
  void budgetsReserveUnderALockSettleFromTripEventsAndDenyWhatDoesNotFit() throws Exception {
    Instant now = Instant.now();
    String body =
        "{\"name\":\"Q4 sales travel\",\"scopeKind\":\"COST_CENTER\",\"scopeRef\":\"cc_1\",\"periodStart\":\""
            + now.minus(Duration.ofDays(1))
            + "\",\"periodEnd\":\""
            + now.plus(Duration.ofDays(30))
            + "\",\"currency\":\"USD\",\"amountMinor\":100000,\"hard\":true}";
    assertThat(post("/api/v1/budgets", TestTokens.alice(), body).getStatusCode().value())
        .as("a traveler does not create budgets")
        .isEqualTo(403);
    ResponseEntity<String> created = post("/api/v1/budgets", TestTokens.carol(), body);
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
    budgetId = json.readTree(created.getBody()).get("budgetId").asString();
    assertThat(budgetId).startsWith("bud_");

    // the verdict knows the budget: a trip that does not fit a hard budget is denied
    PolicyDecision tooBig =
        evaluate(scope("", "cc_1", ""), air("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB8", 120000));
    assertThat(tooBig.getOutcome()).isEqualTo(Outcome.DENY);
    assertThat(tooBig.getReasonsList()).extracting(r -> r.getCode()).contains("BUDGET_EXCEEDED");
    assertThat(tooBig.getBudgetId()).isEqualTo(budgetId);
    assertThat(tooBig.getBudgetRemaining().getAmountMinor()).isEqualTo(100000);
    assertThat(
            evaluate(scope("", "cc_1", ""), air("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB9", 60000))
                .getOutcome())
        .isEqualTo(Outcome.ALLOW);

    // two trips race for the same budget: exactly one gets it, the other is told why
    String tripB = "trip_01ARZ3NDEKTSV4RRFFQ69G5FBB";
    String tripC = "trip_01ARZ3NDEKTSV4RRFFQ69G5FBC";
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<BudgetReservation>> futures =
        List.of(
            pool.submit(
                () -> {
                  go.await();
                  return reserve(tripB, 60000);
                }),
            pool.submit(
                () -> {
                  go.await();
                  return reserve(tripC, 60000);
                }));
    go.countDown();
    BudgetReservation b = futures.get(0).get();
    BudgetReservation c = futures.get(1).get();
    pool.shutdown();
    assertThat(List.of(b.getStatus(), c.getStatus()))
        .containsExactlyInAnyOrder(BudgetStatus.RESERVED, BudgetStatus.EXCEEDED);
    BudgetReservation won = b.getStatus() == BudgetStatus.RESERVED ? b : c;
    BudgetReservation lost = won == b ? c : b;
    String wonTrip = won == b ? tripB : tripC;
    String lostTrip = won == b ? tripC : tripB;
    assertThat(won.getRemaining().getAmountMinor()).isEqualTo(40000);
    assertThat(lost.getHard()).isTrue();
    assertThat(lost.getMessage()).contains("does not fit");
    // asking again is the same reservation, not a second one
    BudgetReservation again = reserve(wonTrip, 60000);
    assertThat(again.getStatus()).isEqualTo(BudgetStatus.RESERVED);
    assertThat(again.getReservationId()).isEqualTo(won.getReservationId());
    JsonNode view = json.readTree(get("/api/v1/budgets/" + budgetId, TestTokens.bob()).getBody());
    assertThat(view.get("reserved").get("amountMinor").asLong()).isEqualTo(60000);
    assertThat(view.get("remaining").get("amountMinor").asLong()).isEqualTo(40000);

    // the trip is cancelled: the platform's own event gives the money back
    send(
        event(
            "travel.trip.cancelled",
            wonTrip,
            Map.of("tripId", wonTrip, "reason", "meeting moved", "cancelledBy", "human/alice")));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        json.readTree(
                                get("/api/v1/budgets/" + budgetId, TestTokens.carol()).getBody())
                            .get("reserved")
                            .get("amountMinor")
                            .asLong())
                    .isZero());
    assertThat(reserve(lostTrip, 60000).getStatus()).isEqualTo(BudgetStatus.RESERVED);
    // the other trip is booked: committed at what it cost
    send(
        event(
            "travel.trip.booked",
            lostTrip,
            Map.of(
                "tripId",
                lostTrip,
                "orderId",
                "ord_01ARZ3NDEKTSV4RRFFQ69G5FBD",
                "approvalId",
                "apr_01ARZ3NDEKTSV4RRFFQ69G5FBE",
                "total",
                Map.of("currency", "USD", "amountMinor", 55000))));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        json.readTree(
                                get("/api/v1/budgets/" + budgetId, TestTokens.carol()).getBody())
                            .get("committed")
                            .get("amountMinor")
                            .asLong())
                    .isEqualTo(55000));
    view = json.readTree(get("/api/v1/budgets/" + budgetId, TestTokens.carol()).getBody());
    assertThat(view.get("reserved").get("amountMinor").asLong()).isZero();
    assertThat(view.get("remaining").get("amountMinor").asLong()).isEqualTo(45000);
    JsonNode reservations =
        json.readTree(
            get("/api/v1/budgets/" + budgetId + "/reservations", TestTokens.carol()).getBody());
    assertThat(reservations).hasSize(2);
    assertThat(reservations.valueStream().map(r -> r.get("status").asString()).toList())
        .containsExactlyInAnyOrder("RELEASED", "COMMITTED");
    // no budget for a scope nobody funded
    assertThat(
            policy
                .reserveBudget(
                    ReserveBudgetRequest.newBuilder()
                        .setCtx(ctx("acme"))
                        .setTripId("trip_01ARZ3NDEKTSV4RRFFQ69G5FBF")
                        .setScope(scope("dep_none", "", ""))
                        .setAmount(usd(1))
                        .build())
                .getStatus())
        .isEqualTo(BudgetStatus.NO_BUDGET);
    assertThat(get("/api/v1/budgets", TestTokens.dan()).getStatusCode().value()).isEqualTo(403);
    assertThat(
            get(
                    "/api/v1/budgets/" + budgetId,
                    TestTokens.user("zadmin", "globex", "emp_2002", List.of("TRAVEL_ADMIN")))
                .getStatusCode()
                .value())
        .as("another tenant's admin sees nothing")
        .isEqualTo(404);
  }

  @Test
  @Order(4)
  void supplierAgreementsSteerTheSearchAndThePolicyDecidesWhatANonPreferredChoiceMeans() {
    assertThat(
            post(
                    "/api/v1/policies/agreements",
                    TestTokens.alice(),
                    "{\"provider\":\"sandbox-hotel\",\"kind\":\"HOTEL\"}")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    ResponseEntity<String> hotel =
        post(
            "/api/v1/policies/agreements",
            TestTokens.carol(),
            "{\"provider\":\"sandbox-hotel\",\"kind\":\"HOTEL\",\"preferred\":true,\"contractRef\":\"HB-2026-ACME\"}");
    assertThat(hotel.getStatusCode().value()).as(hotel.getBody()).isEqualTo(201);
    assertThat(
            post(
                    "/api/v1/policies/agreements",
                    TestTokens.carol(),
                    "{\"provider\":\"sandbox-air\",\"kind\":\"AIR\",\"carrier\":\"DL\",\"negotiated\":true}")
                .getStatusCode()
                .value())
        .as("a negotiated agreement names its rate code")
        .isEqualTo(422);
    assertThat(
            post(
                    "/api/v1/policies/agreements",
                    TestTokens.carol(),
                    "{\"provider\":\"sandbox-air\",\"kind\":\"AIR\",\"carrier\":\"DL\",\"rateCode\":\"FLX53\",\"negotiated\":true}")
                .getStatusCode()
                .value())
        .isEqualTo(201);
    Governance g =
        policy.getGovernance(
            GetGovernanceRequest.newBuilder()
                .setCtx(ctx("acme"))
                .setTravelerId("emp_1001")
                .build());
    assertThat(g.getAgreementsList()).hasSize(2);
    assertThat(g.getAgreementsList())
        .filteredOn(a -> a.getKind().equals("AIR"))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.getCarrier()).isEqualTo("DL");
              assertThat(a.getRateCode()).isEqualTo("FLX53");
              assertThat(a.getNegotiated()).isTrue();
            });
    // CHAIN_POLICY (cost center cc_9) says a non-preferred hotel needs a travel admin
    PolicyDecision other =
        evaluate(
            scope("", "cc_9", ""),
            airAndHotel("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC1", 47500, "other-hotel"));
    assertThat(other.getOutcome()).isEqualTo(Outcome.ALLOW_WITH_APPROVAL);
    assertThat(other.getReasonsList())
        .extracting(r -> r.getCode())
        .contains("SUPPLIER_NOT_PREFERRED");
    assertThat(other.getApprovalChainList()).extracting(a -> a.getRole()).contains("TRAVEL_ADMIN");
    PolicyDecision preferred =
        evaluate(
            scope("", "cc_9", ""),
            airAndHotel("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC2", 47500, "sandbox-hotel"));
    assertThat(preferred.getReasonsList())
        .extracting(r -> r.getCode())
        .doesNotContain("SUPPLIER_NOT_PREFERRED");
    // the tenant default says nothing about suppliers: the same choice is simply allowed
    assertThat(
            evaluate(
                    scope("", "", ""),
                    airAndHotel("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC3", 47500, "other-hotel"))
                .getReasonsList())
        .extracting(r -> r.getCode())
        .doesNotContain("SUPPLIER_NOT_PREFERRED");
    // an ended agreement no longer steers anything
    String hotelId = json.readTree(hotel.getBody()).get("agreementId").asString();
    assertThat(
            http.delete()
                .uri("/api/v1/policies/agreements/" + hotelId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.carol())
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(204);
    assertThat(
            evaluate(
                    scope("", "cc_9", ""),
                    airAndHotel("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC4", 47500, "other-hotel"))
                .getReasonsList())
        .extracting(r -> r.getCode())
        .doesNotContain("SUPPLIER_NOT_PREFERRED");
    assertThat(json.readTree(get("/api/v1/policies/agreements", TestTokens.bob()).getBody()))
        .hasSize(2);
  }

  // ------------------------------------------------------------------ helpers

  private PolicyDecision evaluate(EvaluationScope scope, Bundle bundle) {
    return policy
        .evaluateTrip(
            EvaluateTripRequest.newBuilder()
                .setCtx(ctx("acme"))
                .setTripId(TRIP)
                .setTravelerId("emp_1001")
                .setIntent(TravelIntent.newBuilder().setOrigin("BOS").setDestination("SEA"))
                .setScope(scope)
                .addCandidates(bundle)
                .build())
        .getCandidates(0)
        .getDecision();
  }

  private BudgetReservation reserve(String tripId, long cents) {
    return policy.reserveBudget(
        ReserveBudgetRequest.newBuilder()
            .setCtx(ctx("acme"))
            .setTripId(tripId)
            .setTravelerId("emp_1001")
            .setScope(scope("", "cc_1", ""))
            .setAmount(usd(cents))
            .build());
  }

  private static EvaluationScope scope(String department, String costCenter, String project) {
    return EvaluationScope.newBuilder()
        .setDepartmentId(department)
        .setCostCenterId(costCenter)
        .setProjectId(project)
        .build();
  }

  private EventEnvelope event(String type, String tripId, Map<String, Object> data) {
    return EventEnvelope.create(
        type, 1, TenantId.of("acme"), tripId, null, "travel-core", data, Clock.systemUTC());
  }

  private void send(EventEnvelope e) {
    String payload = codec.toJson(e);
    assertThat(EventSchemas.violations(payload)).as(payload).isEmpty();
    kafka.send(Topics.topicFor(e.eventType()), e.correlationId(), payload).join();
  }

  private static RequestContext ctx(String tenant) {
    return RequestContext.newBuilder()
        .setTenantId(tenant)
        .setCorrelationId(TRIP)
        .setPrincipal(
            Principal.newBuilder().setKind(Principal.Kind.AGENT).setId("agent/trip-planner/v1"))
        .build();
  }

  private static Money usd(long cents) {
    return Money.newBuilder().setCurrency("USD").setAmountMinor(cents).build();
  }

  private static Bundle air(String id, long fareCents) {
    Offer air =
        Offer.newBuilder()
            .setOfferId("off_" + id)
            .setProvider("sandbox-air")
            .setType(OfferType.AIR)
            .setTotal(usd(fareCents))
            .setAir(
                AirOffer.newBuilder()
                    .setOutbound(
                        Journey.newBuilder()
                            .addSegments(
                                FlightSegment.newBuilder()
                                    .setOrigin("BOS")
                                    .setDestination("SEA")
                                    .setCabin(Cabin.ECONOMY)
                                    .setCarrier("DL"))))
            .build();
    return Bundle.newBuilder().setBundleId(id).addOffers(air).setTotal(usd(fareCents)).build();
  }

  private static Bundle airAndHotel(String id, long fareCents, String hotelProvider) {
    Bundle a = air(id, fareCents);
    Offer hotel =
        Offer.newBuilder()
            .setOfferId("off_h_" + id)
            .setProvider(hotelProvider)
            .setType(OfferType.HOTEL)
            .setTotal(usd(40000))
            .setHotel(
                HotelOffer.newBuilder()
                    .setPropertyId("HTL-1")
                    .setName("Harbor Suites")
                    .setCity("SEA")
                    .setNights(2)
                    .setNightlyRate(usd(20000)))
            .build();
    return a.toBuilder().addOffers(hotel).setTotal(usd(fareCents + 40000)).build();
  }

  private ResponseEntity<String> publish(String token, String document) {
    return http.post()
        .uri("/api/v1/policies")
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body("{\"document\":" + document + "}")
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

  private ResponseEntity<String> post(String path, String token, String body) {
    return http.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> put(String path, String token, String body) {
    return http.put()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }
}
