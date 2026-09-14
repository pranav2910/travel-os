package io.travelos.supplier.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.travelos.contracts.supplier.v1.CancelOrderRequest;
import io.travelos.contracts.supplier.v1.CancelOrderResponse;
import io.travelos.contracts.supplier.v1.ChangeOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderRequest;
import io.travelos.contracts.supplier.v1.CreateOrderResponse;
import io.travelos.contracts.supplier.v1.Passenger;
import io.travelos.contracts.supplier.v1.PriceOfferRequest;
import io.travelos.contracts.supplier.v1.PriceOfferResponse;
import io.travelos.contracts.supplier.v1.SearchAirRequest;
import io.travelos.contracts.supplier.v1.SearchAirResponse;
import io.travelos.contracts.supplier.v1.SupplierGatewayGrpc;
import io.travelos.contracts.supplier.v1.SupplierOrderStatus;
import io.travelos.spring.grpc.GrpcServerLifecycle;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real gRPC over TCP against the sandbox adapter backed by a real Postgres ledger. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupplierGatewayIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

  private static final Instant OUT = Instant.parse("2026-10-06T00:00:00Z");
  private static final Instant OUT_END = Instant.parse("2026-10-06T23:59:59Z");
  private static final Instant BACK = Instant.parse("2026-10-07T00:00:00Z");
  private static final Instant BACK_END = Instant.parse("2026-10-07T23:59:59Z");

  @Autowired GrpcServerLifecycle grpc;
  @Autowired JdbcClient jdbc;

  private ManagedChannel channel;
  private SupplierGatewayGrpc.SupplierGatewayBlockingStub gateway;

  @BeforeAll
  void setUp() {
    channel = ManagedChannelBuilder.forAddress("localhost", grpc.port()).usePlaintext().build();
    gateway = SupplierGatewayGrpc.newBlockingStub(channel);
  }

  @AfterAll
  void tearDown() {
    channel.shutdownNow();
  }

  @Test
  void searchReturnsNormalizedOffersForTheWindow() {
    SearchAirResponse response = gateway.searchAir(search("BOS", "SEA", true));
    assertThat(response.getSearchSessionId()).startsWith("srch_");
    assertThat(response.getErrorsList()).isEmpty();
    assertThat(response.getOffersList()).hasSizeGreaterThan(10);
    for (Offer offer : response.getOffersList()) {
      assertThat(offer.getOfferId()).startsWith("off_");
      assertThat(offer.getProvider()).isEqualTo("sandbox-air");
      assertThat(offer.getProviderOfferId()).startsWith("SBX-");
      assertThat(offer.getTotal().getCurrency()).isEqualTo("USD");
      assertThat(offer.getTotal().getAmountMinor()).isPositive();
      assertThat(offer.getExpiresAt().getSeconds()).isGreaterThan(Instant.now().getEpochSecond());
      assertThat(offer.getAir().getOutbound().getSegments(0).getOrigin()).isEqualTo("BOS");
      assertThat(offer.getAir().getInbound().getSegmentsCount()).isPositive();
      assertThat(offer.getAir().getOutbound().getSegments(0).getDeparture().getSeconds())
          .isBetween(OUT.getEpochSecond(), OUT_END.getEpochSecond());
    }
    assertThat(response.getOffersList())
        .anyMatch(o -> o.getAir().getOutbound().getSegmentsCount() == 2);
  }

  @Test
  void oneWaySearchHasNoInbound() {
    SearchAirResponse response = gateway.searchAir(search("BOS", "SEA", false));
    assertThat(response.getOffersList())
        .isNotEmpty()
        .allSatisfy(o -> assertThat(o.getAir().hasInbound()).isFalse());
  }

  @Test
  void aBrokenSupplierIsReportedNotFatal() {
    SearchAirResponse response = gateway.searchAir(search("BOS", "ZZZ", false));
    assertThat(response.getOffersList()).isEmpty();
    assertThat(response.getErrorsList())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getProvider()).isEqualTo("sandbox-air");
              assertThat(e.getCode()).isEqualTo("UNAVAILABLE");
              assertThat(e.getRetryable()).isTrue();
            });
  }

  @Test
  void priceThenBookIsIdempotentByKeyAndCancellable() {
    Offer offer = gateway.searchAir(search("BOS", "SEA", true)).getOffers(0);
    PriceOfferResponse priced =
        gateway.priceOffer(
            PriceOfferRequest.newBuilder()
                .setCtx(ctx(""))
                .setProvider("sandbox-air")
                .setProviderOfferId(offer.getProviderOfferId())
                .build());
    assertThat(priced.getPriceChanged()).isFalse();
    assertThat(priced.getOffer().getTotal()).isEqualTo(offer.getTotal());
    assertThat(priced.getOffer().getAir().getOutbound()).isEqualTo(offer.getAir().getOutbound());

    String key = "trip_01ARZ3NDEKTSV4RRFFQ69G5FAV:CREATE-ORDER:1-" + UUID.randomUUID();
    CreateOrderRequest create =
        CreateOrderRequest.newBuilder()
            .setCtx(ctx(key))
            .setProvider("sandbox-air")
            .setProviderOfferId(offer.getProviderOfferId())
            .addPassengers(alice())
            .setPaymentToken("tok_visa_4242")
            .build();
    CreateOrderResponse first = gateway.createOrder(create);
    CreateOrderResponse second = gateway.createOrder(create);
    CreateOrderResponse third = gateway.createOrder(create);

    assertThat(first.getStatus()).isEqualTo(SupplierOrderStatus.CONFIRMED);
    assertThat(first.getExternalOrderId()).startsWith("SBX-");
    assertThat(first.getRecordLocator()).hasSize(6);
    assertThat(first.getTicketNumbersList()).hasSize(1);
    assertThat(first.getCharged()).isEqualTo(offer.getTotal());
    assertThat(second).isEqualTo(first);
    assertThat(third).isEqualTo(first);
    assertThat(
            jdbc.sql("SELECT count(*) FROM sandbox_order WHERE idempotency_key = :k")
                .param("k", key)
                .query(Long.class)
                .single())
        .isEqualTo(1L);

    CancelOrderResponse cancelled =
        gateway.cancelOrder(
            CancelOrderRequest.newBuilder()
                .setCtx(ctx(""))
                .setProvider("sandbox-air")
                .setExternalOrderId(first.getExternalOrderId())
                .build());
    assertThat(cancelled.getStatus()).isEqualTo(SupplierOrderStatus.CANCELLED);
    assertThat(cancelled.getRefund().getAmountMinor())
        .isBetween(offer.getTotal().getAmountMinor() - 7500, offer.getTotal().getAmountMinor());
    CancelOrderResponse again =
        gateway.cancelOrder(
            CancelOrderRequest.newBuilder()
                .setCtx(ctx(""))
                .setProvider("sandbox-air")
                .setExternalOrderId(first.getExternalOrderId())
                .build());
    assertThat(again.getStatus()).isEqualTo(SupplierOrderStatus.CANCELLED);
  }

  @Test
  void bookingFailuresAreFinalAndExplicit() {
    SearchAirResponse offers = gateway.searchAir(search("BOS", "SEA", true));
    Offer doomed =
        offers.getOffersList().stream()
            .filter(o -> providerSlot(o) == 13)
            .findFirst()
            .orElseThrow();
    assertThatThrownBy(
            () ->
                gateway.createOrder(
                    CreateOrderRequest.newBuilder()
                        .setCtx(ctx("k-" + UUID.randomUUID()))
                        .setProvider("sandbox-air")
                        .setProviderOfferId(doomed.getProviderOfferId())
                        .addPassengers(alice())
                        .setPaymentToken("tok")
                        .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(e.getStatus().getDescription()).contains("SEAT_NO_LONGER_AVAILABLE");
            });
    Offer fine = offers.getOffers(0);
    assertThatThrownBy(
            () ->
                gateway.createOrder(
                    CreateOrderRequest.newBuilder()
                        .setCtx(ctx("k-" + UUID.randomUUID()))
                        .setProvider("sandbox-air")
                        .setProviderOfferId(fine.getProviderOfferId())
                        .addPassengers(alice())
                        .setPaymentToken("decline")
                        .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getDescription()).contains("PAYMENT_DECLINED"));
    assertThatThrownBy(
            () ->
                gateway.createOrder(
                    CreateOrderRequest.newBuilder()
                        .setCtx(ctx(""))
                        .setProvider("sandbox-air")
                        .setProviderOfferId(fine.getProviderOfferId())
                        .addPassengers(alice())
                        .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    assertThatThrownBy(
            () ->
                gateway.priceOffer(
                    PriceOfferRequest.newBuilder()
                        .setCtx(ctx(""))
                        .setProvider("amadeus")
                        .setProviderOfferId("x")
                        .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    assertThatThrownBy(
            () ->
                gateway.changeOrder(
                    ChangeOrderRequest.newBuilder()
                        .setCtx(ctx(""))
                        .setProvider("sandbox-air")
                        .setExternalOrderId("SBX-x")
                        .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getDescription()).startsWith("IDEMPOTENCY_KEY_REQUIRED"));
  }

  @Test
  void requestsWithoutContextAreRejected() {
    assertThatThrownBy(
            () ->
                gateway.searchAir(
                    SearchAirRequest.newBuilder().setOrigin("BOS").setDestination("SEA").build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  // ------------------------------------------------------------------ helpers

  private static int providerSlot(Offer o) {
    String raw =
        new String(java.util.Base64.getUrlDecoder().decode(o.getProviderOfferId().substring(4)));
    return Integer.parseInt(raw.split("\\|")[5]);
  }

  private static SearchAirRequest search(String origin, String destination, boolean roundTrip) {
    SearchAirRequest.Builder b =
        SearchAirRequest.newBuilder()
            .setCtx(ctx(""))
            .setOrigin(origin)
            .setDestination(destination)
            .setPassengers(1)
            .addCabins(Cabin.ECONOMY)
            .addCabins(Cabin.BUSINESS)
            .setOutboundDeparture(
                TimeWindow.newBuilder().setNotBefore(ts(OUT)).setNotAfter(ts(OUT_END)));
    if (roundTrip) {
      b.setReturnDeparture(
          TimeWindow.newBuilder().setNotBefore(ts(BACK)).setNotAfter(ts(BACK_END)));
    }
    return b.build();
  }

  private static RequestContext ctx(String idempotencyKey) {
    return RequestContext.newBuilder()
        .setTenantId("acme")
        .setCorrelationId("trip_01ARZ3NDEKTSV4RRFFQ69G5FAV")
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
