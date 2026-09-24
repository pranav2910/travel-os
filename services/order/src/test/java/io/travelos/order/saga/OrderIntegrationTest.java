package io.travelos.order.saga;

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
import io.travelos.contracts.offer.v1.AirOffer;
import io.travelos.contracts.offer.v1.Bundle;
import io.travelos.contracts.offer.v1.FlightSegment;
import io.travelos.contracts.offer.v1.Journey;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.offer.v1.OfferType;
import io.travelos.contracts.order.v1.CancelOrderCommand;
import io.travelos.contracts.order.v1.ChangeOrderCommand;
import io.travelos.contracts.order.v1.CreateOrderCommand;
import io.travelos.contracts.order.v1.FindOrderByExternalRefRequest;
import io.travelos.contracts.order.v1.GetOrderRequest;
import io.travelos.contracts.order.v1.Order;
import io.travelos.contracts.order.v1.OrderItemStatus;
import io.travelos.contracts.order.v1.OrderServiceGrpc;
import io.travelos.contracts.order.v1.OrderStatus;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.events.Topics;
import io.travelos.events.testing.EventSchemas;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.spring.web.testing.TestTokens;
import java.io.IOException;
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

/** Real gRPC in, real gRPC out (to a scripted gateway on a real port), real Postgres and Kafka. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestTokens.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  static final FakeSupplierGateway SUPPLIER = new FakeSupplierGateway();
  static final String TRIP = "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV";
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
  @Autowired KafkaConnectionDetails kafkaConnection;

  private final JsonMapper json = JsonMapper.builder().build();
  private ManagedChannel channel;
  private OrderServiceGrpc.OrderServiceBlockingStub orders;
  private RestClient http;
  private KafkaConsumer<String, String> consumer;
  private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

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
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnection.getBootstrapServers()));
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "order-test-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of(Topics.ORDER));
  }

  @AfterAll
  void tearDown() {
    consumer.close();
    channel.shutdownNow();
    SUPPLIER.stop();
  }

  @Test
  @org.junit.jupiter.api.Order(1)
  void happyPathConfirmsTheOrderWithEvidenceAndIsIdempotent() {
    String key = TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet();
    CreateOrderCommand command = command(key, bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA1", "ok-DL291"));

    Order first = orders.createOrder(command);
    Order second = orders.createOrder(command);
    Order third = orders.createOrder(command);

    assertThat(first.getOrderId()).startsWith("ord_");
    assertThat(first.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(first.getExternalOrderId()).startsWith("EXT-");
    assertThat(first.getItemsList())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.getStatus()).isEqualTo(OrderItemStatus.ITEM_CONFIRMED);
              assertThat(item.getRecordLocator()).startsWith("LOC");
              assertThat(item.getOffer().getProviderOfferId()).isEqualTo("ok-DL291");
            });
    assertThat(first.getPolicyDecisionId()).isEqualTo("pd_01ARZ3NDEKTSV4RRFFQ69G5FAV");
    assertThat(first.getApprovalId()).isEqualTo("apr_01ARZ3NDEKTSV4RRFFQ69G5FAV");
    assertThat(second).isEqualTo(first);
    assertThat(third).isEqualTo(first);
    assertThat(
            jdbc.sql("SELECT count(*) FROM travel_order WHERE idempotency_key = :k")
                .param("k", key)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    assertThat(SUPPLIER.createAttempts)
        .containsKey(first.getOrderId() + ":" + first.getItems(0).getItemId());
    assertThat(
            SUPPLIER
                .createAttempts
                .get(first.getOrderId() + ":" + first.getItems(0).getItemId())
                .get())
        .as("replays never reach the supplier again")
        .isEqualTo(1);

    Order fetched =
        orders.getOrder(
            GetOrderRequest.newBuilder().setCtx(ctx("")).setOrderId(first.getOrderId()).build());
    assertThat(fetched).isEqualTo(first);
  }

  @Test
  @org.junit.jupiter.api.Order(2)
  void transientSupplierFailuresAreRetriedWithinBounds() {
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA2", "flaky-UA100")));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(
            SUPPLIER
                .createAttempts
                .get(order.getOrderId() + ":" + order.getItems(0).getItemId())
                .get())
        .isEqualTo(3);
  }

  @Test
  @org.junit.jupiter.api.Order(3)
  void aFailedComponentCompensatesTheConfirmedOnes() {
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA3", "ok-DL100", "soldout-DL200")));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    assertThat(order.getItems(0).getStatus())
        .as("the confirmed first leg was released")
        .isEqualTo(OrderItemStatus.ITEM_CANCELLED);
    assertThat(order.getItems(1).getStatus()).isEqualTo(OrderItemStatus.ITEM_FAILED);
    assertThat(SUPPLIER.cancelled).contains(order.getItems(0).getExternalRef());
    JsonNode view =
        json.readTree(get("/api/v1/orders/" + order.getOrderId(), TestTokens.alice()).getBody());
    assertThat(view.get("failureCode").asString()).isEqualTo("SEAT_NO_LONGER_AVAILABLE");
    assertThat(view.get("compensated").asBoolean()).isTrue();
    // Replaying the same command returns the failed order; the saga is not restarted.
    assertThat(
            orders
                .createOrder(
                    command(
                        order.getIdempotencyKey(),
                        bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA3", "ok-DL100", "soldout-DL200")))
                .getStatus())
        .isEqualTo(OrderStatus.FAILED);
  }

  @Test
  @org.junit.jupiter.api.Order(4)
  void compensationThatCannotCompleteIsPartiallyFailedAndHonestAboutIt() {
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA4", "stuck-DL300", "soldout-DL400")));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FAILED);
    JsonNode view =
        json.readTree(get("/api/v1/orders/" + order.getOrderId(), TestTokens.bob()).getBody());
    assertThat(view.get("compensated").asBoolean()).isFalse();
    assertThat(view.get("items").get(0).get("status").asString())
        .as("still confirmed at the supplier: a human must release it")
        .isEqualTo("CANCEL_FAILED");
    assertThat(view.get("exposures")).hasSize(1);
    assertThat(view.get("exposures").get(0).get("status").asString()).isEqualTo("OPEN");
    assertThat(view.get("exposures").get(0).get("amount").get("amountMinor").asLong())
        .isEqualTo(view.get("items").get(0).get("total").get("amountMinor").asLong());
    // Finance's inbox: every open exposure of the tenant with its order and trip; travelers and
    // managers are refused (the list would show other people's orders)
    ResponseEntity<String> open = get("/api/v1/orders/exposures?status=OPEN", TestTokens.carol());
    assertThat(open.getStatusCode().value()).isEqualTo(200);
    JsonNode rows = json.readTree(open.getBody());
    assertThat(rows)
        .anySatisfy(
            r -> {
              assertThat(r.get("orderId").asString()).isEqualTo(order.getOrderId());
              assertThat(r.get("tripId").asString()).isEqualTo(TRIP);
              assertThat(r.get("exposure").get("status").asString()).isEqualTo("OPEN");
            });
    assertThat(get("/api/v1/orders/exposures", TestTokens.alice()).getStatusCode().value())
        .isEqualTo(403);
    assertThat(get("/api/v1/orders/exposures", TestTokens.bob()).getStatusCode().value())
        .isEqualTo(403);
    assertThat(
            get("/api/v1/orders/exposures?status=NOPE", TestTokens.carol()).getStatusCode().value())
        .isEqualTo(422);
  }

  @Test
  @org.junit.jupiter.api.Order(5)
  void aHigherRepriceIsNotSilentlyAccepted() {
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA5", "pricier-AA500")));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    JsonNode view =
        json.readTree(get("/api/v1/orders/" + order.getOrderId(), TestTokens.alice()).getBody());
    assertThat(view.get("failureCode").asString()).isEqualTo("PRICE_CHANGED");
    assertThat(view.get("failureMessage").asString()).contains("re-evaluate policy");
  }

  @Test
  @org.junit.jupiter.api.Order(60)
  void aRefusedCancellationKeepsTheOrderPendingWithAnExposureUntilAPersonReleasesIt() {
    // BUG-08: a supplier that refuses to release a component never makes the order CANCELLED
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                itinerary("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC1", "ok-DL170", "norefund-hotel-SEA")));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    CancelOrderCommand cancel =
        CancelOrderCommand.newBuilder()
            .setCtx(ctx(order.getOrderId() + ":CANCEL-ORDER:1"))
            .setOrderId(order.getOrderId())
            .setReason("Meeting moved to video")
            .build();
    Order pending = orders.cancelOrder(cancel);
    assertThat(pending.getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
    assertThat(pending.getFailureCode()).isEqualTo("CANCELLATION_INCOMPLETE");
    assertThat(pending.getItems(0).getStatus())
        .as("the flight was released")
        .isEqualTo(OrderItemStatus.ITEM_CANCELLED);
    assertThat(pending.getItems(1).getStatus())
        .as("the non-refundable hotel is still confirmed at the supplier")
        .isEqualTo(OrderItemStatus.ITEM_CANCEL_FAILED);
    assertThat(pending.getExposuresCount()).isEqualTo(1);
    assertThat(pending.getExposures(0).getReason()).isEqualTo("CANCELLATION_REFUSED");
    assertThat(pending.getExposures(0).getStatus()).isEqualTo("OPEN");
    assertThat(pending.getExposures(0).getAmount()).isEqualTo(pending.getItems(1).getTotal());

    // asked again: the same answer, no second supplier call, no second exposure
    int attempts = SUPPLIER.cancelAttempts.size();
    Order again = orders.cancelOrder(cancel);
    assertThat(again.getStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
    assertThat(again.getExposuresCount()).isEqualTo(1);
    assertThat(SUPPLIER.cancelAttempts).hasSize(attempts);
    assertThat(SUPPLIER.cancelled.stream().filter(pending.getItems(0).getExternalRef()::equals))
        .as("the flight was released exactly once")
        .hasSize(1);

    // a person releases the hotel by phone and closes the exposure: now, and only now, CANCELLED
    String path =
        "/api/v1/orders/"
            + order.getOrderId()
            + "/exposures/"
            + pending.getExposures(0).getExposureId()
            + "/resolution";
    ResponseEntity<String> resolved = resolve(path, TestTokens.carol(), "res-cancel-1");
    assertThat(resolved.getStatusCode().value()).as(resolved.getBody()).isEqualTo(200);
    JsonNode view =
        json.readTree(get("/api/v1/orders/" + order.getOrderId(), TestTokens.bob()).getBody());
    assertThat(view.get("status").asString()).isEqualTo("CANCELLED");
    JsonNode failureCode = view.get("failureCode");
    assertThat(failureCode == null || failureCode.isNull() || failureCode.asString().isEmpty())
        .as("a completed cancellation carries no failure any more")
        .isTrue();
    assertThat(orders.cancelOrder(cancel).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              consumer.poll(Duration.ofMillis(250)).forEach(received::add);
              List<JsonNode> mine =
                  received.stream()
                      .filter(r -> r.value().contains(order.getOrderId()))
                      .map(r -> json.readTree(r.value()))
                      .toList();
              assertThat(mine.stream().map(e -> e.get("eventType").asString()))
                  .contains(
                      "travel.order.compensation-failed",
                      "travel.order.exposure-resolved",
                      "travel.order.cancelled");
              JsonNode incomplete =
                  mine.stream()
                      .filter(
                          e ->
                              e.get("eventType")
                                  .asString()
                                  .equals("travel.order.compensation-failed"))
                      .findFirst()
                      .orElseThrow();
              assertThat(incomplete.get("data").get("reasonCode").asString())
                  .isEqualTo("CANCELLATION_INCOMPLETE");
              JsonNode cancelled =
                  mine.stream()
                      .filter(e -> e.get("eventType").asString().equals("travel.order.cancelled"))
                      .findFirst()
                      .orElseThrow();
              assertThat(cancelled.get("data").get("refund").get("amountMinor").asLong())
                  .as("the refund the airline gave for the released flight")
                  .isEqualTo(40000);
              assertThat(
                      mine.stream()
                          .filter(
                              e -> e.get("eventType").asString().equals("travel.order.cancelled"))
                          .count())
                  .isEqualTo(1);
            });
    for (ConsumerRecord<String, String> record : received) {
      if (record.value().contains(order.getOrderId())) {
        assertThat(EventSchemas.violations(record.value())).as(record.value()).isEmpty();
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Order(61)
  void aLostCancellationAnswerResumesFromTheItemStatesAndReleasesNothingTwice() {
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                itinerary("bdl_01ARZ3NDEKTSV4RRFFQ69G5FC2", "stuck-DL190", "ok-hotel-SEA")));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    String airRef = order.getItems(0).getExternalRef();
    String hotelRef = order.getItems(1).getExternalRef();
    CancelOrderCommand cancel =
        CancelOrderCommand.newBuilder()
            .setCtx(ctx(order.getOrderId() + ":CANCEL-ORDER:1"))
            .setOrderId(order.getOrderId())
            .setReason("Trip cancelled by traveler")
            .build();
    // the hotel (released last-booked-first) is let go; the airline's answer never arrives
    for (int attempt = 1; attempt <= 2; attempt++) {
      assertThatThrownBy(() -> orders.cancelOrder(cancel))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
      JsonNode view =
          json.readTree(get("/api/v1/orders/" + order.getOrderId(), TestTokens.bob()).getBody());
      assertThat(view.get("status").asString())
          .as("pending, not cancelled: the flight is still confirmed at the airline")
          .isEqualTo("CANCELLATION_PENDING");
      assertThat(view.get("items").get(1).get("status").asString()).isEqualTo("CANCELLED");
      assertThat(view.get("items").get(0).get("status").asString()).isEqualTo("CONFIRMED");
      assertThat(view.get("exposures") == null || view.get("exposures").isEmpty()).isTrue();
    }
    assertThat(SUPPLIER.cancelled.stream().filter(hotelRef::equals))
        .as("the hotel was released once, however often the cancel is retried")
        .hasSize(1);
    // the airline comes back: the retry finishes what was left, with the same key
    SUPPLIER.offerByExternalId.put(airRef, "ok-DL190");
    Order cancelled = orders.cancelOrder(cancel);
    assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(cancelled.getItems(0).getStatus()).isEqualTo(OrderItemStatus.ITEM_CANCELLED);
    assertThat(SUPPLIER.cancelled.stream().filter(hotelRef::equals)).hasSize(1);
    assertThat(SUPPLIER.cancelled.stream().filter(airRef::equals)).hasSize(1);
  }

  @Test
  @org.junit.jupiter.api.Order(6)
  void cancellingAConfirmedOrderReleasesItAndIsIdempotent() {
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA6", "ok-B6600")));
    CancelOrderCommand cancel =
        CancelOrderCommand.newBuilder()
            .setCtx(ctx(order.getOrderId() + ":CANCEL-ORDER:1"))
            .setOrderId(order.getOrderId())
            .setReason("Trip cancelled by traveler")
            .build();
    Order cancelled = orders.cancelOrder(cancel);
    assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(cancelled.getItems(0).getStatus()).isEqualTo(OrderItemStatus.ITEM_CANCELLED);
    assertThat(orders.cancelOrder(cancel)).isEqualTo(cancelled);
  }

  @Test
  @org.junit.jupiter.api.Order(7)
  void guardsAndTenancy() {
    assertThatThrownBy(
            () -> orders.createOrder(CreateOrderCommand.newBuilder().setTripId(TRIP).build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    assertThatThrownBy(
            () -> orders.createOrder(command("", bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA7", "ok-x"))))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getDescription()).contains("idempotency_key"));
    String key = TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet();
    Order order =
        orders.createOrder(command(key, bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA8", "ok-y")));
    assertThatThrownBy(
            () ->
                orders.createOrder(command(key, bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FA9", "ok-y"))))
        .as("same key, different bundle")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getDescription()).contains("IDEMPOTENCY_KEY_REUSED"));
    assertThat(
            get("/api/v1/orders/" + order.getOrderId(), TestTokens.alice()).getStatusCode().value())
        .isEqualTo(200);
    assertThat(
            get("/api/v1/orders/" + order.getOrderId(), TestTokens.dan()).getStatusCode().value())
        .isEqualTo(404);
    assertThat(
            get("/api/v1/orders/" + order.getOrderId(), TestTokens.zoe()).getStatusCode().value())
        .isEqualTo(404);
    assertThat(
            get("/api/v1/orders/" + order.getOrderId(), TestTokens.bob()).getStatusCode().value())
        .isEqualTo(200);
    JsonNode mine =
        json.readTree(get("/api/v1/orders?tripId=" + TRIP, TestTokens.alice()).getBody());
    assertThat(mine).isNotEmpty();
    assertThat(mine.findValues("orderId").stream().map(JsonNode::asString))
        .contains(order.getOrderId());
  }

  // ------------------------------------------------------------------ Slice 2: changes

  private Order confirmed(String offerId) {
    return orders.createOrder(
        command(
            TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
            bundle(
                "bdl_01ARZ3NDEKTSV4RRFFQ69G5F" + String.format("%02d", ATTEMPT.get() % 100),
                offerId)));
  }

  private ChangeOrderCommand change(Order order, String key, String replacementOfferId) {
    return ChangeOrderCommand.newBuilder()
        .setCtx(ctx(key))
        .setOrderId(order.getOrderId())
        .setDisruptionId("dsr_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setReplacement(bundle("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB1", replacementOfferId))
        .setPolicyDecisionId("pd_01ARZ3NDEKTSV4RRFFQ69G5FB1")
        .setOptimizationRunId("opt_01ARZ3NDEKTSV4RRFFQ69G5FB1")
        .addPassengers(
            Passenger.newBuilder()
                .setGivenName("Alice")
                .setFamilyName("Nguyen")
                .setEmail("alice@acme.example"))
        .setPaymentToken("tok_corp_visa")
        .build();
  }

  @Test
  @org.junit.jupiter.api.Order(10)
  void aChangeReissuesTheOrderOnceHoweverManyTimesItIsAsked() {
    Order order = confirmed("ok-DL240");
    String key = "TRIP:" + TRIP + ":DISRUPTION:dsr_01ARZ3NDEKTSV4RRFFQ69G5FAV:CHANGE:1";
    ChangeOrderCommand command = change(order, key, "ok-DL242");

    Order first = orders.changeOrder(command);
    Order second = orders.changeOrder(command);
    Order third = orders.changeOrder(command);

    assertThat(first.getStatus()).isEqualTo(OrderStatus.CHANGED);
    assertThat(first.getBundleId()).isEqualTo("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB1");
    assertThat(first.getItemsList()).hasSize(2);
    assertThat(first.getItems(0).getStatus()).isEqualTo(OrderItemStatus.ITEM_CHANGED);
    assertThat(first.getItems(1).getStatus()).isEqualTo(OrderItemStatus.ITEM_CONFIRMED);
    assertThat(first.getItems(1).getOffer().getProviderOfferId()).isEqualTo("ok-DL242");
    long expectedIncrement =
        FakeSupplierGateway.cents("ok-DL242") - FakeSupplierGateway.cents("ok-DL240");
    assertThat(first.getTotal().getAmountMinor())
        .isEqualTo(order.getTotal().getAmountMinor() + expectedIncrement);
    assertThat(first.getChangesList())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.getStatus()).isEqualTo("APPLIED");
              assertThat(c.getIdempotencyKey()).isEqualTo(key);
              assertThat(c.getDisruptionId()).isEqualTo("dsr_01ARZ3NDEKTSV4RRFFQ69G5FAV");
              assertThat(c.getIncrementalCost().getAmountMinor()).isEqualTo(expectedIncrement);
              assertThat(c.getPreviousBundleId()).isEqualTo(order.getBundleId());
            });
    assertThat(second).isEqualTo(first);
    assertThat(third).isEqualTo(first);
    String supplierKey = first.getOrderId() + ":" + first.getChanges(0).getChangeId();
    assertThat(SUPPLIER.changeAttempts.get(supplierKey).get())
        .as("replays never reach the supplier again")
        .isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM order_change WHERE order_id = :o")
                .param("o", first.getOrderId())
                .query(Long.class)
                .single())
        .isEqualTo(1L);

    // Impacted-trip detection finds it by the supplier's reference, in its tenant only.
    Order found =
        orders.findOrderByExternalRef(
            FindOrderByExternalRefRequest.newBuilder()
                .setCtx(ctx(""))
                .setSupplier("sandbox-air")
                .setExternalOrderId(first.getExternalOrderId())
                .build());
    assertThat(found.getOrderId()).isEqualTo(first.getOrderId());
    assertThatThrownBy(
            () ->
                orders.findOrderByExternalRef(
                    FindOrderByExternalRefRequest.newBuilder()
                        .setCtx(ctx("").toBuilder().setTenantId("globex").build())
                        .setSupplier("sandbox-air")
                        .setExternalOrderId(first.getExternalOrderId())
                        .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.NOT_FOUND));
  }

  @Test
  @org.junit.jupiter.api.Order(11)
  void aChangeSurvivesTransientSupplierFailuresAndResumesFromPending() {
    Order order = confirmed("ok-UA300");
    String key = "TRIP:" + TRIP + ":DISRUPTION:dsr_01ARZ3NDEKTSV4RRFFQ69G5FB2:CHANGE:1";
    Order changed = orders.changeOrder(change(order, key, "flaky-UA302"));
    assertThat(changed.getStatus()).isEqualTo(OrderStatus.CHANGED);
    String supplierKey = changed.getOrderId() + ":" + changed.getChanges(0).getChangeId();
    assertThat(SUPPLIER.changeAttempts.get(supplierKey).get())
        .as("two blips, then success, all under one supplier-side key")
        .isEqualTo(3);
  }

  @Test
  @org.junit.jupiter.api.Order(12)
  void aFinalSupplierRefusalEndsTheChangeAndLeavesTheOrderAsItWas() {
    Order order = confirmed("ok-AA500");
    String key = "TRIP:" + TRIP + ":DISRUPTION:dsr_01ARZ3NDEKTSV4RRFFQ69G5FB3:CHANGE:1";
    Order after = orders.changeOrder(change(order, key, "soldout-AA502"));
    assertThat(after.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(after.getBundleId()).isEqualTo(order.getBundleId());
    assertThat(after.getItemsList()).hasSize(1);
    assertThat(after.getChangesList())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.getStatus()).isEqualTo("FAILED");
              assertThat(c.getFailureCode()).isEqualTo("SEAT_NO_LONGER_AVAILABLE");
            });
    // The key is spent: asking again returns the order, it does not retry the supplier.
    Order again = orders.changeOrder(change(order, key, "soldout-AA502"));
    assertThat(again.getChangesList()).hasSize(1);
    // A NEW logical attempt (n=2) may try another itinerary.
    Order second =
        orders.changeOrder(
            change(
                order,
                "TRIP:" + TRIP + ":DISRUPTION:dsr_01ARZ3NDEKTSV4RRFFQ69G5FB3:CHANGE:2",
                "ok-AA504"));
    assertThat(second.getStatus()).isEqualTo(OrderStatus.CHANGED);
    assertThat(second.getChangesList()).hasSize(2);
  }

  @Test
  @org.junit.jupiter.api.Order(13)
  void changesAreGuarded() {
    Order order = confirmed("ok-B6700");
    assertThatThrownBy(
            () -> orders.changeOrder(change(order, "TRIP:" + TRIP + ":CHANGE:1", "ok-B6700")))
        .as("same itinerary")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getDescription()).contains("SAME_ITINERARY"));
    assertThatThrownBy(() -> orders.changeOrder(change(order, "", "ok-B6702")))
        .as("no key")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e ->
                assertThat(e.getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.INVALID_ARGUMENT));
    assertThatThrownBy(
            () ->
                orders.changeOrder(
                    change(order, "TRIP:" + TRIP + ":CHANGE:1", "ok-B6702").toBuilder()
                        .setCtx(ctx("TRIP:" + TRIP + ":CHANGE:1").toBuilder().setTenantId("globex"))
                        .build()))
        .as("another tenant cannot change it")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.NOT_FOUND));
  }

  // ------------------------------------------------------------------ Phase 6

  @Test
  @org.junit.jupiter.api.Order(18)
  void aPartialCancellationReleasesOnlyTheNamedComponentsAndKeepsTheOrder() {
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                itinerary(
                    "bdl_01ARZ3NDEKTSV4RRFFQ69G5FB8",
                    "ok-DL190",
                    "hotel-SEA-8",
                    "norefund-hotel-SEA-9")));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    String hotel = order.getItems(1).getComponentId();
    String nonRefundable = order.getItems(2).getComponentId();
    String airRef = order.getItems(0).getExternalRef();

    // the refundable hotel is not needed any more: only it is released, the order stays
    CancelOrderCommand partial =
        CancelOrderCommand.newBuilder()
            .setCtx(ctx(order.getOrderId() + ":RELEASE-COMPONENTS:1"))
            .setOrderId(order.getOrderId())
            .setReason("the meeting moved online; the hotel is not needed")
            .addComponentIds(hotel)
            .build();
    Order after = orders.cancelOrder(partial);
    assertThat(after.getStatus())
        .as("the order keeps its status for the rest")
        .isEqualTo(OrderStatus.CONFIRMED);
    assertThat(after.getItems(0).getStatus()).isEqualTo(OrderItemStatus.ITEM_CONFIRMED);
    assertThat(after.getItems(1).getStatus()).isEqualTo(OrderItemStatus.ITEM_CANCELLED);
    assertThat(after.getItems(2).getStatus()).isEqualTo(OrderItemStatus.ITEM_CONFIRMED);
    assertThat(SUPPLIER.cancelled)
        .contains(order.getItems(1).getExternalRef())
        .doesNotContain(airRef);
    // the same request again releases nothing twice
    int attempts = SUPPLIER.cancelAttempts.size();
    assertThat(orders.cancelOrder(partial).getItems(1).getStatus())
        .isEqualTo(OrderItemStatus.ITEM_CANCELLED);
    assertThat(SUPPLIER.cancelAttempts).hasSize(attempts);
    // the refund reached the ledger per item
    JsonNode receipt =
        json.readTree(
            http.get()
                .uri("/api/v1/orders/" + order.getOrderId() + "/receipt")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.alice())
                .retrieve()
                .body(String.class));
    assertThat(receipt.get("payment").get("refundedMinor").asLong()).isEqualTo(40000);

    // a non-refundable component: refused for good, an exposure for a person, the rest untouched
    CancelOrderCommand refused =
        partial.toBuilder()
            .setCtx(ctx(order.getOrderId() + ":RELEASE-COMPONENTS:2"))
            .clearComponentIds()
            .addComponentIds(nonRefundable)
            .build();
    Order exposed = orders.cancelOrder(refused);
    assertThat(exposed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(exposed.getItems(2).getStatus()).isEqualTo(OrderItemStatus.ITEM_CANCEL_FAILED);
    assertThat(exposed.getItems(2).getFailureCode()).isEqualTo("CANCELLATION_REFUSED");
    JsonNode exposures =
        json.readTree(
            http.get()
                .uri("/api/v1/orders/exposures?status=OPEN&limit=500")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.carol())
                .retrieve()
                .body(String.class));
    assertThat(
            exposures
                .valueStream()
                .map(e -> e.get("exposure").path("componentId").asString(""))
                .toList())
        .as("the refusal is an exposure for a person, on that component")
        .contains(nonRefundable);

    // an unknown component is refused explicitly; a foreign tenant sees no order
    assertThatThrownBy(
            () ->
                orders.cancelOrder(
                    partial.toBuilder()
                        .clearComponentIds()
                        .addComponentIds("cmp_01ARZ3NDEKTSV4RRFFQ69G5FZZ")
                        .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
  }

  @Test
  @org.junit.jupiter.api.Order(99)
  void everyOrderEventIsContractValid() {
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
                      "travel.order.created",
                      "travel.order.confirmed",
                      "travel.order.failed",
                      "travel.order.cancelled",
                      "travel.order.change-requested",
                      "travel.order.changed",
                      "travel.order.items-released");
            });
    for (ConsumerRecord<String, String> record : received) {
      assertThat(EventSchemas.violations(record.value())).as(record.value()).isEmpty();
      assertThat(record.key()).isEqualTo(TRIP);
    }
  }

  // ------------------------------------------------------------------ Slice 3

  @Test
  @org.junit.jupiter.api.Order(14)
  void anItineraryBooksLegsThenStaysThenTransfersAndCarriesComponentIds() {
    int before = SUPPLIER.createLog.size();
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                itinerary(
                    "bdl_01ARZ3NDEKTSV4RRFFQ69G5FB1", "ground-SEA-1", "hotel-SEA-1", "ok-DL140")));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(order.getItemsList())
        .extracting(i -> i.getOffer().getType().name())
        .as("legs first, then the stay, then the transfer")
        .containsExactly("AIR", "HOTEL", "GROUND");
    assertThat(order.getItemsList()).allMatch(i -> i.getComponentId().startsWith("cmp_"));
    assertThat(order.getItemsList()).allMatch(i -> i.getTotal().getAmountMinor() > 0);
    assertThat(SUPPLIER.createLog.subList(before, SUPPLIER.createLog.size()))
        .containsExactly("ok-DL140", "hotel-SEA-1", "ground-SEA-1");
    JsonNode view =
        json.readTree(get("/api/v1/orders/" + order.getOrderId(), TestTokens.alice()).getBody());
    assertThat(view.get("items").get(1).get("hotel").get("propertyId").asString())
        .isEqualTo("HTL-SEA-1");
    assertThat(view.get("items").get(1).get("hotel").get("checkInDate").asString())
        .isEqualTo("2026-10-06");
    assertThat(view.get("items").get(2).get("ground").get("vendorName").asString())
        .isEqualTo("CityShuttle");
    assertThat(view.get("items").get(0).get("componentId").asString()).startsWith("cmp_");
    assertThat(view.has("exposures")).isFalse();
  }

  @Test
  @org.junit.jupiter.api.Order(15)
  void aLostAnswerIsReconciledByStatusLookupAndNeverBookedTwice() {
    int lookupsBefore = SUPPLIER.statusLookups.get();
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                itinerary("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB2", "ok-DL150", "lost-hotel-SEA")));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    String key = order.getOrderId() + ":" + order.getItems(1).getItemId();
    assertThat(SUPPLIER.ordersByKey).containsKey(key);
    assertThat(order.getItems(1).getExternalRef())
        .as("the booking the supplier made under our key is the one we hold")
        .isEqualTo(SUPPLIER.ordersByKey.get(key).getExternalOrderId());
    assertThat(SUPPLIER.createAttempts.get(key).get())
        .as("the client retried, every answer was lost")
        .isGreaterThanOrEqualTo(3);
    assertThat(SUPPLIER.statusLookups.get()).isGreaterThan(lookupsBefore);
    assertThat(SUPPLIER.ordersByKey.keySet().stream().filter(key::equals).count()).isEqualTo(1);
  }

  @Test
  @org.junit.jupiter.api.Order(15)
  void anUnknownOutcomeIsNeitherFailedNorConfirmedButExposedForAPerson() {
    // Phase 4 (ADR-0016): the gateway's ledger could neither reconcile nor safely retry
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                itinerary("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB9", "ok-DL170", "unknown-hotel-SEA")));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FAILED);
    assertThat(order.getCompensated()).as("money may be committed at the hotel").isFalse();
    assertThat(order.getItems(1).getStatus().name()).isEqualTo("ITEM_UNKNOWN");
    assertThat(order.getItems(0).getStatus())
        .as("the confirmed leg was released")
        .isEqualTo(OrderItemStatus.ITEM_CANCELLED);
    assertThat(order.getExposuresList())
        .anySatisfy(
            x -> {
              assertThat(x.getReason()).isEqualTo("OUTCOME_UNKNOWN");
              assertThat(x.getStatus()).isEqualTo("OPEN");
              assertThat(x.getAmount()).isEqualTo(order.getItems(1).getTotal());
            });
    assertThat(order.getFailureCode()).isEqualTo("OUTCOME_UNKNOWN");
  }

  @Test
  @org.junit.jupiter.api.Order(16)
  void aLaterFailureCompensatesInReverseOrderAndExposesWhatCannotBeReleased() {
    int before = SUPPLIER.cancelAttempts.size();
    Order order =
        orders.createOrder(
            command(
                TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(),
                itinerary(
                    "bdl_01ARZ3NDEKTSV4RRFFQ69G5FB3",
                    "ok-DL160",
                    "norefund-hotel-SEA",
                    "soldout-ground-SEA")));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FAILED);
    assertThat(order.getCompensated()).isFalse();
    assertThat(order.getItems(2).getStatus()).isEqualTo(OrderItemStatus.ITEM_FAILED);
    assertThat(order.getItems(1).getStatus()).isEqualTo(OrderItemStatus.ITEM_CANCEL_FAILED);
    assertThat(order.getItems(0).getStatus()).isEqualTo(OrderItemStatus.ITEM_CANCELLED);
    assertThat(SUPPLIER.cancelAttempts.subList(before, SUPPLIER.cancelAttempts.size()))
        .as("the last thing confirmed is the first thing released")
        .containsExactly(order.getItems(1).getExternalRef(), order.getItems(0).getExternalRef());
    assertThat(order.getExposuresCount()).isEqualTo(1);
    assertThat(order.getExposures(0).getStatus()).isEqualTo("OPEN");
    assertThat(order.getExposures(0).getReason()).isEqualTo("COMPENSATION_FAILED");
    assertThat(order.getExposures(0).getDetail()).startsWith("CANCELLATION_REFUSED");
    assertThat(order.getExposures(0).getAmount()).isEqualTo(order.getItems(1).getTotal());
    String exposureId = order.getExposures(0).getExposureId();
    String path =
        "/api/v1/orders/" + order.getOrderId() + "/exposures/" + exposureId + "/resolution";
    // the traveler cannot close the company's exposure; a travel admin can, once
    assertThat(resolve(path, TestTokens.alice(), "res-" + exposureId).getStatusCode().value())
        .isEqualTo(403);
    ResponseEntity<String> resolved = resolve(path, TestTokens.carol(), "res-" + exposureId);
    assertThat(resolved.getStatusCode().value()).as(resolved.getBody()).isEqualTo(200);
    assertThat(json.readTree(resolved.getBody()).get("status").asString()).isEqualTo("RESOLVED");
    assertThat(json.readTree(resolved.getBody()).get("resolvedBy").asString())
        .isEqualTo("human/carol");
    assertThat(resolve(path, TestTokens.carol(), "res-" + exposureId).getStatusCode().value())
        .as("idempotent")
        .isEqualTo(200);
    assertThat(resolve(path, TestTokens.carol(), "other-key").getStatusCode().value())
        .as("a different resolution of the same exposure is a conflict")
        .isEqualTo(409);
    JsonNode view =
        json.readTree(get("/api/v1/orders/" + order.getOrderId(), TestTokens.bob()).getBody());
    assertThat(view.get("status").asString())
        .as("nothing is open any more: FAILED, compensated by a person")
        .isEqualTo("FAILED");
    assertThat(view.get("compensated").asBoolean()).isTrue();
    assertThat(view.get("exposures").get(0).get("resolution").asString()).contains("phone");
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              consumer.poll(Duration.ofMillis(250)).forEach(received::add);
              List<String> mine =
                  received.stream()
                      .filter(r -> r.value().contains(order.getOrderId()))
                      .map(r -> json.readTree(r.value()).get("eventType").asString())
                      .toList();
              assertThat(mine)
                  .contains("travel.order.compensation-failed", "travel.order.exposure-resolved");
            });
    for (ConsumerRecord<String, String> record : received) {
      if (record.value().contains(order.getOrderId())) {
        assertThat(EventSchemas.violations(record.value())).as(record.value()).isEmpty();
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Order(17)
  void aComponentChangePreservesTheItemsItDoesNotName() {
    Bundle original =
        itinerary("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB4", "ok-DL170", "hotel-SEA-2", "ground-SEA-1");
    Order order =
        orders.createOrder(command(TRIP + ":CREATE-ORDER:" + ATTEMPT.incrementAndGet(), original));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    String rideId = original.getOffers(2).getComponentId();
    String stayId = original.getOffers(1).getComponentId();
    String legId = original.getOffers(0).getComponentId();
    Bundle fresh = itinerary("bdl_01ARZ3NDEKTSV4RRFFQ69G5FB5", "ok-DL777", "ground-SEA-2");
    Bundle replacement =
        fresh.toBuilder()
            .clearOffers()
            .addOffers(fresh.getOffers(0).toBuilder().setComponentId(legId))
            .addOffers(fresh.getOffers(1).toBuilder().setComponentId(rideId))
            .build();
    String key = "TRIP:" + TRIP + ":DISRUPTION:dsr_01ARZ3NDEKTSV4RRFFQ69G5FB5:CHANGE:1";
    ChangeOrderCommand change =
        ChangeOrderCommand.newBuilder()
            .setCtx(ctx(key))
            .setOrderId(order.getOrderId())
            .setDisruptionId("dsr_01ARZ3NDEKTSV4RRFFQ69G5FB5")
            .setReplacement(replacement)
            .setPolicyDecisionId("pd_01ARZ3NDEKTSV4RRFFQ69G5FB5")
            .addPassengers(Passenger.newBuilder().setGivenName("Alice").setFamilyName("Nguyen"))
            .setPaymentToken("tok_corp_visa")
            .setReason("connected recovery")
            .build();
    Order changed = orders.changeOrder(change);
    assertThat(changed.getStatus()).isEqualTo(OrderStatus.CHANGED);
    assertThat(changed.getItemsList())
        .extracting(i -> i.getComponentId() + ":" + i.getStatus().name())
        .containsExactlyInAnyOrder(
            legId + ":ITEM_CHANGED",
            stayId + ":ITEM_CONFIRMED",
            rideId + ":ITEM_CHANGED",
            legId + ":ITEM_CONFIRMED",
            rideId + ":ITEM_CONFIRMED");
    long expected =
        changed.getItemsList().stream()
            .filter(i -> i.getStatus() == OrderItemStatus.ITEM_CONFIRMED)
            .mapToLong(i -> i.getTotal().getAmountMinor())
            .sum();
    assertThat(changed.getTotal().getAmountMinor()).isEqualTo(expected);
    long incremental =
        FakeSupplierGateway.cents("ok-DL777")
            - FakeSupplierGateway.cents("ok-DL170")
            + FakeSupplierGateway.cents("ground-SEA-2")
            - FakeSupplierGateway.cents("ground-SEA-1");
    assertThat(changed.getChanges(0).getIncrementalCost().getAmountMinor()).isEqualTo(incremental);
    assertThat(orders.changeOrder(change).getVersion())
        .as("replay is a read")
        .isEqualTo(changed.getVersion());
  }

  private ResponseEntity<String> resolve(String path, String token, String key) {
    return http.post()
        .uri(path)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key)
        .body("{\"resolution\":\"cancelled by phone with the property; refund confirmed\"}")
        .retrieve()
        .toEntity(String.class);
  }

  /**
   * Typed offers by prefix (hotel-*, ground-*, else air), each tagged with a fresh component id.
   */
  private static Bundle itinerary(String bundleId, String... providerOfferIds) {
    Bundle.Builder b = Bundle.newBuilder().setBundleId(bundleId);
    long total = 0;
    for (String id : providerOfferIds) {
      long cents = FakeSupplierGateway.cents(id);
      total += cents;
      String componentId =
          io.travelos.common.ids.Ids.newId(io.travelos.common.ids.IdPrefix.COMPONENT);
      Offer.Builder o =
          Offer.newBuilder()
              .setOfferId("off_" + id)
              .setProviderOfferId(id)
              .setComponentId(componentId)
              .setTotal(Money.newBuilder().setCurrency("USD").setAmountMinor(cents));
      if (id.contains("hotel-")) {
        o.setProvider("sandbox-hotel")
            .setType(OfferType.HOTEL)
            .setHotel(
                io.travelos.contracts.offer.v1.HotelOffer.newBuilder()
                    .setPropertyId("HTL-SEA-1")
                    .setName("Budget Inn")
                    .setCity("SEA")
                    .setCheckInDate("2026-10-06")
                    .setCheckOutDate("2026-10-08")
                    .setNights(2)
                    .setTimeZone("America/Los_Angeles"));
      } else if (id.contains("ground-")) {
        o.setProvider("sandbox-ground")
            .setType(OfferType.GROUND)
            .setGround(
                io.travelos.contracts.offer.v1.GroundOffer.newBuilder()
                    .setVendorId("GRD-SEA-SHUTTLE")
                    .setVendorName("CityShuttle")
                    .setVehicleClass("SHUTTLE")
                    .setPickupLocation("SEA airport")
                    .setDropoffLocation("hotel")
                    .setTimeZone("America/Los_Angeles"));
      } else {
        o.setProvider("sandbox-air")
            .setType(OfferType.AIR)
            .setAir(
                AirOffer.newBuilder()
                    .setOutbound(
                        Journey.newBuilder()
                            .addSegments(
                                FlightSegment.newBuilder()
                                    .setCarrier("DL")
                                    .setOrigin("BOS")
                                    .setDestination("SEA"))));
      }
      b.addOffers(o);
    }
    return b.setTotal(Money.newBuilder().setCurrency("USD").setAmountMinor(total)).build();
  }

  // ------------------------------------------------------------------ helpers

  private static CreateOrderCommand command(String key, Bundle bundle) {
    return CreateOrderCommand.newBuilder()
        .setCtx(ctx(key))
        .setTripId(TRIP)
        .setTravelerId("emp_1001")
        .setBundle(bundle)
        .setPolicyDecisionId("pd_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setOptimizationRunId("opt_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .setApprovalId("apr_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        .addPassengers(
            Passenger.newBuilder()
                .setGivenName("Alice")
                .setFamilyName("Nguyen")
                .setEmail("alice@acme.example"))
        .setPaymentToken("tok_corp_visa")
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

  private static Bundle bundle(String bundleId, String... providerOfferIds) {
    Bundle.Builder b = Bundle.newBuilder().setBundleId(bundleId);
    long total = 0;
    for (String id : providerOfferIds) {
      long cents = FakeSupplierGateway.cents(id);
      total += cents;
      b.addOffers(
          Offer.newBuilder()
              .setOfferId("off_" + id)
              .setProvider("sandbox-air")
              .setProviderOfferId(id)
              .setType(OfferType.AIR)
              .setTotal(Money.newBuilder().setCurrency("USD").setAmountMinor(cents))
              .setAir(
                  AirOffer.newBuilder()
                      .setOutbound(
                          Journey.newBuilder()
                              .addSegments(
                                  FlightSegment.newBuilder()
                                      .setCarrier("DL")
                                      .setOrigin("BOS")
                                      .setDestination("SEA")))));
    }
    return b.setTotal(Money.newBuilder().setCurrency("USD").setAmountMinor(total)).build();
  }

  private ResponseEntity<String> get(String path, String token) {
    return http.get()
        .uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }
}
