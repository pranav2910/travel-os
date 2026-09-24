package io.travelos.order.finance;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.travelos.contracts.common.v1.Money;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.offer.v1.AirOffer;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.offer.v1.Journey;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.order.v1.CancelOrderCommand;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderServiceGrpc;
import io.travelos.contracts.order.v1.OrderStatus;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.order.saga.FakeSupplierGateway;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.spring.web.testing.TestTokens;
import java.io.IOException;
import java.time.Instant;
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
 * Phase 5 (ADR-0017): the finance ledger around the order saga, with the SIMULATED payment provider
 * and the scripted supplier gateway. Money is recorded before it moves, never twice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestTokens.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  static final FakeSupplierGateway SUPPLIER = new FakeSupplierGateway();
  static final String TRIP = "trip_01ARZ3NDEKTSV4RRFFQ69G5FB0";
  static final java.util.concurrent.atomic.AtomicInteger ATTEMPT =
      new java.util.concurrent.atomic.AtomicInteger();

  @DynamicPropertySource
  static void supplierAddress(DynamicPropertyRegistry registry) throws IOException {
    int port = SUPPLIER.start();
    registry.add("travelos.grpc.clients.supplier-gateway.address", () -> "localhost:" + port);
  }

  @Autowired Environment environment;
  @Autowired GrpcServerLifecycle grpc;
  @Autowired JdbcClient jdbc;
  @Autowired FinanceService finance;
  private final JsonMapper json = JsonMapper.builder().build();
  private ManagedChannel channel;
  private OrderServiceGrpc.OrderServiceBlockingStub orders;
  private RestClient http;

  @BeforeAll
  void setUp() {
    channel = ManagedChannelBuilder.forAddress("localhost", grpc.port()).usePlaintext().build();
    orders = OrderServiceGrpc.newBlockingStub(channel);
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (rq, rs) -> {})
            .build();
    // the tenant's corporate card, registered once (idempotent by token)
    post(
        "/api/v1/finance/instruments",
        TestTokens.carol(),
        "{\"kind\":\"CORPORATE_CARD\",\"provider\":\"sandbox-payments\",\"token\":\"tok_sbx_visa_1\",\"label\":\"Corporate Visa\",\"last4\":\"4242\",\"currency\":\"USD\"}");
  }

  @AfterAll
  void tearDown() {
    channel.shutdownNow();
    SUPPLIER.stop();
  }

  @Test
  void aCardNumberIsNeverAcceptedAsAnInstrument() {
    ResponseEntity<String> pan =
        post(
            "/api/v1/finance/instruments",
            TestTokens.carol(),
            "{\"kind\":\"CORPORATE_CARD\",\"provider\":\"sandbox-payments\",\"token\":\"4242 4242 4242 4242\",\"currency\":\"USD\"}");
    assertThat(pan.getStatusCode().value()).isEqualTo(422);
    assertThat(pan.getBody()).contains("PAN_NOT_ACCEPTED");
    assertThat(
            jdbc.sql("SELECT count(*) FROM payment_instrument WHERE token LIKE '4242%'")
                .query(Long.class)
                .single())
        .isZero();
    ResponseEntity<String> ok =
        post(
            "/api/v1/finance/instruments",
            TestTokens.carol(),
            "{\"kind\":\"VIRTUAL_CARD\",\"provider\":\"sandbox-payments\",\"token\":\"tok_sbx_virtual_9\",\"label\":\"Virtual card\",\"last4\":\"9999\",\"currency\":\"USD\"}");
    assertThat(ok.getStatusCode().value()).as(ok.getBody()).isEqualTo(201);
    assertThat(json.readTree(ok.getBody()).get("instrumentId").asString()).startsWith("pmi_");
    assertThat(
            post(
                    "/api/v1/finance/instruments",
                    TestTokens.alice(),
                    "{\"kind\":\"CORPORATE_CARD\",\"provider\":\"sandbox-payments\",\"token\":\"tok_sbx_visa_2\",\"currency\":\"USD\"}")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(get("/api/v1/finance/instruments", TestTokens.carol()).getBody())
        .as("labels and last four, never the token itself")
        .contains("Corporate Visa")
        .contains("\"4242\"")
        .doesNotContain("tok_sbx_visa_1")
        .doesNotContain("4242 4242");
  }

  @Test
  void moneyIsAuthorizedBeforeBookingCapturedAfterAndOwedPerSupplier() {
    String key = TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet();
    Order order =
        orders.createOrder(
            command(
                key,
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC1", "ok-DL200", "ok-hotel-SEA"),
                "tok_sbx_visa_1"));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    JsonNode receipt =
        json.readTree(
            get("/api/v1/orders/" + order.getOrderId() + "/receipt", TestTokens.alice()).getBody());
    JsonNode payment = receipt.get("payment");
    assertThat(payment.get("status").asString()).isEqualTo("CAPTURED");
    assertThat(payment.get("provider").asString()).isEqualTo("sandbox-payments");
    assertThat(payment.get("authorizedMinor").asLong())
        .isEqualTo(order.getTotal().getAmountMinor());
    assertThat(payment.get("capturedMinor").asLong()).isEqualTo(order.getTotal().getAmountMinor());
    assertThat(payment.get("providerRef").asString()).startsWith("spx_");
    assertThat(receipt.get("paymentEvents")).hasSize(2);
    assertThat(receipt.get("paymentEvents").get(0).get("kind").asString()).isEqualTo("AUTHORIZE");
    assertThat(receipt.get("paymentEvents").get(1).get("kind").asString()).isEqualTo("CAPTURE");
    assertThat(receipt.get("instrument").get("last4").asString()).isEqualTo("4242");
    assertThat(receipt.get("payables")).hasSize(2);
    assertThat(receipt.get("payables").get(0).get("method").asString())
        .isEqualTo("CARD_AT_SUPPLIER");
    assertThat(receipt.get("payables").get(0).get("status").asString()).isEqualTo("SETTLED");
    // the same command again is the same order and the same money
    Order again =
        orders.createOrder(
            command(
                key,
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC1", "ok-DL200", "ok-hotel-SEA"),
                "tok_sbx_visa_1"));
    assertThat(again.getOrderId()).isEqualTo(order.getOrderId());
    assertThat(
            jdbc.sql("SELECT count(*) FROM payment_event WHERE payment_id = :p")
                .param("p", payment.get("paymentId").asString())
                .query(Long.class)
                .single())
        .isEqualTo(2L);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM outbox WHERE payload->>'eventType' = 'travel.finance.payment-captured' AND payload->'data'->>'orderId' = :o")
                .param("o", order.getOrderId())
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    // nobody else reads the receipt
    assertThat(
            get("/api/v1/orders/" + order.getOrderId() + "/receipt", TestTokens.dan())
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  @Test
  void aDeclineFailsTheOrderBeforeAnySupplierIsCalled() {
    int created = SUPPLIER.createLog.size();
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC2", "ok-DL210"),
                "tok_sbx_declined_card"));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    assertThat(order.getFailureCode()).isEqualTo("PAYMENT_DECLINED");
    assertThat(order.getCompensated()).isTrue();
    assertThat(SUPPLIER.createLog.size()).as("no supplier heard of it").isEqualTo(created);
    JsonNode receipt =
        json.readTree(
            get("/api/v1/orders/" + order.getOrderId() + "/receipt", TestTokens.alice()).getBody());
    assertThat(receipt.get("payment").get("status").asString()).isEqualTo("DECLINED");
    assertThat(receipt.get("payment").get("failureCode").asString()).isEqualTo("CARD_DECLINED");
    assertThat(receipt.get("payables")).isEmpty();
  }

  @Test
  void aFailedBookingVoidsTheAuthorization() {
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC3", "ok-DL220", "soldout-DL221"),
                "tok_sbx_visa_1"));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    assertThat(order.getCompensated()).isTrue();
    JsonNode receipt =
        json.readTree(
            get("/api/v1/orders/" + order.getOrderId() + "/receipt", TestTokens.alice()).getBody());
    assertThat(receipt.get("payment").get("status").asString()).isEqualTo("VOIDED");
    assertThat(receipt.get("payment").get("capturedMinor").asLong()).isZero();
    assertThat(receipt.get("paymentEvents").get(1).get("kind").asString()).isEqualTo("VOID");
  }

  @Test
  void cancellationRefundsPerItemAndKeepsACreditAsValueNotMoney() {
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC4", "ok-DL230", "ok-hotel-SEA"),
                "tok_sbx_visa_1"));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    Order cancelled =
        orders.cancelOrder(
            CancelOrderCommand.newBuilder()
                .setCtx(ctx(TRIP + ":CANCEL:" + ATTEMPT.incrementAndGet()))
                .setOrderId(order.getOrderId())
                .setReason("plans changed")
                .build());
    assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    JsonNode receipt =
        json.readTree(
            get("/api/v1/orders/" + order.getOrderId() + "/receipt", TestTokens.alice()).getBody());
    // the fake gateway refunds USD 400.00 per item: two refunds, never more than captured
    assertThat(receipt.get("payment").get("refundedMinor").asLong()).isEqualTo(80000);
    assertThat(receipt.get("payment").get("status").asString())
        .isIn("PARTIALLY_REFUNDED", "REFUNDED");
    long refunds = 0;
    for (JsonNode e : receipt.get("paymentEvents")) {
      if ("REFUND".equals(e.get("kind").asString())) {
        refunds++;
      }
    }
    assertThat(refunds).isEqualTo(2);
    // cancelling again changes nothing in the ledger
    orders.cancelOrder(
        CancelOrderCommand.newBuilder()
            .setCtx(ctx(TRIP + ":CANCEL:" + ATTEMPT.incrementAndGet()))
            .setOrderId(order.getOrderId())
            .setReason("again")
            .build());
    JsonNode after =
        json.readTree(
            get("/api/v1/orders/" + order.getOrderId() + "/receipt", TestTokens.alice()).getBody());
    assertThat(after.get("payment").get("refundedMinor").asLong()).isEqualTo(80000);
  }

  @Test
  void creditsAreIssuedFromTheSupplierAndAppliedOnlyByFinance() {
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC5", "credit-DL240"),
                "tok_sbx_visa_1"));
    Order cancelled =
        orders.cancelOrder(
            CancelOrderCommand.newBuilder()
                .setCtx(ctx(TRIP + ":CANCEL:" + ATTEMPT.incrementAndGet()))
                .setOrderId(order.getOrderId())
                .setReason("credit test")
                .build());
    assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    JsonNode receipt =
        json.readTree(
            get("/api/v1/orders/" + order.getOrderId() + "/receipt", TestTokens.alice()).getBody());
    assertThat(receipt.get("payment").get("refundedMinor").asLong())
        .as("a credit is not a refund")
        .isZero();
    assertThat(receipt.get("credits")).hasSize(1);
    JsonNode credit = receipt.get("credits").get(0);
    assertThat(credit.get("status").asString()).isEqualTo("AVAILABLE");
    assertThat(credit.get("amount").get("amountMinor").asLong())
        .isEqualTo(FakeSupplierGateway.cents("credit-DL240") - 7500);
    String creditId = credit.get("creditId").asString();
    // the traveler sees their own credits; Finance applies them
    assertThat(get("/api/v1/finance/credits", TestTokens.alice()).getBody()).contains(creditId);
    assertThat(get("/api/v1/finance/credits", TestTokens.dan()).getBody()).doesNotContain(creditId);
    assertThat(
            post(
                    "/api/v1/finance/credits/" + creditId + "/application",
                    TestTokens.alice(),
                    "{\"orderId\":\"ord_01ARZ3NDEKTSV4RRFFQ69G5FC9\"}")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    ResponseEntity<String> applied =
        post(
            "/api/v1/finance/credits/" + creditId + "/application",
            TestTokens.carol(),
            "{\"orderId\":\"ord_01ARZ3NDEKTSV4RRFFQ69G5FC9\",\"note\":\"applied by the airline at ticketing\"}");
    assertThat(applied.getStatusCode().value()).as(applied.getBody()).isEqualTo(200);
    assertThat(json.readTree(applied.getBody()).get("status").asString()).isEqualTo("APPLIED");
    assertThat(
            post(
                    "/api/v1/finance/credits/" + creditId + "/application",
                    TestTokens.carol(),
                    "{\"orderId\":\"ord_01ARZ3NDEKTSV4RRFFQ69G5FC8\"}")
                .getStatusCode()
                .value())
        .as("a credit is applied once")
        .isEqualTo(409);
    // expiry: a credit past its date is swept
    jdbc.sql(
            "UPDATE travel_credit SET status = 'AVAILABLE', expires_at = :past WHERE credit_id = :id")
        .param("past", java.sql.Timestamp.from(Instant.now().minusSeconds(60)))
        .param("id", creditId)
        .update();
    assertThat(finance.expireCreditsNow(Instant.now())).isEqualTo(1);
    assertThat(get("/api/v1/finance/credits?status=EXPIRED", TestTokens.alice()).getBody())
        .contains(creditId);
  }

  @Test
  void payablesFollowTheSettlementMethodAndFinanceSettlesThem() {
    // hotelbeds is configured INVOICE; the fake gateway answers for any provider name it is given
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC6", "ok-DL250").toBuilder()
                    .setOffers(
                        0,
                        offer("ok-DL250").toBuilder()
                            .setProvider("hotelbeds")
                            .setType(OfferType.HOTEL)
                            .clearAir()
                            .setHotel(
                                io.travelos.contracts.offer.v1.HotelOffer.newBuilder()
                                    .setName("Harbor")
                                    .setNights(1)
                                    .setCity("SEA")
                                    .setTimeZone("America/Los_Angeles")
                                    .setCheckInDate("2026-10-06")
                                    .setCheckOutDate("2026-10-07"))
                            .build())
                    .build(),
                "tok_sbx_visa_1"));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    JsonNode due =
        json.readTree(
            get("/api/v1/finance/payables?provider=hotelbeds&status=DUE", TestTokens.carol())
                .getBody());
    assertThat(due).isNotEmpty();
    JsonNode payable = null;
    for (JsonNode p : due) {
      if (p.get("orderId").asString().equals(order.getOrderId())) {
        payable = p;
      }
    }
    assertThat(payable).isNotNull();
    assertThat(payable.get("method").asString()).isEqualTo("INVOICE");
    JsonNode balances =
        json.readTree(get("/api/v1/finance/balances", TestTokens.carol()).getBody());
    assertThat(balances.get("hotelbeds").get("USD").asLong())
        .isGreaterThanOrEqualTo(payable.get("amount").get("amountMinor").asLong());
    String id = payable.get("payableId").asString();
    assertThat(
            post(
                    "/api/v1/finance/payables/" + id + "/settlement",
                    TestTokens.bob(),
                    "{\"status\":\"PAID\"}")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    ResponseEntity<String> invoiced =
        post(
            "/api/v1/finance/payables/" + id + "/settlement",
            TestTokens.carol(),
            "{\"status\":\"INVOICED\",\"invoiceReference\":\"HB-INV-1\"}");
    assertThat(invoiced.getStatusCode().value()).as(invoiced.getBody()).isEqualTo(200);
    ResponseEntity<String> paid =
        post(
            "/api/v1/finance/payables/" + id + "/settlement",
            TestTokens.carol(),
            "{\"status\":\"PAID\"}");
    assertThat(json.readTree(paid.getBody()).get("status").asString()).isEqualTo("PAID");
    assertThat(json.readTree(paid.getBody()).get("invoiceReference").asString())
        .isEqualTo("HB-INV-1");
    assertThat(
            post(
                    "/api/v1/finance/payables/" + id + "/settlement",
                    TestTokens.carol(),
                    "{\"status\":\"PAID\"}")
                .getStatusCode()
                .value())
        .as("idempotent")
        .isEqualTo(200);
  }

  @Test
  void reconciliationMatchesTheLedgerAgainstTheProvidersRecords() {
    Instant from = Instant.now().minusSeconds(3600);
    orders.createOrder(
        command(
            TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
            bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC7", "ok-DL260"),
            "tok_sbx_visa_1"));
    ResponseEntity<String> r =
        get(
            "/api/v1/finance/reconciliation?from=" + from + "&to=" + Instant.now().plusSeconds(60),
            TestTokens.carol());
    assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(200);
    JsonNode view = json.readTree(r.getBody());
    assertThat(view.get("matched").asInt()).isGreaterThanOrEqualTo(2);
    assertThat(view.get("missingLocally").asInt()).isZero();
    assertThat(view.get("missingAtProvider").asInt()).isZero();
    assertThat(view.get("mismatched").asInt()).isZero();
    // a provider-side movement the ledger never saw shows up as MISSING_LOCALLY
    jdbc.sql(
            "INSERT INTO sandbox_payment_transaction (provider_ref, tenant_id, kind, currency, amount_minor, settlement_currency, settlement_minor, idempotency_key, at) VALUES ('spx_ghost', 'acme', 'CAPTURE', 'USD', 12345, 'USD', 12345, 'ghost', now())")
        .update();
    view =
        json.readTree(
            get(
                    "/api/v1/finance/reconciliation?from="
                        + from
                        + "&to="
                        + Instant.now().plusSeconds(60),
                    TestTokens.carol())
                .getBody());
    assertThat(view.get("missingLocally").asInt()).isEqualTo(1);
    assertThat(
            get(
                    "/api/v1/finance/reconciliation?from=" + from + "&to=" + Instant.now(),
                    TestTokens.alice())
                .getStatusCode()
                .value())
        .isEqualTo(403);
  }

  @Test
  void aForeignCurrencyInstrumentRecordsTheProvidersConversionAsProvenance() {
    post(
        "/api/v1/finance/instruments",
        TestTokens.carol(),
        "{\"kind\":\"CORPORATE_CARD\",\"provider\":\"sandbox-payments\",\"token\":\"tok_sbx_eur_card\",\"currency\":\"EUR\"}");
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC8", "ok-DL270"),
                "tok_sbx_eur_card"));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    JsonNode payment =
        json.readTree(
                get("/api/v1/orders/" + order.getOrderId() + "/receipt", TestTokens.alice())
                    .getBody())
            .get("payment");
    assertThat(payment.get("currency").asString()).as("the order's currency").isEqualTo("USD");
    JsonNode fx = payment.get("fx");
    assertThat(fx.get("settlementCurrency").asString()).isEqualTo("EUR");
    assertThat(fx.get("settlementMinor").asLong())
        .isEqualTo(Math.round(order.getTotal().getAmountMinor() * 0.92));
    assertThat(fx.get("source").asString()).contains("static table");
  }

  // ------------------------------------------------------------------ helpers

  private static CreateOrderCommand command(String key, Bundle bundle, String token) {
    return CreateOrderCommand.newBuilder()
        .setCtx(ctx(key))
        .setTripId(TRIP)
        .setTravelerId("emp_1001")
        .setBundle(bundle)
        .setPolicyDecisionId("pd_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setOptimizationRunId("opt_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .addPassengers(
            Passenger.newBuilder()
                .setGivenName("Alice")
                .setFamilyName("Nguyen")
                .setEmail("alice@acme.example"))
        .setPaymentToken(token)
        .build();
  }

  private static RequestContext ctx(String key) {
    return RequestContext.newBuilder()
        .setTenantId("acme")
        .setCorrelationId(TRIP)
        .setCausationId("cmd_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setIdempotencyKey(key)
        .setPrincipal(
            Principal.newBuilder().setKind(Principal.Kind.AGENT).setId("agent/trip-planner/v1"))
        .build();
  }

  private static Offer offer(String id) {
    long cents = FakeSupplierGateway.cents(id);
    return Offer.newBuilder()
        .setOfferId("off_" + id)
        .setProvider(id.contains("hotel") ? "sandbox-hotel" : "sandbox-air")
        .setProviderOfferId(id)
        .setType(id.contains("hotel") ? OfferType.HOTEL : OfferType.AIR)
        .setTotal(Money.newBuilder().setCurrency("USD").setAmountMinor(cents))
        .setAir(
            AirOffer.newBuilder()
                .setOutbound(
                    Journey.newBuilder()
                        .addSegments(
                            FlightSegment.newBuilder()
                                .setCarrier("DL")
                                .setOrigin("BOS")
                                .setDestination("SEA"))))
        .build();
  }

  private static Bundle bundle(String bundleId, String... providerOfferIds) {
    Bundle.Builder b = Bundle.newBuilder().setBundleId(bundleId);
    long total = 0;
    for (String id : providerOfferIds) {
      total += FakeSupplierGateway.cents(id);
      b.addOffers(offer(id));
    }
    return b.setTotal(Money.newBuilder().setCurrency("USD").setAmountMinor(total)).build();
  }

  private ResponseEntity<String> post(String path, String token, String body) {
    return http.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", UUID.randomUUID().toString())
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

  static List<String> none() {
    return List.of();
  }
}
