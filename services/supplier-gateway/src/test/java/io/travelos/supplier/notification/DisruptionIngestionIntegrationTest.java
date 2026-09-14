package io.travelos.supplier.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.travelos.contracts.common.v1.Cabin;
import io.travelos.contracts.common.v1.Principal;
import io.travelos.contracts.common.v1.RequestContext;
import io.travelos.contracts.common.v1.TimeWindow;
import io.travelos.contracts.offer.v1.Offer;
import io.travelos.contracts.supplier.v1.ChangeOrderRequest;
import io.travelos.contracts.supplier.v1.ChangeOrderResponse;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SupplierGatewayGrpc;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.events.Topics;
import io.travelos.events.testing.EventSchemas;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import io.travelos.spring.web.testing.TestTokens;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.http.HttpStatus;
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
 * The airline cancels a flight: its webhook is verified, deduped and normalized into ONE
 * travel.disruption.detected on the real broker; its inventory loses the flight and reprices the
 * alternatives deterministically; the order is reissued exactly once per key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestTokens.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DisruptionIngestionIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  static final String SECRET = "sandbox-air-dev-webhook-secret";
  static final String TRIP = "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV";
  static final Instant OUT = Instant.parse("2026-10-06T00:00:00Z");
  static final Instant OUT_END = Instant.parse("2026-10-06T23:59:59Z");
  static final Instant BACK = Instant.parse("2026-10-07T00:00:00Z");
  static final Instant BACK_END = Instant.parse("2026-10-07T23:59:59Z");

  @Autowired GrpcServerLifecycle grpc;
  @Autowired JdbcClient jdbc;
  @Autowired Environment environment;
  @Autowired KafkaConnectionDetails kafkaConnection;

  private final JsonMapper json = JsonMapper.builder().build();
  private ManagedChannel channel;
  private SupplierGatewayGrpc.SupplierGatewayBlockingStub gateway;
  private RestClient http;
  private KafkaConsumer<String, String> consumer;

  private Offer booked;
  private List<Offer> initialOffers;
  private CreateOrderResponse order;
  private String disruptionId;

  @BeforeAll
  void setUp() {
    channel = ManagedChannelBuilder.forAddress("localhost", grpc.port()).usePlaintext().build();
    gateway = SupplierGatewayGrpc.newBlockingStub(channel);
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
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "gateway-test-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of(Topics.DISRUPTION));
  }

  @AfterAll
  void tearDown() {
    consumer.close();
    channel.shutdownNow();
  }

  @Test
  @org.junit.jupiter.api.Order(1)
  void theGatewayRemembersWhatItBooked() {
    SearchAirResponse search = gateway.searchAir(search("BOS", "SEA"));
    initialOffers = search.getOffersList();
    booked =
        search.getOffersList().stream()
            .filter(o -> o.getAir().getOutbound().getSegmentsCount() == 1)
            .filter(o -> o.getAir().getOutbound().getSegments(0).getCabin() == Cabin.ECONOMY)
            .min(Comparator.comparingLong(o -> o.getTotal().getAmountMinor()))
            .orElseThrow();
    order =
        gateway.createOrder(
            CreateOrderRequest.newBuilder()
                .setCtx(ctx(TRIP + ":CREATE-ORDER:1"))
                .setProvider("sandbox-air")
                .setProviderOfferId(booked.getProviderOfferId())
                .addPassengers(alice())
                .setPaymentToken("tok_visa_4242")
                .build());
    assertThat(order.getStatus()).isEqualTo(SupplierOrderStatus.CONFIRMED);
    assertThat(
            jdbc.sql(
                    "SELECT correlation_id FROM supplier_order_ref WHERE provider = 'sandbox-air' AND external_order_id = :id")
                .param("id", order.getExternalOrderId())
                .query(String.class)
                .single())
        .isEqualTo(TRIP);
  }

  @Test
  @org.junit.jupiter.api.Order(2)
  void aSignedCancellationBecomesExactlyOneDetectedEventHoweverOftenItIsDelivered() {
    String flight = booked.getAir().getOutbound().getSegments(0).getFlightNumber();
    String body =
        """
        {"eventId":"sbx-evt-1001","type":"FLIGHT_CANCELLED","externalOrderId":"%s","flightNumber":"%s",
         "date":"2026-10-06","reason":"crew availability","reaccommodation":{"fareDeltaMinor":7300}}
        """
            .formatted(order.getExternalOrderId(), flight);

    ResponseEntity<String> unsigned = post(body, "sha256=deadbeef");
    assertThat(unsigned.getStatusCode()).as(unsigned.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
    ResponseEntity<String> noHeader = post(body, null);
    assertThat(noHeader.getStatusCode()).as(noHeader.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);

    ResponseEntity<String> first = post(body, sign(body));
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    JsonNode receipt = json.readTree(first.getBody());
    disruptionId = receipt.get("disruptionId").asString();
    assertThat(disruptionId).startsWith("dsr_");
    assertThat(receipt.get("duplicate").asBoolean()).isFalse();

    ResponseEntity<String> redelivered = post(body, sign(body));
    assertThat(redelivered.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode again = json.readTree(redelivered.getBody());
    assertThat(again.get("disruptionId").asString()).isEqualTo(disruptionId);
    assertThat(again.get("duplicate").asBoolean()).isTrue();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM supplier_notification WHERE supplier_event_id = 'sbx-evt-1001'")
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isEqualTo(1L);

    List<ConsumerRecord<String, String>> received = new ArrayList<>();
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              consumer.poll(Duration.ofMillis(250)).forEach(received::add);
              assertThat(received).hasSize(1);
            });
    ConsumerRecord<String, String> record = received.getFirst();
    assertThat(EventSchemas.violations(record.value())).as(record.value()).isEmpty();
    assertThat(record.key()).as("keyed by the trip, like every event of that trip").isEqualTo(TRIP);
    JsonNode event = json.readTree(record.value());
    assertThat(event.get("eventType").asString()).isEqualTo("travel.disruption.detected");
    assertThat(event.get("producer").asString()).isEqualTo("supplier-gateway");
    assertThat(event.get("correlationId").asString()).isEqualTo(TRIP);
    assertThat(event.get("causationId").asString()).isEqualTo("sbx-evt-1001");
    JsonNode data = event.get("data");
    assertThat(data.get("disruptionId").asString()).isEqualTo(disruptionId);
    assertThat(data.get("externalOrderId").asString()).isEqualTo(order.getExternalOrderId());
    assertThat(data.get("severity").asString()).isEqualTo("HIGH");
    assertThat(data.get("reason").asString()).isEqualTo("crew availability");
    assertThat(data.get("affected").get("flightNumber").asString()).isEqualTo(flight);
    assertThat(data.get("affected").get("origin").asString()).isEqualTo("BOS");
    assertThat(data.has("reaccommodation"))
        .as("sandbox knobs never leak into the platform")
        .isFalse();

    // Notices about orders the gateway never booked are refused, and unknown providers too.
    ResponseEntity<String> unknown =
        post(
            body.replace(order.getExternalOrderId(), "SBX-01ARZ3NDEKTSV4RRFFQ69G5FZZ"), null, true);
    assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<String> noAdapter =
        http.post()
            .uri("/api/v1/suppliers/ndc-nowhere/events")
            .header("Content-Type", "application/json")
            .header(SupplierNotificationController.SIGNATURE_HEADER, sign(body))
            .body(body)
            .retrieve()
            .toEntity(String.class);
    assertThat(noAdapter.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @org.junit.jupiter.api.Order(3)
  void theCancelledFlightIsGoneAndTheReplacementCostsExactlyTheDeltaMore() {
    SearchAirResponse after = gateway.searchAir(search("BOS", "SEA"));
    assertThat(after.getOffersList())
        .noneMatch(o -> o.getProviderOfferId().equals(booked.getProviderOfferId()));
    String cancelledFlight = booked.getAir().getOutbound().getSegments(0).getFlightNumber();
    assertThat(after.getOffersList())
        .noneMatch(
            o ->
                o.getAir().getOutbound().getSegmentsCount() == 1
                    && o.getAir()
                        .getOutbound()
                        .getSegments(0)
                        .getFlightNumber()
                        .equals(cancelledFlight));
    List<Offer> economy =
        after.getOffersList().stream()
            .filter(o -> o.getAir().getOutbound().getSegments(0).getCabin() == Cabin.ECONOMY)
            .sorted(Comparator.comparingLong(o -> o.getTotal().getAmountMinor()))
            .toList();
    long original = booked.getTotal().getAmountMinor();
    Offer cheapest = economy.getFirst();
    assertThat(cheapest.getTotal().getAmountMinor()).isEqualTo(original + 7300);
    assertThat(cheapest.getAir().getOutbound().getSegmentsCount()).isEqualTo(1);
    assertThat(cheapest.getAir().getOutbound().getSegments(0).getCarrier())
        .isEqualTo(booked.getAir().getOutbound().getSegments(0).getCarrier());
    // every surviving nonstop is reaccommodated at exactly the delta; one-stops cost strictly more
    assertThat(economy)
        .filteredOn(o -> o.getAir().getOutbound().getSegmentsCount() == 1)
        .isNotEmpty()
        .allSatisfy(o -> assertThat(o.getTotal().getAmountMinor()).isEqualTo(original + 7300));
    assertThat(economy)
        .filteredOn(o -> o.getAir().getOutbound().getSegmentsCount() > 1)
        .isNotEmpty()
        .allSatisfy(
            o ->
                assertThat(o.getTotal().getAmountMinor())
                    .isGreaterThanOrEqualTo(original + 7300 + 2500));
    // and the same search, twice, prices the same way: reaccommodation is deterministic
    assertThat(
            gateway.searchAir(search("BOS", "SEA")).getOffersList().stream()
                .filter(o -> o.getAir().getOutbound().getSegments(0).getCabin() == Cabin.ECONOMY)
                .mapToLong(o -> o.getTotal().getAmountMinor())
                .min()
                .orElseThrow())
        .isEqualTo(original + 7300);
    // another trip on the same route and day loses the cancelled flight but keeps the published
    // fares
    SearchAirResponse other =
        gateway.searchAir(
            search("BOS", "SEA").toBuilder()
                .setCtx(ctx("").toBuilder().setCorrelationId("trip_01ARZ3NDEKTSV4RRFFQ69G5FAZ"))
                .build());
    assertThat(other.getOffersList())
        .noneMatch(
            o ->
                o.getAir().getOutbound().getSegmentsCount() == 1
                    && o.getAir()
                        .getOutbound()
                        .getSegments(0)
                        .getFlightNumber()
                        .equals(cancelledFlight));
    long otherCheapest =
        other.getOffersList().stream()
            .filter(o -> o.getAir().getOutbound().getSegments(0).getCabin() == Cabin.ECONOMY)
            .mapToLong(o -> o.getTotal().getAmountMinor())
            .min()
            .orElseThrow();
    assertThat(otherCheapest)
        .as("no reaccommodation surcharge for other travelers")
        .isLessThan(original + 7300);
    // pricing the cancelled offer says so, finally
    assertThatThrownBy(
            () ->
                gateway.priceOffer(
                    PriceOfferRequest.newBuilder()
                        .setCtx(ctx(""))
                        .setProvider("sandbox-air")
                        .setProviderOfferId(booked.getProviderOfferId())
                        .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(e.getStatus().getDescription()).startsWith("FLIGHT_CANCELLED");
            });
    // The cancelled flight is gone in business class too, but business fares are untouched by an
    // economy reaccommodation.
    java.util.Map<String, Long> businessBefore = new java.util.HashMap<>();
    for (Offer o : initialOffers) {
      if (o.getAir().getOutbound().getSegments(0).getCabin() == Cabin.BUSINESS) {
        businessBefore.put(
            o.getProviderOfferId().replaceAll("\\|[0-9]+$", ""), o.getTotal().getAmountMinor());
      }
    }
    List<Offer> businessAfter =
        after.getOffersList().stream()
            .filter(o -> o.getAir().getOutbound().getSegments(0).getCabin() == Cabin.BUSINESS)
            .toList();
    assertThat(businessAfter).isNotEmpty();
    for (Offer o : businessAfter) {
      assertThat(o.getAir().getOutbound().getSegments(0).getFlightNumber())
          .isNotEqualTo(cancelledFlight);
      Long before = businessFare(initialOffers, o);
      assertThat(before).as("business itinerary existed before").isNotNull();
      assertThat(o.getTotal().getAmountMinor()).isEqualTo(before);
    }
  }

  @Test
  @org.junit.jupiter.api.Order(4)
  void reissueIsIdempotentByKeyAndChargesTheIncrementOnce() {
    Offer replacement =
        gateway.searchAir(search("BOS", "SEA")).getOffersList().stream()
            .filter(o -> o.getAir().getOutbound().getSegments(0).getCabin() == Cabin.ECONOMY)
            .min(Comparator.comparingLong(o -> o.getTotal().getAmountMinor()))
            .orElseThrow();
    String key = "TRIP:" + TRIP + ":DISRUPTION:" + disruptionId + ":CHANGE:1";
    ChangeOrderRequest change =
        ChangeOrderRequest.newBuilder()
            .setCtx(ctx(key))
            .setProvider("sandbox-air")
            .setExternalOrderId(order.getExternalOrderId())
            .setNewProviderOfferId(replacement.getProviderOfferId())
            .setPaymentToken("tok_visa_4242")
            .build();
    ChangeOrderResponse first = gateway.changeOrder(change);
    ChangeOrderResponse second = gateway.changeOrder(change);
    assertThat(first.getStatus()).isEqualTo(SupplierOrderStatus.CHANGED);
    assertThat(first.getExternalOrderId()).isEqualTo(order.getExternalOrderId());
    assertThat(first.getRecordLocator()).isEqualTo(order.getRecordLocator());
    assertThat(first.getIncrementalCost().getAmountMinor()).isEqualTo(7300);
    assertThat(first.getChargedTotal().getAmountMinor())
        .isEqualTo(booked.getTotal().getAmountMinor() + 7300);
    assertThat(first.getTicketNumbersList())
        .hasSize(1)
        .doesNotContainAnyElementsOf(order.getTicketNumbersList());
    assertThat(second).isEqualTo(first);
    assertThat(
            jdbc.sql("SELECT count(*) FROM sandbox_order_change WHERE external_order_id = :id")
                .param("id", order.getExternalOrderId())
                .query(Long.class)
                .single())
        .as("one reissue at the airline, whatever we asked")
        .isEqualTo(1L);
    assertThat(
            jdbc.sql("SELECT status FROM sandbox_order WHERE external_order_id = :id")
                .param("id", order.getExternalOrderId())
                .query(String.class)
                .single())
        .isEqualTo("CHANGED");
    assertThatThrownBy(() -> gateway.changeOrder(change.toBuilder().setCtx(ctx("")).build()))
        .as("a change without a key is refused")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getDescription()).contains("IDEMPOTENCY_KEY_REQUIRED"));
    assertThatThrownBy(
            () ->
                gateway.changeOrder(
                    change.toBuilder()
                        .setCtx(ctx(key + "0").toBuilder().setTenantId("globex"))
                        .build()))
        .as("another tenant's key cannot touch the order")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
  }

  @Test
  @org.junit.jupiter.api.Order(5)
  void aRedeliveryAfterTheOrderMovedOnIsStillTheSameDisruptionButANewNoticeForTheOldFlightIsNot() {
    String oldFlight = booked.getAir().getOutbound().getSegments(0).getFlightNumber();
    String original =
        """
        {"eventId":"sbx-evt-1001","type":"FLIGHT_CANCELLED","externalOrderId":"%s","flightNumber":"%s",
         "date":"2026-10-06","reason":"crew availability","reaccommodation":{"fareDeltaMinor":7300}}
        """
            .formatted(order.getExternalOrderId(), oldFlight);
    // the airline retries its webhook days later; the order has been reissued onto another flight
    ResponseEntity<String> redelivered = post(original, sign(original));
    assertThat(redelivered.getStatusCode()).as(redelivered.getBody()).isEqualTo(HttpStatus.OK);
    JsonNode again = json.readTree(redelivered.getBody());
    assertThat(again.get("disruptionId").asString()).isEqualTo(disruptionId);
    assertThat(again.get("duplicate").asBoolean()).isTrue();
    assertThat(jdbc.sql("SELECT count(*) FROM supplier_notification").query(Long.class).single())
        .isEqualTo(1L);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isEqualTo(1L);
    // but a genuinely new notice about a flight the order is no longer on is a mismatch
    String stale = original.replace("sbx-evt-1001", "sbx-evt-1002");
    ResponseEntity<String> mismatch = post(stale, sign(stale));
    assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(mismatch.getBody()).contains("NOTICE_MISMATCH").contains(oldFlight);
    assertThat(jdbc.sql("SELECT count(*) FROM supplier_notification").query(Long.class).single())
        .as("a refused notice is not remembered")
        .isEqualTo(1L);
  }

  // ------------------------------------------------------------------ helpers

  private ResponseEntity<String> post(String body, String signature) {
    return post(body, signature, false);
  }

  private ResponseEntity<String> post(String body, String signature, boolean signIt) {
    RestClient.RequestBodySpec spec =
        http.post()
            .uri("/api/v1/suppliers/sandbox-air/events")
            .header("Content-Type", "application/json");
    String sig = signIt ? sign(body) : signature;
    if (sig != null) {
      spec = spec.header(SupplierNotificationController.SIGNATURE_HEADER, sig);
    }
    return spec.body(body).retrieve().toEntity(String.class);
  }

  /** Same itinerary (outbound + inbound flight numbers, cabin) in the earlier search, its fare. */
  private static Long businessFare(List<Offer> earlier, Offer o) {
    String key = itineraryKey(o);
    return earlier.stream()
        .filter(e -> itineraryKey(e).equals(key))
        .map(e -> e.getTotal().getAmountMinor())
        .findFirst()
        .orElse(null);
  }

  private static String itineraryKey(Offer o) {
    StringBuilder sb = new StringBuilder();
    o.getAir()
        .getOutbound()
        .getSegmentsList()
        .forEach(seg -> sb.append(seg.getFlightNumber()).append('>'));
    sb.append('/');
    o.getAir()
        .getInbound()
        .getSegmentsList()
        .forEach(seg -> sb.append(seg.getFlightNumber()).append('>'));
    sb.append('/').append(o.getAir().getOutbound().getSegments(0).getCabin());
    return sb.toString();
  }

  private static String sign(String body) {
    return SupplierNotificationController.sign(SECRET, body.getBytes(StandardCharsets.UTF_8));
  }

  private static SearchAirRequest search(String origin, String destination) {
    return SearchAirRequest.newBuilder()
        .setCtx(ctx(""))
        .setOrigin(origin)
        .setDestination(destination)
        .setPassengers(1)
        .addCabins(Cabin.ECONOMY)
        .addCabins(Cabin.BUSINESS)
        .setOutboundDeparture(
            TimeWindow.newBuilder().setNotBefore(ts(OUT)).setNotAfter(ts(OUT_END)))
        .setReturnDeparture(
            TimeWindow.newBuilder().setNotBefore(ts(BACK)).setNotAfter(ts(BACK_END)))
        .build();
  }

  private static RequestContext ctx(String idempotencyKey) {
    return RequestContext.newBuilder()
        .setTenantId("acme")
        .setCorrelationId(TRIP)
        .setIdempotencyKey(idempotencyKey)
        .setPrincipal(Principal.newBuilder().setKind(Principal.Kind.SERVICE).setId("service/order"))
        .build();
  }

  private static Passenger alice() {
    return Passenger.newBuilder()
        .setGivenName("Alice")
        .setFamilyName("Nguyen")
        .setEmail("alice@acme.example")
        .build();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).build();
  }
}
